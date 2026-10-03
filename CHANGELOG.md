# CHANGELOG — uwb-pos-android

동작이 바뀌면 여기에 적는다 (constitution §10). 설계가 바뀌면 `docs/ARCHITECTURE.md`도 함께 고친다.
**"Verified"는 실기기(POS 폰 + 손님 앱 폰)로 확인한 것만** 쓴다 (constitution §7).

## 2026-10-04 — T15 사이클 실행기 (실기기 10회 완주)

### Added
- `trial/CycleRunner.kt` — `pos_sim/cycle.py` 동등: `CYCLE k/N stop|start`, `publisher started` 10 s 대기, 실패·중단은 ERR 뒤 중단(재시도 없음), 진행(`progress`)은 대기 시간만 합산. `CycleRunnerTest` 5건(가상 시계).
- `PosService` — `ACTION_CYCLE_START/STOP`. OFF/ON = `enableAdvertising`(set·주소 유지, D-005), 디버그 토글이면 stop/start. 광고 미송출이면 ERR 거부. 1 s 진행 publish. 끝나면 `CYCLE done k/N · writes W · no-write [...]` + **events·cycles·pos 세 파일 자동 저장**(5 분 자동 저장도 cycles 포함). OFF 중 중단되면 광고 복구.
- 화면: ① 사이클 줄 "실행" → 진행 줄 + "중단", 배지 `사이클 k/N · OFF/ON`. 사이클 중(Off 포함)에는 "시작" 비활성, 광고 설정 접힘 유지.

### Verified (SM-G977N, 00:49~00:59)
- `CYCLE done 10/10 · writes 1 · no-write [1..9]`, cycles CSV 10행, events `cycle=10`·`adv_to_write_s 13.262`, late write 없음. stop→start 15.0~15.6 s, `onAdvertisingEnabled` 40~50 ms.
- **비교 run (stop/start, 새 주소, N=3)**: 매 사이클 `MATCH_LOST`(stop +10 s) → `FIRST_MATCH`(start +1.1~3.1 s) → 전송 2건, cycle 2 는 손님 앱 `자동 전송 억제 — 성공 후 56초` (§4-1 60 s). 깨우는 조건 = 주소 변경 확정. 억제를 피하려면 ON ≥ 50(OFF 15).
- **같은 주소 재진입은 손님 폰이 못 본다**: 사이클 1 stop 10.4 s 뒤 `MATCH_LOST` 는 왔지만 start 뒤 같은 주소에 `FIRST_MATCH` 가 9 번 모두 안 왔다(OS 스캐너). 10 번째의 1건은 RPA 회전(≤13 분)으로 생긴 **새 주소**에 대한 `FIRST_MATCH`. → 손님 앱 005 의 핵심 입력(MATCH_LOST 뒤 스캔 재등록 등). device-tests §2 사이클.

## 2026-10-03 — T14 3패널 화면 · events `result` 열이 비던 결함 수정 (실기기 SM-G977N)

### Added
- `ui/MainScreen.kt`·`ui/MainViewModel.kt` — `specs/001-android-pos/ui-mockup.html` 그대로. 한 `LazyColumn` 에 ① 광고 제어(배지 3개 · 접이식 광고 설정 6칸 · 시작/광고 중지/저장·공유 · 카운터 · 사이클 줄 · 송출 패킷 raw · 디버그 카드[DEBUG]) → ② 연결 중인 폰(세션 · 멤버 ID+주소+연결 시각 · UWB · MTU/구독) → ③ sticky 탭(수신 이벤트 3줄 행 + 탭 상세 raw 16B 필드색 / Activity Log 가로 스크롤·자동 스크롤).
  ERR 줄은 스낵바로도. 31+ 권한 없으면 빨간 줄 + "허용". `MainActivity` 는 권한 런처와 액션 바인딩만.
- `ui-mockup.html` — 시뮬레이터 목업 토큰·배치, Android 차이(MTU·구독·주소·사이클·Flags 점선) 주석.

### Fixed
- **events CSV `result` 열이 항상 비었다.** `EventsCsv.Received` 가 `equals` 를 (wallMs, sessionKey, raw) 로 재정의해 `EventsStore.replace`(조회 결과 채우기)가 만든 목록을 `MutableStateFlow` 가 "같은 값" 으로 보고 버렸다. 재정의 삭제 (실기기 23:06~23:31 5건 모두 `result` 빈칸으로 확인).

### Verified (SM-G977N ↔ 손님 앱 SM-S928N, 23:26·23:31 두 번)
- `CONN A1 connected` → `MTU 512` → `result notify 구독` → `NONCE read` → `write 16B member=1111222200 … 수락 (발급 후 0.086s)` → `LOOK … notified=true` → `disconnected · 세션 폐기`. elapsed 0.80 s. **S1 판정은 손님 앱 CSV(`규칙 ①`) 를 받은 뒤** (device-tests §4).
- **S1 통과** (10-04 00:05, nonce 116FC840, PC 시뮬레이터 OFF): 손님 앱 `조기 종료 0.5s · 기기 1` → `select 규칙 ① · rssi -54` → `mtu 512` → `cccd` → `nonce` → `write_ack 0 ACK` → `result notify 63B` → `done OK 2095ms · success`. POS `OK` elapsed 0.775 s, `result`·`adv_started_at` 열 채워짐. 손님 폰 시계 +0.75 s.
  (23:48 첫 run 은 시뮬레이터가 켜져 있어 손님 앱 스캔이 `3.0s · 기기 3` — 그래도 규칙 ①로 이 POS 를 골랐다.)
- **S2 통과** (10-04 00:14 D1 OFF 60 s 화면 ON · 00:18 E1 OFF 10 s 화면 OFF): 손님 앱 `wake FIRST_MATCH … LOW_POWER/STICKY` → 자동 전송 OK. POS `adv_to_write_s` 2.528 / 3.067, 손님 앱 `wake_to_ack_ms` 2002 / 2062 → **감지 지연 0.53 / 1.05 s**. 시계 오프셋 +0.62 s. 수동 중지/시작은 새 set = 새 주소(손님 앱 `detect` 주소가 run 마다 다름) — D-005 근거.
- **S9 통과** (00:27 G1): `uwb=미지원`, raw `[6..7]=00 00`, 조회·NOTIFY 수행. **S10 통과** (00:27 H1): `ERR write 16B 거부 0x80: 불일치 — 기대 F61FFDD0, 수신 00000000` → 손님 앱 `write_reject 128` → 재READ → `수락 (재시도 1/1)`. events `불일치`+`OK` 2행.
- **주소 회전 첫 관측** (00:26 F1): set 생성 8분 뒤 RPA 가 바뀌어 손님 앱이 새 주소에 `FIRST_MATCH` 로 깨어나 재전송, 옛 주소는 `MATCH_LOST`. D-004 예상대로 — P2·손님 앱 005 자료.
- (수정 뒤 발견) `EventsStore.replace` 가 행 전체를 바꿔 `adv_started_at`·`cycle` 이 사라졌다 → `resultJson` 만 병합. `EventsStoreTest` 3건 추가.
- MTU 는 185 가 아니라 **512** — 손님 앱이 185 를 요청해도 Android 14+ 스택은 517 을 보내고 서버가 512 로 응답한다 (FAQ Q6).

## 2026-10-03 — 첫 "시작" 크래시 수정: txPowerLevel 단위 혼동 (실기기 SM-G977N · Android 12)

### Fixed
- `Settings.DEFAULT_TX_LEVEL` 이 `3`(레거시 `AdvertiseSettings` 0..3 enum)이라 `AdvertisingSetParameters.Builder.setTxPowerLevel` 이 `IllegalArgumentException: unknown txPowerLevel 3` 을 던지고
  프로세스가 죽었다(광고 자체가 안 나감). → `AdvertisingSetParameters.TX_POWER_HIGH`(1 dBm). `setAdvertising` 은 저장 전에 범위를 자른다(interval 160..INTERVAL_MAX, txLevel −127..1).
- `PosAdvertiser.start` 의 `AdvertisingSetParameters.Builder` 체인이 `runCatching` 밖에 있었다(constitution §5 위반). 이제 Builder 예외도 `State.Failed("파라미터 오류")` + ERR 로그로 끝난다.

### Verified (SM-G977N, Android 12)
- 재설치(`install -r`) → `MY_PACKAGE_REPLACED` → `PosService` FGS 기동 → GATT 서버 등록 → `onAdvertisingSetStarted(0, txPower=-2)` → Activity Log `ADV publisher started … tx -2 dBm, interval 160×0.625ms`.
  "시작" 을 누르지 않아도 광고가 올라온다 (P3 PACKAGE_REPLACED 경로). 요청 1 dBm 에 스택은 −2 dBm 으로 응답.

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
