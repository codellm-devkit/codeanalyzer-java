package org.example;

/**
 * An enum whose constants carry class bodies. Each such body is an anonymous subclass of the enum, so
 * its methods are real callables that a call graph must be able to reach.
 */
public enum Op {
    PLUS("+") {
        @Override
        public int apply(int a, int b) {
            return record(a + b);
        }
    },
    MINUS("-") {
        @Override
        public int apply(int a, int b) {
            return record(a - b);
        }
    },
    /** A constant with no body: it specialises nothing and gets no type of its own. */
    @Deprecated
    NOOP("");

    private final String symbol;

    Op(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public int apply(int a, int b) {
        return 0;
    }

    int record(int result) {
        return result;
    }
}
