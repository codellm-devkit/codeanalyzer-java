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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.cldk.entities.CRUDOperation;
import com.ibm.cldk.entities.CRUDQuery;
import com.ibm.cldk.entities.CallSite;
import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.Comment;
import com.ibm.cldk.entities.EnumConstant;
import com.ibm.cldk.entities.Field;
import com.ibm.cldk.entities.Import;
import com.ibm.cldk.entities.InitializationBlock;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.entities.ParameterInCallable;
import com.ibm.cldk.entities.RecordComponent;
import com.ibm.cldk.entities.Type;
import com.ibm.cldk.entities.VariableDeclaration;
import com.ibm.cldk.neo4j.GraphRows.NodeRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code project()} — the pure projection from the canonical codeanalyzer symbol table (and the
 * level-2 call graph) to graph rows. It walks the same containment tree the SDG builder walks, but
 * instead of collecting call edges it emits nodes + edges. No I/O: the writers
 * ({@link CypherWriter} snapshot / {@link BoltWriter} incremental) consume the returned
 * {@link GraphRows}.
 *
 * <p>Modelling decisions (mirrors the codeanalyzer-typescript Neo4j projection; all node labels are
 * {@code J}-prefixed and relationship types {@code J_}-prefixed so a Java graph can share a Neo4j
 * database with the Python/TypeScript backends without colliding):
 * <ul>
 *   <li>{@code JType} and {@code JCallable} carry a shared {@code :JSymbol} label (the global-identity
 *       / MERGE key); their {@code id} is the FQN (types) or {@code <fqn>#<signature>} (callables).</li>
 *   <li>call sites, fields, parameters, variables, enum constants, record components, initialization
 *       blocks, CRUD operations/queries and comments are all first-class nodes (the graph is a
 *       lossless projection of the IR); annotations are shared {@code :JAnnotation} nodes (like TS
 *       decorators) in addition to the {@code annotations} string array on each owner.</li>
 *   <li>entrypoints are a marker label ({@code :JEntrypoint}) on the owning callable/type.</li>
 *   <li>every project-owned node carries an internal {@code _module} provenance prop, so the
 *       incremental writer can delete exactly what a re-analyzed compilation unit previously emitted.</li>
 * </ul>
 */
public final class GraphProjector {

    private static final Gson GSON = new Gson();

    private GraphProjector() {}

    /**
     * @param symbolTable file path → {@link JavaCompilationUnit} (the {@code symbol_table} map).
     * @param callGraph   the {@code call_graph} array (level 2), or {@code null} at level 1.
     * @param appName     logical application name for the {@code :JApplication} anchor.
     */
    public static GraphRows project(Map<String, JavaCompilationUnit> symbolTable, JsonArray callGraph, String appName) {
        RowBuilder b = new RowBuilder();

        NodeRef app = b.node(Collections.singletonList("JApplication"), "name", appName,
                map("schema_version", SchemaCatalog.SCHEMA_VERSION, "name", appName));

        for (Map.Entry<String, JavaCompilationUnit> e : symbolTable.entrySet()) {
            String fileKey = e.getKey();
            JavaCompilationUnit cu = e.getValue();
            NodeRef cuRef = b.node(Collections.singletonList("JCompilationUnit"), "file_key", fileKey,
                    compilationUnitProps(cu, fileKey));
            b.edge("J_HAS_UNIT", app, cuRef);
            projectCompilationUnit(b, fileKey, cuRef, cu);
        }

        if (callGraph != null) {
            projectCallGraph(b, callGraph);
        }

        return b.finish();
    }

    // ----------------------------------------------------------------------------------------------
    // Compilation unit body
    // ----------------------------------------------------------------------------------------------

    private static void projectCompilationUnit(RowBuilder b, String fileKey, NodeRef cuRef, JavaCompilationUnit cu) {
        Map<String, Type> types = cu.getTypeDeclarations();
        if (types == null) {
            types = Collections.emptyMap();
        }
        Set<String> typeKeys = types.keySet();

        // Per-type nodes; nested types hang off their parent (HAS_NESTED_TYPE), top-level off the unit.
        Map<String, NodeRef> typeRefs = new java.util.HashMap<>();
        for (Map.Entry<String, Type> te : types.entrySet()) {
            typeRefs.put(te.getKey(), b.node(symbolLabels("JType", te.getValue().isEntrypointClass()), "id",
                    te.getKey(), typeProps(te.getValue(), te.getKey(), fileKey)));
        }

        for (Map.Entry<String, Type> te : types.entrySet()) {
            String fqn = te.getKey();
            Type type = te.getValue();
            NodeRef typeRef = typeRefs.get(fqn);

            String parent = type.getParentType();
            if (parent != null && typeKeys.contains(parent)) {
                b.edge("J_HAS_NESTED_TYPE", typeRefs.get(parent), typeRef);
            } else {
                b.edge("J_DECLARES_TYPE", cuRef, typeRef);
            }

            projectTypeBody(b, fileKey, fqn, typeRef, type);
        }

        // Imports: resolve to a known Type (gated) or to a Package node.
        if (cu.getImports() != null) {
            for (Import im : cu.getImports()) {
                projectImport(b, cuRef, im, typeKeys);
            }
        }

        projectComments(b, cuRef, cu.getComments(), fileKey);
    }

    private static void projectImport(RowBuilder b, NodeRef cuRef, Import im, Set<String> typeKeys) {
        String path = im.getPath();
        if (path == null || path.isEmpty()) {
            return;
        }
        Map<String, Object> props = map("is_static", im.isStatic(), "is_wildcard", im.isWildcard());
        if (!im.isWildcard() && typeKeys.contains(path)) {
            b.edgeToSymbol("J_IMPORTS", cuRef, path, props);
            return;
        }
        // Otherwise model the imported package: the path's package portion (strip the trailing class).
        String pkg = im.isWildcard() ? path : packageOf(path);
        if (pkg != null && !pkg.isEmpty()) {
            NodeRef pkgRef = b.node(Collections.singletonList("JPackage"), "name", pkg, map("name", pkg));
            b.edge("J_IMPORTS", cuRef, pkgRef, props);
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Type body
    // ----------------------------------------------------------------------------------------------

    private static void projectTypeBody(RowBuilder b, String fileKey, String fqn, NodeRef typeRef, Type type) {
        for (String s : safe(type.getAnnotations())) {
            annotate(b, typeRef, s);
        }
        for (String s : safe(type.getExtendsList())) {
            b.edgeToSymbol("J_EXTENDS", typeRef, s);
        }
        for (String s : safe(type.getImplementsList())) {
            b.edgeToSymbol("J_IMPLEMENTS", typeRef, s);
        }

        if (type.getCallableDeclarations() != null) {
            for (Map.Entry<String, Callable> ce : type.getCallableDeclarations().entrySet()) {
                projectCallable(b, fileKey, fqn, typeRef, ce.getValue(), ce.getKey());
            }
        }
        for (Field f : safe(type.getFieldDeclarations())) {
            projectField(b, fileKey, fqn, typeRef, f);
        }
        for (EnumConstant ec : safe(type.getEnumConstants())) {
            projectEnumConstant(b, fileKey, fqn, typeRef, ec);
        }
        for (RecordComponent rc : safe(type.getRecordComponents())) {
            projectRecordComponent(b, fileKey, fqn, typeRef, rc);
        }
        List<InitializationBlock> initBlocks = type.getInitializationBlocks();
        if (initBlocks != null) {
            for (int i = 0; i < initBlocks.size(); i++) {
                projectInitializationBlock(b, fileKey, fqn, typeRef, initBlocks.get(i), i);
            }
        }
        projectComments(b, typeRef, type.getComments(), fileKey);
    }

    private static void projectCallable(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            Callable c, String mapKey) {
        String signature = c.getSignature() != null ? c.getSignature() : mapKey;
        String id = ownerFqn + "#" + signature;
        NodeRef ref = b.node(symbolLabels("JCallable", c.isEntrypoint()), "id", id, callableProps(c, id, signature, fileKey));
        b.edge("J_HAS_CALLABLE", owner, ref);

        for (String s : safe(c.getAnnotations())) {
            annotate(b, ref, s);
        }

        List<ParameterInCallable> params = c.getParameters();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                projectParameter(b, fileKey, id, ref, params.get(i), i);
            }
        }
        for (CallSite cs : safe(c.getCallSites())) {
            projectCallSite(b, fileKey, id, ref, cs);
        }
        for (VariableDeclaration v : safe(c.getVariableDeclarations())) {
            projectVariable(b, fileKey, id, ref, v);
        }
        List<CRUDOperation> crudOps = c.getCrudOperations();
        if (crudOps != null) {
            for (int i = 0; i < crudOps.size(); i++) {
                projectCrudOperation(b, fileKey, id, ref, crudOps.get(i), i);
            }
        }
        List<CRUDQuery> crudQueries = c.getCrudQueries();
        if (crudQueries != null) {
            for (int i = 0; i < crudQueries.size(); i++) {
                projectCrudQuery(b, fileKey, id, ref, crudQueries.get(i), i);
            }
        }
        projectComments(b, ref, c.getComments(), fileKey);
    }

    private static void projectParameter(RowBuilder b, String fileKey, String callableId, NodeRef owner,
            ParameterInCallable p, int index) {
        String id = callableId + "#param#" + index;
        NodeRef ref = b.node(Collections.singletonList("JParameter"), "id", id, RowBuilder.prune(
                map("id", id, "name", p.getName(), "type", p.getType(),
                        "annotations", strList(p.getAnnotations()), "modifiers", strList(p.getModifiers()),
                        "start_line", asLong(p.getStartLine()), "end_line", asLong(p.getEndLine()),
                        "start_column", asLong(p.getStartColumn()), "end_column", asLong(p.getEndColumn()),
                        "_module", fileKey)));
        b.edge("J_HAS_PARAMETER", owner, ref);
    }

    private static void projectCallSite(RowBuilder b, String fileKey, String callableId, NodeRef owner, CallSite s) {
        String id = callableId + "@" + s.getStartLine() + ":" + s.getStartColumn()
                + "-" + s.getEndLine() + ":" + s.getEndColumn();
        NodeRef ref = b.node(Collections.singletonList("JCallSite"), "id", id, RowBuilder.prune(
                map("id", id, "method_name", s.getMethodName(), "receiver_expr", s.getReceiverExpr(),
                        "receiver_type", s.getReceiverType(), "return_type", s.getReturnType(),
                        "callee_signature", s.getCalleeSignature(), "argument_types", strList(s.getArgumentTypes()),
                        "argument_expr", strList(s.getArgumentExpr()),
                        "is_static_call", s.isStaticCall(), "is_constructor_call", s.isConstructorCall(),
                        "is_public", s.isPublic(), "is_private", s.isPrivate(), "is_protected", s.isProtected(),
                        "is_unspecified", s.isUnspecified(),
                        "start_line", asLong(s.getStartLine()), "start_column", asLong(s.getStartColumn()),
                        "end_line", asLong(s.getEndLine()), "end_column", asLong(s.getEndColumn()),
                        "docstring", docstringOf(s.getComment()), "_module", fileKey)));
        b.edge("J_HAS_CALLSITE", owner, ref);
        if (s.getCalleeSignature() != null && !s.getCalleeSignature().isEmpty()) {
            // Gated: kept only if the callee was emitted as a JCallable node.
            b.edgeToSymbol("J_RESOLVES_TO", ref, s.getCalleeSignature());
        }
        projectComment(b, ref, s.getComment(), fileKey);
        projectCrudOperation(b, fileKey, id, ref, s.getCrudOperation(), 0);
        projectCrudQuery(b, fileKey, id, ref, s.getCrudQuery(), 0);
    }

    private static void projectVariable(RowBuilder b, String fileKey, String callableId, NodeRef owner,
            VariableDeclaration v) {
        String id = callableId + "#var#" + v.getName() + "@" + v.getStartLine();
        NodeRef ref = b.node(Collections.singletonList("JVariable"), "id", id, RowBuilder.prune(
                map("id", id, "name", v.getName(), "type", v.getType(), "initializer", v.getInitializer(),
                        "start_line", asLong(v.getStartLine()), "end_line", asLong(v.getEndLine()),
                        "start_column", asLong(v.getStartColumn()), "end_column", asLong(v.getEndColumn()),
                        "docstring", docstringOf(v.getComment()), "_module", fileKey)));
        b.edge("J_DECLARES_VAR", owner, ref);
        projectComment(b, ref, v.getComment(), fileKey);
    }

    private static void projectField(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner, Field f) {
        String id = ownerFqn + "#field#" + f.getName();
        NodeRef ref = b.node(Collections.singletonList("JField"), "id", id, RowBuilder.prune(
                map("id", id, "name", f.getName(), "type", f.getType(),
                        "modifiers", strList(f.getModifiers()), "annotations", strList(f.getAnnotations()),
                        "variables", strList(f.getVariables()),
                        "variable_initializers_json", variableInitializersJson(f.getVariableInitializers()),
                        "start_line", asLong(f.getStartLine()), "end_line", asLong(f.getEndLine()),
                        "docstring", docstringOf(f.getComment()), "_module", fileKey)));
        b.edge("J_HAS_FIELD", owner, ref);
        for (String s : safe(f.getAnnotations())) {
            annotate(b, ref, s);
        }
        projectComment(b, ref, f.getComment(), fileKey);
    }

    private static void projectEnumConstant(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            EnumConstant ec) {
        String id = ownerFqn + "#enum#" + ec.getName();
        NodeRef ref = b.node(Collections.singletonList("JEnumConstant"), "id", id, RowBuilder.prune(
                map("id", id, "name", ec.getName(), "arguments", strList(ec.getArguments()), "_module", fileKey)));
        b.edge("J_HAS_ENUM_CONSTANT", owner, ref);
    }

    private static void projectRecordComponent(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            RecordComponent rc) {
        String id = ownerFqn + "#rec#" + rc.getName();
        NodeRef ref = b.node(Collections.singletonList("JRecordComponent"), "id", id, RowBuilder.prune(
                map("id", id, "name", rc.getName(), "type", rc.getType(),
                        "modifiers", strList(rc.getModifiers()), "annotations", strList(rc.getAnnotations()),
                        "default_value", rc.getDefaultValue() == null ? null : String.valueOf(rc.getDefaultValue()),
                        "is_var_args", rc.isVarArgs(),
                        "docstring", docstringOf(rc.getComment()), "_module", fileKey)));
        b.edge("J_HAS_RECORD_COMPONENT", owner, ref);
        projectComment(b, ref, rc.getComment(), fileKey);
    }

    private static void annotate(RowBuilder b, NodeRef on, String annotation) {
        if (annotation == null || annotation.isEmpty()) {
            return;
        }
        NodeRef ann = b.node(Collections.singletonList("JAnnotation"), "name", annotation, map("name", annotation));
        b.edge("J_ANNOTATED_BY", on, ann);
    }

    // ----------------------------------------------------------------------------------------------
    // Initialization blocks, CRUD, comments (first-class nodes — full IR fidelity)
    // ----------------------------------------------------------------------------------------------

    private static void projectInitializationBlock(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            InitializationBlock ib, int index) {
        if (ib == null) {
            return;
        }
        String id = ownerFqn + "#init#" + index;
        NodeRef ref = b.node(Collections.singletonList("JInitializationBlock"), "id", id, RowBuilder.prune(
                map("id", id, "file_path", ib.getFilePath(), "code", ib.getCode(),
                        "annotations", strList(ib.getAnnotations()), "thrown_exceptions", strList(ib.getThrownExceptions()),
                        "referenced_types", strList(ib.getReferencedTypes()), "accessed_fields", strList(ib.getAccessedFields()),
                        "is_static", ib.isStatic(), "cyclomatic_complexity", asLong(ib.getCyclomaticComplexity()),
                        "start_line", asLong(ib.getStartLine()), "end_line", asLong(ib.getEndLine()),
                        "docstring", docstringOf(ib.getComments()), "_module", fileKey)));
        b.edge("J_HAS_INIT_BLOCK", owner, ref);
        projectComments(b, ref, ib.getComments(), fileKey);
        for (CallSite cs : safe(ib.getCallSites())) {
            projectCallSite(b, fileKey, id, ref, cs);
        }
        for (VariableDeclaration v : safe(ib.getVariableDeclarations())) {
            projectVariable(b, fileKey, id, ref, v);
        }
    }

    private static void projectCrudOperation(RowBuilder b, String fileKey, String ownerId, NodeRef owner,
            CRUDOperation op, int index) {
        if (op == null) {
            return;
        }
        String id = ownerId + "#crudop#" + index;
        NodeRef ref = b.node(Collections.singletonList("JCrudOperation"), "id", id, RowBuilder.prune(
                map("id", id, "line_number", asLong(op.getLineNumber()),
                        "operation_type", enumName(op.getOperationType()),
                        "target_table", op.getTargetTable(), "involved_columns", strList(op.getInvolvedColumns()),
                        "condition", op.getCondition(), "joined_tables", strList(op.getJoinedTables()),
                        "_module", fileKey)));
        b.edge("J_HAS_CRUD_OPERATION", owner, ref);
    }

    private static void projectCrudQuery(RowBuilder b, String fileKey, String ownerId, NodeRef owner,
            CRUDQuery q, int index) {
        if (q == null) {
            return;
        }
        String id = ownerId + "#crudq#" + index;
        NodeRef ref = b.node(Collections.singletonList("JCrudQuery"), "id", id, RowBuilder.prune(
                map("id", id, "line_number", asLong(q.getLineNumber()),
                        "query_type", enumName(q.getQueryType()),
                        "query_arguments", strList(q.getQueryArguments()), "_module", fileKey)));
        b.edge("J_HAS_CRUD_QUERY", owner, ref);
    }

    private static void projectComments(RowBuilder b, NodeRef owner, List<Comment> comments, String fileKey) {
        for (Comment c : safe(comments)) {
            projectComment(b, owner, c, fileKey);
        }
    }

    private static void projectComment(RowBuilder b, NodeRef owner, Comment c, String fileKey) {
        if (c == null || c.getContent() == null) {
            return;
        }
        String id = owner.value + "#comment@" + c.getStartLine() + ":" + c.getStartColumn()
                + "-" + c.getEndLine() + ":" + c.getEndColumn();
        NodeRef ref = b.node(Collections.singletonList("JComment"), "id", id, RowBuilder.prune(
                map("id", id, "content", c.getContent(), "is_javadoc", c.isJavadoc(),
                        "start_line", asLong(c.getStartLine()), "start_column", asLong(c.getStartColumn()),
                        "end_line", asLong(c.getEndLine()), "end_column", asLong(c.getEndColumn()),
                        "_module", fileKey)));
        b.edge("J_HAS_COMMENT", owner, ref);
    }

    // ----------------------------------------------------------------------------------------------
    // Call graph (level 2)
    // ----------------------------------------------------------------------------------------------

    private static void projectCallGraph(RowBuilder b, JsonArray callGraph) {
        for (JsonElement el : callGraph) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject edge = el.getAsJsonObject();
            String from = vertexId(edge.getAsJsonObject("source"));
            String to = vertexId(edge.getAsJsonObject("target"));
            if (from == null || to == null) {
                continue;
            }
            Map<String, Object> props = RowBuilder.prune(map(
                    "type", str(edge, "type"),
                    "weight", asLong(parseIntOrNull(str(edge, "weight"))),
                    "source_kind", str(edge, "source_kind"),
                    "destination_kind", str(edge, "destination_kind")));
            // Gated against the symbol table: kept only if both callables were emitted as nodes.
            b.edgeIfBothResolved("J_CALLS",
                    new NodeRef("JSymbol", "id", from), new NodeRef("JSymbol", "id", to), props);
        }
    }

    private static String vertexId(JsonObject vertex) {
        if (vertex == null) {
            return null;
        }
        String typeDecl = str(vertex, "type_declaration");
        String signature = str(vertex, "signature");
        if (typeDecl == null || signature == null) {
            return null;
        }
        return typeDecl + "#" + signature;
    }

    // ----------------------------------------------------------------------------------------------
    // Property flattening
    // ----------------------------------------------------------------------------------------------

    private static Map<String, Object> compilationUnitProps(JavaCompilationUnit cu, String fileKey) {
        int commentCount = cu.getComments() == null ? 0 : cu.getComments().size();
        return RowBuilder.prune(map(
                "file_key", fileKey,
                "file_path", cu.getFilePath(),
                "package_name", cu.getPackageName(),
                "content_hash", contentHash(cu),
                "comment_count", (long) commentCount,
                "is_modified", cu.isModified(),
                "_module", fileKey));
    }

    private static Map<String, Object> typeProps(Type t, String fqn, String fileKey) {
        Map<String, Object> p = map(
                "id", fqn,
                "name", simpleName(fqn),
                "fqn", fqn,
                "kind", typeKind(t),
                "modifiers", strList(t.getModifiers()),
                "annotations", strList(t.getAnnotations()),
                "extends_list", strList(t.getExtendsList()),
                "implements_list", strList(t.getImplementsList()),
                "nested_type_declarations", strList(t.getNestedTypeDeclarations()),
                "is_interface", t.isInterface(),
                "is_nested_type", t.isNestedType(),
                "is_inner_class", t.isInnerClass(),
                "is_local_class", t.isLocalClass(),
                "is_entrypoint_class", t.isEntrypointClass(),
                "parent_type", t.getParentType(),
                "docstring", docstringOf(t.getComments()),
                "_module", fileKey);
        return RowBuilder.prune(p);
    }

    private static Map<String, Object> callableProps(Callable c, String id, String signature, String fileKey) {
        Map<String, Object> p = map(
                "id", id,
                "name", callableName(signature),
                "signature", signature,
                "declaration", c.getDeclaration(),
                "return_type", c.getReturnType(),
                "modifiers", strList(c.getModifiers()),
                "annotations", strList(c.getAnnotations()),
                "thrown_exceptions", strList(c.getThrownExceptions()),
                "parameter_types", parameterTypes(c),
                "referenced_types", strList(c.getReferencedTypes()),
                "accessed_fields", strList(c.getAccessedFields()),
                "code", c.getCode(),
                "code_start_line", asLong(c.getCodeStartLine()),
                "start_line", asLong(c.getStartLine()),
                "end_line", asLong(c.getEndLine()),
                "cyclomatic_complexity", asLong(c.getCyclomaticComplexity()),
                "is_constructor", c.isConstructor(),
                "is_implicit", c.isImplicit(),
                "is_entrypoint", c.isEntrypoint(),
                "docstring", docstringOf(c.getComments()),
                "_module", fileKey);
        return RowBuilder.prune(p);
    }

    // ----------------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------------

    private static List<String> symbolLabels(String specific, boolean entrypoint) {
        if (entrypoint) {
            return Arrays.asList("JSymbol", specific, "JEntrypoint");
        }
        return Arrays.asList("JSymbol", specific);
    }

    private static String typeKind(Type t) {
        if (t.isInterface()) {
            return "interface";
        }
        if (t.isEnumDeclaration()) {
            return "enum";
        }
        if (t.isAnnotationDeclaration()) {
            return "annotation";
        }
        if (t.isRecordDeclaration()) {
            return "record";
        }
        return "class";
    }

    private static List<String> parameterTypes(Callable c) {
        if (c.getParameters() == null) {
            return null;
        }
        return c.getParameters().stream().map(ParameterInCallable::getType).collect(Collectors.toList());
    }

    private static String docstringOf(List<Comment> comments) {
        if (comments == null) {
            return null;
        }
        String joined = comments.stream().filter(Comment::isJavadoc).map(Comment::getContent)
                .collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    private static String docstringOf(Comment comment) {
        if (comment == null || !comment.isJavadoc()) {
            return null;
        }
        return comment.getContent();
    }

    private static String simpleName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private static String callableName(String signature) {
        int paren = signature.indexOf('(');
        String head = paren < 0 ? signature : signature.substring(0, paren);
        int dot = head.lastIndexOf('.');
        return dot < 0 ? head : head.substring(dot + 1);
    }

    private static String packageOf(String fqImport) {
        int i = fqImport.lastIndexOf('.');
        return i < 0 ? "" : fqImport.substring(0, i);
    }

    private static String contentHash(JavaCompilationUnit cu) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(GSON.toJson(cu).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte x : digest) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        return new ArrayList<>(in);
    }

    private static <T> List<T> safe(List<T> in) {
        return in == null ? Collections.emptyList() : in;
    }

    private static Long asLong(Integer i) {
        return i == null ? null : i.longValue();
    }

    private static Long asLong(int i) {
        return (long) i;
    }

    /** Enum → its {@code name()} (via toString), or null. Avoids importing the CRUD enum types. */
    private static String enumName(Object e) {
        return e == null ? null : e.toString();
    }

    /** Serialize a field's per-variable initializer map to a JSON string (Neo4j has no map type), or null. */
    private static String variableInitializersJson(Map<String, String> initializers) {
        if (initializers == null || initializers.isEmpty()) {
            return null;
        }
        return GSON.toJson(initializers);
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Build an ordered props map from alternating key/value varargs. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = RowBuilder.props();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
