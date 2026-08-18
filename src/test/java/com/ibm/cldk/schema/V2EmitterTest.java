package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the thin {@link V2Emitter} assembler wrapping pre-built modules into the v2 envelope. */
class V2EmitterTest {

    @Test
    void emit_wrapsModulesIntoEnvelopeAndApplication() {
        JModule module = new JModule();
        module.setId("can://java/myapp/src/Foo.java");
        module.setPackageName("com.example");
        Map<String, JModule> modules = new LinkedHashMap<>();
        modules.put("src/Foo.java", module);

        Analysis analysis = V2Emitter.emit("myapp", 1, modules);

        assertEquals("2.0.0", analysis.getSchemaVersion());
        assertEquals("java", analysis.getLanguage());
        assertEquals(1, analysis.getMaxLevel());
        assertEquals("can://java/myapp", analysis.getApplication().getId());
        assertEquals("application", analysis.getApplication().getKind());
        assertSame(module, analysis.getApplication().getSymbolTable().get("src/Foo.java"));
    }
}
