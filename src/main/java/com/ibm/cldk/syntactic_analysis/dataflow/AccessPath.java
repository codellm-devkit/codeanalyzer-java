package com.ibm.cldk.syntactic_analysis.dataflow;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives the k-limited access path an expression names, as a syntactic string
 * {@code base(.field | [*])*}. This is the {@code var} carried on a {@code ddg} edge. Array subscripts
 * collapse to {@code [*]} (index-insensitive) and the path is truncated to {@code k} steps past the
 * base ({@code .*} marks the collapsed tail). Matching is by spelling — object-insensitive,
 * field-sensitive (see the L3 design's §4.5); {@code null} is returned for anything that is not a
 * simple variable/field/array reference (a method call, a literal, {@code new}).
 */
public final class AccessPath {

    private AccessPath() {}

    /** The access path {@code e} names, truncated to {@code k} steps, or {@code null} if it is not one. */
    public static String of(Expression e, int k) {
        List<String> segments = new ArrayList<>();
        if (!walk(e, segments)) {
            return null;
        }
        StringBuilder path = new StringBuilder(segments.get(0));
        int steps = 0;
        for (int i = 1; i < segments.size(); i++) {
            if (steps >= k) {
                path.append(".*");
                break;
            }
            path.append(segments.get(i));
            steps++;
        }
        return path.toString();
    }

    /** Fill {@code segments} base-first ({@code [base, ".f", "[*]", …]}); false if {@code e} is not a path. */
    private static boolean walk(Expression e, List<String> segments) {
        if (e instanceof NameExpr) {
            segments.add(((NameExpr) e).getNameAsString());
            return true;
        }
        if (e instanceof ThisExpr) {
            segments.add("this");
            return true;
        }
        if (e instanceof SuperExpr) {
            segments.add("super");
            return true;
        }
        if (e instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) e;
            if (!walk(fa.getScope(), segments)) {
                return false;
            }
            segments.add("." + fa.getNameAsString());
            return true;
        }
        if (e instanceof ArrayAccessExpr) {
            if (!walk(((ArrayAccessExpr) e).getName(), segments)) {
                return false;
            }
            segments.add("[*]");
            return true;
        }
        return false;
    }
}
