package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The descriptor→id converters that make WALA endpoints joinable to v2 ids. The call-graph build
 * itself is exercised in the {@code realworld}-tagged suite; these pin the string conversions that a
 * fixture cannot reliably cover, and that must match {@code Signatures} exactly or every RTA edge misses.
 */
class RtaCallGraphTest {

    @Test
    void binaryTypeNameStripsTheDescriptorPrefixAndDotsThePackage() {
        assertEquals("org.example.User", RtaCallGraph.binaryTypeName("Lorg/example/User"));
    }

    @Test
    void binaryTypeNameKeepsTheDollarOfANestedType() {
        // Binary, not dotted: the id must be unambiguous and join WALA natively (§2).
        assertEquals("java.util.Map$Entry", RtaCallGraph.binaryTypeName("Ljava/util/Map$Entry"));
    }

    @Test
    void signatureDotsReferenceParametersAndSpellsPrimitives() {
        assertEquals("m(java.util.List, int)", RtaCallGraph.signature("m", "(Ljava/util/List;I)V"));
    }

    @Test
    void signatureRendersArraysWithBracketsLikeSignatures() {
        assertEquals("m(java.lang.String[])", RtaCallGraph.signature("m", "([Ljava/lang/String;)V"));
    }

    @Test
    void signatureOfANoArgConstructorIsInitWithEmptyParens() {
        assertEquals("<init>()", RtaCallGraph.signature("<init>", "()V"));
    }
}
