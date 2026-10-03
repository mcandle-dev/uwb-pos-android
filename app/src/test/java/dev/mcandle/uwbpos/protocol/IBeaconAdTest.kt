package dev.mcandle.uwbpos.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 광고 바이트 — PROTOCOL §2-1·§2-2, `tests/test_codec.py:12-19`, 손님 앱 `ScanFiltersTest`. 손님 앱 필터가 보는 18B 와 같아야 한다.
 */
class IBeaconAdTest {

    private val uuid: UUID = ProtocolConstants.PROXIMITY_UUID
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `payload - major 1 minor 3 tx -59`() {
        assertArrayEquals(
            hex("02 15 94 C5 D7 26 6C 10 49 B9 87 58 04 BE 29 74 9F 20 00 01 00 03 C5"),
            IBeaconAd.payload(uuid, 1, 3, -59).getOrThrow(),
        )
    }

    @Test
    fun `first 18 bytes equal the customer app scan filter prefix`() {
        // uwb-member-app ScanFilters.iBeaconPrefix = byteArrayOf(0x02, 0x15) + uuid.toBytes()  — 그 함수를 그대로 옮긴 값과 대조
        val memberAppPrefix: ByteArray = byteArrayOf(0x02, 0x15) + uuid.toBytes()
        assertArrayEquals(memberAppPrefix, IBeaconAd.prefix(uuid))
        assertArrayEquals(memberAppPrefix, IBeaconAd.payload(uuid, 1, 257, -59).getOrThrow().copyOfRange(0, 18))
    }

    @Test
    fun `default config - minor 0x0101 is 1F 1번`() {
        val p = IBeaconAd.payload(uuid, 1, 0x0101, -59).getOrThrow()
        assertEquals(0x01.toByte(), p[20]); assertEquals(0x01.toByte(), p[21])
        assertEquals(0xC5.toByte(), p[22])
    }

    @Test
    fun `range errors are results not exceptions`() {
        assertTrue(IBeaconAd.payload(uuid, -1, 0, 0).isFailure)
        assertTrue(IBeaconAd.payload(uuid, 0x10000, 0, 0).isFailure)
        assertTrue(IBeaconAd.payload(uuid, 0, -1, 0).isFailure)
        assertTrue(IBeaconAd.payload(uuid, 0, 0, 128).isFailure)
        assertTrue(IBeaconAd.payload(uuid, 0, 0, -129).isFailure)
    }

    @Test
    fun `scan response uuid is little endian - PROTOCOL 2-2 on-air bytes`() {
        assertArrayEquals(hex("74 9F 28 A5 F9 33 E3 BA 32 42 AA 65 01 00 3C BF"), IBeaconAd.scanResponseUuidLe(ProtocolConstants.SERVICE_UUID))
        assertEquals(18, IBeaconAd.scanResponseBytes(ProtocolConstants.SERVICE_UUID).size)
    }

    @Test
    fun `fits the legacy budget with the stack flags`() {
        val p = IBeaconAd.payload(uuid, 1, 1, -59).getOrThrow()
        assertEquals(23, p.size)
        assertTrue(IBeaconAd.fitsLegacyBudget(p))
        assertEquals(30, IBeaconAd.advDataBytes(p).size)
        assertArrayEquals(hex("02 01 06 1A FF 4C 00"), IBeaconAd.advDataBytes(p).copyOfRange(0, 7))
    }
}
