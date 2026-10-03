package dev.mcandle.uwbpos.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 권한 판정 — spec 001 FR-E1, constitution §3. **SDK 로 가른다**: 30 에는 런타임 권한이 없고(레거시 normal 2개는 설치 시 부여),
 * 31+ 는 `BLUETOOTH_ADVERTISE`·`BLUETOOTH_CONNECT`, 33+ 는 `POST_NOTIFICATIONS`. 30 기기에서 31 상수를 확인하면 영구 DENIED 로 보여
 * 화면이 거짓 빨강이 된다 — 그래서 손님 앱 것을 그대로 쓰지 않는다. 위치·스캔은 없다 (광고·서버에 불필요).
 */
object PermissionStatus {

    data class Item(val key: String, val label: String, val granted: Boolean, val required: Boolean, val hint: String)

    data class Snapshot(val items: List<Item>) {
        val missingRequired: List<Item> get() = items.filter { it.required && !it.granted }
        val allGranted: Boolean get() = items.all { it.granted }
        /** 런처에 넘길 것 — 없는 것만 */
        val toRequest: List<String> get() = items.filter { !it.granted }.map { it.key }
    }

    /** 이 SDK 에서 요청해야 하는 런타임 권한 */
    fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun evaluate(context: Context): Snapshot {
        fun has(p: String): Boolean = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val items = ArrayList<Item>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items += Item(Manifest.permission.BLUETOOTH_ADVERTISE, "근처 기기 (광고)", has(Manifest.permission.BLUETOOTH_ADVERTISE), true, "iBeacon 광고를 내보냅니다")
            items += Item(Manifest.permission.BLUETOOTH_CONNECT, "근처 기기 (연결)", has(Manifest.permission.BLUETOOTH_CONNECT), true, "GATT 서버로 폰의 연결을 받습니다")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            items += Item(Manifest.permission.POST_NOTIFICATIONS, "알림", has(Manifest.permission.POST_NOTIFICATIONS), false, "상시 알림 \"POS 광고 중\" 표시")
        }
        return Snapshot(items)
    }

    /** 14+ `connectedDevice` FGS 는 BLUETOOTH_* 중 하나가 있어야 startForeground 가 된다 */
    fun canStartConnectedDeviceFgs(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
}
