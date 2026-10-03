package dev.mcandle.uwbpos.protocol

/**
 * payload 16B 해석 — PROTOCOL §3-1, `pos_sim/codec.py:152-177` 의 `decode_payload` 를 그대로 옮겼다. 순수 함수.
 *
 * ```
 * [0]      version    0x01
 * [1..5]   member ID  10자리 → BCD 5B (첫 자리 = 상위 니블)
 * [6..7]   UWB addr   받은 순서 그대로 `AA:BB`. 00 00 = 폰이 UWB 미지원
 * [8..11]  nonce      방금 READ 한 값의 에코 — NonceStore 가 판정
 * [12..15] reserved   무시 (2차 HMAC 자리)
 * ```
 * 검사 순서는 **길이 → 버전 → BCD** 다. 어느 하나라도 실패하면 [Decode.Malformed] → 응답 `0x81`.
 * 판정 문자열(`길이 오류`/`버전 오류`/`BCD 오류`)은 시뮬레이터 `PayloadErrorKind` 와 같다 — events CSV `verdict` 열.
 */
object PayloadCodec {

    enum class ErrorKind(val label: String) {
        LENGTH("길이 오류"), VERSION("버전 오류"), BCD("BCD 오류"),
    }

    sealed class Decode {
        data class Ok(
            val version: Int,
            val memberId: String,
            val uwb: ByteArray,
            val nonce: ByteArray,
            val reserved: ByteArray,
        ) : Decode() {
            /** `AA:BB` 또는 `미지원` (`codec.uwb_addr_str`) */
            val uwbStr: String get() = uwbStr(uwb)
            val nonceHex: String get() = nonce.toHex()
            override fun equals(other: Any?): Boolean = other is Ok && version == other.version && memberId == other.memberId &&
                uwb.contentEquals(other.uwb) && nonce.contentEquals(other.nonce) && reserved.contentEquals(other.reserved)
            override fun hashCode(): Int = memberId.hashCode() * 31 + nonce.contentHashCode()
        }

        /** [detail] 은 시뮬레이터 `PayloadError` 메시지와 같은 꼴 — events CSV `detail` 열 */
        data class Malformed(val kind: ErrorKind, val detail: String) : Decode()
    }

    fun decode(raw: ByteArray): Decode {
        if (raw.size != ProtocolConstants.PAYLOAD_SIZE) {
            return Decode.Malformed(ErrorKind.LENGTH, "길이 ${raw.size}B (기대 ${ProtocolConstants.PAYLOAD_SIZE}B)")
        }
        val version: Int = raw[0].toInt() and 0xFF
        if (version != ProtocolConstants.PAYLOAD_VERSION.toInt()) {
            return Decode.Malformed(ErrorKind.VERSION, "version 0x%02X (기대 0x01)".format(version))
        }
        val memberId: String = bcdDecode(raw.copyOfRange(1, 6))
            ?: return Decode.Malformed(ErrorKind.BCD, "BCD 니블 > 9: ${raw.copyOfRange(1, 6).toHexSpaced()}")
        return Decode.Ok(
            version = version,
            memberId = memberId,
            uwb = raw.copyOfRange(6, 8),
            nonce = raw.copyOfRange(8, 12),
            reserved = raw.copyOfRange(12, 16),
        )
    }

    /** BCD 5B → 10자리. 니블이 9 를 넘으면 null (`codec.bcd_decode`) */
    fun bcdDecode(bytes: ByteArray): String? {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val hi: Int = (b.toInt() shr 4) and 0x0F
            val lo: Int = b.toInt() and 0x0F
            if (hi > 9 || lo > 9) return null
            sb.append(hi).append(lo)
        }
        return sb.toString()
    }

    /** `"0123456789"` → `01 23 45 67 89` — 테스트 왕복용 (손님 앱 `Payload.bcd` 와 같다) */
    fun bcdEncode(digits: String): ByteArray {
        require(digits.length % 2 == 0 && digits.all { it in '0'..'9' }) { "digits" }
        return ByteArray(digits.length / 2) { i ->
            (((digits[2 * i] - '0') shl 4) or (digits[2 * i + 1] - '0')).toByte()
        }
    }

    /** `00 00` → `미지원`, 그 외 `AA:BB` (`codec.uwb_addr_str`) */
    fun uwbStr(uwb: ByteArray): String =
        if (uwb.size == 2 && uwb[0] == 0.toByte() && uwb[1] == 0.toByte()) UWB_UNSUPPORTED
        else uwb.joinToString(":") { "%02X".format(it) }

    const val UWB_UNSUPPORTED: String = "미지원"
}
