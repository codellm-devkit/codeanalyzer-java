package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The incremental push deletes, and a delete anchored on nothing deletes everything that matches.
 *
 * <p>{@code _module} is a shared convention, not a java-private property: the sibling analyzers set
 * it on their own nodes with the same value (the module's file key). An unlabelled
 * {@code MATCH (x {_module: $m})} therefore reaches THEIR nodes wherever a file key collides in a
 * shared database (#213). These tests pin the anchor that stops it.
 *
 * <p>They assert the statement text rather than round-tripping through Neo4j deliberately: the
 * live-database path needs Docker and does not run everywhere, and the property that makes the fix
 * correct -- which labels the match is confined to -- is fully determined by the catalogs.
 */
class BoltWriterPurgeTest {

    private static List<String> moduleOwnedLabels() {
        List<String> labels = new ArrayList<>();
        for (List<SchemaCatalog.NodeLabel> catalog :
                Arrays.asList(SchemaCatalog.NODE_LABELS, V2SchemaCatalog.NODE_LABELS)) {
            for (SchemaCatalog.NodeLabel nl : catalog) {
                if (nl.properties.containsKey("_module") && !labels.contains(nl.label)) {
                    labels.add(nl.label);
                }
            }
        }
        return labels;
    }

    @Test
    void neitherPurgeStatementMatchesAnUnlabelledNode() {
        for (String stmt : new String[] {BoltWriter.PURGE_MODULE_EDGES, BoltWriter.PURGE_VANISHED_NODES}) {
            assertFalse(stmt.contains("(x {_module"),
                    "an unlabelled _module match reaches a sibling analyzer's nodes: " + stmt);
            assertTrue(stmt.contains("(x:" + CypherWriter.MODULE_OWNED + ")"),
                    "every _module match must be anchored on java-owned labels: " + stmt);
        }
    }

    @Test
    void theAnchorCoversEveryLabelThatCarriesModule() {
        // A label that carries _module but is missing from the anchor would stop being purged --
        // stale nodes surviving a push, the opposite failure from deleting a sibling's.
        for (String label : moduleOwnedLabels()) {
            assertTrue(Arrays.asList(CypherWriter.MODULE_OWNED.split("\\|")).contains(label),
                    label + " carries _module but is not in the purge anchor");
        }
    }

    @Test
    void theAnchorSpansBothSchemaGenerations() {
        // BoltWriter is schema-agnostic and may be pushing either generation, so a v2-only anchor
        // would silently stop purging a v1 graph.
        List<String> anchor = Arrays.asList(CypherWriter.MODULE_OWNED.split("\\|"));
        assertTrue(anchor.contains("JCompilationUnit"), "v1's module-owned label must be covered");
        assertTrue(anchor.contains("JModule"), "v2's module-owned label must be covered");
    }

    @Test
    void theAnchorExcludesCrossLanguageAndSharedNodes() {
        // Artifact/Package/ConfigKey are deliberately un-prefixed cross-language merge targets, and
        // JPackage/JAnnotation/JExternal are shared across modules. None carries _module, so none
        // may appear in an anchor used for deletion -- see CypherWriter.DESCENDANTS' javadoc.
        List<String> anchor = Arrays.asList(CypherWriter.MODULE_OWNED.split("\\|"));
        for (String shared : new String[] {"Artifact", "Package", "ConfigKey", "JPackage",
                "JAnnotation", "JExternal", "JApplication"}) {
            assertFalse(anchor.contains(shared), shared + " must never be reachable by a purge");
        }
    }

    @Test
    void theAnchorIsNotEmpty() {
        // A pattern that degraded to "" would render `MATCH (x:)`, which is a syntax error rather
        // than a silent over-delete -- but assert it anyway, since the value is derived.
        assertFalse(CypherWriter.MODULE_OWNED.isEmpty());
        assertFalse(CypherWriter.MODULE_OWNED.startsWith("|"));
        assertFalse(CypherWriter.MODULE_OWNED.endsWith("|"));
    }
}
