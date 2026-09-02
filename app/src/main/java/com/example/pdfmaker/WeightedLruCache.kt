package com.example.pdfmaker

/** Small synchronized LRU used where entry count alone cannot represent memory cost. */
internal class WeightedLruCache<K, V>(
    private val maximumEntries: Int,
    private val maximumWeight: Long,
    private val weightOf: (V) -> Long,
) {
    private data class Entry<V>(
        val value: V,
        val weight: Long,
    )

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var retainedWeight = 0L

    init {
        require(maximumEntries > 0) { "Cache entry limit must be positive" }
        require(maximumWeight > 0) { "Cache weight limit must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(
        key: K,
        value: V,
    ) {
        val weight = weightOf(value)
        require(weight > 0) { "Cached values must have a positive weight" }
        entries.remove(key)?.let { previous -> retainedWeight -= previous.weight }
        if (weight > maximumWeight) return

        entries[key] = Entry(value, weight)
        retainedWeight += weight
        trimToLimits()
    }

    @Synchronized
    fun removeWhere(predicate: (K) -> Boolean): List<V> {
        val removed = mutableListOf<V>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val current = iterator.next()
            if (predicate(current.key)) {
                removed += current.value.value
                retainedWeight -= current.value.weight
                iterator.remove()
            }
        }
        return removed
    }

    @Synchronized
    fun clear(): List<V> {
        val removed = entries.values.map(Entry<V>::value)
        entries.clear()
        retainedWeight = 0L
        return removed
    }

    @Synchronized
    fun statistics(): CacheStatistics = CacheStatistics(entries.size, retainedWeight)

    private fun trimToLimits() {
        val iterator = entries.entries.iterator()
        while ((entries.size > maximumEntries || retainedWeight > maximumWeight) && iterator.hasNext()) {
            val eldest = iterator.next()
            retainedWeight -= eldest.value.weight
            iterator.remove()
        }
    }
}

internal data class CacheStatistics(
    val entryCount: Int,
    val retainedWeight: Long,
)
