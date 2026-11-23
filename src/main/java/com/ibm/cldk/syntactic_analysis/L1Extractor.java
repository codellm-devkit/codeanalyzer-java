package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.FqnIndex;
import com.ibm.cldk.schema.JEntrypointReport;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.utils.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Orchestrates L1: discovers a project's source roots, parses each file <em>with a symbol solver</em>
 * (so type resolution and erased signatures work), and builds one canonical schema v2 {@code module}
 * per file via {@link ModuleBuilder}.
 *
 * <p>The type solver is assembled explicitly from three sources, because resolution quality is what
 * makes L1 type fields useful: the JDK (reflection), the project's own source roots, and — crucially —
 * the project's <b>library dependencies</b>. Without the dependency jars, third-party types degrade to
 * bare spellings ({@code Model} instead of {@code org.springframework.ui.Model}), which loses exactly
 * the qualified names downstream consumers join on.
 *
 * <p>Modules are keyed by the file's path <em>relative to the project root</em>, normalised to
 * {@code /} — the key must be stable across runs and machines for caching and SDK lookups to work.
 */
public final class L1Extractor {

    private L1Extractor() {}

    /** Source roots that hold test data rather than analysable project code. */
    private static final String[] EXCLUDED_SOURCE_ROOTS = {
        Paths.get("src", "test", "resources").toString(),
        Paths.get("src", "it", "resources").toString(),
        Paths.get("src", "xdocs-examples").toString(),
        // Gradle's processResources copies src/test/resources into build/resources; those copies are
        // build output, not project code.
        Paths.get("build", "resources").toString()
    };

    /** Analyse a project with no library dependencies available (JDK + project sources only). */
    public static Map<String, JModule> extractAll(Path projectRoot, String appName) throws IOException {
        return extractAll(projectRoot, appName, null);
    }

    /** Analyse a project without reusing any cached modules. */
    public static Map<String, JModule> extractAll(Path projectRoot, String appName, Path dependencyDir)
            throws IOException {
        return extractAll(projectRoot, appName, dependencyDir, new LinkedHashMap<>());
    }

    /**
     * Build the v2 symbol table for a project.
     *
     * @param projectRoot the project's root directory
     * @param appName the application name — the {@code can://<app>} prefix of every id
     * @param dependencyDir directory of dependency jars to put on the solver's path, or {@code null};
     *     missing or unreadable jars are skipped rather than failing the analysis
     * @return modules keyed by relative file path, iterated in sorted key order for determinism
     */
    public static Map<String, JModule> extractAll(
            Path projectRoot, String appName, Path dependencyDir, Map<String, JModule> cached)
            throws IOException {
        return extractAll(projectRoot, appName, dependencyDir, cached, 1, 3);
    }

    /**
     * Build the v2 symbol table, additionally running the L3 dataflow pass at parse time when
     * {@code analysisLevel >= 3} and {@code l3Engine} is {@code "ast"}. The AST L3 engine needs
     * the live {@code BlockStmt} and symbol solver, which exist only during this pass;
     * {@code graphFieldDepth} is the DDG access-path bound k.
     */
    public static Map<String, JModule> extractAll(
            Path projectRoot, String appName, Path dependencyDir, Map<String, JModule> cached,
            int analysisLevel, int graphFieldDepth)
            throws IOException {
        return extractAll(projectRoot, appName, dependencyDir, cached, analysisLevel, graphFieldDepth, "ast");
    }

    /**
     * Build the v2 symbol table with explicit L3 engine selection. When {@code l3Engine} is
     * {@code "wala"}, the parse-time L3 hook in {@code CallableBuilder} is suppressed; the WALA
     * post-build pass ({@code L3WalaOverlays}) will apply overlays after this method returns.
     */
    public static Map<String, JModule> extractAll(
            Path projectRoot, String appName, Path dependencyDir, Map<String, JModule> cached,
            int analysisLevel, int graphFieldDepth, String l3Engine)
            throws IOException {
        return extractAll(projectRoot, appName, dependencyDir, cached, analysisLevel, graphFieldDepth,
                l3Engine, new JEntrypointReport());
    }

    /**
     * As above, accumulating the entrypoint pass's failures into the caller's {@code entrypointReport}
     * — an IN parameter rather than a second return value, so the map-returning signature above stays
     * the one every caller already uses. It records only what happened in THIS run: a module served
     * from the cache runs no finders, so it contributes nothing here (its per-node
     * {@code entrypoint_frameworks} still carries the attribution, which is why the application-level
     * {@code frameworks_detected} is a union over the tree rather than a tally kept here).
     */
    public static Map<String, JModule> extractAll(
            Path projectRoot, String appName, Path dependencyDir, Map<String, JModule> cached,
            int analysisLevel, int graphFieldDepth, String l3Engine, JEntrypointReport entrypointReport)
            throws IOException {
        return extractAllForApplication(projectRoot, CanId.applicationId(appName), dependencyDir, cached, analysisLevel,
                graphFieldDepth, l3Engine, entrypointReport);
    }

    /** Extract application-relative modules under an already validated canonical application root. */
    public static Map<String, JModule> extractAllForApplication(
            Path projectRoot, String applicationId, Path dependencyDir, Map<String, JModule> cached,
            int analysisLevel, int graphFieldDepth, String l3Engine, JEntrypointReport entrypointReport)
            throws IOException {
        // JavaParser's collection strategy silently finds zero source roots for a path with a `.`
        // element (`-i .` arrives here as exactly that), so normalize before discovery.
        projectRoot = projectRoot.toAbsolutePath().normalize();
        ParserConfiguration discovery = parserConfiguration();
        ProjectRoot root = new ParserCollectionStrategy(discovery).collect(projectRoot);

        List<SourceRoot> sourceRoots = new ArrayList<>();
        for (SourceRoot sourceRoot : root.getSourceRoots()) {
            if (!isExcluded(sourceRoot.getRoot(), projectRoot)) {
                sourceRoots.add(sourceRoot);
            }
        }
        // ProjectRoot lists roots in hash order of their absolute paths, so the order changed with
        // the checkout location. Sorted on the /-separated relative path, the same on every OS and
        // the order L2 breaks ties in, since a node id spells that path (#269).
        Path keyRoot = projectRoot;
        sourceRoots.sort(java.util.Comparator.comparing(sr -> fileKey(keyRoot, sr.getRoot())));
        List<String> rootKeys = new ArrayList<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            rootKeys.add(fileKey(keyRoot, sourceRoot.getRoot()));
        }

        // One resolution order per source root, its own root first (#269): a type declared in
        // several roots (vendored copies of a shared library) then binds each caller to its own
        // copy, not to whichever root a single shared solver happened to try first.
        Solvers solvers = typeSolvers(sourceRoots, rootKeys, dependencyDir, discovery);

        // Collect into a sorted map first: directory listings are not ordered, and output must not
        // depend on traversal order.
        Map<String, JModule> modules = new TreeMap<>();
        int reused = 0;
        for (int r = 0; r < sourceRoots.size(); r++) {
            SourceRoot sourceRoot = sourceRoots.get(r);
            JavaParser parser = new JavaParser(parserConfiguration()
                    .setSymbolResolver(new JavaSymbolSolver(solvers.orderedFor(r))));
            for (Path path : javaFilesUnder(sourceRoot.getRoot())) {
                String fileKey = fileKey(projectRoot, path);
                // Read the file's own text rather than printing the AST: `span.bytes` must index the
                // real file, byte for byte.
                String source = Files.readString(path, StandardCharsets.UTF_8);
                L1BuildContext ctx = new L1BuildContext(
                        applicationId, fileKey, source, analysisLevel, graphFieldDepth, l3Engine,
                        entrypointReport);

                // Reuse the cached module when the file is byte-for-byte what it was last time. This
                // skips the parse as well as the build, which is where the cost is.
                JModule cachedModule = cached.get(fileKey);
                if (cachedModule != null && ctx.contentHash().equals(cachedModule.getContentHash())) {
                    modules.put(fileKey, cachedModule);
                    reused++;
                    continue;
                }

                ParseResult<CompilationUnit> parseResult = parser.parse(path);
                if (parseResult.getResult().isEmpty()) {
                    Log.warn("Skipping unparsable file " + path + ": " + parseResult.getProblems());
                    continue;
                }
                // A file can yield a usable AST *despite* parse problems: JavaParser recovers by
                // replacing what it could not parse (a whole method body, say) with an error node. The
                // module then looks structurally complete while the recovered region's call sites, locals
                // and local types are simply absent — and nothing downstream, the strict JSON conformance
                // gate included, can tell that from a genuinely empty method. Emitting it beats dropping
                // the file, but it must be said out loud.
                if (!parseResult.isSuccessful()) {
                    Log.warn("Partially parsed " + path + " — facts in the unparsed region(s) are missing: "
                            + parseResult.getProblems());
                }
                modules.put(fileKey, new ModuleBuilder(ctx).build(parseResult.getResult().get()));
            }
        }
        if (!cached.isEmpty()) {
            Log.debug("Reused " + reused + " of " + modules.size() + " modules from cache");
        }
        // Source-root discovery uses JavaParser's own stricter check, so a single file with a parse
        // problem can make it abandon the directory that contains it. Exiting 0 with an empty symbol
        // table would look like "this project has no code" rather than "discovery found nothing".
        if (modules.isEmpty()) {
            Log.warn("No Java modules were built under " + projectRoot + " (" + sourceRoots.size()
                    + " source root(s) discovered) — the symbol table will be empty");
        }
        return new LinkedHashMap<>(modules);
    }

    /** Java sources under a source root, in sorted order so traversal cannot affect output. */
    private static List<Path> javaFilesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private static ParserConfiguration parserConfiguration() {
        return new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    /**
     * The type solvers of an application: one per source root and one per dependency jar, each
     * built once, and arranged per source root by {@link #orderedFor}.
     */
    private static final class Solvers {
        final List<TypeSolver> roots;
        final List<String> rootKeys;
        final List<TypeSolver> jars;

        Solvers(List<TypeSolver> roots, List<String> rootKeys, List<TypeSolver> jars) {
            this.roots = roots;
            this.rootKeys = rootKeys;
            this.jars = jars;
            // A solver's parent is what it resolves the insides of a found declaration through
            // (its supertypes, its members' types). That is the application-wide order; the
            // per-root orders below decide only which copy a caller's reference finds (#269). So a
            // chained call through a found copy (copy.helper().tag()) resolves helper()'s return type
            // application-wide, a known limitation: lifting it needs a parse cache per source root.
            CombinedTypeSolver application = new CombinedTypeSolver();
            application.add(new ReflectionTypeSolver());
            roots.forEach(application::add);
            jars.forEach(application::add);
        }

        /**
         * JDK, then source root {@code own}, then the other roots nearest it first (the most leading
         * path segments in common, so a sibling of the same service before another service), ties
         * in path order, then the jars. That is the rule L2 binds a call by ({@link FqnIndex}), so
         * the two levels pick the same copy when the caller's own root has none (#269).
         */
        CombinedTypeSolver orderedFor(int own) {
            CombinedTypeSolver solver = new CombinedTypeSolver();
            // JRE types only. A classpath-wide ReflectionTypeSolver would resolve the *analyzer's*
            // own dependencies (WALA, Guava, JavaParser, ...) as if the analysed project depended
            // on them, silently inventing qualified names.
            solver.add(new ReflectionTypeSolver());
            solver.add(new SharedTypeSolver(roots.get(own)));
            List<Integer> others = new ArrayList<>();
            for (int r = 0; r < roots.size(); r++) {
                if (r != own) {
                    others.add(r);
                }
            }
            String ownKey = rootKeys.get(own);
            // Stable sort over path order: equal nearness keeps the roots in path order.
            others.sort(java.util.Comparator.comparingInt(
                    (Integer r) -> -FqnIndex.commonSegments(rootKeys.get(r), ownKey)));
            others.forEach(r -> solver.add(new SharedTypeSolver(roots.get(r))));
            jars.forEach(jar -> solver.add(new SharedTypeSolver(jar)));
            return solver;
        }
    }

    /**
     * A solver that answers for a shared one. A {@link TypeSolver} takes a single parent, so the one
     * solver per source root (and its parse cache) can sit in every root's order only behind one of
     * these: the wrapper takes the order as its parent, the shared solver keeps its own.
     */
    private static final class SharedTypeSolver implements TypeSolver {
        private final TypeSolver delegate;
        private TypeSolver parent;

        SharedTypeSolver(TypeSolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public TypeSolver getParent() {
            return parent;
        }

        @Override
        public void setParent(TypeSolver parent) {
            this.parent = parent;
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            return delegate.tryToSolveType(name);
        }
    }

    /** Project sources and dependency jars, each solver built once (see {@link Solvers}). */
    private static Solvers typeSolvers(List<SourceRoot> sourceRoots, List<String> rootKeys,
            Path dependencyDir, ParserConfiguration config) {
        List<TypeSolver> roots = new ArrayList<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            roots.add(new JavaParserTypeSolver(sourceRoot.getRoot(), config));
        }
        List<TypeSolver> jarSolvers = new ArrayList<>();
        if (dependencyDir != null && Files.isDirectory(dependencyDir)) {
            try (Stream<Path> entries = Files.walk(dependencyDir)) {
                List<Path> jarFiles = entries
                        .filter(p -> p.toString().endsWith(".jar"))
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
                for (Path jar : jarFiles) {
                    try {
                        jarSolvers.add(new JarTypeSolver(jar));
                    } catch (IOException e) {
                        // A corrupt or unreadable jar degrades resolution; it must not fail analysis.
                        Log.debug("Skipping unreadable dependency jar " + jar + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.warn("Could not scan dependency directory " + dependencyDir + ": " + e.getMessage());
            }
        }
        Log.debug("Type solver: " + sourceRoots.size() + " source root(s), " + jarSolvers.size()
                + " dependency jar(s)");
        return new Solvers(roots, rootKeys, jarSolvers);
    }

    /**
     * Top-level qualified type names declared in more than one file, each with its files in sorted
     * order (#269). Two source roots that vendor one library are the common case; each caller then
     * resolves against its own root's copy first, but the name itself is ambiguous to a consumer.
     */
    public static Map<String, List<String>> duplicateTypeNames(Map<String, JModule> modules) {
        Map<String, List<String>> files = new TreeMap<>();
        for (Map.Entry<String, JModule> e : modules.entrySet()) {
            String pkg = e.getValue().getPackageName();
            for (String simple : e.getValue().getTypes().keySet()) {
                String fqn = pkg == null || pkg.isEmpty() ? simple : pkg + "." + simple;
                files.computeIfAbsent(fqn, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        files.values().removeIf(f -> f.size() < 2);
        files.values().forEach(java.util.Collections::sort);
        return files;
    }

    /** The {@code symbol_table} key: path relative to the key root, always {@code /}-separated. */
    private static String fileKey(Path keyRoot, Path file) {
        Path relative = keyRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/');
    }

    private static boolean isExcluded(Path sourceRoot, Path projectRoot) {
        Path relative = projectRoot.toAbsolutePath().relativize(sourceRoot.toAbsolutePath());
        for (String excluded : EXCLUDED_SOURCE_ROOTS) {
            if (Pattern.compile(Pattern.quote(excluded)).matcher(relative.toString()).find()) {
                return true;
            }
        }
        return false;
    }
}
