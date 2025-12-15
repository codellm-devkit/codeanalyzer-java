import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import junit.framework.TestCase;

public class WeakHashtableTestCase extends TestCase {

    public static class TestThread extends Thread {

        public TestThread(final String name) {
            super(name);
        }

        @Override
        public void run() {
            for (int i = 0; i < RUN_LOOPS; i++) {
                hashtable.put("key:" + i % 10, Boolean.TRUE);
                if (i % 50 == 0) {
                    yield();
                }
            }
        }
    }
    private static final int RUN_LOOPS = 3000;
    private static WeakHashtable hashtable;

    public WeakHashtableTestCase(final String testName) {
        super(testName);
    }

}
