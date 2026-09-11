package com.ibm.cldk.artifacts;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.Span;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The view-dispatch table tier (spec 2026-09-11 § 4.5, #261): a target expression that is a call
 * {@code T.m(...)} whose every {@code return} is an array access rooted at a static
 * {@code String[]} / {@code String[][]} field of {@code T} with an array-initializer of string
 * literals closes on <em>every</em> literal in that initializer — a may-dispatch. DayTrader's
 * {@code TradeConfig.getPage(N)} = {@code return webUI[webInterface][pageNumber]} is the shape.
 *
 * <p>Resolution is by declaring-type simple name plus method name within the tree, not by the L2
 * {@code callee}, so it runs at {@code -a 1}; two same-named types both declaring the method, or two
 * same-arity overloads, make it give up rather than pick one. Anything that is not exactly this
 * shape — a computed return, a field without a literal initializer — returns {@code null}.
 */
final class StringTables {

    private final Map<String, List<Owner>> typesBySimpleName = new LinkedHashMap<>();

    /** A type plus the module source its callables' body spans slice. */
    private static final class Owner {
        final JType type;
        final String source;

        Owner(JType type, String source) {
            this.type = type;
            this.source = source;
        }
    }

    StringTables(Map<String, JModule> modules) {
        if (modules != null) {
            for (JModule m : modules.values()) {
                index(m.getTypes(), m.getSource());
            }
        }
    }

    private void index(Map<String, JType> types, String source) {
        if (types == null) {
            return;
        }
        for (Map.Entry<String, JType> e : types.entrySet()) {
            typesBySimpleName.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                    .add(new Owner(e.getValue(), source));
            for (JCallable c : e.getValue().getCallables().values()) {
                index(c.getTypes(), source);
            }
            index(e.getValue().getTypes(), source);
        }
    }

    /** True when {@code expr} has the call shape the tier can even attempt, for the {@code prov} record. */
    static boolean isCall(String expr) {
        return parseCall(expr) != null;
    }

    /** Every literal of the table {@code expr} indexes, or {@code null} if it is not a table lookup. */
    List<String> close(String expr) {
        MethodCallExpr call = parseCall(expr);
        if (call == null) {
            return null;
        }
        String typeName = call.getScope().get().toString();
        typeName = typeName.substring(typeName.lastIndexOf('.') + 1);
        List<Owner> owners = typesBySimpleName.get(typeName);
        if (owners == null || owners.size() != 1) {
            return null;
        }
        Owner owner = owners.get(0);
        JCallable callee = uniqueCallable(owner.type, call.getNameAsString(), call.getArguments().size());
        if (callee == null) {
            return null;
        }
        String field = tableField(callee, owner.source);
        if (field == null) {
            return null;
        }
        JField f = owner.type.getFields().get(field);
        if (f == null || f.getType() == null || !f.getType().contains("String")
                || !f.getModifiers().contains("static") || f.getInitializer() == null) {
            return null;
        }
        List<String> literals = literalsOf(f.getInitializer());
        return literals.isEmpty() ? null : literals;
    }

    private static MethodCallExpr parseCall(String expr) {
        if (expr == null || !expr.endsWith(")")) {
            return null;
        }
        Expression parsed;
        try {
            parsed = StaticJavaParser.parseExpression(expr);
        } catch (RuntimeException e) {
            return null;
        }
        if (!parsed.isMethodCallExpr() || parsed.asMethodCallExpr().getScope().isEmpty()) {
            return null;
        }
        Expression scope = parsed.asMethodCallExpr().getScope().get();
        return scope.isNameExpr() || scope.isFieldAccessExpr() ? parsed.asMethodCallExpr() : null;
    }

    private static JCallable uniqueCallable(JType type, String name, int arity) {
        JCallable found = null;
        for (JCallable c : type.getCallables().values()) {
            if (c.getSignature() != null && c.getSignature().startsWith(name + "(")
                    && c.getParameters().size() == arity) {
                if (found != null) {
                    return null;
                }
                found = c;
            }
        }
        return found;
    }

    /** The one static field every {@code return} of {@code callee} indexes into, or {@code null}. */
    private static String tableField(JCallable callee, String source) {
        String body = slice(source, callee.getBodySpan());
        if (body == null) {
            return null;
        }
        BlockStmt block;
        try {
            block = StaticJavaParser.parseBlock(body);
        } catch (RuntimeException e) {
            return null;
        }
        Set<String> roots = new LinkedHashSet<>();
        List<ReturnStmt> returns = block.findAll(ReturnStmt.class);
        for (ReturnStmt r : returns) {
            Expression e = r.getExpression().orElse(null);
            if (e == null || !e.isArrayAccessExpr()) {
                return null;
            }
            while (e.isArrayAccessExpr()) {
                e = ((ArrayAccessExpr) e).getName();
            }
            if (e.isNameExpr()) {
                roots.add(e.asNameExpr().getNameAsString());
            } else if (e.isFieldAccessExpr()) {
                roots.add(e.asFieldAccessExpr().getNameAsString());
            } else {
                return null;
            }
        }
        return returns.isEmpty() || roots.size() != 1 ? null : roots.iterator().next();
    }

    private static List<String> literalsOf(String initializer) {
        List<String> out = new ArrayList<>();
        Expression parsed;
        try {
            // An array initializer is not an expression on its own; give it a declaration to sit in.
            parsed = StaticJavaParser.parseVariableDeclarationExpr("String[][] t = " + initializer);
        } catch (RuntimeException e) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (StringLiteralExpr s : parsed.findAll(StringLiteralExpr.class)) {
            if (seen.add(s.getValue())) {
                out.add(s.getValue());
            }
        }
        return out;
    }

    private static String slice(String source, Span span) {
        int[] bytes = span == null ? null : span.getBytes();
        if (source == null || bytes == null || bytes.length < 2) {
            return null;
        }
        byte[] raw = source.getBytes(StandardCharsets.UTF_8);
        if (bytes[0] < 0 || bytes[1] > raw.length || bytes[0] >= bytes[1]) {
            return null;
        }
        return new String(raw, bytes[0], bytes[1] - bytes[0], StandardCharsets.UTF_8);
    }
}
