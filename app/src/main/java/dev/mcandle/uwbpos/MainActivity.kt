package dev.mcandle.uwbpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mcandle.uwbpos.service.PermissionStatus
import dev.mcandle.uwbpos.ui.MainActions
import dev.mcandle.uwbpos.ui.MainScreen
import dev.mcandle.uwbpos.ui.MainViewModel

/**
 * 단일 화면 호스트 — 권한 런처(31+ ADVERTISE·CONNECT, 33+ POST_NOTIFICATIONS, FR-E1)와 `MainScreen` 연결. BLE 객체를 잡지 않는다.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        vm.refreshPermissions()
        if (PermissionStatus.evaluate(this).missingRequired.isEmpty()) vm.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = MainActions(
            onStart = { startWithPermissions() },
            onStopAdvertising = { vm.stopAdvertising() },
            onStopService = { vm.stopService() },
            onSaveShare = { vm.saveAndShare() },
            onRequestPermissions = { permissionLauncher.launch(PermissionStatus.evaluate(this).toRequest.toTypedArray()) },
            onEdit = { vm.edit(it) },
            onRunCycle = { vm.runCycle() },
            onStopCycle = { vm.stopCycle() },
            onClearEvents = { vm.clearEvents() },
            onTab = { vm.selectTab(it) },
            onAutoScroll = { vm.toggleAutoScroll(it) },
            onSelect = { vm.select(it) },
            onSnackShown = { vm.consumeSnack() },
            onForceStatus = { vm.setForceStatus(it) },
            onForceNotFound = { vm.setForceNotFound(it) },
            onLookupDelay = { vm.setLookupDelay(it) },
            onReenableOnConnect = { vm.setReenableOnConnect(it) },
            onCycleStopStart = { vm.setCycleStopStart(it) },
            onAutosave = { vm.setAutosave(it) },
            onDebugProximityUuid = { vm.setDebugProximityUuid(it) },
        )
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0B7A8C), surface = Color.White, background = Color.White)) {
                val state by vm.state.collectAsStateWithLifecycle()
                MainScreen(state, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    private fun startWithPermissions() {
        val missing: List<String> = PermissionStatus.evaluate(this).toRequest
        if (missing.isEmpty()) vm.start() else permissionLauncher.launch(missing.toTypedArray())
    }
}
