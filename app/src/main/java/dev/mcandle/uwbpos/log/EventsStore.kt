package dev.mcandle.uwbpos.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * ③ 수신 이벤트 목록 — `events_*.csv` 의 원천. 시간순으로 쌓고 화면은 뒤집어 보여준다.
 * **지우기는 Activity Log 에 1행 남긴다** (시뮬레이터 FAQ Q13 — 거기서는 흔적 없이 지워져 세션이 사라진 것처럼 보였다).
 */
class EventsStore(private val log: ActivityLog) {

    private val _events = MutableStateFlow<List<EventsCsv.Received>>(emptyList())
    val events: StateFlow<List<EventsCsv.Received>> = _events

    fun add(r: EventsCsv.Received) { _events.update { it + r } }

    /**
     * 조회 결과가 뒤늦게 채워진다 — 같은 (wallMs, sessionKey) 행에 `resultJson` 만 병합한다.
     * 행 전체를 바꾸면 `TrialState.attach` 가 붙인 `cycle`·`advStartedWallMs` 가 사라진다 (2026-10-03 실기기: `adv_started_at` 빈칸).
     */
    fun replace(r: EventsCsv.Received) {
        _events.update { list -> list.map { if (it.wallMs == r.wallMs && it.sessionKey == r.sessionKey) it.copy(resultJson = r.resultJson) else it } }
    }

    fun clear() {
        val n: Int = _events.value.size
        _events.value = emptyList()
        log.add(ActivityLine.Cat.EVENTS, null, "cleared $n rows", false)
    }

    fun csv(): String = EventsCsv.serializeEvents(_events.value)
}
