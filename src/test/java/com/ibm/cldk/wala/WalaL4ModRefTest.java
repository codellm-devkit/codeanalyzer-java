package com.ibm.cldk.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ibm.cldk.L4WalaOverlays;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real WALA over the l4-sdg-test fixture: heap round-trip yields points-to ddg additions. */
class WalaL4ModRefTest {

    private static final String FIXTURE = "src/test/resources/test-applications/l4-sdg-test";

    @Test
    void heapRoundTripGainsPointsToEdgesWithoutLosingSsaOnes(@TempDir Path tmp) throws Exception {
        Map<String, JModule> modules = L1Extractor.extractAll(
                Paths.get(FIXTURE), "l4-sdg-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        Optional<WalaAnalysis> wala = WalaAnalysis.of(compileFixture(tmp), null, null, "rta");
        assumeTrue(wala.isPresent(), "WALA build unavailable in this environment");

        JCallable roundTrip = modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .filter(t -> t.getId().endsWith("/Heap"))
                .flatMap(t -> t.getCallables().values().stream())
                .filter(c -> c.getId().endsWith("roundTrip(int)"))
                .findFirst().orElseThrow();

        // Snapshot every callable's L3 ddg, not just the one under test: additivity is the gate.
        // JCallable's equals is structural and its ddg is about to be mutated, so index by
        // position rather than hashing the callables themselves.
        List<JCallable> callables = modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(t -> t.getCallables().values().stream())
                .filter(c -> c.getDdg() != null)
                .collect(Collectors.toList());
        // The fixture's methods declare no locals, so the AST L3 engine emits no ssa edges of its
        // own — seed one, or the additivity gate below has nothing to be additive over. It shares
        // (src,dst,var) with the points-to edge WALA is about to derive and differs only in prov,
        // so it also pins prov as part of the dedup identity: collapse the two and one is lost.
        JDdgEdge seeded = new JDdgEdge();
        seeded.setSrc("15:9");
        seeded.setDst("16:9");
        seeded.setVar("box");
        seeded.getProv().add("ssa");
        roundTrip.getDdg().add(seeded);

        List<List<JDdgEdge>> before = new ArrayList<>();
        for (JCallable callable : callables) {
            before.add(List.copyOf(callable.getDdg()));
        }

        L4WalaOverlays.apply(wala.get(), modules, 3);

        for (int i = 0; i < callables.size(); i++) {
            assertTrue(callables.get(i).getDdg().containsAll(before.get(i)),
                    "L3 ssa edges survive untouched (L3 ⊆ L4): " + callables.get(i).getId());
        }
        assertTrue(roundTrip.getDdg().stream().anyMatch(e -> e.getProv().contains("points-to")),
                "the this.box write→read round-trip appears as a points-to dependence");
    }

    /** Compiles the fixture's sources into {@code tmp} and returns it as the WALA input root. */
    private static String compileFixture(Path tmp) throws Exception {
        List<String> sources;
        try (Stream<Path> walk = Files.walk(Paths.get(FIXTURE, "src", "main", "java"))) {
            sources = walk.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }
        String[] args = Stream.concat(
                Stream.of("-g", "-d", tmp.toString()), sources.stream()).toArray(String[]::new);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args),
                "fixture compilation must succeed");
        return tmp.toString();
    }
}
