package com.ibm.cldk.schema;

import lombok.Data;

/**
 * Where a node lives in source. {@code start}/{@code end} are {@code [line, column]} (JavaParser
 * native: both 1-based) for addressing/display; {@code bytes} are {@code [from, to)} UTF-8 offsets
 * into {@code module.source} for O(1) slicing.
 */
@Data
public class Span {
    private int[] start;
    private int[] end;
    private int[] bytes;
}
