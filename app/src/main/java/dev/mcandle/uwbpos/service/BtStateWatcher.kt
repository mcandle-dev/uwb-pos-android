package dev.mcandle.uwbpos.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * FGS 안 동적 리시버 — spec 001 FR-E4. `BluetoothAdapter.ACTION_STATE_CHANGED` 는 8+ 매니페스트 리시버에 오지 않는다
 * (손님 앱 003 `SystemStateWatcher` 와 같은 이유·같은 패턴). OFF → 서버·set 무효 표시, ON → 호출측이 1초 뒤 재오픈·재광고.
 * [register]/[unregister] 는 `onCreate`/`onDestroy` 짝.
 */
class BtStateWatcher(private val context: Context, private val onBtOn: () -> Unit, private val onBtOff: () -> Unit) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> onBtOn()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBtOff() // 둘 다 온다 — 호출측 멱등
            }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
