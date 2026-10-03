package dev.mcandle.uwbpos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * D-002 — `BOOT_COMPLETED`·`MY_PACKAGE_REPLACED` 에서 [PosService] 를 다시 띄운다 (spec 001 FR-E3).
 * `LOCKED_BOOT_COMPLETED` 는 받지 않는다. T0 골격 — T3 에서 startForegroundService 연결.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // T3
    }
}
