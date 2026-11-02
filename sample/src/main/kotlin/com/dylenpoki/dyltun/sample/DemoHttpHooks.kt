package com.dylenpoki.dyltun.sample

import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse

internal object DemoHttpHooks : HttpHooks {
    override fun request(flow: HttpFlow) {
        flow.request.headers["X-Dyltun-Request"] = "enabled"
        if (flow.request.path == "/local-health") {
            flow.response = HttpResponse.make(200, """{"status":"ok"}""",
                HttpHeaders.of("Content-Type" to "application/json; charset=utf-8"))
        }
    }

    override fun response(flow: HttpFlow) {
        flow.response!!.headers["X-Dyltun"] = "enabled"
    }
}
