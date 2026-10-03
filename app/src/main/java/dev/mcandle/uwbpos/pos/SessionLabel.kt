package dev.mcandle.uwbpos.pos

/** 연결 순서 → `A1, B1, … Z1, A2 …` — 시뮬레이터 `gatt_server.py:73-75 _label` 과 같다. ② 패널·Activity Log·events CSV `session` 열 */
object SessionLabel {
    fun of(n: Int): String {
        require(n >= 0) { "n" }
        return "${'A' + (n % 26)}${n / 26 + 1}"
    }
}
