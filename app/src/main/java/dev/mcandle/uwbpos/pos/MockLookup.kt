package dev.mcandle.uwbpos.pos

import dev.mcandle.uwbpos.protocol.ResultJson
import kotlinx.coroutines.delay

/**
 * 멤버 조회 — 1차는 mock (REQUIREMENTS D-008). `pos_sim/member_lookup.py` 와 같은 DB·지연·마스킹. 실제 서버는 범위 밖.
 * 반환은 이미 PROTOCOL §3-2 규칙(≤120B)을 적용한 JSON 문자열이다.
 */
fun interface Lookup {
    suspend fun lookup(memberId: String): String
}

class MockLookup(
    private val delayMs: Long = DEFAULT_DELAY_MS,
    /** D-009 디버그 — 있는 ID 도 not_found 로 */
    private val forceNotFound: () -> Boolean = { false },
) : Lookup {

    override suspend fun lookup(memberId: String): String {
        delay(delayMs) // 네트워크 흉내 (`member_lookup.py:31`)
        val name: String? = if (forceNotFound()) null else DB[memberId]
        return if (name == null) ResultJson.notFound() else ResultJson.success(mask(name))
    }

    companion object {
        const val DEFAULT_DELAY_MS: Long = 50L

        /** `member_lookup.py:13-18` — 1111222200 은 2026-09-21 연동 시험 폰의 ID */
        val DB: Map<String, String> = mapOf(
            "0123456789" to "홍길동",
            "9876543210" to "김영희",
            "1111111111" to "테스터",
            "1111222200" to "김테스트",
        )

        /** `_mask`: 1글자 그대로, 2글자 `X*`, 3글자 이상 첫 글자 + `*`… + 끝 글자 */
        fun mask(name: String): String = when {
            name.length <= 1 -> name
            name.length == 2 -> name[0] + "*"
            else -> name[0] + "*".repeat(name.length - 2) + name[name.length - 1]
        }
    }
}
