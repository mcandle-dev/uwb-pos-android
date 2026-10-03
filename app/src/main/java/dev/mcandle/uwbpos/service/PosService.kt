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
import dev.mcandle.uwbpos.trial.CyclePlan
import dev.mcandle.uwbpos.trial.CycleRunner
import dev.mcandle.uwbpos.trial.TrialState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    private var autosaveJob: Job? = null
    private var cycleJob: Job? = null
    private var cycleRunner: CycleRunner? = null

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
            ACTION_STOP_ADV -> scope.launch { stopCycle("광고 중지"); stopAdvertising(); publish() }
            ACTION_SAVE -> scope.launch { saveAll("수동") }
            ACTION_CYCLE_START -> startCycle(
                intent.getIntExtra(EXTRA_N, CyclePlan.DEFAULT_N), intent.getIntExtra(EXTRA_OFF_S, CyclePlan.DEFAULT_OFF_S), intent.getIntExtra(EXTRA_ON_S, CyclePlan.DEFAULT_ON_S),
            )
            ACTION_CYCLE_STOP -> scope.launch { stopCycle("사용자 중단") }
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
        cycleJob?.cancel()
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

    // ── 사이클 (T15, 시뮬레이터 spec 002 동등) ─────────────────────────────

    private val trial = TrialState()

    /**
     * OFF/ON 은 `enableAdvertising` (D-005, set·주소 유지). 디버그 D-005 토글이면 stop/start (새 set = 새 주소 — 비교용).
     * 광고가 살아 있어야 시작한다(첫 stop 이 의미를 갖도록). 끝나면 요약 + 세 파일 저장. 돌고 있으면 무시하고 ERR 1줄.
     */
    private fun startCycle(n: Int, offS: Int, onS: Int) {
        if (cycleJob?.isActive == true) { app.activityLog.add(Cat.ERR, null, "사이클이 이미 실행 중 — 중단 뒤 다시", true); return }
        val plan: List<CyclePlan.Step> = runCatching { CyclePlan.plan(n, offS.toDouble(), onS.toDouble()) }
            .getOrElse { app.activityLog.add(Cat.ERR, null, "사이클 설정 오류: ${it.message}", true); return }
        if (!advertiser.isStarted) { app.activityLog.add(Cat.ERR, null, "광고가 송출 중이 아니라 사이클을 시작할 수 없음 — 먼저 \"시작\"", true); return }
        val stopStart: Boolean = settingsSnap?.debugCycleStopStart == true
        val ttl: Int = settingsSnap?.nonceTtlS ?: 30
        app.activityLog.add(Cat.CYCLE, null, "사이클 시작 — ${n}회 · OFF ${offS}s · ON ${onS}s · ttl ${ttl}s · ${if (stopStart) "stop/start (디버그, 새 주소)" else "enableAdvertising (set·주소 유지)"}", false)
        if (CyclePlan.belowSuppression(offS, onS)) app.activityLog.add(Cat.CYCLE, null, "OFF+ON = ${offS + onS}s < ${CyclePlan.SUPPRESSION_S}s — 손님 앱 60초 억제에 걸릴 수 있음", true)
        trial.begin(n)
        val runner = CycleRunner(
            doStop = { if (stopStart) advertiser.stop() else check(advertiser.enable(false)) { "enableAdvertising(false) 거부 — set 없음" } },
            doStart = {
                val t0 = System.currentTimeMillis()
                if (stopStart) startAll() else check(advertiser.enable(true)) { "enableAdvertising(true) 거부 — set 없음" }
                startedSince = t0
            },
            waitStarted = { waitPublisherStarted(startedSince) },
            onStep = { step ->
                val now = System.currentTimeMillis()
                if (step.action == CyclePlan.Action.STOP) trial.onStop(step.k, now) else trial.onStart(step.k, now, onS)
            },
            log = { m, bad -> app.activityLog.add(if (bad) Cat.ERR else Cat.CYCLE, null, m, bad) },
        )
        cycleRunner = runner
        cycleJob = scope.launch {
            val ticker: Job = launch { while (true) { publish(); delay(1_000L) } }
            val result: CycleRunner.Result = try { runner.run(plan) } finally { ticker.cancel() }
            trial.end()
            if (result.cancelled) {
                app.activityLog.add(Cat.CYCLE, null, "중단 — ${result.stoppedAtK ?: 0}/$n 에서", true)
                if (!advertiser.isStarted && advertiser.isSetAlive) advertiser.enable(true) // OFF 에서 끊겼으면 광고 복구
            }
            app.activityLog.add(Cat.CYCLE, null, trial.summary(result.stoppedAtK), false)
            delay(300L) // 마지막 콜백이 로그에 들어올 여유
            saveAll("사이클 종료", cycles = true)
            cycleRunner = null
            publish()
        }
        publish()
    }

    @Volatile private var startedSince: Long = 0L

    /** `onAdvertisingSetStarted`/`onAdvertisingEnabled(true)` 가 [since] 이후에 왔는가 — 10초 한도 (CyclePlan.STARTED_TIMEOUT_MS) */
    private suspend fun waitPublisherStarted(since: Long): Boolean = withTimeoutOrNull(CyclePlan.STARTED_TIMEOUT_MS) {
        advertiser.state.first { it is PosAdvertiser.State.Started && it.sinceWallMs >= since }
    } != null

    private suspend fun stopCycle(why: String) {
        val job = cycleJob ?: return
        if (!job.isActive) return
        app.activityLog.add(Cat.CYCLE, null, "$why — 사이클 중단 요청", false)
        job.cancel()
        job.join()
    }

    // ── 로그 ───────────────────────────────────────────────────────────

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
    private fun saveAll(why: String, cycles: Boolean = trial.rows.isNotEmpty()) {
        runCatching {
            val e = LogExport.saveEvents(this, app.events.csv())
            val c = if (cycles) LogExport.saveCycles(this, EventsCsv.serializeCycles(trial.rows)) else null
            val p = LogExport.saveActivity(this, app.activityLog.text())
            app.activityLog.add(Cat.GATT, null, "$why 저장 → ${listOfNotNull(e, c, p).joinToString(" · ") { it.name }}", false)
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
            cycle = cycleRunner?.progress(),
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
        /** ① 사이클 진행 줄. 안 돌면 null */
        val cycle: CyclePlan.Progress? = null,
    )

    companion object {
        private const val CHANNEL_ID = "pos"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_ADV = "dev.mcandle.uwbpos.action.STOP_ADV"
        const val ACTION_SAVE = "dev.mcandle.uwbpos.action.SAVE"
        const val ACTION_CYCLE_START = "dev.mcandle.uwbpos.action.CYCLE_START"
        const val ACTION_CYCLE_STOP = "dev.mcandle.uwbpos.action.CYCLE_STOP"
        const val EXTRA_N = "n"; const val EXTRA_OFF_S = "off_s"; const val EXTRA_ON_S = "on_s"
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
        fun startCycle(context: Context, n: Int, offS: Int, onS: Int) {
            context.startForegroundService(Intent(context, PosService::class.java).setAction(ACTION_CYCLE_START).putExtra(EXTRA_N, n).putExtra(EXTRA_OFF_S, offS).putExtra(EXTRA_ON_S, onS))
        }
        fun stopCycle(context: Context) { context.startForegroundService(Intent(context, PosService::class.java).setAction(ACTION_CYCLE_STOP)) }
    }
}
