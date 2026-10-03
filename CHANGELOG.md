# CHANGELOG — uwb-pos-android

동작이 바뀌면 여기에 적는다 (constitution §10). 설계가 바뀌면 `docs/ARCHITECTURE.md`도 함께 고친다.
**"Verified"는 실기기(POS 폰 + 손님 앱 폰)로 확인한 것만** 쓴다 (constitution §7).

## 2026-10-03 — 리포 생성, spec 001 골격 (T0)

- 리포 `mcandle-dev/uwb-pos-android` 생성. `_reference_console/` = `uwb-console-kotlin` 서브모듈(읽기 전용).
- 거버넌스 문서: `constitution.md`(Peripheral 관점 10절), `CLAUDE.md`, `PEERS.md`(시뮬레이터 = 계약 원본·참조 구현, 손님 앱 = Central), `KICKOFF_PROMPT.md`.
- `docs/REQUIREMENTS.md`(§7 시나리오를 POS 쪽에서, §9 확정값 D-001~D-010), `docs/ARCHITECTURE.md`, `docs/FAQ.md`, `docs/HANDOFF.md`.
- `specs/001-android-pos/` spec·plan·tasks. Gradle 골격은 손님 앱 복사(Gradle 9.3.1·AGP 9.1.1·Kotlin 2.2.10·Compose BOM 2026.02.01), `core-uwb` 제외,
  `minSdk 30`. 매니페스트 권한 매트릭스(30 레거시 2개 / 31+ ADVERTISE·CONNECT / 33+ POST_NOTIFICATIONS / FGS connectedDevice / BOOT). 빈 앱 `assembleDebug`.
- `.claude/skills`(sdd-new-spec·wrap-up 손님 앱 복사), `.claude/agents`(protocol-auditor Peripheral 판·peer-repo-auditor 상대 2개·spec-auditor Kotlin 판).
