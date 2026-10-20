package dev.codenb.core

import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.NodeId
import java.util.LinkedHashMap

data class CacheKey(
    val graphId: GraphId,
    val nodeId: NodeId,
    val revision: String,
)

interface ExpansionCache {
    fun get(key: CacheKey): Expansion?

    fun put(
        key: CacheKey,
        expansion: Expansion,
    )

    fun remove(key: CacheKey)

    fun clear()
}

class InMemoryExpansionCache(
    private val maxEntries: Int,
) : ExpansionCache {
    init {
        require(maxEntries > 0) { "Cache size must be positive" }
    }

    private val lock = Any()
    private val entries =
        object : LinkedHashMap<CacheKey, Expansion>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Expansion>?): Boolean = size > maxEntries
        }

    override fun get(key: CacheKey): Expansion? = synchronized(lock) { entries[key] }

    override fun put(
        key: CacheKey,
        expansion: Expansion,
    ) {
        synchronized(lock) {
            entries[key] = expansion
        }
    }

    override fun remove(key: CacheKey) {
        synchronized(lock) {
            entries.remove(key)
        }
    }

    override fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) { entries.size }
}
