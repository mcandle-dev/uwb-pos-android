package dev.mcandle.uwbpos.trial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `tests/test_cycle.py` 대응 — 2n 단계, 진행 표시, 억제 경고 */
class CyclePlanTest {

    @Test
    fun `plan has 2n steps alternating stop start`() {
        val steps = CyclePlan.plan(3, 15.0, 45.0)
        assertEquals(6, steps.size)
        assertEquals(CyclePlan.Step(1, CyclePlan.Action.STOP, 15.0), steps[0])
        assertEquals(CyclePlan.Step(1, CyclePlan.Action.START, 45.0), steps[1])
        assertEquals(CyclePlan.Step(3, CyclePlan.Action.START, 45.0), steps[5])
    }

    @Test
    fun `invalid arguments throw`() {
        runCatching { CyclePlan.plan(0, 15.0, 45.0) }.let { assertTrue(it.isFailure) }
        runCatching { CyclePlan.plan(1, -1.0, 45.0) }.let { assertTrue(it.isFailure) }
        runCatching { CyclePlan.plan(1, 15.0, 0.0) }.let { assertTrue(it.isFailure) }
    }

    @Test
    fun `progress walks through phases and ends with null`() {
        val steps = CyclePlan.plan(2, 15.0, 45.0)
        val p0 = CyclePlan.progress(steps, 3.0)!!
        assertEquals(1, p0.k); assertTrue(p0.phaseOff); assertEquals("start", p0.next); assertEquals(12.0, p0.remainingS, 1e-9)
        assertEquals("사이클 1/2 · 광고 OFF · 다음 start -0:12", p0.text())
        val p1 = CyclePlan.progress(steps, 20.0)!!
        assertEquals(1, p1.k); assertFalse(p1.phaseOff); assertEquals("stop", p1.next)
        val last = CyclePlan.progress(steps, 119.0)!!
        assertEquals(2, last.k); assertEquals("done", last.next); assertEquals("사이클 2/2 · 광고 ON · 완료 -0:01", last.text())
        assertNull(CyclePlan.progress(steps, 120.0))
    }

    @Test
    fun `suppression warning and summary`() {
        assertTrue(CyclePlan.belowSuppression(15, 30))
        assertFalse(CyclePlan.belowSuppression(15, 45))
        assertEquals("CYCLE done 10/10 · writes 8 · no-write [3,7]", CyclePlan.summary(10, 10, 8, listOf(3, 7)))
        assertEquals("-1:05", CyclePlan.formatRemaining(65.0))
    }
}
