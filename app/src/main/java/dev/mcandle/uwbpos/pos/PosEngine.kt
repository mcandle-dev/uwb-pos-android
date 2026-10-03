package dev.mcandle.uwbpos.pos

import dev.mcandle.uwbpos.log.EventsCsv
import dev.mcandle.uwbpos.protocol.PayloadCodec
import dev.mcandle.uwbpos.protocol.ProtocolConstants
import dev.mcandle.uwbpos.protocol.ResultJson
import dev.mcandle.uwbpos.protocol.toHex

/**
 * POS 쪽 프로토콜 판단 — `gatt_server.py:355-413` (`_evaluate`·`_lookup_and_store`) 을 BLE 객체 없이 옮겼다. `PosGattServer` 가
 * 콜백에서 이것을 부르고 `sendResponse`/`notify` 만 담당한다 (ARCHITECTURE §3). JVM 테스트 가능.
 *
 * write 평가 순서: `PayloadCodec.decode`(길이 → 버전 → BCD) 실패 → `0x81` / `NonceStore.verify` 실패 → `0x80` / 일치 → `0`.
 * **응답 코드는 평가 즉시 돌려주고**, 조회·NOTIFY 는 그 뒤다 (constitution §2).
 */
class PosEngine(
    private val sessions: SessionRegistry,
    private val nonces: NonceStore,
    private val lookup: Lookup,
    private val wallMs: () -> Long,
    private val ttlS: () -> Int = { ProtocolConstants.NONCE_TTL_S },
    /** D-009 디버그 — 판정과 무관하게 응답 코드를 강제 (0x80 / 0x81). null 이면 정상 */
    private val forceStatus: () -> Int? = { null },
) {
    /** write 평가 결과 — 응답 코드와 CSV/③ 패널 행 */
    data class Evaluated(val status: Int, val received: EventsCsv.Received)

    /** 수락 뒤 조회 결과 — NOTIFY 할지는 [shouldNotify] 로 */
    data class Looked(val resultJson: String, val lookupMs: Long)

    fun nonceIssued(sessionKey: String): NonceStore.Issued = nonces.issue(sessionKey)

    fun evaluateWrite(sessionKey: String, raw: ByteArray, monoMs: Long): Evaluated {
        val s: SessionRegistry.Session = sessions.get(sessionKey) ?: sessions.open(sessionKey)
        val now: Long = wallMs()
        val issued: NonceStore.Issued? = nonces.peek(sessionKey)
        var rec = EventsCsv.Received(
            wallMs = now, sessionLabel = s.label, sessionKey = s.key, raw = raw, verdict = "", accepted = false,
            issuedNonceHex = issued?.hex, issuedWallMs = issued?.issuedWall, sessionOpenedWallMs = s.openedWallMs,
        )
        when (val d = PayloadCodec.decode(raw)) {
            is PayloadCodec.Decode.Malformed -> {
                rec = rec.copy(verdict = d.kind.label, detail = d.detail)
                return Evaluated(forceStatus() ?: ProtocolConstants.WRITE_ERR_PAYLOAD_MALFORMED, rec)
            }
            is PayloadCodec.Decode.Ok -> {
                rec = rec.copy(memberId = d.memberId, uwbAddr = d.uwbStr, nonceHex = d.nonceHex)
                val vr: NonceStore.VerifyResult = nonces.verify(sessionKey, d.nonce)
                rec = rec.copy(verdict = vr.verdict.label, nonceAgeMs = vr.ageMs, detail = vr.detail(d.nonceHex, ttlS()))
                val forced: Int? = forceStatus()
                if (vr.accepted && forced == null) {
                    rec = rec.copy(accepted = true, elapsedMs = monoMs - s.openedMonoMs)
                    sessions.update(sessionKey) { it.copy(memberId = d.memberId, uwbAddr = d.uwbStr) }
                    return Evaluated(0, rec)
                }
                return Evaluated(forced ?: ProtocolConstants.WRITE_ERR_NONCE_REJECTED, rec)
            }
        }
    }

    /** 수락 뒤 — 조회 실패도 결과값(`status:error`)이다. 세션이 그 사이 끊겼으면 결과만 돌려주고 저장은 생략 */
    suspend fun afterAccept(sessionKey: String, memberId: String, monoMs: () -> Long): Looked {
        val t0 = monoMs()
        val json: String = runCatching { lookup.lookup(memberId) }
            .getOrElse { e -> ResultJson.error("lookup failed: ${e.javaClass.simpleName}: ${e.message}") }
        sessions.update(sessionKey) { it.copy(resultJson = json) }
        return Looked(json, monoMs() - t0)
    }

    /** NOTIFY 가능? 구독 중이고 `size ≤ mtu−3` (`gatt_server.py:401-404`). 아니면 호출자가 ERR 로그 — 폰은 5초 뒤 READ 로 받는다 */
    fun shouldNotify(session: SessionRegistry.Session, json: String): NotifyDecision {
        if (!session.subscribed) return NotifyDecision.NotSubscribed
        val size: Int = json.toByteArray(Charsets.UTF_8).size
        return if (size > session.notifyLimit) NotifyDecision.TooLarge(size, session.notifyLimit) else NotifyDecision.Send
    }

    sealed class NotifyDecision {
        object Send : NotifyDecision()
        object NotSubscribed : NotifyDecision()
        data class TooLarge(val size: Int, val limit: Int) : NotifyDecision()
    }

    /** result READ 응답 바이트 — `offset` 부터 (long read). 결과 없으면 빈 배열. `offset > size` 는 호출자가 `GATT_INVALID_OFFSET` */
    fun resultBytes(session: SessionRegistry.Session?, offset: Int): ByteArray? {
        val bytes: ByteArray = session?.resultJson?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        if (offset > bytes.size) return null
        return bytes.copyOfRange(offset, bytes.size)
    }

    /** DISCONNECTED — nonce 와 세션을 함께 폐기 (PROTOCOL §3-0) */
    fun closeSession(sessionKey: String): SessionRegistry.Session? {
        nonces.drop(sessionKey)
        return sessions.close(sessionKey)
    }

    companion object {
        fun hex(b: ByteArray): String = b.toHex()
    }
}
