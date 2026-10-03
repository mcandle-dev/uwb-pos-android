package dev.mcandle.uwbpos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * D-002 — `BOOT_COMPLETED`·`MY_PACKAGE_REPLACED` 에서 [PosService] 를 다시 띄운다 (spec 001 FR-E3). 두 액션은 12+ 백그라운드 FGS 시작
 * 예외 목록에 있다. `LOCKED_BOOT_COMPLETED` 는 받지 않는다. 실패는 결과값(로그는 서비스가 뜨지 못하면 남길 곳이 없어 Log 만).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                runCatching { PosService.start(context) }
                    .onFailure { android.util.Log.w("BootReceiver", "서비스 시작 실패 ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
