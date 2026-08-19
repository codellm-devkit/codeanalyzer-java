package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.javaee.EntrypointsFinderFactory;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JEnumConstant;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.JRecordComponent;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
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

        // Fields, keyed by simple name — one entry per declared variable (int a, b; -> a, b).
        Map<String, JField> fields = new LinkedHashMap<>();
        for (FieldDeclaration fd : td.getFields()) {
            fieldBuilder.build(fd, type.getId()).forEach(f -> fields.put(f.getName(), f));
        }
        type.setFields(fields);

        // Callables (methods + constructors) declared directly in this type — getMethods()/
        // getConstructors() return only direct members, so nested-type methods are not swept in.
        // Keyed by type-erasure signature. Field names are handed down so each callable's
        // refs.fields can recognize accesses to this type's fields.
        List<String> fieldNames = new ArrayList<>(fields.keySet());
        String typeFqn = td.getFullyQualifiedName().orElse(td.getNameAsString());
        List<CallableDeclaration<?>> declared = new ArrayList<>();
        declared.addAll(td.getConstructors());
        declared.addAll(td.getMethods());
        Map<String, JCallable> callables = new TreeMap<>();
        for (CallableDeclaration<?> cd : declared) {
            JCallable callable = callableBuilder.build(cd, type.getId(), typeFqn, fieldNames);
            callables.put(callable.getSignature(), callable);
        }
        // Initializer blocks are callables too (keystone kind `initializer`) — L3 gives them their own
        // CFGs. Numbered per kind so the id survives line edits; `$` marks the synthetic member.
        int staticIndex = 0;
        int instanceIndex = 0;
        for (InitializerDeclaration id : td.getMembers().stream()
                .filter(m -> m instanceof InitializerDeclaration)
                .map(m -> (InitializerDeclaration) m)
                .collect(Collectors.toList())) {
            String signature = id.isStatic()
                    ? "<clinit>$" + staticIndex++ + "()"
                    : "<instance-init>$" + instanceIndex++ + "()";
            callables.put(signature, callableBuilder.buildInitializer(id, type.getId(), typeFqn, fieldNames, signature));
        }
        type.setCallables(new LinkedHashMap<>(callables));

        // Recurse into member (inner) types; nesting/parent are encoded by this containment (and the
        // id path). Local classes in method bodies are handled later by the callable builder.
        Map<String, JType> nested = new TreeMap<>();
        td.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration)
                .map(m -> (TypeDeclaration<?>) m)
                .forEach(member -> nested.put(member.getNameAsString(), build(member, type.getId())));
        type.setTypes(new LinkedHashMap<>(nested));

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
}
