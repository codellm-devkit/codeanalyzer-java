package org.example;

import java.util.List;

/**
 * A record whose canonical constructor is written in compact form. Its parameters come from the record
 * components, not from the (empty) compact parameter list, so its signature is what a {@code new
 * Money(...)} call site resolves to.
 */
public record Money(List<String> tags, int cents) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("negative");
        }
    }

    public Money plus(Money other) {
        return new Money(tags, cents + other.cents());
    }
}
