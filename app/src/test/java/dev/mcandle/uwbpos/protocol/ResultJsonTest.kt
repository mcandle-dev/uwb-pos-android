package dev.mcandle.uwbpos.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** result JSON — PROTOCOL §3-2, `member_lookup.py:38-54`, `tests/test_result.py`. 바이트가 Python `json.dumps` 와 같아야 한다 */
class ResultJsonTest {

    @Test
    fun `exact success string`() {
        assertEquals("{\"v\":1,\"status\":\"success\",\"member\":\"홍*동\",\"message\":\"mock\"}", ResultJson.success("홍*동"))
    }

    @Test
    fun `not found shape`() {
        assertEquals("{\"v\":1,\"status\":\"not_found\",\"member\":null,\"message\":\"mock: unknown member\"}", ResultJson.notFound())
    }

    @Test
    fun `error shape keeps the reason`() {
        assertEquals("{\"v\":1,\"status\":\"error\",\"member\":null,\"message\":\"lookup failed: boom\"}", ResultJson.error("lookup failed: boom"))
    }

    @Test
    fun `v is the first key and there is no whitespace`() {
        val s = ResultJson.success("김**트")
        assertTrue(s.startsWith("{\"v\":1,"))
        assertFalse(s.contains(" "))
    }

    @Test
    fun `long message is cut to 40 chars then shortened until 120 bytes, member kept`() {
        val s = ResultJson.build("success", "홍*동", "가".repeat(200))
        assertTrue(ResultJson.utf8Len(s) <= 120)
        assertTrue(s.contains("\"member\":\"홍*동\""))
        val msg = Regex("\"message\":\"([^\"]*)\"").find(s)!!.groupValues[1]
        assertTrue(msg.length <= 40)
    }

    @Test
    fun `member goes null when the message alone cannot fit`() {
        val s = ResultJson.build("success", "가".repeat(60), "mock")
        assertTrue(ResultJson.utf8Len(s) <= 120)
        assertTrue(s.contains("\"member\":null"))
    }

    @Test
    fun `message key is dropped when emptied by shortening`() {
        // member 36자(108B) + 고정부 ≈ 40B → message 가 한 글자도 못 들어간다 → 키 삭제 → 그래도 넘치면 member null
        val s = ResultJson.build("success", "가".repeat(36), "x")
        assertTrue(ResultJson.utf8Len(s) <= 120)
        assertFalse(s.contains("\"message\""))
    }

    @Test
    fun `null message omits the key like python`() {
        assertEquals("{\"v\":1,\"status\":\"error\",\"member\":null}", ResultJson.build("error", null, null))
    }

    @Test
    fun `quote escapes only what json dumps escapes`() {
        assertEquals("\"a/b\"", ResultJson.quote("a/b"))
        assertEquals("\"a\\\"b\\\\c\"", ResultJson.quote("a\"b\\c"))
        assertEquals("\"한글\"", ResultJson.quote("한글"))
    }
}
