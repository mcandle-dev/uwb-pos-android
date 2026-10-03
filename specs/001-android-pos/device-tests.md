# 실기기 검증 인계 — spec 001 (Android POS)

이 세션(2026-10-03)에서는 **정적 검토·JVM 단위 테스트·컴파일까지만** 했다 (CLAUDE.md "환경 제약"). 아래는 POS 폰(Android 11+) + 손님 앱 폰(SM-S928N) +
nRF Connect 폰이 있어야 확인되는 항목이다. 확인 전에는 spec.md Acceptance에 체크하지 않는다 (constitution §7).
로그는 **양쪽 리포 `docs/logs/`** 에 — POS 쪽 `events_*.csv`·`pos_*.txt`(·`cycles_*.csv`)는 이 리포, 손님 앱 CSV는 손님 앱 리포. nonce로 짝짓는다.

## 0. 준비

| 항목 | 값 | 확인 |
|---|---|---|
| POS 폰 | Android 11+ (minSdk 30). 모델·OS 버전 기록: `adb shell getprop ro.product.model ro.build.version.release` | [ ] |
| 설치 | `adb install -r app\build\outputs\apk\debug\app-debug.apk` (adb 는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`). 설치 = `MY_PACKAGE_REPLACED` → 서비스가 뜨고 알림 "POS 광고 중" 이 오면 BootReceiver 가 산 것 | [ ] |
| 권한 | 31+ 폰이면 첫 "시작" 에 근처 기기(광고·연결) 다이얼로그. 30 폰이면 다이얼로그 없음(정상). 33+ 는 알림 권한 추가 | [ ] |
| 손님 앱 | `uwb-member-app` 최신 debug(004 `f194a64` 이후). 멤버 ID `1111222200` (mock DB 에 있음 → `김**트`). 시뮬레이터는 **꺼 둔다**(광고 두 개가 섞이면 규칙 판정이 흐려진다) | [ ] |
| nRF Connect | GATT 단독 확인용(P4). Scanner 탭에서 "UWB POS" 가 아니라 manufacturer `Apple 0x004C` + 서비스 `BF3C0001…` 로 보인다 (기기 이름 미포함) | [ ] |
| 로그 저장 | 화면 "저장·공유" → `events_*.csv` + `pos_*.txt` 공유(Quick Share 등) → 이 리포 `docs/logs/` 에 접미 붙여 복사. 5분마다 자동 저장도 된다(`files/logs/`) | [ ] |
| 접미 | `_S1` `_S2` `_S9` `_S10` `_S12` `_P1` `_P3` `_P4` `_P5` `_CYC`(사이클) `_P2`(24h) | [ ] |

Activity Log 는 시뮬레이터와 같은 줄 형식이다. 기대 줄: `ADV … publisher started (AdvertisingSet 콜백 확인) — on-air 30B …` · `GATT … service advertising started — 3 chars` ·
`CONN A1 connected <addr> · 세션 생성` · `GATT A1 MTU 185` · `GATT A1 result notify 구독` · `NONCE A1 read → XXXXXXXX (ttl 30s)` · `GATT A1 write 16B member=… → 수락 (nonce 일치 (발급 후 0.0xxs))` ·
`LOOK A1 lookup → {"v":1,…} (x ms) · notified=true` · `CONN A1 disconnected · 세션 폐기`.

## 1. 시나리오

### S1 — 손님 앱 수동 전송 (첫 연동 ★)
1. POS "시작" → 광고 "송출 중", GATT "대기 중". 2. 손님 앱 열고 "직접 전송".
- [ ] Activity Log 에 위 기대 줄이 순서대로. `MTU 185` 가 보이는가 (손님 앱 requestMtu)
- [ ] **손님 앱 CSV `select` 행이 `규칙 ①`** (같은 주소에 iBeacon + 서비스 UUID — 시뮬레이터에서는 ③만 나왔다). 손님 앱 `scan` 행 `조기 종료 0.5s · 기기 1`
- [ ] 손님 앱 `write_ack … ACK`, `result notify 63B`(김**트). POS events 1행 `OK`, `elapsed_s` ≈ 0.3~1.0(연결→수락, D-003)
- [ ] 로그 `_S1`

### S2 — 광고 중지 → 시작 → 손님 앱 자동 발화
1. 손님 앱 홈으로(화면 ON). 2. POS "광고 중지" → 15초 → "시작". 3. 손님 앱 깨어나 전송.
- [ ] POS events `adv_started_at` 채워짐, `adv_to_write_s` = 광고 시작 → write. 손님 앱 `wake type=FIRST_MATCH … mode=…`, `done … wake_to_ack_ms=`
- [ ] 두 값의 차 ≈ 감지 지연(+시계 오프셋). 손님 앱 `nonce` 행 wall ↔ POS `nonce_issued_at` 으로 오프셋
- [ ] 로그 `_S2`

### S9 — UWB 미지원
손님 앱 디버그 "UWB 미지원으로 보내기" ON → 전송.
- [ ] POS events `uwb=미지원`, `raw_hex` `[6..7] = 00 00`, 조회는 수행(`LOOK`). 로그 `_S9`

### S10 — nonce 거부
손님 앱 디버그 "잘못된 nonce 보내기" ON → 전송.
- [ ] POS: `ERR A1 write 16B 거부 0x80: 불일치 — 기대 …, 수신 00000000` → 손님 앱 재READ → 두 번째 write `수락`. events 2행(`불일치`, `OK`)
- [ ] 손님 앱 `write_reject status 128` → `nonce` → `write_ack`. 로그 `_S10`

### S12 — 폰 2대 동시
손님 앱 폰 2대(또는 1대 + nRF Connect)가 동시에 연결.
- [ ] 세션 `A1`·`B1` 각각 nonce 발급, 각자 write 수락, NOTIFY 각자. 한쪽 nonce 로 다른 쪽 write → `미발급`/`불일치`
- [ ] **연결 중 광고 유지**: 1대가 연결된 동안 2대째가 POS 를 스캔에서 본다 (P1 과 같음). 로그 `_S12`

### P1 — 연결 중 광고 유지 (FR-A4, D-007)
1. nRF Connect 로 POS 에 연결해 둔다(아무것도 안 함). 2. 손님 앱 "직접 전송".
- [ ] 손님 앱이 POS 를 찾아 전송 성공 → 스택이 광고를 재개한 것. 실패(`POS 없음`)면 POS 디버그 "연결 시 광고 재enable" ON 뒤 재시도 → 결과 기록 (D-007 결정)
- [ ] 로그 `_P1`

### P3 — 부팅 · BT 토글 재광고 (FR-E3·E4)
1. POS 재부팅, 앱 안 열기 → 알림 "POS 광고 중" 복귀 → 손님 앱 전송 OK. 2. POS BT OFF(Activity Log `ERR BT 꺼짐`) → ON → `BT 켜짐 — 1000ms 뒤 서버 재오픈·재광고` → `publisher started` → 손님 앱 전송 OK.
- [ ] 두 경로 모두 OK. 로그 `_P3`

### P4 — 거부 코드·디스크립터 (nRF Connect)
POS 에 연결 → Client Characteristic Configuration(0x2902) 읽기 → payload 특성에 write(With Response):
| write | 기대 응답 | events verdict |
|---|---|---|
| 15B `01 01 23 45 67 89 43 33 18 51 48 5F 00 00 00` | 0x81 (`GATT_INTERNAL_ERROR`/`0x81` 표시) | `길이 오류` |
| 16B, [0]=07 | 0x81 | `버전 오류` |
| 16B, [3]=AB | 0x81 | `BCD 오류` |
| 16B 정상, nonce READ 안 하고 | 0x80 | `미발급` |
| nonce READ → 16B 정상(에코 틀림) | 0x80 | `불일치` |
| nonce READ → 31초 뒤 에코 | 0x80 | `만료` |
| nonce READ → 즉시 정확 에코 | 0x00 | `OK` → result READ 가 JSON |
- [ ] 표 전부. CCCD 읽기가 `00 00`/`01 00` 으로 응답(구독 전/후). `status 14` 는 **한 번도** 안 나와야 한다
- [ ] 로그 `_P4`

### P5 — pair_logs
```
cd D:\dev\mcandle\uwb-pos-simulator
uv run tools/pair_logs.py <이 리포 docs/logs/events_…_S2.csv> <손님 앱 docs/logs/app_….csv> <이 리포 docs/logs/pos_…_S2.txt>
```
- [ ] 표가 나오고 `publisher started` 카운트가 맞는다. 열 변경·오류 없음. 결과를 §2 에 붙임

### 사이클 (시뮬레이터 spec 002 T22 동등)
① 패널(T5 뒤) 또는 디버그 메뉴에서 N=10 · OFF 15 · ON 45. 손님 앱 화면 OFF.
- [ ] `CYCLE k/10 stop|start` 로그, events `cycle` 열 1..10, `cycles_*.csv` 10행. `late write` 유무. 로그 `_CYC`

### P2 — 주소 회전 24h (D-004)
POS 광고를 24시간 켜 두고 손님 앱을 옆에 둔다(화면 OFF, 충전).
- [ ] 손님 앱 CSV `detect` 행 `pos_addr` 가 바뀌는 시각 목록 → 회전 주기. 바뀔 때마다 손님 앱이 재전송했는가(15분 재전송 문제가 Android POS 에서도 나는가)
- [ ] 결과 → spec §F-2, 상대 PROTOCOL §2-3·FAQ Q11 PR, 손님 앱 005 억제 키 결정

## 2. pair_logs 표 (붙일 곳)

## 3. 상대 리포에 넘길 것 (실측 후, T22)

| 항목 | 근거 | 어디로 |
|---|---|---|
| PROTOCOL §2-1 Flags `02 01 06` → "Flags 존재, 값은 스택" (D-006). 실측 Flags 값 | S1 nRF Connect raw | PROTOCOL PR |
| §2-3 Android POS 표 — 실측(한 기기, 규칙 ①, 간격 100 ms) | S1·S2 | PROTOCOL PR |
| 주소 회전 — "Android POS 는 고정" 정정 | P2 | PROTOCOL §4-1 부기 · FAQ Q11 |
| `elapsed_s`/`session_opened_at` = 연결 기준 (D-003) | S1 | FAQ Q12 부기 |
| 연결 중 광고 자동 재개 여부 (D-007) | P1 | FAQ |

## 4. 시험 뒤 Claude 에게 알려줄 것 — 보고 양식

로그에서 읽을 수 있는 것은 **파일만**. 로그에 없는 것만 적는다.

```
POS 폰 모델 / Android 버전 : __ / __
설치 직후 알림 "POS 광고 중" 떴나 : 예 / 아니오
권한 다이얼로그 (31+)       : 떴음 / 안 떴음(30)
S1  : 시각 __:__:__ · 손님 앱 화면에 "규칙 ①" 보였나 (예/아니오) · 파일 _S1
S2  : 광고 중지 __:__:__ → 시작 __:__:__ · 손님 폰 화면 ON/OFF · 파일 _S2
S9  : 파일 _S9
S10 : 파일 _S10 · 손님 앱 "0x80 nonce 거부 → 재시도" 표시 (예/아니오)
S12 : 두 번째 기기 종류(손님 앱 폰 2 / nRF Connect) · 둘 다 성공 (예/아니오) · 파일 _S12
P1  : nRF 연결 중 손님 앱 전송 성공 (예/아니오) · 실패 시 재enable 토글 켜고 재시도 결과 · 파일 _P1
P3  : 재부팅 뒤 알림 복귀 (예/아니오) · BT 토글 뒤 복귀 (예/아니오) · 파일 _P3
P4  : 표 7행 중 다른 결과가 난 행 · status 14 본 적 있나 (예/아니오) · CCCD 읽기 값 · 파일 _P4
P5  : pair_logs 출력 붙여넣기 또는 오류 메시지
CYC : 명령/설정 · 파일 _CYC
P2  : 시작 __월 __일 __:__ ~ 끝 · 손님 앱 CSV 파일명
이상 현상 : 시각 + 하던 일 + 화면에 보인 것
```
