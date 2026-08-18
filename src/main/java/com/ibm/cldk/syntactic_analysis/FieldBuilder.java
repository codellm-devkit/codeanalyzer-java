package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JComment;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds v2 {@code field} nodes from a JavaParser {@link FieldDeclaration}. A single declaration may
 * declare several variables ({@code int a, b;}), so this yields one {@link JField} per variable —
 * each keyed/id'd by its own name but sharing the declaration's modifiers, decorators, and span.
 * Delegates annotation shaping to {@link DecoratorBuilder}.
 */
public final class FieldBuilder {

    private final L1BuildContext ctx;
    private final DecoratorBuilder decoratorBuilder;

    public FieldBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
        this.decoratorBuilder = new DecoratorBuilder(ctx);
    }

    /**
     * @param fd the field declaration
     * @param parentTypeId the containing type's id
     */
    public List<JField> build(FieldDeclaration fd, String parentTypeId) {
        String type = ctx.resolveType(fd.getCommonType());
        List<String> modifiers =
                fd.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList());
        List<JDecorator> decorators =
                fd.getAnnotations().stream().map(decoratorBuilder::build).collect(Collectors.toList());
        Span span = ctx.spanOf(fd);
        List<JComment> comments = ctx.commentsOf(fd);

        List<JField> fields = new ArrayList<>();
        for (VariableDeclarator var : fd.getVariables()) {
            JField field = new JField();
            field.setName(var.getNameAsString());
            field.setId(CanId.childId(parentTypeId, var.getNameAsString()));
            field.setType(type);
            field.setModifiers(modifiers);
            field.setDecorators(decorators);
            field.setSpan(span);
            field.setComments(comments);
            var.getInitializer().ifPresent(init -> field.setInitializer(init.toString()));
            fields.add(field);
        }
        return fields;
    }
}
