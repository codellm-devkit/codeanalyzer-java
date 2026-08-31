package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JDependency;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles the emitted {@link JDependency} list from every {@code dependency-manifest} artifact
 * {@code ArtifactDiscovery} found, then reconciles it against any lockfile. Mirrors
 * codeanalyzer-python's {@code artifacts/dependencies.py} two-step shape -- declare, then backfill
 * from the lock -- but drops its import-binding layer entirely: python needs an alias table because
 * a PyPI distribution name is not its import name, so a same-name guess is a genuine heuristic
 * worth making. A Java package is declared at the call site with no such naming mismatch, so the
 * analogous guess here would be strictly worse than emitting nothing -- {@code provides_imports}/
 * {@code unresolved_imports} are deliberately not ported.
 */
public final class DependencyView {

    private DependencyView() {}

    // The one basename ManifestParsers.parseLockPins recognizes today. Mirrors python's
    // _LOCK_BASENAMES tuple (three entries, one per format pip's ecosystem has); Gradle has one.
    private static final Set<String> LOCK_BASENAMES = Set.of("gradle.lockfile");

    /**
     * Assemble declared dependencies, reconcile them against lock pins, and set each artifact's
     * {@code extraction}. Mutates the artifacts' {@code extraction} in place; returns the sorted
     * dependency list.
     */
    public static List<JDependency> build(Path projectDir, Map<String, JArtifact> artifacts) {
        // Sorted once, iterated twice: both steps below must visit artifacts in a fixed order so
        // that a tie in the final (name, declaredIn) sort -- or which lock artifact wins a pin
        // collision -- resolves the same way on every run, independent of the caller's Map type.
        Set<String> paths = new TreeSet<>(artifacts.keySet());
        List<JDependency> deps = new ArrayList<>();

        // 1. Declared: every dependency-manifest artifact that is not itself a lockfile (locks are
        // handled by step 2 below, matching python's explicit _LOCK_BASENAMES skip here).
        for (String path : paths) {
            if (isLockfile(path)) {
                continue;
            }
            JArtifact art = artifacts.get(path);
            if (!art.getRoles().contains("dependency-manifest")) {
                continue;
            }
            String text = readFromDisk(projectDir, path);
            if (text == null) {
                // Unreadable/undecodable (deleted mid-run, permissions, bad encoding). Must not
                // call parseManifest with null: its pom.xml branch now propagates NPE on null text
                // (the checked-exception catch was narrowed in task 3), so this guard is the
                // precondition DependencyView owns as the first real caller -- flag and move on
                // rather than crash or silently fall back to a possibly stale `source`.
                art.setExtraction("partial");
                continue;
            }
            ManifestParsers.ParseResult result = ManifestParsers.parseManifest(path, text);
            art.setExtraction(result.partial ? "partial" : "full");
            for (ManifestParsers.RawDep raw : result.deps) {
                deps.add(declaredDependency(raw, art.getId()));
            }
        }

        // 2. Lock backfill: a pin matching a declared dependency (by group:name) sets its
        // lockedVersion; a pin with no declaration is itself emitted as a transitive dependency.
        // This is python's reconciliation (dependencies.py:181-186) and knowingly contradicts the
        // spec's "transitive out of scope" -- see .github#48.
        Map<String, String> pins = new HashMap<>();
        Map<String, String> pinLockArtifact = new HashMap<>();
        for (String path : paths) {
            if (!isLockfile(path)) {
                continue;
            }
            JArtifact art = artifacts.get(path);
            String text = readFromDisk(projectDir, path);
            if (text == null) {
                art.setExtraction("partial");
                continue;
            }
            Map<String, String> lockPins = ManifestParsers.parseLockPins(path, text);
            pins.putAll(lockPins);
            for (String key : lockPins.keySet()) {
                pinLockArtifact.put(key, art.getId());
            }
            // Real content that yields zero pins failed to parse (corrupt/unrecognized shape) --
            // "full" would be a false claim of clean extraction. A blank lock has nothing to
            // extract, which is not a failure, so it still counts as "full".
            art.setExtraction(!lockPins.isEmpty() || text.trim().isEmpty() ? "full" : "partial");
        }

        for (JDependency dep : deps) {
            String lockedVersion = pins.get(dep.getGroup() + ":" + dep.getName());
            if (lockedVersion != null) {
                dep.setLockedVersion(lockedVersion);
                TreeSet<String> prov = new TreeSet<>(dep.getProv());
                prov.add("lockfile");
                dep.setProv(new ArrayList<>(prov));
            }
        }
        Set<String> declaredKeys = new HashSet<>();
        for (JDependency dep : deps) {
            declaredKeys.add(dep.getGroup() + ":" + dep.getName());
        }
        for (String key : new TreeSet<>(pins.keySet())) {
            if (declaredKeys.contains(key)) {
                continue;
            }
            deps.add(lockOnlyDependency(key, pins.get(key), pinLockArtifact.get(key)));
        }

        deps.sort(Comparator.comparing(JDependency::getName).thenComparing(JDependency::getDeclaredIn));
        return deps;
    }

    private static JDependency declaredDependency(ManifestParsers.RawDep raw, String declaredInId) {
        JDependency dep = new JDependency();
        dep.setGroup(raw.group);
        dep.setName(raw.name);
        dep.setSpec(raw.spec);
        dep.setKind(raw.kind);
        dep.setExtras(raw.extras);
        dep.setDeclaredIn(declaredInId);
        dep.setProv(new ArrayList<>(List.of("declared")));
        return dep;
    }

    private static JDependency lockOnlyDependency(String groupColonName, String lockedVersion, String lockArtifactId) {
        JDependency dep = new JDependency();
        int colon = groupColonName.indexOf(':');
        dep.setGroup(groupColonName.substring(0, colon));
        dep.setName(groupColonName.substring(colon + 1));
        dep.setKind("runtime");
        dep.setDeclaredIn(lockArtifactId);
        dep.setDirect(false);
        dep.setLockedVersion(lockedVersion);
        dep.setProv(new ArrayList<>(List.of("lockfile")));
        return dep;
    }

    private static boolean isLockfile(String path) {
        return LOCK_BASENAMES.contains(basename(path));
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Read an artifact's full text straight from disk -- never from {@link JArtifact#getSource()}.
     * {@code source} is capped by {@code --artifact-text-max-bytes} and emptied outright by {@code
     * --no-artifact-text} (both payload-size controls on the emitted JSON, not extraction
     * controls), so extraction must not silently degrade under either flag. {@code null} means the
     * file could not be read or is not valid UTF-8; the caller marks that artifact {@code partial}
     * and skips it rather than handing unreliable text to a parser.
     */
    private static String readFromDisk(Path projectDir, String relativePath) {
        try {
            byte[] raw = Files.readAllBytes(projectDir.resolve(relativePath));
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
        } catch (IOException e) {
            return null;
        }
    }
}
