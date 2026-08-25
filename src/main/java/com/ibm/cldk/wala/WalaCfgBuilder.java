package com.ibm.cldk.wala;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import com.ibm.cldk.wala.WalaAnalysis.MethodIr;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code cfg} overlay on an existing {@link ControlFlowGraph} whose node set has
 * already been populated by {@link com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder}.
 * This builder only adds edges; it never creates or overwrites nodes (the additive invariant).
 *
 * <p>The block structure is taken from the WALA {@link SSACFG} of the method. Each block's
 * instructions are projected to body-node local ids via the {@link InstructionToNode} mapper.
 * Consecutive distinct nodes within a block receive a {@code fallthrough} edge; inter-block edges
 * carry a kind derived from the block terminator and the kind of the last mapped node:
 *
 * <ul>
 *   <li>{@code SSAConditionalBranchInstruction} — kind depends on the source construct (from
 *       {@link com.ibm.cldk.schema.JBodyNode#getKind()} of the branch node):
 *       <ul>
 *         <li>{@code "branch"} ({@code if}): javac compiles {@code if (cond)} as {@code if NOT cond
 *             goto else}, so {@code getTarget()} always points to the <em>else</em> arm (source
 *             {@code false}); the fall-through successor is the <em>then</em> arm (source
 *             {@code true}). Rule: taken = {@code "false"}, fall-through = {@code "true"}.
 *         <li>{@code "loop"} top-tested ({@code while}/{@code for}): javac emits {@code if NOT
 *             cond goto exit}, so {@code getTarget()} = exit (source {@code false}), fall-through =
 *             body (source {@code "true"}). Rule: same as {@code "branch"} — taken = {@code
 *             "false"}, fall-through = {@code "true"}.
 *         <li>{@code "loop"} bottom-tested ({@code do/while}): the conditional is at the bottom;
 *             javac emits {@code if cond goto body_start}, so {@code getTarget()} = body start
 *             (a back-edge, identified by the successor block having a <em>lower</em> number than
 *             the current block). Rule: taken = {@code "loop_back"}, fall-through = {@code "false"}.
 *       </ul>
 *   <li>{@code SSASwitchInstruction} → {@code switch_case} to every normal successor.
 *   <li>{@code SSAReturnInstruction} → {@code return} to {@code @exit}.
 *   <li>{@code SSAThrowInstruction} → {@code exception} to exceptional successors / {@code @exit}.
 *   <li>Goto or no explicit terminator: if the successor has a <em>lower</em> block number (a
 *       back-edge, i.e. the loop back-jump in a top-tested loop) → {@code loop_back}; otherwise
 *       → {@code fallthrough}.
 * </ul>
 *
 * <p>Block iteration is deterministic (sorted by WALA block number). Edge deduplication is handled
 * by {@link ControlFlowGraph#toCfgEdges}.
 *
 * <p>The {@code finally}-collapse property holds naturally: javac duplicates finally bodies in
 * bytecode, but all copies share the same source line, so the mapper returns the same node id for
 * every copy. Consecutive-distinct deduplication within a block then collapses them to one node,
 * and inter-block edges all target that single node.
 */
public final class WalaCfgBuilder {

    private WalaCfgBuilder() {}

    /**
     * Adds CFG edges derived from the WALA SSACFG of {@code m} to {@code g}. {@code g} must
     * already contain the node set produced by
     * {@link com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder#populate}; this method
     * only calls {@link ControlFlowGraph#addEdge}. Returns {@code g} for chaining.
     */
    public static ControlFlowGraph build(MethodIr m, ControlFlowGraph g, InstructionToNode mapper) {
        SSACFG cfg = m.ir.getControlFlowGraph();
        SSAInstruction[] instrs = m.ir.getInstructions();

        // Collect all blocks in deterministic order.
        List<ISSABasicBlock> allBlocks = new ArrayList<>();
        for (ISSABasicBlock bb : cfg) {
            allBlocks.add(bb);
        }
        allBlocks.sort((a, b) -> a.getNumber() - b.getNumber());

        // Build the mapped-node sequence for each block.
        Map<ISSABasicBlock, List<String>> blockNodes = new LinkedHashMap<>();
        for (ISSABasicBlock bb : allBlocks) {
            blockNodes.put(bb, collectNodes(bb, instrs, m, g, mapper));
        }

        for (ISSABasicBlock bb : allBlocks) {
            if (bb.isEntryBlock()) {
                // Wire @entry → first mapped node of every normal successor of the WALA entry block.
                for (ISSABasicBlock succ : sorted(cfg.getNormalSuccessors(bb))) {
                    String first = firstNodeOf(succ, blockNodes, cfg);
                    if (first != null) {
                        g.addEdge(ControlFlowGraph.ENTRY, first, "fallthrough");
                    }
                }
                continue;
            }
            if (bb.isExitBlock()) {
                continue;
            }

            List<String> nodes = blockNodes.get(bb);
            if (nodes.isEmpty()) {
                continue;
            }

            // Intra-block fallthrough edges between consecutive distinct nodes.
            for (int i = 0; i < nodes.size() - 1; i++) {
                g.addEdge(nodes.get(i), nodes.get(i + 1), "fallthrough");
            }

            String lastNode = nodes.get(nodes.size() - 1);
            SSAInstruction terminator = blockTerminator(bb, instrs);

            // Normal successors.
            List<ISSABasicBlock> normalSuccs = sorted(cfg.getNormalSuccessors(bb));
            wireNormalSuccessors(g, cfg, blockNodes, bb, lastNode, terminator, normalSuccs);

            // Exceptional successors.
            for (ISSABasicBlock succ : sorted(cfg.getExceptionalSuccessors(bb))) {
                if (succ.isExitBlock()) {
                    g.addEdge(lastNode, ControlFlowGraph.EXIT, "exception");
                } else {
                    String first = firstNodeOf(succ, blockNodes, cfg);
                    if (first != null) {
                        g.addEdge(lastNode, first, "exception");
                    }
                }
            }
        }

        // Orphan splice: some source statements (e.g. constant-initialized locals like
        // "int sum = 0;") have no corresponding WALA SSA instruction because the constant
        // value is folded into a phi node at the loop or join header. BodyNodeBuilder creates
        // a body node for every such statement, but the instruction-based edge pass above
        // never wires it in. Splice each orphan (a non-synthetic body node with no incoming
        // edges) into the CFG at its source position, between its nearest source-adjacent
        // wired predecessor and successor.
        spliceOrphans(g);

        return g;
    }

    // ----- normal-successor wiring -------------------------------------------------------

    private static void wireNormalSuccessors(
            ControlFlowGraph g,
            SSACFG cfg,
            Map<ISSABasicBlock, List<String>> blockNodes,
            ISSABasicBlock currentBb,
            String lastNode,
            SSAInstruction terminator,
            List<ISSABasicBlock> normalSuccs) {

        if (terminator instanceof SSAConditionalBranchInstruction) {
            wireConditional(g, cfg, blockNodes, currentBb, lastNode,
                    (SSAConditionalBranchInstruction) terminator, normalSuccs);
            return;
        }

        if (terminator instanceof SSASwitchInstruction) {
            for (ISSABasicBlock succ : normalSuccs) {
                if (succ.isExitBlock()) {
                    g.addEdge(lastNode, ControlFlowGraph.EXIT, "return");
                } else {
                    String first = firstNodeOf(succ, blockNodes, cfg);
                    if (first != null) {
                        g.addEdge(lastNode, first, "switch_case");
                    }
                }
            }
            return;
        }

        if (terminator instanceof SSAReturnInstruction) {
            // A return block's normal successor in WALA is the exit block.
            for (ISSABasicBlock succ : normalSuccs) {
                if (succ.isExitBlock()) {
                    g.addEdge(lastNode, ControlFlowGraph.EXIT, "return");
                } else {
                    String first = firstNodeOf(succ, blockNodes, cfg);
                    if (first != null) {
                        g.addEdge(lastNode, first, "return");
                    }
                }
            }
            if (normalSuccs.isEmpty()) {
                g.addEdge(lastNode, ControlFlowGraph.EXIT, "return");
            }
            return;
        }

        if (terminator instanceof SSAThrowInstruction) {
            for (ISSABasicBlock succ : normalSuccs) {
                if (succ.isExitBlock()) {
                    g.addEdge(lastNode, ControlFlowGraph.EXIT, "exception");
                } else {
                    String first = firstNodeOf(succ, blockNodes, cfg);
                    if (first != null) {
                        g.addEdge(lastNode, first, "exception");
                    }
                }
            }
            return;
        }

        // Goto or no explicit terminator. A successor with a lower block number is a loop
        // back-edge (the jump back to the loop condition in a top-tested while/for loop).
        for (ISSABasicBlock succ : normalSuccs) {
            if (succ.isExitBlock()) {
                g.addEdge(lastNode, ControlFlowGraph.EXIT, "return");
            } else {
                boolean isBackEdge = succ.getNumber() < currentBb.getNumber();
                String kind = isBackEdge ? "loop_back" : "fallthrough";
                String first = firstNodeOf(succ, blockNodes, cfg);
                if (first != null) {
                    g.addEdge(lastNode, first, kind);
                }
            }
        }
    }

    /**
     * Wires the two normal successors of a conditional branch, deriving the edge kinds from the
     * source construct recorded in the body-node graph.
     *
     * <p>javac uniformly compiles conditional tests by branching on the <em>negated</em> condition:
     * {@code if (cond)} becomes {@code if NOT cond goto else/exit}, so
     * {@link SSAConditionalBranchInstruction#getTarget()} always points to the "negative" target
     * (the else arm for an {@code if}, the loop exit for a top-tested loop). The exception is a
     * bottom-tested {@code do/while}, where the branch fires on the <em>positive</em> condition back
     * to the loop body — identified by the taken block having a <em>lower</em> block number.
     *
     * <p>Kind assignment:
     * <ul>
     *   <li>Taken block (getTarget()) is a <em>back-edge</em> (lower block number) AND node kind is
     *       {@code "loop"}: this is a do/while bottom test — taken → {@code "loop_back"}, fall-through
     *       → {@code "false"}.
     *   <li>Otherwise (if statement or top-tested loop): taken → {@code "false"}, fall-through →
     *       {@code "true"}.
     * </ul>
     */
    private static void wireConditional(
            ControlFlowGraph g,
            SSACFG cfg,
            Map<ISSABasicBlock, List<String>> blockNodes,
            ISSABasicBlock currentBb,
            String lastNode,
            SSAConditionalBranchInstruction branch,
            List<ISSABasicBlock> normalSuccs) {

        int takenPc = branch.getTarget();

        // Identify the taken block by matching the branch target PC to block first-instruction.
        ISSABasicBlock takenBlock = null;
        for (ISSABasicBlock succ : normalSuccs) {
            if (!succ.isExitBlock() && succ.getFirstInstructionIndex() == takenPc) {
                takenBlock = succ;
                break;
            }
        }
        // Fallback when no block matches the target PC (degenerate): higher block number = taken.
        if (takenBlock == null && normalSuccs.size() == 2) {
            ISSABasicBlock s0 = normalSuccs.get(0);
            ISSABasicBlock s1 = normalSuccs.get(1);
            takenBlock = (s0.getNumber() > s1.getNumber()) ? s0 : s1;
        }

        // Determine whether the taken block is a back-edge (do/while bottom test).
        boolean takenIsBackEdge = takenBlock != null
                && !takenBlock.isExitBlock()
                && takenBlock.getNumber() < currentBb.getNumber();

        // Read the source-construct kind stored in the graph for this node.
        JBodyNode nodeObj = g.nodes().get(lastNode);
        String nodeKind = nodeObj != null ? nodeObj.getKind() : "";

        for (ISSABasicBlock succ : normalSuccs) {
            boolean isTaken = (succ == takenBlock);
            String kind;
            if ("loop".equals(nodeKind) && takenIsBackEdge) {
                // do/while bottom test: taken back-edge is the loop body re-entry.
                kind = isTaken ? "loop_back" : "false";
            } else {
                // if-statement or top-tested loop: taken is the negative/exit direction.
                kind = isTaken ? "false" : "true";
            }

            if (succ.isExitBlock()) {
                g.addEdge(lastNode, ControlFlowGraph.EXIT, kind);
            } else {
                String first = firstNodeOf(succ, blockNodes, cfg);
                if (first != null) {
                    g.addEdge(lastNode, first, kind);
                }
            }
        }
    }

    // ----- helpers -----------------------------------------------------------------------

    /**
     * Collects the sequence of distinct mapped node ids for the instructions of {@code bb}.
     * Null instruction slots and instructions that do not map to any node in {@code g} are skipped.
     * Consecutive duplicates are collapsed so intra-block repetition does not produce self-loops.
     */
    private static List<String> collectNodes(
            ISSABasicBlock bb,
            SSAInstruction[] instrs,
            MethodIr m,
            ControlFlowGraph g,
            InstructionToNode mapper) {

        List<String> result = new ArrayList<>();
        int first = bb.getFirstInstructionIndex();
        int last = bb.getLastInstructionIndex();
        for (int i = first; i <= last; i++) {
            if (i < 0 || i >= instrs.length) {
                continue;
            }
            SSAInstruction ins = instrs[i];
            if (ins == null) {
                continue;
            }
            int line = sourceLine(m, i);
            String nodeId = mapper.map(ins, line);
            if (!g.hasNode(nodeId)) {
                continue;
            }
            if (result.isEmpty() || !nodeId.equals(result.get(result.size() - 1))) {
                result.add(nodeId);
            }
        }
        return result;
    }

    /**
     * Returns the first mapped node reachable from {@code bb}: if the block has nodes, the first
     * one; otherwise look through its normal successors (handles WALA's empty synthetic blocks).
     * Returns {@code null} when no node can be found.
     */
    private static String firstNodeOf(
            ISSABasicBlock bb,
            Map<ISSABasicBlock, List<String>> blockNodes,
            SSACFG cfg) {

        if (bb.isExitBlock()) {
            return ControlFlowGraph.EXIT;
        }
        List<String> nodes = blockNodes.getOrDefault(bb, java.util.List.of());
        if (!nodes.isEmpty()) {
            return nodes.get(0);
        }
        // Empty block — look through normal successors.
        for (ISSABasicBlock succ : sorted(cfg.getNormalSuccessors(bb))) {
            String s = firstNodeOf(succ, blockNodes, cfg);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** The last non-null instruction in {@code bb}'s instruction range, or {@code null}. */
    private static SSAInstruction blockTerminator(ISSABasicBlock bb, SSAInstruction[] instrs) {
        for (int i = bb.getLastInstructionIndex(); i >= bb.getFirstInstructionIndex(); i--) {
            if (i >= 0 && i < instrs.length && instrs[i] != null) {
                return instrs[i];
            }
        }
        return null;
    }

    /** Sorts a collection of basic blocks by block number for deterministic iteration. */
    private static List<ISSABasicBlock> sorted(Collection<ISSABasicBlock> blocks) {
        List<ISSABasicBlock> list = new ArrayList<>(blocks);
        list.sort((a, b) -> a.getNumber() - b.getNumber());
        return list;
    }

    /**
     * Source line for instruction at {@code idx} in {@code m}. Returns {@code -1} when the
     * line-number table is absent or the index is out of range.
     */
    private static int sourceLine(MethodIr m, int idx) {
        try {
            return m.method.getLineNumber(m.bytecode.getBytecodeIndex(idx));
        } catch (Throwable t) {
            return -1;
        }
    }

    // ----- orphan splice -----------------------------------------------------------------

    /**
     * Ensures every non-synthetic body node is reachable from {@code @entry} by splicing
     * instruction-less orphan nodes into the CFG in source order.
     *
     * <p>WALA folds constant-initialized local variables (e.g. {@code int sum = 0;} before a
     * loop) into phi nodes at the loop header, leaving the corresponding source-line block empty.
     * {@link BodyNodeBuilder} creates a body node for every such statement, but the instruction-
     * based edge pass in {@link #build} never wires it in because no instruction maps to it.
     * The result is an orphan node (no incoming edges, not {@code @entry}/{@code @exit}) that
     * fails reachability checks.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Collect all non-synthetic, non-sentinel nodes with an empty predecessor list.
     *   <li>Sort them ascending by (line, col) so that multiple orphans in the same block are
     *       processed in textual order (each splice is visible to the next iteration).
     *   <li>For each orphan, find the "crossing edge" — an existing edge (pred, succ) such that
     *       pred appears before the orphan in source order and succ appears after it. Among all
     *       crossing edges, pick the one whose pred is closest to the orphan (highest line:col).
     *   <li>Remove the crossing edge and insert pred→orphan (fallthrough) + orphan→succ (fallthrough).
     * </ol>
     *
     * <p>If no crossing edge exists for an orphan (degenerate: orphan is after the last wired node
     * with no successor), the orphan is skipped — this is safe because it means no instruction
     * ever executes after the orphan's source position, so the node is dead code in the bytecode.
     */
    private static void spliceOrphans(ControlFlowGraph g) {
        List<String> orphans = new ArrayList<>();
        for (String id : g.nodes().keySet()) {
            if (id.startsWith("@")) {
                continue;
            }
            if (isSentinel(id)) {
                continue;
            }
            if (g.predecessors(id).isEmpty()) {
                orphans.add(id);
            }
        }
        if (orphans.isEmpty()) {
            return;
        }
        orphans.sort(Comparator.comparingInt(WalaCfgBuilder::sourceLineOf)
                .thenComparingInt(WalaCfgBuilder::sourceColOf));

        for (String orphan : orphans) {
            int oLine = sourceLineOf(orphan);
            int oCol = sourceColOf(orphan);
            List<JCfgEdge> allEdges = g.toCfgEdges();
            JCfgEdge crossing = null;
            int bestSrcLine = -1;
            int bestSrcCol = -1;
            for (JCfgEdge e : allEdges) {
                String src = e.getSrc();
                String dst = e.getDst();
                int sLine = sourceLineOf(src);
                int sCol = sourceColOf(src);
                int dLine = sourceLineOf(dst);
                int dCol = sourceColOf(dst);
                boolean srcBefore = sLine < oLine || (sLine == oLine && sCol < oCol);
                boolean dstAfter = dLine > oLine || (dLine == oLine && dCol > oCol);
                if (srcBefore && dstAfter) {
                    if (crossing == null
                            || sLine > bestSrcLine
                            || (sLine == bestSrcLine && sCol > bestSrcCol)) {
                        crossing = e;
                        bestSrcLine = sLine;
                        bestSrcCol = sCol;
                    }
                }
            }
            if (crossing == null) {
                continue;
            }
            g.removeEdge(crossing.getSrc(), crossing.getDst(), crossing.getKind());
            g.addEdge(crossing.getSrc(), orphan, "fallthrough");
            g.addEdge(orphan, crossing.getDst(), "fallthrough");
        }
    }

    /**
     * Returns {@code true} when {@code id} is a sentinel node (a zero-column body node with
     * no real source position, of the form {@code line:0}).
     */
    private static boolean isSentinel(String id) {
        return id != null && id.matches("\\d+:0");
    }

    /**
     * Parses the line component from a {@code line:col} id. Returns {@code 0} for {@code @entry},
     * {@link Integer#MAX_VALUE} for {@code @exit}, and {@code -1} when the id is unparseable.
     */
    private static int sourceLineOf(String id) {
        if (ControlFlowGraph.ENTRY.equals(id)) {
            return 0;
        }
        if (ControlFlowGraph.EXIT.equals(id)) {
            return Integer.MAX_VALUE;
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(0, colon));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses the column component from a {@code line:col} id. Returns {@code 0} for {@code @entry}
     * and when no column segment is present; returns {@link Integer#MAX_VALUE} for {@code @exit}.
     */
    private static int sourceColOf(String id) {
        if (ControlFlowGraph.ENTRY.equals(id)) {
            return 0;
        }
        if (ControlFlowGraph.EXIT.equals(id)) {
            return Integer.MAX_VALUE;
        }
        int colon = id.indexOf(':');
        if (colon < 0 || colon + 1 >= id.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(colon + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
