# KICKOFF_PROMPT — 새 세션 시작용 프롬프트

새 Claude 세션을 시작할 때 아래를 복사해 쓴다. `<...>`만 채운다.

---

## 기본

```
이 리포는 SDD(Spec-Driven Development) 방식으로 개발한다.

시작 전에 다음 순서로 컨텍스트를 로드해:
1. constitution.md — 불변 원칙 (위반 금지)
2. docs/REQUIREMENTS.md — 요구사항. §9 확정값이 기준
3. docs/ARCHITECTURE.md — 설계
4. PEERS.md — 상대 리포 위치. 와이어 계약은 uwb-pos-simulator/PROTOCOL.md 이고 여기서 바꾸지 않는다
5. specs/ 에서 진행 중인 spec의 spec.md / plan.md / tasks.md

오늘 작업: <작업 내용 한 줄>

규칙:
- 이 앱은 BLE **Peripheral**이다 (iBeacon 광고 + GATT 서버). 스캔하지 않는다.
- 모든 GATT 요청에 sendResponse 한다. 예외 경로도 0x0E 로 응답한다.
- 연결됐다고 광고 set 을 stop/start 하지 않는다 (새 랜덤 주소). 사이클은 enableAdvertising.
- 프로토콜을 바꿔야 하면 상대 리포 PROTOCOL.md 에 먼저 PR 을 낸다.
- 상대 리포의 검증된 동작에 의존하는 결정을 하기 전에 peer-repo-auditor 를 돌린다.
- _reference_console/ 은 읽기 전용이다. 가져온 코드는 출처를 주석에 남긴다.
- 실기기(POS 폰 + 손님 앱 폰) 없이는 검증할 수 없다. 실행할 수 없으면 정적 검토와
  JVM 단위 테스트까지만 하고, 실기기 확인이 필요한 항목을 REQUIREMENTS §7 시나리오 번호로 정리해줘.
- 실기기로 확인하지 않은 항목을 Acceptance에 체크하지 마.
- 작업을 마치면 /wrap-up 으로 정리해.
```

## 변형: 2026-10-03 인계 뒤 첫 세션 (S1 bring-up)

```
docs/HANDOFF.md §5 를 먼저 읽어. 이 리포는 손님 앱 세션에서 만들어졌고 spec 001 T0~T13 이 끝나 있다 (실기기 0회).
오늘 작업: specs/001-android-pos/device-tests.md §1 S1 — 손님 앱(uwb-member-app, SM-S928N) 상대 첫 연동.
POS 폰에 app-debug.apk 를 설치하고 "시작" → 손님 앱 "직접 전송". 내가 보고 양식(§4)대로 결과를 알려주면
Activity Log·events CSV·손님 앱 CSV 를 대조해 S1 Acceptance 를 판정하고, 문제가 있으면 protocol-auditor 를 먼저 돌려.
S1 이 되면 T14(3패널 화면)·T15(사이클 실행기)로 간다.
```

## 변형: 연동이 안 될 때

```
증상: <손님 앱 로그 / POS Activity Log 요약, 또는 docs/logs/ 경로>

먼저 peer-repo-auditor 로 손님 앱·시뮬레이터에서 실기기로 확인된 사실과 이쪽 전제가 어긋나는지 보고,
그 다음 protocol-auditor 로 광고 23B 와 GATT 특성 속성, payload decode 를 실제로 찍어서 PROTOCOL.md 와 대조해.
두 보고를 본 뒤에 수정 방향을 제안해. 수정 후 CHANGELOG.md 에 기록해.
```

## 변형: 상대 리포가 바뀐 뒤

```
uwb-member-app(또는 uwb-pos-simulator) 가 업데이트됐다 (git -C <PEERS.md local_path> log --oneline -10 로 확인).
peer-repo-auditor 를 돌려서 PROTOCOL.md·CHANGELOG·FAQ·specs 변화 중 이 앱에 영향 주는 것을 정리하고,
docs/REQUIREMENTS.md 나 docs/ARCHITECTURE.md 를 고쳐야 하면 diff 를 제안해. 코드는 아직 고치지 마.
```
