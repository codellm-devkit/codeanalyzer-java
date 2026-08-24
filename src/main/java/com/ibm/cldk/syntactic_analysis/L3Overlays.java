package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCdgEdge;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.syntactic_analysis.controlflow.CdgBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.CfgBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import com.ibm.cldk.syntactic_analysis.dataflow.DdgBuilder;
import java.util.List;
import java.util.Map;

/**
 * The L3 orchestrator (sibling to {@link L2CallGraph}): given a callable's JavaParser body block and
 * its L1 {@code call} nodes, completes the body with statement nodes and computes the three L3
 * overlays — {@code cfg}/{@code cdg} (control, via the {@code controlflow} package) and {@code ddg}
 * (data, via the {@code dataflow} package). Pure and side-effect free — the caller
 * ({@code CallableBuilder}) merges the {@link L3Result} onto the {@code JCallable}. Runs at parse time,
 * where the {@code BlockStmt} and symbol solver are live (the "ast" engine).
 */
public final class L3Overlays {

    private L3Overlays() {}

    /** The completed body plus the three overlays, ready to set on the callable. */
    public static final class L3Result {
        private final Map<String, JBodyNode> body;
        private final List<JCfgEdge> cfg;
        private final List<JCdgEdge> cdg;
        private final List<JDdgEdge> ddg;

        public L3Result(Map<String, JBodyNode> body, List<JCfgEdge> cfg, List<JCdgEdge> cdg,
                List<JDdgEdge> ddg) {
            this.body = body;
            this.cfg = cfg;
            this.cdg = cdg;
            this.ddg = ddg;
        }

        public Map<String, JBodyNode> body() {
            return body;
        }

        public List<JCfgEdge> cfg() {
            return cfg;
        }

        public List<JCdgEdge> cdg() {
            return cdg;
        }

        public List<JDdgEdge> ddg() {
            return ddg;
        }
    }

    public static L3Result build(BlockStmt body, Map<String, JBodyNode> existingBody, L1BuildContext ctx,
            int fieldDepth) {
        ControlFlowGraph cfg = CfgBuilder.build(body, existingBody, ctx);
        List<JCdgEdge> cdg = CdgBuilder.build(cfg);
        List<JDdgEdge> ddg = DdgBuilder.build(cfg, fieldDepth);
        return new L3Result(cfg.nodes(), cfg.toCfgEdges(), cdg, ddg);
    }
}
