package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.TypeParameter;
import com.ibm.cldk.schema.JTypeParameter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the {@code type_parameters} of a generic declaration. Delegates annotation shaping to
 * {@link DecoratorBuilder}.
 *
 * <p>Genericity is keyed off {@link NodeWithTypeParameters} rather than an {@code instanceof} chain
 * over declaration kinds, which keeps the language rule in one place: classes, interfaces, records,
 * methods and constructors can be generic; enums and annotation types cannot, and neither can
 * anonymous classes or enum-constant bodies.
 */
public final class TypeParameterBuilder {

    private final L1BuildContext ctx;
    private final DecoratorBuilder decoratorBuilder;

    public TypeParameterBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.decoratorBuilder = new DecoratorBuilder(ctx);
    }

    /** The type parameters of any declaration, empty for one that cannot be generic. */
    public List<JTypeParameter> build(Node declaration) {
        if (!(declaration instanceof NodeWithTypeParameters)) {
            return Collections.emptyList();
        }
        return ((NodeWithTypeParameters<?>) declaration).getTypeParameters().stream()
                .map(this::buildOne)
                .collect(Collectors.toList());
    }

    private JTypeParameter buildOne(TypeParameter tp) {
        JTypeParameter out = new JTypeParameter();
        out.setName(tp.getNameAsString());
        out.setBounds(tp.getTypeBound().stream().map(ctx::resolveType).collect(Collectors.toList()));
        out.setSpan(ctx.spanOf(tp));
        out.setDecorators(
                tp.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
        return out;
    }
}
