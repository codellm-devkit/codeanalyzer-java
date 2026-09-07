package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JConfigRead;
import com.ibm.cldk.schema.JConfigUseEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two dataflow tiers: closing a non-literal key over the callable's own DDG (L3) and over the
 * call graph (L4).
 *
 * <p>The assertions that matter are the <em>refusals</em>. A tier that closes a read it should not
 * have produces a confident wrong answer — an edge saying some code reads a key it never reads —
 * which is worse than the unresolved record it replaced. So each closing case is paired with the
 * ambiguous shape that must stay unresolved.
 */
class ConfigDataflowTierTest {

    private static final String APP = "dataflow-test";

    @TempDir
    Path root;

    private ConfigUses.Result run(String source, int analysisLevel) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Reader.java"), source, StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "HOME=/home/app\nPATH=/usr/bin\n",
                StandardCharsets.UTF_8);

        // The DDG is a parse-time overlay, so extract at 3 whenever a tier needs it; the tier gate
        // under test is the level passed to ConfigUses, not the level the tree was built at.
        int extractLevel = Math.max(analysisLevel, 1);
        Map<String, JModule> modules = L1Extractor.extractAll(
                root, APP, null, new LinkedHashMap<>(), extractLevel, 3, "ast");
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(root, APP, true, 262144);
        for (JArtifact a : artifacts.values()) {
            if (ConfigKeys.isEligible(a)) {
                a.setConfigKeys(ConfigKeys.extract(
                        a, DependencyView.readFromDisk(root, a.getPath()), true).keys);
            }
        }
        // Backfills `callee` on the call body nodes, which is what the interprocedural tier walks.
        L2CallGraph.Result l2 = analysisLevel >= 2
                ? L2CallGraph.build(APP, modules, null, false)
                : null;
        return ConfigUses.detect(APP, modules, artifacts, analysisLevel,
                l2 == null ? null : l2.callGraph());
    }

    private static Set<String> keysRead(ConfigUses.Result r) {
        return r.uses.stream()
                .map(e -> e.getDst().substring(e.getDst().lastIndexOf('/') + 1))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> pairs(ConfigUses.Result r) {
        return r.uses.stream()
                .map(e -> e.getSrc() + " -> " + e.getDst())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---- L3: close a local over the callable's own DDG -----------------------------------------

    private static final String LOCAL_VARIABLE =
            "package demo;\n"
                    + "public class Reader {\n"
                    + "    String home() { String k = \"HOME\"; return System.getenv(k); }\n"
                    + "}\n";

    @Test
    void aLocalIsNonLiteralAtLevelOne() throws Exception {
        ConfigUses.Result r = run(LOCAL_VARIABLE, 1);
        assertTrue(r.uses.isEmpty(), "no tier has run yet, so nothing should resolve");
        assertEquals(1, r.unresolved.size());
        assertEquals("non-literal", r.unresolved.get(0).getReason());
        assertEquals(List.of("literal"), r.unresolved.get(0).getProv(),
                "only the literal tier was attempted at -a 1");
    }

    @Test
    void aLocalClosesAtLevelThree() throws Exception {
        ConfigUses.Result r = run(LOCAL_VARIABLE, 3);
        assertEquals(Set.of("HOME"), keysRead(r), "the DDG should close k to \"HOME\"");
        assertEquals(List.of("dataflow"), r.uses.get(0).getProv(),
                "an edge's prov is the tier that CLOSED it, not every tier attempted");
        assertTrue(r.unresolved.isEmpty());
    }

    @Test
    void aLocalAssignedTwoDifferentLiteralsStaysUnresolved() throws Exception {
        // Both defs reach the call, and they disagree. Picking either would be a confident wrong
        // answer, so the read must stay unresolved at every level.
        ConfigUses.Result r = run(
                "package demo;\n"
                        + "public class Reader {\n"
                        + "    String pick(boolean b) {\n"
                        + "        String k = \"HOME\";\n"
                        + "        if (b) { k = \"PATH\"; }\n"
                        + "        return System.getenv(k);\n"
                        + "    }\n"
                        + "}\n",
                3);
        assertTrue(r.uses.isEmpty(), "ambiguous reaching defs must not resolve, got " + keysRead(r));
        assertEquals(1, r.unresolved.size());
        assertEquals(List.of("literal", "dataflow"), r.unresolved.get(0).getProv(),
                "prov records every tier attempted, so a failure says how hard we tried");
    }

    // ---- L4: close a parameter over the call graph ---------------------------------------------

    private static final String PARAMETER_AGREEING =
            "package demo;\n"
                    + "public class Reader {\n"
                    + "    private String read(String name) { return System.getenv(name); }\n"
                    + "    String a() { return read(\"HOME\"); }\n"
                    + "    String b() { return read(\"HOME\"); }\n"
                    + "}\n";

    @Test
    void aParameterDoesNotCloseBelowLevelFour() throws Exception {
        ConfigUses.Result r = run(PARAMETER_AGREEING, 3);
        assertTrue(r.uses.isEmpty(), "the intra tier cannot see callers, got " + keysRead(r));
    }

    @Test
    void aParameterClosesAtLevelFourWhenEveryCallerAgrees() throws Exception {
        ConfigUses.Result r = run(PARAMETER_AGREEING, 4);
        assertEquals(Set.of("HOME"), keysRead(r));
        assertEquals(List.of("dataflow"), r.uses.get(0).getProv());
    }

    @Test
    void aParameterStaysUnresolvedWhenCallersDisagree() throws Exception {
        ConfigUses.Result r = run(
                "package demo;\n"
                        + "public class Reader {\n"
                        + "    private String read(String name) { return System.getenv(name); }\n"
                        + "    String a() { return read(\"HOME\"); }\n"
                        + "    String b() { return read(\"PATH\"); }\n"
                        + "}\n",
                4);
        assertTrue(r.uses.isEmpty(),
                "two callers supplying different keys means the read is ambiguous, got "
                        + keysRead(r));
    }

    @Test
    void aParameterStaysUnresolvedWhenTheCalleeRebindsIt() throws Exception {
        // The caller's argument is not what the read sees, so closing from callers would misattribute.
        ConfigUses.Result r = run(
                "package demo;\n"
                        + "public class Reader {\n"
                        + "    private String read(String name) {\n"
                        + "        name = \"PATH\";\n"
                        + "        return System.getenv(name);\n"
                        + "    }\n"
                        + "    String a() { return read(\"HOME\"); }\n"
                        + "}\n",
                4);
        // The intra tier legitimately closes this to PATH — the rebinding IS what the callee reads.
        // What must never happen is closing it to HOME from the caller.
        assertFalse(keysRead(r).contains("HOME"),
                "a caller's argument must not win over a local rebinding, got " + keysRead(r));
    }

    // ---- the additive contract ------------------------------------------------------------------

    @Test
    void everyLevelIsASupersetOfTheOneBelow() throws Exception {
        String mixed =
                "package demo;\n"
                        + "public class Reader {\n"
                        + "    String direct() { return System.getenv(\"HOME\"); }\n"
                        + "    String local() { String k = \"PATH\"; return System.getenv(k); }\n"
                        + "    private String via(String name) { return System.getenv(name); }\n"
                        + "    String caller() { return via(\"HOME\"); }\n"
                        + "}\n";
        Set<String> l1 = pairs(run(mixed, 1));
        Set<String> l3 = pairs(run(mixed, 3));
        Set<String> l4 = pairs(run(mixed, 4));

        assertTrue(l3.containsAll(l1), "-a 3 dropped an edge -a 1 had: " + l1 + " vs " + l3);
        assertTrue(l4.containsAll(l3), "-a 4 dropped an edge -a 3 had: " + l3 + " vs " + l4);
        assertTrue(l3.size() > l1.size(), "the intra tier should have added something");
        assertTrue(l4.size() > l3.size(), "the interprocedural tier should have added something");
    }

    @Test
    void aLiteralTierEdgeKeepsItsProvenanceAtEveryLevel() throws Exception {
        // Not just present at -a 4 — still marked `literal`. The tier that closed a read is a fact
        // about that read, not about the level the analyzer happened to run at.
        for (int level : new int[] {1, 3, 4}) {
            ConfigUses.Result r = run(
                    "package demo;\n"
                            + "public class Reader {\n"
                            + "    String home() { return System.getenv(\"HOME\"); }\n"
                            + "}\n",
                    level);
            assertEquals(1, r.uses.size(), "level " + level);
            assertEquals(List.of("literal"), r.uses.get(0).getProv(), "level " + level);
        }
    }

    @Test
    void anUnresolvedReadReportsEveryTierAttempted() throws Exception {
        ConfigUses.Result r = run(
                "package demo;\n"
                        + "public class Reader {\n"
                        + "    String dyn(String s) { return System.getenv(s.trim()); }\n"
                        + "}\n",
                4);
        assertEquals(1, r.unresolved.size());
        JConfigRead read = r.unresolved.get(0);
        assertEquals("non-literal", read.getReason());
        assertEquals(List.of("literal", "dataflow"), read.getProv());
    }

    @Test
    void tierEdgesAreSortedLikeLiteralOnes() throws Exception {
        ConfigUses.Result r = run(
                "package demo;\n"
                        + "public class Reader {\n"
                        + "    String a() { String k = \"HOME\"; return System.getenv(k); }\n"
                        + "    String b() { String k = \"PATH\"; return System.getenv(k); }\n"
                        + "}\n",
                3);
        List<String> srcs = r.uses.stream().map(JConfigUseEdge::getSrc).collect(Collectors.toList());
        assertEquals(srcs.stream().sorted().collect(Collectors.toList()), srcs,
                "tier-produced edges must land in the same (src, dst) order as literal ones");
    }
}
