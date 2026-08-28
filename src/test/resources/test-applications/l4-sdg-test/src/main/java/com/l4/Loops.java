package com.l4;

public class Loops {
    // The parameter is named nowhere but the for-each header, which BodyNodeBuilder folds into the
    // single `loop` node covering the whole statement. A parameter has no ddg def site, so nothing
    // can seed `q` except that node's own source text.
    public int first(int[] q) {
        for (int x : q) {
            return x;
        }
        return 0;
    }

    public int callFirst(int[] a) {
        return first(a);
    }
}
