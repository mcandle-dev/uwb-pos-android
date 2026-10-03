package dev.mcandle.uwbpos.pos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 연결 1개 = 세션 1개 — PROTOCOL §3-0, `gatt_server.py:89-101 Session`. 키는 `BluetoothDevice.address`(연결 동안 고정), 라벨 `A1…`.
 * 이 앱은 **연결 콜백**에서 세션을 만든다 (D-003; 시뮬레이터는 Windows 에 연결 이벤트가 없어 첫 GATT 요청에서 만들었다).
 * 끊기면 nonce·result·구독을 즉시 폐기한다 — [NonceStore.drop] 은 호출자(PosEngine)가 함께 부른다. BLE 객체를 모른다 (JVM 테스트 가능).
 */
class SessionRegistry(private val wallMs: () -> Long, private val monoMs: () -> Long) {

    data class Session(
        val key: String,
        val label: String,
        val openedWallMs: Long,
        val openedMonoMs: Long,
        val memberId: String? = null,
        val uwbAddr: String? = null,
        val resultJson: String? = null,
        val subscribed: Boolean = false,
        /** ATT MTU. 폰이 `requestMtu` 하기 전 기본 23 → NOTIFY 한도 20B */
        val mtu: Int = DEFAULT_MTU,
    ) {
        val notifyLimit: Int get() = mtu - 3
    }

    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    /** ② 패널 "연결 중인 폰" — 연결 순서대로 */
    val sessions: StateFlow<Map<String, Session>> = _sessions

    private var counter: Int = 0
    /** ① 패널 카운터 "연결 N" — 누계 */
    var connectedCount: Int = 0
        private set

    /** CONNECTED. 이미 있으면(재연결 콜백 중복) 그대로 */
    @Synchronized
    fun open(key: String): Session {
        _sessions.value[key]?.let { return it }
        val s = Session(key, SessionLabel.of(counter++), wallMs(), monoMs())
        connectedCount++
        _sessions.update { it + (key to s) }
        return s
    }

    /** DISCONNECTED — 세션과 그 안의 result·구독이 함께 사라진다. 없었으면 null (우리 서비스를 쓰지 않은 central 등) */
    @Synchronized
    fun close(key: String): Session? {
        val s = _sessions.value[key] ?: return null
        _sessions.update { it - key }
        return s
    }

    fun get(key: String): Session? = _sessions.value[key]

    @Synchronized
    fun update(key: String, transform: (Session) -> Session): Session? {
        val cur = _sessions.value[key] ?: return null
        val next = transform(cur)
        _sessions.update { it + (key to next) }
        return next
    }

    /** 연결 → 지금 (D-003 `elapsed_s` 재료) */
    fun elapsedMs(s: Session): Long = monoMs() - s.openedMonoMs

    companion object {
        const val DEFAULT_MTU: Int = 23
    }
}
