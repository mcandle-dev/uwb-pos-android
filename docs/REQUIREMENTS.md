# REQUIREMENTS — uwb-pos-android (Android POS)

- **상태**: 승인 2026-10-03 (손님 앱 계획 승인과 함께). §9 확정값이 구현의 기준
- **와이어 계약**: `uwb-pos-simulator/PROTOCOL.md` (복사본 없음, `PEERS.md`). 이 문서는 계약을 **인용**만 한다
- **상대**: 손님 앱 `uwb-member-app` `docs/REQUIREMENTS.md` §7 시나리오 S1~S12 — 같은 번호를 POS 쪽에서 본다

## 1. 목적

Windows 시뮬레이터(`uwb-pos-simulator`)가 하던 POS 역할을 **최종 기기인 Android 11 POS**에서 한다.
시뮬레이터의 Windows 한계(iBeacon·GATT가 두 기기로 보임, 연결 이벤트 없음, 광고 간격 설정 불가, RSSI 없음)를 걷어내고,
손님 앱이 남긴 미결(감지 지연 매트릭스, 재진입 S8, 옆 POS 오연결, 주소 회전)을 실제 조건에서 잴 수 있게 한다.

## 2. 범위

### 포함 (spec 001)
- iBeacon 광고(connectable, 100 ms) + Scan Response(서비스 UUID) **같은 광고 set**
- GATT 서버: nonce READ / payload WRITE(with response) / result READ·NOTIFY(+CCCD), 세션 격리, 0x80·0x81 거부
- mock 멤버 조회(시뮬레이터 DB·지연 그대로) → result JSON ≤120B
- 로그: Activity Log(시뮬레이터 줄 형식) · events CSV 17열 · cycles CSV · 공유(FileProvider)
- 화면 3패널(광고 제어·연결 중인 폰·수신 이벤트/Activity Log), 사이클 실행기
- FGS 수명(부팅·업데이트·BT 토글 뒤 재광고), 12+ 권한 분기

### 제외 (별도 spec 또는 다른 팀)
- 실제 서버 조회(mock만) · 회원 DB
- UWB 레인징(Controller) — 콘솔 `device/uci/` 를 가져오는 2단계 spec
- HMAC 서명(PROTOCOL §4 2차) · 고정 BLE 주소(privileged 설치가 필요, §9 D-004)
- iOS 손님 앱 상대 검증(손님 앱 리포 범위)

## 3. 용어

| 용어 | 뜻 |
|---|---|
| 광고 set | `AdvertisingSet` 1개 = 주소 1개. AdvData(iBeacon) + Scan Response(서비스 UUID) |
| 세션 | 폰 1연결 = 세션 1개 (`A1, B1 …`). nonce·result·구독이 세션에 속한다 (PROTOCOL §3-0) |
| 판정(verdict) | write 평가 결과 `OK / 만료 / 불일치 / 미발급 / 길이 오류 / 버전 오류 / BCD 오류` — 시뮬레이터 문자열 그대로 |
| Activity Log | `HH:MM:SS.mmm  CAT   SID  메시지` 줄 (`pos_*.txt`) |
| events CSV | write 1건 = 1행, 17열 (`events_*.csv`) — 상대 `pair_logs.py` 입력 |

## 4. 사용자 스토리

1. **매장 운영자**: POS 앱을 켜 두면 알림 "POS 광고 중 · Major 1 / Minor 257"이 뜨고, 손님 폰이 다가오면 ② 패널에 연결·멤버 ID·UWB가 바로 보인다.
2. **시험자**: 손님 앱과 같은 절차(S1~S8)를 Android POS 상대로 반복하고, events CSV와 손님 앱 CSV를 nonce로 짝지어 지연 표를 낸다.
3. **시험자**: 사이클(OFF 15 · ON 45 × 10)을 돌려 손님 앱 감지 지연 매트릭스를 Android POS 조건에서 다시 잰다.
4. **개발자**: 거부(0x80/0x81)·내부 결함(0x0E)·NOTIFY 생략이 Activity Log ERR과 ③ 패널에 그대로 보인다.

## 5. 기능 요구사항

### 5-1. 광고 (PROTOCOL §2)
| ID | 요구 |
|---|---|
| FR-1 | manufacturer data = `02 15` + Proximity UUID 16B + Major(2, BE) + Minor(2, BE) + Tx(1, int8) **23B** (`0x004C`). 앞 18B가 손님 앱 `ScanFilters.iBeaconPrefix`와 같다. Flags 3B는 스택이 넣는다(합 30B ≤ 31) |
| FR-2 | Scan Response = 서비스 UUID 128-bit 목록 (`11 07` + UUID LE 16B). 같은 set → 같은 주소 → 손님 앱 규칙 ① |
| FR-3 | connectable · legacy · 간격 100 ms(`INTERVAL_LOW`=160) · Tx 레벨 설정 가능(D-009) · 기기 이름 미포함 |
| FR-4 | **연결 중에도 광고 유지** (폰 여러 대). set을 stop/start하지 않는다. 사이클 OFF/ON은 `enableAdvertising` |
| FR-5 | 광고·GATT 상태는 콜백(`onAdvertisingSetStarted/Stopped/Enabled`, `onServiceAdded`)으로 확인된 것만 표시 |

### 5-2. GATT 서버 (PROTOCOL §3·§3-0·§3-1·§3-2·§4)
| ID | 요구 |
|---|---|
| FR-6 | 서비스 `BF3C0001` + nonce(READ) + payload(WRITE with response) + result(READ·NOTIFY + CCCD 0x2902). `onServiceAdded` 뒤에만 광고 시작 |
| FR-7 | 세션 = `onConnectionStateChange` CONNECTED 시 생성(주소 키, 라벨 A1…), DISCONNECTED 시 nonce·result·구독 즉시 폐기 |
| FR-8 | nonce READ마다 4B 새 난수 발급(세션별), 응답 뒤 Activity Log `NONCE read → XXXXXXXX (ttl 30s)` |
| FR-9 | payload WRITE 평가 순서 **길이 16 → version 0x01 → BCD → nonce(미발급/만료/불일치/일치)**. 디코딩 실패 → `0x81`, nonce 실패 → `0x80`, 일치 → `0`. 응답은 **평가 즉시**, 조회는 응답 뒤 |
| FR-10 | 수락이면 mock 조회 → result JSON(≤120B, PROTOCOL §3-2 축약 규칙) 저장 → **구독한 그 세션에만** NOTIFY. 크기 > MTU−3이면 보내지 않고 ERR 로그(폰은 READ 폴백). result READ는 `offset`부터 |
| FR-11 | 모든 요청에 `sendResponse`. 핸들러 예외 → `0x0E` 명시 응답 + ERR |
| FR-12 | MTU 요청은 스택이 처리. `onMtuChanged`로 세션 MTU 추적(기본 23) |

### 5-3. 로그 (시뮬레이터 동등)
| ID | 요구 |
|---|---|
| FR-13 | Activity Log 줄 `HH:MM:SS.mmm  {cat:<5} {sid:<3} {msg}`, cat `ADV GATT CONN NONCE LOOK ERR CYCLE`, 메시지 문구 시뮬레이터와 동일(`publisher started`·`CYCLE k/N stop|start` 리터럴 포함). 메모리 2000줄, 저장 `pos_YYYYMMDD_HHMMSS.txt` |
| FR-14 | events CSV 17열 헤더 **바이트 동일**, utf-8-sig, RFC4180, 시각 ISO ms 로컬, 소수 3자리(`Locale.US`), 판정 한글, `raw_hex` 공백 대문자, `uwb` `AA:BB`/`미지원`. `session_opened_at`=연결 시각, `elapsed_s`=연결→수락 (D-003) |
| FR-15 | cycles CSV(`cycle,stop_at,adv_started_at,on_until,writes,first_write_at,adv_to_first_write_s,note`), OFF 중 write는 `note=late write` |
| FR-16 | 세 파일 저장·공유(FileProvider). 사이클 종료 시 자동 저장. 주기 자동 저장(시뮬레이터 FAQ Q14 제안) |

### 5-4. 화면 (ui-mockup)
| ID | 요구 |
|---|---|
| FR-17 | ① 광고: 배지(광고/GATT/사이클), 입력(UUID·Major·Minor·Tx·간격·TTL — 송출 중 변경 시 "재시작 필요"), 시작/중지(동시 활성 금지), 사이클 줄(N·OFF·ON, OFF+ON<60 경고), 카운터 `연결 N · nonce N · 수락 N · 거부 N`, 접이식 raw(AdvData 30B·ScanRsp 18B 필드 색) |
| FR-18 | ② 연결 중인 폰: 라벨·주소·MTU·구독·멤버 ID·UWB. RSSI 없음. 끊기면 행 소멸 |
| FR-19 | ③ 수신 이벤트(최신 위, 3줄 행, 거부 경고색, 탭 상세) / Activity Log(자동 스크롤·저장). **지우기는 Activity Log에 `EVENTS cleared N rows` 1행** |
| FR-20 | 오류는 배지·ERR 로그·스낵바 3곳. 디버그 토글(D-009): 0x80/0x81 강제, not_found 강제, 조회 지연, 연결 시 광고 재enable, UUID 덮어쓰기 |

### 5-5. 수명·권한
| ID | 요구 |
|---|---|
| FR-21 | 광고·GATT 서버는 FGS(`connectedDevice`) `PosService` 소유. 알림 "POS 광고 중 · Major N / Minor N" |
| FR-22 | `BOOT_COMPLETED`·`MY_PACKAGE_REPLACED` → 서비스 시작(D-002). BT OFF → 배지 "BT 꺼짐", BT ON → 1초 뒤 서버 재오픈·재광고 (FGS 안 동적 리시버) |
| FR-23 | 권한: 30 없음 / 31+ `BLUETOOTH_ADVERTISE`·`BLUETOOTH_CONNECT` / 33+ `POST_NOTIFICATIONS`. SDK 분기로만 확인·요청 |

## 6. 비기능 요구사항

| ID | 요구 |
|---|---|
| NFR-1 | minSdk 30. 그 아래 분기 없음 (constitution §3) |
| NFR-2 | 순수 함수(`protocol/`, `pos/NonceStore`·`SessionLabel`·`MockLookup`, `log/EventsCsv`·`ActivityLog` 포맷, `trial/CyclePlan`)는 JVM 단위 테스트 — 바이트 golden 값은 손님 앱 테스트·시뮬레이터 `tests/`와 교차 |
| NFR-3 | BLE 호출은 `runCatching`, 실패는 결과값. 조용히 리턴하지 않는다 |
| NFR-4 | `_reference_console/` 수정 금지. 가져온 코드는 출처 주석 |
| NFR-5 | 실기기로 확인하지 않은 항목을 완료로 표시하지 않는다 |
| NFR-6 | 프로토콜 변경은 상대 PROTOCOL.md PR이 먼저 |

## 7. 테스트 시나리오 (손님 앱 §7 번호를 POS 쪽에서)

손님 앱(SM-S928N)을 상대로 한다. 각 시나리오는 **POS events·Activity Log와 손님 앱 CSV를 나란히** 남긴다.

| # | 시나리오 | POS 쪽 기대 |
|---|---|---|
| S1 | 손님 앱 수동 전송 | 세션 생성 → nonce READ → write 수락(규칙 ① — 손님 앱 `select 규칙 ①`) → LOOK → NOTIFY. events 1행 |
| S2 | 손님 앱 포그라운드 자동 | 광고 중지→시작(수동 버튼 또는 사이클) 뒤 발화 → 위와 같음. `adv_to_write_s` 기록 |
| S3/S4 | 손님 주머니/종료 상태 | 같음. POS는 구분 못 함(정상) |
| S5/S6 | 손님 재부팅·BT 토글 | 같음 |
| S8 | 재진입 | 손님이 멀어져 끊기고(DISCONNECTED·세션 폐기) 돌아와 다시 write — POS는 새 세션 |
| S9 | UWB 미지원 폰 | `uwb=미지원`(`00 00`), 조회는 함 |
| S10 | nonce 거부 | 손님 앱 디버그 토글 → `불일치` → `0x80` → 재READ 뒤 수락. events 2행 |
| S11 | 손님 강제 종료 | POS 변화 없음 |
| S12 | 폰 2대 동시 | 세션 2개, nonce 독립, NOTIFY 각자. **연결 중 광고 유지** 확인(둘째 폰이 발견) |
| P1 | 연결 중 광고 유지 | 폰 1 연결 중 nRF Connect(또는 폰 2)가 POS를 스캔에서 본다 |
| P2 | 주소 회전 | 24h 광고 중 손님 앱 `detect` 행 `pos_addr` 변화 주기 기록 |
| P3 | 부팅·BT 토글 재광고 | 재부팅 / BT OFF→ON 뒤 광고 배지 복귀, 손님 앱 발화 |
| P4 | 0x81 | nRF Connect로 15B·버전 7·BCD 0xAB write → 각 판정 → events |
| P5 | pair_logs | `uv run tools/pair_logs.py <events> <app csv> <pos txt>` 가 열 변경 없이 표를 낸다 |

## 8. 완료 기준 (Acceptance)

- [ ] S1·S2·S9·S10·S12·P1·P3·P4·P5 가 실기기에서 통과하고 로그가 리포(`docs/logs/`)에 남아 있다
- [ ] 손님 앱 CSV의 `select` 행이 규칙 ①(같은 주소)로 찍힌다
- [ ] P2 주소 회전 24h 관측 결과가 기록되고 상대 PROTOCOL §2-3·FAQ Q11 부기 PR이 나갔다
- [ ] 순수 함수 단위 테스트 통과, `protocol-auditor`(Peripheral 판) 통과
- [ ] 손님 앱 005(Android POS 검증)가 이 앱을 상대로 S1~S8을 다시 통과했다 (손님 앱 쪽 Acceptance와 교차 링크)

## 9. 확정값

| ID | 질문 | 결정 (2026-10-03) |
|---|---|---|
| D-001 | minSdk | **30** (Android 11). 31+ 권한 분기 |
| D-002 | 부팅 재광고 범위 | `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`. `LOCKED_BOOT_COMPLETED` 안 함 — POS 기기에 잠금 화면이 있으면 재검토 |
| D-003 | `session_opened_at`/`elapsed_s` | **연결 시각 기준**. 시뮬레이터는 첫 GATT 요청 — `pair_logs.py`가 두 열을 쓰지 않아 호환 영향 없음. FAQ·상대 FAQ Q12 부기 |
| D-004 | BLE 주소 | RPA 회전 수용. 고정은 요구가 아니라 **측정**(P2). 고정 주소는 privileged 설치뿐 — POS 벤더 확인 항목 |
| D-005 | 사이클 OFF/ON | `enableAdvertising(false/true)` (set·주소 유지). 수동 중지/시작 버튼은 stop/startSet. 디버그 토글로 전환 |
| D-006 | PROTOCOL §2-1 Flags `02 01 06` | 스택이 넣는 값(0x06/0x1A) → "Flags 존재, 값은 스택"으로 상대 PR. 손님 앱은 Flags를 보지 않는다 |
| D-007 | 연결 중 광고 재enable | 기본 OFF(스택 자동 재개) + 디버그 토글. P1에서 확인 |
| D-008 | 멤버 조회 | 시뮬레이터 mock DB(0123456789 홍길동·9876543210 김영희·1111111111 테스터·1111222200 김테스트)·50 ms 그대로 |
| D-009 | 디버그 토글 | 손님 앱 D11 동등 — UUID 덮어쓰기·Tx 레벨·0x80/0x81 강제·not_found 강제·조회 지연·재enable. `BuildConfig.DEBUG`에서만 |
| D-010 | 리포 가시성 | public (다른 mcandle-dev 리포와 같음) |
| D-011 | 패키지 | `dev.mcandle.uwbpos` — 손님 앱·콘솔과 한 폰에 공존 |
| D-012 | 테스트·테마 관례 | 손님 앱(JUnit4·라이트 테마·device-tests·ui-mockup) |
