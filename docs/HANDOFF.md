# HANDOFF — uwb-pos-android

## 1. 리포 구조

```
constitution.md · CLAUDE.md · PEERS.md · KICKOFF_PROMPT.md · README.md · CHANGELOG.md
docs/{REQUIREMENTS,ARCHITECTURE,FAQ,HANDOFF}.md · docs/logs/ (events_*.csv · pos_*.txt · cycles_*.csv — 검증 증거, 추적)
specs/NNN-*/ (spec · plan · tasks · device-tests · ui-mockup.html)
.claude/skills/{sdd-new-spec,wrap-up} · .claude/agents/{protocol-auditor,peer-repo-auditor,spec-auditor}
_reference_console/  uwb-console-kotlin 서브모듈 (읽기 전용)
app/                 단일 모듈 Kotlin/Compose
```

## 2. 에이전트

| 에이전트 | 언제 | 무엇을 |
|---|---|---|
| `protocol-auditor` | `protocol/`·`ble/`·`log/EventsCsv` 변경 뒤, 연동 실패 시 | 광고 23B·Scan Response·GATT 속성/CCCD·decode 순서·nonce TTL·응답 코드·CSV 헤더를 PROTOCOL·시뮬레이터 코드와 대조 |
| `peer-repo-auditor` | spec 쓰기 전, 상대 리포가 바뀐 뒤 | 손님 앱·시뮬레이터의 검증된 사실과 이쪽 전제 대조 |
| `spec-auditor` | 릴리스 전, 큰 변경 뒤 | constitution·ARCHITECTURE·specs ↔ 코드 |

## 3. 참조 코드 출처 (`_reference_console/`)

| 콘솔 파일 | 이쪽 파일 | 가져온 것 / 바꾼 것 |
|---|---|---|
| `ble/GattServerBleOobChannel.kt` | `ble/PosGattServer.kt` | `openServer`(서비스·특성 추가, SecurityException 처리), `respond()`, `onCharacteristicReadRequest(offset)` 잘라서 응답, 끊김 시 광고 재개 / **연결 시 광고 중지 안 함**, Write With Response, CCCD·NOTIFY·MTU·세션 격리 추가 |
| `ble/BeaconBleOobChannel.kt` | `ble/PosAdvertiser.kt` | `AdvertiseCallback` 상태 보고 패턴 / `AdvertisingSet`(legacy·connectable·160)으로 교체, manufacturer AD + Scan Response UUID |
| `MainActivity.kt` 권한 플로우 | `MainActivity.kt`·`service/PermissionStatus.kt` | 런타임 권한 런처 / minSdk 30 분기 |

손님 앱(`uwb-member-app`)에서 그대로 복사: `protocol/ProtocolConstants.kt`, Gradle 골격, `res/`, FileProvider, `.claude/skills`.

## 4. spec 계획

| spec | 내용 | 완료 판정 (REQUIREMENTS §7) |
|---|---|---|
| 001-android-pos | 광고 + GATT 서버 + 로그 + 3패널 + 사이클 (시뮬레이터 동등) | S1·S2·S9·S10·S12·P1·P3·P4·P5 |
| 002-(미정) | UWB 레인징 Controller — 콘솔 `device/uci/` 이식, 손님 앱 UWB 주소로 세션 | 손님 앱 레인징 spec과 짝 |
| 003-(미정) | HMAC 서명 (PROTOCOL §4 2차) | 상대 §7-6 뒤 |

손님 앱 쪽 짝: `uwb-member-app/specs/005-android-pos-verify` (S1~S8 재통과, 규칙 ①, 억제 키, 004 이월 실측).

## 5. 세션 인계 — 2026-10-03 (손님 앱 세션에서 생성·bring-up 까지)

### 지금 상태
- `main` = `db43c95`. spec 001 **T0~T13 완료**(순수 함수·GATT 서버·광고·FGS·로그·bring-up 화면), JVM 테스트 55개·lint 0 errors. **실기기 0회** — Acceptance 전부 미체크.
- 남은 코드: **T14** 3패널 화면(`ui/MainScreen`·`MainViewModel`, `ui-mockup.html` — 손님 앱 `specs/003-reregister/ui-mockup.html` 의 CSS 토큰을 그대로 쓴다), **T15** `trial/CycleRunner`(+ cycles CSV·종료 시 자동 저장·① 사이클 줄), T16 나머지(거부 시 화면 사유), T18 `protocol-auditor`·정적 검토, T19 wrap-up.
- 실기기: **S1 이 첫 시험**이다 (`specs/001-android-pos/device-tests.md` §1). S1 이 안 되면 T14·T15 를 먼저 하지 말고 원인부터.

### 이 세션이 내린 결정 (바꾸려면 REQUIREMENTS §9 를 고친다)
- D-003 `session_opened_at`/`elapsed_s` = **연결 시각**(시뮬레이터는 첫 GATT 요청). `pair_logs.py` 는 두 열을 안 쓴다.
- D-004 **주소는 고정이 아니다** — Android 도 RPA 를 ≈15분마다 바꾼다. "Android POS 는 주소 고정" 이라고 쓰지 말 것. P2 24h 측정.
- D-005 사이클 OFF/ON 은 `enableAdvertising` (set·주소 유지). 수동 중지/시작만 set 종료/생성.
- D-006 Flags AD 는 스택이 넣는다 — PROTOCOL §2-1 `02 01 06` 은 상대 PR 로 "값은 스택" 으로 바꿀 것.
- D-007 연결 중 광고 재enable 은 기본 OFF(스택 자동 재개 신뢰) + 디버그 토글.

### 주의 (코드에서 지킨 것 — 깨지 말 것)
- `PosGattServer` 모든 요청 경로가 `sendResponse` 에 닿는다. 예외 → `0x0E`. 새 콜백 경로를 추가하면 같은 규칙.
- `PosAdvertiser` 는 연결 콜백에서 호출되지 않는다. `PosService.onPhoneConnected` 는 D-007 토글일 때만 `enable(true)`.
- `EventsCsv.HEADER` 문자열·`ActivityLine.text()` 포맷은 테스트가 잠궜다. 바꾸면 `pair_logs.py` 가 깨진다.
- `ResultJson` 은 `org.json` 을 쓰지 않는다(이스케이프 차이). `EventsCsv` 소수는 `Locale.US`.
- `_reference_console/` 안에서 커밋 금지. 소스에 BOM 문자를 리터럴로 넣지 말 것(lint `ByteOrderMark` 에러 — `'\uFEFF'` 이스케이프로).

### 손님 앱 쪽에서 기다리는 것
- `uwb-member-app/specs/005-android-pos-verify` 는 **아직 없다**. 이 리포 S1·S2 가 되면 손님 앱 세션에서 `/sdd-new-spec` 으로 만든다 (HANDOFF §4 005 행에 범위 적어 둠).
- 손님 앱 004 §F-7(조기 종료 가정)·§F-8(`pairlog.py` 세션 경계 버그) 는 이 리포 P4·P5 에서 함께 본다.

### 상대(시뮬레이터) 리포에 넘길 것
device-tests §3 표. PR 은 실측(S1·P1·P2) 뒤에. `peer-repo-auditor` 를 먼저 돌린다.
