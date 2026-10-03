package dev.mcandle.uwbpos.trial

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 광고 사이클 실행기 — `pos_sim/cycle.py CycleRunner`. [CyclePlan.plan] 의 일정을 따라 `doStop`/`doStart` 를 부르고 대기한다.
 * BLE 를 모른다: `doStop`·`doStart`·`waitStarted`·`now` 를 주입받아 JVM 에서 가짜 시계로 테스트한다 (`CycleRunnerTest`).
 *
 * 원칙 (constitution §5): 실패는 `ERR …` 로그 뒤 **중단**. 재시도하지 않는다 — 시험 도구가 조건을 바꿔 가며 살리면 실측이 왜곡된다.
 * 로그 줄 `CYCLE k/N stop|start` 는 상대 `pair_logs.py` 가 센다 — 형식을 바꾸지 말 것.
 */
class CycleRunner(
    private val doStop: suspend () -> Unit,
    private val doStart: suspend () -> Unit,
    /** `doStart` 뒤 `publisher started` 가 [CyclePlan.STARTED_TIMEOUT_MS] 안에 왔는가. null 이면 안 기다림 */
    private val waitStarted: (suspend () -> Boolean)? = null,
    /** 각 단계 **직전** 훅 — [TrialState] 가 stop/start 시각을 적는 자리 */
    private val onStep: (CyclePlan.Step) -> Unit = {},
    /** `CYCLE …` 한 줄. bad = ERR */
    private val log: (msg: String, bad: Boolean) -> Unit,
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    data class Result(val completed: Boolean, val stoppedAtK: Int?, val error: String? = null, val cancelled: Boolean = false)

    private var steps: List<CyclePlan.Step> = emptyList()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var doneWaitMs: Long = 0L
    private var waitBeganMs: Long? = null

    /** 일정상 흐른 시간 — 대기만 합산 (do_* 실행·started 대기는 제외해 [CyclePlan.progress] 와 맞춘다) */
    fun elapsedS(): Double {
        val cur: Long = waitBeganMs?.let { monoMs() - it } ?: 0L
        return (doneWaitMs + cur) / 1000.0
    }

    /** ① 패널 진행 줄. 돌고 있지 않으면 null */
    fun progress(): CyclePlan.Progress? = if (_running.value) CyclePlan.progress(steps, elapsedS()) else null

    /**
     * 일정을 끝까지(또는 중단·실패까지) 돈다. 호출한 코루틴을 cancel 하면 [Result.cancelled] 로 끝난다.
     * 호출자가 끝난 뒤 [TrialState.end]·저장·요약을 한다.
     */
    suspend fun run(plan: List<CyclePlan.Step>): Result {
        steps = plan
        _running.value = true
        doneWaitMs = 0L
        waitBeganMs = null
        val n: Int = plan.lastOrNull()?.k ?: 0
        var k: Int? = null
        try {
            for (step in plan) {
                k = step.k
                onStep(step)
                val action: String = step.action.name.lowercase()
                log("${step.k}/$n $action", false)   // Activity Log: `CYCLE —   k/N stop|start`
                val failed: String? = runCatching {
                    if (step.action == CyclePlan.Action.STOP) {
                        doStop(); null
                    } else {
                        doStart()
                        if (waitStarted != null && !waitStarted.invoke()) {
                            "CYCLE ${step.k}/$n: publisher started 콜백이 ${CyclePlan.STARTED_TIMEOUT_MS / 1000}s 안에 오지 않음 — 중단"
                        } else null
                    }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    "CYCLE ${step.k}/$n $action 실패: ${e.javaClass.simpleName}: ${e.message} — 중단"
                }
                if (failed != null) { log(failed, true); return Result(false, k, error = failed) }
                wait((step.waitS * 1000).toLong())
            }
            return Result(true, k)
        } catch (e: CancellationException) {
            return Result(false, k, cancelled = true)
        } finally {
            _running.value = false
            waitBeganMs = null
        }
    }

    private suspend fun wait(ms: Long) {
        waitBeganMs = monoMs()
        try {
            sleep(ms)
        } finally {
            waitBeganMs = null
            doneWaitMs += ms
        }
    }
}
