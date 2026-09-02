package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * {@code can://java/<app>/<file>} — so a prefix match is language-, application- and module-scoped
 * simultaneously.
 *
 * <p>Asserted on the statement text rather than through a live database: that path needs Docker and
 * does not run everywhere, while the property that makes the fix correct is entirely in the
 * predicate.
 */
class BoltWriterPurgeTest {

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
        // Dropping the separator is silent and wrong: can://java/app/src/Foo.java also prefixes
        // can://java/app/src/Foo.javaX, so a bare STARTS WITH would purge a different module.
        String prefix = BoltWriter.descendantPrefix("can://java/app/src/Foo.java");
        assertTrue(prefix.endsWith("/"), prefix);
        assertFalse("can://java/app/src/Foo.javaX".startsWith(prefix),
                "a sibling module whose path merely starts the same way must not match");
        assertTrue("can://java/app/src/Foo.java/Foo/m()".startsWith(prefix),
                "a real declaration of this module must match");
    }

    @Test
    void aCanIdIsRecognizedAndAVersionOneIdIsNot() {
        // What selects the purge path at all: v1 ids are FQN-shaped and carry no application
        // segment, so there is nothing to prefix and the purge is skipped rather than mis-scoped.
        assertTrue(RowBuilder.isCanId("can://java/app/src/Foo.java"));
        assertFalse(RowBuilder.isCanId("com.l4.Arity#caller(int, int)@22:16-22:25"));
        assertFalse(RowBuilder.isCanId(null));
    }
}
