# ARCHITECTURE — uwb-pos-android

- 요구: [REQUIREMENTS.md](REQUIREMENTS.md) · 원칙: [../constitution.md](../constitution.md) · 계약: `uwb-pos-simulator/PROTOCOL.md`
- 참조 구현: 시뮬레이터 `pos_sim/gatt_server.py`(동작), 콘솔 `_reference_console/app/.../ble/GattServerBleOobChannel.kt`(Android API 사용법)

## 1. 시스템 경계

```
손님 앱 (Central, uwb-member-app)                 이 앱 (Peripheral, Android POS)
 PendingIntent 스캔 ◄── iBeacon 광고 (0x004C 02 15 UUID Major Minor Tx) + Scan Response (서비스 UUID) ── AdvertisingSet (같은 주소)
 connectGatt ──────────────────────────────────────────────────────────────────────────────────────────► BluetoothGattServer
 requestMtu(185) · CCCD 구독 · nonce READ · payload WRITE(with response) · result NOTIFY/READ        세션(A1…) · NonceStore · PosEngine · MockLookup
```

## 2. 실행 단위와 수명

| 단위 | 수명 | 하는 일 |
|---|---|---|
| `PosService` (FGS `connectedDevice`) | 사용자가 시작 → 중지할 때까지. 부팅·업데이트 뒤 `BootReceiver`가 다시 띄움 | `PosAdvertiser`·`PosGattServer`·`SessionRegistry`·`PosEngine`·`TrialState` 소유. 알림 "POS 광고 중". `BtStateWatcher`(동적) |
| `MainActivity` + `MainViewModel` | 화면 | 서비스 상태 관찰, 설정 입력, 사이클 조작, 로그 보기·저장. BLE 객체를 잡지 않는다 |
| `App` | 프로세스 | `Settings`(DataStore)·`ActivityLog`·`EventsStore` 보관 |

## 3. 구성 요소 (`dev.mcandle.uwbpos`)

```
protocol/ProtocolConstants.kt  UUID 4종·CCCD·version·TTL·0x80/0x81·result 120B (손님 앱과 동일 파일)
protocol/PayloadCodec.kt       16B decode → Ok | Malformed(LENGTH|VERSION|BCD)         (순수)
protocol/IBeaconAd.kt          23B manufacturer data · Scan Response UUID LE · 범위 검사   (순수)
protocol/ResultJson.kt         result JSON 조립 + 120B 축약                               (순수)
pos/NonceStore.kt              세션별 nonce issue/verify/drop, TTL                        (순수, 시계 주입)
pos/SessionLabel.kt            A1…Z1, A2…                                                 (순수)
pos/MockLookup.kt              mock DB·마스킹·지연                                         (순수 + delay)
pos/SessionRegistry.kt         address → Session(label, openedWall/Mono, memberId, uwb, resultJson, subscribed, mtu)
pos/PosEngine.kt               write 평가 → 응답 코드 → Received → lookup → result → notify 판단. BLE 객체 모름
ble/PosAdvertiser.kt           AdvertisingSet 수명·enable/disable·상태 Flow
ble/PosGattServer.kt           GATT 서버 수명·콜백 → Channel → 단일 소비 코루틴 → PosEngine 호출 → sendResponse/notify
service/PosService.kt          FGS, 알림, BtStateWatcher, 조립
service/BootReceiver.kt        BOOT_COMPLETED·MY_PACKAGE_REPLACED
service/PermissionStatus.kt    SDK 분기 권한 판정                                          (순수에 가까움)
data/Settings.kt               DataStore — 광고 파라미터·TTL·디버그 토글
log/ActivityLog.kt             줄 포맷(순수) + 메모리 2000줄 + 파일 저장
log/EventsCsv.kt               17열 행 조립·직렬화(순수) + 파일 저장
log/LogExport.kt               files/logs 저장 + FileProvider 공유
trial/CyclePlan.kt · TrialState.kt · CycleRunner.kt   사이클 계획(순수)·상태·실행
ui/MainScreen.kt · MainViewModel.kt                   3패널 — 한 LazyColumn(① ② sticky ③). VM 은 status·events·lines·settings 를 combine 만, 조작은 PosService 액션·Settings
```

## 4. 핵심 흐름

### 4-1. 광고 시작
```
사용자 시작 → PosService.startForeground → PosGattServer.open() → onServiceAdded(GATT_SUCCESS)
  → PosAdvertiser.start(IBeaconAd.payload(...), scanResponse=서비스 UUID, interval 160, tx)
  → onAdvertisingSetStarted(txPower) → Activity Log "ADV publisher started" · adv_started_wall 기록 · 배지 송출 중
```
실패(`onAdvertisingSetStarted(status≠0)`, 예산 초과 예외)는 배지 "오류" + ERR + 스낵바. 콜백 전에는 "전환 중".

### 4-2. 연결 → write → result
```
onConnectionStateChange(CONNECTED)      → SessionRegistry.open(address) → 라벨 A1 → "CONN A1 connected <addr> · 세션 생성"
onMtuChanged                            → session.mtu
onDescriptorWriteRequest(CCCD, ENABLE)  → session.subscribed=true → sendResponse → "GATT A1 result notify 구독"
onCharacteristicReadRequest(nonce)      → NonceStore.issue(A1) → sendResponse(4B) → "NONCE A1 read → XXXXXXXX (ttl 30s)"
onCharacteristicWriteRequest(payload)   → PosEngine.evaluate: 길이→version→BCD→nonce
                                          → sendResponse(0 | 0x80 | 0x81)   ← 먼저
                                          → Received(17열 재료) 이벤트 → ③ 패널·events
                                          → 수락이면 MockLookup → ResultJson(≤120B) → session.resultJson
                                          → subscribed && size ≤ mtu−3 ? notify : ERR 로그 (폰은 5초 뒤 READ)
onCharacteristicReadRequest(result, offset) → sendResponse(resultJson[offset:]) — long read
onConnectionStateChange(DISCONNECTED)   → NonceStore.drop · session 폐기 · "CONN A1 disconnected · 세션 폐기"
```
모든 콜백은 `Channel`로 하나의 코루틴에 직렬화된다. 핸들러 예외 → `sendResponse(0x0E)` + ERR.

### 4-3. 사이클
`CyclePlan`(stop→start × N) → `CycleRunner`: OFF = `enableAdvertising(false)`(adv_started_wall=null) → OFF s → ON = `enableAdvertising(true)` →
`onAdvertisingEnabled` ≤10초 대기(미도달 → ERR 중단) → ON s. 각 단계 `CYCLE k/N stop|start` 로그. 끝나면 요약 + 세 파일 자동 저장.

### 4-4. BT 토글·부팅
`BtStateWatcher`(FGS 안 동적 `ACTION_STATE_CHANGED`): OFF → 서버·set 무효(배지 "BT 꺼짐"), ON → 1초 뒤 `open()`→재광고.
`BootReceiver` → `startForegroundService(PosService)`.

## 5. 상태

```
광고 배지: 중지 ─시작─► 전환 중 ─onAdvertisingSetStarted─► 송출 중 ─enable(false)─► OFF(사이클) ─enable(true)─► 송출 중
                                └─status≠0──► 오류                 ─중지─► 중지
GATT 배지: 중지 ─open─► 대기 중(onServiceAdded) ─close/BT OFF─► 중지 · 오류
세션: CONNECTED ─► 열림(nonce 없음) ─nonce READ─► nonce 보유 ─write 수락─► result 보유 ─DISCONNECTED─► 폐기
```

## 6. 스레딩
BLE 콜백 = 바인더 스레드 → `Channel<GattReq>`/`Channel<AdvEvent>` → `PosService` 스코프의 단일 소비 코루틴(Dispatchers.Default).
UI는 `StateFlow` 관찰만. 파일 I/O는 `Dispatchers.IO`.

## 7. 데이터

| 항목 | 저장 | 비고 |
|---|---|---|
| 광고 파라미터(UUID·Major·Minor·Tx·간격·TTL) | DataStore | 송출 중 변경 → "재시작 필요" |
| 디버그 토글 | DataStore, debug 빌드만 | D-009 |
| 세션·nonce·result | 메모리(`SessionRegistry`) | 끊기면 폐기. 저장 안 함 |
| Activity Log | 메모리 2000줄 → `files/logs/pos_*.txt` | 시뮬레이터 줄 형식 |
| events | 메모리 목록 → `files/logs/events_*.csv` | 17열, 지우기는 로그 1행 |
| cycles | `files/logs/cycles_*.csv` | |

## 8. 권한과 매니페스트
REQUIREMENTS FR-23 매트릭스. `BLUETOOTH_SCAN`·위치·배터리 예외 없음. FGS `connectedDevice`. `BootReceiver` 2액션. FileProvider.

## 9. 오류 처리 원칙
constitution §5. GATT 응답 코드 `0`/`0x80`/`0x81`/`0x0E`/`GATT_INVALID_OFFSET`만 쓴다. 다른 값은 결함.

## 10. 테스트 전략

| 계층 | 방법 |
|---|---|
| `PayloadCodec`, `IBeaconAd`, `ResultJson`, `NonceStore`, `SessionLabel`, `MockLookup`(마스킹·JSON), `EventsCsv`, `ActivityLog` 포맷, `CyclePlan` | JVM 단위 테스트. golden = 손님 앱 `PayloadTest`·`ScanFiltersTest`, 시뮬레이터 `tests/` |
| `PosGattServer`·`PosAdvertiser` 이하 | 실기기 + nRF Connect + 손님 앱. REQUIREMENTS §7 |
| 문서 정합 | `protocol-auditor`(Peripheral 판), `peer-repo-auditor`(상대 2개) |

## 11. 시뮬레이터·콘솔과의 차이 요약

| | 시뮬레이터(Windows) | 콘솔(참조) | 이 앱 |
|---|---|---|---|
| 광고 | 2개(iBeacon non-connectable + GATT connectable), 다른 주소 | 서비스 UUID AD, BALANCED, 연결 시 **중지** | 1 set, iBeacon + Scan Response UUID, 100 ms, 연결 중 **유지** |
| 세션 생성 | 첫 GATT 요청 | 연결 | 연결 (D-003) |
| write | with response, 0x80/0x81 | Without Response(거부 못 알림) | with response, 0x80/0x81/0x0E |
| result | NOTIFY + READ, 120B 가드 | 없음 | NOTIFY(MTU−3 가드) + long READ, CCCD 앱이 추가 |
| 주소 | RPA ≈15분 회전 | — | RPA 회전(측정) |
