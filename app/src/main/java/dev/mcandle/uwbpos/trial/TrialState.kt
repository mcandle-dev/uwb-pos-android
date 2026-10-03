package dev.mcandle.uwbpos.trial

import dev.mcandle.uwbpos.log.EventsCsv

/**
 * 사이클 상태 — `pos_sim/trial_state.py`. 각 write 에 `cycle`·`adv_started_wall` 을 붙이고(FR-A6·C2), OFF 중 write 는 `late write`.
 * `adv_started_wall` 은 `onAdvertisingSetStarted`/`onAdvertisingEnabled(true)` 콜백 시각이고 OFF(stop) 에서 null 로 리셋된다.
 * 순수 상태(BLE 모름) — CycleRunner 와 PosService 가 호출한다.
 */
class TrialState {
    @Volatile var advStartedWallMs: Long? = null; private set
    @Volatile var cycle: Int? = null; private set
    @Volatile var phaseOff: Boolean = false; private set
    val rows = ArrayList<EventsCsv.CycleRow>()
    private var n: Int = 0

    fun begin(n: Int) { this.n = n; rows.clear(); cycle = null; phaseOff = false }

    fun onStop(k: Int, wallMs: Long) {
        cycle = k; phaseOff = true; advStartedWallMs = null
        rows += EventsCsv.CycleRow(k = k, stopWallMs = wallMs)
    }

    fun onStart(k: Int, wallMs: Long, onS: Int) {
        cycle = k; phaseOff = false
        row(k)?.let { rows[rows.indexOf(it)] = it.copy(onUntilWallMs = wallMs + onS * 1000L) }
    }

    fun end() { cycle = null; phaseOff = false }

    fun onAdvStarted(wallMs: Long) {
        advStartedWallMs = wallMs
        cycle?.let { k -> row(k)?.let { rows[rows.indexOf(it)] = it.copy(advStartedWallMs = wallMs) } }
    }

    fun onAdvStopped(@Suppress("UNUSED_PARAMETER") wallMs: Long) { advStartedWallMs = null }

    /** write 1건에 사이클 정보 부착 + cycles 행 갱신 */
    fun attach(r: EventsCsv.Received): EventsCsv.Received {
        val k: Int? = cycle
        val out = r.copy(cycle = k, advStartedWallMs = advStartedWallMs)
        if (k != null) row(k)?.let { row ->
            val note: String = if (phaseOff && !row.note.contains("late write")) (if (row.note.isEmpty()) "late write" else "${row.note} · late write") else row.note
            rows[rows.indexOf(row)] = row.copy(writes = row.writes + 1, firstWriteWallMs = row.firstWriteWallMs ?: r.wallMs, note = note)
        }
        return out
    }

    fun noWriteCycles(): List<Int> = rows.filter { it.writes == 0 }.map { it.k }
    fun totalWrites(): Int = rows.sumOf { it.writes }
    fun summary(stoppedAtK: Int?): String = CyclePlan.summary(stoppedAtK ?: n, n, totalWrites(), noWriteCycles())

    private fun row(k: Int): EventsCsv.CycleRow? = rows.firstOrNull { it.k == k }
}
