package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Data;

/**
 * Coverage and failure record for the entrypoint pass — the same four keys codeanalyzer-python's
 * {@code PyEntrypointReport} carries, so one SDK model parses either analyzer's report.
 *
 * <p>The pass under-approximates by design, so <em>silence is its failure mode</em>. Without this
 * record, "this application has no entrypoints" and "the detection pass found nothing" are the same
 * observation. It is therefore emitted <b>always, even when every field is empty</b>: an empty
 * {@code frameworks_detected} next to a populated {@code rulesets} is precisely what says the pass
 * looked and came back empty-handed.
 */
@Data
public class JEntrypointReport {

    /** The frameworks that actually matched something — the union of the tree's node attributions. */
    private List<String> frameworksDetected = new ArrayList<>();

    /**
     * Every ruleset the pass could have matched, matched or not. Java's finders are hardcoded
     * classes rather than data-driven rule files, so the five finder names are the ruleset
     * vocabulary.
     */
    private List<String> rulesets = new ArrayList<>();

    /**
     * Per-finder count of declarations the pass could not decide, because that finder threw. Sorted
     * by finder name so the serialized report is byte-stable for the {@code -j} determinism gate.
     */
    private Map<String, Integer> unresolved = new TreeMap<>();

    /** One message per finder that failed, deduplicated. */
    private List<String> errors = new ArrayList<>();
}
