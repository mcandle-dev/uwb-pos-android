# PEERS — 이 리포와 계약을 공유하는 상대 리포

`peer-repo-auditor` 에이전트가 이 파일을 읽고 상대 리포를 찾는다. 로컬 경로는 개발자마다 다르므로
**`local_path`를 자기 환경에 맞게 고치고 커밋하지 않는다** (`.git/info/exclude`에 넣거나 PR에서 제외).

## uwb-pos-simulator — 계약 원본 + Peripheral 참조 구현 (Windows)

- **역할**: 이 앱이 대체하는 Windows POS 시뮬레이터. **와이어 계약 `PROTOCOL.md`의 원본**이고, Peripheral 동작의 1차 참조 구현
- **github**: https://github.com/mcandle-dev/uwb-pos-simulator
- **local_path**: `D:\dev\mcandle\uwb-pos-simulator`
- **계약 원본**: `PROTOCOL.md` — **이 리포는 복사본을 두지 않는다**
- **검증된 동작을 읽을 곳**:
  - `PROTOCOL.md` — 광고 바이트(§2), GATT(§3), 세션 격리(§3-0), payload(§3-1), result(§3-2), nonce(§4), 재진입(§5), 확정값(§7)
  - `pos_sim/codec.py` · `nonce.py` · `gatt_server.py` · `member_lookup.py` · `events_csv.py` — 그대로 옮겨야 하는 동작과 문자열
  - `tools/pair_logs.py` · `pos_sim/pairlog.py` — 이 앱의 events CSV·Activity Log를 읽는 도구 (헤더 앞 12열·`publisher started`·`CYCLE k/N`)
  - `CHANGELOG.md` "Verified" · `docs/FAQ.md`(Q10·Q11·Q13·Q14 가 Windows 한계) · `docs/DECISIONS.md`
  - `tests/fixtures/*.csv` — Kotlin 단위 테스트의 golden 값
- **이쪽이 상대에게 주는 것**: Android POS 실측(주소 회전·간격·연결 중 광고)으로 PROTOCOL §2-1·§2-3·§4-1·FAQ Q11·Q12 부기 PR

## uwb-member-app — 손님 앱 (Central)

- **역할**: 이 앱의 상대. iBeacon에 깨어나 GATT로 멤버 ID + UWB 주소를 write 한다
- **github**: https://github.com/mcandle-dev/uwb-member-app
- **local_path**: `D:\dev\mcandle\uwb-member-app`
- **검증된 동작을 읽을 곳**:
  - `docs/REQUIREMENTS.md` §7 시나리오 S1~S12 · §8 완료 기준 — 이 앱의 Acceptance는 저 시나리오를 POS 쪽에서 통과시키는 것
  - `app/src/main/java/dev/mcandle/uwbmember/protocol/{Payload,ScanFilters,ProtocolConstants}.kt` + 테스트 — 바이트 golden 값
  - `ble/PosCentral.kt` — 폰의 GATT 순서(MTU 185 → CCCD → nonce READ → WRITE with response → NOTIFY 5초/READ)와 타임아웃
  - `ble/PosSelector.kt` — 규칙 ①(같은 주소에 iBeacon+서비스 UUID) — Android POS 상대로는 이게 잡혀야 한다
  - `specs/003-reregister/device-tests.md` §4·§6, `specs/004-reentry/` — 시뮬레이터 상대 실측과 감지 지연 문제
  - `docs/logs/app_*.csv` — nonce로 이 앱의 `events_*.csv`와 짝짓는다
- **이쪽이 상대에게 주는 것**: `docs/logs/events_*.csv` · `pos_*.txt` · `cycles_*.csv`, 그리고 손님 앱 005(Android POS 검증)의 상대

## uwb-console-kotlin — 참조 프로젝트 (계약 상대 아님)

- **역할**: UWB 콘솔(Controller). BLE Peripheral 코드(`ble/GattServerBleOobChannel.kt`·`BeaconBleOobChannel.kt`)와 UWB UCI(`device/uci/`)의 출처.
  **프로토콜을 공유하지 않는다** (OOB 사양서 v0.5, UUID 5F1D…)
- **local_path**: `_reference_console/` (서브모듈, 읽기 전용) · 원본 `D:\dev\mcandle\uwb-console-kotlin`
- **읽을 곳**: `specs/009-gatt-server/`, `constitution.md` P1~P18, `docs/repo_guide.md`
