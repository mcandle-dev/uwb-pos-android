package dev.mcandle.uwbpos.log

import dev.mcandle.uwbpos.protocol.toHexSpaced
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `events_*.csv` · `cycles_*.csv` 행 조립 — `pos_sim/events_csv.py` 를 글자 단위로 옮겼다. 순수 함수.
 *
 * 헤더 앞 12열은 시뮬레이터 001 과 **글자까지 같다** — 상대 `pairlog.parse_events` 가 `header[:12] == HEADER_001` 을 검사한다.
 * 형식: 시각은 로컬 ISO ms(`2026-10-03T13:48:41.126`), 숫자는 소수 3자리(`Locale.US`), 값이 없으면 빈칸,
 * 파일은 utf-8-sig(BOM) + RFC 4180(필요할 때만 따옴표, `"` 는 `""`, 줄 끝 CRLF) — Python `csv.writer` 기본과 같다.
 *
 * 열 의미 (`events_csv.py:3-13`): `adv_started_at` = 최근 광고 시작 **콜백** 시각(OFF 중이면 빈칸), `session_opened_at` = 세션 시작
 * (이 앱은 **연결 시각**, D-003), `nonce_issued_at` = 발급 시각, `cycle` = 사이클 번호(수동이면 빈칸), `adv_to_write_s` = time − adv_started_at.
 * `elapsed_s` 는 연결 → write 수락 — 깨어남 지연이 **아니다** (시뮬레이터 FAQ Q12).
 */
object EventsCsv {

    val HEADER_001: List<String> = listOf(
        "time", "session", "member_id", "uwb", "nonce", "verdict", "issued_nonce", "nonce_age_s",
        "elapsed_s", "result", "raw_hex", "detail",
    )
    val EXTRA_002: List<String> = listOf("adv_started_at", "session_opened_at", "nonce_issued_at", "cycle", "adv_to_write_s")
    val HEADER: List<String> = HEADER_001 + EXTRA_002

    val CYCLES_HEADER: List<String> = listOf(
        "cycle", "stop_at", "adv_started_at", "on_until", "writes", "first_write_at", "adv_to_first_write_s", "note",
    )

    /** payload write 한 건의 결과 — ③ 패널 한 행 = CSV 한 행 (`gatt_server.py:103-124 Received`) */
    data class Received(
        val wallMs: Long,
        val sessionLabel: String,
        val sessionKey: String,
        val raw: ByteArray,
        /** OK / 만료 / 불일치 / 미발급 / 길이 오류 / 버전 오류 / BCD 오류 */
        val verdict: String,
        val accepted: Boolean,
        val memberId: String? = null,
        val uwbAddr: String? = null,
        val nonceHex: String? = null,
        val issuedNonceHex: String? = null,
        val issuedWallMs: Long? = null,
        val nonceAgeMs: Long? = null,
        /** 연결 → write 수락 (D-003) */
        val elapsedMs: Long? = null,
        val resultJson: String? = null,
        val detail: String = "",
        val sessionOpenedWallMs: Long? = null,
        /** 가장 최근 `onAdvertisingSetStarted` 시각 — TrialState 가 채움. OFF 중이면 null */
        val advStartedWallMs: Long? = null,
        /** 사이클 번호 — TrialState 가 채움. 수동이면 null */
        val cycle: Int? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is Received && wallMs == other.wallMs && sessionKey == other.sessionKey && raw.contentEquals(other.raw)
        override fun hashCode(): Int = wallMs.hashCode() * 31 + raw.contentHashCode()
    }

    /** 사이클 1회의 요약 — `trial_state.py CycleRow` */
    data class CycleRow(
        val k: Int,
        val stopWallMs: Long? = null,
        val advStartedWallMs: Long? = null,
        val onUntilWallMs: Long? = null,
        val writes: Int = 0,
        val firstWriteWallMs: Long? = null,
        val note: String = "",
    )

    /** epoch ms → 로컬 ISO ms. null 이면 빈 문자열 (`iso_ms`) */
    fun isoMs(wallMs: Long?): String =
        if (wallMs == null) "" else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(wallMs))

    /** ms → 초 소수 3자리. null 이면 빈칸 (`_f3`) */
    fun f3(ms: Long?): String = if (ms == null) "" else String.format(Locale.US, "%.3f", ms / 1000.0)

    private fun diff(laterMs: Long?, earlierMs: Long?): String =
        if (laterMs == null || earlierMs == null) "" else f3(laterMs - earlierMs)

    /** `Received` 한 건 → 17칸 (`events_csv.row`) */
    fun row(r: Received): List<String> = listOf(
        isoMs(r.wallMs), r.sessionLabel,
        r.memberId ?: "", r.uwbAddr ?: "", r.nonceHex ?: "", r.verdict, r.issuedNonceHex ?: "",
        f3(r.nonceAgeMs), f3(r.elapsedMs), r.resultJson ?: "", r.raw.toHexSpaced(), r.detail,
        isoMs(r.advStartedWallMs),
        isoMs(r.sessionOpenedWallMs),
        isoMs(r.issuedWallMs),
        r.cycle?.toString() ?: "",
        diff(r.wallMs, r.advStartedWallMs),
    )

    fun cycleRow(c: CycleRow): List<String> = listOf(
        c.k.toString(), isoMs(c.stopWallMs), isoMs(c.advStartedWallMs), isoMs(c.onUntilWallMs),
        c.writes.toString(), isoMs(c.firstWriteWallMs), diff(c.firstWriteWallMs, c.advStartedWallMs), c.note,
    )

    /** CSV 텍스트 전체 (BOM 포함, CRLF). 호출자가 시간순으로 넘긴다 */
    fun serialize(header: List<String>, rows: Iterable<List<String>>): String = buildString {
        append('\uFEFF')
        append(header.joinToString(",") { cell(it) }).append("\r\n")
        for (r in rows) append(r.joinToString(",") { cell(it) }).append("\r\n")
    }

    fun serializeEvents(events: Iterable<Received>): String = serialize(HEADER, events.map(::row))
    fun serializeCycles(rows: Iterable<CycleRow>): String = serialize(CYCLES_HEADER, rows.map(::cycleRow))

    /** Python `csv.QUOTE_MINIMAL`: 쉼표·따옴표·줄바꿈이 있을 때만 감싸고 `"` 는 `""` */
    fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
