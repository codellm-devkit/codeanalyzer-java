package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Cross-references out of a callable, nested per design decision D3: {@code types} referenced and
 * {@code fields} accessed in the body.
 *
 * <p>Both are <em>resolved</em> qualified names at L1 (D8) — {@code java.util.List} for a type, and
 * {@code <declaring-type>.<name>} for a field, so {@code other.count} and {@code this.count} stay
 * distinguishable. Each entry degrades to the bare spelling when the symbol solver cannot resolve it.
 * Promoting them to {@code can://} ids needs cross-module resolution and arrives at L2.
 */
@Data
public class JRefs {
    private List<String> types = new ArrayList<>();
    private List<String> fields = new ArrayList<>();
}
