package com.ibm.cldk.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a model field that must survive the on-disk L1 cache but must never appear in the emitted
 * payload. {@link V2Json#cache()} keeps such fields; {@link V2Json#compact()} and
 * {@link V2Json#pretty()} exclude them (in both directions, since a Gson {@code ExclusionStrategy}
 * applies to serialization and deserialization alike).
 *
 * <p>The declaring-type hint on {@link JBodyNode} is the sole case (§4 of the L2 call-graph design):
 * L1 records it per resolved call site so L2 can backfill {@code callee}, but it is internal plumbing,
 * not schema surface. A {@code transient} field would have been excluded from the cache too, silently
 * losing the backfill on any second ({@code -c}) run — hence a marker Gson honours rather than the
 * language keyword Gson mirrors.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CacheOnly {}
