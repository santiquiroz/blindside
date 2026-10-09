package io.github.santiquiroz.blindside.wear.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeCacheTest {
    @Test
    fun `a list never fetched is stale`() {
        assertTrue(nodeListStale(cachedAtMs = null, nowMs = 0L, ttlMs = 30_000L))
    }

    @Test
    fun `a cached list is kept until its time to live runs out`() {
        assertFalse(nodeListStale(cachedAtMs = 1_000L, nowMs = 30_999L, ttlMs = 30_000L))
        assertTrue(nodeListStale(cachedAtMs = 1_000L, nowMs = 31_000L, ttlMs = 30_000L))
    }

    @Test
    fun `a zero time to live asks again on every tick`() {
        assertTrue(nodeListStale(cachedAtMs = 5_000L, nowMs = 5_000L, ttlMs = 0L))
    }
}
