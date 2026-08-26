package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.Type;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JComment;
import com.ibm.cldk.schema.Span;
import com.ibm.cldk.schema.Spans;
import com.ibm.cldk.utils.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;

/**
 * Shared, per-file context threaded through the L1 v2 builders (one cohesive builder per node kind).
 * Holds the identity/source data every builder needs and offers the small helpers they share, so the
 * builders stay focused on their node kind rather than re-deriving ids/spans.
 */
@Getter
public final class L1BuildContext {

    private final String applicationId;
    private final String fileKey;
    private final String source;

    /** The requested analysis level; the L3 dataflow pass runs at parse time when this is {@code >= 3}. */
    private final int analysisLevel;

    /** The DDG access-path bound k (the {@code --graph-field-depth} flag), used only at level 3. */
    private final int graphFieldDepth;

    /**
     * The L3 engine name — {@code "ast"} (default, parse-time overlays) or {@code "wala"}
     * (post-build overlays produced by {@code L3WalaOverlays}). Only meaningful at level 3;
     * {@code CallableBuilder} uses it to gate the parse-time AST L3 hook.
     */
    private final String l3Engine;

    /**
     * Memoized *type* resolution failures. Safe because a declared type spelling resolves consistently
     * within one file (this context is per-file), and retrying an unresolvable spelling is expensive.
     * Expression results are deliberately NOT memoized: the same text (`x`) can denote different types
     * in different scopes of one file, so caching a failure would blank later resolvable occurrences.
     */
    private final Set<String> unresolvedTypes = new HashSet<>();

    public L1BuildContext(String applicationId, String fileKey, String source) {
        this(applicationId, fileKey, source, 1, 3, "ast");
    }

    public L1BuildContext(String applicationId, String fileKey, String source, int analysisLevel,
            int graphFieldDepth) {
        this(applicationId, fileKey, source, analysisLevel, graphFieldDepth, "ast");
    }

    public L1BuildContext(String applicationId, String fileKey, String source, int analysisLevel,
            int graphFieldDepth, String l3Engine) {
        this.applicationId = applicationId;
        this.fileKey = fileKey;
        this.source = source;
        this.analysisLevel = analysisLevel;
        this.graphFieldDepth = graphFieldDepth;
        this.l3Engine = l3Engine != null ? l3Engine.toLowerCase(java.util.Locale.ROOT) : "ast";
    }

    /** The {@code can://java/<app>/<file>} id for this module. */
    public String moduleId() {
        return CanId.moduleId(applicationId, fileKey);
    }

    /**
     * Resolve a declared type to its qualified name via the JavaParser symbol solver, falling back to
     * the AST spelling when resolution fails (unresolvable dependency, missing classpath entry). This
     * is what makes L1 type fields qualified — the keystone expects the resolver to populate them when
     * the structural tool resolves, and the v1 symbol table did exactly this.
     *
     * <p>Resolution is attempted through the resolver attached to the parsed unit, so the caller must
     * have parsed with a symbol-solver-configured {@code ParserConfiguration}. Failures are memoized:
     * an unresolvable spelling is expensive to retry and appears repeatedly in real projects.
     */
    public String resolveType(Type type) {
        String spelling = type.asString();
        if (unresolvedTypes.contains(spelling)) {
            return spelling;
        }
        try {
            return type.resolve().describe();
        } catch (Throwable e) {
            Log.debug("Could not resolve type: " + spelling + ": " + e.getMessage());
            unresolvedTypes.add(spelling);
            return spelling;
        }
    }

    /**
     * Resolve an expression's type to its qualified name, or {@code ""} when it cannot be resolved
     * (mirrors the v1 behaviour, where an unresolved expression contributes no type fact).
     */
    public String resolveExpressionType(Expression expression) {
        try {
            return expression.calculateResolvedType().describe();
        } catch (Throwable e) {
            Log.debug("Could not resolve expression: " + expression + ": " + e.getMessage());
            return "";
        }
    }

    /**
     * The comment attached to a declaration — its javadoc, or the leading line/block comment. Returns
     * an empty list when the node has none. Deliberately the node's <em>own</em> comment rather than
     * every comment contained within it (v1's behaviour, which duplicated member comments onto types).
     */
    public List<JComment> commentsOf(Node node) {
        List<JComment> comments = new ArrayList<>();
        node.getComment().ifPresent(c -> comments.add(comment(c)));
        return comments;
    }

    /** Convert a JavaParser comment into the v2 model. */
    public JComment comment(Comment c) {
        JComment out = new JComment();
        out.setContent(c.getContent());
        out.setSpan(spanOf(c));
        out.setJavadoc(c.isJavadocComment());
        return out;
    }

    /**
     * The span covering the whole file — the module's own span. Computed from the source rather than
     * the compilation unit's AST range (which ends inconsistently around trailing whitespace), so the
     * invariant {@code module.source[span.bytes] == module.source} always holds.
     */
    public Span wholeFileSpan() {
        // The end is the position one past the last character. Lines are split on universal newlines
        // (shared with Spans, so \r\n and lone \r count like \n) and keep their terminators, so when the
        // file ends with one the position is the start of the following line.
        List<String> lines = Spans.splitLinesKeepingTerminators(source);
        int lastLine = 1;
        int lastCol = 1;
        if (!lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            boolean endsWithTerminator = last.endsWith("\n") || last.endsWith("\r");
            lastLine = endsWithTerminator ? lines.size() + 1 : lines.size();
            lastCol = endsWithTerminator ? 1 : last.length() + 1;
        }
        Span span = new Span();
        span.setStart(new int[] {1, 1});
        span.setEnd(new int[] {lastLine, lastCol});
        span.setBytes(new int[] {0, source.getBytes(StandardCharsets.UTF_8).length});
        return span;
    }

    /**
     * SHA-256 hex of the file's UTF-8 source — the module's {@code content_hash}, used for
     * incremental caching and the Neo4j writer's per-module diffing (never for identity).
     */
    public String contentHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; treat absence as unrecoverable rather than silently
            // emitting a hash that would break cache/diff correctness.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Build the {@link Span} for an AST node from its source range: {@code start}/{@code end} as
     * JavaParser {@code [line, column]} (1-based), {@code bytes} as {@code [from, to)} UTF-8 offsets
     * into the module source. Returns {@code null} when the node has no range (absent = no fact).
     */
    public Span spanOf(Node node) {
        if (node.getRange().isEmpty()) {
            return null;
        }
        Range r = node.getRange().get();
        Span span = new Span();
        span.setStart(new int[] {r.begin.line, r.begin.column});
        span.setEnd(new int[] {r.end.line, r.end.column});
        // JavaParser columns are 1-based and the end position is the last char (inclusive); convert
        // to a [from, to) byte slice: begin col-1 (0-based start), end col (0-based char after last).
        span.setBytes(Spans.byteOffsets(source, r.begin.line, r.begin.column - 1, r.end.line, r.end.column));
        return span;
    }
}
