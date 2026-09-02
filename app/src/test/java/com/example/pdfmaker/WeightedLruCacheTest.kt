package com.example.pdfmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WeightedLruCacheTest {
    @Test
    fun `evicts the least recently used value at the entry limit`() {
        val cache = WeightedLruCache<String, Int>(2, 100, Int::toLong)
        cache.put("first", 10)
        cache.put("second", 20)
        assertEquals(10, cache["first"])

        cache.put("third", 30)

        assertNull(cache["second"])
        assertEquals(10, cache["first"])
        assertEquals(30, cache["third"])
        assertEquals(CacheStatistics(2, 40), cache.statistics())
    }

    @Test
    fun `evicts enough entries to satisfy the weight limit`() {
        val cache = WeightedLruCache<String, Int>(10, 10, Int::toLong)
        cache.put("a", 4)
        cache.put("b", 4)
        cache.put("c", 5)

        assertNull(cache["a"])
        assertEquals(CacheStatistics(2, 9), cache.statistics())
    }

    @Test
    fun `replacement updates weight without consuming another entry`() {
        val cache = WeightedLruCache<String, Int>(2, 10, Int::toLong)
        cache.put("same", 8)
        cache.put("same", 3)

        assertEquals(3, cache["same"])
        assertEquals(CacheStatistics(1, 3), cache.statistics())
    }

    @Test
    fun `oversized values are returned to the caller but not retained`() {
        val cache = WeightedLruCache<String, Int>(2, 5, Int::toLong)
        cache.put("existing", 2)
        cache.put("oversized", 6)

        assertNull(cache["oversized"])
        assertEquals(CacheStatistics(1, 2), cache.statistics())
    }

    @Test
    fun `remove and clear return released values and reset accounting`() {
        val cache = WeightedLruCache<String, Int>(5, 100, Int::toLong)
        cache.put("one", 1)
        cache.put("two", 2)
        cache.put("three", 3)

        assertEquals(listOf(1, 3), cache.removeWhere { it != "two" })
        assertEquals(listOf(2), cache.clear())
        assertEquals(CacheStatistics(0, 0), cache.statistics())
    }

    @Test
    fun `invalid limits and weights fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            WeightedLruCache<String, Int>(0, 1, Int::toLong)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WeightedLruCache<String, Int>(1, 0, Int::toLong)
        }
        val cache = WeightedLruCache<String, Int>(1, 1, Int::toLong)
        assertThrows(IllegalArgumentException::class.java) { cache.put("zero", 0) }
    }
}
