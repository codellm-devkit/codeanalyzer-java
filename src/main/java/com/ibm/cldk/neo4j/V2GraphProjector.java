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
package com.ibm.cldk.neo4j;

import com.ibm.cldk.neo4j.GraphRows.NodeRef;
import com.ibm.cldk.schema.Analysis;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JCdgEdge;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.schema.JComment;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JEnumConstant;
import com.ibm.cldk.schema.JExternalSymbol;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.JIdEdge;
import com.ibm.cldk.schema.JImport;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JRecordComponent;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JVariableDeclaration;
import com.ibm.cldk.schema.Span;
import com.ibm.cldk.schema.V2Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The schema v2 → Neo4j projection: a pure {@code (Analysis, appName) → GraphRows} function, no
 * I/O, no driver. The vocabulary is {@link V2SchemaCatalog} (graph contract 2.1.0), mirroring
 * codeanalyzer-python's projection: call sites are {@code :JBodyNode} rows (no call-site nodes),
 * parameters flatten to {@code parameters_json}, javadoc collapses to {@code docstring}, and the
 * L3 {@code cfg}/{@code cdg}/{@code ddg} and L4 {@code param_in}/{@code param_out}/{@code summary}
 * overlays become typed relationships between body nodes.
 *
 * <p>Body-node identity is the global ordinal: {@code <callable-id>@<local-key>} for real
 * statements ({@code 12:5}), {@code <callable-id><local-key>} for synthetic bookends whose local
 * key already starts with {@code @} ({@code @entry}, {@code @exit}) — the same rule the JSON
 * projection's ids follow, so both projections land on one identity.
 */
public final class V2GraphProjector {

    private V2GraphProjector() {}

    private static final List<String> SYMBOL_TYPE = Arrays.asList("JSymbol", "JType");
    private static final List<String> SYMBOL_CALLABLE = Arrays.asList("JSymbol", "JCallable");
    private static final List<String> SYMBOL_EXTERNAL = Arrays.asList("JSymbol", "JExternal");

    public static GraphRows project(Analysis analysis, String appName) {
        RowBuilder b = new RowBuilder();
        Map<String, JModule> symbolTable = analysis.getApplication().getSymbolTable();

        Map<String, Object> appProps = RowBuilder.props();
        appProps.put("name", appName);
        appProps.put("schema_version", V2SchemaCatalog.SCHEMA_VERSION);
        if (analysis.getAnalyzer() != null) {
            appProps.put("analyzer_name", analysis.getAnalyzer().getName());
            appProps.put("analyzer_version", analysis.getAnalyzer().getVersion());
        }
        NodeRef app = b.node(Arrays.asList("JApplication"), "name", appName, RowBuilder.prune(appProps));

        // First pass: an in-project index from a type's qualified (dotted) name to its node id and
        // owning module id, for resolving extends/implements/import spellings to emitted nodes.
        Map<String, String> typeIdByFqn = new LinkedHashMap<>();
        Map<String, String> moduleIdByFqn = new LinkedHashMap<>();
        for (Map.Entry<String, JModule> m : symbolTable.entrySet()) {
            indexTypes(m.getValue(), m.getValue().getTypes(), null, typeIdByFqn, moduleIdByFqn);
        }

        for (Map.Entry<String, JModule> entry : symbolTable.entrySet()) {
            String fileKey = entry.getKey();
            JModule module = entry.getValue();

            Map<String, Object> mp = RowBuilder.props();
            mp.put("id", module.getId());
            mp.put("file_key", fileKey);
            mp.put("package", module.getPackageName());
            mp.put("content_hash", module.getContentHash());
            mp.put("_module", fileKey);
            NodeRef mod = b.node(Arrays.asList("JModule"), "id", module.getId(), RowBuilder.prune(mp));
            b.edge("J_HAS_MODULE", app, mod);

            projectImports(b, mod, module, typeIdByFqn, moduleIdByFqn);

            for (Map.Entry<String, JType> t : module.getTypes().entrySet()) {
                projectType(b, mod, "J_DECLARES", t.getKey(), t.getValue(), module, fileKey,
                        typeIdByFqn);
            }
        }

        if (analysis.getApplication().getExternalSymbols() != null) {
            for (Map.Entry<String, JExternalSymbol> e
                    : analysis.getApplication().getExternalSymbols().entrySet()) {
                JExternalSymbol ext = e.getValue();
                Map<String, Object> p = RowBuilder.props();
                p.put("id", e.getKey());
                p.put("kind", ext.getKind());
                p.put("signature", ext.getSignature());
                p.put("declaring_type", ext.getDeclaringType());
                b.node(SYMBOL_EXTERNAL, "id", e.getKey(), RowBuilder.prune(p));
            }
        }

        if (analysis.getApplication().getParamIn() != null) {
            for (JIdEdge e : analysis.getApplication().getParamIn()) {
                b.edgeIfBothResolved("J_PARAM_IN",
                        new NodeRef("JBodyNode", "id", e.getSrc()),
                        new NodeRef("JBodyNode", "id", e.getDst()), RowBuilder.props());
            }
        }
        if (analysis.getApplication().getParamOut() != null) {
            for (JIdEdge e : analysis.getApplication().getParamOut()) {
                b.edgeIfBothResolved("J_PARAM_OUT",
                        new NodeRef("JBodyNode", "id", e.getSrc()),
                        new NodeRef("JBodyNode", "id", e.getDst()), RowBuilder.props());
            }
        }

        if (analysis.getApplication().getCallGraph() != null) {
            for (JCallEdge e : analysis.getApplication().getCallGraph()) {
                Map<String, Object> p = RowBuilder.props();
                p.put("weight", e.getWeight());
                p.put("prov", e.getProv());
                b.edgeIfBothResolved("J_CALLS",
                        new NodeRef("JSymbol", "id", e.getSrc()),
                        new NodeRef("JSymbol", "id", e.getDst()),
                        RowBuilder.prune(p));
            }
        }

        return b.finish();
    }

    // ------------------------------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------------------------------

    private static void indexTypes(JModule module, Map<String, JType> types, String qualifierFqn,
            Map<String, String> typeIdByFqn, Map<String, String> moduleIdByFqn) {
        for (Map.Entry<String, JType> e : types.entrySet()) {
            String fqn = qualifierFqn != null
                    ? qualifierFqn + "." + e.getKey()
                    : (module.getPackageName() == null || module.getPackageName().isEmpty()
                            ? e.getKey()
                            : module.getPackageName() + "." + e.getKey());
            typeIdByFqn.put(fqn, e.getValue().getId());
            moduleIdByFqn.put(fqn, module.getId());
            indexTypes(module, e.getValue().getTypes(), fqn, typeIdByFqn, moduleIdByFqn);
            for (JCallable c : e.getValue().getCallables().values()) {
                indexTypes(module, c.getTypes(), fqn, typeIdByFqn, moduleIdByFqn);
            }
        }
    }

    private static void projectType(RowBuilder b, NodeRef parent, String containmentRel, String name,
            JType type, JModule module, String fileKey, Map<String, String> typeIdByFqn) {
        List<String> labels = type.isEntrypointClass()
                ? Arrays.asList("JSymbol", "JType", "JEntrypoint")
                : SYMBOL_TYPE;
        Map<String, Object> p = RowBuilder.props();
        p.put("id", type.getId());
        p.put("name", name);
        p.put("kind", type.getKind());
        p.put("modifiers", type.getModifiers());
        p.put("base_types", type.getBaseTypes());
        p.put("interfaces", type.getInterfaces());
        p.put("docstring", docstringOf(type.getComments()));
        putLines(p, type.getSpan());
        if (type.isEntrypointClass()) {
            p.put("is_entrypoint", true);
        }
        p.put("_module", fileKey);
        NodeRef ref = b.node(labels, "id", type.getId(), RowBuilder.prune(p));
        b.edge(containmentRel, parent, ref);

        String pkg = module.getPackageName();
        for (String base : type.getBaseTypes()) {
            String target = resolveType(base, pkg, typeIdByFqn);
            if (target != null) {
                b.edgeToSymbol("J_EXTENDS", ref, target);
            }
        }
        for (String iface : type.getInterfaces()) {
            String target = resolveType(iface, pkg, typeIdByFqn);
            if (target != null) {
                b.edgeToSymbol("J_IMPLEMENTS", ref, target);
            }
        }
        annotate(b, ref, type.getDecorators());

        for (Map.Entry<String, JField> f : type.getFields().entrySet()) {
            projectField(b, ref, f.getValue(), fileKey);
        }
        for (JEnumConstant ec : type.getEnumConstants()) {
            Map<String, Object> ep = RowBuilder.props();
            String id = type.getId() + "#enum#" + ec.getName();
            ep.put("id", id);
            ep.put("name", ec.getName());
            ep.put("arguments", ec.getArguments());
            ep.put("docstring", docstringOf(ec.getComments()));
            ep.put("_module", fileKey);
            NodeRef er = b.node(Arrays.asList("JEnumConstant"), "id", id, RowBuilder.prune(ep));
            b.edge("J_HAS_ENUM_CONSTANT", ref, er);
        }
        for (JRecordComponent rc : type.getRecordComponents()) {
            Map<String, Object> rp = RowBuilder.props();
            String id = type.getId() + "#rec#" + rc.getName();
            rp.put("id", id);
            rp.put("name", rc.getName());
            rp.put("type", rc.getType());
            rp.put("modifiers", rc.getModifiers());
            if (rc.isVariadic()) {
                rp.put("is_variadic", true);
            }
            rp.put("docstring", docstringOf(rc.getComments()));
            rp.put("_module", fileKey);
            NodeRef rr = b.node(Arrays.asList("JRecordComponent"), "id", id, RowBuilder.prune(rp));
            b.edge("J_HAS_RECORD_COMPONENT", ref, rr);
        }
        for (Map.Entry<String, JCallable> c : type.getCallables().entrySet()) {
            projectCallable(b, ref, c.getKey(), c.getValue(), module, fileKey, typeIdByFqn);
        }
        for (Map.Entry<String, JType> nested : type.getTypes().entrySet()) {
            projectType(b, ref, "J_DECLARES", nested.getKey(), nested.getValue(), module, fileKey,
                    typeIdByFqn);
        }
    }

    private static void projectField(RowBuilder b, NodeRef owner, JField field, String fileKey) {
        Map<String, Object> p = RowBuilder.props();
        p.put("id", field.getId());
        p.put("name", field.getName());
        p.put("type", field.getType());
        p.put("initializer", field.getInitializer());
        p.put("modifiers", field.getModifiers());
        p.put("docstring", docstringOf(field.getComments()));
        putLines(p, field.getSpan());
        p.put("_module", fileKey);
        NodeRef ref = b.node(Arrays.asList("JField"), "id", field.getId(), RowBuilder.prune(p));
        b.edge("J_HAS_FIELD", owner, ref);
        annotate(b, ref, field.getDecorators());
    }

    // ------------------------------------------------------------------------------------------
    // Callables + the L3 CPG overlay
    // ------------------------------------------------------------------------------------------

    private static void projectCallable(RowBuilder b, NodeRef owner, String signature, JCallable c,
            JModule module, String fileKey, Map<String, String> typeIdByFqn) {
        List<String> labels = c.isEntrypoint()
                ? Arrays.asList("JSymbol", "JCallable", "JEntrypoint")
                : SYMBOL_CALLABLE;
        Map<String, Object> p = RowBuilder.props();
        p.put("id", c.getId());
        p.put("name", signature.contains("(") ? signature.substring(0, signature.indexOf('(')) : signature);
        p.put("signature", signature);
        p.put("kind", c.getKind());
        p.put("declaration", c.getDeclaration());
        p.put("return_type", c.getReturnType());
        if (!c.getParameters().isEmpty()) {
            p.put("parameters_json", V2Json.compact().toJson(c.getParameters()));
        }
        p.put("modifiers", c.getModifiers());
        p.put("error_channel", c.getErrorChannel());
        p.put("code", slice(module.getSource(), c.getSpan()));
        p.put("docstring", docstringOf(c.getComments()));
        if (c.getMetrics() != null) {
            p.put("cyclomatic_complexity", c.getMetrics().getCyclomatic());
        }
        if (c.getRefs() != null) {
            p.put("referenced_types", c.getRefs().getTypes());
            p.put("accessed_fields", c.getRefs().getFields());
        }
        if (c.isImplicit()) {
            p.put("is_implicit", true);
        }
        if (c.isEntrypoint()) {
            p.put("is_entrypoint", true);
        }
        putLines(p, c.getSpan());
        p.put("_module", fileKey);
        NodeRef ref = b.node(labels, "id", c.getId(), RowBuilder.prune(p));
        b.edge("J_HAS_METHOD", owner, ref);
        annotate(b, ref, c.getDecorators());

        for (JVariableDeclaration v : c.getLocalVariables()) {
            Map<String, Object> vp = RowBuilder.props();
            int line = v.getSpan() != null && v.getSpan().getStart() != null ? v.getSpan().getStart()[0] : 0;
            String id = c.getId() + "#" + v.getName() + "@" + line;
            vp.put("id", id);
            vp.put("name", v.getName());
            vp.put("type", v.getType());
            vp.put("initializer", v.getInitializer());
            putLines(vp, v.getSpan());
            vp.put("_module", fileKey);
            NodeRef vr = b.node(Arrays.asList("JVariable"), "id", id, RowBuilder.prune(vp));
            b.edge("J_DECLARES_VAR", ref, vr);
        }

        for (Map.Entry<String, JBodyNode> e : c.getBody().entrySet()) {
            JBodyNode n = e.getValue();
            String id = globalOrdinal(c.getId(), e.getKey());
            Map<String, Object> np = RowBuilder.props();
            np.put("id", id);
            np.put("kind", n.getKind());
            np.put("method_name", n.getMethodName());
            np.put("receiver_expr", n.getReceiverExpr());
            np.put("receiver_type", n.getReceiverType());
            np.put("return_type", n.getReturnType());
            np.put("accessibility", n.getAccessibility());
            if (n.isConstructorCall()) {
                np.put("is_constructor_call", true);
            }
            np.put("is_static_call", n.getIsStaticCall());
            np.put("argument_types", n.getArgumentTypes());
            np.put("argument_expr", n.getArgumentExpr());
            putLines(np, n.getSpan());
            np.put("_module", fileKey);
            // L4 SDG synthetic-vertex payload: absent on every non-synthetic node (prune drops nulls).
            np.put("var", n.getOf());
            np.put("call_node", n.getParent() == null ? null : globalOrdinal(c.getId(), n.getParent()));
            NodeRef nr = b.node(Arrays.asList("JBodyNode"), "id", id, RowBuilder.prune(np));
            b.edge("J_HAS_BODY_NODE", ref, nr);
            if (n.getCallee() != null) {
                b.edgeToSymbol("J_RESOLVES_TO", nr, n.getCallee());
            }
        }

        if (c.getCfg() != null) {
            for (JCfgEdge e : c.getCfg()) {
                Map<String, Object> ep = RowBuilder.props();
                ep.put("kind", e.getKind());
                b.keyedEdge("J_CFG_NEXT", bodyRef(c, e.getSrc()), bodyRef(c, e.getDst()),
                        RowBuilder.prune(ep), e.getKind() == null ? "" : e.getKind());
            }
        }
        if (c.getCdg() != null) {
            for (JCdgEdge e : c.getCdg()) {
                b.edge("J_CDG", bodyRef(c, e.getSrc()), bodyRef(c, e.getDst()));
            }
        }
        if (c.getDdg() != null) {
            for (JDdgEdge e : c.getDdg()) {
                Map<String, Object> ep = RowBuilder.props();
                ep.put("var", e.getVar());
                ep.put("prov", e.getProv());
                String k = (e.getVar() == null ? "" : e.getVar()) + "|"
                        + (e.getProv() == null ? "" : String.join(",", e.getProv()));
                b.keyedEdge("J_DDG", bodyRef(c, e.getSrc()), bodyRef(c, e.getDst()),
                        RowBuilder.prune(ep), k);
            }
        }
        if (c.getSummary() != null) {
            // Endpoints are call-site-local ids on c's own body (SummaryPass.emit), same globalOrdinal
            // rule as J_CFG_NEXT/J_CDG/J_DDG above — no resolution gating needed, unlike the
            // application-scope param_in/param_out edges above, which cross into another callable's body.
            for (JIdEdge e : c.getSummary()) {
                b.edge("J_SUMMARY", bodyRef(c, e.getSrc()), bodyRef(c, e.getDst()));
            }
        }

        for (Map.Entry<String, JType> local : c.getTypes().entrySet()) {
            projectType(b, ref, "J_DECLARES", local.getKey(), local.getValue(), module, fileKey,
                    typeIdByFqn);
        }
    }

    private static NodeRef bodyRef(JCallable c, String localKey) {
        return new NodeRef("JBodyNode", "id", globalOrdinal(c.getId(), localKey));
    }

    /** {@code @entry}-style synthetic keys concatenate; real {@code line:col} keys get an {@code @}. */
    private static String globalOrdinal(String callableId, String localKey) {
        return localKey.startsWith("@") ? callableId + localKey : callableId + "@" + localKey;
    }

    // ------------------------------------------------------------------------------------------
    // Imports, annotations, shared helpers
    // ------------------------------------------------------------------------------------------

    private static void projectImports(RowBuilder b, NodeRef mod, JModule module,
            Map<String, String> typeIdByFqn, Map<String, String> moduleIdByFqn) {
        // Aggregate per target so one edge carries every spelling importing it (python parity).
        Map<String, List<JImport>> byTarget = new LinkedHashMap<>();
        Map<String, NodeRef> refByTarget = new LinkedHashMap<>();
        for (JImport imp : module.getImports()) {
            String path = imp.getPath();
            if (path == null || path.isEmpty()) {
                continue;
            }
            String targetModule = imp.isWildcard() ? null : moduleIdByFqn.get(path);
            String targetKey;
            NodeRef target;
            if (targetModule != null) {
                targetKey = "module:" + targetModule;
                target = new NodeRef("JModule", "id", targetModule);
            } else {
                // Out-of-project (or wildcard): a shared :JPackage keyed by the package prefix.
                String pkg = imp.isWildcard() ? path
                        : (path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : path);
                targetKey = "package:" + pkg;
                target = b.node(Arrays.asList("JPackage"), "name", pkg,
                        RowBuilder.prune(mapOf("name", pkg)));
            }
            byTarget.computeIfAbsent(targetKey, x -> new ArrayList<>()).add(imp);
            refByTarget.put(targetKey, target);
        }
        for (Map.Entry<String, List<JImport>> e : byTarget.entrySet()) {
            List<String> spellings = new ArrayList<>();
            boolean anyStatic = false;
            boolean anyWildcard = false;
            for (JImport imp : e.getValue()) {
                spellings.add(imp.getPath());
                anyStatic |= imp.isStatic();
                anyWildcard |= imp.isWildcard();
            }
            Map<String, Object> p = RowBuilder.props();
            p.put("spellings", spellings);
            if (anyStatic) {
                p.put("is_static", true);
            }
            if (anyWildcard) {
                p.put("is_wildcard", true);
            }
            // In-project targets are :JModule rows this same run emits; the rest are shared
            // :JPackage rows created above — either way the endpoint exists, no defer-gating.
            b.edge("J_IMPORTS", mod, refByTarget.get(e.getKey()), RowBuilder.prune(p));
        }
    }

    private static void annotate(RowBuilder b, NodeRef owner, List<JDecorator> decorators) {
        for (JDecorator d : decorators) {
            if (d.getName() == null || d.getName().isEmpty()) {
                continue;
            }
            NodeRef ann = b.node(Arrays.asList("JAnnotation"), "name", d.getName(),
                    RowBuilder.prune(mapOf("name", d.getName())));
            Map<String, Object> p = RowBuilder.props();
            p.put("arguments", d.getArgs());
            b.edge("J_ANNOTATED_BY", owner, ann, RowBuilder.prune(p));
        }
    }

    /**
     * Resolve a type spelling from the tree ({@code com.example.Base} or a bare {@code Base}) to an
     * in-project type id, trying the spelling as-is then package-qualified. Unresolved spellings
     * stay as the string fallback on the node's {@code base_types}/{@code interfaces} props.
     */
    private static String resolveType(String spelling, String pkg, Map<String, String> typeIdByFqn) {
        if (spelling == null || spelling.isEmpty()) {
            return null;
        }
        String direct = typeIdByFqn.get(spelling);
        if (direct != null) {
            return direct;
        }
        if (pkg != null && !pkg.isEmpty()) {
            return typeIdByFqn.get(pkg + "." + spelling);
        }
        return null;
    }

    /** Join the javadoc comments into one {@code docstring}; non-doc comments are not projected. */
    private static String docstringOf(List<JComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        List<String> docs = new ArrayList<>();
        for (JComment c : comments) {
            if (c.isJavadoc() && c.getContent() != null && !c.getContent().isEmpty()) {
                docs.add(c.getContent());
            }
        }
        return docs.isEmpty() ? null : String.join("\n", docs);
    }

    /** UTF-8 byte slice of the module source per the node's {@code span.bytes}. */
    private static String slice(String source, Span span) {
        if (source == null || span == null || span.getBytes() == null || span.getBytes().length < 2) {
            return null;
        }
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int start = span.getBytes()[0];
        int end = span.getBytes()[1];
        if (start < 0 || end > bytes.length || start >= end) {
            return null;
        }
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private static void putLines(Map<String, Object> p, Span span) {
        if (span != null && span.getStart() != null && span.getStart().length > 0) {
            p.put("start_line", span.getStart()[0]);
        }
        if (span != null && span.getEnd() != null && span.getEnd().length > 0) {
            p.put("end_line", span.getEnd()[0]);
        }
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = RowBuilder.props();
        m.put(k, v);
        return m;
    }
}
