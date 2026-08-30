package com.l4;

/**
 * The fixture's only callable with two parameters, and the only shape that can tell per-parameter
 * seeding apart from seeding the shared node every formal is defined at. Only `p` reaches `leak`'s
 * return; `q` is handed to a void callee and goes nowhere. Seed a parameter at the def end of its
 * ddg edge and both collapse onto `@entry`, `q` walks `p`'s edges to the return, and `caller` gains
 * a second summary edge claiming `n` comes back out of `leak`.
 */
public class Arity {

    public int leak(int p, int q) {
        int t = p;
        sink(q);
        return t;
    }

    public void sink(int z) {
    }

    public int caller(int m, int n) {
        return leak(m, n);
    }
}
