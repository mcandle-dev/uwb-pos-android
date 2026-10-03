package dev.mcandle.uwbpos.trial

import java.util.Locale

/**
 * 광고 사이클 일정 — `pos_sim/cycle.py` 의 `plan`·`progress`·`format_remaining` 을 옮겼다. 순수 함수.
 * `stop → OFF 대기 → start → ON 대기` 를 N 회. 실행은 `CycleRunner` (BLE 주입). 예외는 ERR 뒤 **중단**, 재시도 없음 (constitution §5).
 */
object CyclePlan {

    const val DEFAULT_N: Int = 10
    const val DEFAULT_OFF_S: Int = 15   // 폰 STICKY 가 MATCH_LOST 를 내기에 충분 (손님 앱 002 §F, 실측 ≈10초)
    const val DEFAULT_ON_S: Int = 45    // OFF + ON ≥ 60 — 손님 앱 60초 억제를 피한다
    const val SUPPRESSION_S: Int = 60   // PROTOCOL §4-1
    const val STARTED_TIMEOUT_MS: Long = 10_000L

    enum class Action { STOP, START }

    data class Step(val k: Int, val action: Action, val waitS: Double)

    data class Progress(val k: Int, val n: Int, val phaseOff: Boolean, val next: String, val remainingS: Double) {
        /** `사이클 k/N · 광고 OFF · 다음 start -0:12` */
        fun text(): String {
            val nxt = if (next == "done") "완료" else "다음 $next"
            return "사이클 $k/$n · 광고 ${if (phaseOff) "OFF" else "ON"} · $nxt ${formatRemaining(remainingS)}"
        }
    }

    /** 2n 단계. 홀수 번째 stop(OFF 대기), 짝수 번째 start(ON 대기) */
    fun plan(n: Int, offS: Double, onS: Double): List<Step> {
        require(n >= 1) { "사이클 횟수는 1 이상" }
        require(offS >= 0 && onS > 0) { "OFF는 0 이상, ON은 0보다 커야 함" }
        return (1..n).flatMap { k -> listOf(Step(k, Action.STOP, offS), Step(k, Action.START, onS)) }
    }

    /** 일정 시작 후 [elapsedS] 시점의 진행. 끝났으면 null */
    fun progress(steps: List<Step>, elapsedS: Double): Progress? {
        if (steps.isEmpty()) return null
        val n: Int = steps.last().k
        var t = 0.0
        for ((i, s) in steps.withIndex()) {
            val end = t + s.waitS
            if (elapsedS < end) {
                val next: String = if (i == steps.lastIndex) "done" else steps[i + 1].action.name.lowercase()
                return Progress(s.k, n, s.action == Action.STOP, next, end - elapsedS)
            }
            t = end
        }
        return null
    }

    /** `-M:SS` */
    fun formatRemaining(s: Double): String {
        val total: Int = kotlin.math.ceil(s).toInt().coerceAtLeast(0)
        return String.format(Locale.US, "-%d:%02d", total / 60, total % 60)
    }

    /** OFF + ON < 60 이면 손님 앱 억제 창 안에서 다음 진입이 와 "억제" 만 남는다 — 경고 (중단은 아님) */
    fun belowSuppression(offS: Int, onS: Int): Boolean = offS + onS < SUPPRESSION_S

    /** `CYCLE done k/N · writes W · no-write [..]` */
    fun summary(k: Int, n: Int, writes: Int, noWrite: List<Int>): String =
        "CYCLE done $k/$n · writes $writes · no-write ${noWrite.joinToString(",", "[", "]")}"
}
