# Constitution — uwb-pos-android

이 문서는 이 리포지토리의 **불변 원칙**이다. 개별 spec/plan/tasks보다 우선하며,
변경하려면 이 파일 자체를 수정하는 명시적 결정이 필요하다.

> 출처: `mcandle-dev/uwb-member-app`의 constitution(9절)을 Peripheral 관점으로 뒤집고,
> `mcandle-dev/uwb-pos-simulator`의 constitution(§2 Peripheral·§3 Android 11 호환·§6 nonce)을 가져왔다.
> 참조 코드 규율(서브모듈 읽기 전용)은 `uwb-console-kotlin`의 것을 따른다.

## 1. 비동기 우선

- BLE 작업은 코루틴으로 처리한다. 메인 스레드에서 블로킹 호출을 하지 않는다.
- `BluetoothGattServerCallback`·`AdvertisingSetCallback`은 바인더 스레드에서 온다. 콜백은 `Channel`로
  **단일 소비 코루틴**에 넘기고, 요청 처리는 순차로 읽히게 한다 (ATT는 링크당 한 번에 한 요청이다).

## 2. 이 앱은 Peripheral이다

- **광고와 GATT 서버만** 한다. **스캔하지 않는다.** `BLUETOOTH_SCAN`·위치 권한을 선언하지 않는다.
- 폰 → POS 데이터는 **GATT write**로만 받는다. 폰이 광고로 데이터를 보내게 하자는 안은 재검토하지 않는다
  (`uwb-pos-simulator/PROTOCOL.md` §1 — iOS 제약이 강제한다).
- **모든 GATT 요청에 `sendResponse`한다.** READ·WRITE(responseNeeded)·디스크립터 READ/WRITE·`onExecuteWrite` 전부.
  응답을 빠뜨리면 폰은 30초 ATT 타임아웃 뒤 링크를 끊고 `status 14(0x0E)`를 본다. 내부 예외도 `0x0E`로 **명시 응답**한다.
- 자동 전송·알림 버튼·타일 — 폰이 어느 경로로 왔는지 POS는 모르고, 구분하는 코드도 두지 않는다 (PROTOCOL §5-1).

## 3. minSdk 아래로 내려가지 않는다

- `minSdk`는 REQUIREMENTS.md D-001 확정값(30, Android 11)이다. 그 이하 API를 위한 분기를 만들지 않는다.
- 31+ 블루투스 런타임 권한(`BLUETOOTH_ADVERTISE`·`BLUETOOTH_CONNECT`)과 33+ `POST_NOTIFICATIONS`는 **SDK 분기**로 요청한다.
  30 기기에서 31 권한 상수를 `checkSelfPermission`하면 영구 DENIED로 보인다 — 화면에 거짓으로 빨간 항목을 만들지 않는다.
- 레거시 광고 31B 예산 안에서만 광고한다(iBeacon 27B + 스택이 넣는 Flags 3B). 기기 이름을 광고에 넣지 않는다. 확장 광고를 쓰지 않는다.

## 4. PROTOCOL.md는 상대 리포에 있고, 여기서 바꾸지 않는다

- 와이어 계약의 원본은 `mcandle-dev/uwb-pos-simulator/PROTOCOL.md` 하나다. 이 리포에 복사본을 두지 않는다.
  `PEERS.md`가 위치를 가리킨다. 손님 앱(`uwb-member-app`)도 같은 원본을 본다.
- 프로토콜을 바꿔야 하면 **상대 리포에 먼저 PR**을 내고, 머지된 뒤 이 리포가 따라간다.
- 바이트 계약(`protocol/` 패키지)은 순수 함수로 분리하고 단위 테스트를 둔다. `protocol-auditor`로 PROTOCOL.md와 대조한 뒤 커밋한다.
- 시뮬레이터와 **같은 로그 형식**(events CSV 17열·Activity Log 줄·cycles CSV)을 낸다. 상대 `tools/pair_logs.py`가 그대로 돌아야 한다.

## 5. 방어적 예외 처리 — 예외는 계층을 넘지 않는다

- BLE 호출은 모두 `runCatching`으로 감싼다. 예외는 **결과값(실패 사유)**이 되어 위로 간다.
  `PosGattServer`·`PosAdvertiser` 밖으로 예외가 새어 나가면 결함이다.
- "nonce 불일치", "형식 오류", "조회 실패"는 **정상 결과**이지 예외가 아니다 — 각각 `0x80`·`0x81`·`status:error`로 폰에 돌려준다.
- 실패는 세 곳에 남긴다 — Activity Log(ERR), 화면 배지, 스낵바. **조용히 리턴하지 않는다.**
- UI가 상태를 거짓으로 표시하지 않는다. 광고·GATT 배지는 **콜백으로 확인된 상태**만 보인다.

## 6. 보안 — 평문을 전제로 한다

- BLE는 암호화가 없다. 주변 누구나 수신·녹음한다고 가정한다.
- nonce는 **READ마다 새로** 발급하고, 같은 연결에서 방금 발급한 값과 일치할 때만 수락한다. TTL 30초, 1회 소비. 검증을 생략하지 않는다.
- result JSON에 멤버 ID를 넣지 않는다(마스킹된 이름만). 로그의 멤버 ID는 앞 3자리만 (`012*******`). 디버그 빌드는 예외.

## 7. 실기기 검증 없이 완료로 표시하지 않는다

- BLE 동작은 **실기기(POS 폰 + 손님 앱 폰)**로만 검증된다. JVM 단위 테스트는 순수 함수 계층에만 있고, 필요조건이지 충분조건이 아니다.
- 실기기로 확인하지 않은 항목을 spec Acceptance에 체크하지 않는다.
- 지연은 **POS 로그와 손님 앱 로그를 nonce로 짝지어** 기록한다. 한쪽만으로 판단하지 않는다.

## 8. 수명 규칙

- **광고와 GATT 서버는 FGS(`PosService`) 안에서 산다.** 액티비티가 죽어도 유지된다.
- **연결 중에도 광고를 유지한다** (폰 여러 대가 동시에 발견·연결). 연결됐다고 광고 set을 stop/start하지 않는다 — 새 set은 새 랜덤 주소다.
- **연결이 끊기면 그 세션의 nonce·result·구독을 즉시 폐기한다** (PROTOCOL §3-0).
- 사이클 OFF/ON은 `enableAdvertising(false/true)`로 set을 유지한다. BT OFF→ON·부팅·업데이트 뒤에는 서버를 다시 열고 재광고한다.
- 랜덤 주소(RPA) 회전은 OS가 결정한다(≈15분). 앱은 고정을 **요구하지 않고 측정**한다.

## 9. 참조 코드는 읽기 전용

- `_reference_console/`(`uwb-console-kotlin`)은 git submodule이다. 안에서 커밋하지 않고, 파일을 수정하지 않는다.
  pin은 "이 커밋이 어느 콘솔 커밋을 참조했나"의 기록이다.
- 가져온 코드는 출처(파일:줄)를 주석에 남긴다. 콘솔과 **다르게 한 것**(연결 중 광고 유지, Write With Response, CCCD/NOTIFY)은 spec §F에 적는다.

## 10. 문서 동기화

- 동작이 바뀌면 `CHANGELOG.md`, 설계가 바뀌면 `docs/ARCHITECTURE.md`, 요구가 바뀌면 `docs/REQUIREMENTS.md`를 갱신한다.
  구현과 문서가 다른 상태로 커밋하지 않는다.
- 새 기능은 코드보다 먼저 `specs/NNN-*/`로 정의한다 (spec → plan → tasks).
- 상대 리포(시뮬레이터·손님 앱)의 검증된 동작이 바뀌었는지는 `peer-repo-auditor`로 확인한다.
