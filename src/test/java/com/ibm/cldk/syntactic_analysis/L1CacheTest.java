package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declaring-type hint (§4) is cache-only: excluded from the emitted payload but required to
 * survive the on-disk cache, or a warm-cache {@code -c} run silently loses the L2 {@code callee}
 * backfill. Because a Gson {@code ExclusionStrategy} skips a field in <em>both</em> directions, BOTH
 * cache paths ({@code save} and {@code load}) must use the hint-preserving Gson — switching only the
 * writer would persist the hint and then discard it on read, reproducing the exact bug it exists to
 * prevent while looking fixed. This is the one failure mode of §4's plumbing no single-run test sees.
 */
class L1CacheTest {

    private static final String APP = "myapp";
    private static final String VERSION = "9.9.9";
    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";

    private static Map<String, JModule> modulesWithHint(String hint) {
        JBodyNode call = new JBodyNode();
        call.setKind("call");
        call.setDeclaringTypeHint(hint);

        JCallable callable = new JCallable();
        callable.setKind("method");
        callable.getBody().put("4:5", call);

        JType type = new JType();
        type.setKind("class");
        type.getCallables().put("m()", callable);

        JModule module = new JModule();
        module.setPackageName("com.example");
        module.setSource("class Foo {}\n");
        module.getTypes().put("Foo", type);

        Map<String, JModule> modules = new LinkedHashMap<>();
        modules.put(FILE_KEY, module);
        return modules;
    }

    private static String hintOf(Map<String, JModule> modules) {
        return modules.get(FILE_KEY).getTypes().get("Foo").getCallables().get("m()")
                .getBody().get("4:5").getDeclaringTypeHint();
    }

    @Test
    void save_thenLoad_preservesTheDeclaringTypeHint(@TempDir Path cacheDir) {
        L1Cache.save(cacheDir, APP, VERSION, modulesWithHint("java.util.Map$Entry"));
        Map<String, JModule> loaded = L1Cache.load(cacheDir, APP, VERSION);
        assertEquals("java.util.Map$Entry", hintOf(loaded),
                "the hint must round-trip through the cache; losing it regresses the backfill on -c runs");
    }
}
