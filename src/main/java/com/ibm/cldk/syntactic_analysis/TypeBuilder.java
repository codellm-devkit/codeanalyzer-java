package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.ibm.cldk.javaee.EntrypointsFinderFactory;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JEnumConstant;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JRecordComponent;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds a v2 {@code type} node from a JavaParser {@link TypeDeclaration}: derives the {@code kind},
 * the byte-offset {@code span}, structured {@code decorators}, and {@code base_types}/
 * {@code interfaces}. Delegates annotation shaping to {@link DecoratorBuilder}.
 */
public final class TypeBuilder {

    private final L1BuildContext ctx;
    private final DecoratorBuilder decoratorBuilder;
    private final FieldBuilder fieldBuilder;
    private final CallableBuilder callableBuilder;
    private final TypeParameterBuilder typeParameterBuilder;

    public TypeBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.decoratorBuilder = new DecoratorBuilder(ctx);
        this.fieldBuilder = new FieldBuilder(ctx);
        this.callableBuilder = new CallableBuilder(ctx);
        this.typeParameterBuilder = new TypeParameterBuilder(ctx);
    }

    /**
     * @param td the type declaration
     * @param parentId the containing node's id (module id for top-level types)
     */
    public JType build(TypeDeclaration<?> td, String parentId) {
        JType type = new JType();
        type.setId(CanId.childId(parentId, td.getNameAsString()));
        type.setKind(kindOf(td));
        type.setSpan(ctx.spanOf(td));
        type.setEntrypointClass(
                EntrypointsFinderFactory.getEntrypointFinders().anyMatch(f -> f.isEntrypointClass(td)));
        type.setComments(ctx.commentsOf(td));
        type.setModifiers(
                td.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList()));
        type.setDecorators(
                td.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
        type.setTypeParameters(typeParameterBuilder.build(td));

        List<String> baseTypes = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) td;
            cls.getExtendedTypes().forEach(t -> baseTypes.add(ctx.resolveType(t)));
            cls.getImplementedTypes().forEach(t -> interfaces.add(ctx.resolveType(t)));
        } else if (td instanceof EnumDeclaration) {
            ((EnumDeclaration) td).getImplementedTypes().forEach(t -> interfaces.add(ctx.resolveType(t)));
        } else if (td instanceof RecordDeclaration) {
            ((RecordDeclaration) td).getImplementedTypes().forEach(t -> interfaces.add(ctx.resolveType(t)));
        }
        type.setBaseTypes(baseTypes);
        type.setInterfaces(interfaces);

        if (td instanceof RecordDeclaration) {
            List<JRecordComponent> components = new ArrayList<>();
            for (Parameter p : ((RecordDeclaration) td).getParameters()) {
                JRecordComponent component = new JRecordComponent();
                component.setName(p.getNameAsString());
                component.setType(ctx.resolveType(p.getType()));
                component.setSpan(ctx.spanOf(p));
                component.setModifiers(
                        p.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList()));
                component.setDecorators(
                        p.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
                component.setComments(ctx.commentsOf(p));
                component.setVariadic(p.isVarArgs());
                components.add(component);
            }
            type.setRecordComponents(components);
        }

        populateMembers(type, td.getMembers(), typeFqnOf(td));

        if (td instanceof EnumDeclaration) {
            populateEnumConstants(type, (EnumDeclaration) td, typeFqnOf(td));
        }

        synthesizeImplicitConstructor(type, td);
        synthesizeImplicitMethods(type, td);
        return type;
    }

    /**
     * Emit the methods the language generates for records and enums, so a call site resolving to one has
     * a callable to name.
     *
     * <p>Which ones matter was settled by probing the symbol solver rather than by reading the JLS:
     * {@code money.cents()}, {@code Op.values()} and {@code Op.valueOf(s)} all report the in-project type
     * as their declaring type, which makes their absence unrecoverable — the target can be neither named
     * nor homed as external. A record's generated {@code equals}/{@code hashCode}/{@code toString} do not
     * resolve at all, so synthesizing them would add callables nothing points at.
     *
     * <p>Only named declarations reach here. Enum-constant class bodies and anonymous classes are built
     * through {@link #populateMembers}, which is correct: javac generates {@code values()} on the enum
     * class, not on a constant's subclass.
     */
    private void synthesizeImplicitMethods(JType type, TypeDeclaration<?> td) {
        if (td instanceof RecordDeclaration) {
            for (Parameter component : ((RecordDeclaration) td).getParameters()) {
                String signature = component.getNameAsString() + "()";
                // A record may override an accessor, and both occupy this one signature slot.
                if (type.getCallables().containsKey(signature)) {
                    continue;
                }
                // The component is declared `String...` but the field, and so the accessor's return, is
                // `String[]` — the same asymmetry Signatures.erasedTypeOf handles on the parameter side.
                String returnType = ctx.resolveType(component.getType()) + (component.isVarArgs() ? "[]" : "");
                addCallable(type, callableBuilder.buildImplicitMethod(
                        type.getId(), signature, returnType, List.of("public"), List.of()));
            }
        } else if (td instanceof EnumDeclaration) {
            // Unconditional: declaring either of these is a compile error, so unlike the accessors there
            // is nothing that could suppress them.
            String enumFqn = typeFqnOf(td);
            addCallable(type, callableBuilder.buildImplicitMethod(
                    type.getId(), "values()", enumFqn + "[]", List.of("public", "static"), List.of()));
            JParameter name = new JParameter();
            name.setName("name");
            name.setType("java.lang.String");
            addCallable(type, callableBuilder.buildImplicitMethod(
                    type.getId(),
                    "valueOf(java.lang.String)",
                    enumFqn,
                    List.of("public", "static"),
                    List.of(name)));
        }
    }

    /**
     * Emit the constructor the language guarantees when the source declares none, so a {@code new Foo()}
     * call site has a callable to name.
     *
     * <p>The rule is the JLS's, checked against the compiler rather than inferred: a class or an enum gets
     * one only when it declares <em>no</em> constructor at all, a record gets its canonical constructor
     * unless a canonical one is declared, and an interface or annotation never gets one.
     */
    private void synthesizeImplicitConstructor(JType type, TypeDeclaration<?> td) {
        if (td instanceof AnnotationDeclaration
                || (td instanceof ClassOrInterfaceDeclaration && ((ClassOrInterfaceDeclaration) td).isInterface())) {
            return;
        }
        String signature;
        if (td instanceof RecordDeclaration) {
            // A *non-canonical* constructor does not suppress the canonical one — `record R(int x, int y)
            // { R(int x) {...} }` genuinely compiles to both. Testing for the canonical signature makes
            // that fall out, and a declared compact constructor is already keyed under it.
            signature = Signatures.typeErasure((RecordDeclaration) td);
            if (type.getCallables().containsKey(signature)) {
                return;
            }
        } else {
            // A class or enum is suppressed by *any* declared constructor, not just a no-arg one.
            if (td.getMembers().stream().anyMatch(m -> m instanceof ConstructorDeclaration)) {
                return;
            }
            signature = "<init>()";
        }
        addCallable(type, callableBuilder.buildImplicitConstructor(type.getId(), signature));
    }

    /** Add a callable, keeping the signature-sorted ordering {@link #populateMembers} establishes. */
    private static void addCallable(JType type, JCallable callable) {
        Map<String, JCallable> merged = new TreeMap<>(type.getCallables());
        merged.put(callable.getSignature(), callable);
        type.setCallables(new LinkedHashMap<>(merged));
    }

    /**
     * Populate an enum's {@code enum_constants}, and give any constant that declares a class body
     * ({@code PLUS { int apply(int a) { ... } }}) its own {@code type} node.
     *
     * <p>Entries are not part of {@code EnumDeclaration.getMembers()}, so this runs alongside
     * {@link #populateMembers} rather than inside it. A constant with a body is an anonymous subclass of
     * the enum, and is modelled as one for the same reason ordinary anonymous classes are (D13): its
     * methods, call sites and locals belong to it. Leaving the body unmodelled dropped those facts
     * entirely — the callables did not exist anywhere in the output, so L2 could not resolve calls into
     * or out of them. Keyed {@code $enum$<NAME>}: {@code $} marks the synthetic member and keeps it
     * distinct from a nested type that happens to share the constant's name.
     */
    private void populateEnumConstants(JType type, EnumDeclaration enumDecl, String typeFqn) {
        List<JEnumConstant> constants = new ArrayList<>();
        Map<String, JType> constantBodies = new LinkedHashMap<>(type.getTypes());
        for (EnumConstantDeclaration ecd : enumDecl.getEntries()) {
            JEnumConstant constant = new JEnumConstant();
            constant.setName(ecd.getNameAsString());
            constant.setArguments(
                    ecd.getArguments().stream().map(Object::toString).collect(Collectors.toList()));
            constant.setSpan(ctx.spanOf(ecd));
            constant.setComments(ctx.commentsOf(ecd));
            constant.setDecorators(
                    ecd.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
            constants.add(constant);

            if (!ecd.getClassBody().isEmpty()) {
                String name = "$enum$" + ecd.getNameAsString();
                JType body = new JType();
                body.setId(CanId.childId(type.getId(), name));
                body.setKind("class");
                body.setSpan(ctx.spanOf(ecd));
                // The enum itself is the supertype the constant's body specialises.
                body.setBaseTypes(List.of(typeFqn));
                // No implicit constructor is synthesized here, unlike for an anonymous class. Which enum
                // constructor a constant invokes is not recoverable: a resolved enum constant exposes only
                // its name and type, so naming the generated constructor would mean reimplementing overload
                // resolution over the constant's arguments. Nothing is lost — a constant is not a call site,
                // so no edge could point at it (see the enum-constructor contract in the L2 design).
                populateMembers(body, ecd.getClassBody(), typeFqn);
                constantBodies.put(name, body);
            }
        }
        type.setEnumConstants(constants);
        type.setTypes(constantBodies);
    }

    /** Maps a JavaParser type declaration to its v2 {@code kind} (design decision D4). */
    private static String kindOf(TypeDeclaration<?> td) {
        if (td instanceof AnnotationDeclaration) {
            return "annotation";
        }
        if (td instanceof EnumDeclaration) {
            return "enum";
        }
        if (td instanceof RecordDeclaration) {
            return "record";
        }
        if (td instanceof ClassOrInterfaceDeclaration && ((ClassOrInterfaceDeclaration) td).isInterface()) {
            return "interface";
        }
        return "class";
    }

    /** The fully-qualified name used to qualify field references, falling back to the simple name. */
    private static String typeFqnOf(TypeDeclaration<?> td) {
        return td.getFullyQualifiedName().orElse(td.getNameAsString());
    }

    /**
     * Build a {@code type} node for an anonymous class body ({@code new Runnable() { ... }}).
     *
     * <p>An anonymous class has no name, so it is keyed positionally ({@code $anon$0}, {@code $anon$1},
     * ... in declaration order within the callable) — stable across line edits, and {@code $} marks it
     * synthetic. Modelling it as its own type is what keeps its methods, initializers, locals and call
     * sites attributed to it rather than mis-attributed to the enclosing callable or dropped.
     */
    public JType buildAnonymous(ObjectCreationExpr creation, String parentId, String name) {
        JType type = new JType();
        type.setId(CanId.childId(parentId, name));
        type.setKind("class");
        type.setSpan(ctx.spanOf(creation));

        // The instantiated type is a supertype: an interface if it resolves to one, else a base class.
        String supertype = ctx.resolveType(creation.getType());
        if (resolvesToInterface(creation)) {
            type.setInterfaces(List.of(supertype));
        } else {
            type.setBaseTypes(List.of(supertype));
        }

        populateMembers(type, creation.getAnonymousClassBody().orElseGet(NodeList::new), supertype);
        anonymousConstructor(creation)
                .ifPresent(signature -> addCallable(type, callableBuilder.buildImplicitConstructor(type.getId(), signature)));
        return type;
    }

    /**
     * The signature of an anonymous class's generated constructor, which forwards the creation site's
     * arguments to the superclass.
     *
     * <p>Resolving the creation names it directly: {@code new Thread("x") { ... }} resolves to
     * {@code Thread.<init>(java.lang.String)}, which is also the generated constructor's own signature.
     * That is what lets a call site's {@code dst} be the anonymous class's own constructor rather than a
     * composed {@code (receiver_type, callee_signature)} pair — which for {@code new Runnable() { ... }}
     * would name {@code java.lang.Runnable.<init>()}, a constructor that cannot exist because interfaces
     * have none.
     *
     * <p>An unresolvable creation yields nothing rather than a guess. {@code <init>()} would be wrong for
     * every anonymous class that takes arguments, and the same site is unresolvable downstream too, so no
     * edge will point here to dangle.
     */
    private static Optional<String> anonymousConstructor(ObjectCreationExpr creation) {
        try {
            return Optional.of(Signatures.typeErasure(creation.resolve()));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    private static boolean resolvesToInterface(ObjectCreationExpr creation) {
        try {
            return creation.getType().resolve().asReferenceType().getTypeDeclaration()
                    .map(d -> d.isInterface())
                    .orElse(false);
        } catch (Throwable e) {
            // Unresolvable supertype: treat it as a base class rather than guessing.
            return false;
        }
    }

    /**
     * Populate a type's fields, callables (methods, constructors and initializer blocks) and member
     * types from its declared members. Shared by named types and anonymous class bodies so both get the
     * same treatment.
     */
    private void populateMembers(JType type, List<BodyDeclaration<?>> members, String typeFqn) {
        // Fields, keyed by simple name — one entry per declared variable (int a, b; -> a, b).
        Map<String, JField> fields = new LinkedHashMap<>();
        for (BodyDeclaration<?> member : members) {
            if (member instanceof FieldDeclaration) {
                fieldBuilder.build((FieldDeclaration) member, type.getId())
                        .forEach(f -> fields.put(f.getName(), f));
            }
        }
        type.setFields(fields);

        // Field names are handed down so each callable's refs.fields can recognise accesses to them.
        List<String> fieldNames = new ArrayList<>(fields.keySet());
        Map<String, JCallable> callables = new TreeMap<>();
        for (BodyDeclaration<?> member : members) {
            if (member instanceof CallableDeclaration) {
                JCallable callable =
                        callableBuilder.build((CallableDeclaration<?>) member, type.getId(), typeFqn, fieldNames);
                callables.put(callable.getSignature(), callable);
            } else if (member instanceof CompactConstructorDeclaration) {
                // A record's compact constructor extends BodyDeclaration directly rather than
                // CallableDeclaration, so it needs its own branch — matching only CallableDeclaration
                // dropped it silently, losing the canonical constructor along with its body, call sites
                // and locals, and leaving every `new R(...)` site with no callable to resolve to.
                JCallable callable = callableBuilder.buildCompactConstructor(
                        (CompactConstructorDeclaration) member, type.getId(), typeFqn, fieldNames);
                callables.put(callable.getSignature(), callable);
            }
        }
        // Initializer blocks are callables too (keystone kind `initializer`) — L3 gives them their own
        // CFGs. Numbered per kind so the id survives line edits; `$` marks the synthetic member.
        int staticIndex = 0;
        int instanceIndex = 0;
        for (BodyDeclaration<?> member : members) {
            if (member instanceof InitializerDeclaration) {
                InitializerDeclaration id = (InitializerDeclaration) member;
                String signature = id.isStatic()
                        ? "<clinit>$" + staticIndex++ + "()"
                        : "<instance-init>$" + instanceIndex++ + "()";
                callables.put(signature,
                        callableBuilder.buildInitializer(id, type.getId(), typeFqn, fieldNames, signature));
            }
        }
        type.setCallables(new LinkedHashMap<>(callables));

        // Member types; nesting/parent are encoded by this containment (and the id path).
        Map<String, JType> nested = new TreeMap<>();
        for (BodyDeclaration<?> member : members) {
            if (member instanceof TypeDeclaration) {
                TypeDeclaration<?> nestedType = (TypeDeclaration<?>) member;
                nested.put(nestedType.getNameAsString(), build(nestedType, type.getId()));
            }
        }
        // Anonymous classes in field initializers are lexically members of this type, not of any
        // callable, so they are attributed here (e.g. `static final X F = new X() { { ... } };`).
        // Scope-filtered like every other member fact: an anonymous class declared *inside* another
        // anonymous class's body belongs to that class, and is built when its own members are populated.
        // Without the filter the inner class was emitted twice — once correctly nested and once hoisted
        // to this type — double-counting its callables, call sites and metrics.
        List<ObjectCreationExpr> anonymous = new ArrayList<>();
        for (BodyDeclaration<?> member : members) {
            if (member instanceof FieldDeclaration) {
                member.findAll(ObjectCreationExpr.class).stream()
                        .filter(oce -> oce.getAnonymousClassBody().isPresent())
                        .filter(oce -> AstScopes.belongsDirectlyTo(oce, member))
                        .forEach(anonymous::add);
            }
        }
        anonymous.sort(Comparator
                .comparingInt((ObjectCreationExpr oce) -> oce.getBegin().map(pos -> pos.line).orElse(0))
                .thenComparingInt(oce -> oce.getBegin().map(pos -> pos.column).orElse(0)));
        for (int i = 0; i < anonymous.size(); i++) {
            String name = "$anon$" + i;
            nested.put(name, buildAnonymous(anonymous.get(i), type.getId(), name));
        }
        type.setTypes(new LinkedHashMap<>(nested));
    }
}
