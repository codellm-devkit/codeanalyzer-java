package com.l4;

public class Mutual {
    public int even(int n) {
        if (n == 0) {
            return 1;
        }
        return odd(n - 1);
    }

    public int odd(int n) {
        if (n == 0) {
            return 0;
        }
        return even(n - 1);
    }
}
