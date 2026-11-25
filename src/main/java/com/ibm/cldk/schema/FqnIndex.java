package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node ids by qualified (or binary) type name, where one name may be declared by several types.
 *
 * <p>Vendored copies of one library across source roots declare the same name in several files
 * (#269). A reference to such a name binds to the copy nearest the referring node: the one whose id
 * shares the most leading {@code /}-separated segments with it, which puts the referrer's own source
 * root first. Ties, and lookups with no referrer, fall back to id order, so the choice is always
 * deterministic rather than an artifact of insertion order.
 */
public final class FqnIndex {
    private final Map<String, List<String>> ids = new HashMap<>();
    private boolean sorted = true;

    /** Record that {@code id} declares {@code name}. */
    public void put(String name, String id) {
        ids.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
        sorted = false;
    }

    public boolean containsKey(String name) {
        return ids.containsKey(name);
    }

    /** Every id declaring {@code name}, nearest {@code near} first ({@code near} may be null); read-only. */
    public List<String> ordered(String name, String near) {
        sortOnce();
        List<String> found = ids.get(name);
        if (found == null) {
            return List.of();
        }
        if (near == null || found.size() < 2) {
            return Collections.unmodifiableList(found);
        }
        List<String> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt((String id) -> -commonSegments(id, near))
                .thenComparing(Comparator.naturalOrder()));
        return ordered;
    }

    /** The id declaring {@code name} nearest {@code near}, or null when none does. */
    public String nearest(String name, String near) {
        List<String> ordered = ordered(name, near);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    private void sortOnce() {
        if (!sorted) {
            ids.values().forEach(Collections::sort);
            sorted = true;
        }
    }

    /** How many leading {@code /}-separated segments two ids (or two relative paths) share. */
    public static int commonSegments(String a, String b) {
        String[] x = a.split("/");
        String[] y = b.split("/");
        int n = 0;
        while (n < x.length && n < y.length && x[n].equals(y[n])) {
            n++;
        }
        return n;
    }
}
