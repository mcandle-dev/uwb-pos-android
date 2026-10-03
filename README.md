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

Windows: `.\gradlew.bat …`. `adb`는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

## 문서

읽는 순서는 [CLAUDE.md](CLAUDE.md) "문서 지도". 원칙은 [constitution.md](constitution.md), 요구사항은 [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md),
설계는 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), 작업 단위는 [specs/](specs/), 변경 이력은 [CHANGELOG.md](CHANGELOG.md).

## 현재 상태

**spec 001 — bring-up 빌드 (T0~T13 중 T10·T14·T15 제외).** 광고(AdvertisingSet)·GATT 서버(CCCD·NOTIFY·0x80/0x81/0x0E)·세션·nonce·mock 조회·FGS·BT 토글·부팅·Activity Log·events CSV·저장/공유가 들어 있고 화면은 bring-up 판(시작/중지/저장·세션·로그). 3패널 화면(T14)과 사이클 실행기(T15)는 다음. **실기기 검증 전 — Acceptance 전부 미체크.** 첫 시험은 `specs/001-android-pos/device-tests.md` S1.
