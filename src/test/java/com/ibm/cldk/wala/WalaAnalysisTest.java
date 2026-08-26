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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.RtaCallGraph;
import com.ibm.cldk.syntactic_analysis.L2CallGraph.RtaEndpoint;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link WalaAnalysis}: RTA call graph construction, per-method IR lookup, source-line
 * resolution, PDG construction, and L2 endpoint parity.
 *
 * <p>Realworld-tagged because the WALA scope build loads jmods from JAVA_HOME, which is large and
 * slow, and because it compiles a live Java fixture at test time.
 */
@Tag("realworld")
class WalaAnalysisTest {

    /**
     * The fixture: a minimal class with one method that calls another.
     * Compiled at test time into the temp directory so WALA can load it without a build step.
     */
    private static final String FIXTURE_SOURCE =
            "public class A {\n"
            + "    int f() { return g(); }\n"
            + "    int g() { return 1; }\n"
            + "}";

    /**
     * Compiles {@code source} into {@code dir} and returns the directory path as a String.
     * Uses {@code -g} so bytecode carries line-number tables.
     */
    private static String compileFixture(Path dir, String source) throws Exception {
        Path src = dir.resolve("A.java");
        Files.writeString(src, source);
        int rc = ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-g", "-d", dir.toString(), src.toString());
        if (rc != 0) {
            throw new IllegalStateException("Compilation failed with exit code " + rc);
        }
        return dir.toString();
    }

    @Test
    void walaAnalysisBuildsCallGraphAndExposesApplicationMethods(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp, FIXTURE_SOURCE);

        // projectRootPom must be set before WalaAnalysis.of so BuildProject's static initializer
        // does not NPE when it resolves the build-tool wrappers.
        CodeAnalyzer.projectRootPom = dir;

        // build == null => ScopeUtils streams the pre-compiled .class files without rebuilding.
        Optional<WalaAnalysis> opt = WalaAnalysis.of(dir, null, null);
        assertTrue(opt.isPresent(), "WalaAnalysis.of must succeed on a valid compiled project");
        WalaAnalysis wala = opt.get();

        // (a) applicationMethods() contains a method named f
        List<WalaAnalysis.MethodIr> methods = wala.applicationMethods();
        assertTrue(methods.size() > 0, "applicationMethods must be non-empty");
        boolean hasFMethod = methods.stream()
                .anyMatch(m -> m.method.getName().toString().equals("f"));
        assertTrue(hasFMethod, "applicationMethods must contain method 'f'");

        // (b) sourceLine for f at a valid instruction index is > 0
        WalaAnalysis.MethodIr fIr = methods.stream()
                .filter(m -> m.method.getName().toString().equals("f"))
                .findFirst()
                .orElseThrow();
        // SSA instruction 0 is the method entry; try a few indices until we get a positive line
        boolean foundPositiveLine = false;
        for (int i = 0; i < fIr.ir.getInstructions().length; i++) {
            if (fIr.ir.getInstructions()[i] != null) {
                int line = wala.sourceLine(fIr, i);
                if (line > 0) {
                    foundPositiveLine = true;
                    break;
                }
            }
        }
        assertTrue(foundPositiveLine, "sourceLine must return > 0 for at least one instruction in f");

        // (c) pdgFor(fNode) has nodes
        assertTrue(wala.pdgFor(fIr.node).getNumberOfNodes() > 0,
                "PDG for f must have at least one node");

        // (d) parity: rtaEndpoints() must equal RtaCallGraph.endpoints(dir,null,null) as a set
        // Both are built from the same scope; we compare the toString representations.
        Set<String> walaEndpoints = wala.rtaEndpoints().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        // Reset projectRootPom so RtaCallGraph's guard sets it to dir as well.
        CodeAnalyzer.projectRootPom = null;
        Set<String> rtaEndpoints = RtaCallGraph.endpoints(dir, null, null).stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertEquals(rtaEndpoints, walaEndpoints,
                "rtaEndpoints() must produce the identical set as RtaCallGraph.endpoints()");
    }
}
