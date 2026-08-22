package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

/**
 * Parses test sources with a symbol solver attached, so the builders exercise the same resolution path
 * they use in production (qualified type names, erased signatures). Without this the builders silently
 * fall back to AST spellings and the tests would not cover resolution at all.
 */
final class TestParsers {

    private TestParsers() {}

    static CompilationUnit parseResolved(String source) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setStoreTokens(true)
                .setAttributeComments(true)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new JavaParser(config).parse(source).getResult().orElseThrow();
    }
}
