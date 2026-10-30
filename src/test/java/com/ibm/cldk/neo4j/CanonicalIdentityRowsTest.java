package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalIdentityRowsTest {
    @Test
    void conflictingCanonicalEntityPropertiesAreRejected() {
        RowBuilder builder = new RowBuilder();
        String id = "can://team/app/java/Foo.java/Foo";
        builder.node(List.of("JSymbol"), "id", id, Map.of("kind", "class"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> builder.node(List.of("JType"), "id", id, Map.of("kind", "method")));
    }

    @Test
    void canonicalIdsDeduplicateAcrossLabelsWithoutCollapsingEdgeKindsOrSites() throws Exception {
        var fixture = JsonParser.parseReader(new InputStreamReader(
                getClass().getResourceAsStream("/identity/namespaces.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("duplicate_module_rows");
        RowBuilder builder = new RowBuilder();
        GraphRows.NodeRef first = null;
        GraphRows.NodeRef second = null;
        for (var row : fixture.getAsJsonArray("rows")) {
            String id = row.getAsJsonObject().get("module_id").getAsString();
            var ref = builder.node(List.of("JModule"), "id", id, Map.of("id", id));
            if (first == null) first = ref;
            else if (!first.value.equals(id)) second = ref;
        }
        assertEquals(fixture.get("expected_unique_id_count").getAsInt(), builder.finish().nodes.size());
        var alternate = builder.node(List.of("JSymbol"), "id", first.value, Map.of("id", first.value));
        builder.keyedEdge("J_CALLS", first, second, Map.of(), "site1");
        builder.keyedEdge("J_CALLS", alternate, second, Map.of(), "site1");
        builder.keyedEdge("J_CALLS", first, second, Map.of(), "site2");
        builder.keyedEdge("J_RESOLVES_TO", first, second, Map.of(), "site1");
        var rows = builder.finish();
        assertEquals(2, rows.nodes.size());
        assertEquals(3, rows.edges.size());
        org.junit.jupiter.api.Assertions.assertTrue(rows.nodes.stream()
                .filter(n -> n.value.equals(alternate.value)).findFirst().orElseThrow()
                .labels.containsAll(List.of("JModule", "JSymbol", "JCanNode")));
    }
}
