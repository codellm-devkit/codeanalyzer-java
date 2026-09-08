package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.GraphRows.NodeRef;
import com.ibm.cldk.schema.CanId;
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
            String moduleId = CanId.moduleId(CanId.applicationId("app"), "T" + i + ".java");
            refs.add(b.node(Arrays.asList("JType", "JSymbol"), "id", CanId.childId(moduleId, "Type" + i), p));
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

    @Test
    void theOldApplicationNameConstraintIsDroppedBeforeAnyConstraintIsCreated() throws IOException {
        // Regression for the upgrade path. Pre-3.1.1 databases carry `j_application_name`
        // (:JApplication.name IS UNIQUE). The root is now keyed on its can:// id, so on an upgraded
        // DB the MERGE-on-id misses the id-less legacy root, CREATEs a second one, and `SET n +=
        // {name: ...}` trips that surviving constraint -- ConstraintValidationFailed kills the whole
        // push. `CREATE CONSTRAINT ... IF NOT EXISTS` cannot supersede it; only a DROP can, and it
        // has to run BEFORE the load. The Bolt path (Neo4jBoltWriterTest) starts on a virgin
        // container and so cannot see this; the emitted preamble is where the ordering is pinned.
        String out = CypherWriter.renderCypher(new RowBuilder().finish(), "app");
        int drop = out.indexOf("DROP CONSTRAINT j_application_name IF EXISTS;");
        assertTrue(drop >= 0, "the legacy name constraint must be dropped on every load, got: " + out);
        assertTrue(drop < out.indexOf("CREATE CONSTRAINT"),
                "the DROP must precede every CREATE CONSTRAINT, or the load runs under the old "
                        + "constraint it exists to remove, got: " + out);
        for (String stmt : Schema.MIGRATIONS) {
            assertTrue(out.contains(stmt + ";"),
                    "every Schema.MIGRATIONS statement must reach the script: " + stmt);
        }
    }

    @Test
    void theWipePreambleMatchesAV2RootByIdAndALegacyV1RootByNameButNotAnotherApplication()
            throws IOException {
        // v2 roots are keyed on `id`, which is unique-constrained and exact -- but legacy v1 roots
        // (GraphProjector.java) never write `id` at all, only `name`. An id-only match would orphan
        // a prior v1 graph of the same app instead of replacing it; an ungated `a.name =` disjunct
        // reaches every v2 root carrying that display name, including roots this analyzer never
        // minted, since `name` stopped being unique once the id re-key landed. The predicate must
        // do both without regressing either: `a.id = <id> OR (a.id IS NULL AND a.name = <name>)`.
        // It does not separate two services sharing an --app-name -- CanId.applicationId is
        // "can://" + appName, so those share an id and are one node regardless.
        String out = CypherWriter.renderCypher(new RowBuilder().finish(), "app");

        assertTrue(out.contains("a.id = 'can://app'"),
                "must reach a v2 root of THIS app by its unique id, got: " + out);
        assertTrue(out.contains("a.id IS NULL AND a.name = 'app'"),
                "must reach a legacy v1 root (no id) by name, guarded by id IS NULL, got: " + out);
        // Every `a.name =` in the predicate must be inside the `a.id IS NULL AND ...` guard: an
        // unguarded one reaches v2 roots this analyzer never minted that carry the same name.
        assertFalse(out.replace("a.id IS NULL AND a.name = 'app'", "").contains("a.name ="),
                "a.name must never be matched outside the a.id IS NULL guard -- that would reach "
                        + "any v2 root carrying this display name, not just this app's, got: " + out);
        assertFalse(out.contains("JApplication {name:"),
                "the wipe must not property-match :JApplication by its no-longer-unique name: " + out);
    }

    @Test
    void anEmptyApplicationNameIsRefusedRatherThanEmittingAnUnscopedWipe() {
        // `STARTS WITH ''` matches every node in the database, so an empty --app-name would turn
        // the prefix sweep into "delete every can:// node any analyzer ever wrote". There is no
        // sane statement to emit here, so refuse to emit one at all (same call as
        // codeanalyzer-python's application_prefix). Blank is empty with extra steps.
        for (String name : new String[] {"", "   ", null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CypherWriter.renderCypher(new RowBuilder().finish(), name),
                    "an unscoped destructive statement must never be rendered, app name: " + name);
        }
        // ... and the guard lives on the prefix itself, so every future caller inherits it.
        assertEquals("can://app/", CypherWriter.applicationPrefix("app"),
                "the swept prefix must end in the separator, or `can://appX` is in scope too");
    }
}
