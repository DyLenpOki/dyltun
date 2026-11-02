package com.dylenpoki.dyltun

interface HttpHooks {
    fun request(flow: HttpFlow) {}
    fun response(flow: HttpFlow) {}
    /** May set flow.response after upstream I/O fails, before any final response is committed. */
    fun error(flow: HttpFlow, cause: Throwable) {}
}

enum class ResponseSource { UPSTREAM, LOCAL }

class HttpFlow internal constructor(
    val id: Long,
    val connection: ConnectionInfo,
    val request: HttpRequest,
    private val limits: BodyLimits,
    private val allocate: ((Long) -> Unit)? = null
) {
    var responseSource: ResponseSource? = null
        private set

    var response: HttpResponse? = null
        set(value) {
            check(!responseCallback || value != null) { "response() cannot remove the final response" }
            value?.let {
                synchronized(it) {
                    check(it.owner == null || it.owner === this) { "A response cannot be shared between flows" }
                    it.bodyLimit = limits.response
                    it.validateContent()
                    allocate?.let(it::bindAllocation)
                    it.owner = this
                }
            }
            field = value
            responseSource = if (value == null) null else ResponseSource.LOCAL
        }

    private var responseCallback = false

    init {
        request.bodyLimit = limits.request
        request.validateContent()
        allocate?.let(request::bindAllocation)
    }

    internal fun receiveUpstream(value: HttpResponse) {
        response = value
        responseSource = ResponseSource.UPSTREAM
    }

    internal fun beginResponseCallback() {
        check(response != null) { "Missing final response" }
        responseCallback = true
    }
}
