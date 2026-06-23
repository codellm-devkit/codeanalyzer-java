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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Unit-level guard for the level-2 call-graph projection (issue #158). The call-graph {@code source}
 * /{@code target} vertices rewrite constructor signatures from {@code <init>(...)} to the simple
 * class name for readability, while the {@code :JCallable} node id keeps the raw {@code <init>(...)}
 * signature. {@link GraphProjector} must key {@code J_CALLS} endpoints off {@code callable_declaration}
 * (which preserves {@code <init>}) so constructor call edges resolve to their nodes instead of being
 * silently gated out.
 */
public class GraphProjectorCallGraphTest {

    private static final String FQN = "com.x.Foo";

    /** A compilation unit declaring {@code com.x.Foo} with a constructor and a {@code bar()} method. */
    private static Map<String, JavaCompilationUnit> symbolTable() {
        Type type = new Type();
        Map<String, Callable> callables = new HashMap<>();
        callables.put("<init>()", callable("<init>()"));
        callables.put("bar()", callable("bar()"));
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

    private static JsonObject vertex(String typeDecl, String signature, String callableDeclaration) {
        JsonObject v = new JsonObject();
        v.addProperty("type_declaration", typeDecl);
        v.addProperty("signature", signature);
        if (callableDeclaration != null) {
            v.addProperty("callable_declaration", callableDeclaration);
        }
        return v;
    }

    private static JsonObject edge(JsonObject source, JsonObject target) {
        JsonObject e = new JsonObject();
        e.add("source", source);
        e.add("target", target);
        e.addProperty("type", "CALL_DEP");
        e.addProperty("weight", "1");
        return e;
    }

    private static boolean hasCall(GraphRows rows, String fromId, String toId) {
        for (EdgeRow er : rows.edges) {
            if (er.type.equals("J_CALLS") && er.from.value.equals(fromId) && er.to.value.equals(toId)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void constructorCallEdgeResolvesViaCallableDeclaration() {
        JsonArray cg = new JsonArray();
        // bar() -> Foo() : the constructor target is rewritten to the simple class name in `signature`,
        // but `callable_declaration` keeps `<init>()`.
        cg.add(edge(vertex(FQN, "bar()", "bar()"),
                vertex(FQN, "Foo()", "<init>()")));

        GraphRows rows = GraphProjector.project(symbolTable(), cg, "app");

        assertTrue(hasCall(rows, FQN + "#bar()", FQN + "#<init>()"),
                "constructor call edge should resolve to the <init> node via callable_declaration");
        assertEquals(1, rows.edges.stream().filter(e -> e.type.equals("J_CALLS")).count(),
                "exactly one J_CALLS edge expected");
    }

    @Test
    public void unresolvableTargetIsGatedOut() {
        JsonArray cg = new JsonArray();
        // Target is a synthetic accessor with no JCallable node — must NOT produce a J_CALLS edge.
        cg.add(edge(vertex(FQN, "bar()", "bar()"),
                vertex(FQN, "access$000()", "access$000()")));

        GraphRows rows = GraphProjector.project(symbolTable(), cg, "app");

        assertFalse(rows.edges.stream().anyMatch(e -> e.type.equals("J_CALLS")),
                "edge to a callable with no node should be gated out");
    }

    @Test
    public void fallsBackToSignatureWhenCallableDeclarationAbsent() {
        JsonArray cg = new JsonArray();
        // Older payloads without callable_declaration must still resolve via signature.
        cg.add(edge(vertex(FQN, "bar()", null),
                vertex(FQN, "<init>()", null)));

        GraphRows rows = GraphProjector.project(symbolTable(), cg, "app");

        assertTrue(hasCall(rows, FQN + "#bar()", FQN + "#<init>()"),
                "method edge should resolve using signature when callable_declaration is absent");
    }
}
