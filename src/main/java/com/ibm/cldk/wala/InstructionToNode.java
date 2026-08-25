package com.ibm.cldk.wala;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a WALA SSA instruction (with its recovered source line) to a body-node {@code localId}.
 *
 * <p>WALA recovers an instruction's source line from the bytecode line-number table but never a
 * column (bytecode has no column table). This mapper takes the line and the AST statements anchored
 * on that line and returns the matched statement's exact {@code line:col} localId, or
 * {@code "<line>:0"} (the sentinel) when it cannot uniquely identify the covering statement.
 *
 * <p>The matched statement's {@code localId} is computed by
 * {@link BodyNodeBuilder#nodeIdFor(Statement)}, the single source of truth for the id rule (bare-
 * call statements anchor at the invoked-name/type, everything else at its own begin).
 *
 * <p>Content disambiguation for multiple statements on one line: for
 * {@link SSAAbstractInvokeInstruction} the invoked method name is matched against
 * {@link MethodCallExpr} nodes in each candidate; other instructions produce no content and fall
 * through to the sentinel.
 *
 * <p>The sentinel {@code "<line>:0"} is a valid {@code localId} (matches {@code ^\d+:\d+$}) and
 * indicates an over-approximation: the edge exists but its exact source node is unresolved. Call
 * {@link #overApproximationCount()} to retrieve the running total for logging.
 */
public final class InstructionToNode {

    private final Map<Integer, List<Statement>> statementsByLine;
    private int overApproximationCount;

    /**
     * @param statementsByLine index from source line to the AST statements whose anchor falls on
     *     that line, built from the re-parsed {@code BlockStmt} by the caller using the same
     *     anchoring rule as {@link BodyNodeBuilder}.
     */
    public InstructionToNode(Map<Integer, List<Statement>> statementsByLine) {
        this.statementsByLine = statementsByLine;
    }

    /**
     * Returns the {@code localId} of the AST statement that best covers {@code ins} at
     * {@code line}, or {@code "<line>:0"} when the match is absent or ambiguous.
     */
    public String map(SSAInstruction ins, int line) {
        List<Statement> candidates = statementsByLine.getOrDefault(line, Collections.emptyList());
        return match(descriptorOf(ins), line, candidates);
    }

    /** The total number of instructions that could not be uniquely mapped to a source statement. */
    public int overApproximationCount() {
        return overApproximationCount;
    }

    // ----- package-private seam (unit-testable without a WALA build) ---------------------------

    /**
     * Extracts an operation descriptor from {@code ins}. For invoke instructions the invoked method
     * name is captured; all other instructions produce a descriptor with no content (variable-name
     * extraction requires the IR's local-variable table, which is not held here).
     */
    static InstructionDescriptor descriptorOf(SSAInstruction ins) {
        if (ins instanceof SSAAbstractInvokeInstruction) {
            String name =
                    ((SSAAbstractInvokeInstruction) ins).getDeclaredTarget().getName().toString();
            return new InstructionDescriptor(name, null);
        }
        return new InstructionDescriptor(null, null);
    }

    /**
     * Selects the best-matching statement for {@code desc} at {@code line} from {@code candidates}
     * and returns its {@code localId}, or {@code "<line>:0"} when the match is absent or ambiguous.
     *
     * <p>Disambiguation rules:
     * <ul>
     *   <li>Zero candidates → sentinel (no AST node covers this line).
     *   <li>One candidate → take it unconditionally.
     *   <li>Multiple candidates + {@code invokedMethodName} present → filter to those containing a
     *       {@link MethodCallExpr} with that name; if exactly one survives, take it; otherwise
     *       sentinel.
     *   <li>Multiple candidates + no content → sentinel.
     * </ul>
     *
     * <p>Every sentinel emission increments the {@link #overApproximationCount} counter.
     *
     * <p>Note: {@code desc.definedName} is currently <em>not</em> consulted — only
     * {@code invokedMethodName} drives disambiguation. Populating {@code definedName} (see
     * {@link InstructionDescriptor}) has no effect until a matching branch is added here.
     */
    String match(InstructionDescriptor desc, int line, List<Statement> candidates) {
        if (candidates.isEmpty()) {
            overApproximationCount++;
            return line + ":0";
        }
        if (candidates.size() == 1) {
            return BodyNodeBuilder.nodeIdFor(candidates.get(0));
        }
        if (desc.invokedMethodName != null) {
            List<Statement> filtered = filterByInvokedMethod(candidates, desc.invokedMethodName);
            if (filtered.size() == 1) {
                return BodyNodeBuilder.nodeIdFor(filtered.get(0));
            }
        }
        overApproximationCount++;
        return line + ":0";
    }

    /**
     * Returns the subset of {@code candidates} that contain a {@link MethodCallExpr} whose simple
     * name equals {@code methodName}.
     */
    private static List<Statement> filterByInvokedMethod(
            List<Statement> candidates, String methodName) {
        List<Statement> result = new ArrayList<>();
        for (Statement s : candidates) {
            boolean hasMatch =
                    s.findAll(MethodCallExpr.class).stream()
                            .anyMatch(mc -> mc.getNameAsString().equals(methodName));
            if (hasMatch) {
                result.add(s);
            }
        }
        return result;
    }

    // ----- static index builder -----------------------------------------------------------------

    /**
     * Builds a {@code Map<line, List<Statement>>} index from {@code body} by walking the block with
     * the same transparency rules as {@link BodyNodeBuilder}: labeled statements, bare block
     * statements, synchronized bodies, and try blocks are transparent (recursed into without
     * creating their own entries), while every other statement is entered under the line of its
     * addressing anchor. The resulting index is the correct input to
     * {@link InstructionToNode#InstructionToNode}.
     */
    public static Map<Integer, List<Statement>> statementsByLine(BlockStmt body) {
        Map<Integer, List<Statement>> result = new LinkedHashMap<>();
        collectBlock(body, result);
        return result;
    }

    private static void collectBlock(BlockStmt block, Map<Integer, List<Statement>> acc) {
        for (Statement s : block.getStatements()) {
            collectStatement(s, acc);
        }
    }

    private static void collectStatement(Statement s, Map<Integer, List<Statement>> acc) {
        // Transparent containers: recurse without adding an entry for the container itself.
        if (s.isLabeledStmt()) {
            collectStatement(s.asLabeledStmt().getStatement(), acc);
            return;
        }
        if (s.isBlockStmt()) {
            collectBlock(s.asBlockStmt(), acc);
            return;
        }
        if (s.isSynchronizedStmt()) {
            collectStatement(s.asSynchronizedStmt().getBody(), acc);
            return;
        }
        if (s.isTryStmt()) {
            TryStmt ts = s.asTryStmt();
            collectBlock(ts.getTryBlock(), acc);
            for (CatchClause cc : ts.getCatchClauses()) {
                collectBlock(cc.getBody(), acc);
            }
            ts.getFinallyBlock().ifPresent(fb -> collectBlock(fb, acc));
            return;
        }
        // All other statements get their own index entry; recurse into nested bodies.
        addToIndex(s, acc);
        if (s.isIfStmt()) {
            IfStmt is = s.asIfStmt();
            collectStatement(is.getThenStmt(), acc);
            is.getElseStmt().ifPresent(e -> collectStatement(e, acc));
        } else if (s.isWhileStmt()) {
            collectStatement(s.asWhileStmt().getBody(), acc);
        } else if (s.isForStmt()) {
            collectStatement(s.asForStmt().getBody(), acc);
        } else if (s.isForEachStmt()) {
            collectStatement(s.asForEachStmt().getBody(), acc);
        } else if (s.isDoStmt()) {
            collectStatement(s.asDoStmt().getBody(), acc);
        } else if (s.isSwitchStmt()) {
            SwitchStmt sw = s.asSwitchStmt();
            for (SwitchEntry se : sw.getEntries()) {
                for (Statement es : se.getStatements()) {
                    collectStatement(es, acc);
                }
            }
        }
    }

    private static void addToIndex(Statement s, Map<Integer, List<Statement>> acc) {
        String id = BodyNodeBuilder.nodeIdFor(s);
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return;
        }
        try {
            int line = Integer.parseInt(id.substring(0, colon));
            acc.computeIfAbsent(line, k -> new ArrayList<>()).add(s);
        } catch (NumberFormatException e) {
            // node at "0:0" from missing range — skip
        }
    }

    // ----- inner type ---------------------------------------------------------------------------

    /**
     * An operation descriptor derived from a WALA instruction. Drives content-based disambiguation
     * against AST candidate statements without requiring a WALA build in tests.
     *
     * <p>{@code invokedMethodName} is set for invoke instructions. {@code definedName} is reserved
     * for future use once the IR's local-variable table is threaded through.
     */
    static final class InstructionDescriptor {
        final String invokedMethodName;
        final String definedName;

        InstructionDescriptor(String invokedMethodName, String definedName) {
            this.invokedMethodName = invokedMethodName;
            this.definedName = definedName;
        }
    }
}
