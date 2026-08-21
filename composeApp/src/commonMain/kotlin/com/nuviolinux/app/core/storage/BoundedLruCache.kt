package com.nuviolinux.app.core.storage

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe bounded cache with LRU eviction and entry expiry.
 *
 * Two independent bounds keep memory in check:
 *  - [maxSize]: hard ceiling on entry count; eviction always takes the
 *    least-recently-used entry.
 *  - Expiry: an entry leaves as soon as it is older than [maxAge] OR was last
 *    accessed longer than [idleTtl] ago — so unutilized entries are released
 *    to the GC even while the map is well under its cap.
 *
 * Expiry sweeps run amortized on access (every [SWEEP_EVERY] mutations), so no
 * background thread is needed: cold entries disappear on a regular cadence and
 * the map can never exceed [maxSize]. All operations are O(1) amortized except
 * sweeps, which are O(n) over at most [maxSize] entries.
 *
 * Iteration order of Kotlin's LinkedHashMap is insertion order on every
 * target, and lookups reinsert the entry (remove + add) so the map head is
 * always the least-recently-used entry — a portable LRU without JVM-only
 * access-ordered LinkedHashMap constructors.
 */
class BoundedLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val maxAge: Duration = Duration.INFINITE,
    private val idleTtl: Duration = Duration.INFINITE,
    /** Injectable monotonic-milliseconds clock for deterministic tests. */
    private val nowMillis: () -> Long = DEFAULT_CLOCK,
    private val sweepEvery: Long = SWEEP_EVERY,
) {
    private class Entry<V>(
        var value: V,
        var createdAtMillis: Long,
        var lastAccessMillis: Long,
    )

    private val monitor = SynchronizedObject()
    private val map = LinkedHashMap<K, Entry<V>>()
    private var mutations = 0

    val size: Int
        get() = synchronized(monitor) { map.size }

    fun get(key: K): V? = synchronized(monitor) {
        sweepIfDue()
        val entry = map[key] ?: return null
        val now = nowMillis()
        if (isExpired(entry, now)) {
            map.remove(key)
            return null
        }
        entry.lastAccessMillis = now
        map.remove(key)
        map[key] = entry /* reinsert -> MRU tail */
        entry.value
    }

    fun put(key: K, value: V): Unit = synchronized(monitor) {
        val now = nowMillis()
        val existing = map.remove(key)
        if (existing != null) {
            existing.value = value
            existing.lastAccessMillis = now
            map[key] = existing
        } else {
            map[key] = Entry(value, now, now)
        }
        mutations++
        if (map.size > maxSize) {
            /* Cap pressure: drop expired first so LRU trimming only runs
             * against live entries. */
            evictExpired(now)
        }
        trimToCap()
        sweepIfDue()
    }

    /**
     * Returns the cached value or stores [loader]'s result. The loader runs
     * OUTSIDE the lock (expensive fetches must not block other keys); like the
     * plain-map code this replaces, two racing callers may both load.
     */
    inline fun getOrPut(key: K, loader: () -> V): V {
        get(key)?.let { return it }
        val value = loader()
        put(key, value)
        return value
    }

    fun remove(key: K): Boolean = synchronized(monitor) {
        val removed = map.remove(key) != null
        if (removed) {
            mutations++
            sweepIfDue(forceCheck = true)
        }
        removed
    }

    fun clear() {
        synchronized(monitor) {
            map.clear()
            mutations++
        }
    }

    private fun isExpired(entry: Entry<V>, now: Long): Boolean =
        (maxAge != Duration.INFINITE && now - entry.createdAtMillis > maxAge.inWholeMilliseconds) ||
            (idleTtl != Duration.INFINITE && now - entry.lastAccessMillis > idleTtl.inWholeMilliseconds)

    /** Drops every expired entry — called before LRU trimming and periodically. */
    private fun evictExpired(now: Long) {
        if (maxAge == Duration.INFINITE && idleTtl == Duration.INFINITE) return
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value, now)) iterator.remove()
        }
    }

    private fun trimToCap() {
        val keyIterator = map.keys.iterator()
        while (map.size > maxSize && keyIterator.hasNext()) {
            keyIterator.next()
            keyIterator.remove() /* head = least recently used */
        }
    }

    /**
     * Full expiry sweep on a mutation cadence so cold entries leave memory
     * regularly even when the cache never approaches [maxSize].
     */
    private fun sweepIfDue(forceCheck: Boolean = false) {
        if (!forceCheck && mutations % sweepEvery != 0L) return
        evictExpired(nowMillis())
    }

    companion object {
        private const val SWEEP_EVERY = 64L

        /** Fixed origin so the default clock reports true elapsed millis
         *  (a fresh mark per call would always read ~0). */
        private val ELAPSED_ORIGIN = TimeSource.Monotonic.markNow()
        private val DEFAULT_CLOCK: () -> Long = { ELAPSED_ORIGIN.elapsedNow().inWholeMilliseconds }
    }
}
