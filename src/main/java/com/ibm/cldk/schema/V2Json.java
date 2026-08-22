package com.ibm.cldk.schema;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Gson configuration for canonical schema v2 output.
 *
 * <p>Two conventions from the keystone are encoded here:
 *
 * <ul>
 *   <li><b>snake_case keys</b> via {@code LOWER_CASE_WITH_UNDERSCORES}, so one set of SDK models
 *       parses every analyzer ({@code schemaVersion} → {@code schema_version}, {@code errorChannel} →
 *       {@code error_channel}, {@code isVariadic} → {@code is_variadic}, …).
 *   <li><b>Absence means "no fact" — nulls are never emitted.</b> Unlike the v1 emitter (which used
 *       {@code serializeNulls()}), a v2 payload omits a key entirely rather than writing {@code null}.
 *       This includes the {@code callee} refinement slot: at L1 the key is simply absent, and it
 *       appears once L2 resolves the site. (The keystone's worked example shows {@code callee: null}
 *       illustratively; the reference Python analyzer likewise drops it via {@code exclude_none}.)
 * </ul>
 */
public final class V2Json {

    private V2Json() {}

    /**
     * Skips {@link CacheOnly} fields. Applied to the payload writers (both directions), so a cache-only
     * field neither leaks into the emitted payload nor is read back from one.
     */
    private static final ExclusionStrategy CACHE_ONLY = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(FieldAttributes f) {
            return f.getAnnotation(CacheOnly.class) != null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    private static final Gson COMPACT = payload().create();
    private static final Gson PRETTY = payload().setPrettyPrinting().create();
    private static final Gson CACHE = base().create();

    private static GsonBuilder base() {
        return new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .disableHtmlEscaping();
        // Deliberately NOT serializeNulls(): absent = no fact.
    }

    /** The payload configuration: {@link #base()} plus exclusion of {@link CacheOnly} fields. */
    private static GsonBuilder payload() {
        return base().setExclusionStrategies(CACHE_ONLY);
    }

    /** Compact JSON — what goes to stdout when {@code -o} is omitted. */
    public static Gson compact() {
        return COMPACT;
    }

    /** Pretty-printed JSON — what is written to {@code analysis.json}. */
    public static Gson pretty() {
        return PRETTY;
    }

    /**
     * The JSON writer/reader for the on-disk L1 cache. Identical to {@link #compact()} except it
     * <em>keeps</em> {@link CacheOnly} fields, which the payload writers exclude — so the declaring-type
     * hint (§4) survives a warm-cache run instead of being silently dropped.
     */
    public static Gson cache() {
        return CACHE;
    }
}
