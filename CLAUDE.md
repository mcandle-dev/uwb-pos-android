# CLAUDE.md

UWB 멤버십 **Android POS 앱** — iBeacon을 광고하고 GATT 서버로 폰의 멤버 ID + UWB 주소를 받는다.
BLE **Peripheral**. 상대는 손님 앱 `uwb-member-app`(Central). Windows 시뮬레이터 `uwb-pos-simulator`를 대체한다.

## 문서 지도 — 코드를 읽기 전에

- **불변 원칙**: [constitution.md](constitution.md)
- **요구사항**: [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) — §9 확정값이 구현의 기준
- **설계**: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 실행 단위·구성 요소·흐름·상태
- **와이어 계약**: `PEERS.md`가 가리키는 `uwb-pos-simulator/PROTOCOL.md`. **이 리포에 복사본 없음**
- **FAQ**: [docs/FAQ.md](docs/FAQ.md) — 시험 중 반복된 질문. 새 질문이 나오면 여기에 이어 쓴다
- **상대 리포**: [PEERS.md](PEERS.md) — 시뮬레이터(계약 원본 + Peripheral 참조 구현), 손님 앱(Central). `peer-repo-auditor`
- **작업 단위**: [specs/](specs/) — 새 기능은 코드 전에 spec부터 (`/sdd-new-spec`)
- **참조 코드**: `_reference_console/` (`uwb-console-kotlin`, 서브모듈, **읽기 전용**) — `ble/GattServerBleOobChannel.kt`·`BeaconBleOobChannel.kt`.
  가져온 것과 바꾼 것은 `specs/001-android-pos/spec.md` §F

## 절대 헷갈리지 말 것 — 이 앱은 Peripheral이다

| | 이 리포 | 상대 `uwb-member-app` |
|---|---|---|
| BLE 역할 | **Peripheral** (iBeacon 광고 + GATT 서버) | Central (스캔 + 연결 + write) |
| 데이터 방향 | 받아서 조회, result로 응답 | **폰 → POS** (멤버 ID, UWB addr) |
| 깨우는 쪽 | **깨운다** (iBeacon) | 깨어난다 (PendingIntent 스캔) |

**폰이 광고로 데이터를 돌려주게 만들자는 제안은 거부할 것.** iOS 제약으로 이미 GATT로 결정됐다.

## 함정

- **모든 GATT 요청에 `sendResponse`.** 하나라도 빠지면 폰이 30초 뒤 끊기고 `status 14`를 본다. 예외 경로도 `0x0E`로 응답.
- **CCCD(0x2902)는 앱이 직접 추가한다.** Android는 자동으로 안 붙인다. 없으면 손님 앱의 result 구독이 실패하고 5초 뒤 READ 폴백으로 간다.
- **NOTIFY는 MTU−3 이하.** 넘으면 스택이 조용히 자른다. `onMtuChanged`로 세션별 MTU를 추적하고, 크면 보내지 않고 로그만 (시뮬레이터와 같음).
- **연결됐다고 광고 set을 stop/start하지 말 것.** 새 set = 새 랜덤 주소 → 손님 앱 STICKY 추적이 "새 기기"로 본다. 사이클도 `enableAdvertising`.
- **Flags AD는 스택이 넣는다.** 앱은 manufacturer 23B만 넣는다(27B). PROTOCOL §2-1의 `02 01 06`은 값이 스택마다 다를 수 있다.
- **같은 주소가 보장되는 건 Scan Response뿐이다.** 랜덤 주소 자체는 OS가 ≈15분마다 바꾼다 — "Android POS는 주소 고정"이라고 쓰지 말 것. 측정 항목.
- **`characteristic.value`에 세션 값을 두지 말 것.** 특성 객체는 연결 전체가 공유한다. 값은 `sendResponse`에 직접 넘긴다.
- **12+ 권한 분기.** 30 기기에서 `BLUETOOTH_CONNECT`를 확인하면 영구 DENIED. `PermissionStatus`는 SDK로 가른다.
- **서버 콜백은 폰의 모든 LE 연결을 알린다** (우리 서비스를 안 쓰는 central 포함). 세션 라벨만 소비된다 — 결함 아님.
- **events CSV는 `pair_logs.py`가 읽는다.** 헤더 앞 12열 바이트 동일, utf-8-sig, `Locale.US` 소수점, JSON은 손 조립(`org.json`은 `/`를 이스케이프).

## 환경 제약

- 실기기 **POS 폰(Android 11+) + 손님 앱 폰(SM-S928N)** 이 있어야 검증된다. nRF Connect 폰이 있으면 GATT 단독 확인에 쓴다.
  실행할 수 없는 환경이면 **정적 검토·JVM 단위 테스트까지만** 하고, 실기기 확인이 필요한 항목을 REQUIREMENTS §7 시나리오 번호로 정리해 사용자에게 넘긴다.
- `adb`는 PATH에 없다: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

## 작업 규칙

1. 새 기능은 `specs/NNN-이름/`에 spec → plan → tasks 작성 후 구현 (`/sdd-new-spec`).
2. **프로토콜은 여기서 바꾸지 않는다.** 상대 리포 PROTOCOL.md에 먼저 PR.
3. 바이트 계약·광고 데이터·GATT 속성·CSV 헤더를 건드리면 `protocol-auditor`, 상대 리포 동작에 의존하는 결정을
   하기 전에는 `peer-repo-auditor`를 돌린다.
4. 동작 변경 시 `CHANGELOG.md`, 설계 변경 시 `docs/ARCHITECTURE.md` 갱신 (`/wrap-up`).
5. 실기기로 확인하지 않은 항목을 Acceptance에 체크하지 않는다.
6. 커밋 메시지는 영문, `feat:`/`fix:`/`docs:` 접두사. `_reference_console/` 안에서는 커밋하지 않는다.
