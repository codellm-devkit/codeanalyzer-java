package com.ibm.cldk.schema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * UTF-8 byte-offset computation for schema v2 {@code span.bytes}.
 *
 * <p>Converts source positions given as a 1-based line and a 0-based character column into byte
 * offsets into the (UTF-8) module source. {@code span.bytes} carries these alongside
 * {@code line:col} so the SDK can slice a node's text as {@code module.source[from:to]} in O(1).
 * The column is a <em>character</em> offset within the line (multibyte characters count as one
 * column but contribute their full UTF-8 width to the byte offset).
 */
public final class Spans {

    private Spans() {}

    /** Byte offset into {@code source} of the position at (1-based {@code line}, 0-based {@code col}). */
    public static int byteOffset(String source, int line, int col) {
        List<String> lines = splitLinesKeepingTerminators(source);
        int prefixBytes = 0;
        for (int k = 0; k < line - 1 && k < lines.size(); k++) {
            prefixBytes += utf8Length(lines.get(k));
        }
        String current = (line - 1 >= 0 && line - 1 < lines.size()) ? lines.get(line - 1) : "";
        int c = Math.max(0, Math.min(col, current.length()));
        return prefixBytes + utf8Length(current.substring(0, c));
    }

    /** {@code [from, to)} byte offsets (end exclusive) for a span from (startLine,startCol) to (endLine,endCol). */
    public static int[] byteOffsets(String source, int startLine, int startCol, int endLine, int endCol) {
        return new int[] {byteOffset(source, startLine, startCol), byteOffset(source, endLine, endCol)};
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Split into lines <em>keeping</em> their terminators (universal newlines: {@code \n},
     * {@code \r\n}, {@code \r}), mirroring Python's {@code splitlines(keepends=True)}. A final line
     * without a terminator is included.
     */
    public static List<String> splitLinesKeepingTerminators(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                out.add(s.substring(start, i + 1));
                i++;
                start = i;
            } else if (ch == '\r') {
                if (i + 1 < n && s.charAt(i + 1) == '\n') {
                    out.add(s.substring(start, i + 2));
                    i += 2;
                } else {
                    out.add(s.substring(start, i + 1));
                    i++;
                }
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            out.add(s.substring(start));
        }
        return out;
    }
}
