package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JMetrics;
import com.ibm.cldk.schema.JRefs;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JVariableDeclaration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds a v2 {@code callable} node from a JavaParser {@link CallableDeclaration}: the type-erasure
 * {@code signature} + containment {@code id}, {@code parameters}, {@code return_type}, the
 * {@code error_channel} (declared {@code throws}), {@code modifiers}, structured {@code decorators},
 * nested {@code metrics}/{@code refs}, the L1 {@code body} {@code call} nodes, and local classes
 * under {@code types} (containment, design decision D4). Delegates each concern to its focused
 * builder ({@link ParameterBuilder}, {@link CallSiteBuilder}, {@link TypeBuilder}, {@link DecoratorBuilder}).
 */
public final class CallableBuilder {

    private final L1BuildContext ctx;
    private final ParameterBuilder parameterBuilder;
    private final DecoratorBuilder decoratorBuilder;
    private final CallSiteBuilder callSiteBuilder;

    public CallableBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.parameterBuilder = new ParameterBuilder(ctx);
        this.decoratorBuilder = new DecoratorBuilder(ctx);
        this.callSiteBuilder = new CallSiteBuilder(ctx);
        // TypeBuilder is constructed lazily in localClasses() to break the callable<->type
        // construction cycle (a type builds callables; a callable builds its local-class types).
    }

    /**
     * @param cd the callable declaration
     * @param parentTypeId the containing type's id
     * @param classFieldNames simple names of the enclosing type's fields (for {@code refs.fields})
     */
    public JCallable build(CallableDeclaration<?> cd, String parentTypeId, List<String> classFieldNames) {
        JCallable callable = new JCallable();
        String signature = Signatures.typeErasure(cd);
        callable.setSignature(signature);
        callable.setId(CanId.childId(parentTypeId, signature));
        callable.setKind(cd instanceof MethodDeclaration ? "method" : "constructor");
        callable.setSpan(ctx.spanOf(cd));
        callable.setParameters(
                cd.getParameters().stream().map(parameterBuilder::build).collect(Collectors.toList()));
        callable.setReturnType(
                cd instanceof MethodDeclaration ? ctx.resolveType(((MethodDeclaration) cd).getType()) : null);
        callable.setErrorChannel(
                cd.getThrownExceptions().stream().map(ctx::resolveType).collect(Collectors.toList()));
        callable.setModifiers(
                cd.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList()));
        callable.setComments(ctx.commentsOf(cd));
        callable.setDecorators(
                cd.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));

        // `declaration` mirrors v1: modifiers + return type + name + parameter names, no body.
        callable.setDeclaration(cd.getDeclarationAsString(true, true, true).strip());

        JMetrics metrics = new JMetrics();
        metrics.setCyclomatic(cyclomaticComplexity(cd));
        callable.setMetrics(metrics);

        Optional<BlockStmt> body = bodyOf(cd);
        body.flatMap(b -> b.getRange().map(r -> r.begin.line)).ifPresent(callable::setCodeStartLine);
        callable.setRefs(refs(body, classFieldNames));
        body.ifPresent(b -> callable.setLocalVariables(localVariables(b)));
        body.ifPresent(b -> callable.setBody(callSiteBuilder.build(b)));
        body.ifPresent(b -> callable.setTypes(localClasses(b, callable.getId())));
        return callable;
    }

    /**
     * Build an initializer block as a {@code callable} with {@code kind:"initializer"}. It has no
     * parameters, return type or declared throws; everything else (body call sites, locals, refs,
     * metrics, local classes) works exactly as for a method.
     */
    public JCallable buildInitializer(
            InitializerDeclaration id, String parentTypeId, List<String> classFieldNames, String signature) {
        JCallable callable = new JCallable();
        callable.setSignature(signature);
        callable.setId(CanId.childId(parentTypeId, signature));
        callable.setKind("initializer");
        callable.setSpan(ctx.spanOf(id));
        callable.setComments(ctx.commentsOf(id));
        callable.setModifiers(
                id.isStatic() ? List.of("static") : List.of());
        callable.setDecorators(
                id.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));

        JMetrics metrics = new JMetrics();
        metrics.setCyclomatic(cyclomaticComplexity(id));
        callable.setMetrics(metrics);

        BlockStmt body = id.getBody();
        body.getRange().map(r -> r.begin.line).ifPresent(callable::setCodeStartLine);
        callable.setRefs(refs(Optional.of(body), classFieldNames));
        callable.setLocalVariables(localVariables(body));
        callable.setBody(callSiteBuilder.build(body));
        callable.setTypes(localClasses(body, callable.getId()));
        return callable;
    }

    private static Optional<BlockStmt> bodyOf(CallableDeclaration<?> cd) {
        if (cd instanceof MethodDeclaration) {
            return ((MethodDeclaration) cd).getBody();
        }
        return Optional.of(((ConstructorDeclaration) cd).getBody());
    }

    /** Locals declared directly in this body, in source order (nested classes' locals are theirs). */
    private List<JVariableDeclaration> localVariables(BlockStmt body) {
        List<JVariableDeclaration> locals = new ArrayList<>();
        for (VariableDeclarator vd : body.findAll(VariableDeclarator.class)) {
            if (!CallSiteBuilder.belongsDirectlyTo(vd, body)) {
                continue;
            }
            JVariableDeclaration local = new JVariableDeclaration();
            local.setName(vd.getNameAsString());
            local.setType(ctx.resolveType(vd.getType()));
            vd.getInitializer().ifPresent(init -> local.setInitializer(init.toString()));
            local.setSpan(ctx.spanOf(vd));
            local.setComments(ctx.commentsOf(vd));
            locals.add(local);
        }
        return locals;
    }

    /** Local (method-body) classes declared directly in the body, keyed by simple name (sorted). */
    private Map<String, JType> localClasses(BlockStmt body, String callableId) {
        TypeBuilder typeBuilder = new TypeBuilder(ctx);
        Map<String, JType> locals = new TreeMap<>();
        body.findAll(TypeDeclaration.class).stream()
                .filter(td -> CallSiteBuilder.belongsDirectlyTo(td, body))
                .forEach(td -> locals.put(td.getNameAsString(), typeBuilder.build(td, callableId)));
        return new LinkedHashMap<>(locals);
    }

    /** Syntactic cross-refs: types referenced and enclosing-type fields accessed in the body. */
    private JRefs refs(Optional<BlockStmt> body, List<String> classFieldNames) {
        JRefs refs = new JRefs();
        if (body.isEmpty()) {
            return refs;
        }
        BlockStmt b = body.get();

        TreeSet<String> types = new TreeSet<>();
        b.findAll(VariableDeclarator.class).stream()
                .filter(vd -> CallSiteBuilder.belongsDirectlyTo(vd, b) && vd.getType().isClassOrInterfaceType())
                .forEach(vd -> types.add(ctx.resolveType(vd.getType())));
        b.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> CallSiteBuilder.belongsDirectlyTo(oce, b))
                .forEach(oce -> types.add(ctx.resolveType(oce.getType())));
        refs.setTypes(new ArrayList<>(types));

        TreeSet<String> fields = new TreeSet<>();
        b.findAll(FieldAccessExpr.class).stream()
                .filter(fa -> CallSiteBuilder.belongsDirectlyTo(fa, b)
                        && !(fa.getParentNode().orElse(null) instanceof FieldAccessExpr))
                .forEach(fa -> fields.add(fa.getNameAsString()));
        b.findAll(NameExpr.class).stream()
                .filter(ne -> CallSiteBuilder.belongsDirectlyTo(ne, b) && classFieldNames.contains(ne.getNameAsString()))
                .forEach(ne -> fields.add(ne.getNameAsString()));
        refs.setFields(new ArrayList<>(fields));
        return refs;
    }

    /**
     * Cyclomatic complexity: one plus the number of branch points (if/loop/switch-case/ternary/catch)
     * in the callable (mirrors the v1 symbol-table metric).
     */
    private static int cyclomaticComplexity(InitializerDeclaration id) {
        return branchPoints(id) + 1;
    }

    private static int cyclomaticComplexity(CallableDeclaration<?> cd) {
        return branchPoints(cd) + 1;
    }

    /** Branch points (if / loop / switch-case / ternary / catch) inside any node. */
    private static int branchPoints(com.github.javaparser.ast.Node node) {
        int ifCount = node.findAll(IfStmt.class).size();
        int loopCount = node.findAll(DoStmt.class).size() + node.findAll(ForStmt.class).size()
                + node.findAll(ForEachStmt.class).size() + node.findAll(WhileStmt.class).size();
        int switchCaseCount =
                node.findAll(SwitchStmt.class).stream().mapToInt(s -> s.getEntries().size()).sum();
        int ternaryCount = node.findAll(ConditionalExpr.class).size();
        int catchCount = node.findAll(CatchClause.class).size();
        return ifCount + loopCount + switchCaseCount + ternaryCount + catchCount;
    }
}
