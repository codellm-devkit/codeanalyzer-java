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

import com.ibm.cldk.syntactic_analysis.L2CallGraph.RtaEndpoint;
import com.ibm.cldk.utils.AnalysisUtils;
import com.ibm.cldk.utils.Log;
import com.ibm.cldk.utils.ScopeUtils;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.java.translator.jdt.ecj.ECJClassLoaderFactory;
import com.ibm.wala.classLoader.CallSiteReference;
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
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.output.NullOutputStream;
import org.objectweb.asm.Type;

/**
 * Builds WALA's RTA call graph for a built application and reduces it to {@link RtaEndpoint} pairs for
 * {@code L2CallGraph} to join as the {@code rta} overlay (§5).
 *
 * <p>The RTA graph is consumed unchanged — this class contributes attestations and edges, never nodes.
 * It mirrors the v1 construction ({@code ScopeUtils.createScope} → CHA → {@code Util.makeRTABuilder}),
 * but instead of v1's {@code .replace("$", ".")} de-mangling it emits <em>binary</em> declaring-type
 * names (so the join lands on the same key WALA uses natively) and erased source signatures (via ASM,
 * the same spelling {@code Signatures} produces). Any failure — an absent build, a missing
 * {@code JAVA_HOME}, a WALA error — degrades to an empty list: {@code declared} edges still ship.
 */
public final class RtaCallGraph {

    private RtaCallGraph() {}

    /**
     * The RTA call graph's edges as endpoint pairs, or an empty list when it cannot be built. Never
     * throws: {@code -a 2} must not fail for want of a build.
     *
     * @param input the project root
     * @param dependencies directory of dependency jars, or {@code null}
     * @param build the build command ({@code "auto"}, a custom command, or {@code null} to skip building)
     */
    public static List<RtaEndpoint> endpoints(String input, String dependencies, String build) {
        // The build machinery (BuildProject) keys its working directory off the global
        // CodeAnalyzer.projectRootPom — read even from its static initializer. The CLI sets it before any
        // build; default it here so a direct caller (the RTA suite, the SDK) need not know that coupling.
        if (CodeAnalyzer.projectRootPom == null) {
            CodeAnalyzer.projectRootPom = input;
        }
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            AnalysisScope scope = ScopeUtils.createScope(input, dependencies, build);
            IClassHierarchy cha =
                    ClassHierarchyFactory.make(scope, new ECJClassLoaderFactory(scope.getExclusions()));
            // Surface the application-class count: when it is far below the number of compiled classes,
            // the WALA scope admitted only a fraction of the project (e.g. a dependency jar shadowing the
            // project's own classes into the Extension loader), and the rta overlay will be thin.
            Log.info("RTA class hierarchy: " + cha.getNumberOfClasses() + " total classes, "
                    + AnalysisUtils.getNumberOfApplicationClasses(cha) + " application classes");

            AnalysisOptions options = new AnalysisOptions();
            options.setEntrypoints(AnalysisUtils.getEntryPoints(cha));
            options.getSSAOptions().setDefaultValues(com.ibm.wala.ssa.SymbolTable::getDefaultValue);
            options.setReflectionOptions(ReflectionOptions.NONE);
            IAnalysisCacheView cache =
                    new AnalysisCacheImpl(AstIRFactory.makeDefaultFactory(), options.getSSAOptions());

            CallGraph callGraph;
            try {
                // WALA writes progress to stdout/stderr; stdout is our JSON data channel, so mute both.
                System.setOut(new PrintStream(NullOutputStream.INSTANCE));
                System.setErr(new PrintStream(NullOutputStream.INSTANCE));
                CallGraphBuilder<InstanceKey> builder = Util.makeRTABuilder(options, cache, cha);
                callGraph = builder.makeCallGraph(options, null);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
            return toEndpoints(callGraph);
        } catch (Throwable t) {
            System.setOut(originalOut);
            System.setErr(originalErr);
            Log.warn("RTA call graph unavailable (" + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "); emitting declared edges only");
            return List.of();
        }
    }

    /** One endpoint pair per resolved (caller, call site, target) occurrence, sourced from app classes. */
    private static List<RtaEndpoint> toEndpoints(CallGraph callGraph) {
        List<RtaEndpoint> endpoints = new ArrayList<>();
        for (CGNode node : callGraph) {
            IMethod caller = node.getMethod();
            // Only app-sourced edges can be attributed to an in-project node; skip library callers.
            if (!AnalysisUtils.isApplicationClass(caller.getDeclaringClass())) {
                continue;
            }
            String srcType = binaryTypeName(caller);
            String srcSignature = signature(caller);
            Iterator<CallSiteReference> sites = node.iterateCallSites();
            while (sites.hasNext()) {
                CallSiteReference site = sites.next();
                for (CGNode target : callGraph.getPossibleTargets(node, site)) {
                    IMethod callee = target.getMethod();
                    endpoints.add(new RtaEndpoint(
                            true,
                            srcType,
                            srcSignature,
                            AnalysisUtils.isApplicationClass(callee.getDeclaringClass()),
                            binaryTypeName(callee),
                            signature(callee)));
                }
            }
        }
        return endpoints;
    }

    private static String binaryTypeName(IMethod method) {
        return binaryTypeName(method.getDeclaringClass().getName().toString());
    }

    /** {@code Lorg/example/Map$Entry} → {@code org.example.Map$Entry} — a binary name, {@code $} kept. */
    static String binaryTypeName(String walaTypeName) {
        String name = walaTypeName.startsWith("L") ? walaTypeName.substring(1) : walaTypeName;
        return name.replace('/', '.');
    }

    private static String signature(IMethod method) {
        return signature(method.getName().toString(), method.getDescriptor().toString());
    }

    /**
     * {@code <name>(<erased source param types>)} from a JVM descriptor — {@code java.util.List},
     * {@code int}, {@code java.lang.String[]}: the spelling {@code Signatures} produces, so the two join.
     */
    static String signature(String methodName, String descriptor) {
        List<String> arguments = Arrays.stream(Type.getMethodType(descriptor).getArgumentTypes())
                .map(Type::getClassName)
                .collect(Collectors.toList());
        return methodName + "(" + String.join(", ", arguments) + ")";
    }
}
