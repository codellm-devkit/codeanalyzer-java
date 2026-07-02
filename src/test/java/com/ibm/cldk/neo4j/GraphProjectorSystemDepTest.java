/*
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.entities.Type;
import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level guard for the level-3 system-dependency-graph projection: method-level SDG edges
 * become {@code J_CONTROL_DEP}/{@code J_DATA_DEP}/{@code J_HEAP_DATA_DEP} relationships between
 * the same {@code :JCallable} nodes the call graph resolves to. The dependence kind must ride in
 * the relationship type (the writers MERGE one relationship per (type, source, target), so a
 * pair with both a control and a data dependence would otherwise lose one), and edges are gated
 * out when either endpoint has no node — the same rules as {@code J_CALLS}.
 */
public class GraphProjectorSystemDepTest {

    private static final String FQN = "com.x.Foo";

    private static Map<String, JavaCompilationUnit> symbolTable() {
        Type type = new Type();
        Map<String, Callable> callables = new HashMap<>();
        callables.put("bar()", callable("bar()"));
        callables.put("baz()", callable("baz()"));
        type.setCallableDeclarations(callables);

        Map<String, Type> types = new HashMap<>();
        types.put(FQN, type);

        JavaCompilationUnit cu = new JavaCompilationUnit();
        cu.setFilePath("Foo.java");
        cu.setTypeDeclarations(types);

        Map<String, JavaCompilationUnit> st = new HashMap<>();
        st.put("Foo.java", cu);
        return st;
    }

    private static Callable callable(String signature) {
        Callable c = new Callable();
        c.setSignature(signature);
        return c;
    }

    private static JsonObject vertex(String typeDecl, String signature) {
        JsonObject v = new JsonObject();
        v.addProperty("type_declaration", typeDecl);
        v.addProperty("signature", signature);
        v.addProperty("callable_declaration", signature);
        return v;
    }

    private static JsonObject sdgEdge(JsonObject source, JsonObject target, String type) {
        JsonObject e = new JsonObject();
        e.add("source", source);
        e.add("target", target);
        e.addProperty("type", type);
        e.addProperty("source_kind", "NORMAL");
        e.addProperty("destination_kind", "METHOD_ENTRY");
        e.addProperty("weight", "2");
        return e;
    }

    @Test
    public void bothDependenceKindsSurviveBetweenTheSamePair() {
        JsonArray sdg = new JsonArray();
        sdg.add(sdgEdge(vertex(FQN, "bar()"), vertex(FQN, "baz()"), "CONTROL_DEP"));
        sdg.add(sdgEdge(vertex(FQN, "bar()"), vertex(FQN, "baz()"), "DATA_DEP"));

        GraphRows rows = GraphProjector.project(symbolTable(), null, sdg, "app");

        for (String relType : new String[] { "J_CONTROL_DEP", "J_DATA_DEP" }) {
            assertEquals(1, rows.edges.stream().filter(e -> e.type.equals(relType)).count(),
                    "exactly one " + relType + " relationship expected");
        }
        for (EdgeRow er : rows.edges) {
            if (!er.type.startsWith("J_CONTROL") && !er.type.startsWith("J_DATA")) {
                continue;
            }
            assertEquals(FQN + "#bar()", er.from.value);
            assertEquals(FQN + "#baz()", er.to.value);
            assertEquals(2L, er.props.get("weight"));
        }
    }

    @Test
    public void unknownDependenceKindIsSkipped() {
        JsonArray sdg = new JsonArray();
        sdg.add(sdgEdge(vertex(FQN, "bar()"), vertex(FQN, "baz()"), "SOMETHING_ELSE"));

        GraphRows rows = GraphProjector.project(symbolTable(), null, sdg, "app");

        assertFalse(rows.edges.stream().anyMatch(e -> e.type.endsWith("_DEP")),
                "an edge outside WALA's closed dependence vocabulary should be skipped");
    }

    @Test
    public void unresolvableEndpointIsGatedOut() {
        JsonArray sdg = new JsonArray();
        // Target callable has no JCallable node (e.g. a WALA-synthesized method) — no edge.
        sdg.add(sdgEdge(vertex(FQN, "bar()"), vertex(FQN, "access$000()"), "DATA_DEP"));

        GraphRows rows = GraphProjector.project(symbolTable(), null, sdg, "app");

        assertFalse(rows.edges.stream().anyMatch(e -> e.type.equals("J_DATA_DEP")),
                "edge to a callable with no node should be gated out");
    }
}
