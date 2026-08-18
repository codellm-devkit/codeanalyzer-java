package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a canonical schema v2 {@code module} node from a JavaParser {@link CompilationUnit}. Owns
 * only module-level concerns (id, package, source, imports) and delegates each declared type to
 * {@code TypeBuilder}; it does not inline type/callable walking.
 */
public final class ModuleBuilder {

    private final L1BuildContext ctx;

    public ModuleBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
    }

    public JModule build(CompilationUnit cu) {
        JModule module = new JModule();
        module.setId(ctx.moduleId());
        module.setPackageName(cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(""));
        module.setSource(ctx.getSource());

        // Top-level types, keyed by simple name and sorted for deterministic output (the -j gate).
        TypeBuilder typeBuilder = new TypeBuilder(ctx);
        Map<String, JType> types = new TreeMap<>();
        for (TypeDeclaration<?> td : cu.getTypes()) {
            types.put(td.getNameAsString(), typeBuilder.build(td, module.getId()));
        }
        module.setTypes(new LinkedHashMap<>(types));
        return module;
    }
}
