# CHANGELOG — uwb-pos-android

동작이 바뀌면 여기에 적는다 (constitution §10). 설계가 바뀌면 `docs/ARCHITECTURE.md`도 함께 고친다.
**"Verified"는 실기기(POS 폰 + 손님 앱 폰)로 확인한 것만** 쓴다 (constitution §7).

## 2026-10-03 — spec 001 bring-up 빌드: 광고 + GATT 서버 + 세션·nonce + 로그 + FGS (실기기 미확인)

전부 정적 검토·JVM 단위 테스트 55개·`assembleDebug`·lint 0 errors 까지 — **Verified 아님**. 실기기 절차는 `specs/001-android-pos/device-tests.md`.

### Added
- `protocol/` — `ProtocolConstants`(손님 앱과 동일 파일), `PayloadCodec`(길이→버전→BCD, 판정 문자열 시뮬레이터 동일), `IBeaconAd`(23B, 앞 18B == 손님 앱 필터 prefix 테스트, Scan Response LE),
  `ResultJson`(손 조립, 120B 축약 순서 = `member_lookup.to_json`).
- `pos/` — `NonceStore`(세션별·TTL·불일치 유지·detail 문구), `SessionLabel`, `MockLookup`(DB·마스킹·50ms), `SessionRegistry`(연결 시각 기준, D-003), `PosEngine`(평가 → 응답 코드 → Received → 조회 → NOTIFY 판단, long read).
- `ble/PosGattServer` — 서비스 + **CCCD 앱이 추가**, 콜백 → Channel → 단일 소비 코루틴, 모든 경로 `sendResponse`(예외 → 0x0E), MTU−3 가드, API 33 notify 분기, `onNotificationSent` 대기.
- `ble/PosAdvertiser` — `startAdvertisingSet(legacy·connectable·scannable·160)`, manufacturer 23B + Scan Response 서비스 UUID, OFF/ON `enableAdvertising`(set·주소 유지), 연결 시 set 불변.
- `service/` — `PosService`(FGS connectedDevice·알림·`BtStateWatcher`·5분 자동 저장), `BootReceiver`(BOOT·PACKAGE_REPLACED), `PermissionStatus`(SDK 분기).
- `log/` — `ActivityLine`(시뮬레이터 줄 형식)·`ActivityLog`(2000줄)·`EventsCsv`(17열 헤더 동일·BOM·CRLF·Locale.US)·`EventsStore`(지우기 → `EVENTS cleared N rows`)·`LogExport`(files/logs + FileProvider).
- `trial/` — `CyclePlan`(순수), `TrialState`(cycle·adv_started_wall 부착, late write). 실행기(`CycleRunner`)는 다음.
- `MainActivity` bring-up 화면: 권한 → 시작/광고 중지/서비스 종료 · 저장·공유 · 세션 목록 · Activity Log.

## 2026-10-03 — 리포 생성, spec 001 골격 (T0)

- 리포 `mcandle-dev/uwb-pos-android` 생성. `_reference_console/` = `uwb-console-kotlin` 서브모듈(읽기 전용).
- 거버넌스 문서: `constitution.md`(Peripheral 관점 10절), `CLAUDE.md`, `PEERS.md`(시뮬레이터 = 계약 원본·참조 구현, 손님 앱 = Central), `KICKOFF_PROMPT.md`.
- `docs/REQUIREMENTS.md`(§7 시나리오를 POS 쪽에서, §9 확정값 D-001~D-010), `docs/ARCHITECTURE.md`, `docs/FAQ.md`, `docs/HANDOFF.md`.
- `specs/001-android-pos/` spec·plan·tasks. Gradle 골격은 손님 앱 복사(Gradle 9.3.1·AGP 9.1.1·Kotlin 2.2.10·Compose BOM 2026.02.01), `core-uwb` 제외,
  `minSdk 30`. 매니페스트 권한 매트릭스(30 레거시 2개 / 31+ ADVERTISE·CONNECT / 33+ POST_NOTIFICATIONS / FGS connectedDevice / BOOT). 빈 앱 `assembleDebug`.
- `.claude/skills`(sdd-new-spec·wrap-up 손님 앱 복사), `.claude/agents`(protocol-auditor Peripheral 판·peer-repo-auditor 상대 2개·spec-auditor Kotlin 판).
