package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.GraphRows.NodeRef;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The snapshot writer must stream. Building the whole script as one {@code String} first is what
 * made {@code --emit neo4j} die on a large repository with {@code OutOfMemoryError: Requested
 * string length exceeds VM limit} (#209) — not heap exhaustion, but a single {@code String}
 * exceeding the JVM's maximum array length, which no {@code -Xmx} can raise.
 */
class CypherWriterStreamingTest {

    /** A graph big enough to span several batches, so batching and streaming both get exercised. */
    private static GraphRows sampleRows(int nodes) {
        RowBuilder b = new RowBuilder();
        List<NodeRef> refs = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            Map<String, Object> p = RowBuilder.props();
            p.put("name", "Type" + i);
            p.put("code", "class Type" + i + " { void m() {} }");
            refs.add(b.node(Arrays.asList("JType", "JSymbol"), "id", "can://java/app/T" + i + ".java/Type" + i, p));
        }
        for (int i = 1; i < nodes; i++) {
            b.edge("J_CALLS", refs.get(i - 1), refs.get(i));
        }
        return b.finish();
    }

    /** Records every write so the test can tell streaming from one giant write. */
    private static final class CountingWriter extends Writer {
        final StringWriter sink = new StringWriter();
        int writes;
        int largestWrite;

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            writes++;
            largestWrite = Math.max(largestWrite, len);
            sink.write(cbuf, off, len);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    @Test
    void streamedOutputIsIdenticalToTheRenderedString() throws IOException {
        GraphRows rows = sampleRows(1200); // > 2 batches of 500
        StringWriter w = new StringWriter();
        CypherWriter.writeCypher(w, rows, "app");
        assertEquals(CypherWriter.renderCypher(rows, "app"), w.toString(),
                "streaming must not change a single byte of the emitted script");
    }

    @Test
    void theScriptReachesTheWriterIncrementallyRatherThanAsOneString() throws IOException {
        GraphRows rows = sampleRows(1200);
        CountingWriter w = new CountingWriter();
        CypherWriter.writeCypher(w, rows, "app");

        int total = w.sink.toString().length();
        assertTrue(total > 100_000, "sample graph should be substantial, was " + total + " chars");
        // The defect being pinned: render-then-write hands the writer everything in one call. Any
        // streaming implementation writes each statement separately, so no single write is anywhere
        // near the whole script.
        assertTrue(w.writes > 10, "expected many writes, got " + w.writes);
        assertTrue(w.largestWrite < total / 2,
                "no single write may carry half the script (largest=" + w.largestWrite + " of " + total
                        + ") — that means the whole script was materialized before writing");
    }

    /**
     * The equality test above cannot catch a separator regression, because {@code renderCypher} now
     * delegates to {@code writeCypher} and so compares the implementation with itself. This pins the
     * boundary independently: statements are separated by newlines, not terminated by them.
     * Emitting {@code statement + "\n"} per statement appends one byte the previous released
     * version never wrote, which is invisible to a self-comparison and obvious in a diff of two
     * generated scripts.
     */
    @Test
    void statementsAreSeparatedByNewlinesNotTerminatedByThem() throws IOException {
        StringWriter w = new StringWriter();
        CypherWriter.writeCypher(w, sampleRows(3), "app");
        String out = w.toString();
        assertTrue(out.endsWith(";\n"), "script ends with the final statement then one newline");
        assertFalse(out.endsWith("\n\n"), "no trailing blank line — the separator goes between statements");
    }

    @Test
    void anEmptyGraphStillEmitsConstraintsAndTheWipe() throws IOException {
        StringWriter w = new StringWriter();
        CypherWriter.writeCypher(w, new RowBuilder().finish(), "app");
        String out = w.toString();
        assertTrue(out.contains("CREATE CONSTRAINT"), "constraints are emitted even with no rows");
        assertTrue(out.contains("DETACH DELETE"), "the wipe is emitted even with no rows");
        assertEquals(CypherWriter.renderCypher(new RowBuilder().finish(), "app"), out);
    }
}
