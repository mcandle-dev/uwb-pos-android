package dev.mcandle.uwbpos.pos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL §3-0·§4, `tests/test_nonce.py` — 가짜 시계로 소비·만료·불일치 유지·세션 독립·재발급을 확인한다 */
class NonceStoreTest {

    private var mono = 100_000L
    private val store = NonceStore(ttlMs = 30_000L, monoMs = { mono }, wallMs = { 1_700_000_000_000L + mono })

    @Test
    fun `ok consumes the nonce`() {
        val issued = store.issue("A")
        val r = store.verify("A", issued.value)
        assertEquals(NonceStore.Verdict.OK, r.verdict)
        assertTrue(r.accepted)
        assertNull(store.peek("A"))
        assertEquals(NonceStore.Verdict.NOT_ISSUED, store.verify("A", issued.value).verdict)
    }

    @Test
    fun `expired after ttl and removed`() {
        val issued = store.issue("A")
        mono += 31_000L
        val r = store.verify("A", issued.value)
        assertEquals(NonceStore.Verdict.EXPIRED, r.verdict)
        assertEquals(31_000L, r.ageMs)
        assertNull(store.peek("A"))
        assertEquals("발급 후 31.0s (ttl 30s)", r.detail(issued.hex))
    }

    @Test
    fun `mismatch keeps the nonce so the phone can retry`() {
        val issued = store.issue("A")
        val r = store.verify("A", byteArrayOf(0, 0, 0, 0))
        assertEquals(NonceStore.Verdict.MISMATCH, r.verdict)
        assertEquals(issued, store.peek("A"))
        assertEquals("기대 ${issued.hex}, 수신 00000000", r.detail("00000000"))
        assertEquals(NonceStore.Verdict.OK, store.verify("A", issued.value).verdict)
    }

    @Test
    fun `sessions are independent`() {
        val a = store.issue("A")
        val b = store.issue("B")
        assertNotEquals(a.hex, b.hex)
        assertEquals(NonceStore.Verdict.OK, store.verify("B", b.value).verdict)
        assertEquals(NonceStore.Verdict.OK, store.verify("A", a.value).verdict)
    }

    @Test
    fun `reissue overwrites`() {
        val first = store.issue("A")
        val second = store.issue("A")
        assertEquals(NonceStore.Verdict.MISMATCH, store.verify("A", first.value).verdict)
        assertEquals(NonceStore.Verdict.OK, store.verify("A", second.value).verdict)
        assertEquals(2, store.issuedCount)
    }

    @Test
    fun `not issued detail and drop`() {
        assertEquals("이 연결에서 nonce를 READ한 적이 없음", store.verify("Z", byteArrayOf(1, 2, 3, 4)).detail("01020304"))
        store.issue("A"); store.drop("A")
        assertNull(store.peek("A"))
    }

    @Test
    fun `ok detail uses three decimals`() {
        val issued = store.issue("A")
        mono += 62L
        assertEquals("nonce 일치 (발급 후 0.062s)", store.verify("A", issued.value).detail(issued.hex))
        assertFalse(store.verify("A", issued.value).accepted)
    }
}
