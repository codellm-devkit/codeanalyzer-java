package com.ibm.cldk.wala;

import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import com.ibm.cldk.wala.WalaAnalysis.MethodIr;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import java.util.ArrayList;
import java.util.Collection;
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
 * carry a kind derived from the block terminator:
 * <ul>
 *   <li>{@code SSAConditionalBranchInstruction} → {@code true} / {@code false} to the two normal
 *       successors (the taken/branch-target block receives {@code true}, the fall-through
 *       {@code false}).
 *   <li>{@code SSASwitchInstruction} → {@code switch_case} to every normal successor.
 *   <li>{@code SSAReturnInstruction} → {@code return} to the single synthetic {@code @exit}.
 *   <li>{@code SSAThrowInstruction} → {@code exception} to exceptional successors; normal
 *       successor (WALA exit block) → {@code exception} to {@code @exit}.
 *   <li>{@code SSAGotoInstruction} or no explicit terminator → {@code fallthrough}.
 *   <li>Any normal successor that is the WALA exit block → {@code return} to {@code @exit}.
 *   <li>Any exceptional successor that is the WALA exit block → {@code exception} to {@code @exit}.
 * </ul>
 *
 * <p>Block iteration is deterministic (sorted by WALA block number). Edge deduplication is handled
 * by {@link ControlFlowGraph#toCfgEdges}.
 *
 * <p>The {@code finally}-collapse property holds naturally: javac duplicates finally bodies in
 * bytecode, but all copies share the same source line, so the mapper returns the same node id for
 * every copy. Consecutive-distinct deduplication within a block then collapses them to one node,
 * and inter-block edges all target that single node.
 *
 * <p>Terminator true/false ordering: WALA's {@link SSAConditionalBranchInstruction#getTarget()}
 * returns the bytecode PC of the taken-branch target. The normal successor block whose
 * {@link ISSABasicBlock#getFirstInstructionIndex()} equals that PC is labelled {@code true}; the
 * other is labelled {@code false}. If no match is found (degenerate case), the lower-numbered
 * block is labelled {@code false} and the higher {@code true}.
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
            wireNormalSuccessors(g, cfg, blockNodes, lastNode, terminator, normalSuccs);

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

        return g;
    }

    // ----- normal-successor wiring -------------------------------------------------------

    private static void wireNormalSuccessors(
            ControlFlowGraph g,
            SSACFG cfg,
            Map<ISSABasicBlock, List<String>> blockNodes,
            String lastNode,
            SSAInstruction terminator,
            List<ISSABasicBlock> normalSuccs) {

        if (terminator instanceof SSAConditionalBranchInstruction) {
            wireConditional(g, cfg, blockNodes, lastNode,
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

        // Goto or no explicit terminator: fallthrough to each normal successor.
        for (ISSABasicBlock succ : normalSuccs) {
            if (succ.isExitBlock()) {
                g.addEdge(lastNode, ControlFlowGraph.EXIT, "return");
            } else {
                String first = firstNodeOf(succ, blockNodes, cfg);
                if (first != null) {
                    g.addEdge(lastNode, first, "fallthrough");
                }
            }
        }
    }

    /**
     * Wires the two normal successors of a conditional branch.
     *
     * <p>WALA's {@link SSAConditionalBranchInstruction#getTarget()} returns the bytecode PC of the
     * taken-branch target. The normal successor block whose first instruction index matches that PC
     * is labelled {@code true}; the other is labelled {@code false}. When no block matches (rare
     * degenerate cases), the higher-numbered block is labelled {@code true} and the lower
     * {@code false} — this ensures both edge kinds are always emitted.
     */
    private static void wireConditional(
            ControlFlowGraph g,
            SSACFG cfg,
            Map<ISSABasicBlock, List<String>> blockNodes,
            String lastNode,
            SSAConditionalBranchInstruction branch,
            List<ISSABasicBlock> normalSuccs) {

        int takenPc = branch.getTarget();

        // Find which successor starts at the taken-branch target PC.
        ISSABasicBlock takenBlock = null;
        for (ISSABasicBlock succ : normalSuccs) {
            if (!succ.isExitBlock() && succ.getFirstInstructionIndex() == takenPc) {
                takenBlock = succ;
                break;
            }
        }

        for (ISSABasicBlock succ : normalSuccs) {
            boolean isTaken;
            if (takenBlock != null) {
                isTaken = (succ == takenBlock);
            } else {
                // Fallback: higher block number → true, lower → false.
                ISSABasicBlock other = normalSuccs.stream()
                        .filter(s -> s != succ).findFirst().orElse(null);
                isTaken = other == null || succ.getNumber() > other.getNumber();
            }
            String kind = isTaken ? "true" : "false";

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
}
