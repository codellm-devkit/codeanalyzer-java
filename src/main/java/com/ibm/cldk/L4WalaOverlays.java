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

package com.ibm.cldk;

import com.github.javaparser.ast.CompilationUnit;
import com.ibm.cldk.L3WalaOverlays.Joined;
import com.ibm.cldk.L3WalaOverlays.TypeEntry;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.utils.Log;
import com.ibm.cldk.wala.InstructionToNode;
import com.ibm.cldk.wala.WalaAnalysis;
import com.ibm.cldk.wala.WalaAnalysis.MethodIr;
import com.ibm.cldk.wala.WalaPdgBuilder;
import com.ibm.cldk.wala.WalaPdgBuilder.PdgOverlays;
import com.ibm.wala.ipa.callgraph.propagation.ArrayContentsKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceFieldKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.StaticFieldKey;
import com.ibm.wala.ipa.slicer.Dependency;
import com.ibm.wala.ipa.slicer.HeapStatement;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.PDG;
import com.ibm.wala.ipa.slicer.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Post-build L4 orchestrator: the semantic half of {@code ddg}.
 *
 * <p>L3 answers "which definitions reach this use through named variables" — syntax the AST or the
 * SSA form can see on its own, tagged {@code prov:["ssa"]}. What neither can see is dependence
 * carried by the heap across a call: {@code put(v)} then {@code get()} touch the same field through
 * two different frames, and only a points-to analysis can attest that they alias. Those edges are
 * tagged {@code prov:["points-to"]} and are what this pass adds.
 *
 * <p>It starts by priming WALA's mod/ref maps ({@link WalaAnalysis#primeL4ModRef}), which is what
 * makes the PDG materialize heap statements at call sites at all. The dependences then arrive in
 * two shapes, and both are collected per method:
 * <ul>
 *   <li><b>Intraprocedural</b> — a write and a read of the same location inside one body. Both
 *       endpoints are {@code NormalStatement}s, so {@link WalaPdgBuilder} already emits them with
 *       {@code prov:["points-to"]} and is simply re-run here. (Under {@code --l3-engine wala} L3
 *       emitted these too and the merge dedupes them; under the AST engine they are new.)
 *   <li><b>Interprocedural</b> — the round trip through a callee. WALA anchors these on
 *       {@code HeapStatement}s, which have no body node, so the builder's deliberate
 *       {@code NormalStatement}-only filter cannot carry them. They are projected onto their own
 *       call statements here instead; see {@link #interproceduralHeapEdges}.
 * </ul>
 *
 * <p>The merge is strictly additive: L4 may only add reach, never remove it, so every edge L3
 * produced survives byte-identical and only unseen {@code (src,dst,var,prov)} tuples are appended.
 */
public final class L4WalaOverlays {

    private L4WalaOverlays() {}

    /**
     * Merges WALA's points-to {@code ddg} edges into the L3 overlays already on {@code modules}.
     *
     * @param wala       the pre-built WALA analysis (the same instance L3 used)
     * @param modules    the module map, already carrying L3 overlays (mutated in place)
     * @param fieldDepth the DDG access-path bound k, for parity with
     *                   {@link L3WalaOverlays#apply} — heap access paths come from the WALA
     *                   instruction's declared field, so it is not consulted here
     */
    public static void apply(WalaAnalysis wala, Map<String, JModule> modules, int fieldDepth) {
        if (modules.isEmpty()) {
            return;
        }

        wala.primeL4ModRef();

        Map<String, TypeEntry> typeIndex = L3WalaOverlays.buildTypeIndex(modules);
        Map<String, CompilationUnit> parseCache = new LinkedHashMap<>();

        int matched = 0;
        int addedEdges = 0;

        for (MethodIr m : wala.applicationMethods()) {
            Optional<Joined> joined = L3WalaOverlays.join(m, typeIndex, parseCache, modules);
            if (!joined.isPresent()) {
                continue;
            }
            JCallable callable = joined.get().callable;
            if (callable.getDdg() == null) {
                // No L3 ddg to extend (analysis ran below level 3): nothing to merge into.
                continue;
            }

            InstructionToNode mapper = new InstructionToNode(
                    InstructionToNode.statementsByLine(joined.get().blockStmt));

            // Intraprocedural half: the builder's own points-to edges, both endpoints already
            // NormalStatements. Under `--l3-engine wala` L3 emitted these too and the merge dedupes
            // them away; under the AST engine they are new.
            PdgOverlays pdg = WalaPdgBuilder.build(wala, m, mapper);
            List<JDdgEdge> produced = new ArrayList<>(pdg.ddg);

            // Interprocedural half: the edges only the primed mod/ref can see.
            produced.addAll(interproceduralHeapEdges(wala, m, mapper));

            addedEdges += merge(callable, produced);
            matched++;
        }

        Log.info("L4 WALA semantic ddg applied: " + matched + " callable(s) visited, "
                + addedEdges + " points-to edge(s) added");
    }

    /**
     * The heap dependences that cross a call boundary, projected onto the call statements that
     * carry them.
     *
     * <p>WALA anchors every interprocedural heap dependence on a {@link HeapStatement}, never on a
     * {@link NormalStatement}: {@code put(v); … get()} surfaces as {@code HeapReturnCaller(put) →
     * HeapParamCaller(get)}. Those endpoints have no body node of their own, which is why
     * {@link WalaPdgBuilder} — whose {@code NormalStatement}-only filter is deliberate — cannot see
     * them. Each caller-side heap statement does name the SSA index of its call, so it projects
     * onto that call's own {@code NormalStatement}, and the dependence lands on the two body nodes
     * a reader would point at.
     *
     * <p>Callee-side heap statements ({@code HEAP_PARAM_CALLEE}, {@code HEAP_RET_CALLEE}) are
     * dropped: they are the method's own SDG interface, which {@code SdgVertices} derives as
     * {@code param_in}/{@code param_out} vertices rather than {@code ddg} edges.
     *
     * <p>This builds a second PDG for {@code m}. That is deliberate — it keeps {@link WalaPdgBuilder}
     * and the L3 paths it serves untouched — and the pass runs only at {@code -a 4}.
     */
    private static List<JDdgEdge> interproceduralHeapEdges(
            WalaAnalysis wala, MethodIr m, InstructionToNode mapper) {

        PDG<InstanceKey> pdg = wala.pdgFor(m.node);

        // Same lazy-edge trap as WalaPdgBuilder: WALA materializes heap du-pairs only when the
        // *unlabeled* successor/predecessor accessor is called. Without this loop the labeled
        // accessors below return nothing.
        for (Statement s : pdg) {
            pdg.getSuccNodes(s).forEachRemaining(x -> {});
            pdg.getPredNodes(s).forEachRemaining(x -> {});
        }

        Map<Integer, NormalStatement> callStatements = new HashMap<>();
        for (Statement s : pdg) {
            if (s instanceof NormalStatement) {
                NormalStatement ns = (NormalStatement) s;
                callStatements.put(ns.getInstructionIndex(), ns);
            }
        }

        List<JDdgEdge> edges = new ArrayList<>();
        for (Dependency label : new Dependency[]{Dependency.DATA_DEP, Dependency.HEAP_DATA_DEP}) {
            for (Statement s : pdg) {
                for (Iterator<? extends Statement> it = pdg.getSuccNodes(s, label); it.hasNext();) {
                    Statement d = it.next();

                    // An endpoint that is a heap statement is what makes the edge interprocedural;
                    // a pair of NormalStatements is the intraprocedural case WalaPdgBuilder emits.
                    if (!(s instanceof HeapStatement) && !(d instanceof HeapStatement)) {
                        continue;
                    }
                    // PDG labels an edge into a heap statement DATA_DEP even though it is heap flow
                    // (PDG.createHeapDataDependenceEdges), so a DATA_DEP edge carries the heap
                    // exactly when its target is one; every HEAP_DATA_DEP edge does.
                    if (label == Dependency.DATA_DEP && !(d instanceof HeapStatement)) {
                        continue;
                    }
                    NormalStatement src = projectToCall(s, callStatements);
                    NormalStatement dst = projectToCall(d, callStatements);
                    if (src == null || dst == null || src.equals(dst)) {
                        continue;
                    }
                    JDdgEdge edge = new JDdgEdge();
                    edge.setSrc(mapper.map(
                            src.getInstruction(), wala.sourceLine(m, src.getInstructionIndex())));
                    edge.setDst(mapper.map(
                            dst.getInstruction(), wala.sourceLine(m, dst.getInstructionIndex())));
                    edge.setVar(heapVar(s, d));
                    edge.getProv().add("points-to");
                    edges.add(edge);
                }
            }
        }
        return edges;
    }

    /**
     * The {@link NormalStatement} that owns {@code s}: itself when it already is one, the statement
     * of the call it decorates when it is a caller-side heap statement, and {@code null} otherwise
     * (callee-side heap statements and the method entry/exit interface have no owning instruction).
     */
    private static NormalStatement projectToCall(
            Statement s, Map<Integer, NormalStatement> callStatements) {
        if (s instanceof NormalStatement) {
            return (NormalStatement) s;
        }
        if (s instanceof HeapStatement.HeapParamCaller) {
            return callStatements.get(((HeapStatement.HeapParamCaller) s).getCallIndex());
        }
        if (s instanceof HeapStatement.HeapReturnCaller) {
            return callStatements.get(((HeapStatement.HeapReturnCaller) s).getCallIndex());
        }
        return null;
    }

    /**
     * The access path of the heap location the edge carries, taken from whichever endpoint is a
     * {@link HeapStatement}. Mirrors the vocabulary {@link WalaPdgBuilder} uses for its own heap
     * edges: the field's simple name, {@code "[*]"} for array contents, {@code "heap"} otherwise —
     * non-empty by schema contract.
     */
    private static String heapVar(Statement src, Statement dst) {
        PointerKey location = src instanceof HeapStatement
                ? ((HeapStatement) src).getLocation()
                : ((HeapStatement) dst).getLocation();
        if (location instanceof InstanceFieldKey) {
            return ((InstanceFieldKey) location).getField().getName().toString();
        }
        if (location instanceof StaticFieldKey) {
            return ((StaticFieldKey) location).getField().getName().toString();
        }
        if (location instanceof ArrayContentsKey) {
            return "[*]";
        }
        return "heap";
    }

    /**
     * Appends the {@code points-to} edges of {@code produced} that {@code callable} does not
     * already carry, then re-sorts the whole list on {@code (src,dst,var,prov)} — the same key
     * both producers sort on, so the merged list stays in canonical order.
     *
     * @return the number of edges actually added
     */
    private static int merge(JCallable callable, List<JDdgEdge> produced) {
        List<JDdgEdge> merged = new ArrayList<>(callable.getDdg());
        Set<String> seen = new LinkedHashSet<>();
        for (JDdgEdge edge : merged) {
            seen.add(key(edge));
        }

        int added = 0;
        for (JDdgEdge edge : produced) {
            if (!edge.getProv().contains("points-to")) {
                continue;
            }
            if (seen.add(key(edge))) {
                merged.add(edge);
                added++;
            }
        }
        if (added == 0) {
            return 0;
        }

        merged.sort(Comparator.comparing(JDdgEdge::getSrc)
                .thenComparing(JDdgEdge::getDst)
                .thenComparing(JDdgEdge::getVar)
                .thenComparing(e -> e.getProv().toString()));
        callable.setDdg(merged);
        return added;
    }

    /** The dedup identity of a ddg edge: {@code (src, dst, var, prov)}. */
    private static String key(JDdgEdge edge) {
        return edge.getSrc() + "\0" + edge.getDst() + "\0" + edge.getVar()
                + "\0" + edge.getProv();
    }
}
