package com.ibm.cldk.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real WALA over the l4-sdg-test fixture: heap round-trip yields points-to ddg additions. */
@Tag("realworld")
class WalaL4ModRefTest {

    private static final String FIXTURE = "src/test/resources/test-applications/l4-sdg-test";

    /**
     * The heap round trip, from {@code Heap.java}:
     * <pre>
     * 14:     public int roundTrip(int v) {
     * 15:         put(v);        // writes this.box  -> body node 15:9
     * 16:         int r = get(); // reads  this.box  -> body node 16:9
     * 17:         return r;
     * 18:     }
     * </pre>
     */
    private static final String PUT_CALL = "15:9";
    private static final String GET_CALL = "16:9";

    @Test
    void heapRoundTripGainsPointsToEdgesWithoutLosingSsaOnes(@TempDir Path tmp) throws Exception {
        Map<String, JModule> modules = L1Extractor.extractAll(
                Paths.get(FIXTURE), "l4-sdg-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        Optional<WalaAnalysis> wala = WalaAnalysis.of(compileFixture(tmp), null, null, "rta");
        // Not an assumption: WalaAnalysis.of swallows every Throwable, so a regression inside it
        // would silently downgrade this gate to a skip.
        assertTrue(wala.isPresent(), "WalaAnalysis.of must succeed over the compiled fixture");

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
        roundTrip.getDdg().add(edge(PUT_CALL, GET_CALL, "box", List.of("ssa")));

        // Deep-copy the snapshot: sharing the JDdgEdge references would compare a rewritten edge
        // with itself, so an in-place mutation of an L3 edge would slip through the gate below.
        List<List<JDdgEdge>> before = new ArrayList<>();
        for (JCallable callable : callables) {
            before.add(callable.getDdg().stream()
                    .map(e -> edge(e.getSrc(), e.getDst(), e.getVar(), e.getProv()))
                    .collect(Collectors.toList()));
        }

        L4WalaOverlays.apply(wala.get(), modules, 3);

        for (int i = 0; i < callables.size(); i++) {
            assertTrue(callables.get(i).getDdg().containsAll(before.get(i)),
                    "L3 ssa edges survive untouched and unrewritten (L3 ⊆ L4): "
                            + callables.get(i).getId());
        }

        // Pin the whole tuple, not just the provenance: the endpoints are the projection of WALA's
        // caller-side heap statements onto the calls they decorate, so a regression that lost the
        // attribution (both ids collapsing to the "<line>:0" sentinel, or landing on the wrong call
        // site) would still satisfy a bare "some edge is points-to" assertion.
        assertTrue(roundTrip.getDdg().contains(edge(PUT_CALL, GET_CALL, "box", List.of("points-to"))),
                "the this.box write→read round-trip is a points-to dependence from the put call to "
                        + "the get call, keyed on the field: " + roundTrip.getDdg());

        // ...and the endpoints must be real body nodes of this callable, not merely well-formed ids.
        assertTrue(roundTrip.getBody().keySet().containsAll(List.of(PUT_CALL, GET_CALL)),
                "both endpoints are body nodes of roundTrip: " + roundTrip.getBody().keySet());
    }

    private static JDdgEdge edge(String src, String dst, String var, List<String> prov) {
        JDdgEdge edge = new JDdgEdge();
        edge.setSrc(src);
        edge.setDst(dst);
        edge.setVar(var);
        edge.getProv().addAll(prov);
        return edge;
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
