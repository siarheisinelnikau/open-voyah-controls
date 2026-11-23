package org.openvoyah.controls.data.frida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProofCacheTest {
    private val cache = ProofCache<Any>()
    private var loads = 0
    private fun load(): Any = Any().also { loads++ }

    @Test fun theProofIsLoadedOnceForEveryCaller() {
        val first = cache.get(::load)
        assertSame(first, cache.get(::load))
        assertEquals(1, loads)
    }

    @Test fun aRefusalDropsTheProofItWasRunWith() {
        val first = cache.get(::load)
        cache.invalidate(first)
        val second = cache.get(::load)
        assertEquals(2, loads)
        // A late refusal of the old proof must not drop the new one.
        cache.invalidate(first)
        assertSame(second, cache.get(::load))
        cache.invalidate()
        cache.get(::load)
        assertEquals(3, loads)
    }

    @Test fun aFailedLoadCachesNothing() {
        runCatching { cache.get { throw IllegalStateException("refused") } }
        cache.get(::load)
        assertEquals(1, loads)
    }
}
