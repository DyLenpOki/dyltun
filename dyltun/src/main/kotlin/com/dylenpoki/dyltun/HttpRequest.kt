package com.dylenpoki.dyltun

import java.net.URI

class HttpRequest(
    method: String,
    url: String,
    headers: HttpHeaders = HttpHeaders(),
    content: ByteArray = byteArrayOf()
) : HttpMessage(headers, content, BodyState.BUFFERED) {
    var method: String = method
        set(value) {
            require(isHttpToken(value)) { "Invalid HTTP method" }
            field = value
        }

    private var uri: URI = checkedUri(url)

    var url: String
        get() = uri.toASCIIString()
        set(value) {
            val next = checkedUri(value)
            if (next.rawAuthority != uri.rawAuthority) this.headers["Host"] = next.rawAuthority
            uri = next
        }

    /** Origin-form path including the raw query, matching mitmproxy's path convention. */
    var path: String
        get() = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        set(value) {
            require(value.startsWith('/') && !value.startsWith("//") && '#' !in value) { "Invalid HTTP path" }
            uri = checkedUri("${uri.scheme}://${uri.rawAuthority}$value")
        }

    init {
        require(isHttpToken(method)) { "Invalid HTTP method" }
        if (this.headers["Host"] == null) this.headers["Host"] = uri.rawAuthority
    }

    private companion object {
        fun checkedUri(value: String): URI {
            require(value.none { it.code <= 32 || it.code == 127 }) { "Invalid HTTP URL" }
            val uri = URI(value)
            require(uri.scheme.equals("http", true)) { "Only http:// URLs are supported" }
            require(!uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null) {
                "An absolute HTTP URL without user information or fragment is required"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid HTTP port" }
            return URI(uri.toASCIIString())
        }
    }
}
