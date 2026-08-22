package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.schema.JComment;
import com.ibm.cldk.schema.JImport;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        module.setSpan(ctx.wholeFileSpan());
        module.setPackageName(cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(""));
        module.setSource(ctx.getSource());
        module.setContentHash(ctx.contentHash());

        // File-level comments: the unit's own comment plus orphans (e.g. a licence header that is not
        // attached to any declaration). Declaration comments live on their own nodes.
        List<JComment> comments = new ArrayList<>(ctx.commentsOf(cu));
        cu.getAllComments().stream()
                .filter(c -> c.getCommentedNode().isEmpty())
                .forEach(c -> comments.add(ctx.comment(c)));
        module.setComments(comments);

        List<JImport> imports = new ArrayList<>();
        for (ImportDeclaration id : cu.getImports()) {
            JImport imp = new JImport();
            String path = id.getNameAsString();
            imp.setPath(path);
            imp.setName(path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path);
            imp.setStatic(id.isStatic());
            imp.setWildcard(id.isAsterisk());
            imp.setSpan(ctx.spanOf(id));
            imports.add(imp);
        }
        module.setImports(imports);

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
