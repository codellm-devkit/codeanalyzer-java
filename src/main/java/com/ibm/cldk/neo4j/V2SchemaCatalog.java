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

import com.ibm.cldk.neo4j.SchemaCatalog.NodeLabel;
import com.ibm.cldk.neo4j.SchemaCatalog.RelType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The schema v2 Neo4j graph catalog (graph contract {@code 2.1.0}) — the in-repo source of truth
 * for what {@link V2GraphProjector} may emit, serialized by {@code --emit schema} and enforced by
 * the v2 conformance test. Mirrors codeanalyzer-python's {@code neo4j/schema.py} vocabulary with
 * {@code J}/{@code J_} namespacing; java-only constructs (enum constants, record components,
 * {@code J_IMPLEMENTS}) are additive leaves per the cross-language parity clause
 * (design spec: docs/design/specs/2026-08-27-v2-neo4j-projection.md).
 *
 * <p>Per the python convergence spec there are no call-site / parameter / comment nodes: call sites
 * are {@code :JBodyNode} rows with {@code kind == "call"}, parameters flatten to
 * {@code JCallable.parameters_json}, and javadoc collapses to a {@code docstring} property.
 *
 * <p>{@code SCHEMA_VERSION} bump policy: MAJOR on a breaking change (renamed/removed label,
 * relationship or key), MINOR on an additive one. Stamped onto {@code :JApplication} so consumers
 * detect producer/consumer mismatch at runtime.
 */
public final class V2SchemaCatalog {

    private V2SchemaCatalog() {}

    // 2.1.0: additive MINOR — L4 SDG overlay (JBodyNode.var/call_node; J_PARAM_IN/J_PARAM_OUT/
    // J_SUMMARY, reserved at 2.0.0, now actually emitted).
    public static final String SCHEMA_VERSION = "2.1.0";

    /** Labels layered onto a node in addition to its merge + specific labels. */
    public static final List<String> MARKER_LABELS = Arrays.asList("JEntrypoint");

    /** Tiny ordered-map builder for property declarations. */
    private static final class P {
        private final Map<String, String> m = new LinkedHashMap<>();

        P put(String k, String v) {
            m.put(k, v);
            return this;
        }

        Map<String, String> done() {
            return m;
        }
    }

    private static Map<String, String> lines(P p) {
        return p.put("start_line", "integer").put("end_line", "integer").done();
    }

    public static final List<NodeLabel> NODE_LABELS = buildNodeLabels();
    public static final List<RelType> REL_TYPES = buildRelTypes();

    private static NodeLabel node(String label, String merge, String key, Map<String, String> props) {
        return new NodeLabel(label, merge, key, props);
    }

    private static List<NodeLabel> buildNodeLabels() {
        List<NodeLabel> n = new ArrayList<>();

        n.add(node("JApplication", "JApplication", "name",
                new P().put("name", "string").put("schema_version", "string")
                        .put("analyzer_name", "string").put("analyzer_version", "string").done()));

        n.add(node("JModule", "JModule", "id",
                new P().put("id", "string").put("file_key", "string").put("package", "string")
                        .put("content_hash", "string").put("_module", "string").done()));

        n.add(node("JType", "JSymbol", "id",
                lines(new P().put("id", "string").put("name", "string").put("kind", "string")
                        .put("modifiers", "string[]").put("base_types", "string[]")
                        .put("interfaces", "string[]").put("docstring", "string")
                        .put("is_entrypoint", "boolean").put("_module", "string"))));

        n.add(node("JCallable", "JSymbol", "id",
                lines(new P().put("id", "string").put("name", "string").put("signature", "string")
                        .put("kind", "string").put("declaration", "string").put("return_type", "string")
                        .put("parameters_json", "string").put("modifiers", "string[]")
                        .put("error_channel", "string[]").put("code", "string").put("docstring", "string")
                        .put("cyclomatic_complexity", "integer")
                        .put("referenced_types", "string[]").put("accessed_fields", "string[]")
                        .put("is_implicit", "boolean").put("is_entrypoint", "boolean")
                        .put("_module", "string"))));

        n.add(node("JExternal", "JSymbol", "id",
                new P().put("id", "string").put("kind", "string").put("signature", "string")
                        .put("declaring_type", "string").done()));

        n.add(node("JField", "JField", "id",
                lines(new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("initializer", "string").put("modifiers", "string[]")
                        .put("docstring", "string").put("_module", "string"))));

        n.add(node("JVariable", "JVariable", "id",
                lines(new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("initializer", "string").put("_module", "string"))));

        n.add(node("JEnumConstant", "JEnumConstant", "id",
                new P().put("id", "string").put("name", "string").put("arguments", "string[]")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(node("JRecordComponent", "JRecordComponent", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("modifiers", "string[]").put("is_variadic", "boolean")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(node("JBodyNode", "JBodyNode", "id",
                lines(new P().put("id", "string").put("kind", "string").put("method_name", "string")
                        .put("receiver_expr", "string").put("receiver_type", "string")
                        .put("return_type", "string").put("accessibility", "string")
                        .put("is_constructor_call", "boolean").put("is_static_call", "boolean")
                        .put("argument_types", "string[]").put("argument_expr", "string[]")
                        .put("_module", "string")
                        // L4 SDG synthetic-vertex payload (python-parity names).
                        .put("var", "string").put("call_node", "string"))));

        n.add(node("JPackage", "JPackage", "name", new P().put("name", "string").done()));

        n.add(node("JAnnotation", "JAnnotation", "name", new P().put("name", "string").done()));

        return n;
    }

    private static RelType rel(String type, List<String> from, List<String> to, Map<String, String> props) {
        return new RelType(type, from, to, props);
    }

    private static List<RelType> buildRelTypes() {
        List<RelType> r = new ArrayList<>();
        Map<String, String> none = new LinkedHashMap<>();
        List<String> symbol = Arrays.asList("JCallable", "JExternal");
        List<String> body = Arrays.asList("JBodyNode");

        r.add(rel("J_HAS_MODULE", Arrays.asList("JApplication"), Arrays.asList("JModule"), none));
        r.add(rel("J_DECLARES", Arrays.asList("JModule", "JType", "JCallable"), Arrays.asList("JType"), none));
        r.add(rel("J_HAS_METHOD", Arrays.asList("JType"), Arrays.asList("JCallable"), none));
        r.add(rel("J_HAS_FIELD", Arrays.asList("JType"), Arrays.asList("JField"), none));
        r.add(rel("J_DECLARES_VAR", Arrays.asList("JCallable"), Arrays.asList("JVariable"), none));
        r.add(rel("J_HAS_ENUM_CONSTANT", Arrays.asList("JType"), Arrays.asList("JEnumConstant"), none));
        r.add(rel("J_HAS_RECORD_COMPONENT", Arrays.asList("JType"), Arrays.asList("JRecordComponent"), none));
        r.add(rel("J_HAS_BODY_NODE", Arrays.asList("JCallable"), body, none));
        r.add(rel("J_RESOLVES_TO", body, symbol, none));
        r.add(rel("J_CALLS", symbol, symbol,
                new P().put("weight", "integer").put("prov", "string[]").done()));
        r.add(rel("J_EXTENDS", Arrays.asList("JType"), Arrays.asList("JType"), none));
        r.add(rel("J_IMPLEMENTS", Arrays.asList("JType"), Arrays.asList("JType"), none));
        r.add(rel("J_IMPORTS", Arrays.asList("JModule"), Arrays.asList("JModule", "JPackage"),
                new P().put("spellings", "string[]").put("is_static", "boolean")
                        .put("is_wildcard", "boolean").done()));
        r.add(rel("J_ANNOTATED_BY", Arrays.asList("JType", "JCallable", "JField"),
                Arrays.asList("JAnnotation"), new P().put("arguments", "string[]").done()));
        // L3 CPG overlay. `_k` is the MERGE discriminant (internal, underscore-prefixed): J_CFG_NEXT
        // merges per `kind` (a conditional's true/false pair), J_DDG per `(var, prov)`.
        r.add(rel("J_CFG_NEXT", body, body, new P().put("kind", "string").put("_k", "string").done()));
        r.add(rel("J_CDG", body, body, none));
        r.add(rel("J_DDG", body, body,
                new P().put("var", "string").put("prov", "string[]").put("_k", "string").done()));
        // L4 SDG — reserved at 2.0.0, emitted from 2.1.0: J_PARAM_IN/J_PARAM_OUT from the
        // application-scope param_in/param_out edges, J_SUMMARY per callable.
        r.add(rel("J_PARAM_IN", body, body, new P().put("var", "string").done()));
        r.add(rel("J_PARAM_OUT", body, body, new P().put("var", "string").done()));
        r.add(rel("J_SUMMARY", body, body, none));

        return r;
    }

    /** One uniqueness constraint per distinct {@code (merge_label, key)}, deterministically named. */
    public static List<String> uniquenessConstraints() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (NodeLabel nl : NODE_LABELS) {
            seen.putIfAbsent(nl.mergeLabel, nl.key);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : seen.entrySet()) {
            out.add("CREATE CONSTRAINT " + e.getKey().toLowerCase() + "_" + e.getValue()
                    + " IF NOT EXISTS FOR (x:" + e.getKey() + ") REQUIRE x." + e.getValue() + " IS UNIQUE");
        }
        return out;
    }

    /** Build the machine-readable schema document emitted by {@code --emit schema}. */
    public static Map<String, Object> buildSchemaDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema_version", SCHEMA_VERSION);
        doc.put("generator", "codeanalyzer-java");
        doc.put("marker_labels", MARKER_LABELS);

        List<Map<String, Object>> nodeLabels = new ArrayList<>();
        for (NodeLabel nl : NODE_LABELS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", nl.label);
            m.put("merge_label", nl.mergeLabel);
            m.put("key", nl.key);
            m.put("properties", nl.properties);
            nodeLabels.add(m);
        }
        doc.put("node_labels", nodeLabels);

        List<Map<String, Object>> relTypes = new ArrayList<>();
        for (RelType rt : REL_TYPES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", rt.type);
            m.put("from", rt.from);
            m.put("to", rt.to);
            m.put("properties", rt.properties);
            relTypes.add(m);
        }
        doc.put("relationship_types", relTypes);

        doc.put("constraints", uniquenessConstraints());
        doc.put("indexes", Schema.INDEXES);
        return doc;
    }
}
