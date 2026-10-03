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
