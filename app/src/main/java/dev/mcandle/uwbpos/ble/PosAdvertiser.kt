package dev.mcandle.uwbpos.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import dev.mcandle.uwbpos.log.ActivityLine.Cat
import dev.mcandle.uwbpos.protocol.IBeaconAd
import dev.mcandle.uwbpos.protocol.ProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * iBeacon 광고 — PROTOCOL §2, spec 001 FR-A1~A6, §G D-002·D-005·D-007.
 *
 * `startAdvertisingSet(legacy · connectable · scannable · interval 160 = 100 ms)` 하나로 iBeacon(AdvData, manufacturer 23B)과
 * 서비스 UUID(Scan Response)를 **같은 주소**로 낸다 → 손님 앱 규칙 ①. Flags 3B 는 스택이 넣는다(합 30B). 기기 이름·Tx 는 넣지 않는다.
 * OFF/ON 은 `enableAdvertising` 으로 set 과 주소를 유지한다(D-005) — set 을 다시 만들면 새 랜덤 주소라 손님 앱이 "새 기기"로 본다.
 * 연결돼도 set 을 건드리지 않는다 — 스택이 재개한다(D-007, FAQ Q3). 상태는 콜백으로 확인된 것만 (FR-5).
 *
 * 참조: `_reference_console/.../ble/BeaconBleOobChannel.kt:190-240` (AdvertiseCallback 상태 보고 패턴) — API 는 AdvertisingSet 으로 바꿨다.
 */
@SuppressLint("MissingPermission")
class PosAdvertiser(
    context: Context,
    private val log: (Cat, String?, String, Boolean) -> Unit,
    private val onStarted: (wallMs: Long) -> Unit = {},
    private val onEnabled: (enabled: Boolean, wallMs: Long) -> Unit = { _, _ -> },
) {
    private val adapter: BluetoothAdapter? = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    data class Params(
        val proximityUuid: UUID = ProtocolConstants.PROXIMITY_UUID,
        val serviceUuid: UUID = ProtocolConstants.SERVICE_UUID,
        val major: Int = 1,
        val minor: Int = 0x0101,
        val txPowerDbm: Int = -59,
        /** 0.625 ms 단위. 160 = 100 ms (`INTERVAL_LOW`). 최소 160 */
        val intervalUnits: Int = AdvertisingSetParameters.INTERVAL_LOW,
        val txPowerLevel: Int = AdvertisingSetParameters.TX_POWER_HIGH,
    )

    sealed class State {
        object Idle : State()
        object Starting : State()
        data class Started(val txPower: Int, val sinceWallMs: Long) : State()
        /** 사이클 OFF — set 은 살아 있다 */
        object Off : State()
        data class Failed(val status: Int, val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var set: AdvertisingSet? = null
    private var current: Params? = null
    /** 마지막으로 실제 송출한 23B — ① raw 표시용 */
    var payload: ByteArray? = null
        private set

    val isStarted: Boolean get() = _state.value is State.Started
    val isSetAlive: Boolean get() = set != null

    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                set = advertisingSet
                val now = System.currentTimeMillis()
                _state.value = State.Started(txPower, now)
                log(Cat.ADV, null, "publisher started (AdvertisingSet 콜백 확인) — on-air 30B, Flags 스택, connectable, tx ${txPower} dBm, interval ${current?.intervalUnits ?: 0}×0.625ms", false)
                onStarted(now)
            } else {
                _state.value = State.Failed(status, statusName(status))
                log(Cat.ERR, null, "publisher 시작 실패 status=$status (${statusName(status)})", true)
            }
        }

        override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
            val now = System.currentTimeMillis()
            if (status != ADVERTISE_SUCCESS) {
                log(Cat.ERR, null, "enableAdvertising($enable) 실패 status=$status (${statusName(status)})", true)
                return
            }
            if (enable) {
                val tx: Int = (_state.value as? State.Started)?.txPower ?: 0
                _state.value = State.Started(tx, now)
                log(Cat.ADV, null, "publisher started (enableAdvertising 콜백 확인) — set·주소 유지", false)
                onStarted(now)
            } else {
                _state.value = State.Off
                log(Cat.ADV, null, "publisher stopped (enableAdvertising false — set 유지)", false)
            }
            onEnabled(enable, now)
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            set = null
            _state.value = State.Idle
            log(Cat.ADV, null, "publisher stopped (set 종료)", false)
        }
    }

    /** 새 set 시작 (수동 "시작"). 이미 살아 있으면 enable 로 (D-005) */
    fun start(params: Params): Boolean {
        if (set != null) return enable(true)
        val advertiser: BluetoothLeAdvertiser = adapter?.bluetoothLeAdvertiser ?: run {
            _state.value = State.Failed(-1, "광고 미지원"); log(Cat.ERR, null, "이 기기는 BLE 광고 미지원 (bluetoothLeAdvertiser == null)", true); return false
        }
        val p: ByteArray = IBeaconAd.payload(params.proximityUuid, params.major, params.minor, params.txPowerDbm).getOrElse { e ->
            _state.value = State.Failed(-1, e.message ?: "payload"); log(Cat.ERR, null, "iBeacon payload 오류: ${e.message}", true); return false
        }
        if (!IBeaconAd.fitsLegacyBudget(p)) {
            _state.value = State.Failed(-1, "31B 예산 초과"); log(Cat.ERR, null, "광고 데이터 ${p.size + 4}B > ${IBeaconAd.ADV_DATA_BUDGET}B", true); return false
        }
        current = params; payload = p
        _state.value = State.Starting
        log(Cat.ADV, null, "start 요청 iBeacon 23B major=${params.major} minor=${params.minor} tx=${params.txPowerDbm} — 상태는 콜백으로 확인", false)
        // Builder 는 범위 밖 값에 IAE 를 던진다(txPowerLevel −127..1 dBm, interval ≤ INTERVAL_MAX) — 예외는 결과값으로 (constitution §5)
        val parameters: AdvertisingSetParameters = runCatching {
            AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true) // legacy + connectable 은 scannable 이어야 build 가 통과한다
                .setInterval(params.intervalUnits.coerceAtLeast(AdvertisingSetParameters.INTERVAL_LOW))
                .setTxPowerLevel(params.txPowerLevel)
                .build()
        }.getOrElse { e ->
            _state.value = State.Failed(-1, "파라미터 오류")
            log(Cat.ERR, null, "AdvertisingSetParameters 오류 ${e.javaClass.simpleName}: ${e.message} (interval=${params.intervalUnits} txLevel=${params.txPowerLevel} dBm)", true)
            return false
        }
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(ProtocolConstants.APPLE_COMPANY_ID, p)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(params.serviceUuid))
            .build()
        return runCatching { advertiser.startAdvertisingSet(parameters, advData, scanResponse, null, null, callback); true }
            .getOrElse { e ->
                _state.value = State.Failed(-1, e.javaClass.simpleName)
                log(Cat.ERR, null, "startAdvertisingSet 예외 ${e.javaClass.simpleName}: ${e.message}", true)
                false
            }
    }

    /** 사이클 OFF/ON — set·주소 유지 (D-005). set 이 없으면 false */
    fun enable(on: Boolean): Boolean {
        val s: AdvertisingSet = set ?: return false
        log(Cat.ADV, null, if (on) "start 요청 (enableAdvertising true)" else "stop 요청 (enableAdvertising false — set 유지)", false)
        return runCatching { s.enableAdvertising(on, 0, 0); true }
            .getOrElse { e -> log(Cat.ERR, null, "enableAdvertising 예외 ${e.javaClass.simpleName}: ${e.message}", true); false }
    }

    /** 수동 "중지" — set 종료 (주소도 버린다) */
    fun stop() {
        val advertiser: BluetoothLeAdvertiser = adapter?.bluetoothLeAdvertiser ?: run { set = null; _state.value = State.Idle; return }
        if (set == null) { _state.value = State.Idle; return }
        log(Cat.ADV, null, "stop 요청 (set 종료)", false)
        runCatching { advertiser.stopAdvertisingSet(callback) }
            .onFailure { e -> log(Cat.ERR, null, "stopAdvertisingSet 예외 ${e.javaClass.simpleName}", true); set = null; _state.value = State.Idle }
    }

    /** BT OFF 등으로 set 이 스택에서 사라졌을 때 상태만 정리 */
    fun invalidate() { set = null; _state.value = State.Idle }

    companion object {
        fun statusName(status: Int): String = when (status) {
            AdvertisingSetCallback.ADVERTISE_SUCCESS -> "SUCCESS"
            AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            else -> "UNKNOWN"
        }
    }
}
