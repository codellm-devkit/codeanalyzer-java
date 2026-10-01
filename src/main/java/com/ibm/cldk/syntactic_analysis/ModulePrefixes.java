package com.ibm.cldk.syntactic_analysis;

import com.ibm.cldk.artifacts.ModuleCoordinates;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Resolves the build module each source file belongs to, and the {@code can://} id path that follows
 * from it: {@code <module coordinate>/<path relative to that module>}, or the bare
 * {@code --input}-relative path when no coordinate applies.
 *
 * <p><b>This shapes the id only.</b> The {@code symbol_table} key stays the real
 * {@code --input}-relative path, because the key's job is uniqueness and a path is unique by
 * construction where a coordinate is not — and because the key and the id are the only things in
 * the output that locate a file on disk.
 *
 * <p><b>The coordinate is applied only when exactly one module directory claims it.</b> The id is
 * the join key for call-graph endpoints, so a coordinate claimed by two directories would merge two
 * distinct callables onto one node — the same silent loss, one level up. Vendored duplicates are
 * where this bites: several services each building one shared internal library under the same module
 * name. A contested coordinate is therefore applied to none of its claimants, which keeps "ids are
 * distinct by construction" true rather than trading it away for a nicer spelling.
 *
 * <p>The search for a manifest walks up from each file and <b>stops at {@code --input}</b>, so a
 * manifest above the analysis root is invisible and cannot be mistaken for the enclosing module of a
 * tree that merely happens to sit inside one.
 */
public final class ModulePrefixes {

    private final Path inputRoot;

    /** File → its module directory, for files whose coordinate survived the uniqueness check. */
    private final Map<Path, Path> moduleDirByFile;

    /** Module directory → the coordinate applied to it. */
    private final Map<Path, String> coordinateByModuleDir;

    /** Coordinate → the competing module directories that claimed it, in path order. */
    private final Map<String, List<Path>> contested;

    private ModulePrefixes(
            Path inputRoot,
            Map<Path, Path> moduleDirByFile,
            Map<Path, String> coordinateByModuleDir,
            Map<String, List<Path>> contested) {
        this.inputRoot = inputRoot;
        this.moduleDirByFile = moduleDirByFile;
        this.coordinateByModuleDir = coordinateByModuleDir;
        this.contested = contested;
    }

    /**
     * Resolve every file's module, then drop any coordinate claimed by more than one module
     * directory.
     *
     * @param inputRoot the analysis root; the manifest search never looks above it
     * @param files the source files being analysed
     */
    public static ModulePrefixes resolve(Path inputRoot, Collection<Path> files) {
        Path root = inputRoot.toAbsolutePath().normalize();

        // Memoized per directory: a module of a few hundred files would otherwise re-read and
        // re-parse its pom once per file.
        Map<Path, Optional<Path>> moduleDirByDirectory = new HashMap<>();
        Map<Path, String> coordinateByModuleDir = new HashMap<>();
        Map<Path, Path> moduleDirByFile = new LinkedHashMap<>();

        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            Path directory = absolute.getParent();
            if (directory == null) {
                continue;
            }
            Optional<Path> moduleDir = moduleDirByDirectory.computeIfAbsent(
                    directory, d -> nearestModuleDir(d, root, coordinateByModuleDir));
            moduleDir.ifPresent(dir -> moduleDirByFile.put(absolute, dir));
        }

        // One coordinate, two or more directories: apply it to neither.
        Map<String, Set<Path>> claimants = new TreeMap<>();
        coordinateByModuleDir.forEach(
                (dir, coordinate) -> claimants.computeIfAbsent(coordinate, c -> new TreeSet<>()).add(dir));

        Map<String, List<Path>> contested = new LinkedHashMap<>();
        claimants.forEach((coordinate, dirs) -> {
            if (dirs.size() > 1) {
                contested.put(coordinate, List.copyOf(dirs));
                dirs.forEach(coordinateByModuleDir::remove);
            }
        });
        moduleDirByFile.values().removeIf(dir -> !coordinateByModuleDir.containsKey(dir));

        return new ModulePrefixes(
                root,
                Collections.unmodifiableMap(moduleDirByFile),
                Collections.unmodifiableMap(coordinateByModuleDir),
                Collections.unmodifiableMap(contested));
    }

    /**
     * Walk up from {@code directory} to {@code root} inclusive; the first directory declaring a
     * coordinate is the file's module. Nearest wins, so a reactor child carries its own coordinate
     * rather than the aggregator's.
     */
    private static Optional<Path> nearestModuleDir(
            Path directory, Path root, Map<Path, String> coordinateByModuleDir) {
        for (Path current = directory;
                current != null && current.startsWith(root);
                current = current.getParent()) {
            String known = coordinateByModuleDir.get(current);
            if (known != null) {
                return Optional.of(current);
            }
            Optional<String> coordinate = ModuleCoordinates.nameOf(current);
            if (coordinate.isPresent()) {
                coordinateByModuleDir.put(current, coordinate.get());
                return Optional.of(current);
            }
            if (current.equals(root)) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * The id path for {@code file} — what follows the language segment of its {@code can://} id.
     * Prefixed with the module coordinate when one applies, otherwise the bare
     * {@code --input}-relative path.
     */
    public String idPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path moduleDir = moduleDirByFile.get(absolute);
        if (moduleDir == null) {
            return posix(inputRoot.relativize(absolute));
        }
        return coordinateByModuleDir.get(moduleDir) + "/" + posix(moduleDir.relativize(absolute));
    }

    /**
     * Coordinates that were claimed by several module directories and therefore applied to none.
     * The caller warns on these; an empty map is the ordinary case.
     */
    public Map<String, List<Path>> contested() {
        return contested;
    }

    private static String posix(Path path) {
        return path.toString().replace('\\', '/');
    }
}
