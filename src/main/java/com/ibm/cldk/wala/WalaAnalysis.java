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

import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.RtaCallGraph;
import com.ibm.cldk.syntactic_analysis.L2CallGraph.RtaEndpoint;
import com.ibm.cldk.utils.AnalysisUtils;
import com.ibm.cldk.utils.ScopeUtils;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.java.translator.jdt.ecj.ECJClassLoaderFactory;
import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.ipa.slicer.PDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.util.intset.MutableMapping;
import com.ibm.wala.util.intset.MutableSparseIntSet;
import com.ibm.wala.util.intset.OrdinalSet;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.io.output.NullOutputStream;

/**
 * The single WALA entry point for the L3 WALA engine.
 *
 * <p>Builds the RTA call graph and retains the pointer analysis so that per-method PDGs and CFGs
 * can be constructed from it. When the CLI runs at {@code -a 3 --l3-engine wala} this object also
 * supplies the L2 {@code rta} endpoints, so the call graph is built exactly once for both levels.
 *
 * <p>Construction mirrors {@link RtaCallGraph#endpoints}: same scope, CHA, options, and RTA
 * builder; same stdout/stderr muting; same degrade-to-{@link Optional#empty()} on any failure.
 */
public final class WalaAnalysis {

    // ----- empty-defaulting maps for per-method PDG construction ---------------------------------

    /**
     * A shared empty mapping used as the backing store for {@link #EMPTY_LOC}.
     * {@code OrdinalSet.empty()} has a null backing set, which PDG.unionHeapLocations rejects
     * with IllegalArgumentException. We use a non-null-backed empty set instead.
     */
    private static final MutableMapping<PointerKey> MAPPING = MutableMapping.make();

    /**
     * A non-null-backed empty location set.
     * Do NOT use OrdinalSet.empty() here — its backing set is null and PDG rejects it.
     */
    private static final OrdinalSet<PointerKey> EMPTY_LOC =
            new OrdinalSet<>(MutableSparseIntSet.makeEmpty(), MAPPING);

    /**
     * Returns a map that answers every key with {@link #EMPTY_LOC}, instead of null.
     * PDG requires a non-null value from the mod/ref maps for every node it visits, but computing
     * the global ModRef closure OOMs at 4 GB over a JDK-inclusive call graph. Giving PDG this
     * empty-defaulting map lets it proceed without the global closure; interprocedural heap
     * parameter statements are dropped (that is L4 work, not L3).
     */
    private static Map<CGNode, OrdinalSet<PointerKey>> emptyDefaultingMap() {
        return new HashMap<CGNode, OrdinalSet<PointerKey>>() {
            @Override
            public OrdinalSet<PointerKey> get(Object k) {
                OrdinalSet<PointerKey> v = super.get(k);
                return v != null ? v : EMPTY_LOC;
            }
        };
    }

    // ----- instance state -----------------------------------------------------------------------

    private final CallGraph callGraph;
    private final PointerAnalysis<InstanceKey> pa;
    private final ModRef<InstanceKey> modRef;
    private final Map<CGNode, OrdinalSet<PointerKey>> emptyMod;
    private final Map<CGNode, OrdinalSet<PointerKey>> emptyRef;
    private final List<MethodIr> applicationMethods;

    private WalaAnalysis(
            CallGraph callGraph,
            PointerAnalysis<InstanceKey> pa) {
        this.callGraph = callGraph;
        this.pa = pa;
        this.modRef = ModRef.make();
        this.emptyMod = emptyDefaultingMap();
        this.emptyRef = emptyDefaultingMap();
        this.applicationMethods = buildApplicationMethods(callGraph);
    }

    // ----- factory method -----------------------------------------------------------------------

    /**
     * Builds the RTA call graph for {@code input} and returns a ready-to-use {@link WalaAnalysis},
     * or {@link Optional#empty()} when construction fails for any reason (absent build, missing
     * {@code JAVA_HOME}, WALA error, …). Never throws.
     *
     * @param input        the project root
     * @param dependencies directory of dependency jars, or {@code null}
     * @param build        the build command ({@code "auto"}, a custom command, or {@code null} to
     *                     skip building and stream pre-compiled {@code .class} files directly)
     */
    public static Optional<WalaAnalysis> of(String input, String dependencies, String build) {
        // Mirror RtaCallGraph.endpoints: default the global so BuildProject's static initializer
        // does not NPE when it resolves the build-tool wrappers.
        if (CodeAnalyzer.projectRootPom == null) {
            CodeAnalyzer.projectRootPom = input;
        }

        // Mute stdout/stderr across the whole phase — same rationale as RtaCallGraph.
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(NullOutputStream.INSTANCE));
        System.setErr(new PrintStream(NullOutputStream.INSTANCE));
        try {
            AnalysisScope scope = ScopeUtils.createScope(input, dependencies, build);
            IClassHierarchy cha =
                    ClassHierarchyFactory.make(scope, new ECJClassLoaderFactory(scope.getExclusions()));

            AnalysisOptions options = new AnalysisOptions();
            options.setEntrypoints(AnalysisUtils.getEntryPoints(cha));
            options.getSSAOptions().setDefaultValues(com.ibm.wala.ssa.SymbolTable::getDefaultValue);
            options.setReflectionOptions(ReflectionOptions.NONE);
            IAnalysisCacheView cache =
                    new AnalysisCacheImpl(AstIRFactory.makeDefaultFactory(), options.getSSAOptions());

            CallGraphBuilder<InstanceKey> builder = Util.makeRTABuilder(options, cache, cha);
            CallGraph cg = builder.makeCallGraph(options, null);
            PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();

            return Optional.of(new WalaAnalysis(cg, pa));
        } catch (Throwable t) {
            return Optional.empty();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    // ----- public API ---------------------------------------------------------------------------

    /**
     * One {@link MethodIr} per application {@link CGNode} (filtered by
     * {@link AnalysisUtils#isApplicationClass}). Nodes whose method is not an
     * {@link IBytecodeMethod} or whose IR is null are excluded.
     */
    public List<MethodIr> applicationMethods() {
        return applicationMethods;
    }

    /**
     * The SSA source line for {@code instructionIndex} within {@code m}, or {@code -1} when the
     * line-number table is absent or the index is out of range. Never throws.
     */
    public int sourceLine(MethodIr m, int instructionIndex) {
        try {
            return m.method.getLineNumber(m.bytecode.getBytecodeIndex(instructionIndex));
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Constructs a {@link PDG} for {@code node} using the retained pointer analysis and the shared
     * empty-defaulting mod/ref maps. The global ModRef closure is intentionally skipped; PDG
     * computes each method's heap du-pairs from its own per-instruction getMod/getRef.
     */
    public PDG<InstanceKey> pdgFor(CGNode node) {
        return new PDG<>(
                node,
                pa,
                emptyMod,
                emptyRef,
                Slicer.DataDependenceOptions.FULL,
                Slicer.ControlDependenceOptions.FULL,
                null,
                callGraph,
                modRef);
    }

    /**
     * The RTA call graph's edges as endpoint pairs. Delegates to
     * {@link RtaCallGraph#toEndpoints(CallGraph)} so the L2 {@code rta} overlay is byte-identical
     * whether produced here or via {@link RtaCallGraph#endpoints}.
     */
    public List<RtaEndpoint> rtaEndpoints() {
        return RtaCallGraph.toEndpoints(callGraph);
    }

    // ----- helpers ------------------------------------------------------------------------------

    private static List<MethodIr> buildApplicationMethods(CallGraph callGraph) {
        List<MethodIr> result = new ArrayList<>();
        for (CGNode node : callGraph) {
            IMethod method = node.getMethod();
            if (!AnalysisUtils.isApplicationClass(method.getDeclaringClass())) {
                continue;
            }
            if (!(method instanceof IBytecodeMethod)) {
                continue;
            }
            IR ir = node.getIR();
            if (ir == null) {
                continue;
            }
            result.add(new MethodIr(node, method, ir, (IBytecodeMethod<?>) method));
        }
        // Sort deterministically: declaring-type name first (primary key), then method signature (tiebreaker).
        // WALA iterates call-graph nodes in hash-based order, which is not stable across JVM runs.
        result.sort(
                java.util.Comparator.comparing(
                                (MethodIr m) -> m.method.getDeclaringClass().getName().toString())
                        .thenComparing(m -> m.method.getSignature()));
        return result;
    }

    // ----- inner types --------------------------------------------------------------------------

    /**
     * A bundle of the call-graph node, its method, its SSA IR, and its bytecode view.
     * The bytecode view is used for line-number resolution; the IR is used for instruction
     * iteration.
     */
    public static final class MethodIr {
        public final CGNode node;
        public final IMethod method;
        public final IR ir;
        public final IBytecodeMethod<?> bytecode;

        MethodIr(CGNode node, IMethod method, IR ir, IBytecodeMethod<?> bytecode) {
            this.node = node;
            this.method = method;
            this.ir = ir;
            this.bytecode = bytecode;
        }
    }
}
