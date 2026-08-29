package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L4 model fields serialize under their canonical names and are absent (not empty) below L4. */
class L4ModelSerializationTest {

    @Test
    void syntheticVertexFieldsSerializeAsOfAndParent() {
        JBodyNode n = new JBodyNode();
        n.setKind("actual_in");
        n.setOf("arg0");
        n.setParent("5:16");
        String json = V2Json.compact().toJson(n);
        assertTrue(json.contains("\"of\":\"arg0\""), json);
        assertTrue(json.contains("\"parent\":\"5:16\""), json);
    }

    @Test
    void applicationCarriesParamEdgesOnlyWhenSet() {
        JApplication app = new JApplication();
        app.setId("can://java/x");
        assertFalse(V2Json.compact().toJson(app).contains("param_in"),
                "absent means no fact — no empty lists below L4");

        JIdEdge e = new JIdEdge();
        e.setSrc("can://java/x/f.java/A/a(int)@3:16/actual_in:0");
        e.setDst("can://java/x/f.java/A/b(int)@formal_in:0");
        app.setParamIn(List.of(e));
        String json = V2Json.compact().toJson(app);
        assertTrue(json.contains("\"param_in\""), json);
        assertTrue(json.contains("actual_in:0"), json);
    }

    @Test
    void emitterOverloadAttachesParamEdges() {
        JIdEdge in = new JIdEdge();
        in.setSrc("s");
        in.setDst("d");
        Analysis a = V2Emitter.emit("app", 4, Map.of(), "test", null, null, List.of(in), List.of());
        assertEquals(4, a.getMaxLevel());
        assertEquals(1, a.getApplication().getParamIn().size());
        assertTrue(a.getApplication().getParamOut() == null,
                "empty overlay is omitted, matching call_graph's absence rule");
    }
}
