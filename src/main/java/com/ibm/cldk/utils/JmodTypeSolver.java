package com.ibm.cldk.utils;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javassist.ClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;

/**
 * A JavaParser {@link TypeSolver} that resolves types by reading {@code .class}
 * bytecode straight out of zip archives (the JDK's {@code .jmod} files and a
 * project's dependency jars), using Javassist with a custom, reflection-free
 * {@link ClassPath} backed by {@link ZipFile}.
 *
 * <p><b>Why this exists.</b> The native-image build cannot register the full JDK
 * reflection surface (doing so drags deleted/hosted-only types into the image
 * heap and aborts the build), so a {@code ReflectionTypeSolver} silently fails
 * to resolve unregistered JDK types in the native binary, and javassist's
 * built-in {@code JarClassPath} (used by {@code JarTypeSolver} for dependency
 * jars) likewise fails to read class bytes under native-image. Both make the
 * native output diverge from {@code java -jar} (empty callee signatures/return
 * types, missing CRUD detection). Reading bytecode through this class's own
 * {@code ZipFile}-based {@link ClassPath} works identically in the JVM and the
 * native image — the same way WALA already reads the JDK from the jmods.
 *
 * <p>jmod entries live under a {@code classes/} prefix
 * (e.g. {@code classes/java/lang/String.class}); ordinary jar entries do not.
 * Each indexed class records its exact archive and entry name, so lookups are
 * prefix-agnostic.
 */
public class JmodTypeSolver implements TypeSolver {

    private static final String CLASS_SUFFIX = ".class";
    private static final String JMOD_PREFIX = "classes/";

    private TypeSolver parent;

    private final ClassPool classPool;
    /** lookup key (canonical {@code $}-nested name and dotted alias) -> bytecode location. */
    private final Map<String, Location> index = new HashMap<>();
    /** archive -> open zip handle, kept open for the solver's lifetime. */
    private final Map<Path, ZipFile> openZips = new HashMap<>();

    private record Location(Path archive, String entryName, String classPoolName) {
    }

    private JmodTypeSolver() {
        this.classPool = new ClassPool();
        this.classPool.appendClassPath(new ZipClassPath());
    }

    /**
     * Build a solver over the JDK jmods, or return {@code null} when no jmods
     * are available (e.g. a JRE-only dev environment), in which case callers
     * should fall back to reflection.
     */
    public static JmodTypeSolver tryCreate() {
        Path jmodsDir;
        try {
            jmodsDir = ScopeUtils.resolveJmodsDir();
        } catch (RuntimeException e) {
            Log.warn("JmodTypeSolver disabled: " + e.getMessage());
            return null;
        }
        try {
            JmodTypeSolver solver = new JmodTypeSolver();
            try (var stream = Files.walk(jmodsDir)) {
                for (Path jmod : stream.filter(p -> p.toString().endsWith(".jmod"))
                        .collect(Collectors.toList())) {
                    solver.indexArchive(jmod, JMOD_PREFIX);
                }
            }
            Log.info("JmodTypeSolver indexed " + solver.index.size()
                    + " JDK types from " + jmodsDir);
            return solver;
        } catch (IOException e) {
            Log.warn("JmodTypeSolver disabled: failed to index jmods at " + jmodsDir
                    + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Index additional dependency jars (classes at archive root, no prefix) so
     * the solver can resolve a project's third-party API types from bytecode.
     * Unreadable jars are skipped with a warning.
     */
    public void addJars(List<Path> jars) {
        int before = index.size();
        for (Path jar : jars) {
            try {
                indexArchive(jar, "");
            } catch (IOException e) {
                Log.warn("Skipping unreadable jar for symbol resolution: " + jar
                        + " (" + e.getMessage() + ")");
            }
        }
        Log.info("JmodTypeSolver indexed " + (index.size() - before)
                + " additional dependency-type aliases from " + jars.size() + " jar(s)");
    }

    private void indexArchive(Path archive, String prefix) throws IOException {
        ZipFile zip = new ZipFile(archive.toFile());
        openZips.put(archive, zip);
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            String internal = name.substring(prefix.length(), name.length() - CLASS_SUFFIX.length());
            if (internal.endsWith("module-info") || internal.endsWith("package-info")) {
                continue;
            }
            String classPoolName = internal.replace('/', '.');
            Location location = new Location(archive, name, classPoolName);
            // First archive to define a type wins (the JDK is indexed before jars).
            index.putIfAbsent(classPoolName, location);
            // Allow lookups that use '.' for nested types (e.g. Map.Entry).
            index.putIfAbsent(classPoolName.replace('$', '.'), location);
        }
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        Location location = index.get(name);
        if (location == null) {
            return SymbolReference.unsolved();
        }
        try {
            CtClass ctClass = classPool.get(location.classPoolName());
            return SymbolReference.solved(JavassistFactory.toTypeDeclaration(ctClass, getRoot()));
        } catch (NotFoundException e) {
            return SymbolReference.unsolved();
        }
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        if (parent == this) {
            throw new IllegalStateException("The parent of this type solver cannot be itself.");
        }
        this.parent = parent;
    }

    /** Javassist class path backed by the indexed zip archives. */
    private final class ZipClassPath implements ClassPath {
        @Override
        public InputStream openClassfile(String classname) throws NotFoundException {
            Location location = index.get(classname);
            if (location == null) {
                return null;
            }
            ZipFile zip = openZips.get(location.archive());
            ZipEntry entry = zip == null ? null : zip.getEntry(location.entryName());
            if (entry == null) {
                return null;
            }
            try {
                return zip.getInputStream(entry);
            } catch (IOException e) {
                throw new NotFoundException(classname, e);
            }
        }

        @Override
        public URL find(String classname) {
            Location location = index.get(classname);
            if (location == null) {
                return null;
            }
            try {
                return location.archive().toUri().toURL();
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }

    /** @return the type names this solver can resolve. */
    public Set<String> getKnownClasses() {
        return index.keySet();
    }
}
