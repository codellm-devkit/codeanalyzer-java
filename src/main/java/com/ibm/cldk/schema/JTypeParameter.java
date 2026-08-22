package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A declared type parameter — the {@code <T extends Comparable<T>>} clause on a generic type or
 * callable.
 *
 * <p>Without this, a generic signature is not reconstructable from the emitted facts. A parameter
 * declared as {@code T} resolves to the bare spelling {@code T} (a type variable has no qualified
 * name), and {@code declaration} omits the type-parameter clause because JavaParser's
 * {@code getDeclarationAsString} does — so a consumer seeing {@code type: "T"} had no way to learn
 * what {@code T} ranges over. These nodes supply exactly that.
 *
 * <p>Order is significant and is declaration order: a type argument binds to a parameter by
 * position, so {@code Map<K, V>} depends on {@code K} coming first.
 *
 * <p>The canonical schema has no generics vocabulary, so this is an additive Java node.
 */
@Data
public class JTypeParameter {

    private String name;

    /**
     * The declared bounds, resolved. {@code T extends Comparable<T> & Cloneable} yields two entries;
     * an unbounded {@code T} yields none — the implicit {@code java.lang.Object} bound is left absent
     * rather than fabricated, so "unbounded" stays distinguishable from "explicitly bounded by
     * Object". Bounds keep their type arguments, matching every other resolved type field.
     */
    private List<String> bounds = new ArrayList<>();

    private Span span;

    /** Annotations on the parameter itself, as in {@code <@NonNull T>}. */
    private List<JDecorator> decorators = new ArrayList<>();
}
