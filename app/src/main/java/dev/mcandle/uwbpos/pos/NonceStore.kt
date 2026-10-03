package dev.mcandle.uwbpos.pos

import dev.mcandle.uwbpos.protocol.ProtocolConstants
import dev.mcandle.uwbpos.protocol.toHex
import java.security.SecureRandom

/**
 * nonce 발급·검증 — 연결(세션) 단위. PROTOCOL §3-0·§4, `pos_sim/nonce.py` 를 그대로 옮겼다. 순수(시계·난수 주입).
 *
 * 전역 값 하나가 아니다. 폰 A 가 읽은 nonce 는 폰 B 의 READ 로 무효화되지 않는다.
 * verify 순서: 미발급 → `NOT_ISSUED` / age > TTL → `EXPIRED`(**삭제**) / 불일치 → `MISMATCH`(**유지** — 재시도 가능) / 일치 → `OK`(삭제, 1회 소비).
 * 판정 문자열은 events CSV `verdict` 열과 같다.
 */
class NonceStore(
    private val ttlMs: Long = ProtocolConstants.NONCE_TTL_S * 1000L,
    private val monoMs: () -> Long,
    private val wallMs: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class Verdict(val label: String) {
        OK("OK"), EXPIRED("만료"), MISMATCH("불일치"), NOT_ISSUED("미발급"),
    }

    /** 발급 1건. [issuedMono] 는 age 계산, [issuedWall] 은 로그·CSV(`nonce_issued_at`) 표시용 */
    data class Issued(val value: ByteArray, val issuedMono: Long, val issuedWall: Long) {
        val hex: String get() = value.toHex()
        override fun equals(other: Any?): Boolean = other is Issued && value.contentEquals(other.value) && issuedMono == other.issuedMono
        override fun hashCode(): Int = value.contentHashCode()
    }

    data class VerifyResult(val verdict: Verdict, val issued: Issued?, val ageMs: Long?) {
        val accepted: Boolean get() = verdict == Verdict.OK

        /** 시뮬레이터 `gatt_server.py:378-386` 과 같은 detail 문구 — events CSV `detail` 열 */
        fun detail(receivedHex: String, ttlS: Int = ProtocolConstants.NONCE_TTL_S): String = when (verdict) {
            Verdict.OK -> "nonce 일치 (발급 후 %.3fs)".format((ageMs ?: 0L) / 1000.0)
            Verdict.EXPIRED -> "발급 후 %.1fs (ttl %ds)".format((ageMs ?: 0L) / 1000.0, ttlS)
            Verdict.MISMATCH -> "기대 ${issued?.hex ?: "?"}, 수신 $receivedHex"
            Verdict.NOT_ISSUED -> "이 연결에서 nonce를 READ한 적이 없음"
        }
    }

    private val bySession = HashMap<String, Issued>()

    /** 발급 누계 — ① 패널 카운터 */
    var issuedCount: Int = 0
        private set

    /** READ 마다 새로 발급. 같은 세션의 이전 값은 덮어쓴다 */
    @Synchronized
    fun issue(sessionKey: String): Issued {
        val bytes = ByteArray(ProtocolConstants.NONCE_SIZE).also(random::nextBytes)
        val issued = Issued(bytes, monoMs(), wallMs())
        bySession[sessionKey] = issued
        issuedCount++
        return issued
    }

    /** 같은 세션에서 발급한 값과만 대조. OK 면 즉시 소비(1회용) */
    @Synchronized
    fun verify(sessionKey: String, echoed: ByteArray): VerifyResult {
        val issued: Issued = bySession[sessionKey] ?: return VerifyResult(Verdict.NOT_ISSUED, null, null)
        val age: Long = monoMs() - issued.issuedMono
        if (age > ttlMs) {
            bySession.remove(sessionKey)
            return VerifyResult(Verdict.EXPIRED, issued, age)
        }
        if (!echoed.contentEquals(issued.value)) return VerifyResult(Verdict.MISMATCH, issued, age)
        bySession.remove(sessionKey)
        return VerifyResult(Verdict.OK, issued, age)
    }

    @Synchronized
    fun peek(sessionKey: String): Issued? = bySession[sessionKey]

    /** 연결이 끊길 때 (PROTOCOL §3-0) */
    @Synchronized
    fun drop(sessionKey: String) { bySession.remove(sessionKey) }
}
