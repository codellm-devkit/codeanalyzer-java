package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.schema.CanId;
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

    public TypeBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.decoratorBuilder = new DecoratorBuilder(ctx);
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
        type.setDecorators(
                td.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));

        List<String> baseTypes = new ArrayList<>();
        List<String> interfaces = new ArrayList<>();
        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) td;
            cls.getExtendedTypes().forEach(t -> baseTypes.add(t.asString()));
            cls.getImplementedTypes().forEach(t -> interfaces.add(t.asString()));
        } else if (td instanceof EnumDeclaration) {
            ((EnumDeclaration) td).getImplementedTypes().forEach(t -> interfaces.add(t.asString()));
        } else if (td instanceof RecordDeclaration) {
            ((RecordDeclaration) td).getImplementedTypes().forEach(t -> interfaces.add(t.asString()));
        }
        type.setBaseTypes(baseTypes);
        type.setInterfaces(interfaces);

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
