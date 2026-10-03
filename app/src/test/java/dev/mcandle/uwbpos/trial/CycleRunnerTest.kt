package dev.mcandle.uwbpos.trial

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `tests/test_cycle.py` 의 CycleRunner 부분 — 가상 시계로 일정·로그·중단·실패를 본다 */
@OptIn(ExperimentalCoroutinesApi::class)
class CycleRunnerTest {

    private class Harness(val startOk: Boolean = true, val stopThrows: Boolean = false) {
        val calls = ArrayList<String>()
        val logs = ArrayList<Pair<String, Boolean>>()
        val steps = ArrayList<CyclePlan.Step>()
        lateinit var now: () -> Long
        val runner: CycleRunner by lazy {
            CycleRunner(
                doStop = { calls += "stop"; if (stopThrows) throw IllegalStateException("adapter gone") },
                doStart = { calls += "start" },
                waitStarted = { startOk },
                onStep = { steps += it },
                log = { m, bad -> logs += m to bad },
                sleep = { delay(it) },
                monoMs = { now() },
            )
        }
    }

    @Test
    fun `runs 2n steps in order with CYCLE log lines and waits`() = runTest {
        val h = Harness(); h.now = { currentTime }
        val result = h.runner.run(CyclePlan.plan(2, 15.0, 45.0))
        assertTrue(result.completed); assertEquals(2, result.stoppedAtK)
        assertEquals(listOf("stop", "start", "stop", "start"), h.calls)
        assertEquals(listOf("1/2 stop", "1/2 start", "2/2 stop", "2/2 start"), h.logs.map { it.first })
        assertTrue(h.logs.none { it.second })
        assertEquals(2 * (15_000L + 45_000L), currentTime)
        assertEquals(4, h.steps.size)
        assertFalse(h.runner.running.value); assertNull(h.runner.progress())
    }

    @Test
    fun `progress follows the virtual clock`() = runTest {
        val h = Harness(); h.now = { currentTime }
        val job = launch { h.runner.run(CyclePlan.plan(3, 10.0, 20.0)) }
        advanceTimeBy(5_000); // 1/3 OFF, 5 s left
        var p = h.runner.progress()!!
        assertEquals(1, p.k); assertTrue(p.phaseOff); assertEquals("start", p.next); assertEquals(5.0, p.remainingS, 0.01)
        advanceTimeBy(10_000); // 1/3 ON, 15 s left
        p = h.runner.progress()!!
        assertEquals(1, p.k); assertFalse(p.phaseOff); assertEquals("stop", p.next); assertEquals(15.0, p.remainingS, 0.01)
        advanceTimeBy(20_000); // 2/3 OFF
        assertEquals(2, h.runner.progress()!!.k)
        job.cancelAndJoin()
    }

    @Test
    fun `cancel during wait returns cancelled result and stops calling`() = runTest {
        val h = Harness(); h.now = { currentTime }
        var result: CycleRunner.Result? = null
        val job = launch { result = h.runner.run(CyclePlan.plan(5, 15.0, 45.0)) }
        advanceTimeBy(20_000) // in 1/5 ON
        job.cancelAndJoin()
        assertTrue(result!!.cancelled); assertEquals(1, result!!.stoppedAtK)
        assertEquals(listOf("stop", "start"), h.calls)
        assertFalse(h.runner.running.value)
    }

    @Test
    fun `started timeout aborts with ERR and no retry`() = runTest {
        val h = Harness(startOk = false); h.now = { currentTime }
        val result = h.runner.run(CyclePlan.plan(3, 15.0, 45.0))
        assertFalse(result.completed); assertEquals(1, result.stoppedAtK)
        assertTrue(result.error!!.contains("publisher started"))
        assertEquals(listOf("stop", "start"), h.calls)
        assertEquals(true, h.logs.last().second)
        assertEquals(15_000L, currentTime) // only the OFF wait elapsed
    }

    @Test
    fun `exception in doStop becomes ERR and aborts`() = runTest {
        val h = Harness(stopThrows = true); h.now = { currentTime }
        val result = h.runner.run(CyclePlan.plan(2, 15.0, 45.0))
        assertFalse(result.completed); assertEquals(1, result.stoppedAtK)
        assertTrue(result.error!!.contains("IllegalStateException"))
        assertEquals(listOf("stop"), h.calls)
        assertEquals(0L, currentTime)
    }
}
