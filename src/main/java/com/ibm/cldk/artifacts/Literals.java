package com.ibm.cldk.artifacts;

import java.util.regex.Pattern;

/** String-literal decoding shared by the config-use and view-dispatch passes. */
final class Literals {

    private Literals() {}

    /** A bare Java identifier — the only argument shape a dataflow tier can trace. */
    static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    /** The decoded value of a {@code "..."} source expression, or {@code null} if it is not one. */
    static String stringLiteral(String expr) {
        if (expr == null || expr.length() < 2 || expr.charAt(0) != '"'
                || expr.charAt(expr.length() - 1) != '"') {
            return null;
        }
        return unescape(expr.substring(1, expr.length() - 1), true);
    }

    static String literalOf(com.github.javaparser.ast.expr.Expression expr) {
        return expr != null && expr.isStringLiteralExpr()
                ? unescape(expr.asStringLiteralExpr().getValue(), false)
                : null;
    }

    static String unescape(String body, boolean rejectInteriorQuote) {
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && rejectInteriorQuote) {
                return null;
            }
            if (c == '\\' && i + 1 < body.length()) {
                out.append(body.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
