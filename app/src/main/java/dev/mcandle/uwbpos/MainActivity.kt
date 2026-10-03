package dev.mcandle.uwbpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 단일 화면 호스트 (spec 001 FR-D). T0 단계 — 골격만. 3패널 화면은 T5 에서 `ui/MainScreen` 으로.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("UWB POS", style = MaterialTheme.typography.headlineSmall)
                        Text("spec 001 — 골격 (T0). 광고·GATT 서버는 T2 에서 붙는다.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
