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
    void theLabelAnchorNowCoversTheLegacyPathOnly() {
        // v2 nodes no longer carry `_module` at all -- they are scoped by can:// id prefix instead,
        // which is language-, application- AND module-scoped where a label anchor is none of those.
        // So MODULE_OWNED is now exactly the v1 vocabulary, and stays only because v1 ids are
        // FQN-shaped and have no prefix to scope by.
        List<String> anchor = Arrays.asList(CypherWriter.MODULE_OWNED.split("\\|"));
        assertTrue(anchor.contains("JCompilationUnit"), "v1's module-owned label must be covered");
        assertFalse(anchor.contains("JModule"),
                "v2 is prefix-scoped now; a v2 label here would mean _module is still being emitted");
    }

    @Test
    void theVersionTwoPurgeIsScopedByCanIdPrefixNotByLabelOrModule() {
        for (String stmt : new String[] {BoltWriter.PURGE_MODULE_EDGES_V2, BoltWriter.PURGE_VANISHED_NODES_V2}) {
            assertFalse(stmt.contains("_module"),
                    "the v2 purge must not reference the retired property: " + stmt);
            assertTrue(stmt.contains("x.id = $mid"), "the module itself is matched by equality: " + stmt);
            assertTrue(stmt.contains("STARTS WITH $pre"),
                    "descendants are matched on the id prefix: " + stmt);
            assertTrue(stmt.contains("(x:" + RowBuilder.CAN_NODE + ")"),
                    "a label is still needed so the prefix predicate can seek: " + stmt);
        }
    }

    @Test
    void theDescendantPrefixCarriesASeparatorSoASiblingPathCannotMatch() {
        // Without the trailing slash, `can://java/app/src/Foo.java` also prefixes
        // `can://java/app/src/Foo.javaX` and would purge a different module's nodes.
        assertTrue(BoltWriter.PURGE_VANISHED_NODES_V2.contains("$pre"));
        assertTrue(BoltWriter.descendantPrefix("can://java/app/src/Foo.java").endsWith("/"),
                "the descendant prefix must end with the path separator");
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
