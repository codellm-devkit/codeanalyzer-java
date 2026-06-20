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
import com.ibm.cldk.entities.CallSite;
import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.Comment;
import com.ibm.cldk.entities.EnumConstant;
import com.ibm.cldk.entities.Field;
import com.ibm.cldk.entities.Import;
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
 * <p>Modelling decisions (mirrors the codeanalyzer-typescript Neo4j projection):
 * <ul>
 *   <li>{@code Type} and {@code Callable} carry a shared {@code :Symbol} label (the global-identity
 *       / MERGE key); their {@code id} is the FQN (types) or {@code <fqn>#<signature>} (callables).</li>
 *   <li>call sites, fields, parameters, variables, enum constants and record components are
 *       first-class nodes; annotations are shared {@code :Annotation} nodes (like TS decorators).</li>
 *   <li>entrypoints are a marker label ({@code :Entrypoint}) on the owning callable/type.</li>
 *   <li>every project-owned node carries an internal {@code _unit} provenance prop, so the
 *       incremental writer can delete exactly what a re-analyzed compilation unit previously emitted.</li>
 * </ul>
 */
public final class GraphProjector {

    private static final Gson GSON = new Gson();

    private GraphProjector() {}

    /**
     * @param symbolTable file path → {@link JavaCompilationUnit} (the {@code symbol_table} map).
     * @param callGraph   the {@code call_graph} array (level 2), or {@code null} at level 1.
     * @param appName     logical application name for the {@code :Application} anchor.
     */
    public static GraphRows project(Map<String, JavaCompilationUnit> symbolTable, JsonArray callGraph, String appName) {
        RowBuilder b = new RowBuilder();

        NodeRef app = b.node(Collections.singletonList("Application"), "name", appName,
                map("schema_version", SchemaCatalog.SCHEMA_VERSION, "name", appName));

        for (Map.Entry<String, JavaCompilationUnit> e : symbolTable.entrySet()) {
            String fileKey = e.getKey();
            JavaCompilationUnit cu = e.getValue();
            NodeRef cuRef = b.node(Collections.singletonList("CompilationUnit"), "file_key", fileKey,
                    compilationUnitProps(cu, fileKey));
            b.edge("HAS_UNIT", app, cuRef);
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
            typeRefs.put(te.getKey(), b.node(symbolLabels("Type", te.getValue().isEntrypointClass()), "id",
                    te.getKey(), typeProps(te.getValue(), te.getKey(), fileKey)));
        }

        for (Map.Entry<String, Type> te : types.entrySet()) {
            String fqn = te.getKey();
            Type type = te.getValue();
            NodeRef typeRef = typeRefs.get(fqn);

            String parent = type.getParentType();
            if (parent != null && typeKeys.contains(parent)) {
                b.edge("HAS_NESTED_TYPE", typeRefs.get(parent), typeRef);
            } else {
                b.edge("DECLARES_TYPE", cuRef, typeRef);
            }

            projectTypeBody(b, fileKey, fqn, typeRef, type);
        }

        // Imports: resolve to a known Type (gated) or to a Package node.
        if (cu.getImports() != null) {
            for (Import im : cu.getImports()) {
                projectImport(b, cuRef, im, typeKeys);
            }
        }
    }

    private static void projectImport(RowBuilder b, NodeRef cuRef, Import im, Set<String> typeKeys) {
        String path = im.getPath();
        if (path == null || path.isEmpty()) {
            return;
        }
        Map<String, Object> props = map("is_static", im.isStatic(), "is_wildcard", im.isWildcard());
        if (!im.isWildcard() && typeKeys.contains(path)) {
            b.edgeToSymbol("IMPORTS", cuRef, path, props);
            return;
        }
        // Otherwise model the imported package: the path's package portion (strip the trailing class).
        String pkg = im.isWildcard() ? path : packageOf(path);
        if (pkg != null && !pkg.isEmpty()) {
            NodeRef pkgRef = b.node(Collections.singletonList("Package"), "name", pkg, map("name", pkg));
            b.edge("IMPORTS", cuRef, pkgRef, props);
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
            b.edgeToSymbol("EXTENDS", typeRef, s);
        }
        for (String s : safe(type.getImplementsList())) {
            b.edgeToSymbol("IMPLEMENTS", typeRef, s);
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
    }

    private static void projectCallable(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            Callable c, String mapKey) {
        String signature = c.getSignature() != null ? c.getSignature() : mapKey;
        String id = ownerFqn + "#" + signature;
        NodeRef ref = b.node(symbolLabels("Callable", c.isEntrypoint()), "id", id, callableProps(c, id, signature, fileKey));
        b.edge("HAS_CALLABLE", owner, ref);

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
    }

    private static void projectParameter(RowBuilder b, String fileKey, String callableId, NodeRef owner,
            ParameterInCallable p, int index) {
        String id = callableId + "#param#" + index;
        NodeRef ref = b.node(Collections.singletonList("Parameter"), "id", id, RowBuilder.prune(
                map("id", id, "name", p.getName(), "type", p.getType(),
                        "annotations", strList(p.getAnnotations()), "modifiers", strList(p.getModifiers()),
                        "start_line", asLong(p.getStartLine()), "end_line", asLong(p.getEndLine()),
                        "_unit", fileKey)));
        b.edge("HAS_PARAMETER", owner, ref);
    }

    private static void projectCallSite(RowBuilder b, String fileKey, String callableId, NodeRef owner, CallSite s) {
        String id = callableId + "@" + s.getStartLine() + ":" + s.getStartColumn()
                + "-" + s.getEndLine() + ":" + s.getEndColumn();
        NodeRef ref = b.node(Collections.singletonList("CallSite"), "id", id, RowBuilder.prune(
                map("id", id, "method_name", s.getMethodName(), "receiver_expr", s.getReceiverExpr(),
                        "receiver_type", s.getReceiverType(), "return_type", s.getReturnType(),
                        "callee_signature", s.getCalleeSignature(), "argument_types", strList(s.getArgumentTypes()),
                        "is_static_call", s.isStaticCall(), "is_constructor_call", s.isConstructorCall(),
                        "is_public", s.isPublic(), "is_private", s.isPrivate(), "is_protected", s.isProtected(),
                        "start_line", asLong(s.getStartLine()), "start_column", asLong(s.getStartColumn()),
                        "end_line", asLong(s.getEndLine()), "end_column", asLong(s.getEndColumn()),
                        "_unit", fileKey)));
        b.edge("HAS_CALLSITE", owner, ref);
        if (s.getCalleeSignature() != null && !s.getCalleeSignature().isEmpty()) {
            // Gated: kept only if the callee was emitted as a Callable node.
            b.edgeToSymbol("RESOLVES_TO", ref, s.getCalleeSignature());
        }
    }

    private static void projectVariable(RowBuilder b, String fileKey, String callableId, NodeRef owner,
            VariableDeclaration v) {
        String id = callableId + "#var#" + v.getName() + "@" + v.getStartLine();
        NodeRef ref = b.node(Collections.singletonList("Variable"), "id", id, RowBuilder.prune(
                map("id", id, "name", v.getName(), "type", v.getType(), "initializer", v.getInitializer(),
                        "start_line", asLong(v.getStartLine()), "end_line", asLong(v.getEndLine()),
                        "_unit", fileKey)));
        b.edge("DECLARES_VAR", owner, ref);
    }

    private static void projectField(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner, Field f) {
        String id = ownerFqn + "#field#" + f.getName();
        NodeRef ref = b.node(Collections.singletonList("Field"), "id", id, RowBuilder.prune(
                map("id", id, "name", f.getName(), "type", f.getType(),
                        "modifiers", strList(f.getModifiers()), "annotations", strList(f.getAnnotations()),
                        "variables", strList(f.getVariables()),
                        "start_line", asLong(f.getStartLine()), "end_line", asLong(f.getEndLine()),
                        "docstring", docstringOf(f.getComment()), "_unit", fileKey)));
        b.edge("HAS_FIELD", owner, ref);
        for (String s : safe(f.getAnnotations())) {
            annotate(b, ref, s);
        }
    }

    private static void projectEnumConstant(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            EnumConstant ec) {
        String id = ownerFqn + "#enum#" + ec.getName();
        NodeRef ref = b.node(Collections.singletonList("EnumConstant"), "id", id, RowBuilder.prune(
                map("id", id, "name", ec.getName(), "arguments", strList(ec.getArguments()), "_unit", fileKey)));
        b.edge("HAS_ENUM_CONSTANT", owner, ref);
    }

    private static void projectRecordComponent(RowBuilder b, String fileKey, String ownerFqn, NodeRef owner,
            RecordComponent rc) {
        String id = ownerFqn + "#rec#" + rc.getName();
        NodeRef ref = b.node(Collections.singletonList("RecordComponent"), "id", id, RowBuilder.prune(
                map("id", id, "name", rc.getName(), "type", rc.getType(),
                        "modifiers", strList(rc.getModifiers()), "annotations", strList(rc.getAnnotations()),
                        "is_var_args", rc.isVarArgs(), "_unit", fileKey)));
        b.edge("HAS_RECORD_COMPONENT", owner, ref);
    }

    private static void annotate(RowBuilder b, NodeRef on, String annotation) {
        if (annotation == null || annotation.isEmpty()) {
            return;
        }
        NodeRef ann = b.node(Collections.singletonList("Annotation"), "name", annotation, map("name", annotation));
        b.edge("ANNOTATED_BY", on, ann);
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
                    "weight", asLong(parseIntOrNull(str(edge, "weight")))));
            // Gated against the symbol table: kept only if both callables were emitted as nodes.
            b.edgeIfBothResolved("CALLS",
                    new NodeRef("Symbol", "id", from), new NodeRef("Symbol", "id", to), props);
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
                "_unit", fileKey));
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
                "_unit", fileKey);
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
                "_unit", fileKey);
        return RowBuilder.prune(p);
    }

    // ----------------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------------

    private static List<String> symbolLabels(String specific, boolean entrypoint) {
        if (entrypoint) {
            return Arrays.asList("Symbol", specific, "Entrypoint");
        }
        return Arrays.asList("Symbol", specific);
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
