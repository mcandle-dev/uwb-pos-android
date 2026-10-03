package dev.mcandle.uwbpos.protocol

import java.util.UUID

/**
 * 광고 바이트 — PROTOCOL §2-1·§2-2, `pos_sim/codec.py:ibeacon_payload`·`scan_response_segments`. 순수 함수.
 *
 * Android 광고에서 앱이 넣는 것은 두 조각뿐이다:
 * - AdvData: `addManufacturerData(0x004C, [payload])` — 23B. 스택이 `len FF 4C 00` 헤더(4B)와 Flags AD(3B)를 붙여 on-air 30B 가 된다.
 *   PROTOCOL §2-1 의 `02 01 06` Flags 값은 스택이 정한다 (spec 001 §F-1).
 * - Scan Response: `addServiceUuid(SERVICE_UUID)` — 스택이 `11 07` + UUID LE 16B = 18B 로 만든다. 여기서는 그 16B 를 테스트용으로 계산한다.
 *
 * `payload` 의 앞 18B(`02 15` + UUID)는 손님 앱 `ScanFilters.iBeaconPrefix` 와 **같아야** 한다 — 손님 앱 필터가 그 18B 를 mask FF 로 본다.
 */
object IBeaconAd {

    const val IBEACON_TYPE: Byte = 0x02
    const val IBEACON_LEN: Byte = 0x15
    const val PAYLOAD_SIZE: Int = 23

    /** 레거시 광고 31B − Flags 3B = 앱 데이터 예산. manufacturer 23B + 헤더 4B = 27B 가 들어가야 한다 */
    const val ADV_DATA_BUDGET: Int = 31 - 3
    const val MANUFACTURER_AD_OVERHEAD: Int = 4 // len(1) + type FF(1) + company ID(2)

    /**
     * `02 15` + UUID(BE 16B) + major(BE 2B) + minor(BE 2B) + tx(int8). 범위 밖이면 `Result.failure` (constitution §5).
     * major/minor 0..65535, tx −128..127. Major 0 은 "미할당" 이라 PROTOCOL §2-1 이 피하라고 하지만 바이트로는 유효하다 — UI 가 경고한다.
     */
    fun payload(proximityUuid: UUID, major: Int, minor: Int, txPowerDbm: Int): Result<ByteArray> {
        if (major !in 0..0xFFFF) return Result.failure(IllegalArgumentException("major $major 범위 밖 (0..65535)"))
        if (minor !in 0..0xFFFF) return Result.failure(IllegalArgumentException("minor $minor 범위 밖 (0..65535)"))
        if (txPowerDbm !in -128..127) return Result.failure(IllegalArgumentException("tx $txPowerDbm 범위 밖 (-128..127)"))
        val out = ByteArray(PAYLOAD_SIZE)
        out[0] = IBEACON_TYPE
        out[1] = IBEACON_LEN
        proximityUuid.toBytes().copyInto(out, 2)
        out[18] = (major shr 8).toByte(); out[19] = major.toByte()
        out[20] = (minor shr 8).toByte(); out[21] = minor.toByte()
        out[22] = txPowerDbm.toByte()
        return Result.success(out)
    }

    /** 앞 18B — 손님 앱 `ScanFilters.iBeaconPrefix` 와 같은 값. 테스트와 화면 raw 표시용 */
    fun prefix(proximityUuid: UUID): ByteArray = byteArrayOf(IBEACON_TYPE, IBEACON_LEN) + proximityUuid.toBytes()

    /** Scan Response 의 UUID 16B — **little-endian** (BE 바이트열을 뒤집은 것). `codec.py:71-76` */
    fun scanResponseUuidLe(serviceUuid: UUID): ByteArray = serviceUuid.toBytes().reversedArray()

    /** 스택이 만드는 Scan Response 18B 전체 (`11 07` + LE 16B) — 화면 raw 표시용 */
    fun scanResponseBytes(serviceUuid: UUID): ByteArray = byteArrayOf(0x11, 0x07) + scanResponseUuidLe(serviceUuid)

    /**
     * 스택이 만드는 on-air AdvData 30B (`02 01 flags` + `1A FF 4C 00` + payload 23B) — 화면 raw 표시용.
     * Flags 값은 기기마다 다를 수 있어 [flags] 로 받는다 (기본 0x06 = PROTOCOL §2-1 예시).
     */
    fun advDataBytes(payload: ByteArray, flags: Byte = 0x06): ByteArray =
        byteArrayOf(0x02, 0x01, flags, (payload.size + 3).toByte(), 0xFF.toByte(), 0x4C, 0x00) + payload

    /** 예산 검사 — 23 + 4 ≤ 28. 바뀌면 `startAdvertisingSet` 이 `ADVERTISE_FAILED_DATA_TOO_LARGE` 를 낸다 */
    fun fitsLegacyBudget(payload: ByteArray): Boolean = payload.size + MANUFACTURER_AD_OVERHEAD <= ADV_DATA_BUDGET
}
