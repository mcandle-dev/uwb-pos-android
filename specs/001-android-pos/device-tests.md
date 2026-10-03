# 실기기 검증 인계 — spec 001 (Android POS)

이 세션(2026-10-03)에서는 **정적 검토·JVM 단위 테스트·컴파일까지만** 했다 (CLAUDE.md "환경 제약"). 아래는 POS 폰(Android 11+) + 손님 앱 폰(SM-S928N) +
nRF Connect 폰이 있어야 확인되는 항목이다. 확인 전에는 spec.md Acceptance에 체크하지 않는다 (constitution §7).
로그는 **양쪽 리포 `docs/logs/`** 에 — POS 쪽 `events_*.csv`·`pos_*.txt`(·`cycles_*.csv`)는 이 리포, 손님 앱 CSV는 손님 앱 리포. nonce로 짝짓는다.

## 0. 준비

| 항목 | 값 | 확인 |
|---|---|---|
| POS 폰 | Android 11+ (minSdk 30). 모델·OS 버전 기록: `adb shell getprop ro.product.model ro.build.version.release` — **SM-G977N / Android 12 (SDK 31)** 2026-10-03 | [x] |
| 설치 | `adb install -r app\build\outputs\apk\debug\app-debug.apk` (adb 는 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`). 설치 = `MY_PACKAGE_REPLACED` → 서비스가 뜨고 알림 "POS 광고 중" 이 오면 BootReceiver 가 산 것 — **첫 설치는 브로드캐스트 없음(정상)**. 2026-10-03 재설치에서 FGS 기동·`publisher started` 를 logcat 으로 확인. 무선 디버깅은 README "설치" | [x] |
| 권한 | 31+ 폰이면 첫 "시작" 에 근처 기기(광고·연결) 다이얼로그. 30 폰이면 다이얼로그 없음(정상). 33+ 는 알림 권한 추가 | [ ] |
| 손님 앱 | `uwb-member-app` 최신 debug(004 `f194a64` 이후). 멤버 ID `1111222200` (mock DB 에 있음 → `김**트`). 시뮬레이터는 **꺼 둔다**(광고 두 개가 섞이면 규칙 판정이 흐려진다) | [ ] |
| nRF Connect | GATT 단독 확인용(P4). Scanner 탭에서 "UWB POS" 가 아니라 manufacturer `Apple 0x004C` + 서비스 `BF3C0001…` 로 보인다 (기기 이름 미포함) | [ ] |
| 로그 저장 | 화면 "저장·공유" → `events_*.csv` + `pos_*.txt` 공유(Quick Share 등) → 이 리포 `docs/logs/` 에 접미 붙여 복사. 5분마다 자동 저장도 된다(`files/logs/`) | [ ] |
| 접미 | `_S1` `_S2` `_S9` `_S10` `_S12` `_P1` `_P3` `_P4` `_P5` `_CYC`(사이클) `_P2`(24h) | [ ] |

Activity Log 는 시뮬레이터와 같은 줄 형식이다. 기대 줄: `ADV … publisher started (AdvertisingSet 콜백 확인) — on-air 30B …` · `GATT … service advertising started — 3 chars` ·
`CONN A1 connected <addr> · 세션 생성` · `GATT A1 MTU 512`(손님 폰 Android 14+ 는 185 요청이 517 로 올라간다 — FAQ Q6; ≥185 면 됨) · `GATT A1 result notify 구독` · `NONCE A1 read → XXXXXXXX (ttl 30s)` · `GATT A1 write 16B member=… → 수락 (nonce 일치 (발급 후 0.0xxs))` ·
`LOOK A1 lookup → {"v":1,…} (x ms) · notified=true` · `CONN A1 disconnected · 세션 폐기`.

## 1. 시나리오

### S1 — 손님 앱 수동 전송 (첫 연동 ★)
1. POS "시작" → 광고 "송출 중", GATT "대기 중". 2. 손님 앱 열고 "직접 전송".
- [x] Activity Log 에 위 기대 줄이 순서대로. `MTU ≥185` 가 보이는가 (손님 앱 requestMtu; SM-S928N 은 512) — 2026-10-04 00:05 B1, `MTU 512`
- [x] **손님 앱 CSV `select` 행이 `규칙 ①`** (같은 주소에 iBeacon + 서비스 UUID — 시뮬레이터에서는 ③만 나왔다) — `규칙 ① · rssi -54`. 손님 앱 `scan` 행 `조기 종료 0.5s · 기기 1 (결과 3건)`. (23:48 첫 run 은 PC 시뮬레이터가 켜져 있어 `3.0s · 기기 3` 이었다 — 시뮬레이터를 끄고 재시험)
- [x] 손님 앱 `write_ack … ACK`, `result notify 63B`(김**트). POS events 1행 `OK`, `elapsed_s` ≈ 0.3~1.0(연결→수락, D-003) — `write_ack 0 ACK` · `result notify 63B` · `done OK 2095ms · success` / POS `OK`, elapsed 0.775, `result`·`adv_started_at` 채워짐
- [x] 로그 `_S1` — `docs/logs/events_20261004_000849_S1.csv` · `pos_20261004_000849_S1.txt` (23:58 A1 · 00:05 B1 두 건) / 손님 앱 `docs/logs/app_20261004_000642.csv`

### S2 — 광고 중지 → 시작 → 손님 앱 자동 발화
1. 손님 앱 홈으로(화면 ON). 2. POS "광고 중지" → 15초 → "시작". 3. 손님 앱 깨어나 전송.
- [x] POS events `adv_started_at` 채워짐, `adv_to_write_s` = 광고 시작 → write. 손님 앱 `wake type=FIRST_MATCH … mode=`, `done … wake_to_ack_ms=` — 2026-10-04 00:14 D1 (OFF 60 s, 화면 ON) `adv_to_write_s` 2.528 / `wake_to_ack_ms=2002` · 00:18 E1 (OFF 10 s, 화면 OFF 96 s) 3.067 / 2062. 둘 다 `type=FIRST_MATCH(2) … mode=LOW_POWER/STICKY`
- [x] 두 값의 차 ≈ 감지 지연(+시계 오프셋). 손님 앱 `nonce` 행 wall ↔ POS `nonce_issued_at` 으로 오프셋 — 오프셋 +0.62 s. **감지 지연(광고 시작 → 손님 앱 detect) D1 0.53 s · E1 1.05 s** = `adv_to_write_s − wake_to_ack_ms` 와 일치 (0.526 · 1.005). §2 표
- [x] 로그 `_S2` — `docs/logs/events_20261004_001850_S2.csv` · `pos_20261004_001850_S2.txt` / 손님 앱 `docs/logs/app_20261004_001959.csv`

### S9 — UWB 미지원
손님 앱 디버그 "UWB 미지원으로 보내기" ON → 전송.
- [x] POS events `uwb=미지원`, `raw_hex` `[6..7] = 00 00`, 조회는 수행(`LOOK`). 로그 `_S9` — 2026-10-04 00:27:30 G1 nonce 9F6B35A3: `uwb=미지원`, raw `… 22 00 00 00 9F 6B 35 A3 …`, `LOOK … notified=true`, 손님 앱 `uwb 미지원 → 0000` → `done OK 1605ms`. 파일 `_S9_S10`

### S10 — nonce 거부
손님 앱 디버그 "잘못된 nonce 보내기" ON → 전송.
- [x] POS: `ERR A1 write 16B 거부 0x80: 불일치 — 기대 …, 수신 00000000` → 손님 앱 재READ → 두 번째 write `수락`. events 2행(`불일치`, `OK`) — 2026-10-04 00:27:53 H1: `NONCE read → F61FFDD0` → `ERR H1 write 16B 거부 0x80: 불일치 — 기대 F61FFDD0, 수신 00000000` → `NONCE read → 43FCB48B` → `write … 수락 (발급 후 0.072s)` → `LOOK notified=true`. events `불일치`(issued F61FFDD0, age 0.038, elapsed 빈칸) + `OK`(elapsed 0.891)
- [x] 손님 앱 `write_reject status 128` → `nonce` → `write_ack`. 로그 `_S10` — `write_reject 128 "0x80 nonce 거부 (raw 128) → nonce 재READ 후 재시도"` → `nonce 43FCB48B` → `write_ack 0 "ACK (재시도 1/1)"` → `done OK 1818ms`. 파일 `docs/logs/events_20261004_002835_S9_S10.csv` · `pos_20261004_002835_S9_S10.txt` / 손님 앱 `app_20261004_002845.csv`

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
- [x] `CYCLE k/10 stop|start` 로그, events `cycle` 열 1..10, `cycles_*.csv` 10행. `late write` 유무. 로그 `_CYC` — 2026-10-04 00:49:36~00:59:43, `enableAdvertising` 모드. `CYCLE done 10/10 · writes 1 · no-write [1,2,3,4,5,6,7,8,9]`, late write 없음. 세 파일 자동 저장 확인(`events`·`cycles`·`pos` `_005943`). **손님 앱은 사이클 1~9 에 발화하지 않았고 10 에서만 1건**(00:59:10, adv_to_write 13.3 s) — 아래 §2 사이클 해석

### P2 — 주소 회전 24h (D-004)
POS 광고를 24시간 켜 두고 손님 앱을 옆에 둔다(화면 OFF, 충전).
- [ ] 손님 앱 CSV `detect` 행 `pos_addr` 가 바뀌는 시각 목록 → 회전 주기. 바뀔 때마다 손님 앱이 재전송했는가(15분 재전송 문제가 Android POS 에서도 나는가)
- [ ] 결과 → spec §F-2, 상대 PROTOCOL §2-3·FAQ Q11 PR, 손님 앱 005 억제 키 결정

## 2. pair_logs 표 (붙일 곳)

### S1 — 2026-10-04 00:05 B1 (손으로 짝지음, `pair_logs.py` 는 P5)

| nonce | POS 세션 | POS 광고 주소(손님 앱이 본) | 손님 폰 주소(POS 가 본) | 단계 | 손님 앱 wall | POS wall | 차 |
|---|---|---|---|---|---|---|---|
| 116FC840 | B1 | 56:79:59:9D:30:E9 | 76:18:C6:A9:E1:40 | connected | 00:05:55.652 | 00:05:54.870 | +0.78 |
| | | | | mtu 512 | 00:05:56.277 | 00:05:55.512 | +0.77 |
| | | | | cccd 구독 | 00:05:56.309 | 00:05:55.573 | +0.74 |
| | | | | nonce read | 00:05:56.337 | 00:05:55.605 | +0.73 |
| | | | | write ACK / 수락 | 00:05:56.426 | 00:05:55.673 | +0.75 |
| | | | | result notify 63B / LOOK notified=true | 00:05:56.492 | 00:05:55.757 | +0.74 |

- 손님 폰 시계가 POS 보다 **≈ +0.75 s** 앞선다 (23:48 run 에서는 +0.45 — 폰 시계는 세션마다 다를 수 있으니 매 run `nonce` 행으로 다시 잰다).
- 손님 앱 `done 2095ms` = 스캔 0.63 + 연결 0.62 + 탐색·MTU·CCCD 0.66 + nonce→result 0.18. POS `elapsed_s` 0.775 = 연결→수락.
- UWB 주소: 손님 앱 `uwb addr 802E` ↔ POS `uwb=80:2E`. `status 14`·ERR 없음.
- 같은 파일의 23:58 A1(nonce 4BA29C87)은 시뮬레이터가 켜진 상태였지만 POS 쪽은 동일하게 OK — 손님 앱이 규칙 ①로 이 POS 를 골랐다.

### S2 — 2026-10-04 00:14 D1 · 00:18 E1

| run | OFF | 화면 | POS publisher started | 손님 앱 detect (−0.62 s 보정) | 감지 지연 | 손님 앱 wake→ACK | POS adv→write | 차 |
|---|---|---|---|---|---|---|---|---|
| D1 2DA73FA6 | 60 s | ON | 00:14:49.897 | 00:14:51.046 → 50.43 | **0.53 s** | 2002 ms | 2.528 s | 0.526 |
| E1 6CD18C54 | 10 s | OFF 96 s | 00:18:23.903 | 00:18:25.571 → 24.95 | **1.05 s** | 2062 ms | 3.067 s | 1.005 |

- 시계 오프셋(손님 폰 − POS): D1 connected +0.655 · nonce +0.615 · write +0.62 / E1 +0.66 · +0.60 · +0.64 → **+0.62 s**. S1 의 +0.75 와 다르다 — run 마다 다시 잰다.
- 손님 앱 `wake_to_ack_ms` 2.0 s 의 내역(두 run 같음): 스캔 조기 종료 0.52 + 연결 0.65 + 탐색·MTU·CCCD 0.6 + nonce→ACK 0.05.
- **POS 광고 주소가 run 마다 바뀌었다**: D1 `6B:9D:C4:21:32:14`, E1 `6D:45:35:81:7E:2E`(손님 앱 `detect`). 수동 "광고 중지/시작" = `stopAdvertisingSet/startAdvertisingSet` 이라 새 set = 새 RPA (D-005 가 사이클에 `enableAdvertising` 을 쓰는 이유). 그런데도 손님 앱이 매번 `FIRST_MATCH` 로 깨어났다 — 주소가 바뀌니 STICKY 가 새 기기로 본 것(손님 앱 005 억제 키 논의 자료).
- OFF 10 s 에도 `FIRST_MATCH` 가 왔다 → SM-S928N STICKY `MATCH_LOST` 는 10 s 안에 난다 (손님 앱 002 실측 ≈10 s 와 일치). 화면 OFF 96 s 상태에서도 1.05 s 에 감지.
- `status 14`·ERR 없음. 두 run 다 `규칙 ①`, MTU 512, `result notify 63B`.

### P2 예비 관찰 — 주소 회전 (2026-10-04 00:26, S9 직전에 우연히)

- POS set 은 00:18:23 에 만들어졌고(E1 때 손님 앱이 본 주소 `6D:45:35:81:7E:2E`) 광고는 끊긴 적이 없다. 그런데 **00:26:28** 손님 앱이 새 주소 `6F:A9:6F:B9:AC:40` 에 `wake FIRST_MATCH`(화면 OFF 253 s) 로 깨어나 **자동 전송**(F1, nonce 96BDFF2C, `wake_to_ack_ms=1808`), 00:26:36 에 옛 주소 `6D:45…` 로 `MATCH_LOST`.
- 즉 **RPA 가 set 생성 ≤8 분 뒤에 바뀌었고, 손님 앱은 그걸 새 POS 로 보고 재전송했다** — 시뮬레이터에서 보던 "15분 재전송" 이 Android POS 에서도 난다(D-004 예상대로). 회전 주기는 P2 24h 로 잰다. 손님 앱 005 억제 키(Major/Minor) 논의의 첫 실측.

### 사이클 — 2026-10-04 00:49 (N=10 · OFF 15 · ON 45, `enableAdvertising`)

| cycle | stop_at | adv_started_at | writes | adv_to_first_write_s |
|---|---|---|---|---|
| 1~9 | 00:49:36 … 00:57:43 | +15.0~15.6 s 뒤 | **0** | — |
| 10 | 00:58:43.339 | 00:58:58.403 | 1 (B1, nonce C9FE27E1) | 13.262 |

- 실행기 자체는 정확하다: stop→start 15.0~15.6 s, `onAdvertisingEnabled` 콜백 40~50 ms, late write 없음. 사이클 6→7·7→8 사이 ON 이 45 → 47.7 s 로 늘었다(`delay` 지연 — 화면 OFF 뒤 Dispatchers.Default 스로틀 추정). 측정은 CSV 의 실제 시각을 쓰므로 영향 없음.
- **손님 앱이 같은 주소의 OFF 15 s / ON 45 s 에 재발화하지 않았다.** 직전 전송은 00:46:04(재설치 → 새 set → FIRST_MATCH). 사이클 10 의 1건은 00:59:10 — 직전 전송 13 분 뒤. 두 가설: (a) 손님 앱 STICKY 가 OFF 15 s 안에 `MATCH_LOST` 를 안 낸다, (b) 손님 앱이 같은 주소에 ≈13~15 분 억제를 건다(004 "15분 재전송"). 또는 (c) 00:59 에 RPA 가 회전해(set 생성 00:46 → 13 분) 새 주소로 FIRST_MATCH 가 난 것 — S9 직전 관찰(8 분)과 같은 패턴. **손님 앱 CSV 의 `detect` 주소·`wake`/`MATCH_LOST` 행으로 가른다.**
- 시뮬레이터 사이클(주소가 바뀌는 2개 광고)과 달리 Android `enableAdvertising` 사이클은 **같은 주소**라 "재진입" 이 안 된다 → 손님 앱 005 에서 억제 키·MATCH_LOST 조건을 정할 때 이 run 이 기준. 비교용으로 디버그 "사이클을 stop/start 로"(새 주소) run 이 다음 항목.

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
