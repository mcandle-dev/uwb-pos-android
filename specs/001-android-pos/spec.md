# Spec 001 — Android POS: iBeacon 광고 + GATT 서버 (시뮬레이터 동등)

- **상태**: **승인됨 · 구현 중** (2026-10-03 — 계획 승인과 함께. T0 골격)
- **관련**: [docs/REQUIREMENTS.md](../../docs/REQUIREMENTS.md) §5·§7·§9, [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md),
  손님 앱 `uwb-member-app` specs 002~004(시뮬레이터 상대 실측과 미결), 시뮬레이터 `uwb-pos-simulator` specs 001·002(대체 대상)
- **화면 가이드**: [ui-mockup.html](ui-mockup.html) (T5)
- **와이어 계약**: `uwb-pos-simulator/PROTOCOL.md` §2·§3·§3-0·§3-1·§3-2·§4·§7 — **이 spec은 프로토콜을 바꾸지 않는다.** 상대 문서 부기 2건(§F)만 PR
- **짝 spec**: 손님 앱 `specs/005-android-pos-verify`(예정) — 이 앱을 상대로 S1~S8 재통과
- **참조 코드**: `_reference_console/` (`ble/GattServerBleOobChannel.kt`·`BeaconBleOobChannel.kt`), 시뮬레이터 `pos_sim/*.py`

## 목적 (Why)

손님 앱은 Windows 시뮬레이터 상대로 1차 검증을 마쳤지만, 남은 실측(감지 지연·재진입·옆 POS·주소 회전)은 시뮬레이터의 한계 때문에
결론을 낼 수 없었다. 최종 기기인 Android 11 POS에서 **같은 계약·같은 로그 형식**으로 POS 역할을 하는 앱을 만들어, 손님 앱 S1~S8을
실제 조건에서 다시 통과시키고 PROTOCOL §7 미결(간격·반경·재진입·주소)을 닫을 자료를 낸다.

**이 spec에서 하지 않는 것**: 실제 서버 조회(mock), UWB 레인징(002), HMAC(003), 고정 주소(privileged), iOS 손님 앱 검증.

## 사용자 스토리

1. 매장 운영자가 POS 앱을 켜 두면 알림 "POS 광고 중 · Major 1 / Minor 257"이 뜨고, 손님 폰이 다가오면 ② 패널에 연결·멤버 ID·UWB가 바로 보인다.
2. 시험자가 손님 앱과 같은 절차를 Android POS 상대로 반복하고, events CSV와 손님 앱 CSV를 nonce로 짝지어 `pair_logs.py` 표를 낸다.
3. 시험자가 사이클(OFF 15 · ON 45 × 10)을 돌려 손님 앱 감지 지연 매트릭스를 Android POS 조건에서 다시 잰다.
4. 개발자가 거부(0x80/0x81)·내부 결함(0x0E)·NOTIFY 생략을 Activity Log ERR과 ③ 패널에서 바로 본다.

## 범위에 드는 구성 요소

ARCHITECTURE §3 전체가 이 spec의 신규 코드다 (`protocol/`·`pos/`·`ble/`·`service/`·`data/`·`log/`·`trial/`·`ui/`). 손님 앱에서 복사: `ProtocolConstants.kt`.

## 기능 요구사항

### A. 광고 (REQUIREMENTS FR-1~5 ← PROTOCOL §2)

| ID | 요구 |
|---|---|
| FR-A1 | `IBeaconAd.payload(uuid, major, minor, tx)` = `02 15` + UUID(BE 16B) + major(BE 2B) + minor(BE 2B) + tx(int8) — 23B. 범위 밖(major/minor 0..65535, tx −128..127)은 `Result.failure`. **앞 18B == 손님 앱 `ScanFilters.iBeaconPrefix`** (테스트) |
| FR-A2 | `AdvertisingSetParameters` legacy · connectable · scannable · `INTERVAL_LOW`(160 = 100 ms) · `setTxPowerLevel`(D-009). AdvData = `addManufacturerData(0x004C, 23B)`, `includeDeviceName=false`, `includeTxPowerLevel=false`. Flags 3B는 스택이 넣는다(합 30B). Scan Response = `addServiceUuid(SERVICE_UUID)` |
| FR-A3 | 상태 `Idle / Starting / Started(txPower) / Off(사이클) / Failed(status)` — `onAdvertisingSetStarted/Stopped/Enabled` 콜백으로만 전이 |
| FR-A4 | **연결 중 광고 유지** — 연결 콜백에서 set을 건드리지 않는다. D-007 디버그 토글 ON이면 CONNECTED에 `enableAdvertising(true, 0, 0)` 재호출(오류 status는 로그만) |
| FR-A5 | 사이클 OFF/ON = `enableAdvertising(false/true)` (D-005). 수동 중지/시작 = `stopAdvertisingSet`/`startAdvertisingSet`. 광고 데이터·간격을 바꾸면 재시작 필요 표시 |
| FR-A6 | `onAdvertisingSetStarted` 성공 시각 = `adv_started_wall` (events `adv_started_at`). OFF에서 null |

### B. GATT 서버 (FR-6~12 ← PROTOCOL §3·§3-0·§3-1·§3-2·§4)

| ID | 요구 |
|---|---|
| FR-B1 | 서비스 `BF3C0001` PRIMARY: nonce(`PROPERTY_READ`/`PERMISSION_READ`), payload(`PROPERTY_WRITE`/`PERMISSION_WRITE`), result(`PROPERTY_READ|NOTIFY`/`PERMISSION_READ`) + **CCCD `0x2902` 디스크립터(`PERMISSION_READ|WRITE`) 앱이 추가**. `addService` 비동기 → `onServiceAdded(GATT_SUCCESS)` 뒤에만 광고 |
| FR-B2 | 콜백 → `Channel<GattReq>` → 단일 소비 코루틴. ATT는 링크당 한 요청이라 직렬 처리가 안전 |
| FR-B3 | 세션 = `onConnectionStateChange(CONNECTED)`에서 `device.address` 키로 생성, 라벨 `SessionLabel.of(n)` (A1…Z1, A2…). `openedWall/Mono` 기록. DISCONNECTED → `NonceStore.drop`·세션 폐기·구독 해제 |
| FR-B4 | nonce READ: `NonceStore.issue(key)` 4B `SecureRandom` → `sendResponse(GATT_SUCCESS, 0, 4B)` → `NONCE A1 read → XXXXXXXX (ttl 30s)`. `nonce_issued_at` 기록 |
| FR-B5 | payload WRITE 평가 `PosEngine.evaluate`: `PayloadCodec.decode` 실패 → `0x81`(판정 `길이 오류/버전 오류/BCD 오류`) / `NonceStore.verify` → `미발급/만료/불일치` → `0x80` / `OK` → `0`. **응답 먼저**, 그 뒤 `Received` 이벤트·조회. detail 문자열은 시뮬레이터와 동일 |
| FR-B6 | 수락 → `MockLookup(memberId)`(D-008) → `ResultJson.build` ≤120B → `session.resultJson` → 구독 중이고 `size ≤ mtu−3`이면 `notifyCharacteristicChanged(device, …)`(API 33 분기), 아니면 ERR 로그 생략. `onNotificationSent` 대기. `LOOK A1 lookup → {json} (x ms) · notified=…` |
| FR-B7 | result READ: `sendResponse(GATT_SUCCESS, offset, json[offset:])`, `offset > size` → `GATT_INVALID_OFFSET`. 결과 없으면 빈 값 |
| FR-B8 | CCCD WRITE(ENABLE/DISABLE) → 세션 구독 플래그, `sendResponse`(responseNeeded). CCCD READ → 2B 현재값. `onExecuteWrite` → GATT_SUCCESS |
| FR-B9 | 핸들러 `runCatching` 실패 → `sendResponse(0x0E)` + ERR. `characteristic.value`에 세션 값 저장 금지(공유 객체) |
| FR-B10 | `onMtuChanged` → 세션 mtu(기본 23). 서버는 MTU를 요청하지 않는다 |

### C. 로그 (FR-13~16)

| ID | 요구 |
|---|---|
| FR-C1 | `ActivityLog.Line(wallMs, cat, sid, msg).text()` = `"%s  %-5s %-3s %s"`(`HH:mm:ss.SSS`), cat `ADV GATT CONN NONCE LOOK ERR CYCLE`. 메시지: `ADV start 요청 iBeacon 23B major= minor= tx= — 상태는 콜백으로 확인` · `ADV publisher started (AdvertisingSet 콜백 확인) — on-air 30B, Flags 스택, connectable` · `GATT service advertising started — 3 chars (nonce/payload/result), connectable` · `CONN A1 connected <addr> · 세션 생성` · `NONCE A1 read → …` · `GATT A1 write 16B member= uwb= nonce= → 수락 (detail) · cycle k · 광고 시작 후 N.Ns` · `LOOK A1 lookup → … · notified=` · `CONN A1 disconnected · 세션 폐기` · `CYCLE k/N stop|start`. 메모리 2000줄 |
| FR-C2 | `EventsCsv.HEADER` 17열 **문자열 동일**: `time,session,member_id,uwb,nonce,verdict,issued_nonce,nonce_age_s,elapsed_s,result,raw_hex,detail,adv_started_at,session_opened_at,nonce_issued_at,cycle,adv_to_write_s`. utf-8-sig, RFC4180(QUOTE_MINIMAL, CRLF), 시각 `yyyy-MM-dd'T'HH:mm:ss.SSS` 로컬, 소수 3자리 `Locale.US`, 빈값 빈칸, `uwb` `AA:BB`/`미지원`, `raw_hex` 공백 대문자, `result` JSON 손 조립 |
| FR-C3 | cycles CSV 헤더 `cycle,stop_at,adv_started_at,on_until,writes,first_write_at,adv_to_first_write_s,note` |
| FR-C4 | 저장 `files/logs/{events,pos,cycles}_yyyyMMdd_HHmmss.*` + FileProvider 공유. 사이클 종료 시 자동 저장. 5분 주기 자동 저장(디버그 토글로 끔) |

### D. 화면 (FR-17~20)
ui-mockup(T5)이 진실원천. ① 광고 제어·상태 ② 연결 중인 폰 ③ 수신 이벤트/Activity Log. 지우기 → `EVENTS cleared N rows` 1행. 디버그 카드(D-009).

### E. 수명·권한 (FR-21~23)

| ID | 요구 |
|---|---|
| FR-E1 | 권한 매트릭스: 30 → 없음(레거시 normal 2개) / 31+ `BLUETOOTH_ADVERTISE`·`BLUETOOTH_CONNECT` / 33+ `POST_NOTIFICATIONS`. `PermissionStatus`는 SDK 분기로만 판정 |
| FR-E2 | `PosService` FGS `connectedDevice` — `ServiceCompat.startForeground` 5초 안, 14+ `SecurityException`(권한 전 시작) `runCatching`. 알림 "POS 광고 중 · Major N / Minor N", 탭 → 앱 |
| FR-E3 | `BootReceiver`: `BOOT_COMPLETED`·`MY_PACKAGE_REPLACED` → `startForegroundService` (D-002) |
| FR-E4 | `BtStateWatcher`(FGS 안 동적 `ACTION_STATE_CHANGED`, `RECEIVER_NOT_EXPORTED`): OFF → 서버·set 무효 표시, ON → 1초 뒤 `open`·재광고. `getConnectedDevices(GATT_SERVER)`로 세션 복원 |

## 비기능 요구사항

| ID | 요구 |
|---|---|
| NFR-1 | 순수 함수 전부 JUnit4 (REQUIREMENTS NFR-2). golden: 손님 앱 `PayloadTest`·`ScanFiltersTest`, 시뮬레이터 `tests/test_codec.py`·`test_nonce.py`·`test_result.py`·`test_events_csv.py`·`fixtures/` |
| NFR-2 | 광고 데이터 27B ≤ 31−3 테스트. `startAdvertisingSet` 예외(예산·간격)는 결과값 |
| NFR-3 | `_reference_console/` 수정 금지, 출처 주석 |
| NFR-4 | 릴리스 빌드에 디버그 토글 없음(`BuildConfig.DEBUG`) |
| NFR-5 | 실기기 미확인 항목 Acceptance 체크 금지 |

## F. 설계 결정과 상대 문서·참조 코드와의 차이

1. **Flags AD는 스택이 넣는다** — PROTOCOL §2-1은 `02 01 06`을 적지만 Android 스택은 connectable set에 Flags를 자동 삽입하고 값(0x06/0x1A)은 기기마다 다를 수 있다. 앱은 manufacturer 23B만 넣는다. 손님 앱은 Flags를 보지 않는다 → **상대 PR: "Flags 존재, 값은 스택"** (D-006).
2. **주소는 고정이 아니다** — Android도 RPA를 ≈15분마다 바꾼다(앱이 own address type을 못 고름). 시뮬레이터 FAQ Q11 "Android POS는 고정"은 틀릴 가능성 → P2 24h 측정 뒤 상대 PR. 손님 앱 005에서 억제 키 Major/Minor 결정 (D-004).
3. **세션은 연결 시각에 생긴다** — 시뮬레이터는 첫 GATT 요청(Windows에 연결 이벤트 없음). `session_opened_at`/`elapsed_s` 의미가 바뀌지만 `pair_logs.py`는 두 열을 쓰지 않는다. 상대 FAQ Q12 부기 (D-003).
4. **연결 중 광고 유지 메커니즘** — 규격상 connectable set은 연결 시 종료되고 Android 스택이 재개한다(콜백 없음). 콘솔 `onPhoneConnected`는 광고를 멈추는데(1연결 가정) POS는 반대. P1에서 2번째 스캐너로 확인, D-007 토글.
5. **콘솔과의 차이** — Write Without Response → With Response(0x80/0x81), `AdvertiseSettings` BALANCED → `AdvertisingSet` 160, CCCD·NOTIFY·MTU·세션 격리·nonce 신규. `openServer`·`respond`·long read·끊김 시 재광고는 가져옴.
6. **서버 콜백은 폰의 모든 LE 연결을 알린다** — 우리 서비스를 쓰지 않는 central도 세션 라벨을 소비한다. 결함 아님, 문서화.
7. **MTU는 서버가 정하지 않는다** — 폰의 `requestMtu(185)`에 스택이 응답. 앱은 `onMtuChanged`만 본다.

## 완료 기준 (Acceptance)

로그 세 벌(POS events·Activity Log + 손님 앱 CSV)을 양쪽 `docs/logs/`에 남기고 nonce로 짝짓는다.

- [ ] **S1** 손님 앱 수동 전송 → 세션·nonce·write 수락·LOOK·NOTIFY. 손님 앱 CSV `select 규칙 ①`
- [ ] **S2** 광고 중지→시작 뒤 손님 앱 자동 발화 → OK, events `adv_to_write_s` 기록
- [ ] **S9** UWB 미지원(손님 앱 디버그 토글) → `uwb=미지원`, 조회 수행
- [ ] **S10** 손님 앱 잘못된 nonce → `불일치` 0x80 → 재READ 수락. events 2행
- [ ] **S12** 폰 2대 동시 → 세션 2개·nonce 독립·NOTIFY 각자
- [ ] **P1** 폰 1 연결 중 2번째 스캐너가 POS를 본다 (연결 중 광고 유지)
- [ ] **P3** 재부팅·BT OFF→ON 뒤 광고 배지 복귀, 손님 앱 발화
- [ ] **P4** nRF Connect로 15B / 버전 7 / BCD 0xAB write → `길이 오류/버전 오류/BCD 오류` 0x81, 미발급 0x80, CCCD 읽기 응답
- [ ] **P5** `pair_logs.py` 가 POS events + 손님 앱 CSV를 열 변경 없이 짝짓는다
- [ ] **P2** 24h 광고 중 손님 앱 `pos_addr` 회전 주기 기록 → 상대 PR(§2-3·FAQ Q11)
- [ ] 사이클 10회가 돌고 cycles CSV·events `cycle` 열이 찍힌다 (시뮬레이터 spec 002 T22 동등)
- [ ] JVM 단위 테스트 통과 · `protocol-auditor`(Peripheral 판) 통과 · lint 0 errors
- [ ] 상대 PR: PROTOCOL §2-1 Flags 문구 · §2-3 실측 표 · §4-1 주소 부기 · FAQ Q11 정정 · Q12 elapsed_s 부기
- [ ] CHANGELOG · ARCHITECTURE · FAQ · device-tests 갱신 (`/wrap-up`)
