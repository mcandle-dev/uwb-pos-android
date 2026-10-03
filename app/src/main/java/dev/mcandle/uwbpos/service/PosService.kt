package dev.mcandle.uwbpos.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * FGS(connectedDevice) — 광고·GATT 서버·세션의 소유자 (spec 001 FR-E2, ARCHITECTURE §2).
 * T0 골격. T3 에서 startForeground·알림·BtStateWatcher·광고/서버 수명을 붙인다.
 */
class PosService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
