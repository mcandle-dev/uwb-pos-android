package dev.mcandle.uwbpos.pos

import dev.mcandle.uwbpos.protocol.ProtocolConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** spec 001 FR-B5~B7 — 평가 순서·응답 코드·MTU 가드·long read, BLE 없이 */
class PosEngineTest {

    private var mono = 10_000L
    private val wall = { 1_700_000_000_000L + mono }
    private val sessions = SessionRegistry(wall, { mono })
    private val nonces = NonceStore(ttlMs = 30_000L, monoMs = { mono }, wallMs = wall)
    private val engine = PosEngine(sessions, nonces, MockLookup(delayMs = 0), wall)

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun payload(memberBcd: String, uwb: String, nonce: ByteArray = ByteArray(4)): ByteArray =
        hex("01 $memberBcd $uwb") + nonce + ByteArray(4)

    @Test
    fun `happy path - connect, nonce, write accepted with status 0`() {
        sessions.open("AA")
        val issued = engine.nonceIssued("AA")
        mono += 62
        val e = engine.evaluateWrite("AA", payload("11 11 22 22 00", "E6 F1"), issued.value, mono)
        assertEquals(0, e.status)
        assertTrue(e.received.accepted)
        assertEquals("OK", e.received.verdict)
        assertEquals("1111222200", e.received.memberId)
        assertEquals("E6:F1", e.received.uwbAddr)
        assertEquals(62L, e.received.elapsedMs)
        assertEquals("nonce 일치 (발급 후 0.062s)", e.received.detail)
        assertEquals("A1", e.received.sessionLabel)
        assertEquals("1111222200", sessions.get("AA")?.memberId)
    }

    private fun PosEngine.evaluateWrite(key: String, head: ByteArray, nonce: ByteArray, mono: Long) =
        evaluateWrite(key, head.copyOfRange(0, 8) + nonce + ByteArray(4), mono)

    @Test
    fun `malformed payload is 0x81 regardless of nonce`() {
        sessions.open("AA"); engine.nonceIssued("AA")
        val e = engine.evaluateWrite("AA", hex("01 01 23 45 67"), mono)
        assertEquals(ProtocolConstants.WRITE_ERR_PAYLOAD_MALFORMED, e.status)
        assertEquals("길이 오류", e.received.verdict)
        assertFalse(e.received.accepted)
        assertNull(e.received.memberId)
    }

    @Test
    fun `not issued, mismatch and expired are 0x80`() {
        sessions.open("AA")
        val none = engine.evaluateWrite("AA", payload("01 23 45 67 89", "43 33"), byteArrayOf(1, 2, 3, 4), mono)
        assertEquals(ProtocolConstants.WRITE_ERR_NONCE_REJECTED, none.status); assertEquals("미발급", none.received.verdict)

        val issued = engine.nonceIssued("AA")
        val mis = engine.evaluateWrite("AA", payload("01 23 45 67 89", "43 33"), byteArrayOf(0, 0, 0, 0), mono)
        assertEquals(0x80, mis.status); assertEquals("불일치", mis.received.verdict)
        assertEquals(issued.hex, mis.received.issuedNonceHex)

        mono += 31_000
        val exp = engine.evaluateWrite("AA", payload("01 23 45 67 89", "43 33"), issued.value, mono)
        assertEquals(0x80, exp.status); assertEquals("만료", exp.received.verdict)
    }

    @Test
    fun `sessions are isolated - B cannot use A's nonce`() {
        sessions.open("AA"); sessions.open("BB")
        val a = engine.nonceIssued("AA")
        val e = engine.evaluateWrite("BB", payload("01 23 45 67 89", "43 33"), a.value, mono)
        assertEquals(0x80, e.status); assertEquals("미발급", e.received.verdict); assertEquals("B1", e.received.sessionLabel)
        assertEquals(0, engine.evaluateWrite("AA", payload("01 23 45 67 89", "43 33"), a.value, mono).status)
    }

    @Test
    fun `lookup result stored and notify guarded by mtu`() = runTest {
        sessions.open("AA")
        val looked = engine.afterAccept("AA", "1111222200") { mono }
        assertEquals("{\"v\":1,\"status\":\"success\",\"member\":\"김**트\",\"message\":\"mock\"}", looked.resultJson)
        val s = sessions.get("AA")!!
        assertEquals(looked.resultJson, s.resultJson)
        assertEquals(PosEngine.NotifyDecision.NotSubscribed, engine.shouldNotify(s, looked.resultJson))
        val sub = sessions.update("AA") { it.copy(subscribed = true) }!!
        assertTrue(engine.shouldNotify(sub, looked.resultJson) is PosEngine.NotifyDecision.TooLarge) // mtu 23 → 20B 한도
        val big = sessions.update("AA") { it.copy(mtu = 185) }!!
        assertEquals(PosEngine.NotifyDecision.Send, engine.shouldNotify(big, looked.resultJson))
    }

    @Test
    fun `result long read from offset and invalid offset`() = runTest {
        sessions.open("AA")
        engine.afterAccept("AA", "0123456789") { mono }
        val s = sessions.get("AA")!!
        val all = engine.resultBytes(s, 0)!!
        assertEquals(s.resultJson, String(all, Charsets.UTF_8))
        assertEquals(all.size - 20, engine.resultBytes(s, 20)!!.size)
        assertEquals(0, engine.resultBytes(s, all.size)!!.size)
        assertNull(engine.resultBytes(s, all.size + 1))
        assertEquals(0, engine.resultBytes(null, 0)!!.size)
    }

    @Test
    fun `disconnect drops nonce and session`() {
        sessions.open("AA"); val issued = engine.nonceIssued("AA")
        assertEquals("A1", engine.closeSession("AA")?.label)
        assertNull(sessions.get("AA"))
        assertNull(nonces.peek("AA"))
        assertNull(engine.closeSession("AA"))
        // 재연결은 새 라벨
        assertEquals("B1", sessions.open("AA").label)
        assertEquals("미발급", engine.evaluateWrite("AA", payload("01 23 45 67 89", "43 33"), issued.value, mono).received.verdict)
    }

    @Test
    fun `forced status debug toggle overrides the verdict`() {
        var force: Int? = 0x81
        val eng = PosEngine(sessions, nonces, MockLookup(0), wall, forceStatus = { force })
        sessions.open("AA"); val issued = eng.nonceIssued("AA")
        assertEquals(0x81, eng.evaluateWrite("AA", payload("01 23 45 67 89", "43 33"), issued.value, mono).status)
        force = null
    }
}
