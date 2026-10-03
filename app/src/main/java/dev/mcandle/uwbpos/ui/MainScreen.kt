package dev.mcandle.uwbpos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.mcandle.uwbpos.ble.PosAdvertiser
import dev.mcandle.uwbpos.ble.PosGattServer
import dev.mcandle.uwbpos.log.ActivityLine
import dev.mcandle.uwbpos.log.EventsCsv
import dev.mcandle.uwbpos.pos.SessionRegistry
import dev.mcandle.uwbpos.protocol.IBeaconAd
import dev.mcandle.uwbpos.protocol.ProtocolConstants
import dev.mcandle.uwbpos.protocol.toHexSpaced
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 3패널 — specs/001-android-pos/ui-mockup.html. ①②는 고정, ③(수신 이벤트 / Activity Log)이 남은 높이를 차지한다.
 * 색은 의미: 초록 정상 / 주황 알아둘 것(거부·재시작 필요) / 빨강 오류·형식 오류 / 회색 없음. 강조색(signal)은 동작·라벨에만.
 * 토큰은 시뮬레이터 ui-mockup 과 같다 (라이트만, D-012).
 */

private val Ground = Color(0xFFE9EDF0)
private val SurfaceC = Color(0xFFFFFFFF)
private val Sunk = Color(0xFFF3F5F7)
private val Ink = Color(0xFF1A2028)
private val InkSoft = Color(0xFF4A5560)
private val InkFaint = Color(0xFF7A8791)
private val Rule = Color(0xFFD2D9DE)
private val RuleSoft = Color(0xFFE3E8EC)
private val Signal = Color(0xFF0B7A8C)
private val SignalSoft = Color(0xFFDCEFF2)
private val Ok = Color(0xFF3D7A4E)
private val OkSoft = Color(0xFFE2EFE5)
private val Warn = Color(0xFFB4532A)
private val WarnSoft = Color(0xFFF7E7DF)
private val Err = Color(0xFF9E2B2B)
private val ErrSoft = Color(0xFFF6E0E0)

private val TimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private val TimeMsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

class MainActions(
    val onStart: () -> Unit,
    val onStopAdvertising: () -> Unit,
    val onStopService: () -> Unit,
    val onSaveShare: () -> Unit,
    val onRequestPermissions: () -> Unit,
    val onEdit: ((MainViewModel.Draft) -> MainViewModel.Draft) -> Unit,
    val onRunCycle: () -> Unit,
    val onStopCycle: () -> Unit,
    val onClearEvents: () -> Unit,
    val onTab: (MainViewModel.Tab) -> Unit,
    val onAutoScroll: (Boolean) -> Unit,
    val onSelect: (EventsCsv.Received) -> Unit,
    val onSnackShown: () -> Unit,
    val onForceStatus: (Int?) -> Unit,
    val onForceNotFound: (Boolean) -> Unit,
    val onLookupDelay: (Long) -> Unit,
    val onReenableOnConnect: (Boolean) -> Unit,
    val onCycleStopStart: (Boolean) -> Unit,
    val onAutosave: (Boolean) -> Unit,
    val onDebugProximityUuid: (String) -> Unit,
)

@Composable
fun MainScreen(state: MainViewModel.UiState, actions: MainActions) {
    Surface(modifier = Modifier.fillMaxSize(), color = SurfaceC) {
        Column(Modifier.fillMaxSize()) {
            AppBar(state)
            val listState: LazyListState = rememberLazyListState()
            val logScroll = rememberScrollState()
            // Activity Log 자동 스크롤 — 새 줄이 오면 끝으로 (①② 2항목 + 헤더 1항목 뒤)
            LaunchedEffect(state.lines.size, state.tab, state.autoScroll) {
                if (state.tab == MainViewModel.Tab.LOG && state.autoScroll && state.lines.isNotEmpty()) listState.scrollToItem(3 + state.lines.lastIndex)
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item(key = "p1") { PanelAdvertising(state, actions) }
                    item(key = "p2") { PanelConnected(state) }
                    stickyHeader(key = "p3") { PanelEventsHeader(state, actions) }
                    when (state.tab) {
                        MainViewModel.Tab.EVENTS -> eventItems(state, actions)
                        MainViewModel.Tab.LOG -> logItems(state, logScroll)
                    }
                }
                state.snack?.let { msg ->
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 8.dp), containerColor = Ink, contentColor = Ground,
                        action = { TextButton(onClick = actions.onSnackShown) { Text("닫기", color = WarnSoft) } },
                    ) { Text(msg, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

// ───────────────────────────── 앱바 ─────────────────────────────

@Composable
private fun AppBar(state: MainViewState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("uwb-pos-android", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink)
        Spacer(Modifier.weight(1f))
        Mono("Android ${android.os.Build.VERSION.RELEASE} · Peripheral", InkFaint)
    }
    HorizontalDivider(color = Rule)
}

private typealias MainViewState = MainViewModel.UiState

// ───────────────────────────── ① 광고 제어 · 상태 ─────────────────────────────

@Composable
private fun PanelAdvertising(state: MainViewState, actions: MainActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        PanelHead("①", "광고 제어 · 상태")
        // 배지 세 개 — 콜백으로 확인된 상태만 (constitution §5)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            when (val a = state.status.adv) {
                PosAdvertiser.State.Idle -> Badge("광고 중지", InkFaint, Sunk)
                PosAdvertiser.State.Starting -> Badge("광고 전환 중", Warn, WarnSoft)
                is PosAdvertiser.State.Started -> Badge("광고 송출 중 · ${a.txPower} dBm", Ok, OkSoft)
                PosAdvertiser.State.Off -> Badge("광고 OFF (사이클)", Signal, SignalSoft)
                is PosAdvertiser.State.Failed -> Badge("광고 오류 ${a.reason}", Err, ErrSoft)
            }
            when (val g = state.status.gatt) {
                PosGattServer.State.Closed -> Badge("GATT 중지", InkFaint, Sunk)
                PosGattServer.State.Opening -> Badge("GATT 여는 중", Warn, WarnSoft)
                PosGattServer.State.Ready -> Badge("GATT 대기 중", Ok, OkSoft)
                is PosGattServer.State.Failed -> Badge("GATT 오류 ${g.reason}", Err, ErrSoft)
            }
            state.cycleProgress?.let { Badge("사이클 ${it.k}/${it.n} · ${if (it.phaseOff) "OFF" else "ON"}", Signal, SignalSoft) }
                ?: Badge("사이클 없음", InkFaint, Sunk)
        }
        // 31+ 권한 (FR-E1) — 30 폰은 항목이 없어 줄 자체가 안 나온다
        val missing = state.permissions.missingRequired
        if (missing.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).background(ErrSoft, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${missing.joinToString(" · ") { it.label }} 권한이 없어 광고할 수 없습니다", color = Err, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SmallButton("허용", onClick = actions.onRequestPermissions)
            }
        }
        // 입력은 접이식 — 송출 중이면 접힌다 (② 가 첫 화면에 들어오게). 바꾸면 "재시작 필요"
        val started = state.status.adv is PosAdvertiser.State.Started
        Fold(
            "광고 설정 · Major ${state.draft.major} / Minor ${state.draft.minor} · ${state.draft.intervalMs} ms · TTL ${state.draft.ttlS}s",
            initiallyOpen = !started, tag = if (state.needsRestart) "재시작 필요" else null,
        ) {
            val d = state.draft
            Field("Proximity UUID", (state.settings?.proximityUuid ?: ProtocolConstants.PROXIMITY_UUID).toString().uppercase(), enabled = false, onChange = {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Major", d.major, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(major = v) } }
                Field("Minor", d.minor, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(minor = v) } }
                Field("Tx (dBm)", d.txDbm, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(txDbm = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("간격 (ms)", d.intervalMs, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(intervalMs = v) } }
                Field("nonce TTL (s)", d.ttlS, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(ttlS = v) } }
                Field("송신 레벨 (dBm)", d.txLevel, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(txLevel = v) } }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canStart = state.permissions.missingRequired.isEmpty() && state.status.adv !is PosAdvertiser.State.Started && state.status.adv !is PosAdvertiser.State.Starting
            Button(
                onClick = actions.onStart, enabled = canStart || missing.isNotEmpty(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal, disabledContainerColor = SignalSoft, disabledContentColor = Signal),
            ) { Text("시작") }
            OutlinedButton(onClick = actions.onStopAdvertising, enabled = state.status.adv is PosAdvertiser.State.Started || state.status.adv is PosAdvertiser.State.Off, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { Text("광고 중지", color = Ink) }
            OutlinedButton(onClick = actions.onSaveShare, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { Text("저장·공유", color = Ink) }
        }
        if (state.needsRestart) Text("▲ 값이 바뀌었습니다 — 광고 중지 후 시작해야 반영 (새 set = 새 랜덤 주소)", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        else if (state.running && state.status.adv is PosAdvertiser.State.Idle) Text("서비스는 떠 있고 광고만 멈춤 — \"시작\" 으로 새 set", color = InkFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        // 카운터
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Counter("연결", state.status.connectedCount); Counter("nonce", state.status.issuedCount)
            Counter("수락", state.status.acceptedCount); Counter("거부", state.status.rejectedCount)
        }
        CycleRow(state, actions)
        RawFold(state)
        if (state.isDebug) DebugFold(state, actions)
    }
    HorizontalDivider(color = Rule)
}

@Composable
private fun CycleRow(state: MainViewState, actions: MainActions) {
    Column(Modifier.padding(top = 10.dp)) {
        HorizontalDivider(color = RuleSoft)
        val p = state.cycleProgress
        if (p == null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                val d = state.draft
                Field("사이클 N", d.cycleN, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(cycleN = v) } }
                Field("OFF (s)", d.cycleOffS, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(cycleOffS = v) } }
                Field("ON (s)", d.cycleOnS, Modifier.weight(1f)) { v -> actions.onEdit { it.copy(cycleOnS = v) } }
                OutlinedButton(onClick = actions.onRunCycle, enabled = state.status.adv is PosAdvertiser.State.Started, shape = RoundedCornerShape(4.dp)) { Text("실행", color = Ink) }
            }
            if (state.cycleBelowSuppression) Text("▲ OFF+ON < 60s — 손님 앱 재전송 억제(60s)와 겹쳐 발화가 안 보일 수 있음", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(p.text(), InkSoft, Modifier.weight(1f))
                SmallButton("중단", onClick = actions.onStopCycle)
            }
        }
    }
}

@Composable
private fun RawFold(state: MainViewState) {
    val payload = state.status.payload ?: return
    Fold("송출 패킷 raw") {
        val adv = IBeaconAd.advDataBytes(payload)
        FoldLabel("AdvData (앱 ${payload.size + 4}B + Flags 스택)", "${adv.size} / 31 B")
        HexRow(adv.toList().mapIndexed { i, b -> b to hexClassAdv(i) })
        val srsp = IBeaconAd.scanResponseBytes(ProtocolConstants.SERVICE_UUID)
        FoldLabel("ScanRsp (같은 set · 같은 주소)", "${srsp.size} / 31 B")
        HexRow(srsp.toList().mapIndexed { i, b -> b to (if (i < 2) HexClass.HDR else HexClass.UUID) })
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Flags(스택)", Sunk); Key("헤더", SignalSoft); Key("UUID", Signal); Key("Major", OkSoft); Key("Minor", WarnSoft); Key("Tx", ErrSoft)
        }
    }
}

@Composable
private fun DebugFold(state: MainViewState, actions: MainActions) {
    val s = state.settings ?: return
    Fold("디버그", tag = "DEBUG 빌드만") {
        ToggleRow("write 응답 0x80 강제 (nonce 거부)", s.debugForceStatus == 0x80) { actions.onForceStatus(if (it) 0x80 else null) }
        ToggleRow("write 응답 0x81 강제 (형식 오류)", s.debugForceStatus == 0x81) { actions.onForceStatus(if (it) 0x81 else null) }
        ToggleRow("조회 not_found 강제", s.debugForceNotFound, actions.onForceNotFound)
        ToggleRow("조회 지연 ${s.lookupDelayMs} ms → ${if (s.lookupDelayMs >= 2000L) 50 else 2000} ms", s.lookupDelayMs >= 2000L) { actions.onLookupDelay(if (it) 2000L else 50L) }
        ToggleRow("연결 시 광고 재enable (D-007)", s.debugReenableOnConnect, actions.onReenableOnConnect)
        ToggleRow("사이클을 stop/start 로 (D-005 비교용)", s.debugCycleStopStart, actions.onCycleStopStart)
        ToggleRow("5분 자동 저장", s.autosave, actions.onAutosave)
        var uuid by rememberSaveable { mutableStateOf("") }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Proximity UUID 덮어쓰기 (빈칸 = 기본)", uuid, Modifier.weight(1f), keyboard = KeyboardType.Text) { uuid = it }
            SmallButton("적용") { actions.onDebugProximityUuid(uuid) }
        }
    }
}

// ───────────────────────────── ② 연결 중인 폰 ─────────────────────────────

@Composable
private fun PanelConnected(state: MainViewState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        PanelHead("②", "연결 중인 폰", right = "${state.status.sessions.size}대")
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Label("세션", Modifier.width(34.dp)); Label("멤버 ID · 주소", Modifier.weight(1f)); Label("UWB", Modifier.width(56.dp), TextAlign.End); Label("링크", Modifier.width(64.dp), TextAlign.End)
        }
        HorizontalDivider(color = RuleSoft)
        if (state.status.sessions.isEmpty()) Text("연결 없음", color = InkFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        for (s in state.status.sessions) SessionRow(s)
    }
    HorizontalDivider(color = Rule)
}

@Composable
private fun SessionRow(s: SessionRegistry.Session) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Mono(s.label, Signal, Modifier.width(34.dp), bold = true)
        Column(Modifier.weight(1f)) {
            if (s.memberId != null) Text(s.memberId, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            else Text("payload 전", fontSize = 12.sp, color = InkFaint)
            Mono("${s.key} · ${TimeFmt.format(Date(s.openedWallMs))}", InkFaint, size = 10.sp)
        }
        Mono(s.uwbAddr ?: "—", if (s.uwbAddr == null) InkFaint else Ink, Modifier.width(56.dp), size = 13.sp, align = TextAlign.End)
        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End) {
            Mono("MTU ${s.mtu}", InkSoft, size = 10.sp); Mono(if (s.subscribed) "구독 ✓" else "구독 —", if (s.subscribed) InkSoft else InkFaint, size = 10.sp)
        }
    }
    HorizontalDivider(color = RuleSoft)
}

// ───────────────────────────── ③ 수신 이벤트 / Activity Log ─────────────────────────────

@Composable
private fun PanelEventsHeader(state: MainViewState, actions: MainActions) {
    Column(Modifier.fillMaxWidth().background(SurfaceC)) {
        HorizontalDivider(color = Rule)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Num("③"); Spacer(Modifier.width(8.dp))
            Tab("수신 이벤트", state.tab == MainViewModel.Tab.EVENTS) { actions.onTab(MainViewModel.Tab.EVENTS) }
            Tab("Activity Log", state.tab == MainViewModel.Tab.LOG) { actions.onTab(MainViewModel.Tab.LOG) }
            Spacer(Modifier.weight(1f))
            if (state.tab == MainViewModel.Tab.EVENTS) {
                SmallButton("지우기 (${state.events.size})", onClick = actions.onClearEvents)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.autoScroll, onCheckedChange = actions.onAutoScroll, modifier = Modifier.size(28.dp)); Text("자동", fontSize = 11.sp, color = InkSoft)
                }
                Spacer(Modifier.width(6.dp)); SmallButton("저장", onClick = actions.onSaveShare)
            }
        }
        HorizontalDivider(color = Rule)
    }
}

private fun LazyListScope.eventItems(state: MainViewState, actions: MainActions) {
    if (state.events.isEmpty()) {
        item(key = "ev-empty") { Text("아직 수신한 write 가 없습니다", color = InkFaint, fontSize = 12.sp, modifier = Modifier.padding(14.dp)) }
        return
    }
    items(state.events, key = { "ev/${it.wallMs}/${it.sessionKey}/${it.raw.contentHashCode()}" }) { r ->
        val selected = state.selected == (r.wallMs to r.sessionKey)
        val live = state.status.sessions.firstOrNull { it.key == r.sessionKey }
        EventRow(r, live, selected) { actions.onSelect(r) }
        if (selected) EventDetail(r, live)
    }
}

private fun LazyListScope.logItems(state: MainViewState, scroll: ScrollState) {
    if (state.lines.isEmpty()) {
        item(key = "log-empty") { Text("로그 없음", color = InkFaint, fontSize = 12.sp, modifier = Modifier.padding(14.dp)) }
        return
    }
    // 줄마다 같은 ScrollState 를 공유 → 가로 스크롤이 함께 움직인다
    itemsIndexed(state.lines, key = { i, l -> "log/$i/${l.wallMs}" }) { _, l -> LogLine(l, scroll) }
}

@Composable
private fun EventRow(r: EventsCsv.Received, live: SessionRegistry.Session?, selected: Boolean, onClick: () -> Unit) {
    val bg = when { selected -> SignalSoft; !r.accepted -> WarnSoft; else -> Color.Transparent }
    Column(Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        // 1줄: 시각 · 세션 · 접속(주소 축약 · MTU) · 판정 · 경과
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(TimeFmt.format(Date(r.wallMs)), InkSoft)
            Mono(r.sessionLabel, Signal, bold = true)
            Mono(shortAddr(r.sessionKey) + (live?.let { " · MTU ${it.mtu}" } ?: ""), InkFaint, size = 11.sp)
            Spacer(Modifier.weight(1f))
            VerdictBadge(r)
            Mono(r.elapsedMs?.let { "%.2f s".format(Locale.US, it / 1000.0) } ?: "—", InkSoft)
        }
        // 2줄: 멤버 ID · UWB · nonce
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            if (r.memberId == null) Mono("디코딩 실패 · ${r.raw.size}B", InkFaint)
            else {
                Label("멤버"); Text(r.memberId, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Label("UWB"); Mono(r.uwbAddr ?: "—", if (r.uwbAddr == "미지원" || r.uwbAddr == null) InkFaint else Ink)
                Label("nonce"); Mono(r.nonceHex ?: "—", Ink)
            }
        }
        // 3줄: 결과 · 전달 · 사이클
        val line3: String = resultLine(r, live)
        if (line3.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line3, fontSize = 12.sp, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            r.cycle?.let { k -> Mono("cycle $k" + (r.advStartedWallMs?.let { " · 광고 시작 후 %.1fs".format(Locale.US, (r.wallMs - it) / 1000.0) } ?: ""), InkFaint, size = 11.sp) }
        }
    }
    HorizontalDivider(color = RuleSoft)
}

@Composable
private fun EventDetail(r: EventsCsv.Received, live: SessionRegistry.Session?) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(SurfaceC)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Signal))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("상세 · ${r.sessionLabel} · ${TimeMsFmt.format(Date(r.wallMs))}", color = Signal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            KV("접속", r.sessionKey + (r.sessionOpenedWallMs?.let { " · 연결 ${TimeMsFmt.format(Date(it))}" } ?: "") + (live?.let { " · MTU ${it.mtu} · ${if (it.subscribed) "구독 ✓" else "구독 —"}" } ?: " · 끊김"))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                Mono("raw ${r.raw.size}B", InkFaint, Modifier.width(64.dp))
                HexRow(r.raw.toList().mapIndexed { i, b -> b to hexClassPayload(i, r.raw.size) })
            }
            if (r.memberId != null) {
                KV("member", "${r.raw.copyOfRange(1, 6).toHexSpaced()} → BCD → ${r.memberId}")
                KV("uwb", "${r.raw.copyOfRange(6, 8).toHexSpaced()} → ${r.uwbAddr}")
            }
            KV("nonce", buildString {
                append(r.nonceHex ?: "—")
                r.issuedNonceHex?.let { append(" · 발급 $it") }
                r.issuedWallMs?.let { append(" ${TimeMsFmt.format(Date(it))}") }
                r.nonceAgeMs?.let { append(" · 경과 %.3fs".format(Locale.US, it / 1000.0)) }
                append(" → ${r.verdict}")
            })
            if (r.detail.isNotEmpty()) KV("detail", r.detail)
            r.resultJson?.let { KV("result", it) }
            if (r.accepted) KV("전달", deliveryText(r, live))
        }
    }
    HorizontalDivider(color = RuleSoft)
}

@Composable
private fun LogLine(l: ActivityLine, scroll: ScrollState) {
    val catColor = when (l.cat) {
        ActivityLine.Cat.ADV, ActivityLine.Cat.LOOK -> Signal
        ActivityLine.Cat.NONCE -> Ok
        ActivityLine.Cat.ERR -> Err
        ActivityLine.Cat.CYCLE -> Warn
        else -> Ink
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 14.dp, vertical = 1.dp)) {
        Mono(TimeMsFmt.format(Date(l.wallMs)).substring(3), InkFaint, size = 11.sp)
        Spacer(Modifier.width(6.dp))
        Mono(l.cat.name.padEnd(5), catColor, size = 11.sp, bold = true)
        Spacer(Modifier.width(6.dp))
        Mono((l.sid ?: ActivityLine.NO_SESSION).padEnd(3), Ink, size = 11.sp)
        Spacer(Modifier.width(6.dp))
        Mono(l.msg, if (l.bad) Warn else InkSoft, size = 11.sp, softWrap = false)
    }
}

// ───────────────────────────── 조각 ─────────────────────────────

@Composable
private fun PanelHead(num: String, title: String, right: String? = null) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Num(num)
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkSoft, letterSpacing = 0.6.sp)
        right?.let { Mono(it, InkFaint) }
    }
}

@Composable
private fun Num(n: String) {
    Text(n, color = Signal, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.border(1.dp, Signal, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp))
}

@Composable
private fun Badge(text: String, color: Color, bg: Color) {
    Row(Modifier.background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VerdictBadge(r: EventsCsv.Received) {
    val (c, bg) = when {
        r.accepted -> Ok to OkSoft
        r.verdict.endsWith("오류") -> Err to ErrSoft
        else -> Warn to WarnSoft
    }
    Text(r.verdict, color = c, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, keyboard: KeyboardType = KeyboardType.Number, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label.uppercase(), fontSize = 10.sp, color = InkFaint, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (enabled) Ink else InkSoft),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).border(1.dp, Rule, RoundedCornerShape(4.dp)).background(if (enabled) SurfaceC else Sunk, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Counter(label: String, n: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Mono(label, InkSoft); Mono(n.toString(), Ink, bold = true) }
}

@Composable
private fun Fold(title: String, tag: String? = null, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(initiallyOpen) { mutableStateOf(initiallyOpen) }
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider(color = RuleSoft)
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (open) "▾" else "▸", color = Signal, fontSize = 11.sp)
            Text(title, fontSize = 12.sp, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            tag?.let { Text(it, color = Warn, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.border(1.dp, Warn, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) }
        }
        if (open) Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun FoldLabel(l: String, r: String) {
    Row(Modifier.fillMaxWidth()) { Mono(l, InkFaint); Spacer(Modifier.weight(1f)); Mono(r, InkFaint) }
}

private enum class HexClass { FLAGS, HDR, UUID, MAJ, MIN, TX, VER, MEM, UWB, NONCE, RSV }

/** AdvData 30B: Flags 3 · 헤더 6 (len type 4C 00 02 15) · UUID 16 · Major 2 · Minor 2 · Tx 1 */
private fun hexClassAdv(i: Int): HexClass = when {
    i < 3 -> HexClass.FLAGS; i < 9 -> HexClass.HDR; i < 25 -> HexClass.UUID; i < 27 -> HexClass.MAJ; i < 29 -> HexClass.MIN; else -> HexClass.TX
}

/** payload 16B: ver 1 · member 5 · uwb 2 · nonce 4 · reserved 4 */
private fun hexClassPayload(i: Int, size: Int): HexClass = when {
    size != 16 -> HexClass.RSV
    i == 0 -> HexClass.VER; i < 6 -> HexClass.MEM; i < 8 -> HexClass.UWB; i < 12 -> HexClass.NONCE; else -> HexClass.RSV
}

@Composable
private fun HexRow(bytes: List<Pair<Byte, HexClass>>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((b, c) in bytes) {
            val (fg, bg) = when (c) {
                HexClass.FLAGS, HexClass.RSV -> InkFaint to Sunk
                HexClass.HDR, HexClass.VER -> Signal to SignalSoft
                HexClass.UUID, HexClass.MEM -> Color.White to Signal
                HexClass.MAJ, HexClass.NONCE -> Ok to OkSoft
                HexClass.MIN, HexClass.UWB -> Warn to WarnSoft
                HexClass.TX -> Err to ErrSoft
            }
            Text("%02X".format(b), color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.background(bg, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun Key(label: String, bg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(bg, RoundedCornerShape(2.dp)).border(if (bg == Sunk) 1.dp else 0.dp, if (bg == Sunk) Rule else Color.Transparent, RoundedCornerShape(2.dp)))
        Text(label, fontSize = 10.sp, color = InkFaint)
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Mono(k, InkFaint, Modifier.width(64.dp)); Mono(v, InkSoft, Modifier.weight(1f))
    }
}

@Composable
private fun Tab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Signal else InkFaint)
        Spacer(Modifier.height(2.dp).fillMaxWidth().background(if (selected) Signal else Color.Transparent))
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp), modifier = Modifier.height(28.dp)) {
        Text(text, fontSize = 11.sp, color = Ink)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = InkSoft, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text.uppercase(), fontSize = 10.sp, color = InkFaint, letterSpacing = 0.5.sp, modifier = modifier, textAlign = align)
}

@Composable
private fun Mono(text: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 12.sp, bold: Boolean = false, align: TextAlign? = null, softWrap: Boolean = true) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = modifier, textAlign = align, softWrap = softWrap)
}

private fun shortAddr(a: String): String = if (a.length >= 17) "${a.take(5)}:…:${a.takeLast(2)}" else a

private fun resultLine(r: EventsCsv.Received, live: SessionRegistry.Session?): String {
    if (!r.accepted) return r.detail
    val json = r.resultJson ?: return "조회 중…"
    val status = Regex("\"status\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "?"
    val member = Regex("\"member\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    return listOfNotNull(status, member, deliveryShort(json, live)).joinToString(" · ")
}

private fun deliveryShort(json: String, live: SessionRegistry.Session?): String {
    val n = json.toByteArray(Charsets.UTF_8).size
    val limit = live?.notifyLimit ?: return "${n}B"
    return if (live.subscribed && n <= limit) "notify ${n}B" else "NOTIFY 생략 ${n}B > ${limit}B · READ"
}

private fun deliveryText(r: EventsCsv.Received, live: SessionRegistry.Session?): String {
    val json = r.resultJson ?: return "조회 중"
    val n = json.toByteArray(Charsets.UTF_8).size
    return if (live == null) "${n}B · 세션 끊김 (전달 당시 MTU 는 Activity Log LOOK 줄)"
    else if (live.subscribed && n <= live.notifyLimit) "NOTIFY ${n}B ≤ ${live.notifyLimit} (MTU−3)"
    else if (!live.subscribed) "구독 없음 → 폰이 READ 로 (5초 폴백)"
    else "NOTIFY 생략 ${n}B > ${live.notifyLimit}B → READ"
}
