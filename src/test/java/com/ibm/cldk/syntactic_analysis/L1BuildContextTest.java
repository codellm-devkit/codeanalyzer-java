package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.cldk.schema.CanId;
import org.junit.jupiter.api.Test;

class L1BuildContextTest {

    private static final String APP = CanId.applicationId("daytrader");

    @Test
    void theModuleIdIsBuiltFromTheIdPathAndNotFromTheFileKey() {
        // The two differ whenever a module declares a coordinate: the key stays the real
        // --input-relative path (uniqueness is the key's job), while the id carries the coordinate.
        L1BuildContext ctx = new L1BuildContext(
                APP,
                "src/main/java/com/foo/Bar.java",
                "daytrader-web-service/src/main/java/com/foo/Bar.java",
                "class Bar {}",
                1,
                3,
                "ast",
                null);

        assertEquals(
                "can://daytrader/java/daytrader-web-service/src/main/java/com/foo/Bar.java",
                ctx.moduleId());
        assertEquals("src/main/java/com/foo/Bar.java", ctx.getFileKey());
    }

    @Test
    void aContextBuiltWithoutAnIdPathFallsBackToTheFileKey() {
        // Every convenience constructor defaults one to the other, so a module with no resolvable
        // coordinate keeps exactly the id it had before coordinates existed.
        L1BuildContext ctx =
                new L1BuildContext(APP, "src/main/java/com/foo/Bar.java", "class Bar {}");

        assertEquals("can://daytrader/java/src/main/java/com/foo/Bar.java", ctx.moduleId());
    }
}
