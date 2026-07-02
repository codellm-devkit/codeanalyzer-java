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

package com.ibm.cldk.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Data;

/**
 * The statement-level program graphs emitted at analysis level 3 (the CLDK level-3 dataflow
 * contract). Functions are keyed by the fully qualified signature
 * {@code <typeDeclaration>.<callableSignature>}; every node inside a function is identified by a
 * small integer id that is stable across runs on identical content: 0 is the synthetic ENTRY,
 * SSA instructions follow in instruction-index order, and the last id is the synthetic EXIT.
 * Cross-function edges reference both endpoints by (signature, node).
 */
@Data
public class ProgramGraphs {
    private String schemaVersion = "1.0.0";
    /** Which data-dependence options the WALA slicer ran with: "no-heap" or "full". */
    private String dataDependence;
    /** Sorted so output is deterministic across runs. */
    private Map<String, FunctionProgramGraph> functions = new TreeMap<>();
    private List<SdgEdge> sdgEdges = new ArrayList<>();

    /** Per-callable graphs: the CFG over (signature, node_id) nodes and the PDG edges over them. */
    @Data
    public static class FunctionProgramGraph {
        private String signature;
        private String typeDeclaration;
        private String filePath;
        private Cfg cfg;
        private Pdg pdg;
    }

    @Data
    public static class Cfg {
        private List<Node> nodes = new ArrayList<>();
        private List<CfgEdge> edges = new ArrayList<>();
    }

    @Data
    public static class Pdg {
        private List<PdgEdge> edges = new ArrayList<>();
    }

    @Data
    public static class Node {
        private int id;
        /** entry | exit | call | branch | switch | return | throw | new | instruction */
        private String kind;
        /** -1 when no source mapping is available (synthetic nodes, missing debug info). */
        private int startLine = -1;
        private int endLine = -1;
    }

    @Data
    public static class CfgEdge {
        private int source;
        private int target;
        /** fallthrough | true | false | switch_case | loop_back | exception | return */
        private String kind;

        public CfgEdge(int source, int target, String kind) {
            this.source = source;
            this.target = target;
            this.kind = kind;
        }
    }

    @Data
    public static class PdgEdge {
        private int source;
        private int target;
        /** CDG | DDG */
        private String type;
        /** The raw WALA dependence label (e.g. CONTROL_DEP, DATA_DEP) for provenance. */
        private String label;

        public PdgEdge(int source, int target, String type, String label) {
            this.source = source;
            this.target = target;
            this.type = type;
            this.label = label;
        }
    }

    /** A cross-function dependence edge; endpoints are (signature, node). */
    @Data
    public static class SdgEdge {
        private SdgEndpoint source;
        private SdgEndpoint target;
        /** CALL | PARAM_IN | PARAM_OUT */
        private String type;
        /** The raw WALA dependence label for provenance. */
        private String label;

        public SdgEdge(SdgEndpoint source, SdgEndpoint target, String type, String label) {
            this.source = source;
            this.target = target;
            this.type = type;
            this.label = label;
        }
    }

    @Data
    public static class SdgEndpoint {
        private String signature;
        private int node;

        public SdgEndpoint(String signature, int node) {
            this.signature = signature;
            this.node = node;
        }
    }
}
