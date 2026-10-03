package dev.mcandle.uwbpos.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** `tests/test_events_csv.py` 대응 — 헤더 앞 12열 글자 일치, 17칸, 빈칸 규칙, 소수 3자리, BOM·CRLF */
class EventsCsvTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val t: Long = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).parse("2026-09-21T19:22:27.192")!!.time
    private val raw = hex("011111222200E6F1C1F376E100000000")

    private fun received(
        advStarted: Long? = null, cycle: Int? = null, verdict: String = "OK", accepted: Boolean = true,
    ) = EventsCsv.Received(
        wallMs = t, sessionLabel = "N1", sessionKey = "k", raw = raw, verdict = verdict, accepted = accepted,
        memberId = "1111222200", uwbAddr = "E6:F1", nonceHex = "C1F376E1", issuedNonceHex = "C1F376E1",
        issuedWallMs = t - 123, nonceAgeMs = 123, elapsedMs = 289, resultJson = "{\"v\":1}", detail = "nonce 일치",
        sessionOpenedWallMs = t - 289, advStartedWallMs = advStarted, cycle = cycle,
    )

    @Test
    fun `header keeps the 12 columns of 001 then appends 5`() {
        assertEquals(
            "time,session,member_id,uwb,nonce,verdict,issued_nonce,nonce_age_s,elapsed_s,result,raw_hex,detail",
            EventsCsv.HEADER_001.joinToString(","),
        )
        assertEquals(listOf("adv_started_at", "session_opened_at", "nonce_issued_at", "cycle", "adv_to_write_s"), EventsCsv.EXTRA_002)
        assertEquals(17, EventsCsv.HEADER.size)
        assertEquals(
            "time,session,member_id,uwb,nonce,verdict,issued_nonce,nonce_age_s,elapsed_s,result,raw_hex,detail,adv_started_at,session_opened_at,nonce_issued_at,cycle,adv_to_write_s",
            EventsCsv.HEADER.joinToString(","),
        )
    }

    @Test
    fun `row has 17 cells and the first 12 match 001 formatting`() {
        val row = EventsCsv.row(received())
        assertEquals(17, row.size)
        assertEquals("2026-09-21T19:22:27.192", row[0])
        assertEquals("N1", row[1]); assertEquals("1111222200", row[2]); assertEquals("E6:F1", row[3])
        assertEquals("C1F376E1", row[4]); assertEquals("OK", row[5]); assertEquals("C1F376E1", row[6])
        assertEquals("0.123", row[7]); assertEquals("0.289", row[8]); assertEquals("{\"v\":1}", row[9])
        assertEquals("01 11 11 22 22 00 E6 F1 C1 F3 76 E1 00 00 00 00", row[10]); assertEquals("nonce 일치", row[11])
    }

    @Test
    fun `manual write leaves adv and cycle cells empty`() {
        val row = EventsCsv.row(received())
        assertEquals("", row[12]); assertEquals("2026-09-21T19:22:26.903", row[13]); assertEquals("2026-09-21T19:22:27.069", row[14])
        assertEquals("", row[15]); assertEquals("", row[16])
    }

    @Test
    fun `cycle write fills adv_started_at cycle and adv_to_write_s`() {
        val row = EventsCsv.row(received(advStarted = t - 12_346, cycle = 3))
        assertEquals("2026-09-21T19:22:14.846", row[12]); assertEquals("3", row[15]); assertEquals("12.346", row[16])
    }

    @Test
    fun `rejected write has empty member and elapsed`() {
        val r = received(verdict = "불일치", accepted = false).copy(memberId = null, uwbAddr = null, elapsedMs = null, resultJson = null, detail = "기대 AA, 수신 BB")
        val row = EventsCsv.row(r)
        assertEquals("", row[2]); assertEquals("불일치", row[5]); assertEquals("", row[8]); assertEquals("", row[9])
    }

    @Test
    fun `serialize starts with a BOM and uses CRLF`() {
        val s = EventsCsv.serializeEvents(listOf(received()))
        assertEquals('\uFEFF', s[0])
        assertTrue(s.startsWith("\uFEFFtime,session,"))
        assertTrue(s.contains("\r\n2026-09-21T19:22:27.192,N1,"))
        assertTrue(s.endsWith("\r\n"))
    }

    @Test
    fun `cells with commas or quotes are quoted like csv QUOTE_MINIMAL`() {
        assertEquals("\"{\"\"v\"\":1,\"\"status\"\":\"\"success\"\"}\"", EventsCsv.cell("{\"v\":1,\"status\":\"success\"}"))
        assertEquals("nonce 일치 (발급 후 0.062s)", EventsCsv.cell("nonce 일치 (발급 후 0.062s)"))
    }

    @Test
    fun `cycles row and header`() {
        assertEquals("cycle,stop_at,adv_started_at,on_until,writes,first_write_at,adv_to_first_write_s,note", EventsCsv.CYCLES_HEADER.joinToString(","))
        val row = EventsCsv.cycleRow(EventsCsv.CycleRow(k = 2, stopWallMs = t, advStartedWallMs = t + 15_000, onUntilWallMs = t + 60_000, writes = 1, firstWriteWallMs = t + 19_300, note = "late write"))
        assertEquals("2", row[0]); assertEquals("4.300", row[6]); assertEquals("late write", row[7])
    }

    @Test
    fun `activity line format matches the simulator`() {
        val line = ActivityLine(t, ActivityLine.Cat.ADV, null, "publisher started").text()
        assertEquals("19:22:27.192  ADV   —   publisher started", line)
        assertEquals("19:22:27.192  NONCE A1  read → C1F376E1 (ttl 30s)", ActivityLine(t, ActivityLine.Cat.NONCE, "A1", "read → C1F376E1 (ttl 30s)").text())
    }
}
