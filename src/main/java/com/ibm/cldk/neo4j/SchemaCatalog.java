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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declarative Neo4j schema catalog — the single in-repo source of truth for the graph contract
 * (node labels, their keys and typed properties, relationship types and their endpoints).
 * {@code --emit schema} serializes this (with the DDL from {@link Schema}) to a machine-readable
 * {@code schema.json}, and the conformance test asserts the real projector never produces a label /
 * relationship / property that isn't declared here — so this file cannot silently drift from
 * {@link GraphProjector}.
 *
 * <p>{@code SCHEMA_VERSION} is the contract version: bump MAJOR on a breaking change (renamed/removed
 * label, relationship or key), MINOR on an additive change (new label/rel/property). It is stamped
 * onto the {@code :Application} node of every emitted graph so any consumer can detect a
 * producer/consumer mismatch at runtime.
 */
public final class SchemaCatalog {

    private SchemaCatalog() {}

    public static final String SCHEMA_VERSION = "1.0.0";

    /** Labels layered onto a node in addition to its primary/specific label. */
    public static final List<String> MARKER_LABELS = Arrays.asList("Entrypoint");

    public static final class NodeLabel {
        public final String label;
        public final String mergeLabel;
        public final String key;
        public final Map<String, String> properties;

        NodeLabel(String label, String mergeLabel, String key, Map<String, String> properties) {
            this.label = label;
            this.mergeLabel = mergeLabel;
            this.key = key;
            this.properties = properties;
        }
    }

    public static final class RelType {
        public final String type;
        public final List<String> from;
        public final List<String> to;
        public final Map<String, String> properties;

        RelType(String type, List<String> from, List<String> to, Map<String, String> properties) {
            this.type = type;
            this.from = from;
            this.to = to;
            this.properties = properties;
        }
    }

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

    private static Map<String, String> span(P p) {
        return p.put("start_line", "integer").put("end_line", "integer").done();
    }

    public static final List<NodeLabel> NODE_LABELS = buildNodeLabels();
    public static final List<RelType> REL_TYPES = buildRelTypes();

    private static List<NodeLabel> buildNodeLabels() {
        List<NodeLabel> n = new ArrayList<>();

        n.add(new NodeLabel("Application", "Application", "name",
                new P().put("name", "string").put("schema_version", "string").done()));

        n.add(new NodeLabel("CompilationUnit", "CompilationUnit", "file_key",
                new P().put("file_key", "string").put("file_path", "string").put("package_name", "string")
                        .put("content_hash", "string").put("comment_count", "integer").put("_unit", "string").done()));

        n.add(new NodeLabel("Type", "Symbol", "id",
                new P().put("id", "string").put("name", "string").put("fqn", "string").put("kind", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]")
                        .put("extends_list", "string[]").put("implements_list", "string[]")
                        .put("nested_type_declarations", "string[]")
                        .put("is_interface", "boolean").put("is_nested_type", "boolean")
                        .put("is_inner_class", "boolean").put("is_local_class", "boolean")
                        .put("is_entrypoint_class", "boolean").put("parent_type", "string")
                        .put("docstring", "string").put("_unit", "string").done()));

        n.add(new NodeLabel("Callable", "Symbol", "id",
                new P().put("id", "string").put("name", "string").put("signature", "string")
                        .put("declaration", "string").put("return_type", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]")
                        .put("thrown_exceptions", "string[]").put("parameter_types", "string[]")
                        .put("referenced_types", "string[]").put("accessed_fields", "string[]")
                        .put("code", "string").put("code_start_line", "integer")
                        .put("start_line", "integer").put("end_line", "integer")
                        .put("cyclomatic_complexity", "integer")
                        .put("is_constructor", "boolean").put("is_implicit", "boolean")
                        .put("is_entrypoint", "boolean").put("docstring", "string").put("_unit", "string").done()));

        n.add(new NodeLabel("Field", "Field", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]").put("variables", "string[]")
                        .put("start_line", "integer").put("end_line", "integer")
                        .put("docstring", "string").put("_unit", "string").done()));

        n.add(new NodeLabel("Parameter", "Parameter", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("annotations", "string[]").put("modifiers", "string[]")
                        .put("start_line", "integer").put("end_line", "integer").put("_unit", "string").done()));

        n.add(new NodeLabel("Variable", "Variable", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("initializer", "string").put("start_line", "integer").put("end_line", "integer")
                        .put("_unit", "string").done()));

        n.add(new NodeLabel("CallSite", "CallSite", "id",
                new P().put("id", "string").put("method_name", "string").put("receiver_expr", "string")
                        .put("receiver_type", "string").put("return_type", "string")
                        .put("callee_signature", "string").put("argument_types", "string[]")
                        .put("is_static_call", "boolean").put("is_constructor_call", "boolean")
                        .put("is_public", "boolean").put("is_private", "boolean").put("is_protected", "boolean")
                        .put("start_line", "integer").put("start_column", "integer")
                        .put("end_line", "integer").put("end_column", "integer").put("_unit", "string").done()));

        n.add(new NodeLabel("EnumConstant", "EnumConstant", "id",
                new P().put("id", "string").put("name", "string").put("arguments", "string[]")
                        .put("_unit", "string").done()));

        n.add(new NodeLabel("RecordComponent", "RecordComponent", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]")
                        .put("is_var_args", "boolean").put("_unit", "string").done()));

        n.add(new NodeLabel("Package", "Package", "name", new P().put("name", "string").done()));

        n.add(new NodeLabel("Annotation", "Annotation", "name", new P().put("name", "string").done()));

        return n;
    }

    private static List<RelType> buildRelTypes() {
        List<RelType> r = new ArrayList<>();
        Map<String, String> none = new LinkedHashMap<>();

        r.add(new RelType("HAS_UNIT", Arrays.asList("Application"), Arrays.asList("CompilationUnit"), none));
        r.add(new RelType("DECLARES_TYPE", Arrays.asList("CompilationUnit"), Arrays.asList("Type"), none));
        r.add(new RelType("HAS_NESTED_TYPE", Arrays.asList("Type"), Arrays.asList("Type"), none));
        r.add(new RelType("HAS_CALLABLE", Arrays.asList("Type"), Arrays.asList("Callable"), none));
        r.add(new RelType("HAS_FIELD", Arrays.asList("Type"), Arrays.asList("Field"), none));
        r.add(new RelType("HAS_PARAMETER", Arrays.asList("Callable"), Arrays.asList("Parameter"), none));
        r.add(new RelType("HAS_CALLSITE", Arrays.asList("Callable"), Arrays.asList("CallSite"), none));
        r.add(new RelType("DECLARES_VAR", Arrays.asList("Callable"), Arrays.asList("Variable"), none));
        r.add(new RelType("HAS_ENUM_CONSTANT", Arrays.asList("Type"), Arrays.asList("EnumConstant"), none));
        r.add(new RelType("HAS_RECORD_COMPONENT", Arrays.asList("Type"), Arrays.asList("RecordComponent"), none));
        r.add(new RelType("EXTENDS", Arrays.asList("Type"), Arrays.asList("Type"), none));
        r.add(new RelType("IMPLEMENTS", Arrays.asList("Type"), Arrays.asList("Type"), none));
        r.add(new RelType("ANNOTATED_BY", Arrays.asList("Type", "Callable", "Field"), Arrays.asList("Annotation"), none));
        r.add(new RelType("IMPORTS", Arrays.asList("CompilationUnit"), Arrays.asList("Type", "Package"),
                new P().put("is_static", "boolean").put("is_wildcard", "boolean").done()));
        r.add(new RelType("RESOLVES_TO", Arrays.asList("CallSite"), Arrays.asList("Callable"), none));
        r.add(new RelType("CALLS", Arrays.asList("Callable"), Arrays.asList("Callable"),
                new P().put("type", "string").put("weight", "integer").done()));

        return r;
    }

    /** Build the full machine-readable schema document emitted by {@code --emit schema}. */
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

        doc.put("constraints", Schema.CONSTRAINTS);
        doc.put("indexes", Schema.INDEXES);
        return doc;
    }
}
