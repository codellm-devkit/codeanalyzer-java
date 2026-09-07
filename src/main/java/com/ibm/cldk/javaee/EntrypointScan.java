package com.ibm.cldk.javaee;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JEntrypointReport;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs the entrypoint finders and keeps <em>which</em> of them matched, rather than collapsing them
 * with {@code anyMatch}. The framework names it returns land on the node
 * ({@code entrypoint_frameworks}); the failures it catches land in the run's
 * {@link JEntrypointReport}.
 *
 * <p><b>Why attribution lives on the node.</b> {@code L1Extractor} reuses a cached module
 * byte-for-byte and skips the build, so the finders never run for that file. Anything recorded only
 * during the walk would therefore under-report on a warm cache — which is exactly the ambiguous
 * empty the report exists to prevent. The tree carries the attribution whether the module was
 * rebuilt or reused; the application-level {@code frameworks_detected} is a union over it, computed
 * at assembly time.
 *
 * <p>The corollary, stated rather than left for a reader to assume: {@code unresolved} and
 * {@code errors} describe <em>this run's</em> pass, so they are genuinely empty for a file served
 * from cache — no finder ran for it, so nothing could fail.
 *
 * <p>A finder that throws does not abort the scan. One badly-shaped declaration must not cost the
 * other four finders their answer, and a silent {@code false} would be indistinguishable from "this
 * really is not an entrypoint" — so the failure is counted and named instead.
 */
public final class EntrypointScan {

    private EntrypointScan() {}

    /** Every ruleset the pass could match, matched or not — the {@code rulesets} vocabulary. */
    public static List<String> rulesets() {
        List<String> out = new ArrayList<>();
        EntrypointsFinderFactory.getEntrypointFinders().forEach(f -> out.add(f.framework()));
        return out;
    }

    /**
     * Fill in the parts of the report that are only knowable once the whole tree exists:
     * {@code rulesets} (always all five, matched or not) and {@code frameworks_detected} (the union
     * of the tree's node attributions).
     *
     * <p>The union is taken over the BUILT TREE, not over what the finders did during the walk,
     * which is what makes it correct on a warm cache — a reused module runs no finders but still
     * carries its attributions.
     */
    public static void completeReport(JEntrypointReport report, Map<String, JModule> modules) {
        report.setRulesets(rulesets());
        Set<String> detected = new TreeSet<>();
        if (modules != null) {
            for (JModule module : modules.values()) {
                collect(module.getTypes(), detected);
            }
        }
        report.setFrameworksDetected(new ArrayList<>(detected));
    }

    private static void collect(Map<String, JType> types, Set<String> detected) {
        if (types == null) {
            return;
        }
        for (JType type : types.values()) {
            detected.addAll(type.getEntrypointFrameworks());
            for (JCallable callable : type.getCallables().values()) {
                detected.addAll(callable.getEntrypointFrameworks());
                collect(callable.getTypes(), detected);
            }
            collect(type.getTypes(), detected);
        }
    }

    /** The frameworks recognising {@code td} as an entrypoint class, in finder order. */
    public static List<String> classFrameworks(TypeDeclaration<?> td, JEntrypointReport report) {
        List<String> out = new ArrayList<>();
        EntrypointsFinderFactory.getEntrypointFinders().forEach(finder -> {
            try {
                if (finder.isEntrypointClass(td)) {
                    out.add(finder.framework());
                }
            } catch (RuntimeException e) {
                record(report, finder, e);
            }
        });
        return out;
    }

    /** The frameworks recognising {@code cd} as an entrypoint method, in finder order. */
    public static List<String> methodFrameworks(CallableDeclaration<?> cd, JEntrypointReport report) {
        List<String> out = new ArrayList<>();
        EntrypointsFinderFactory.getEntrypointFinders().forEach(finder -> {
            try {
                if (finder.isEntrypointMethod(cd)) {
                    out.add(finder.framework());
                }
            } catch (RuntimeException e) {
                record(report, finder, e);
            }
        });
        return out;
    }

    private static void record(JEntrypointReport report, AbstractEntrypointFinder finder,
            RuntimeException e) {
        if (report == null) {
            return;
        }
        report.getUnresolved().merge(finder.framework(), 1, Integer::sum);
        // Deduplicated: one finder failing the same way on 400 declarations is one fact, and 400
        // copies of it would bury the other four finders' failures.
        String message = finder.framework() + ": " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " — " + e.getMessage());
        if (!report.getErrors().contains(message)) {
            report.getErrors().add(message);
        }
    }
}
