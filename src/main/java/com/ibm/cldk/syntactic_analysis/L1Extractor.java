package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.ibm.cldk.schema.CanId;
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
        Paths.get("src", "xdocs-examples").toString()
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
     * @param appName the application name — the {@code can://java/<app>} segment of every id
     * @param dependencyDir directory of dependency jars to put on the solver's path, or {@code null};
     *     missing or unreadable jars are skipped rather than failing the analysis
     * @return modules keyed by relative file path, iterated in sorted key order for determinism
     */
    public static Map<String, JModule> extractAll(
            Path projectRoot, String appName, Path dependencyDir, Map<String, JModule> cached)
            throws IOException {
        ParserConfiguration discovery = parserConfiguration();
        ProjectRoot root = new ParserCollectionStrategy(discovery).collect(projectRoot);

        List<SourceRoot> sourceRoots = new ArrayList<>();
        for (SourceRoot sourceRoot : root.getSourceRoots()) {
            if (!isExcluded(sourceRoot.getRoot(), projectRoot)) {
                sourceRoots.add(sourceRoot);
            }
        }

        ParserConfiguration config = parserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver(sourceRoots, dependencyDir, discovery)));

        // Collect into a sorted map first: directory listings are not ordered, and output must not
        // depend on traversal order.
        Map<String, JModule> modules = new TreeMap<>();
        String applicationId = CanId.applicationId(appName);
        JavaParser parser = new JavaParser(config);
        int reused = 0;
        for (SourceRoot sourceRoot : sourceRoots) {
            for (Path path : javaFilesUnder(sourceRoot.getRoot())) {
                String fileKey = fileKey(projectRoot, path);
                // Read the file's own text rather than printing the AST: `span.bytes` must index the
                // real file, byte for byte.
                String source = Files.readString(path, StandardCharsets.UTF_8);
                L1BuildContext ctx = new L1BuildContext(applicationId, fileKey, source);

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

    /** JDK + project sources + dependency jars, in that resolution order. */
    private static CombinedTypeSolver typeSolver(
            List<SourceRoot> sourceRoots, Path dependencyDir, ParserConfiguration config) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        // JRE types only. A classpath-wide ReflectionTypeSolver would resolve the *analyzer's* own
        // dependencies (WALA, Guava, JavaParser, ...) as if the analysed project depended on them,
        // silently inventing qualified names. Project types come from the source roots below, and
        // library types from the dependency jars.
        solver.add(new ReflectionTypeSolver());
        for (SourceRoot sourceRoot : sourceRoots) {
            solver.add(new JavaParserTypeSolver(sourceRoot.getRoot(), config));
        }
        int jars = 0;
        if (dependencyDir != null && Files.isDirectory(dependencyDir)) {
            try (Stream<Path> entries = Files.walk(dependencyDir)) {
                List<Path> jarFiles = entries
                        .filter(p -> p.toString().endsWith(".jar"))
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
                for (Path jar : jarFiles) {
                    try {
                        solver.add(new JarTypeSolver(jar));
                        jars++;
                    } catch (IOException e) {
                        // A corrupt or unreadable jar degrades resolution; it must not fail analysis.
                        Log.debug("Skipping unreadable dependency jar " + jar + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.warn("Could not scan dependency directory " + dependencyDir + ": " + e.getMessage());
            }
        }
        Log.debug("Type solver: " + sourceRoots.size() + " source root(s), " + jars + " dependency jar(s)");
        return solver;
    }

    /** The {@code symbol_table} key: path relative to the project root, always {@code /}-separated. */
    private static String fileKey(Path projectRoot, Path file) {
        Path relative = projectRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
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
