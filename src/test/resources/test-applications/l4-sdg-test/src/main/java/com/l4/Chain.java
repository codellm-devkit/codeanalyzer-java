package com.l4;

public class Chain {
    public int a(int x) {
        return b(x + 1);
    }

    public int b(int y) {
        return c(y * 2);
    }

    public int c(int z) {
        return z - 3;
    }
}
