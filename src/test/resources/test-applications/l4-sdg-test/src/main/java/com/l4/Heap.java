package com.l4;

public class Heap {
    private int box;

    public void put(int v) {
        this.box = v;
    }

    public int get() {
        return this.box;
    }

    public int roundTrip(int v) {
        put(v);
        return get();
    }
}
