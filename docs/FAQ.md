# FAQ — uwb-pos-android

시험 중 반복된 질문을 여기 이어 쓴다. 시뮬레이터 FAQ(`uwb-pos-simulator/docs/FAQ.md`)의 Windows 한계 항목이 Android에서 어떻게 달라지는지부터 시작한다.

## Q1. 시뮬레이터는 폰에 기기 두 개로 보였는데, Android POS는?

**하나로 보인다.** 광고 set 하나에 iBeacon(AdvData)과 서비스 UUID(Scan Response)를 함께 실으므로 주소가 같다. 손님 앱 `PosSelector` 규칙 ①
("iBeacon과 서비스 UUID가 같은 기기")이 처음으로 실제로 잡힌다 — 손님 앱 CSV `select` 행이 `규칙 ①`이어야 한다. 규칙 ③(서비스 UUID만) 폴백은 시뮬레이터 전용이었다.

## Q2. "Android POS는 주소가 고정"이라고 들었는데 맞나?

**아니다, 측정 항목이다.** Android도 광고에 RPA(랜덤 주소)를 쓰고 스택이 ≈15분마다 바꾼다. 앱이 own address type을 고를 수 없다(Android 11에서는 privileged 전용).
시뮬레이터 FAQ Q11의 "Android POS에서는 사라진다"는 틀렸을 가능성이 높아 상대 PR 항목이다. 손님 앱은 STICKY 추적이 주소 기준이라 회전마다
`MATCH_LOST → FIRST_MATCH`가 올 수 있다 — 손님 앱 005에서 억제 키를 Major/Minor로 바꿀지 결정한다. 측정은 손님 앱 `detect` 행 `pos_addr`로(P2).

## Q3. 연결 중에 광고가 멈추지 않나?

BLE 규격상 connectable set은 연결이 맺히면 컨트롤러가 그 광고를 끝낸다. Android 스택은 앱의 enable 플래그가 살아 있으면 **스스로 다시 켠다**(콜백 없음).
그래서 앱은 아무것도 하지 않는 것이 기본(D-007)이고, 두 번째 스캐너로 확인한다(P1). 재개하지 않는 기기면 디버그 토글(연결 시 `enableAdvertising(true)`)을 켠다.
**연결됐다고 set을 stop/start하면 안 된다** — 새 set은 새 주소라 손님 앱이 새 기기로 본다.

## Q4. 시뮬레이터처럼 "첫 GATT 요청"에 세션이 생기나?

Android에는 연결 이벤트가 있어 **연결 즉시** 세션이 생기고 ② 패널에 바로 보인다(D-003). 그래서 `elapsed_s`(연결→수락)는 시뮬레이터(첫 GATT 요청→수락)보다
탐색·MTU·CCCD 구간만큼 길다(≈0.3~1초). 상대 `pair_logs.py`는 이 두 열을 쓰지 않으므로 호환 문제는 없다. 깨어남 지연 자료는 `adv_to_write_s`와 손님 앱 `wake_to_ack_ms`다.

## Q5. 폰이 `status 14`를 받았다

핸들러가 `sendResponse`를 안 한 것이다 (시뮬레이터 FAQ Q9와 같은 증상). 이 앱은 모든 요청 경로와 예외 경로에서 응답하도록 돼 있으므로, 나오면 결함이다 —
Activity Log ERR과 함께 보고한다. 디스크립터 READ(CCCD 읽기)·`onExecuteWrite`도 응답 대상이다.
