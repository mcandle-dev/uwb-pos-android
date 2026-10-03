package dev.mcandle.uwbpos.protocol

import java.util.UUID

/**
 * 와이어 계약 상수 — 원본은 `uwb-pos-simulator/PROTOCOL.md` (위치: PEERS.md). 이 리포에 복사본을 두지 않는다.
 * 손님 앱 `uwb-member-app/protocol/ProtocolConstants.kt` 와 **같은 파일**(패키지만 다름) — 양쪽이 같은 값을 봐야 한다.
 *
 * 아래 값은 PROTOCOL.md §7-1·§7-2 에서 2026-09-20 확정된 것이다 (`pos_sim/config.py:19-33`).
 * 바꿔야 하면 상대 리포 PR 이 먼저다 (constitution §4). 디버그 빌드는 `Settings` 의 덮어쓰기 값을 쓸 수 있다.
 */
object ProtocolConstants {

    /** iBeacon Proximity UUID — PROTOCOL §7-1 확정 */
    val PROXIMITY_UUID: UUID = UUID.fromString("94C5D726-6C10-49B9-8758-04BE29749F20")

    /** GATT 서비스 — PROTOCOL §7-2 확정. Scan Response 에 실린다 (§2-2) */
    val SERVICE_UUID: UUID = UUID.fromString("BF3C0001-65AA-4232-BAE3-33F9A5289F74")

    /** nonce 특성 READ, 4B, READ 마다 재발급 — PROTOCOL §3, `nonce.py:13` */
    val NONCE_UUID: UUID = UUID.fromString("BF3C0002-65AA-4232-BAE3-33F9A5289F74")

    /** payload 특성 WRITE(with response), 16B — PROTOCOL §3-1 */
    val PAYLOAD_UUID: UUID = UUID.fromString("BF3C0003-65AA-4232-BAE3-33F9A5289F74")

    /** result 특성 READ·NOTIFY, JSON — PROTOCOL §3-2 */
    val RESULT_UUID: UUID = UUID.fromString("BF3C0004-65AA-4232-BAE3-33F9A5289F74")

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG 표준) — 이 앱이 result 에 **직접 추가**한다 */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Apple company ID — iBeacon manufacturer data */
    const val APPLE_COMPANY_ID: Int = 0x004C

    /** payload[0] — `codec.py:18` */
    const val PAYLOAD_VERSION: Byte = 0x01

    /** payload 길이 */
    const val PAYLOAD_SIZE: Int = 16

    /** nonce 길이 — `nonce.py:13 NONCE_LEN = 4` */
    const val NONCE_SIZE: Int = 4

    /** nonce 유효시간 — `config.py:33`. **이 앱이 강제한다** (constitution §6) */
    const val NONCE_TTL_S: Int = 30

    /**
     * write 응답 코드 — `gatt_server.py:52-53`. ATT application error 로 `sendResponse(status)` 에 그대로 넣는다.
     * `0x0E` 는 핸들러가 응답하지 못한 내부 결함 — 예외 경로에서 **명시적으로** 응답한다 (constitution §2)
     */
    const val WRITE_ERR_NONCE_REJECTED: Int = 0x80
    const val WRITE_ERR_PAYLOAD_MALFORMED: Int = 0x81
    const val ERR_INTERNAL: Int = 0x0E

    /** result 스키마 버전 — PROTOCOL §3-2 확정 */
    const val RESULT_SCHEMA_VERSION: Int = 1

    /** result JSON 최대 크기 — message → member 순으로 줄여 맞춘다 (PROTOCOL §3-2, `member_lookup.py:38-54`) */
    const val RESULT_MAX_BYTES: Int = 120

    /** message 최대 글자 수 — `member_lookup.py:10` */
    const val RESULT_MESSAGE_MAX_CHARS: Int = 40

    /** result status 열거 — PROTOCOL §3-2 확정 */
    const val RESULT_SUCCESS: String = "success"
    const val RESULT_NOT_FOUND: String = "not_found"
    const val RESULT_ERROR: String = "error"
}

/** UUID → 16B big-endian (MSB 먼저). iBeacon payload 는 이 순서를 그대로 쓴다 (`codec.py:36`) */
fun UUID.toBytes(): ByteArray {
    val out = ByteArray(16)
    var msb: Long = mostSignificantBits
    var lsb: Long = leastSignificantBits
    for (i in 7 downTo 0) {
        out[i] = (msb and 0xFF).toByte(); msb = msb ushr 8
        out[8 + i] = (lsb and 0xFF).toByte(); lsb = lsb ushr 8
    }
    return out
}

/** 대문자 hex, 구분자 없음 — `events_*.csv` 의 nonce 표기 */
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

/** 공백 구분 대문자 hex — `codec.hexs`, `events_*.csv` 의 `raw_hex` 표기 */
fun ByteArray.toHexSpaced(): String = joinToString(" ") { "%02X".format(it) }

/** 로그용 멤버 ID 마스킹 — 앞 3자리만 (constitution §6). 디버그 빌드는 호출측이 원문을 쓴다 */
fun maskMemberId(memberId: String): String =
    if (memberId.isEmpty()) "(없음)" else memberId.take(3) + "*".repeat((memberId.length - 3).coerceAtLeast(0))
