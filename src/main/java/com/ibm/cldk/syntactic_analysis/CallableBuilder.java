package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.ibm.cldk.javaee.EntrypointsFinderFactory;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JMetrics;
import com.ibm.cldk.schema.JRefs;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JVariableDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final TypeParameterBuilder typeParameterBuilder;

    public CallableBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.parameterBuilder = new ParameterBuilder(ctx);
        this.decoratorBuilder = new DecoratorBuilder(ctx);
        this.callSiteBuilder = new CallSiteBuilder(ctx);
        this.typeParameterBuilder = new TypeParameterBuilder(ctx);
        // TypeBuilder is constructed lazily in localClasses() to break the callable<->type
        // construction cycle (a type builds callables; a callable builds its local-class types).
    }

    /**
     * @param cd the callable declaration
     * @param parentTypeId the containing type's id
     * @param classFieldNames simple names of the enclosing type's fields (for {@code refs.fields})
     */
    public JCallable build(
            CallableDeclaration<?> cd, String parentTypeId, String typeFqn, List<String> classFieldNames) {
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
        callable.setEntrypoint(
                EntrypointsFinderFactory.getEntrypointFinders().anyMatch(f -> f.isEntrypointMethod(cd)));
        callable.setComments(ctx.commentsOf(cd));
        callable.setDecorators(
                cd.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
        callable.setTypeParameters(typeParameterBuilder.build(cd));

        // `declaration` mirrors v1: modifiers + return type + name + parameter names, no body. It omits
        // the type-parameter clause, which is why `type_parameters` carries the bounds separately.
        callable.setDeclaration(cd.getDeclarationAsString(true, true, true).strip());

        JMetrics metrics = new JMetrics();
        metrics.setCyclomatic(cyclomaticComplexity(cd));
        callable.setMetrics(metrics);

        Optional<BlockStmt> body = bodyOf(cd);
        body.ifPresent(b -> callable.setBodySpan(ctx.spanOf(b)));
        callable.setRefs(refs(body, typeFqn, classFieldNames));
        body.ifPresent(b -> callable.setLocalVariables(localVariables(b)));
        body.ifPresent(b -> callable.setBody(callSiteBuilder.build(b)));
        body.ifPresent(b -> callable.setTypes(localClasses(b, callable.getId())));
        return callable;
    }

    /**
     * Build a record's <em>compact</em> constructor ({@code record P(int x, int y) { public P { ... } }}).
     *
     * <p>A {@link CompactConstructorDeclaration} is a {@code BodyDeclaration} but <em>not</em> a
     * {@link CallableDeclaration}, so it needs its own entry point rather than falling through the
     * method/constructor path. It is the record's canonical constructor: its parameters are the record's
     * components (see {@link Signatures#typeErasure(CompactConstructorDeclaration)}), which is what makes
     * a {@code new P(...)} call site joinable against it.
     */
    public JCallable buildCompactConstructor(
            CompactConstructorDeclaration ccd, String parentTypeId, String typeFqn, List<String> classFieldNames) {
        JCallable callable = new JCallable();
        String signature = Signatures.typeErasure(ccd);
        callable.setSignature(signature);
        callable.setId(CanId.childId(parentTypeId, signature));
        callable.setKind("constructor");
        callable.setSpan(ctx.spanOf(ccd));
        // The parameters are the record's components, declared on the record header rather than here;
        // they are already modelled as `type.record_components`, so they are not duplicated onto the
        // callable with fabricated spans pointing at the header. Type parameters are the same story: a
        // compact constructor cannot declare its own, and a generic record's belong to the record.
        callable.setErrorChannel(
                ccd.getThrownExceptions().stream().map(ctx::resolveType).collect(Collectors.toList()));
        callable.setModifiers(
                ccd.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList()));
        callable.setComments(ctx.commentsOf(ccd));
        callable.setDecorators(
                ccd.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
        callable.setDeclaration(ccd.getDeclarationAsString(true, true, true).strip());

        JMetrics metrics = new JMetrics();
        metrics.setCyclomatic(branchPoints(ccd.getBody()) + 1);
        callable.setMetrics(metrics);

        BlockStmt body = ccd.getBody();
        callable.setBodySpan(ctx.spanOf(body));
        callable.setRefs(refs(Optional.of(body), typeFqn, classFieldNames));
        callable.setLocalVariables(localVariables(body));
        callable.setBody(callSiteBuilder.build(body));
        callable.setTypes(localClasses(body, callable.getId()));
        return callable;
    }

    /**
     * Build an initializer block as a {@code callable} with {@code kind:"initializer"}. It has no
     * parameters, return type or declared throws; everything else (body call sites, locals, refs,
     * metrics, local classes) works exactly as for a method.
     */
    public JCallable buildInitializer(
            InitializerDeclaration id,
            String parentTypeId,
            String typeFqn,
            List<String> classFieldNames,
            String signature) {
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
        // An initializer block cannot declare `throws`, so its error channel is what it actually throws:
        // the resolved types of the `throw` statements belonging to the block itself (v1 recorded the
        // same fact, from the block's top-level statements only — nested throws were missed).
        callable.setErrorChannel(thrownTypes(id.getBody()));

        JMetrics metrics = new JMetrics();
        metrics.setCyclomatic(cyclomaticComplexity(id));
        callable.setMetrics(metrics);

        BlockStmt body = id.getBody();
        callable.setBodySpan(ctx.spanOf(body));
        callable.setRefs(refs(Optional.of(body), typeFqn, classFieldNames));
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
            if (!AstScopes.belongsDirectlyTo(vd, body)) {
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

    /**
     * Types declared inside this callable's body: named local classes, plus anonymous class bodies.
     * Both are attributed here rather than to the enclosing type, so their members, locals and call
     * sites belong to the code that actually declares them (D4 containment).
     */
    private Map<String, JType> localClasses(BlockStmt body, String callableId) {
        TypeBuilder typeBuilder = new TypeBuilder(ctx);
        Map<String, JType> locals = new TreeMap<>();
        body.findAll(TypeDeclaration.class).stream()
                .filter(td -> AstScopes.belongsDirectlyTo(td, body))
                .forEach(td -> locals.put(td.getNameAsString(), typeBuilder.build(td, callableId)));

        // Anonymous classes have no name, so they are numbered in declaration order.
        List<ObjectCreationExpr> anonymous = body.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> oce.getAnonymousClassBody().isPresent())
                .filter(oce -> AstScopes.belongsDirectlyTo(oce, body))
                .sorted(Comparator
                        .comparingInt((ObjectCreationExpr oce) -> oce.getBegin().map(pos -> pos.line).orElse(0))
                        .thenComparingInt(oce -> oce.getBegin().map(pos -> pos.column).orElse(0)))
                .collect(Collectors.toList());
        for (int i = 0; i < anonymous.size(); i++) {
            String name = "$anon$" + i;
            locals.put(name, typeBuilder.buildAnonymous(anonymous.get(i), callableId, name));
        }
        return new LinkedHashMap<>(locals);
    }

    /**
     * Resolved types of the exceptions {@code throw}n by this block — the error channel of a construct
     * that has no {@code throws} clause to declare one. Scope-filtered like every other body fact: a
     * {@code throw} inside a nested or anonymous class belongs to <em>its</em> callable.
     */
    private List<String> thrownTypes(BlockStmt body) {
        return own(body, ThrowStmt.class).stream()
                .map(t -> ctx.resolveExpressionType(t.getExpression()))
                .filter(type -> !type.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /** Syntactic cross-refs: types referenced and enclosing-type fields accessed in the body. */
    private JRefs refs(Optional<BlockStmt> body, String typeFqn, List<String> classFieldNames) {
        JRefs refs = new JRefs();
        if (body.isEmpty()) {
            return refs;
        }
        BlockStmt b = body.get();

        TreeSet<String> types = new TreeSet<>();
        b.findAll(VariableDeclarator.class).stream()
                .filter(vd -> AstScopes.belongsDirectlyTo(vd, b) && vd.getType().isClassOrInterfaceType())
                .forEach(vd -> types.add(ctx.resolveType(vd.getType())));
        b.findAll(ObjectCreationExpr.class).stream()
                .filter(oce -> AstScopes.belongsDirectlyTo(oce, b))
                .forEach(oce -> types.add(ctx.resolveType(oce.getType())));
        b.findAll(CastExpr.class).stream()
                .filter(ce -> AstScopes.belongsDirectlyTo(ce, b))
                .forEach(ce -> types.add(ctx.resolveType(ce.getType())));
        b.findAll(InstanceOfExpr.class).stream()
                .filter(ie -> AstScopes.belongsDirectlyTo(ie, b))
                .forEach(ie -> types.add(ctx.resolveType(ie.getType())));
        b.findAll(CatchClause.class).stream()
                .filter(cc -> AstScopes.belongsDirectlyTo(cc, b))
                .forEach(cc -> types.add(ctx.resolveType(cc.getParameter().getType())));
        refs.setTypes(new ArrayList<>(types));

        // Field refs are qualified by their declaring type (as v1 did), so `other.count` and
        // `this.count` stay distinguishable; unresolvable scopes fall back to the bare name.
        TreeSet<String> fields = new TreeSet<>();
        b.findAll(FieldAccessExpr.class).stream()
                .filter(fa -> AstScopes.belongsDirectlyTo(fa, b)
                        && !(fa.getParentNode().orElse(null) instanceof FieldAccessExpr))
                .forEach(fa -> {
                    String declaring = ctx.resolveExpressionType(fa.getScope());
                    fields.add(declaring.isEmpty() ? fa.getNameAsString() : declaring + "." + fa.getNameAsString());
                });
        b.findAll(NameExpr.class).stream()
                .filter(ne -> AstScopes.belongsDirectlyTo(ne, b) && classFieldNames.contains(ne.getNameAsString()))
                .forEach(ne -> fields.add(typeFqn + "." + ne.getNameAsString()));
        refs.setFields(new ArrayList<>(fields));
        return refs;
    }

    /**
     * Cyclomatic complexity: one plus the number of branch points (if/loop/switch-case/ternary/catch)
     * in the callable (mirrors the v1 symbol-table metric).
     */
    private static int cyclomaticComplexity(InitializerDeclaration id) {
        return branchPoints(id.getBody()) + 1;
    }

    private static int cyclomaticComplexity(CallableDeclaration<?> cd) {
        return bodyOf(cd).map(CallableBuilder::branchPoints).orElse(0) + 1;
    }

    /**
     * Branch points (if / loop / switch-case / ternary / catch) belonging to this body itself. Branches
     * inside a nested type or anonymous class belong to <em>its</em> callables — counting them here too
     * would inflate the enclosing callable and double-count them, and every other metric on the callable
     * is scope-filtered the same way.
     */
    private static int branchPoints(BlockStmt node) {
        int ifCount = own(node, IfStmt.class).size();
        int loopCount = own(node, DoStmt.class).size() + own(node, ForStmt.class).size()
                + own(node, ForEachStmt.class).size() + own(node, WhileStmt.class).size();
        int switchCaseCount = own(node, SwitchStmt.class).stream().mapToInt(s -> s.getEntries().size()).sum();
        int ternaryCount = own(node, ConditionalExpr.class).size();
        int catchCount = own(node, CatchClause.class).size();
        return ifCount + loopCount + switchCaseCount + ternaryCount + catchCount;
    }

    /** Nodes of a kind that belong to {@code body} itself, not to a type nested within it. */
    private static <T extends com.github.javaparser.ast.Node> List<T> own(BlockStmt body, Class<T> kind) {
        return body.findAll(kind).stream()
                .filter(n -> AstScopes.belongsDirectlyTo(n, body))
                .collect(Collectors.toList());
    }
}
