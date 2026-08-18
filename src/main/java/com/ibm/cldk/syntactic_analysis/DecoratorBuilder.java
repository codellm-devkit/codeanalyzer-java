package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.ibm.cldk.schema.JDecorator;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a structured {@link JDecorator} ({@code name} + argument expressions + {@code span}) from a
 * JavaParser {@link AnnotationExpr}. Handles marker, single-member, and normal annotations.
 */
public final class DecoratorBuilder {

    private final L1BuildContext ctx;

    public DecoratorBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
    }

    public JDecorator build(AnnotationExpr annotation) {
        JDecorator decorator = new JDecorator();
        decorator.setName(annotation.getNameAsString());
        decorator.setSpan(ctx.spanOf(annotation));

        List<String> args = new ArrayList<>();
        if (annotation instanceof SingleMemberAnnotationExpr) {
            args.add(((SingleMemberAnnotationExpr) annotation).getMemberValue().toString());
        } else if (annotation instanceof NormalAnnotationExpr) {
            for (MemberValuePair pair : ((NormalAnnotationExpr) annotation).getPairs()) {
                args.add(pair.getNameAsString() + "=" + pair.getValue().toString());
            }
        } // MarkerAnnotationExpr has no arguments
        decorator.setArgs(args);
        return decorator;
    }
}
