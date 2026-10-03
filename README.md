# uwb-pos-android

UWB 멤버십 **Android POS 앱** — BLE Peripheral. iBeacon을 광고하고 GATT 서버로 손님 폰의 멤버 ID와 UWB 주소를 받아
(mock) 조회 결과를 돌려준다. Windows 시뮬레이터 `uwb-pos-simulator`를 대체하는 최종 POS 쪽 구현이다.

| | |
|---|---|
| 역할 | BLE **Peripheral** (광고 + GATT 서버). 스캔하지 않는다 |
| 상대 | 손님 앱 [`uwb-member-app`](https://github.com/mcandle-dev/uwb-member-app) (Central) |
| 와이어 계약 | [`uwb-pos-simulator/PROTOCOL.md`](https://github.com/mcandle-dev/uwb-pos-simulator/blob/main/PROTOCOL.md) — 이 리포에 복사본 없음 |
| 참조 코드 | `_reference_console/` = [`uwb-console-kotlin`](https://github.com/mcandle-dev/uwb-console-kotlin) 서브모듈 (읽기 전용) |
| 플랫폼 | Kotlin · Jetpack Compose · minSdk 30 (Android 11) · targetSdk 36 |

## 시작

```bash
git clone --recurse-submodules https://github.com/mcandle-dev/uwb-pos-android.git
cd uwb-pos-android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows: `.\gradlew.bat …`. `adb`는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (PATH에 넣어 두면 아래 명령을 그대로 쓴다).

## 설치 (adb)

POS 폰(Android 11+)에 debug APK를 넣는 절차. 2026-10-03 SM-G977N(Android 12)으로 확인한 순서다.

### 1. 폰 연결 — 무선 디버깅

USB가 있으면 꽂기만 하면 된다. 무선이면 포트가 **두 종류**라는 점만 조심한다.

1. 폰 개발자 옵션 → 무선 디버깅 ON → **"페어링 코드로 기기 페어링"** 팝업을 띄우고 **닫지 않은 채** IP:포트와 6자리 코드를 읽는다.
   팝업의 포트는 **페어링 전용**이고 팝업을 닫으면 바로 만료된다 (`adb connect`에 쓰면 `failed to connect`, 만료 뒤 `pair`하면 `protocol fault`).
2. ```
   adb pair <IP>:<페어링 포트> <6자리 코드>      # Successfully paired …
   adb connect <IP>:<연결 포트>                 # 무선 디버깅 메인 화면 "IP 주소 및 포트"의 번호
   adb devices -l
   ```
3. 한 번 페어링한 폰은 mDNS로 자동 연결되어 `adb-<serial>-…._adb-tls-connect._tcp`로도 보인다. 수동 `connect`까지 하면 같은 폰이 **두 줄**로 잡혀
   `adb install`이 `more than one device/emulator`로 실패한다 → 하나를 `adb disconnect <IP>:<포트>`로 끊거나 모든 명령에 `-s <serial>`을 붙인다.
4. 실패가 반복되면 `adb kill-server` 뒤 재시도. 페어링 포트·코드는 팝업을 열 때마다 새로 나온다.

### 2. 설치·실행·확인

```
adb shell getprop ro.product.model ro.build.version.release ro.build.version.sdk   # device-tests §0 기록
adb install -r app\build\outputs\apk\debug\app-debug.apk                           # Success
adb shell pm list packages | findstr uwbpos                                        # package:dev.mcandle.uwbpos
adb shell dumpsys package dev.mcandle.uwbpos | findstr "versionName lastUpdateTime"
adb shell monkey -p dev.mcandle.uwbpos -c android.intent.category.LAUNCHER 1       # 앱 실행
adb shell pidof dev.mcandle.uwbpos
adb logcat -v time > logcat.txt                                                    # 시험 중 수집
```

- **첫 설치**에는 `MY_PACKAGE_REPLACED`가 오지 않는다 → "POS 광고 중" 알림이 자동으로 뜨지 않는 것이 정상. 재설치(`install -r`)부터 BootReceiver 복귀를 본다 (P3).
- SDK 31+ 폰은 첫 "시작"에 근처 기기(광고·연결) 다이얼로그, 33+ 는 알림 권한 추가. 30 폰은 다이얼로그 없음.
- APK가 소스보다 오래됐는지는 `./gradlew :app:assembleDebug`로 확인한다(내용 해시 기준이라 변경 없으면 그대로 둔다).
- 시험 로그는 앱 "저장·공유" 또는 `adb pull`로 꺼낸다. 실기기 시나리오와 보고 양식은 [specs/001-android-pos/device-tests.md](specs/001-android-pos/device-tests.md).

## 문서

읽는 순서는 [CLAUDE.md](CLAUDE.md) "문서 지도". 원칙은 [constitution.md](constitution.md), 요구사항은 [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md),
설계는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), 작업 단위는 [specs/](specs/), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 현재 상태

**spec 001 — bring-up 빌드 (T0~T13 중 T10·T14·T15 제외).** 광고(AdvertisingSet)·GATT 서버(CCCD·NOTIFY·0x80/0x81/0x0E)·세션·nonce·mock 조회·FGS·BT 토글·부팅·Activity Log·events CSV·저장/공유가 들어 있고 화면은 bring-up 판(시작/중지/저장·세션·로그). 3패널 화면(T14)과 사이클 실행기(T15)는 다음. **실기기 검증 전 — Acceptance 전부 미체크.** 첫 시험은 `specs/001-android-pos/device-tests.md` S1.
