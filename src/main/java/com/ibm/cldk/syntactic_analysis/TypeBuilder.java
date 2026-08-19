package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
import com.ibm.cldk.schema.JRecordComponent;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public TypeBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.decoratorBuilder = new DecoratorBuilder(ctx);
        this.fieldBuilder = new FieldBuilder(ctx);
        this.callableBuilder = new CallableBuilder(ctx);
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

        if (td instanceof EnumDeclaration) {
            List<JEnumConstant> constants = new ArrayList<>();
            for (EnumConstantDeclaration ecd : ((EnumDeclaration) td).getEntries()) {
                JEnumConstant constant = new JEnumConstant();
                constant.setName(ecd.getNameAsString());
                constant.setArguments(
                        ecd.getArguments().stream().map(Object::toString).collect(Collectors.toList()));
                constant.setSpan(ctx.spanOf(ecd));
                constant.setComments(ctx.commentsOf(ecd));
                constants.add(constant);
            }
            type.setEnumConstants(constants);
        }

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

        return type;
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
        return type;
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
        List<ObjectCreationExpr> anonymous = new ArrayList<>();
        for (BodyDeclaration<?> member : members) {
            if (member instanceof FieldDeclaration) {
                member.findAll(ObjectCreationExpr.class).stream()
                        .filter(oce -> oce.getAnonymousClassBody().isPresent())
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
