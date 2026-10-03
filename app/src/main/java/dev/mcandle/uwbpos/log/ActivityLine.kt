package dev.mcandle.uwbpos.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity Log 한 줄 — 시뮬레이터 `ui.py:57-58 LogLine.text` 와 같은 꼴: `HH:MM:SS.mmm  {cat:<5} {sid:<3} {msg}`.
 * `pos_*.txt` 로 저장되고 상대 `tools/pair_logs.py` 가 `publisher started`·`CYCLE k/N stop|start` 를 센다. 순수 값.
 */
data class ActivityLine(val wallMs: Long, val cat: Cat, val sid: String?, val msg: String, val bad: Boolean = false) {

    enum class Cat { ADV, GATT, CONN, NONCE, LOOK, ERR, CYCLE, EVENTS }

    fun text(): String = String.format(
        Locale.US, "%s  %-5s %-3s %s",
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(wallMs)), cat.name, sid ?: NO_SESSION, msg,
    )

    companion object {
        /** 세션이 없는 줄의 sid 자리 (`ui.py`: `—`) */
        const val NO_SESSION: String = "—"
    }
}
