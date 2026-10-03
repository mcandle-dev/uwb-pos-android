package dev.mcandle.uwbpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.mcandle.uwbpos.ble.PosAdvertiser
import dev.mcandle.uwbpos.ble.PosGattServer
import dev.mcandle.uwbpos.log.LogExport
import dev.mcandle.uwbpos.protocol.IBeaconAd
import dev.mcandle.uwbpos.protocol.toHexSpaced
import dev.mcandle.uwbpos.service.PermissionStatus
import dev.mcandle.uwbpos.service.PosService
import kotlinx.coroutines.launch

/**
 * 단일 화면 호스트 — T2 bring-up 판: 권한 → 시작/중지 → 상태·세션·Activity Log·저장/공유. 3패널(FR-17~20)은 T5 `ui/MainScreen` 으로 교체한다.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (PermissionStatus.evaluate(this).missingRequired.isEmpty()) PosService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val app = App.of(this)
                    val status by PosService.status.collectAsState()
                    val running by PosService.running.collectAsState()
                    val lines by app.activityLog.lines.collectAsState()
                    val events by app.events.events.collectAsState()
                    val perms = PermissionStatus.evaluate(this)
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("UWB POS · spec 001 bring-up", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "서비스 ${if (running) "실행 중" else "중지"} · 광고 ${advLabel(status.adv)} · GATT ${gattLabel(status.gatt)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("연결 ${status.connectedCount} · nonce ${status.issuedCount} · 수락 ${status.acceptedCount} · 거부 ${status.rejectedCount} · 세션 ${status.sessions.size}", style = MaterialTheme.typography.bodySmall)
                        status.payload?.let { Text("AdvData 30B: " + IBeaconAd.advDataBytes(it).toHexSpaced(), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                        if (perms.missingRequired.isNotEmpty()) Text("권한 필요: " + perms.missingRequired.joinToString { it.label }, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startWithPermissions() }) { Text("시작") }
                            OutlinedButton(onClick = { PosService.stopAdvertising(this@MainActivity) }) { Text("광고 중지") }
                            OutlinedButton(onClick = { PosService.stop(this@MainActivity) }) { Text("서비스 종료") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saveAndShare() }) { Text("저장·공유") }
                            OutlinedButton(onClick = { app.events.clear() }) { Text("events 지우기 (${events.size})") }
                        }
                        for (s in status.sessions) {
                            Text("${s.label} ${s.key} · MTU ${s.mtu} · ${if (s.subscribed) "구독" else "-"} · ${s.memberId ?: "payload 전"} · ${s.uwbAddr ?: "—"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                            items(lines.asReversed()) { l ->
                                Text(l.text(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (l.bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startWithPermissions() {
        val missing: List<String> = PermissionStatus.evaluate(this).toRequest
        if (missing.isEmpty()) PosService.start(this) else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun saveAndShare() {
        val app = App.of(this)
        lifecycleScope.launch {
            val files = runCatching {
                listOf(LogExport.saveEvents(this@MainActivity, app.events.csv()), LogExport.saveActivity(this@MainActivity, app.activityLog.text()))
            }.getOrElse { app.activityLog.add(dev.mcandle.uwbpos.log.ActivityLine.Cat.ERR, null, "저장 실패 ${it.message}", true); return@launch }
            LogExport.share(this@MainActivity, files).onFailure { app.activityLog.add(dev.mcandle.uwbpos.log.ActivityLine.Cat.ERR, null, "공유 실패 ${it.message}", true) }
        }
    }

    private fun advLabel(s: PosAdvertiser.State): String = when (s) {
        PosAdvertiser.State.Idle -> "중지"; PosAdvertiser.State.Starting -> "전환 중"
        is PosAdvertiser.State.Started -> "송출 중 (tx ${s.txPower})"; PosAdvertiser.State.Off -> "OFF(사이클)"
        is PosAdvertiser.State.Failed -> "오류 ${s.reason}"
    }

    private fun gattLabel(s: PosGattServer.State): String = when (s) {
        PosGattServer.State.Closed -> "중지"; PosGattServer.State.Opening -> "여는 중"; PosGattServer.State.Ready -> "대기 중"
        is PosGattServer.State.Failed -> "오류 ${s.reason}"
    }
}
