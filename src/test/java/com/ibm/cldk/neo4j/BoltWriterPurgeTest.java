package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.CanId;
import org.junit.jupiter.api.Test;

/**
 * The incremental push deletes, and what it is scoped by decides whose data it deletes.
 *
 * <p>Scoping by {@code _module} — a bare project-relative file key — was wrong twice over. It is set
 * by the sibling analyzers too, so an unlabelled match reached their graphs (#213); and it carries
 * no application, so even once labelled it could not tell two java applications sharing
 * {@code src/main/java/Foo.java} apart. No label can: their nodes are identical in every respect
 * except identity.
 *
 * <p>Scoping on the module's own {@code can://} id fixes both at once, because the id is a path —
 * {@code can://<app>/java/<file>} — so a prefix match is language-, application- and module-scoped
 * simultaneously.
 *
 * <p>Asserted on the statement text rather than through a live database: that path needs Docker and
 * does not run everywhere, while the property that makes the fix correct is entirely in the
 * predicate.
 */
class BoltWriterPurgeTest {

    @Test
    void existingAnchorMustHaveExactMarkerAndRawIdentity() {
        var identity = java.util.Map.<String, Object>of("identity_version", 2,
                "application_name", "app", "identity_namespace", "team");
        BoltWriter.requireCompatibleApplicationIdentity("can://team/app", identity, identity);
        for (var legacy : java.util.List.of(java.util.Map.<String, Object>of(),
                java.util.Map.<String, Object>of("identity_version", 2.5, "application_name", "app", "identity_namespace", "team"),
                java.util.Map.<String, Object>of("identity_version", 1, "application_name", "app", "identity_namespace", "team"),
                java.util.Map.<String, Object>of("identity_version", 2, "application_name", "app", "identity_namespace", "other"))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> BoltWriter.requireCompatibleApplicationIdentity("can://team/app", identity, legacy));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> BoltWriter.requireCompatibleApplicationIdentity("can://team/app", java.util.Map.of(), identity));
    }

    @Test
    void incrementalHashesAreScopedToSelectedApplication() {
        assertTrue(BoltWriter.MODULE_HASHES_V2.contains("{id: $appId}"));
        assertTrue(BoltWriter.MODULE_HASHES_V2.contains("J_HAS_MODULE"));
    }

    @Test
    void thePurgeIsScopedByCanIdPrefixAndNotByTheRetiredProperty() {
        for (String stmt : new String[] {BoltWriter.PURGE_MODULE_EDGES_V2, BoltWriter.PURGE_VANISHED_NODES_V2}) {
            assertFalse(stmt.contains("_module"),
                    "the purge must not reference the retired property: " + stmt);
            assertTrue(stmt.contains("x.id = $mid"),
                    "the module itself is matched by equality: " + stmt);
            assertTrue(stmt.contains("STARTS WITH $pre"),
                    "its declarations are matched by id prefix: " + stmt);
        }
    }

    @Test
    void thePurgeCarriesALabelSoThePrefixPredicateCanSeek() {
        // Neo4j property indexes are label-scoped: without a label this scans every node in the
        // store, once per changed module. The label is a performance anchor, not a safety one --
        // the prefix does the scoping -- but it is java-namespaced anyway, so an anchoring mistake
        // still cannot reach another language's nodes.
        for (String stmt : new String[] {BoltWriter.PURGE_MODULE_EDGES_V2, BoltWriter.PURGE_VANISHED_NODES_V2}) {
            assertTrue(stmt.contains("(x:" + RowBuilder.CAN_NODE + ")"), stmt);
        }
    }

    @Test
    void theDescendantPrefixEndsWithTheSeparator() {
        // Dropping the separator is silent and wrong: <moduleId> also prefixes <moduleId>X,
        // so a bare STARTS WITH would purge a different module.
        String app = CanId.applicationId("app");
        String moduleId = CanId.moduleId(app, "src/Foo.java");
        String prefix = BoltWriter.descendantPrefix(moduleId);
        assertTrue(prefix.endsWith("/"), prefix);
        assertFalse((moduleId + "X").startsWith(prefix),
                "a sibling module whose path merely starts the same way must not match");
        assertTrue(CanId.childId(CanId.childId(moduleId, "Foo"), "m()").startsWith(prefix),
                "a real declaration of this module must match");
    }

    @Test
    void orphanPruneNeverFallsBackToLegacyName() {
        String stmt = BoltWriter.PRUNE_VANISHED_UNITS_V2;
        assertTrue(stmt.contains("a.id = $appId"));
        assertFalse(stmt.contains("a.name"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("can://team/app/artifact/"),
                BoltWriter.artifactPrefixes("can://team/app", "app"));
    }

    @Test
    void aCanIdIsRecognizedAndAVersionOneIdIsNot() {
        // What selects the purge path at all: v1 ids are FQN-shaped and carry no application
        // segment, so there is nothing to prefix and the purge is skipped rather than mis-scoped.
        assertTrue(RowBuilder.isCanId(CanId.moduleId(CanId.applicationId("app"), "src/Foo.java")));
        assertFalse(RowBuilder.isCanId("com.l4.Arity#caller(int, int)@22:16-22:25"));
        assertFalse(RowBuilder.isCanId(null));
    }
}
