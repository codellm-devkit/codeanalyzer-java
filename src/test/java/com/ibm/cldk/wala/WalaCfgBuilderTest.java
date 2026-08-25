package com.ibm.cldk.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Realworld tests for {@link WalaCfgBuilder}: compiles a fixture, builds the SSACFG, and asserts
 * well-formedness of the resulting {@link ControlFlowGraph}.
 */
@Tag("realworld")
class WalaCfgBuilderTest {

    private static final String FIXTURE =
            "public class WalaCfgFixture {\n"
            + "    int compute(int x) {\n"
            + "        if (x > 0) {\n"
            + "            x = x + 1;\n"
            + "        } else {\n"
            + "            x = x - 1;\n"
            + "        }\n"
            + "        return x;\n"
            + "    }\n"
            + "}\n";

    @Test
    void cfgIsWellFormedForIfElse(@TempDir Path tmp) throws Exception {
        // Compile the fixture with debug info so WALA can recover source lines.
        Path src = tmp.resolve("WalaCfgFixture.java");
        Files.writeString(src, FIXTURE);
        int rc = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-g", "-d", tmp.toString(), src.toString());
        assertEquals(0, rc, "fixture compilation must succeed");

        String dir = tmp.toString();
        CodeAnalyzer.projectRootPom = dir;
        WalaAnalysis wala = WalaAnalysis.of(dir, null, null)
                .orElseThrow(() -> new AssertionError("WalaAnalysis.of must succeed"));

        WalaAnalysis.MethodIr mir = wala.applicationMethods().stream()
                .filter(m -> m.method.getName().toString().equals("compute"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("method 'compute' not found"));

        // Re-parse the same source with JavaParser; BodyNodeBuilder needs the same source string
        // for span computation.
        BlockStmt body = L3TestSupport.methodBody(FIXTURE, "compute");
        ControlFlowGraph g = new ControlFlowGraph();
        BodyNodeBuilder.populate(g, body, new LinkedHashMap<>(), L3TestSupport.ctx(FIXTURE));

        // Build the statementsByLine index and construct the mapper.
        Map<Integer, List<Statement>> byLine = InstructionToNode.statementsByLine(body);
        InstructionToNode mapper = new InstructionToNode(byLine);

        // Build CFG edges from the SSACFG.
        WalaCfgBuilder.build(mir, g, mapper);

        Set<String> allNodes = g.nodes().keySet();

        // (1) @entry and @exit must be present.
        assertTrue(g.hasNode(ControlFlowGraph.ENTRY), "@entry must exist");
        assertTrue(g.hasNode(ControlFlowGraph.EXIT), "@exit must exist");

        // (2) Every node must be reachable from @entry.
        Set<String> fromEntry = reachable(g, ControlFlowGraph.ENTRY);
        assertEquals(allNodes, fromEntry,
                "all nodes must be reachable from @entry; unreachable: " + diff(allNodes, fromEntry));

        // (3) Every node must reach @exit.
        Set<String> toExit = reverseReachable(g, ControlFlowGraph.EXIT);
        assertEquals(allNodes, toExit,
                "all nodes must reach @exit; nodes not reaching exit: " + diff(allNodes, toExit));

        // (4) The branch node must have both a "true" and a "false" out-edge.
        String branchNode = allNodes.stream()
                .filter(id -> {
                    JBodyNode n = g.nodes().get(id);
                    return n != null && "branch".equals(n.getKind());
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 'branch' node found in graph"));

        List<JCfgEdge> edges = g.toCfgEdges();
        boolean hasTrueEdge = edges.stream()
                .anyMatch(e -> branchNode.equals(e.getSrc()) && "true".equals(e.getKind()));
        boolean hasFalseEdge = edges.stream()
                .anyMatch(e -> branchNode.equals(e.getSrc()) && "false".equals(e.getKind()));
        assertTrue(hasTrueEdge, "branch node must have a 'true' out-edge");
        assertTrue(hasFalseEdge, "branch node must have a 'false' out-edge");
    }

    // ----- helpers -----------------------------------------------------------------------

    private static Set<String> reachable(ControlFlowGraph g, String start) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (visited.add(node)) {
                g.successors(node).forEach(queue::add);
            }
        }
        return visited;
    }

    private static Set<String> reverseReachable(ControlFlowGraph g, String start) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (visited.add(node)) {
                g.predecessors(node).forEach(queue::add);
            }
        }
        return visited;
    }

    private static Set<String> diff(Set<String> all, Set<String> found) {
        Set<String> missing = new LinkedHashSet<>(all);
        missing.removeAll(found);
        return missing;
    }
}
