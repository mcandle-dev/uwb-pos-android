package dev.mcandle.uwbpos.protocol

/**
 * result JSON — PROTOCOL §3-2, `pos_sim/member_lookup.py:38-54 to_json` 을 바이트 단위로 같게 옮겼다. 순수 함수.
 *
 * `{"v":1,"status":"success","member":"홍*동","message":"mock"}` — `v` 가 첫 키, 공백 없음, UTF-8 ≤ 120B.
 * 넘치면 message 를 40자로 자른 뒤 한 글자씩 줄이고, 비면 키를 지우고, 그래도 넘치면 member 를 null 로.
 * **`org.json` 을 쓰지 않는다** — `/` 를 `\/` 로 이스케이프해 Python `json.dumps` 와 바이트가 달라진다. 멤버 ID 는 넣지 않는다 (constitution §6).
 */
object ResultJson {

    fun build(status: String, member: String?, message: String?): String {
        var msg: String? = message?.take(ProtocolConstants.RESULT_MESSAGE_MAX_CHARS)
        var mem: String? = member
        var s: String = dump(status, mem, msg, includeMessage = message != null)
        var includeMessage: Boolean = message != null
        while (utf8Len(s) > ProtocolConstants.RESULT_MAX_BYTES && includeMessage && !msg.isNullOrEmpty()) {
            msg = msg.dropLast(1)
            if (msg.isEmpty()) { msg = null; includeMessage = false }
            s = dump(status, mem, msg, includeMessage)
        }
        if (utf8Len(s) > ProtocolConstants.RESULT_MAX_BYTES) {
            mem = null
            s = dump(status, mem, msg, includeMessage)
        }
        return s
    }

    /** 조회 성공 */
    fun success(maskedName: String, message: String = "mock"): String = build(ProtocolConstants.RESULT_SUCCESS, maskedName, message)

    /** 조회 실패 — 멤버 없음 */
    fun notFound(message: String = "mock: unknown member"): String = build(ProtocolConstants.RESULT_NOT_FOUND, null, message)

    /** 조회 예외 — `gatt_server.py:393` `lookup failed: …` */
    fun error(message: String): String = build(ProtocolConstants.RESULT_ERROR, null, message)

    private fun dump(status: String, member: String?, message: String?, includeMessage: Boolean): String = buildString {
        append("{\"v\":").append(ProtocolConstants.RESULT_SCHEMA_VERSION)
        append(",\"status\":").append(quote(status))
        append(",\"member\":").append(if (member == null) "null" else quote(member))
        if (includeMessage) append(",\"message\":").append(if (message == null) "null" else quote(message))
        append('}')
    }

    /** Python `json.dumps(ensure_ascii=False)` 와 같은 이스케이프: `"` `\` 와 제어문자만 */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
