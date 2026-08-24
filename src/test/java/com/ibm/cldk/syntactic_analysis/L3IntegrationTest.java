package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The parse-time plumbing: {@code extractAll(..., analysisLevel, graphFieldDepth)} runs L3 inside the
 * L1 pass when the level is 3, completing bodies and populating {@code cfg}, and leaves those surfaces
 * absent below level 3. Runs the real extractor over the shared fixture — no CLI, no build.
 */
class L3IntegrationTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/call-graph-test");

    private static Optional<JCallable> findCallable(Map<String, JModule> modules, String signature) {
        for (JModule m : modules.values()) {
            Optional<JCallable> c = inTypes(m.getTypes().values(), signature);
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    private static Optional<JCallable> inTypes(Collection<JType> types, String signature) {
        for (JType t : types) {
            if (t.getCallables().containsKey(signature)) {
                return Optional.of(t.getCallables().get(signature));
            }
            Optional<JCallable> nested = inTypes(t.getTypes().values(), signature);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    @Test
    void level3PopulatesCfgAndCompletesBodyWithEntryExit() throws IOException {
        Map<String, JModule> modules =
                L1Extractor.extractAll(FIXTURE, "app", null, new LinkedHashMap<>(), 3, 3);
        JCallable hello = findCallable(modules, "helloString()").orElseThrow();
        assertNotNull(hello.getCfg(), "level 3 must populate cfg");
        assertFalse(hello.getCfg().isEmpty(), "helloString has statements, so its cfg has edges");
        assertTrue(hello.getBody().containsKey("@entry"), "the body is completed with a synthetic @entry");
        assertTrue(hello.getBody().containsKey("@exit"), "the body is completed with a synthetic @exit");
    }

    @Test
    void level1LeavesOverlaysAbsent() throws IOException {
        Map<String, JModule> modules =
                L1Extractor.extractAll(FIXTURE, "app", null, new LinkedHashMap<>(), 1, 3);
        JCallable hello = findCallable(modules, "helloString()").orElseThrow();
        assertNull(hello.getCfg(), "cfg must be absent below level 3");
        assertFalse(hello.getBody().containsKey("@entry"), "no synthetic body nodes below level 3");
    }
}
