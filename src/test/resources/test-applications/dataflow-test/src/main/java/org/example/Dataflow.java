package org.example;

/**
 * A fixture exercising the L3 dataflow constructs: a loop-carried def-use, if/else, an early return,
 * try/catch/finally, and a shadowed variable in a nested block.
 */
public class Dataflow {

    int compute(int n) {
        int sum = 0;
        int other = n + 1;
        log(other);
        for (int i = 0; i < n; i++) {
            sum = sum + i;
        }
        return sum;
    }

    String classify(int x) {
        String r;
        if (x > 0) {
            r = "pos";
        } else {
            r = "nonpos";
        }
        return r;
    }

    int guarded(int x) {
        if (x < 0) {
            return -1;
        }
        return x;
    }

    int risky(int x) {
        int r;
        try {
            r = parse(x);
        } catch (NumberFormatException e) {
            r = 0;
        } finally {
            log(x);
        }
        return r;
    }

    int parse(int x) {
        return x;
    }

    int viaFinally(int x) {
        try {
            return parse(x);
        } finally {
            log(x);
        }
    }

    int shadowed(int x) {
        int y = x;
        {
            int z = y + 1;
            y = z;
        }
        return y;
    }

    void log(int v) {
    }
}
