package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** One name declared by several types binds to the copy nearest its referrer (#269). */
class FqnIndexTest {
    private static final String A = "can://dt/dt/java/svc-a/src/main/java/com/shared/Bean.java/Bean";
    private static final String B = "can://dt/dt/java/svc-b/src/main/java/com/shared/Bean.java/Bean";
    private static final String C = "can://dt/dt/java/svc-c/src/main/java/com/shared/Bean.java/Bean";

    private static FqnIndex index() {
        FqnIndex index = new FqnIndex();
        index.put("com.shared.Bean", C);   // inserted last-first: order must not matter
        index.put("com.shared.Bean", A);
        index.put("com.shared.Bean", B);
        index.put("com.shared.Only", A + "/Only");
        return index;
    }

    @Test
    void theReferrersOwnCopyWins() {
        assertEquals(A, index().nearest("com.shared.Bean",
                "can://dt/dt/java/svc-a/src/main/java/com/shared/Caller.java/Caller/go()"));
        assertEquals(B, index().nearest("com.shared.Bean",
                "can://dt/dt/java/svc-b/src/main/java/com/shared/Caller.java/Caller/go()"));
    }

    @Test
    void withNoNearerCopyIdOrderDecides() {
        assertEquals(A, index().nearest("com.shared.Bean",
                "can://dt/dt/java/svc-d/src/main/java/com/shared/Caller.java/Caller/go()"));
        assertEquals(A, index().nearest("com.shared.Bean", null));
        assertEquals(List.of(B, A, C), index().ordered("com.shared.Bean",
                "can://dt/dt/java/svc-b/src/main/java/x/Y.java/Y"));
    }

    @Test
    void anUnknownNameHasNone() {
        assertNull(index().nearest("com.shared.Missing", A));
        assertEquals(List.of(), index().ordered("com.shared.Missing", null));
    }
}
