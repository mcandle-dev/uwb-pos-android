package dev.mcandle.uwbpos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.mcandle.uwbpos.App
import dev.mcandle.uwbpos.MainActivity
import dev.mcandle.uwbpos.R
import dev.mcandle.uwbpos.ble.PosAdvertiser
import dev.mcandle.uwbpos.ble.PosGattServer
import dev.mcandle.uwbpos.data.Settings
import dev.mcandle.uwbpos.log.ActivityLine.Cat
import dev.mcandle.uwbpos.log.EventsCsv
import dev.mcandle.uwbpos.log.LogExport
import dev.mcandle.uwbpos.pos.MockLookup
import dev.mcandle.uwbpos.pos.NonceStore
import dev.mcandle.uwbpos.pos.PosEngine
import dev.mcandle.uwbpos.pos.SessionRegistry
import dev.mcandle.uwbpos.trial.TrialState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * FGS(connectedDevice) — 광고·GATT 서버·세션·엔진의 소유자 (spec 001 FR-E2·E4, ARCHITECTURE §2·§4).
 * 알림 "POS 광고 중 · Major N / Minor N". BT 토글은 [BtStateWatcher](동적)로 받는다. 액티비티는 [Companion] 의 정적 Flow 로 관찰만 한다.
 */
class PosService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: App
    private lateinit var sessions: SessionRegistry
    private lateinit var nonces: NonceStore
    private lateinit var engine: PosEngine
    private lateinit var server: PosGattServer
    private lateinit var advertiser: PosAdvertiser
    private lateinit var watcher: BtStateWatcher
    private var settingsSnap: Settings.Snapshot? = null
    private var autosaveJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = App.of(this)
        createChannel()
        val log: (Cat, String?, String, Boolean) -> Unit = { cat, sid, msg, bad -> app.activityLog.add(cat, sid, msg, bad) }
        sessions = SessionRegistry({ System.currentTimeMillis() }, { SystemClock.elapsedRealtime() })
        nonces = NonceStore(monoMs = { SystemClock.elapsedRealtime() }, wallMs = { System.currentTimeMillis() })
        engine = PosEngine(
            sessions, nonces,
            lookup = MockLookup(delayMs = settingsSnap?.lookupDelayMs ?: 50L, forceNotFound = { settingsSnap?.debugForceNotFound == true }),
            wallMs = { System.currentTimeMillis() },
            ttlS = { settingsSnap?.nonceTtlS ?: 30 },
            forceStatus = { settingsSnap?.debugForceStatus },
        )
        advertiser = PosAdvertiser(this, log, onStarted = { wall -> trial.onAdvStarted(wall) }, onEnabled = { on, wall -> if (!on) trial.onAdvStopped(wall) })
        server = PosGattServer(
            this, scope, engine, sessions, log,
            onReceived = { r -> onReceived(r) },
            onLookup = { r, _, _ -> app.events.replace(r) },
            onSessionsChanged = { publish() },
            onConnected = { d -> onPhoneConnected(d) },
        )
        watcher = BtStateWatcher(this, onBtOn = { scope.launch { onBluetoothOn() } }, onBtOff = { onBluetoothOff() }).also { it.register() }
        scope.launch { app.settings.snapshot.collect { settingsSnap = it; publish() } }
        scope.launch { advertiser.state.collect { publish() } }
        scope.launch { server.state.collect { publish() } }
        scope.launch { sessions.sessions.collect { publish() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 후 5초 안에 — 첫 줄에서. 14+ connectedDevice 는 BLUETOOTH_* 권한 중 하나가 있어야 한다 (FR-E2)
        val fg: Boolean = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); true
        }.getOrElse { t -> app.activityLog.add(Cat.ERR, null, "FGS 시작 거부 ${t.javaClass.simpleName} — 근처 기기 권한 확인", true); false }
        if (!fg) { stopSelf(); return START_NOT_STICKY }
        _running.value = true
        when (intent?.action) {
            ACTION_STOP_ADV -> scope.launch { stopAdvertising(); publish() }
            ACTION_SAVE -> scope.launch { saveAll("수동") }
            else -> scope.launch { startAll() } // START · 부팅 · 앱 열기
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        watcher.unregister()
        advertiser.stop()
        server.close()
        autosaveJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── 광고·서버 수명 ─────────────────────────────────────────────────

    private suspend fun startAll() {
        val snap: Settings.Snapshot = settingsSnap ?: app.settings.current().also { settingsSnap = it }
        if (!server.open()) { publish(); return }                       // FR-B1 — onServiceAdded 뒤에만 광고
        advertiser.start(
            PosAdvertiser.Params(
                proximityUuid = snap.proximityUuid, major = snap.major, minor = snap.minor, txPowerDbm = snap.txDbm,
                intervalUnits = snap.intervalUnits, txPowerLevel = snap.txLevel,
            ),
        )
        startAutosave()
        publish()
    }

    private fun stopAdvertising() {
        advertiser.stop()
        trial.onAdvStopped(System.currentTimeMillis())
    }

    private fun onPhoneConnected(device: BluetoothDevice) {
        // FR-A4 — 광고 set 은 건드리지 않는다. 스택이 재개한다. D-007 디버그 토글이 켜져 있으면 enable 을 한 번 더 (오류 status 는 로그만)
        if (settingsSnap?.debugReenableOnConnect == true && advertiser.isSetAlive) {
            app.activityLog.add(Cat.ADV, null, "연결 수신 → enableAdvertising(true) 재호출 (디버그 D-007)", false)
            advertiser.enable(true)
        }
    }

    private suspend fun onBluetoothOn() {
        app.activityLog.add(Cat.ADV, null, "BT 켜짐 — ${BT_ON_SETTLE_MS}ms 뒤 서버 재오픈·재광고", false)
        delay(BT_ON_SETTLE_MS)
        server.close(); advertiser.invalidate()
        startAll()
    }

    private fun onBluetoothOff() {
        app.activityLog.add(Cat.ERR, null, "BT 꺼짐 — 광고·GATT 서버 소멸 (켜지면 자동 복구)", true)
        advertiser.invalidate()
        server.close()
        publish()
    }

    // ── 사이클 · 로그 ───────────────────────────────────────────────────

    private val trial = TrialState()

    private fun onReceived(r: EventsCsv.Received) {
        val tagged: EventsCsv.Received = trial.attach(r)
        app.events.add(tagged)
        publish()
    }

    private fun startAutosave() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            while (true) {
                delay(AUTOSAVE_MS)
                if (settingsSnap?.autosave != false && app.events.events.value.isNotEmpty()) saveAll("자동")
            }
        }
    }

    /** events·Activity Log (·cycles) 저장 — 상대 FAQ Q14 제안대로 주기 자동 저장도 같은 경로 */
    private fun saveAll(why: String) {
        runCatching {
            val e = LogExport.saveEvents(this, app.events.csv())
            val p = LogExport.saveActivity(this, app.activityLog.text())
            app.activityLog.add(Cat.GATT, null, "$why 저장 → ${e.name} · ${p.name}", false)
        }.onFailure { app.activityLog.add(Cat.ERR, null, "저장 실패 ${it.javaClass.simpleName}: ${it.message}", true) }
    }

    // ── 알림 ────────────────────────────────────────────────────────────

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "POS 광고", NotificationManager.IMPORTANCE_LOW).apply { description = "iBeacon 광고와 GATT 서버가 켜져 있는 동안 표시됩니다" },
        )
    }

    private fun buildNotification(): Notification {
        val snap = settingsSnap
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val advState = advertiser.state.value
        val title = when {
            advState is PosAdvertiser.State.Started -> "POS 광고 중"
            advState is PosAdvertiser.State.Off -> "POS 광고 OFF (사이클)"
            advState is PosAdvertiser.State.Failed -> "POS 광고 실패"
            else -> "POS 준비 중"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(title)
            .setContentText("Major ${snap?.major ?: Settings.DEFAULT_MAJOR} / Minor ${snap?.minor ?: Settings.DEFAULT_MINOR} · 연결 ${sessions.sessions.value.size}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun publish() {
        _status.value = Status(
            adv = advertiser.state.value, gatt = server.state.value, sessions = sessions.sessions.value.values.toList(),
            connectedCount = sessions.connectedCount, issuedCount = nonces.issuedCount,
            acceptedCount = server.acceptedCount, rejectedCount = server.rejectedCount, payload = advertiser.payload,
        )
        if (_running.value) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
    }

    /** 액티비티가 관찰하는 서비스 상태 */
    data class Status(
        val adv: PosAdvertiser.State = PosAdvertiser.State.Idle,
        val gatt: PosGattServer.State = PosGattServer.State.Closed,
        val sessions: List<SessionRegistry.Session> = emptyList(),
        val connectedCount: Int = 0,
        val issuedCount: Int = 0,
        val acceptedCount: Int = 0,
        val rejectedCount: Int = 0,
        val payload: ByteArray? = null,
    )

    companion object {
        private const val CHANNEL_ID = "pos"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ADV = "dev.mcandle.uwbpos.action.STOP_ADV"
        const val ACTION_SAVE = "dev.mcandle.uwbpos.action.SAVE"
        const val BT_ON_SETTLE_MS: Long = 1_000L
        const val AUTOSAVE_MS: Long = 5 * 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status.asStateFlow()

        fun start(context: Context) { context.startForegroundService(Intent(context, PosService::class.java)) }
        fun stopAdvertising(context: Context) { context.startForegroundService(Intent(context, PosService::class.java).setAction(ACTION_STOP_ADV)) }
        fun save(context: Context) { context.startForegroundService(Intent(context, PosService::class.java).setAction(ACTION_SAVE)) }
        fun stop(context: Context) { context.stopService(Intent(context, PosService::class.java)) }
    }
}
