# Plan 001 — Android POS

spec: [spec.md](spec.md) — 승인 2026-10-03

## 기술 선택

| 선택 | 근거 |
|---|---|
| `BluetoothLeAdvertiser.startAdvertisingSet(AdvertisingSetParameters legacy·connectable·scannable·interval 160)` | API 26+. 간격을 숫자로 고정(100 ms), `enableAdvertising(bool)` 핸들로 set·주소를 유지한 채 OFF/ON(D-005), `onAdvertisingSetStarted(txPower)`로 실제 Tx 확인. 레거시 `startAdvertising`은 간격 제어가 모드 이름뿐 |
| Flags는 스택에 맡김 | connectable set에 스택이 Flags 3B를 삽입하고 31B 예산에 포함한다. 앱은 manufacturer 27B만 → 30B |
| 세션 키 = `BluetoothDevice.address`, 생성 = CONNECTED | 연결 동안 고정. 시뮬레이터의 "첫 GATT 요청" 대신 연결 이벤트가 있다(D-003) |
| 콜백 → `Channel` → 단일 소비 코루틴 | constitution §1. ATT는 링크당 한 요청이라 직렬 처리가 가장 단순하고 안전. 시뮬레이터 asyncio 루프와 같은 보장 |
| `PosEngine`은 BLE 객체를 모른다 | 평가·조회·NOTIFY 판단을 순수에 가깝게(JVM 테스트 일부 가능). `PosGattServer`가 `sendResponse`/`notify`만 담당 |
| result JSON 손 조립 | `org.json`은 `/`를 `\/`로 이스케이프해 Python `json.dumps`와 바이트가 달라진다. 120B 축약 규칙을 그대로 구현 |
| events CSV 직접 직렬화(`Locale.US`, CRLF, QUOTE_MINIMAL, BOM) | `pair_logs.py`가 Python `csv`로 읽는다. 헤더 앞 12열 문자열 일치가 조건 |
| `PermissionStatus` SDK 분기 | minSdk 30. 31 권한 상수를 30에서 확인하면 영구 DENIED(손님 앱 것 그대로 복사 불가) |
| FGS `connectedDevice` + 동적 `ACTION_STATE_CHANGED` | 손님 앱 003 패턴. 매니페스트로는 못 받는다 |
| 콘솔 코드는 복사·개작, 서브모듈은 pin | 콘솔 repo_guide 규칙. `openServer`·`respond`·long read·끊김 시 재광고 가져오고 연결 시 광고 중지는 **제거** |

## 구현 접근 (단계별)

0. **골격 (T0)** — 리포·서브모듈·문서·Gradle·매니페스트·빈 앱 빌드.
1. **순수 함수 + 테스트 먼저 (T1)** — `PayloadCodec`, `IBeaconAd`, `ResultJson`, `NonceStore`, `SessionLabel`, `MockLookup`, `EventsCsv`, `ActivityLog` 포맷. golden 값은 손님 앱 테스트·시뮬레이터 `tests/`에서.
2. **전파 bring-up (T2)** — `PosAdvertiser` + `PosGattServer` + `SessionRegistry` + `PosEngine` + 버튼 하나 Activity. nRF Connect로 nonce READ·16B WRITE·CCCD·NOTIFY 확인 → 손님 앱 S1. **광고 없이는 손님 앱이 POS를 못 찾으므로 광고와 서버를 함께 올린다.**
3. **수명 (T3)** — `PosService`(FGS·알림·`BtStateWatcher`·복원), `BootReceiver`, `Settings`.
4. **로그 (T4)** — `ActivityLog`·`EventsCsv`·`LogExport`·`TrialState`(adv_started_wall).
5. **화면 (T5)** — 3패널 + 디버그 카드 + ui-mockup.
6. **사이클 (T6)** — `CyclePlan`·`CycleRunner`·cycles CSV·자동 저장.
7. **권한 온보딩 (T7)** — SDK 분기 런처, 14+ `startForeground` SecurityException.
8. **검증·인계 (T8)** — device-tests, `.claude/agents` 3종 점검, protocol-auditor, CHANGELOG, 상대 PR, 손님 앱 005 스캐폴딩.

## 영향 파일

ARCHITECTURE §3 목록 전체 신규. 손님 앱 복사: `protocol/ProtocolConstants.kt`(패키지만), Gradle·res·FileProvider.

## 리스크와 대응

| 리스크 | 대응 |
|---|---|
| RPA 회전으로 "Android는 주소 고정" 전제가 깨져 손님 앱이 15분마다 재전송 | P2 24h 측정 → 손님 앱 005 억제 키 Major/Minor, 상대 FAQ Q11 정정. 사이클은 `enableAdvertising`으로 주소 유지 |
| 연결 뒤 컨트롤러가 광고를 재개하지 않는데 콜백이 없다 | P1 2번째 스캐너 확인 + D-007 디버그 재enable 토글 |
| `sendResponse` 누락 → 폰 30초 ATT 타임아웃·링크 끊김(`status 14`) | 단일 소비 코루틴, 모든 경로 `runCatching` → 0x0E, P4에서 특성·디스크립터 전부 nRF Connect 확인 |
| NOTIFY가 MTU−3에서 조용히 잘림 | 세션별 MTU, 초과 시 생략+ERR(폰은 5초 뒤 READ) |
| events CSV를 `pair_logs.py`가 못 읽음(로케일 소수점·LF·JSON 이스케이프·헤더 drift) | 헤더 golden 테스트, `Locale.US`, 손 조립 JSON, P5 실제 실행 |
| 30 기기에서 31 권한 상수 영구 DENIED / 14+ `startForeground` SecurityException | SDK 분기 `PermissionStatus`, `runCatching` |
| BT OFF가 서버·광고를 조용히 죽임 | FGS 안 동적 리시버, 1초 뒤 재오픈·재광고, `getConnectedDevices`로 복원 |
| `startAdvertisingSet` 예외(예산·간격) | 27B ≤ 28 테스트, 상수 160, 결과값으로 |
| 서버 콜백이 폰의 다른 LE 연결까지 알림 | 라벨만 소비, 문서화(§F-6) |

## 검증 방법

- **JVM**: `PayloadCodecTest`(길이·버전·BCD·왕복·실폰 행), `IBeaconAdTest`(23B golden·iBeaconPrefix 일치·범위·ScanRsp LE), `ResultJsonTest`(정확 문자열·120B 축약 순서·not_found·error),
  `NonceStoreTest`(소비·만료·불일치 유지·세션 독립·재발급), `SessionLabelTest`, `MockLookupTest`(마스킹·DB), `EventsCsvTest`(헤더 golden·행 포맷·빈칸·BOM·CRLF·12.346), `ActivityLogTest`(줄 포맷), `CyclePlanTest`.
- **정적**: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, 매니페스트 권한 매트릭스, `BLUETOOTH_SCAN`·위치 미선언, `protocol-auditor`.
- **실기기** (REQUIREMENTS §7): S1·S2·S9·S10·S12·P1·P3·P4·P5, P2 24h. 로그는 양쪽 `docs/logs/`. `uv run tools/pair_logs.py <events> <app csv> <pos txt>`.
