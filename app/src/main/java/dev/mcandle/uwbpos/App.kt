package dev.mcandle.uwbpos

import android.app.Application
import android.content.Context

/**
 * 프로세스 단위 객체의 보관소. 광고·GATT 서버·세션은 [service.PosService] 가 소유하고, 여기는 설정·로그처럼
 * 프로세스 수명과 같은 것만 둔다 (ARCHITECTURE §2). DI 프레임워크 없이 lazy 로 (손님 앱과 같은 방식).
 */
class App : Application() {

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}
