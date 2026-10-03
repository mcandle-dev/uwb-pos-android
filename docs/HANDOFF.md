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

## 5. 세션 인계 — 2026-10-04 (첫 실기기 세션: 크래시 수정 · T14 · T15 · S1/S2/S9/S10/사이클)

### 지금 상태
- `main` = `ad24343` + wrap-up 커밋. spec 001 **코드 T0~T15 완료**(T16 거부 사유 화면·T18 auditor 전체 판 남음). JVM 테스트 63개 · lint 0.
- **실기기 통과**: S1(규칙 ①·조기 종료·MTU 512·notify 63B), S2(감지 지연 0.53/1.05 s), S9, S10, 사이클 10회(`_CYC`) + stop/start 비교(`_CYC2`). 로그는 `docs/logs/`,
  손님 앱 쪽 `uwb-member-app/docs/logs/app_20261004_*.csv`. 짝짓기 표는 device-tests §2.
- **남은 실기기**: S12(폰 2대 — 두 번째 폰/nRF 필요), P1(nRF 연결 중 광고 유지), P3(재부팅·BT 토글), P4(nRF 거부 코드 7행·CCCD), P5(`pair_logs.py`), P2(24h 회전).
- **폰 상태**: POS 폰(SM-G977N, 무선 디버깅 `adb-R3CM40BQ0AJ…`)에 최신 빌드 광고 중. **디버그 "사이클을 stop/start 로" 토글이 ON** — 첫 일로 끈다. 손님 폰(SM-S928N) 자동 전송 ON.

### 이 세션이 확인한 실측 (손님 앱 005 의 입력)
- **같은 주소 재진입은 `FIRST_MATCH` 가 안 온다.** `enableAdvertising` OFF 15/ON 45 × 9: stop 뒤 10 s `MATCH_LOST` 는 오지만 같은 주소가 다시 나와도 OS 가 FIRST_MATCH 를 안 준다.
  stop/start(새 주소)면 매 사이클 1.1~3.1 s 에 FIRST_MATCH. → 깨우는 조건 = 주소 변경. 손님 앱 005: MATCH_LOST 뒤 스캔 재등록 또는 억제 키.
- **RPA 회전 ≤8~13 분**(set 생성 기준 2회 관측) → 회전마다 손님 폰 재전송. P2 24h 로 주기 확정.
- MTU 512(Android 14+ central 은 517 요청), 60 초 억제는 56 s 에 걸림(ON ≥ 50 권장), 손님 폰 시계 오프셋 run 마다 +0.45~0.75 s.
- D-005·D-006 근거 확보: 수동 stop/start = 새 주소(손님 앱 `detect` 주소가 run 마다 다름), Flags 실측 `02 01 06`.

### 이 세션이 고친 결함 (재발 금지)
- `DEFAULT_TX_LEVEL` 은 dBm(−127..1). 레거시 `AdvertiseSettings` 0..3 아님. Builder 체인은 `runCatching` 안.
- `EventsCsv.Received` 의 `equals` 를 좁히지 말 것 — StateFlow 가 replace 를 버린다. `EventsStore.replace` 는 `resultJson` 만 병합(attach 필드 보존).
- logcat 으로 Activity Log 를 볼 때 `grep "I PosActivity"` 금지 — ERR 는 `Log.w`.

### 주의 (코드에서 지킨 것 — 깨지 말 것)
- `PosGattServer` 모든 요청 경로가 `sendResponse` 에 닿는다. 예외 → `0x0E`. 새 콜백 경로를 추가하면 같은 규칙.
- `PosAdvertiser` 는 연결 콜백에서 호출되지 않는다. 사이클은 `enableAdvertising`, 수동 중지/시작과 디버그 토글만 set 종료/생성.
- `EventsCsv.HEADER`·`ActivityLine.text()`·`CYCLE k/N stop|start` 포맷은 테스트가 잠궜다(`pair_logs.py`).
- `ResultJson` 은 `org.json` 안 씀. `EventsCsv` 소수는 `Locale.US`. `_reference_console/` 커밋 금지. BOM 은 `'\uFEFF'` 이스케이프.
- 서비스는 not exported — adb 로 액션 인텐트를 못 보낸다. 저장은 화면 버튼/5분 자동.

### 다음 세션 첫 30분
1. 디버그 토글 OFF. `adb devices` 로 폰 확인(README "설치"). 2. S12·P1·P4 (nRF Connect 폰 필요) · P3 (재부팅·BT 토글) 각 5분. 3. P5 `uv run tools/pair_logs.py` 로 §2 표.
4. P2 는 두 폰을 켜 둔 채 손님 앱 CSV `detect` 주소 변화만 뽑는다. 5. T18 `protocol-auditor` 전체 판 → spec "완료".

### 상대 리포에 넘길 것
- 시뮬레이터 PROTOCOL PR: §2-1 Flags "값은 스택"(실측 `02 01 06`), §2-3 Android 표(규칙 ①·100 ms·요청 1 dBm → 스택 −2 dBm), §4-1 주소 부기(회전 ≤8~13 분), FAQ Q11·Q12. P2 뒤.
- 손님 앱 005 spec: 위 실측 4건 + `docs/logs/*_CYC*`·`app_20261004_010035/011951.csv`.
