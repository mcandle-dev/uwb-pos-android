package dev.mcandle.uwbpos.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Activity Log — 시뮬레이터 `pos_*.txt` 와 같은 줄 형식 (`ActivityLine`). 메모리 [MAX_LINES] 줄, 화면은 뒤 400줄만 보여도 된다.
 * 저장은 `LogExport` 가 `text()` 를 이어 붙인다. 프로세스 단위(App 보관).
 */
class ActivityLog(private val maxLines: Int = MAX_LINES) {

    private val _lines = MutableStateFlow<List<ActivityLine>>(emptyList())
    val lines: StateFlow<List<ActivityLine>> = _lines

    fun add(cat: ActivityLine.Cat, sid: String?, msg: String, bad: Boolean = false) {
        val line = ActivityLine(System.currentTimeMillis(), cat, sid, msg, bad)
        _lines.update { (it + line).takeLast(maxLines) }
        if (bad) Log.w(TAG, line.text()) else Log.i(TAG, line.text())
    }

    fun text(): String = _lines.value.joinToString("\n") { it.text() } + "\n"

    companion object {
        const val MAX_LINES: Int = 2000
        private const val TAG: String = "PosActivity"
    }
}
