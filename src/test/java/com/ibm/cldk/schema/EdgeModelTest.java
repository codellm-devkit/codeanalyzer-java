package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The L3 edge models are plain data carriers, but two behaviours are load-bearing and worth pinning:
 * they serialise to the snake_case surface the schema tightened in {@link L3SchemaOracleTest}
 * (endpoints as body-node local ids, {@code var}/{@code prov} on ddg), and the callable's
 * {@code cfg}/{@code cdg}/{@code ddg} fields stay <em>absent</em> from the payload until L3 sets them —
 * Gson has no {@code serializeNulls()}, so a null field must not leak a key at L1/L2.
 */
class EdgeModelTest {

    @Test
    void ddgEdgeSerialisesVarAndProvenance() {
        JDdgEdge e = new JDdgEdge();
        e.setSrc("2:5");
        e.setDst("3:7");
        e.setVar("x");
        e.setProv(List.of("ssa"));
        JsonObject o = V2Json.compact().toJsonTree(e).getAsJsonObject();
        assertEquals("2:5", o.get("src").getAsString());
        assertEquals("3:7", o.get("dst").getAsString());
        assertEquals("x", o.get("var").getAsString());
        assertEquals("ssa", o.getAsJsonArray("prov").get(0).getAsString());
    }

    @Test
    void cfgEdgeSerialisesKind() {
        JCfgEdge e = new JCfgEdge();
        e.setSrc("@entry");
        e.setDst("2:5");
        e.setKind("fallthrough");
        JsonObject o = V2Json.compact().toJsonTree(e).getAsJsonObject();
        assertEquals("@entry", o.get("src").getAsString());
        assertEquals("fallthrough", o.get("kind").getAsString());
    }

    @Test
    void cdgEdgeSerialisesEndpointsOnly() {
        JCdgEdge e = new JCdgEdge();
        e.setSrc("2:5");
        e.setDst("3:7");
        JsonObject o = V2Json.compact().toJsonTree(e).getAsJsonObject();
        assertEquals("2:5", o.get("src").getAsString());
        assertEquals("3:7", o.get("dst").getAsString());
    }

    @Test
    void callableOmitsOverlaysWhenNull() {
        JCallable c = new JCallable();
        JsonObject o = V2Json.compact().toJsonTree(c).getAsJsonObject();
        assertFalse(o.has("cfg"), "cfg must be absent (no serializeNulls) below L3");
        assertFalse(o.has("cdg"), "cdg must be absent below L3");
        assertFalse(o.has("ddg"), "ddg must be absent below L3");
    }

    @Test
    void callableEmitsCfgWhenSet() {
        JCallable c = new JCallable();
        JCfgEdge e = new JCfgEdge();
        e.setSrc("@entry");
        e.setDst("2:5");
        e.setKind("fallthrough");
        c.setCfg(List.of(e));
        assertTrue(V2Json.compact().toJsonTree(c).getAsJsonObject().has("cfg"));
    }
}
