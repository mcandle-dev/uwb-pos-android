# Tasks 001 — Android POS

spec: [spec.md](spec.md) · plan: [plan.md](plan.md) · 승인 2026-10-03. 완료 시 커밋 해시를 항목 끝에 병기한다: `- [x] T1. … (abc1234)`

**건드리지 않는 것**: `uwb-pos-simulator/PROTOCOL.md`(부기는 PR로), `_reference_console/`, 손님 앱 코드.

## 0. 골격

- [x] T0. 리포 `mcandle-dev/uwb-pos-android` 생성 · `_reference_console/` 서브모듈 · constitution/CLAUDE/PEERS/KICKOFF/README/CHANGELOG · docs 4종 · specs/001 3종 · Gradle 골격(손님 앱 복사, core-uwb 제외, minSdk 30) · 매니페스트 권한 매트릭스 · `App`·`MainActivity`·`PosService`·`BootReceiver` 스텁 · `.claude/skills`·`agents` → `:app:assembleDebug` (d93a17a)

## 1. 순수 함수 — 테스트 먼저 (plan 1)

- [x] T1. `protocol/ProtocolConstants.kt`(손님 앱 복사) · `protocol/PayloadCodec.kt` `decode(ByteArray): Decode(Ok|Malformed)`, `uwbStr`, `hexs` · `PayloadCodecTest`: `01 01 23 45 67 89 43 33 18 51 48 5F 00…`→0123456789/43:33/1851485F, 길이 5B→LENGTH, [0]=0x07→VERSION, [3]=0xAB→BCD, `98 76 54 32 10`↔9876543210, `00 00`→미지원, 실폰 `011111222200E6F1C1F376E100000000`→1111222200/E6:F1/C1F376E1 (7672d96)
- [x] T2. `protocol/IBeaconAd.kt` `payload(uuid, major, minor, tx): Result<ByteArray>`, `scanResponseUuidLe(uuid)`, `ADV_DATA_BUDGET=31-3` · `IBeaconAdTest`: `(94C5D726…,1,3,-59)`→`02 15 94 C5 … 9F 20 00 01 00 03 C5`, 앞 18B == iBeaconPrefix(테스트에 손님 앱 함수 복사), 범위 오류 5건, ScanRsp LE `74 9F 28 A5 F9 33 E3 BA 32 42 AA 65 01 00 3C BF`, 23B+4 ≤ 28 (7672d96)
- [x] T3. `protocol/ResultJson.kt` `build(status, member, message): String`(v 첫 키·공백 없음·message 40자·120B 축약 순서·키 삭제·member null) · `pos/MockLookup.kt`(DB 4건·마스킹·50 ms·not_found·error) · `ResultJsonTest`(`{"v":1,"status":"success","member":"홍*동","message":"mock"}` 정확, 가×200, 가×60→member null, not_found) · `MockLookupTest`(김테스트→김**트, 홍길동→홍*동, 2글자 X*, 없는 ID) (7672d96)
- [x] T4. `pos/NonceStore.kt` `issue(key)`, `verify(key, echoed): VerifyResult(verdict OK|EXPIRED|MISMATCH|NOT_ISSUED, ageMs, detail)`, `drop`(시계·난수 주입) · `pos/SessionLabel.kt` · `NonceStoreTest`(소비 1회·+31s 만료 age 31·불일치 유지·세션 독립·재발급 덮어쓰기·detail 문자열) · `SessionLabelTest`(0→A1, 25→Z1, 26→A2) (7672d96)
- [x] T5. `log/EventsCsv.kt` `HEADER`(17), `Received` 모델, `row(Received): List<String>`, `isoMs`, `f3`, `serialize(rows)`(BOM·CRLF·QUOTE_MINIMAL), `CYCLES_HEADER` · `log/ActivityLog.kt` `Line.text()` · `EventsCsvTest`(헤더 golden 문자열, 수동/사이클/거부 행 빈칸 규칙, 12.346, utf-8-sig 바이트, 따옴표 이스케이프) · `ActivityLogTest`(`HH:mm:ss.SSS  ADV   —   msg`, sid 3칸) (7672d96)
- [x] T6. `trial/CyclePlan.kt` `plan(n, offS, onS)`, `progressText`, `belowSuppression(off+on<60)` · `CyclePlanTest`(시뮬레이터 `test_cycle.py` 대응) (7672d96)

## 2. 전파 bring-up (plan 2)

- [x] T7. `pos/SessionRegistry.kt`(address→Session, open/close/label 카운터) · `pos/PosEngine.kt`(`evaluateWrite` → 응답 코드·Received, `afterAccept` → lookup·resultJson·notify 판단 `shouldNotify(mtu)`) — BLE 객체 없음, JVM 테스트 `PosEngineTest`(평가 순서·응답 코드·mtu 가드) (7672d96)
- [x] T8. `ble/PosGattServer.kt` — `open()`(서비스+CCCD, `onServiceAdded` 대기), 콜백→Channel→소비 코루틴, 모든 요청 `sendResponse`, long read, CCCD, `onMtuChanged`, notify(API 33 분기, `onNotificationSent` 대기), 예외→0x0E, `close()`. 출처 주석(`_reference_console/.../GattServerBleOobChannel.kt:201-235,389-395`) (7672d96)
- [x] T9. `ble/PosAdvertiser.kt` — `AdvertisingSet` legacy/connectable/scannable/160/tx, AD manufacturer 23B, ScanRsp 서비스 UUID, 상태 Flow, `enable(bool)`, `stop()`. 연결 콜백에서 set 불변(D-007 토글만 재enable) (7672d96)
- [ ] T10. 임시 `MainActivity` 시작/중지 버튼 + Logcat → **nRF Connect**: nonce READ 4B · 16B WRITE 수락/0x80/0x81 · CCCD 구독 → NOTIFY · result READ offset · CCCD READ → **손님 앱 S1**: `select 규칙 ①`, `write_ack 0`, result notify 63B. (실기기 — 사용자)

## 3. 수명

- [x] T11. `service/PosService.kt` — FGS connectedDevice(`ServiceCompat.startForeground`, 14+ SecurityException runCatching), 알림 "POS 광고 중 · Major N / Minor N"(탭→앱), 광고·서버·세션·엔진 조립, `BtStateWatcher`(동적 STATE_CHANGED, OFF 표시 / ON 1초 뒤 재오픈·재광고), `getConnectedDevices(GATT_SERVER)` 복원, START/STOP 액션 (7672d96)
- [x] T12. `service/BootReceiver.kt` → `startForegroundService` · `data/Settings.kt`(DataStore: uuid·major·minor·tx·interval·ttl·lookupDelay·디버그 토글 6종, debug 빌드만 읽음) (7672d96)

## 4. 로그

- [x] T13. `log/ActivityLog.kt` 메모리 2000줄·StateFlow·저장 `pos_yyyyMMdd_HHmmss.txt` · `log/EventsStore.kt`(Received 목록·지우기 → `EVENTS cleared N rows`) · `log/LogExport.kt`(files/logs 저장·FileProvider 공유·5분 자동 저장) · `trial/TrialState.kt`(adv_started_wall·cycle 부착) (7672d96)

## 5. 화면

- [x] T14. `ui/MainViewModel.kt`(UiState: 광고/GATT 배지·세션 목록·events·로그·설정·사이클 진행·권한) · `ui/MainScreen.kt` 3패널(FR-17~20) · 디버그 카드 · `ui-mockup.html`(시뮬레이터 토큰, 3패널 폰 프레임) — 실기기 SM-G977N 화면 확인(스크린샷). 사이클 "실행" 은 T15 전까지 ERR 로그 1줄

## 6. 사이클

- [ ] T15. `trial/CycleRunner.kt`(enableAdvertising OFF/ON, `onAdvertisingEnabled` ≤10초, 예외·미도달 중단, `CYCLE k/N stop|start` 로그, 요약, late write) · cycles CSV · 종료 시 세 파일 자동 저장 · ① 패널 사이클 줄

## 7. 권한

- [~] T16. `service/PermissionStatus.kt`(SDK 분기) · `MainActivity` 런처(31+ ADVERTISE·CONNECT, 33+ POST_NOTIFICATIONS) — 7672d96 (bring-up 화면). 거부 시 화면 사유는 T14 3패널에서

## 8. 검증·인계

- [x] T17. `specs/001-android-pos/device-tests.md` — §0 준비(POS 폰·손님 앱 폰·nRF Connect·adb), §1 S1·S2·S9·S10·S12·P1·P3·P4·P5·P2, §2 pair_logs 표, §3 상대에 넘길 것, §4 보고 양식 (7672d96)
- [ ] T18. `protocol-auditor`(Peripheral 판) 실행 · lint 0 errors · 정적 검토(모든 콜백 경로 sendResponse, `characteristic.value` 미사용, set stop/start 호출 지점 = 수동 버튼뿐, `_reference_console` diff 0)
- [ ] T19. `/wrap-up` — CHANGELOG · ARCHITECTURE · FAQ · spec 상태 "구현 완료 · 실기기 대기"

## 실기기 확인 후 (사용자)

- [ ] T20. S1·S2·S9·S10·S12 손님 앱 상대 — 로그 세 벌 양쪽 `docs/logs/`
- [ ] T21. P1 연결 중 광고 유지(2번째 스캐너) · P3 재부팅/BT 토글 · P4 nRF Connect 거부 코드 · P5 pair_logs 실행
- [ ] T22. P2 24h 주소 회전 관측 → 상대 PR(PROTOCOL §2-1 Flags·§2-3 표·§4-1 주소 부기·FAQ Q11·Q12) · 손님 앱 `specs/005-android-pos-verify` 착수
- [ ] T23. spec Acceptance 체크 + 상태 "완료"
