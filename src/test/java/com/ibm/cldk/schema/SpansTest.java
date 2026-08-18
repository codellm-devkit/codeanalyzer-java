package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for UTF-8 byte-offset computation used by schema v2 {@code span.bytes}. Contract mirrors
 * the Python pilot's {@code byte_offsets}: input is a 1-based line and a 0-based character column
 * (the offset of the char <em>before</em> which the position sits); output is a UTF-8 byte offset
 * into the module source, so {@code module.source[bytes]} slices the node's text.
 */
class SpansTest {

    @Test
    void byteOffset_asciiStartOfFile() {
        assertEquals(0, Spans.byteOffset("abc\ndef\n", 1, 0));
    }

    @Test
    void byteOffset_asciiWithinFirstLine() {
        assertEquals(3, Spans.byteOffset("abc\ndef\n", 1, 3));
    }

    @Test
    void byteOffset_secondLineCountsPriorNewline() {
        assertEquals(4, Spans.byteOffset("abc\ndef\n", 2, 0));
        assertEquals(7, Spans.byteOffset("abc\ndef\n", 2, 3));
    }

    @Test
    void byteOffset_multibyteColumnIsCharsButResultIsBytes() {
        // 'é' is one character but two bytes in UTF-8.
        String src = "é = 1\n";
        assertEquals(2, Spans.byteOffset(src, 1, 1)); // after 'é'
        assertEquals(4, Spans.byteOffset(src, 1, 3)); // after "é ="
    }

    @Test
    void byteOffset_priorMultibyteLineBytesCounted() {
        String src = "é\nx\n";
        assertEquals(3, Spans.byteOffset(src, 2, 0)); // "é\n" = 2 + 1 bytes
        assertEquals(4, Spans.byteOffset(src, 2, 1)); // + "x"
    }

    @Test
    void byteOffsets_returnsFromToPair() {
        assertArrayEquals(new int[] {0, 3}, Spans.byteOffsets("abc\ndef\n", 1, 0, 1, 3));
    }
}
