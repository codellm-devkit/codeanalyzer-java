/*
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.ibm.cldk.wala;

import com.ibm.cldk.schema.JCdgEdge;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.wala.WalaAnalysis.MethodIr;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.slicer.Dependency;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.PDG;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@code cdg} and {@code ddg} overlays for a single method from WALA's native per-method
 * PDG.
 *
 * <p>The centerpiece of the L3 WALA engine: it is the first producer of {@code prov=["points-to"]}
 * DDG edges — intraprocedural heap def-use pairs attested by RTA pointer analysis.
 *
 * <p>Usage pattern:
 * <ol>
 *   <li>Obtain a {@link PDG} from {@link WalaAnalysis#pdgFor(com.ibm.wala.ipa.callgraph.CGNode)}.
 *   <li>Prime every node with unlabeled traversal so WALA materializes its lazy heap edges.
 *   <li>Read labeled edges for {@code CONTROL_DEP}, {@code DATA_DEP}, and {@code HEAP_DATA_DEP},
 *       keeping only {@code NormalStatement} endpoints.
 *   <li>Map endpoints to body-node local ids via {@link InstructionToNode}.
 *   <li>Collect, deduplicate, and sort the result into a {@link PdgOverlays} value.
 * </ol>
 *
 * <p>Heap edges materialize lazily in WALA (PDG.java:1175): they are created only when the
 * unlabeled {@code getSuccNodes}/{@code getPredNodes} is called, not during the PDG constructor
 * and not via the labeled accessor. The priming loop in {@link #build} encodes this finding
 * exactly; removing it silently produces an empty {@code ddg} for heap dependencies.
 *
 * <p>Only {@code NormalStatement} endpoints are kept. Param/heap-param/method-entry/exit
 * statements are the SDG interface (L4) and are explicitly excluded.
 */
public final class WalaPdgBuilder {

    private WalaPdgBuilder() {}

    // ----- result type -------------------------------------------------------------------------

    /** The CDG and DDG overlays produced for a single method. */
    public static final class PdgOverlays {
        /** Control-dependence edges sorted by {@code (src, dst)}. */
        public final List<JCdgEdge> cdg;
        /** Data-dependence edges (scalar SSA + heap points-to) sorted by {@code (src,dst,var,prov)}. */
        public final List<JDdgEdge> ddg;

        PdgOverlays(List<JCdgEdge> cdg, List<JDdgEdge> ddg) {
            this.cdg = cdg;
            this.ddg = ddg;
        }
    }

    // ----- public API --------------------------------------------------------------------------

    /**
     * Builds the CDG and DDG overlays from WALA's per-method PDG for {@code m}.
     *
     * @param wala   WALA analysis state (provides the PDG and source-line resolution)
     * @param m      the SSA IR bundle for the target method
     * @param mapper maps SSA instructions to body-node local ids (built from the re-parsed AST)
     * @return a {@link PdgOverlays} with sorted, deduplicated CDG and DDG lists
     */
    public static PdgOverlays build(WalaAnalysis wala, MethodIr m, InstructionToNode mapper) {
        PDG<InstanceKey> pdg = wala.pdgFor(m.node);

        // Prime every node: WALA materializes heap du-pairs lazily only when the *unlabeled*
        // getSuccNodes/getPredNodes is called. Calling the labeled accessor first silently
        // returns an empty iterator for heap edges. This priming loop is not optional.
        for (Statement s : pdg) {
            pdg.getSuccNodes(s).forEachRemaining(x -> {});
            pdg.getPredNodes(s).forEachRemaining(x -> {});
        }

        // Accumulate edges, deduplicating by key.
        Set<String> cdgKeys = new LinkedHashSet<>();
        List<JCdgEdge> cdg = new ArrayList<>();
        Set<String> ddgKeys = new LinkedHashSet<>();
        List<JDdgEdge> ddg = new ArrayList<>();

        for (Dependency label : new Dependency[]{
                Dependency.CONTROL_DEP, Dependency.DATA_DEP, Dependency.HEAP_DATA_DEP}) {
            for (Statement s : pdg) {
                if (!(s instanceof NormalStatement)) {
                    continue;
                }
                NormalStatement srcNs = (NormalStatement) s;
                for (Iterator<? extends Statement> it = pdg.getSuccNodes(s, label); it.hasNext();) {
                    Statement d = it.next();
                    if (!(d instanceof NormalStatement)) {
                        continue;
                    }
                    NormalStatement dstNs = (NormalStatement) d;

                    String srcId = mapper.map(
                            srcNs.getInstruction(),
                            wala.sourceLine(m, srcNs.getInstructionIndex()));
                    String dstId = mapper.map(
                            dstNs.getInstruction(),
                            wala.sourceLine(m, dstNs.getInstructionIndex()));

                    if (label == Dependency.CONTROL_DEP) {
                        String key = srcId + "\0" + dstId;
                        if (cdgKeys.add(key)) {
                            JCdgEdge edge = new JCdgEdge();
                            edge.setSrc(srcId);
                            edge.setDst(dstId);
                            cdg.add(edge);
                        }
                    } else {
                        String provTag = (label == Dependency.DATA_DEP) ? "ssa" : "points-to";
                        String var = (label == Dependency.DATA_DEP)
                                ? scalarVar(srcNs, m)
                                : heapVar(srcNs, dstNs);
                        String key = srcId + "\0" + dstId + "\0" + var + "\0" + provTag;
                        if (ddgKeys.add(key)) {
                            JDdgEdge edge = new JDdgEdge();
                            edge.setSrc(srcId);
                            edge.setDst(dstId);
                            edge.setVar(var);
                            edge.getProv().add(provTag);
                            ddg.add(edge);
                        }
                    }
                }
            }
        }

        // Sort deterministically: cdg by (src, dst); ddg by (src, dst, var, prov-list).
        cdg.sort(Comparator.comparing(JCdgEdge::getSrc).thenComparing(JCdgEdge::getDst));
        ddg.sort(Comparator.comparing(JDdgEdge::getSrc)
                .thenComparing(JDdgEdge::getDst)
                .thenComparing(JDdgEdge::getVar)
                .thenComparing(e -> e.getProv().toString()));

        return new PdgOverlays(cdg, ddg);
    }

    // ----- var derivation ----------------------------------------------------------------------

    /**
     * Derives the access-path variable name for a scalar (SSA) {@code DATA_DEP} edge.
     *
     * <p>The edge runs from the defining statement to the using statement. We extract the name
     * of the SSA value defined by the source instruction, first consulting the IR's local-variable
     * table (which carries source-level names when the class was compiled with {@code -g}), then
     * falling back to the synthetic {@code "v" + valueNumber} spelling.
     */
    private static String scalarVar(NormalStatement srcNs, MethodIr m) {
        SSAInstruction ins = srcNs.getInstruction();
        if (ins.hasDef()) {
            int vn = ins.getDef();
            try {
                String[] names = m.ir.getLocalNames(srcNs.getInstructionIndex(), vn);
                if (names != null) {
                    for (String n : names) {
                        if (n != null && !n.isEmpty()) {
                            return n;
                        }
                    }
                }
            } catch (Throwable t) {
                // Local-name lookup failed (e.g. no debug table); fall through.
            }
            return "v" + vn;
        }
        // No def: emit a non-empty fallback derived from the first use value number.
        if (ins.getNumberOfUses() > 0) {
            return "v" + ins.getUse(0);
        }
        return "v_unknown";
    }

    /**
     * Derives the access-path variable name for a heap (points-to) {@code HEAP_DATA_DEP} edge.
     *
     * <p>The field name is extracted from whichever of the src (write) or dst (read) instruction
     * is a field-access instruction. For array operations, {@code "[*]"} is returned. Falls back
     * to {@code "heap"} when neither endpoint carries a named field — non-empty by schema contract.
     */
    private static String heapVar(NormalStatement srcNs, NormalStatement dstNs) {
        SSAInstruction srcIns = srcNs.getInstruction();
        if (srcIns instanceof SSAFieldAccessInstruction) {
            return ((SSAFieldAccessInstruction) srcIns).getDeclaredField().getName().toString();
        }
        SSAInstruction dstIns = dstNs.getInstruction();
        if (dstIns instanceof SSAFieldAccessInstruction) {
            return ((SSAFieldAccessInstruction) dstIns).getDeclaredField().getName().toString();
        }
        if (srcIns instanceof SSAArrayStoreInstruction || dstIns instanceof SSAArrayLoadInstruction) {
            return "[*]";
        }
        return "heap";
    }
}
