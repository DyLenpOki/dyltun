package com.dylenpoki.dyltun.sample

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.MqttInspector
import com.dylenpoki.dyltun.TlsClientHelloInspector
import com.dylenpoki.dyltun.dyltun

/** Complete API example, including optional HTTP hooks, body limits and protocol routing. */
@Suppress("unused")
internal fun VpnService.fullApiExample(fd: ParcelFileDescriptor): DyltunSession = dyltun(fd) {
    hooks = object : HttpHooks {
        override fun request(flow: HttpFlow) {
            if (flow.request.path == "/local-health") {
                flow.response = HttpResponse.make(200, """{"status":"ok"}""",
                    HttpHeaders.of("Content-Type" to "application/json; charset=utf-8"))
            }
        }
        override fun response(flow: HttpFlow) { flow.response?.headers?.set("X-Dyltun", "enabled") }
    }
    bodyLimits = BodyLimits(
        request = BodyLimit(512L * 1024, 2L * 1024 * 1024),
        response = BodyLimit(2L * 1024 * 1024, 8L * 1024 * 1024),
        maxTotalBufferedBytes = 32L * 1024 * 1024
    )
    protocols { install(TlsClientHelloInspector()); install(MqttInspector()) }
    routing {
        tlsSni(listOf("blocked.example.com", "blocked.example.org"), includeSubdomains = true) { reset() }
        mqtt { redirect("127.0.0.1", 1883) }
    }
}
