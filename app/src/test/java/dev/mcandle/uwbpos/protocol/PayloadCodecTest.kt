package dev.mcandle.uwbpos.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * payload 16B 해석 — PROTOCOL §3-1, `codec.py:152-177`. 벡터는 손님 앱 `PayloadTest`(조립 쪽)와 시뮬레이터 `tests/test_codec.py`·
 * `tests/test_events_csv.py:39`(실폰 행)에서 가져왔다 — 두 리포가 같은 바이트를 보는지가 이 테스트의 목적이다.
 */
class PayloadCodecTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `spec example decodes - member 0123456789, uwb 43 33, nonce 1851485F`() {
        val d = PayloadCodec.decode(hex("01 01 23 45 67 89 43 33 18 51 48 5F 00 00 00 00")) as PayloadCodec.Decode.Ok
        assertEquals(1, d.version)
        assertEquals("0123456789", d.memberId)
        assertEquals("43:33", d.uwbStr)
        assertEquals("1851485F", d.nonceHex)
        assertArrayEquals(ByteArray(4), d.reserved)
    }

    @Test
    fun `real phone row from 2026-09-21 decodes`() {
        // tests/test_events_csv.py RAW
        val d = PayloadCodec.decode(hex("011111222200E6F1C1F376E100000000")) as PayloadCodec.Decode.Ok
        assertEquals("1111222200", d.memberId)
        assertEquals("E6:F1", d.uwbStr)
        assertEquals("C1F376E1", d.nonceHex)
    }

    @Test
    fun `uwb 0000 means unsupported`() {
        val d = PayloadCodec.decode(hex("01 01 23 45 67 89 00 00 18 51 48 5F 00 00 00 00")) as PayloadCodec.Decode.Ok
        assertEquals("미지원", d.uwbStr)
    }

    @Test
    fun `length error comes first`() {
        val m = PayloadCodec.decode(hex("01 01 23 45 67")) as PayloadCodec.Decode.Malformed
        assertEquals(PayloadCodec.ErrorKind.LENGTH, m.kind)
        assertEquals("길이 오류", m.kind.label)
        assertTrue(m.detail.contains("5B"))
    }

    @Test
    fun `version error before bcd`() {
        val m = PayloadCodec.decode(hex("07 AB CD EF 01 23 43 33 18 51 48 5F 00 00 00 00")) as PayloadCodec.Decode.Malformed
        assertEquals(PayloadCodec.ErrorKind.VERSION, m.kind)
        assertEquals("버전 오류", m.kind.label)
    }

    @Test
    fun `bcd nibble above 9 is rejected`() {
        val m = PayloadCodec.decode(hex("01 01 23 AB 67 89 43 33 18 51 48 5F 00 00 00 00")) as PayloadCodec.Decode.Malformed
        assertEquals(PayloadCodec.ErrorKind.BCD, m.kind)
        assertEquals("BCD 오류", m.kind.label)
    }

    @Test
    fun `bcd round trip`() {
        assertArrayEquals(hex("98 76 54 32 10"), PayloadCodec.bcdEncode("9876543210"))
        assertEquals("9876543210", PayloadCodec.bcdDecode(hex("98 76 54 32 10")))
        assertNull(PayloadCodec.bcdDecode(hex("9A")))
    }

    @Test
    fun `uwb bytes are shown in received order`() {
        assertEquals("0A:3F", PayloadCodec.uwbStr(byteArrayOf(0x0A, 0x3F)))
    }
}
