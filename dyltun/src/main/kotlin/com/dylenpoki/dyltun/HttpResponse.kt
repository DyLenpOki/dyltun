package com.dylenpoki.dyltun

import java.nio.charset.Charset

class HttpResponse private constructor(
    statusCode: Int,
    headers: HttpHeaders,
    content: ByteArray,
    switchingProtocols: Boolean = false
) : HttpMessage(headers, content, BodyState.BUFFERED) {
    var statusCode: Int = if (switchingProtocols && statusCode == 101) 101 else checkedStatus(statusCode)
        set(value) { field = checkedStatus(value) }

    internal var owner: HttpFlow? = null

    companion object {
        internal fun fromWire(statusCode: Int, headers: HttpHeaders): HttpResponse =
            HttpResponse(statusCode, headers, byteArrayOf(), switchingProtocols = true)

        fun make(
            statusCode: Int = 200,
            content: ByteArray = byteArrayOf(),
            headers: HttpHeaders = HttpHeaders()
        ): HttpResponse = HttpResponse(statusCode, headers, content)

        fun make(
            statusCode: Int = 200,
            content: String,
            headers: HttpHeaders = HttpHeaders(),
            charset: Charset = Charsets.UTF_8
        ): HttpResponse {
            val copied = headers.copy()
            if (copied["Content-Type"] == null) copied["Content-Type"] = "text/plain; charset=${charset.name()}"
            val bytes = encodeText(content, charset, Int.MAX_VALUE.toLong())
            val response = HttpResponse(statusCode, copied, bytes)
            require(response.text == content) { "Content-Type charset does not match encoded text" }
            return response
        }

        private fun checkedStatus(status: Int): Int {
            require(status in 200..599) { "make() requires a final status within 200..599" }
            return status
        }
    }
}
