package dev.mcandle.uwbpos.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.mcandle.uwbpos.log.ActivityLine.Cat
import dev.mcandle.uwbpos.log.EventsCsv
import dev.mcandle.uwbpos.pos.NonceStore
import dev.mcandle.uwbpos.pos.PosEngine
import dev.mcandle.uwbpos.pos.SessionRegistry
import dev.mcandle.uwbpos.protocol.ProtocolConstants
import dev.mcandle.uwbpos.protocol.maskMemberId
import dev.mcandle.uwbpos.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * GATT 서버 — PROTOCOL §3·§3-0·§3-1·§3-2, spec 001 FR-B1~B10. 동작은 시뮬레이터 `gatt_server.py`, API 사용법은
 * `_reference_console/.../ble/GattServerBleOobChannel.kt:201-235(openServer)·389-395(respond)·109-121(offset 응답)` 에서 가져왔다.
 *
 * 콘솔과 **다른 것**: 연결돼도 광고를 건드리지 않는다(여기서는 광고를 모른다 — `PosService` 가 소유), payload 는 With Response 로
 * `0x80/0x81` 을 돌려주고, CCCD 디스크립터를 **앱이 추가**하며, NOTIFY 는 MTU−3 가드, 세션 격리·nonce 는 `PosEngine` 이 판단한다.
 *
 * 스레딩(constitution §1): 콜백은 바인더 스레드 → [Channel] → **단일 소비 코루틴** → `PosEngine` → `sendResponse`/`notify`.
 * ATT 는 링크당 한 요청이라 직렬 처리가 안전하다. **모든 요청 경로가 `sendResponse` 에 닿는다** — 예외도 `0x0E` 로 응답 (constitution §2).
 */
@SuppressLint("MissingPermission") // 권한은 PosService 가 시작 전에 확인한다 — 미보유는 SecurityException 을 결과값으로 (constitution §5)
class PosGattServer(
    context: Context,
    private val scope: CoroutineScope,
    private val engine: PosEngine,
    private val sessions: SessionRegistry,
    private val log: (Cat, String?, String, Boolean) -> Unit,
    private val onReceived: (EventsCsv.Received) -> Unit,
    private val onLookup: (EventsCsv.Received, PosEngine.Looked, notified: Boolean) -> Unit,
    private val onSessionsChanged: () -> Unit = {},
    private val onConnected: (BluetoothDevice) -> Unit = {},
    private val serviceUuid: UUID = ProtocolConstants.SERVICE_UUID,
) {
    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    sealed class State {
        object Closed : State()
        object Opening : State()
        object Ready : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Closed)
    /** ① 패널 GATT 배지 — `onServiceAdded` 콜백으로 확인된 것만 Ready (FR-5) */
    val state: StateFlow<State> = _state

    private var server: BluetoothGattServer? = null
    private val lock = Any()
    private var serviceAdded: CompletableDeferred<Int>? = null
    private var consumer: Job? = null
    private val requests = Channel<Req>(Channel.UNLIMITED)
    /** `onNotificationSent` 대기 — 같은 기기에 연속 NOTIFY 를 보낼 때 */
    @Volatile private var notifySent: CompletableDeferred<Int>? = null

    /** ① 카운터 "수락 N · 거부 N" */
    @Volatile var acceptedCount: Int = 0; private set
    @Volatile var rejectedCount: Int = 0; private set

    private sealed class Req {
        data class Conn(val device: BluetoothDevice, val status: Int, val newState: Int) : Req()
        data class Mtu(val device: BluetoothDevice, val mtu: Int) : Req()
        data class Read(val device: BluetoothDevice, val id: Int, val offset: Int, val ch: BluetoothGattCharacteristic) : Req()
        data class Write(val device: BluetoothDevice, val id: Int, val ch: BluetoothGattCharacteristic, val prepared: Boolean, val needResp: Boolean, val offset: Int, val value: ByteArray) : Req()
        data class DescRead(val device: BluetoothDevice, val id: Int, val offset: Int, val desc: BluetoothGattDescriptor) : Req()
        data class DescWrite(val device: BluetoothDevice, val id: Int, val desc: BluetoothGattDescriptor, val prepared: Boolean, val needResp: Boolean, val offset: Int, val value: ByteArray) : Req()
        data class Exec(val device: BluetoothDevice, val id: Int, val execute: Boolean) : Req()
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { requests.trySend(Req.Conn(device, status, newState)) }
        override fun onServiceAdded(status: Int, service: BluetoothGattService) { serviceAdded?.complete(status) }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { requests.trySend(Req.Mtu(device, mtu)) }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            requests.trySend(Req.Read(device, requestId, offset, characteristic))
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            requests.trySend(Req.Write(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value))
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            requests.trySend(Req.DescRead(device, requestId, offset, descriptor))
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            requests.trySend(Req.DescWrite(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value))
        }
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) { requests.trySend(Req.Exec(device, requestId, execute)) }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) { notifySent?.complete(status) }
    }

    /**
     * 서버 열기 + 서비스 추가. `addService` 는 비동기라 `onServiceAdded` 를 기다린 뒤에만 Ready — 광고는 그 뒤 (FR-B1).
     * 실패는 [State.Failed] + 로그. 예외를 던지지 않는다.
     */
    suspend fun open(): Boolean {
        synchronized(lock) { if (server != null) return _state.value is State.Ready }
        _state.value = State.Opening
        val s: BluetoothGattServer? = try {
            manager?.openGattServer(appContext, callback)
        } catch (e: SecurityException) {
            fail("서버 시작 실패: BLUETOOTH_CONNECT 권한 없음 — ${e.message}"); return false
        }
        if (s == null) { fail("GATT 서버 열기 실패 (어댑터 없음 또는 BT 꺼짐)"); return false }
        synchronized(lock) { server = s }
        startConsumer()
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(ProtocolConstants.NONCE_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            addCharacteristic(BluetoothGattCharacteristic(ProtocolConstants.PAYLOAD_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ProtocolConstants.RESULT_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ).apply {
                    // FR-B1 — Android 는 CCCD 를 자동으로 붙이지 않는다. 없으면 폰의 구독(writeDescriptor)이 실패한다
                    addDescriptor(BluetoothGattDescriptor(ProtocolConstants.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                },
            )
        }
        val added = CompletableDeferred<Int>().also { serviceAdded = it }
        val ok: Boolean = runCatching { s.addService(service) }.getOrDefault(false)
        if (!ok) { fail("addService 거부"); close(); return false }
        val status: Int? = withTimeoutOrNull(SERVICE_ADD_TIMEOUT_MS) { added.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("onServiceAdded status=$status"); close(); return false }
        _state.value = State.Ready
        log(Cat.GATT, null, "service advertising started — 3 chars (nonce/payload/result), connectable", false)
        return true
    }

    fun close() {
        val s: BluetoothGattServer? = synchronized(lock) { server.also { server = null } }
        consumer?.cancel(); consumer = null
        runCatching { s?.close() }
        _state.value = State.Closed
    }

    /** 디버그 "끊기" 버튼용 */
    fun cancelConnection(device: BluetoothDevice) { runCatching { synchronized(lock) { server }?.cancelConnection(device) } }

    /** BT ON 뒤 복원 — 이미 붙어 있는 central 들 (`BluetoothManager.getConnectedDevices(GATT_SERVER)`) */
    fun connectedDevices(): List<BluetoothDevice> = runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT_SERVER) ?: emptyList() }.getOrDefault(emptyList())

    private fun fail(reason: String) {
        _state.value = State.Failed(reason)
        log(Cat.ERR, null, reason, true)
    }

    private fun startConsumer() {
        consumer?.cancel()
        consumer = scope.launch {
            for (req in requests) {
                try {
                    handle(req)
                } catch (t: Throwable) {
                    // 어떤 요청이든 응답은 한다 (constitution §2). 응답 누락 = 폰 30초 타임아웃 → status 14
                    log(Cat.ERR, null, "핸들러 예외 ${t.javaClass.simpleName}: ${t.message} — 0x0E 응답", true)
                    when (req) {
                        is Req.Read -> respond(req.device, req.id, ProtocolConstants.ERR_INTERNAL, 0, ByteArray(0))
                        is Req.Write -> if (req.needResp) respond(req.device, req.id, ProtocolConstants.ERR_INTERNAL, 0, ByteArray(0))
                        is Req.DescRead -> respond(req.device, req.id, ProtocolConstants.ERR_INTERNAL, 0, ByteArray(0))
                        is Req.DescWrite -> if (req.needResp) respond(req.device, req.id, ProtocolConstants.ERR_INTERNAL, 0, ByteArray(0))
                        is Req.Exec -> respond(req.device, req.id, ProtocolConstants.ERR_INTERNAL, 0, ByteArray(0))
                        else -> {}
                    }
                }
            }
        }
    }

    private suspend fun handle(req: Req) {
        when (req) {
            is Req.Conn -> onConn(req)
            is Req.Mtu -> {
                sessions.update(req.device.address) { it.copy(mtu = req.mtu) }
                log(Cat.GATT, labelOf(req.device), "MTU ${req.mtu}", false)
                onSessionsChanged()
            }
            is Req.Read -> onRead(req)
            is Req.Write -> onWrite(req)
            is Req.DescRead -> {
                val sub: Boolean = sessions.get(req.device.address)?.subscribed == true
                val v: ByteArray = if (sub) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                respond(req.device, req.id, BluetoothGatt.GATT_SUCCESS, 0, v)
            }
            is Req.DescWrite -> onDescWrite(req)
            is Req.Exec -> respond(req.device, req.id, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }
    }

    private fun onConn(req: Req.Conn) {
        when (req.newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                val s: SessionRegistry.Session = sessions.open(req.device.address)
                log(Cat.CONN, s.label, "connected ${req.device.address} · 세션 생성", false)
                onSessionsChanged()
                onConnected(req.device) // D-007 디버그 재enable 용 — 광고 set 은 여기서 건드리지 않는다 (FR-A4)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                val closed: SessionRegistry.Session? = engine.closeSession(req.device.address)
                if (closed != null) log(Cat.CONN, closed.label, "disconnected · 세션 폐기" + (if (req.status != 0) " (status ${req.status})" else ""), false)
                onSessionsChanged()
            }
        }
    }

    private fun onRead(req: Req.Read) {
        val key: String = req.device.address
        when (req.ch.uuid) {
            ProtocolConstants.NONCE_UUID -> {
                sessions.get(key) ?: sessions.open(key) // 연결 콜백을 못 받은 central (방어) — 세션을 여기서 연다
                val issued: NonceStore.Issued = engine.nonceIssued(key)
                respond(req.device, req.id, BluetoothGatt.GATT_SUCCESS, 0, issued.value)
                log(Cat.NONCE, labelOf(req.device), "read → ${issued.hex} (ttl ${ProtocolConstants.NONCE_TTL_S}s)", false)
            }
            ProtocolConstants.RESULT_UUID -> {
                val bytes: ByteArray? = engine.resultBytes(sessions.get(key), req.offset)
                if (bytes == null) {
                    respond(req.device, req.id, BluetoothGatt.GATT_INVALID_OFFSET, req.offset, ByteArray(0))
                } else {
                    respond(req.device, req.id, BluetoothGatt.GATT_SUCCESS, req.offset, bytes)
                    if (req.offset == 0) log(Cat.GATT, labelOf(req.device), "result read ← ${bytes.size}B", false)
                }
            }
            else -> respond(req.device, req.id, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, ByteArray(0))
        }
    }

    private suspend fun onWrite(req: Req.Write) {
        if (req.ch.uuid != ProtocolConstants.PAYLOAD_UUID) {
            if (req.needResp) respond(req.device, req.id, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, ByteArray(0))
            return
        }
        val key: String = req.device.address
        val ev: PosEngine.Evaluated = engine.evaluateWrite(key, req.value, SystemClock.elapsedRealtime())
        // 응답 먼저 (gatt_server.py:343) — 그 뒤에 이벤트·조회
        if (req.needResp) respond(req.device, req.id, ev.status, 0, ByteArray(0))
        else log(Cat.ERR, ev.received.sessionLabel, "payload 가 Write Without Response 로 왔다 — 거부 코드를 전달할 수 없음 (PROTOCOL §3-1)", true)
        val r: EventsCsv.Received = ev.received
        if (ev.status == 0) {
            acceptedCount++
            log(Cat.GATT, r.sessionLabel, "write 16B member=${memberForLog(r.memberId)} uwb=${r.uwbAddr} nonce=${r.nonceHex} → 수락 (${r.detail})", false)
        } else {
            rejectedCount++
            log(Cat.ERR, r.sessionLabel, "write ${req.value.size}B 거부 0x%02X: ${r.verdict} — ${r.detail}".format(ev.status), true)
        }
        onReceived(r)
        if (ev.status == 0 && r.memberId != null) {
            val looked: PosEngine.Looked = engine.afterAccept(key, r.memberId) { SystemClock.elapsedRealtime() }
            val notified: Boolean = notifyIfPossible(req.device, looked.resultJson)
            log(Cat.LOOK, r.sessionLabel, "lookup → ${looked.resultJson} (%.1f ms) · notified=%s".format(looked.lookupMs.toDouble(), notified), false)
            onLookup(r.copy(resultJson = looked.resultJson), looked, notified)
        }
    }

    private fun onDescWrite(req: Req.DescWrite) {
        if (req.desc.uuid != ProtocolConstants.CCCD_UUID || req.desc.characteristic.uuid != ProtocolConstants.RESULT_UUID) {
            if (req.needResp) respond(req.device, req.id, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, ByteArray(0))
            return
        }
        val enable: Boolean = req.value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
            req.value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        sessions.get(req.device.address) ?: sessions.open(req.device.address)
        sessions.update(req.device.address) { it.copy(subscribed = enable) }
        if (req.needResp) respond(req.device, req.id, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        log(Cat.GATT, labelOf(req.device), if (enable) "result notify 구독" else "result notify 구독 해제", false)
        onSessionsChanged()
    }

    /** 구독 중이고 MTU−3 안이면 NOTIFY. 아니면 ERR 로그만 — 폰은 5초 뒤 READ 폴백 (FR-B6) */
    private suspend fun notifyIfPossible(device: BluetoothDevice, json: String): Boolean {
        val s: SessionRegistry.Session = sessions.get(device.address) ?: return false
        when (val d = engine.shouldNotify(s, json)) {
            PosEngine.NotifyDecision.NotSubscribed -> return false
            is PosEngine.NotifyDecision.TooLarge -> {
                log(Cat.ERR, s.label, "result ${d.size}B > notify 한도 ${d.limit}B — 폰이 MTU를 안 올렸다. NOTIFY 생략, READ로 받아야 함 (PROTOCOL §3-2)", true)
                return false
            }
            PosEngine.NotifyDecision.Send -> {}
        }
        val server: BluetoothGattServer = synchronized(lock) { server } ?: return false
        val ch: BluetoothGattCharacteristic = server.getService(serviceUuid)?.getCharacteristic(ProtocolConstants.RESULT_UUID) ?: return false
        val bytes: ByteArray = json.toByteArray(Charsets.UTF_8)
        val sent = CompletableDeferred<Int>().also { notifySent = it }
        val started: Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, ch, false, bytes) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, ch, false)
            }
        }.getOrElse { e -> log(Cat.ERR, s.label, "notify 예외 ${e.javaClass.simpleName}: ${e.message}", true); false }
        if (!started) return false
        val status: Int? = withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { sent.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) log(Cat.ERR, s.label, "onNotificationSent status=$status", true)
        return status == BluetoothGatt.GATT_SUCCESS
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray) {
        val ok: Boolean = runCatching { synchronized(lock) { server }?.sendResponse(device, requestId, status, offset, value) ?: false }.getOrDefault(false)
        if (!ok) log(Cat.ERR, labelOf(device), "sendResponse 실패 (req $requestId, status 0x%02X) — 폰은 타임아웃을 본다".format(status), true)
    }

    private fun labelOf(device: BluetoothDevice): String? = sessions.get(device.address)?.label

    private fun memberForLog(memberId: String?): String = memberId?.let { if (dev.mcandle.uwbpos.BuildConfig.DEBUG) it else maskMemberId(it) } ?: "-"

    companion object {
        const val SERVICE_ADD_TIMEOUT_MS: Long = 5_000L
        const val NOTIFY_TIMEOUT_MS: Long = 3_000L
        @Suppress("unused") private fun ByteArray.hex(): String = toHex()
    }
}
