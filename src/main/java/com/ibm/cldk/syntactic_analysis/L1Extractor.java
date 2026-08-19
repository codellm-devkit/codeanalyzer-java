package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Orchestrates L1: discovers a project's source roots, parses each file <em>with a symbol solver</em>
 * (so type resolution and erased signatures work), and builds one canonical schema v2 {@code module}
 * per file via {@link ModuleBuilder}.
 *
 * <p>The symbol solver is configured from the project itself, which is what lets types declared in
 * other files of the same project resolve to qualified names. Modules are keyed by the file's path
 * <em>relative to the project root</em>, normalised to {@code /} — the key must be stable across runs
 * and machines for caching and SDK lookups to work.
 */
public final class L1Extractor {

    private L1Extractor() {}

    /** Source roots that hold test data rather than analysable project code. */
    private static final String[] EXCLUDED_SOURCE_ROOTS = {
        Paths.get("src", "test", "resources").toString(),
        Paths.get("src", "it", "resources").toString(),
        Paths.get("src", "xdocs-examples").toString()
    };

    /**
     * Build the v2 symbol table for a project.
     *
     * @param projectRoot the project's root directory
     * @param appName the application name — the {@code can://java/<app>} segment of every id
     * @return modules keyed by relative file path, iterated in sorted key order for determinism
     */
    public static Map<String, JModule> extractAll(Path projectRoot, String appName) throws IOException {
        ParserConfiguration config = new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        SymbolSolverCollectionStrategy strategy = new SymbolSolverCollectionStrategy(config);
        ProjectRoot root = strategy.collect(projectRoot);
        String applicationId = CanId.applicationId(appName);

        // Collect into a sorted map first: source roots and directory listings are not ordered, and
        // `-j N` output must be byte-identical to `-j 1`.
        Map<String, JModule> modules = new TreeMap<>();
        for (SourceRoot sourceRoot : root.getSourceRoots()) {
            if (isExcluded(sourceRoot.getRoot(), projectRoot)) {
                continue;
            }
            sourceRoot.setParserConfiguration(config);
            for (ParseResult<CompilationUnit> parseResult : sourceRoot.tryToParse()) {
                if (parseResult.getResult().isEmpty()) {
                    Log.debug("Skipping unparsable file: " + parseResult.getProblems());
                    continue;
                }
                CompilationUnit cu = parseResult.getResult().get();
                if (cu.getStorage().isEmpty()) {
                    continue;
                }
                Path path = cu.getStorage().get().getPath();
                String fileKey = fileKey(projectRoot, path);
                // Read the file's own text rather than printing the AST: `span.bytes` must index the
                // real file, byte for byte.
                String source = Files.readString(path, StandardCharsets.UTF_8);
                L1BuildContext ctx = new L1BuildContext(applicationId, fileKey, source);
                modules.put(fileKey, new ModuleBuilder(ctx).build(cu));
            }
        }
        return new LinkedHashMap<>(modules);
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
