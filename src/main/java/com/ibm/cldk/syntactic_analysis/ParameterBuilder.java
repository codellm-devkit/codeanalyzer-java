package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.Parameter;
import com.ibm.cldk.schema.JParameter;
import java.util.stream.Collectors;

/**
 * Builds a v2 {@code parameter} node from a JavaParser {@link Parameter}: {@code name}, the AST
 * declared {@code type} (syntactic — no resolution at L1), byte-offset {@code span}, and structured
 * {@code decorators}. Delegates annotation shaping to {@link DecoratorBuilder}.
 */
public final class ParameterBuilder {

    private final L1BuildContext ctx;
    private final DecoratorBuilder decoratorBuilder;

    public ParameterBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.decoratorBuilder = new DecoratorBuilder(ctx);
    }

    public JParameter build(Parameter param) {
        JParameter parameter = new JParameter();
        parameter.setName(param.getNameAsString());
        // Varargs (`String...`) spell as the element type + "[]" so the type reads as a real Java type.
        parameter.setType(param.isVarArgs() ? param.getType().asString() + "[]" : param.getType().asString());
        parameter.setSpan(ctx.spanOf(param));
        parameter.setDecorators(
                param.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList()));
        return parameter;
    }
}
