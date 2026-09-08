package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.cldk.schema.JModule;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class L3WalaOverlaysTest {

    @SuppressWarnings("unchecked")
    private static String invokeDeriveApplicationId(Map<String, JModule> modules) throws Exception {
        Method method = L3WalaOverlays.class.getDeclaredMethod("deriveApplicationId", Map.class);
        method.setAccessible(true);
        return (String) method.invoke(null, modules);
    }

    @Test
    public void deriveApplicationIdFallsBackToCanUnknownWhenTheModuleHasNoId() throws Exception {
        // A module map from which no applicationId can be derived (its one module's id is unset)
        // must fall back to "can://unknown" -- not "can:///unknown" (the old SCHEME + "/" + "unknown"
        // spelling, back when SCHEME was "can://java") and not "can://java/unknown" (the pre-Task-1
        // shape).
        Map<String, JModule> modules = new LinkedHashMap<>();
        modules.put("src/A.java", new JModule());

        String applicationId = invokeDeriveApplicationId(modules);

        assertEquals("can://unknown", applicationId);
    }
}
