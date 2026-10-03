package dev.mcandle.uwbpos.pos

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAndLookupTest {

    @Test
    fun `session labels follow A1 to Z1 then A2`() {
        assertEquals("A1", SessionLabel.of(0))
        assertEquals("B1", SessionLabel.of(1))
        assertEquals("Z1", SessionLabel.of(25))
        assertEquals("A2", SessionLabel.of(26))
        assertEquals("N1", SessionLabel.of(13))
    }

    @Test
    fun `mask follows member_lookup py`() {
        assertEquals("홍*동", MockLookup.mask("홍길동"))
        assertEquals("김**트", MockLookup.mask("김테스트"))
        assertEquals("김*", MockLookup.mask("김영"))
        assertEquals("X", MockLookup.mask("X"))
    }

    @Test
    fun `lookup returns the same json as the simulator`() = runTest {
        val lookup = MockLookup(delayMs = 0)
        assertEquals("{\"v\":1,\"status\":\"success\",\"member\":\"김**트\",\"message\":\"mock\"}", lookup.lookup("1111222200"))
        assertEquals("{\"v\":1,\"status\":\"not_found\",\"member\":null,\"message\":\"mock: unknown member\"}", lookup.lookup("0000000000"))
    }

    @Test
    fun `force not found debug toggle`() = runTest {
        val lookup = MockLookup(delayMs = 0, forceNotFound = { true })
        assertEquals("not_found", Regex("\"status\":\"([a-z_]+)\"").find(lookup.lookup("0123456789"))!!.groupValues[1])
    }
}
