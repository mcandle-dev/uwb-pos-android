package dev.mcandle.uwbpos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mcandle.uwbpos.App
import dev.mcandle.uwbpos.BuildConfig
import dev.mcandle.uwbpos.ble.PosAdvertiser
import dev.mcandle.uwbpos.data.Settings
import dev.mcandle.uwbpos.log.ActivityLine
import dev.mcandle.uwbpos.log.EventsCsv
import dev.mcandle.uwbpos.log.LogExport
import dev.mcandle.uwbpos.service.PermissionStatus
import dev.mcandle.uwbpos.service.PosService
import dev.mcandle.uwbpos.trial.CyclePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 3패널 화면의 상태 — `specs/001-android-pos/ui-mockup.html`. BLE 객체를 잡지 않는다 (ARCHITECTURE §2):
 * 서비스 상태(`PosService.status`)·로그·events·설정을 **관찰만** 하고, 조작은 `PosService` 액션과 `Settings` 로 보낸다.
 * 입력 초안(`draft`)은 화면에만 있다가 "시작" 에 저장된다 — 송출 중 바뀌면 "재시작 필요" (FR-17).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: App = App.of(application)

    /** ① 입력 초안 — 저장 전 문자열 그대로 (잘못된 값은 저장 시 보정) */
    data class Draft(
        val major: String = Settings.DEFAULT_MAJOR.toString(),
        val minor: String = Settings.DEFAULT_MINOR.toString(),
        val txDbm: String = Settings.DEFAULT_TX_DBM.toString(),
        val intervalMs: String = (Settings.DEFAULT_INTERVAL_UNITS * 0.625).toInt().toString(),
        val ttlS: String = "30",
        val txLevel: String = Settings.DEFAULT_TX_LEVEL.toString(),
        val cycleN: String = CyclePlan.DEFAULT_N.toString(),
        val cycleOffS: String = CyclePlan.DEFAULT_OFF_S.toString(),
        val cycleOnS: String = CyclePlan.DEFAULT_ON_S.toString(),
    )

    enum class Tab { EVENTS, LOG }

    data class UiState(
        val running: Boolean = false,
        val status: PosService.Status = PosService.Status(),
        val settings: Settings.Snapshot? = null,
        val draft: Draft = Draft(),
        /** 송출 중인데 초안이 저장값과 다르다 — 중지 후 시작해야 반영 */
        val needsRestart: Boolean = false,
        val permissions: PermissionStatus.Snapshot = PermissionStatus.Snapshot(emptyList()),
        val events: List<EventsCsv.Received> = emptyList(),
        val lines: List<ActivityLine> = emptyList(),
        val tab: Tab = Tab.EVENTS,
        /** ③ 상세가 펼쳐진 행 — (wallMs, sessionKey). 한 번에 한 행 */
        val selected: Pair<Long, String>? = null,
        val autoScroll: Boolean = true,
        val cycleProgress: CyclePlan.Progress? = null,
        /** 스낵바 1건 — 소비하면 null (FR-20) */
        val snack: String? = null,
        val isDebug: Boolean = BuildConfig.DEBUG,
    ) {
        val cycleBelowSuppression: Boolean
            get() = CyclePlan.belowSuppression(draft.cycleOffS.toIntOrNull() ?: 0, draft.cycleOnS.toIntOrNull() ?: 0)
    }

    private val local = MutableStateFlow(UiState())
    private var draftTouched: Boolean = false

    val state: StateFlow<UiState> = combine(
        local, PosService.running, PosService.status, app.settings.snapshot, app.events.events, app.activityLog.lines,
    ) { arr ->
        val l = arr[0] as UiState
        val status = arr[2] as PosService.Status
        val snap = arr[3] as Settings.Snapshot
        val draft: Draft = if (draftTouched) l.draft else draftFrom(snap, l.draft)
        @Suppress("UNCHECKED_CAST")
        l.copy(
            running = arr[1] as Boolean, status = status, settings = snap, draft = draft,
            needsRestart = status.adv is PosAdvertiser.State.Started && draftDiffers(draft, snap),
            events = (arr[4] as List<EventsCsv.Received>).asReversed(), lines = arr[5] as List<ActivityLine>,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private var lastErrCount: Int = 0

    init {
        // ERR 줄이 새로 생기면 스낵바 (FR-20 세 곳 중 하나)
        viewModelScope.launch {
            app.activityLog.lines.collect { lines ->
                val errs = lines.count { it.bad }
                if (errs > lastErrCount && lastErrCount > 0) lines.lastOrNull { it.bad }?.let { l -> local.update { it.copy(snack = "${l.cat.name} ${l.msg}") } }
                lastErrCount = errs
            }
        }
        refreshPermissions()
    }

    // ── 조작 ─────────────────────────────────────────────────────────────

    fun refreshPermissions() { local.update { it.copy(permissions = PermissionStatus.evaluate(getApplication())) } }

    fun edit(transform: (Draft) -> Draft) { draftTouched = true; local.update { it.copy(draft = transform(it.draft)) } }

    /** "시작" — 초안을 저장하고 서비스 START. 권한은 액티비티가 먼저 확인한다 */
    fun start() {
        val d = local.value.draft
        viewModelScope.launch {
            app.settings.setAdvertising(
                major = d.major.toIntOrNull() ?: Settings.DEFAULT_MAJOR,
                minor = d.minor.toIntOrNull() ?: Settings.DEFAULT_MINOR,
                txDbm = d.txDbm.toIntOrNull() ?: Settings.DEFAULT_TX_DBM,
                intervalUnits = ((d.intervalMs.toIntOrNull() ?: 100) / 0.625).toInt(),
                txLevel = d.txLevel.toIntOrNull() ?: Settings.DEFAULT_TX_LEVEL,
            )
            app.settings.setNonceTtl(d.ttlS.toIntOrNull()?.coerceIn(1, 600) ?: 30)
            draftTouched = false
            PosService.start(getApplication())
        }
    }

    fun stopAdvertising() = PosService.stopAdvertising(getApplication())
    fun stopService() = PosService.stop(getApplication())

    fun saveAndShare() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val files = runCatching {
                listOf(LogExport.saveEvents(ctx, app.events.csv()), LogExport.saveActivity(ctx, app.activityLog.text()))
            }.getOrElse { app.activityLog.add(ActivityLine.Cat.ERR, null, "저장 실패 ${it.message}", true); return@launch }
            app.activityLog.add(ActivityLine.Cat.GATT, null, "수동 저장 → ${files.joinToString(" · ") { it.name }}", false)
            LogExport.share(ctx, files).onFailure { app.activityLog.add(ActivityLine.Cat.ERR, null, "공유 실패 ${it.message}", true) }
        }
    }

    fun clearEvents() { app.events.clear(); local.update { it.copy(selected = null) } }
    fun selectTab(t: Tab) = local.update { it.copy(tab = t) }
    fun toggleAutoScroll(on: Boolean) = local.update { it.copy(autoScroll = on) }
    fun select(r: EventsCsv.Received) = local.update { s ->
        val key = r.wallMs to r.sessionKey
        s.copy(selected = if (s.selected == key) null else key)
    }
    fun consumeSnack() = local.update { it.copy(snack = null) }

    /** 사이클 실행 — 실행기(`trial/CycleRunner`)는 T15. 조용히 리턴하지 않는다 (constitution §5) */
    fun runCycle() {
        app.activityLog.add(ActivityLine.Cat.ERR, null, "사이클 실행기 미구현 (spec 001 T15) — N=${local.value.draft.cycleN} OFF=${local.value.draft.cycleOffS} ON=${local.value.draft.cycleOnS}", true)
    }
    fun stopCycle() {}

    // 디버그 (D-009) — DEBUG 빌드에서만 화면에 나온다
    fun setForceStatus(status: Int?) { viewModelScope.launch { app.settings.setDebugForceStatus(status) } }
    fun setForceNotFound(on: Boolean) { viewModelScope.launch { app.settings.setDebugForceNotFound(on) } }
    fun setLookupDelay(ms: Long) { viewModelScope.launch { app.settings.setLookupDelay(ms) } }
    fun setReenableOnConnect(on: Boolean) { viewModelScope.launch { app.settings.setDebugReenableOnConnect(on) } }
    fun setCycleStopStart(on: Boolean) { viewModelScope.launch { app.settings.setDebugCycleStopStart(on) } }
    fun setAutosave(on: Boolean) { viewModelScope.launch { app.settings.setAutosave(on) } }
    fun setDebugProximityUuid(v: String) { viewModelScope.launch { app.settings.setDebugProximityUuid(v) } }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun draftFrom(s: Settings.Snapshot, keep: Draft): Draft = keep.copy(
        major = s.major.toString(), minor = s.minor.toString(), txDbm = s.txDbm.toString(),
        intervalMs = (s.intervalUnits * 0.625).toInt().toString(), ttlS = s.nonceTtlS.toString(), txLevel = s.txLevel.toString(),
    )

    private fun draftDiffers(d: Draft, s: Settings.Snapshot): Boolean =
        d.major.toIntOrNull() != s.major || d.minor.toIntOrNull() != s.minor || d.txDbm.toIntOrNull() != s.txDbm ||
            d.intervalMs.toIntOrNull() != (s.intervalUnits * 0.625).toInt() || d.ttlS.toIntOrNull() != s.nonceTtlS || d.txLevel.toIntOrNull() != s.txLevel
}
