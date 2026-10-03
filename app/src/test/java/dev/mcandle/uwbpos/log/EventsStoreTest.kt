package dev.mcandle.uwbpos.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * 실기기(2026-10-03)에서 잡힌 두 결함의 회귀 테스트:
 * 1. `Received.equals` 재정의 → StateFlow 가 replace 결과를 버려 `result` 열이 영원히 빈칸
 * 2. replace 가 행 전체를 바꿔 `TrialState.attach` 가 붙인 `advStartedWallMs`·`cycle` 이 사라짐
 */
class EventsStoreTest {

    private fun received(result: String? = null, adv: Long? = null, cycle: Int? = null) = EventsCsv.Received(
        wallMs = 1_000L, sessionLabel = "A1", sessionKey = "AA:BB", raw = ByteArray(16), verdict = "OK", accepted = true,
        memberId = "1111222200", resultJson = result, advStartedWallMs = adv, cycle = cycle,
    )

    @Test
    fun `replace fills resultJson and keeps attached cycle fields`() {
        val store = EventsStore(ActivityLog())
        val before = store.events.value
        store.add(received(adv = 500L, cycle = 3))
        store.replace(received(result = "{\"v\":1}"))
        val row = store.events.value.single()
        assertEquals("{\"v\":1}", row.resultJson)
        assertEquals(500L, row.advStartedWallMs)
        assertEquals(3, row.cycle)
        assertNotSame(before, store.events.value)
    }

    @Test
    fun `received with different resultJson is not equal - StateFlow must emit`() {
        assert(received(result = null) != received(result = "{}"))
    }

    @Test
    fun `clear leaves one activity line`() {
        val log = ActivityLog()
        val store = EventsStore(log)
        store.add(received()); store.add(received().copy(wallMs = 2_000L))
        store.clear()
        assertEquals(0, store.events.value.size)
        assertEquals("cleared 2 rows", log.lines.value.last().msg)
        assertEquals(ActivityLine.Cat.EVENTS, log.lines.value.last().cat)
    }
}
