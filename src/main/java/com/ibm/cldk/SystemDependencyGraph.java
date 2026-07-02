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

import static com.ibm.cldk.utils.AnalysisUtils.*;

import com.ibm.cldk.entities.*;
import com.ibm.cldk.utils.AnalysisUtils;
import com.ibm.cldk.utils.Log;
import com.ibm.cldk.utils.ScopeUtils;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.java.translator.jdt.ecj.ECJClassLoaderFactory;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.ipa.slicer.HeapStatement;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.StatementWithInstructionIndex;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphSlicer;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.io.output.NullOutputStream;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.nio.json.JSONExporter;


@Data
abstract class Dependency {
    public CallableVertex source;
    public CallableVertex target;
}

@Data
@EqualsAndHashCode(callSuper = true)
class SDGDependency extends Dependency {
    public String sourceKind;
    public String destinationKind;
    public String type;
    public String weight;

    public SDGDependency(CallableVertex source, CallableVertex target, SystemDepEdge edge) {
        super.source = source;
        super.target = target;
        this.sourceKind = edge.getSourceKind();
        this.destinationKind = edge.getDestinationKind();
        this.type = edge.getType();
        this.weight = String.valueOf(edge.getWeight());
    }
}

@Data
@EqualsAndHashCode(callSuper = true)
class CallDependency extends Dependency {
    public String type;
    public String weight;

    public CallDependency(CallableVertex source, CallableVertex target, AbstractGraphEdge edge) {
        this.source = source;
        this.target = target;
        this.type = edge.toString();
        this.weight = String.valueOf(edge.getWeight());
    }
}

/**
 * Builds the WALA-based dependency graphs: the RTA call graph (analysis level 2) and, at analysis
 * level 3, the full system dependency graph from WALA's slicer — a method-level
 * {@code system_dependency_graph} edge list plus the statement-level {@code program_graphs}
 * section (per-callable CFG + PDG keyed by (signature, node_id), and cross-function SDG edges).
 */
public class SystemDependencyGraph {

    /** The result of a WALA analysis run; sdgEdges/programGraphs are null below level 3. */
    @Data
    public static class Result {
        private final List<Dependency> callEdges;
        private final List<Dependency> sdgEdges;
        private final ProgramGraphs programGraphs;
    }

    /**
     * Per-callable bookkeeping that maps WALA SSA instruction indexes onto the stable
     * (signature, node_id) identity space: ENTRY = 0, instructions in iindex order, EXIT = last.
     */
    private static class MethodNodeIndex {
        final String fqSignature;
        final Map<String, String> callable; // symbol-table vertex map (filePath/typeDeclaration/...)
        final Map<Integer, Integer> iindexToNode = new LinkedHashMap<>();
        final int exitNode;

        MethodNodeIndex(IMethod method, IR ir) {
            this.callable = Optional.ofNullable(getCallableFromSymbolTable(method))
                    .orElseGet(() -> createAndPutNewCallableInSymbolTable(method));
            this.fqSignature = callable.get("typeDeclaration") + "." + callable.get("signature");
            SSAInstruction[] instructions = ir.getInstructions();
            int nextId = 1;
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i] != null) {
                    iindexToNode.put(i, nextId++);
                }
            }
            this.exitNode = nextId;
        }
    }

    /**
     * Get a JGraphT graph exporter to save graph as JSON.
     *
     * @return the graph exporter
     */

    private static JSONExporter<CallableVertex, AbstractGraphEdge> getGraphExporter() {
        JSONExporter<CallableVertex, AbstractGraphEdge> exporter = new JSONExporter<>();
        exporter.setEdgeAttributeProvider(AbstractGraphEdge::getAttributes);
        exporter.setVertexAttributeProvider(CallableVertex::getAttributes);
        return exporter;
    }

    /**
     * Convert SDG to a formal Graph representation.
     *
     * @param callGraph
     * @return
     */
    private static org.jgrapht.Graph<CallableVertex, AbstractGraphEdge> buildOnlyCallGraph(CallGraph callGraph) {

        org.jgrapht.Graph<CallableVertex, AbstractGraphEdge> graph = new DefaultDirectedGraph<>(
                AbstractGraphEdge.class);
        callGraph.getEntrypointNodes()
                .forEach(p -> {
                    // Get call statements that may execute in a given method
                    Iterator<CallSiteReference> outGoingCalls = p.iterateCallSites();
                    outGoingCalls.forEachRemaining(n -> {
                        callGraph.getPossibleTargets(p, n).stream()
                                .filter(o -> AnalysisUtils.isApplicationClass(o.getMethod().getDeclaringClass()))
                                .forEach(o -> {

                                    // Add the source nodes to the graph as vertices
                                    Map<String, String> source = Optional.ofNullable(getCallableFromSymbolTable(p.getMethod())).orElseGet(() -> createAndPutNewCallableInSymbolTable(p.getMethod()));
                                    CallableVertex source_vertex = new CallableVertex(source);

                                    // Add the target nodes to the graph as vertices
                                    Map<String, String> target = Optional.ofNullable(getCallableFromSymbolTable(o.getMethod())).orElseGet(() -> createAndPutNewCallableInSymbolTable(o.getMethod()));
                                    CallableVertex target_vertex = new CallableVertex(target);

                                    if (!source.equals(target) && target != null) {
                                        // Get the edge between the source and the target
                                        graph.addVertex(source_vertex);
                                        graph.addVertex(target_vertex);
                                        AbstractGraphEdge cgEdge = graph.getEdge(source_vertex, target_vertex);
                                        if (cgEdge instanceof CallEdge) {
                                            ((CallEdge) cgEdge).incrementWeight();
                                        } else {
                                            graph.addEdge(source_vertex, target_vertex, new CallEdge());
                                        }
                                    }
                                });
                    });
                });

        return graph;
    }

    /**
     * Construct the WALA dependency graphs for a given input.
     *
     * @param input        the input
     * @param dependencies the dependencies
     * @param build        The build options
     * @param buildFullSdg also build the full slicer SDG (analysis level 3)
     * @param sdgDataDeps  "no-heap" or "full" — how deep slicer data dependence goes
     * @param graphs       which program_graphs sections to emit (cfg/pdg/sdg)
     * @return the call graph edges plus, at level 3, the SDG edge list and program graphs
     * @throws IOException                     the io exception
     * @throws ClassHierarchyException         the class hierarchy exception
     * @throws IllegalArgumentException        the illegal argument exception
     * @throws CallGraphBuilderCancelException the call graph builder cancel
     *                                         exception
     */
    public static Result construct(
            String input, String dependencies, String build, boolean buildFullSdg, String sdgDataDeps,
            Set<String> graphs)
            throws IOException, ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException {

        // Initialize scope
        AnalysisScope scope = ScopeUtils.createScope(input, dependencies, build);
        IClassHierarchy cha = ClassHierarchyFactory.make(scope,
                new ECJClassLoaderFactory(scope.getExclusions()));
        Log.done("There were a total of " + cha.getNumberOfClasses() + " classes of which "
                + AnalysisUtils.getNumberOfApplicationClasses(cha) + " are application classes.");

        // Initialize javaee options
        AnalysisOptions options = new AnalysisOptions();
        Iterable<Entrypoint> entryPoints = AnalysisUtils.getEntryPoints(cha);
        options.setEntrypoints(entryPoints);
        options.getSSAOptions().setDefaultValues(com.ibm.wala.ssa.SymbolTable::getDefaultValue);
        options.setReflectionOptions(ReflectionOptions.NONE);
        IAnalysisCacheView cache = new AnalysisCacheImpl(AstIRFactory.makeDefaultFactory(),
                options.getSSAOptions());

        // Build call graph
        Log.info("Building call graph.");

        // Some fu to remove WALA's console out...
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        long start_time = System.currentTimeMillis();
        CallGraph callGraph;
        CallGraphBuilder<InstanceKey> builder;
        try {
            System.setOut(new PrintStream(NullOutputStream.INSTANCE));
            System.setErr(new PrintStream(NullOutputStream.INSTANCE));
            builder = Util.makeRTABuilder(options, cache, cha);
            callGraph = builder.makeCallGraph(options, null);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        Log.done("Finished construction of call graph. Took "
                + Math.ceil((double) (System.currentTimeMillis() - start_time) / 1000) + " seconds.");

        // set cyclomatic complexity for callables in the symbol table
        callGraph.forEach(cgNode -> {
            Callable callable = getCallableObjectFromSymbolTable(cgNode.getMethod()).getRight();
            if (callable != null) {
                callable.setCyclomaticComplexity(getCyclomaticComplexity(cgNode.getIR()));
            }
        });

        org.jgrapht.Graph<CallableVertex, AbstractGraphEdge> graph;

        graph = buildOnlyCallGraph(callGraph);

        List<Dependency> callEdges = graph.edgeSet().stream()
                .map(abstractGraphEdge -> {
                    CallableVertex source = graph.getEdgeSource(abstractGraphEdge);
                    CallableVertex target = graph.getEdgeTarget(abstractGraphEdge);
                    if (abstractGraphEdge instanceof CallEdge) {
                        return new CallDependency(source, target, abstractGraphEdge);
                    } else {
                        return new SDGDependency(source, target, (SystemDepEdge) abstractGraphEdge);
                    }
                })
                .collect(Collectors.toList());

        if (!buildFullSdg) {
            return new Result(callEdges, null, null);
        }

        // ------------------------------------------------------------------
        // Analysis level 3: the full system dependency graph via WALA's slicer.
        // ------------------------------------------------------------------
        Slicer.DataDependenceOptions dataOptions = "full".equals(sdgDataDeps)
                ? Slicer.DataDependenceOptions.FULL
                : Slicer.DataDependenceOptions.NO_HEAP_NO_EXCEPTIONS;
        Slicer.ControlDependenceOptions controlOptions = "full".equals(sdgDataDeps)
                ? Slicer.ControlDependenceOptions.FULL
                : Slicer.ControlDependenceOptions.NO_EXCEPTIONAL_EDGES;

        Log.info("Building system dependency graph (data dependence: " + sdgDataDeps + ").");
        start_time = System.currentTimeMillis();
        SDG<? extends InstanceKey> sdg = new SDG<>(
                callGraph,
                builder.getPointerAnalysis(),
                new ModRef<>(),
                dataOptions,
                controlOptions);

        // Keep only statements of application classes, matching the call graph's scope.
        Graph<Statement> prunedGraph = GraphSlicer.prune(sdg,
                statement -> statement.getNode()
                        .getMethod()
                        .getDeclaringClass()
                        .getClassLoader()
                        .getReference()
                        .equals(ClassLoaderReference.Application));

        // (signature, node_id) index per method; the fake root and methods without IR are skipped.
        Map<IMethod, MethodNodeIndex> methodIndex = new LinkedHashMap<>();
        ProgramGraphs programGraphs = new ProgramGraphs();
        programGraphs.setDataDependence(sdgDataDeps);
        callGraph.forEach(cgNode -> {
            IMethod method = cgNode.getMethod();
            IR ir = cgNode.getIR();
            if (ir == null || !AnalysisUtils.isApplicationClass(method.getDeclaringClass())
                    || methodIndex.containsKey(method)) {
                return;
            }
            MethodNodeIndex index = new MethodNodeIndex(method, ir);
            methodIndex.put(method, index);
            ProgramGraphs.FunctionProgramGraph fg = new ProgramGraphs.FunctionProgramGraph();
            fg.setSignature(index.callable.get("signature"));
            fg.setTypeDeclaration(index.callable.get("typeDeclaration"));
            fg.setFilePath(index.callable.get("filePath"));
            if (graphs.contains("cfg")) {
                fg.setCfg(buildCfg(method, ir, index));
            }
            if (graphs.contains("pdg")) {
                fg.setPdg(new ProgramGraphs.Pdg());
            }
            programGraphs.getFunctions().put(index.fqSignature, fg);
        });

        // Walk the pruned SDG once: same-method edges become PDG edges (CDG/DDG); cross-method
        // edges become method-level SystemDepEdges plus statement-level sdg_edges.
        Map<String, SystemDepEdge> methodLevelEdges = new LinkedHashMap<>();
        Map<String, Dependency> methodLevelDeps = new LinkedHashMap<>();
        Set<String> seenPdgEdges = new HashSet<>();
        Set<String> seenSdgEdges = new HashSet<>();
        prunedGraph.forEach(p -> prunedGraph.getSuccNodes(p).forEachRemaining(s -> {
            for (String label : edgeLabels(sdg, p, s)) {
                if (p.getNode().equals(s.getNode())) {
                    if (graphs.contains("pdg")) {
                        addPdgEdge(programGraphs, methodIndex, p, s, label, seenPdgEdges);
                    }
                } else {
                    collapseToMethodEdge(methodLevelEdges, methodLevelDeps, p, s, label);
                    if (graphs.contains("sdg")) {
                        addSdgEdge(programGraphs, methodIndex, p, s, label, seenSdgEdges);
                    }
                }
            }
        }));

        List<Dependency> sdgEdges = methodLevelDeps.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    // Re-wrap so the serialized weight reflects the final increment count.
                    SDGDependency dep = (SDGDependency) e.getValue();
                    return (Dependency) new SDGDependency(dep.getSource(), dep.getTarget(),
                            methodLevelEdges.get(e.getKey()));
                })
                .collect(Collectors.toList());

        sortProgramGraphs(programGraphs, graphs);

        Log.done("Finished construction of system dependency graph. Took "
                + Math.ceil((double) (System.currentTimeMillis() - start_time) / 1000) + " seconds. "
                + programGraphs.getFunctions().size() + " functions, "
                + sdgEdges.size() + " method-level edges, "
                + programGraphs.getSdgEdges().size() + " cross-function statement edges.");

        return new Result(callEdges, sdgEdges, programGraphs);
    }

    /**
     * All dependence labels on the edge (a statement pair can carry both CONTROL_DEP and
     * DATA_DEP), sorted for determinism; ["UNKNOWN"] when WALA offers none.
     */
    private static List<String> edgeLabels(SDG<? extends InstanceKey> sdg, Statement p, Statement s) {
        try {
            List<String> labels = sdg.getEdgeLabels(p, s).stream()
                    .map(String::valueOf)
                    .sorted()
                    .collect(Collectors.toList());
            return labels.isEmpty() ? Collections.singletonList("UNKNOWN") : labels;
        } catch (RuntimeException e) {
            return Collections.singletonList("UNKNOWN");
        }
    }

    /**
     * The CFG over the shared node ids: within-block instructions chain as fallthrough; block
     * successors are labeled true/false (conditional branches, fallthrough block = false),
     * switch_case, exception (exceptional successors), return (to EXIT), or loop_back (back
     * edges). Empty basic blocks are contracted onto their first real successor.
     */
    private static ProgramGraphs.Cfg buildCfg(IMethod method, IR ir, MethodNodeIndex index) {
        ProgramGraphs.Cfg cfg = new ProgramGraphs.Cfg();
        SSAInstruction[] instructions = ir.getInstructions();

        ProgramGraphs.Node entry = new ProgramGraphs.Node();
        entry.setId(0);
        entry.setKind("entry");
        cfg.getNodes().add(entry);
        index.iindexToNode.forEach((iindex, nodeId) -> {
            ProgramGraphs.Node node = new ProgramGraphs.Node();
            node.setId(nodeId);
            node.setKind(kindOf(instructions[iindex]));
            try {
                IMethod.SourcePosition pos = method.getSourcePosition(iindex);
                if (pos != null) {
                    node.setStartLine(pos.getFirstLine());
                    node.setEndLine(pos.getLastLine());
                }
            } catch (Exception e) {
                // no debug info for this instruction; keep the -1 sentinel
            }
            cfg.getNodes().add(node);
        });
        ProgramGraphs.Node exit = new ProgramGraphs.Node();
        exit.setId(index.exitNode);
        exit.setKind("exit");
        cfg.getNodes().add(exit);

        SSACFG ssacfg = ir.getControlFlowGraph();
        Set<String> seen = new HashSet<>();
        for (ISSABasicBlock block : ssacfg) {
            List<Integer> blockNodes = blockNodeIds(block, instructions, index);

            // Chain the instructions within the block.
            for (int i = 0; i + 1 < blockNodes.size(); i++) {
                addCfgEdge(cfg, seen, blockNodes.get(i), blockNodes.get(i + 1), "fallthrough");
            }

            int sourceNode = block.equals(ssacfg.entry()) ? 0
                    : blockNodes.isEmpty() ? -1 : blockNodes.get(blockNodes.size() - 1);
            if (sourceNode < 0) {
                continue; // empty block: contracted below via resolveFirstNodes of its predecessors
            }
            SSAInstruction last = blockNodes.isEmpty() ? null
                    : instructions[lastInstructionIndex(block, instructions)];

            for (ISSABasicBlock succ : ssacfg.getNormalSuccessors(block)) {
                for (int targetNode : resolveFirstNodes(ssacfg, succ, instructions, index, new HashSet<>())) {
                    addCfgEdge(cfg, seen, sourceNode, targetNode,
                            normalEdgeKind(block, succ, last, sourceNode, targetNode, index));
                }
            }
            for (ISSABasicBlock succ : ssacfg.getExceptionalSuccessors(block)) {
                for (int targetNode : resolveFirstNodes(ssacfg, succ, instructions, index, new HashSet<>())) {
                    addCfgEdge(cfg, seen, sourceNode, targetNode, "exception");
                }
            }
        }
        cfg.getEdges().sort(Comparator.comparingInt(ProgramGraphs.CfgEdge::getSource)
                .thenComparingInt(ProgramGraphs.CfgEdge::getTarget)
                .thenComparing(ProgramGraphs.CfgEdge::getKind));
        return cfg;
    }

    private static void addCfgEdge(ProgramGraphs.Cfg cfg, Set<String> seen, int source, int target, String kind) {
        if (seen.add(source + ">" + target + ">" + kind)) {
            cfg.getEdges().add(new ProgramGraphs.CfgEdge(source, target, kind));
        }
    }

    private static String normalEdgeKind(ISSABasicBlock block, ISSABasicBlock succ, SSAInstruction last,
            int sourceNode, int targetNode, MethodNodeIndex index) {
        if (targetNode == index.exitNode) {
            return "return";
        }
        if (last instanceof SSAConditionalBranchInstruction) {
            // WALA's fallthrough (not-taken) block is the next block in layout order.
            return succ.getNumber() == block.getNumber() + 1 ? "false" : "true";
        }
        if (last instanceof SSASwitchInstruction) {
            return "switch_case";
        }
        if (targetNode <= sourceNode && sourceNode != 0) {
            return "loop_back";
        }
        return "fallthrough";
    }

    private static int lastInstructionIndex(ISSABasicBlock block, SSAInstruction[] instructions) {
        for (int i = block.getLastInstructionIndex(); i >= block.getFirstInstructionIndex(); i--) {
            if (i >= 0 && i < instructions.length && instructions[i] != null) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> blockNodeIds(ISSABasicBlock block, SSAInstruction[] instructions,
            MethodNodeIndex index) {
        List<Integer> nodes = new ArrayList<>();
        for (int i = Math.max(block.getFirstInstructionIndex(), 0); i <= block.getLastInstructionIndex()
                && i < instructions.length; i++) {
            if (instructions[i] != null && index.iindexToNode.containsKey(i)) {
                nodes.add(index.iindexToNode.get(i));
            }
        }
        return nodes;
    }

    /** First real node(s) reachable from a block, contracting empty blocks; EXIT for the exit block. */
    private static List<Integer> resolveFirstNodes(SSACFG cfg, ISSABasicBlock block,
            SSAInstruction[] instructions, MethodNodeIndex index, Set<Integer> visited) {
        if (!visited.add(block.getNumber())) {
            return Collections.emptyList();
        }
        if (block.equals(cfg.exit())) {
            return Collections.singletonList(index.exitNode);
        }
        List<Integer> nodes = blockNodeIds(block, instructions, index);
        if (!nodes.isEmpty()) {
            return Collections.singletonList(nodes.get(0));
        }
        List<Integer> resolved = new ArrayList<>();
        for (ISSABasicBlock succ : cfg.getNormalSuccessors(block)) {
            resolved.addAll(resolveFirstNodes(cfg, succ, instructions, index, visited));
        }
        return resolved;
    }

    private static String kindOf(SSAInstruction instruction) {
        if (instruction instanceof SSAAbstractInvokeInstruction) {
            return "call";
        } else if (instruction instanceof SSAConditionalBranchInstruction) {
            return "branch";
        } else if (instruction instanceof SSASwitchInstruction) {
            return "switch";
        } else if (instruction instanceof SSAReturnInstruction) {
            return "return";
        } else if (instruction instanceof SSAThrowInstruction) {
            return "throw";
        } else if (instruction instanceof SSANewInstruction) {
            return "new";
        }
        return "instruction";
    }

    /** Maps a slicer statement onto this method's node ids; null when it has no stable anchor. */
    private static Integer statementNode(Statement statement, MethodNodeIndex index) {
        switch (statement.getKind()) {
            case NORMAL:
            case PARAM_CALLER:
            case NORMAL_RET_CALLER:
            case EXC_RET_CALLER:
            case CATCH:
                return index.iindexToNode.get(((StatementWithInstructionIndex) statement).getInstructionIndex());
            case METHOD_ENTRY:
            case PARAM_CALLEE:
                return 0;
            case METHOD_EXIT:
            case NORMAL_RET_CALLEE:
            case EXC_RET_CALLEE:
                return index.exitNode;
            case HEAP_PARAM_CALLER:
            case HEAP_RET_CALLER:
                return index.iindexToNode.get(((HeapStatement.HeapParamCaller.class.isInstance(statement))
                        ? ((HeapStatement.HeapParamCaller) statement).getCall()
                        : ((HeapStatement.HeapReturnCaller) statement).getCall()).iIndex());
            case HEAP_PARAM_CALLEE:
                return 0;
            case HEAP_RET_CALLEE:
                return index.exitNode;
            default: // PHI, PI — no stable source anchor
                return null;
        }
    }

    private static void addPdgEdge(ProgramGraphs programGraphs, Map<IMethod, MethodNodeIndex> methodIndex,
            Statement p, Statement s, String label, Set<String> seen) {
        MethodNodeIndex index = methodIndex.get(p.getNode().getMethod());
        if (index == null) {
            return;
        }
        Integer source = statementNode(p, index);
        Integer target = statementNode(s, index);
        if (source == null || target == null || source.equals(target)) {
            return;
        }
        if (seen.add(index.fqSignature + "#" + source + ">" + target + ">" + label)) {
            String type = label.contains("CONTROL") ? "CDG" : "DDG";
            programGraphs.getFunctions().get(index.fqSignature).getPdg().getEdges()
                    .add(new ProgramGraphs.PdgEdge(source, target, type, label));
        }
    }

    private static void addSdgEdge(ProgramGraphs programGraphs, Map<IMethod, MethodNodeIndex> methodIndex,
            Statement p, Statement s, String label, Set<String> seen) {
        MethodNodeIndex sourceIndex = methodIndex.get(p.getNode().getMethod());
        MethodNodeIndex targetIndex = methodIndex.get(s.getNode().getMethod());
        if (sourceIndex == null || targetIndex == null) {
            return;
        }
        Integer source = statementNode(p, sourceIndex);
        Integer target = statementNode(s, targetIndex);
        if (source == null || target == null) {
            return;
        }
        String type = crossEdgeType(p, s, label);
        String key = sourceIndex.fqSignature + "#" + source + ">" + targetIndex.fqSignature + "#" + target + ">" + type;
        if (seen.add(key)) {
            programGraphs.getSdgEdges().add(new ProgramGraphs.SdgEdge(
                    new ProgramGraphs.SdgEndpoint(sourceIndex.fqSignature, source),
                    new ProgramGraphs.SdgEndpoint(targetIndex.fqSignature, target),
                    type, label));
        }
    }

    /** HRB edge vocabulary from WALA's statement kinds: CALL, PARAM_IN, PARAM_OUT. */
    private static String crossEdgeType(Statement p, Statement s, String label) {
        switch (s.getKind()) {
            case METHOD_ENTRY:
                return "CALL";
            case PARAM_CALLEE:
            case HEAP_PARAM_CALLEE:
                return "PARAM_IN";
            default:
                break;
        }
        switch (p.getKind()) {
            case NORMAL_RET_CALLEE:
            case EXC_RET_CALLEE:
            case METHOD_EXIT:
            case HEAP_RET_CALLEE:
                return "PARAM_OUT";
            default:
                return label.contains("CONTROL") ? "CALL" : "PARAM_IN";
        }
    }

    private static void collapseToMethodEdge(Map<String, SystemDepEdge> methodLevelEdges,
            Map<String, Dependency> methodLevelDeps, Statement p, Statement s, String label) {
        Map<String, String> source = Optional.ofNullable(getCallableFromSymbolTable(p.getNode().getMethod()))
                .orElseGet(() -> createAndPutNewCallableInSymbolTable(p.getNode().getMethod()));
        Map<String, String> target = Optional.ofNullable(getCallableFromSymbolTable(s.getNode().getMethod()))
                .orElseGet(() -> createAndPutNewCallableInSymbolTable(s.getNode().getMethod()));

        String key = source.get("typeDeclaration") + "." + source.get("signature") + ">"
                + target.get("typeDeclaration") + "." + target.get("signature") + ">" + label;
        SystemDepEdge existing = methodLevelEdges.get(key);
        if (existing != null) {
            existing.incrementWeight();
        } else {
            SystemDepEdge edge = new SystemDepEdge(p, s, label);
            methodLevelEdges.put(key, edge);
            methodLevelDeps.put(key,
                    new SDGDependency(new CallableVertex(source), new CallableVertex(target), edge));
        }
    }

    private static void sortProgramGraphs(ProgramGraphs programGraphs, Set<String> graphs) {
        if (graphs.contains("pdg")) {
            programGraphs.getFunctions().values().forEach(fg -> fg.getPdg().getEdges()
                    .sort(Comparator.comparingInt(ProgramGraphs.PdgEdge::getSource)
                            .thenComparingInt(ProgramGraphs.PdgEdge::getTarget)
                            .thenComparing(ProgramGraphs.PdgEdge::getType)));
        }
        programGraphs.getSdgEdges().sort(Comparator
                .comparing((ProgramGraphs.SdgEdge e) -> e.getSource().getSignature())
                .thenComparingInt(e -> e.getSource().getNode())
                .thenComparing(e -> e.getTarget().getSignature())
                .thenComparingInt(e -> e.getTarget().getNode())
                .thenComparing(ProgramGraphs.SdgEdge::getType));
    }
}
