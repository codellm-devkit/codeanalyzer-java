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
 * onto the {@code :JApplication} node of every emitted graph so any consumer can detect a
 * producer/consumer mismatch at runtime.
 */
public final class SchemaCatalog {

    private SchemaCatalog() {}

    public static final String SCHEMA_VERSION = "1.0.0";

    /** Labels layered onto a node in addition to its primary/specific label. */
    public static final List<String> MARKER_LABELS = Arrays.asList("JEntrypoint");

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

        n.add(new NodeLabel("JApplication", "JApplication", "name",
                new P().put("name", "string").put("schema_version", "string").done()));

        n.add(new NodeLabel("JCompilationUnit", "JCompilationUnit", "file_key",
                new P().put("file_key", "string").put("file_path", "string").put("package_name", "string")
                        .put("content_hash", "string").put("comment_count", "integer").put("is_modified", "boolean")
                        .put("_module", "string").done()));

        n.add(new NodeLabel("JType", "JSymbol", "id",
                new P().put("id", "string").put("name", "string").put("fqn", "string").put("kind", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]")
                        .put("extends_list", "string[]").put("implements_list", "string[]")
                        .put("nested_type_declarations", "string[]")
                        .put("is_interface", "boolean").put("is_nested_type", "boolean")
                        .put("is_inner_class", "boolean").put("is_local_class", "boolean")
                        .put("is_entrypoint_class", "boolean").put("parent_type", "string")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JCallable", "JSymbol", "id",
                new P().put("id", "string").put("name", "string").put("signature", "string")
                        .put("file_path", "string")
                        .put("declaration", "string").put("return_type", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]")
                        .put("thrown_exceptions", "string[]").put("parameter_types", "string[]")
                        .put("referenced_types", "string[]").put("accessed_fields", "string[]")
                        .put("code", "string").put("code_start_line", "integer")
                        .put("start_line", "integer").put("end_line", "integer")
                        .put("cyclomatic_complexity", "integer")
                        .put("is_constructor", "boolean").put("is_implicit", "boolean")
                        .put("is_entrypoint", "boolean").put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JField", "JField", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]").put("variables", "string[]")
                        .put("variable_initializers_json", "string")
                        .put("start_line", "integer").put("end_line", "integer")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JParameter", "JParameter", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("annotations", "string[]").put("modifiers", "string[]")
                        .put("start_line", "integer").put("end_line", "integer")
                        .put("start_column", "integer").put("end_column", "integer")
                        .put("_module", "string").done()));

        n.add(new NodeLabel("JVariable", "JVariable", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("initializer", "string").put("start_line", "integer").put("end_line", "integer")
                        .put("start_column", "integer").put("end_column", "integer")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JCallSite", "JCallSite", "id",
                new P().put("id", "string").put("method_name", "string").put("receiver_expr", "string")
                        .put("receiver_type", "string").put("return_type", "string")
                        .put("callee_signature", "string").put("argument_types", "string[]")
                        .put("argument_expr", "string[]")
                        .put("is_static_call", "boolean").put("is_constructor_call", "boolean")
                        .put("is_public", "boolean").put("is_private", "boolean").put("is_protected", "boolean")
                        .put("is_unspecified", "boolean")
                        .put("start_line", "integer").put("start_column", "integer")
                        .put("end_line", "integer").put("end_column", "integer")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JEnumConstant", "JEnumConstant", "id",
                new P().put("id", "string").put("name", "string").put("arguments", "string[]")
                        .put("_module", "string").done()));

        n.add(new NodeLabel("JRecordComponent", "JRecordComponent", "id",
                new P().put("id", "string").put("name", "string").put("type", "string")
                        .put("modifiers", "string[]").put("annotations", "string[]")
                        .put("default_value", "string").put("is_var_args", "boolean")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JInitializationBlock", "JInitializationBlock", "id",
                new P().put("id", "string").put("file_path", "string").put("code", "string")
                        .put("annotations", "string[]").put("thrown_exceptions", "string[]")
                        .put("referenced_types", "string[]").put("accessed_fields", "string[]")
                        .put("is_static", "boolean").put("cyclomatic_complexity", "integer")
                        .put("start_line", "integer").put("end_line", "integer")
                        .put("docstring", "string").put("_module", "string").done()));

        n.add(new NodeLabel("JCrudOperation", "JCrudOperation", "id",
                new P().put("id", "string").put("line_number", "integer").put("operation_type", "string")
                        .put("target_table", "string").put("involved_columns", "string[]")
                        .put("condition", "string").put("joined_tables", "string[]")
                        .put("_module", "string").done()));

        n.add(new NodeLabel("JCrudQuery", "JCrudQuery", "id",
                new P().put("id", "string").put("line_number", "integer").put("query_type", "string")
                        .put("query_arguments", "string[]").put("_module", "string").done()));

        n.add(new NodeLabel("JComment", "JComment", "id",
                new P().put("id", "string").put("content", "string").put("is_javadoc", "boolean")
                        .put("start_line", "integer").put("start_column", "integer")
                        .put("end_line", "integer").put("end_column", "integer").put("_module", "string").done()));

        n.add(new NodeLabel("JPackage", "JPackage", "name", new P().put("name", "string").done()));

        n.add(new NodeLabel("JAnnotation", "JAnnotation", "name", new P().put("name", "string").done()));

        return n;
    }

    private static List<RelType> buildRelTypes() {
        List<RelType> r = new ArrayList<>();
        Map<String, String> none = new LinkedHashMap<>();

        r.add(new RelType("J_HAS_UNIT", Arrays.asList("JApplication"), Arrays.asList("JCompilationUnit"), none));
        r.add(new RelType("J_DECLARES_TYPE", Arrays.asList("JCompilationUnit"), Arrays.asList("JType"), none));
        r.add(new RelType("J_HAS_NESTED_TYPE", Arrays.asList("JType"), Arrays.asList("JType"), none));
        r.add(new RelType("J_HAS_CALLABLE", Arrays.asList("JType"), Arrays.asList("JCallable"), none));
        r.add(new RelType("J_HAS_FIELD", Arrays.asList("JType"), Arrays.asList("JField"), none));
        r.add(new RelType("J_HAS_PARAMETER", Arrays.asList("JCallable"), Arrays.asList("JParameter"), none));
        r.add(new RelType("J_HAS_CALLSITE", Arrays.asList("JCallable", "JInitializationBlock"),
                Arrays.asList("JCallSite"), none));
        r.add(new RelType("J_DECLARES_VAR", Arrays.asList("JCallable", "JInitializationBlock"),
                Arrays.asList("JVariable"), none));
        r.add(new RelType("J_HAS_ENUM_CONSTANT", Arrays.asList("JType"), Arrays.asList("JEnumConstant"), none));
        r.add(new RelType("J_HAS_RECORD_COMPONENT", Arrays.asList("JType"), Arrays.asList("JRecordComponent"), none));
        r.add(new RelType("J_HAS_INIT_BLOCK", Arrays.asList("JType"), Arrays.asList("JInitializationBlock"), none));
        r.add(new RelType("J_EXTENDS", Arrays.asList("JType"), Arrays.asList("JType"), none));
        r.add(new RelType("J_IMPLEMENTS", Arrays.asList("JType"), Arrays.asList("JType"), none));
        r.add(new RelType("J_ANNOTATED_BY", Arrays.asList("JType", "JCallable", "JField"), Arrays.asList("JAnnotation"), none));
        r.add(new RelType("J_IMPORTS", Arrays.asList("JCompilationUnit"), Arrays.asList("JType", "JPackage"),
                new P().put("is_static", "boolean").put("is_wildcard", "boolean").done()));
        r.add(new RelType("J_RESOLVES_TO", Arrays.asList("JCallSite"), Arrays.asList("JCallable"), none));
        r.add(new RelType("J_CALLS", Arrays.asList("JCallable"), Arrays.asList("JCallable"),
                new P().put("type", "string").put("weight", "integer")
                        .put("source_kind", "string").put("destination_kind", "string").done()));
        r.add(new RelType("J_HAS_CRUD_OPERATION", Arrays.asList("JCallable", "JCallSite"),
                Arrays.asList("JCrudOperation"), none));
        r.add(new RelType("J_HAS_CRUD_QUERY", Arrays.asList("JCallable", "JCallSite"),
                Arrays.asList("JCrudQuery"), none));
        r.add(new RelType("J_HAS_COMMENT",
                Arrays.asList("JCompilationUnit", "JType", "JCallable", "JField", "JCallSite", "JVariable",
                        "JRecordComponent", "JInitializationBlock"),
                Arrays.asList("JComment"), none));

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
