package com.dylenpoki.dyltun.sample

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.dylenpoki.dyltun.BodyLimit
import com.dylenpoki.dyltun.BodyLimits
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.HttpFlow
import com.dylenpoki.dyltun.HttpHeaders
import com.dylenpoki.dyltun.HttpHooks
import com.dylenpoki.dyltun.HttpResponse
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.dyltun
import com.dylenpoki.dyltun.dyltunConfig

/** Reusable configuration, independent of the Android service and its descriptor. */
private val fullConfig = dyltunConfig {
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
    routing {
        tlsSni(
            domains = listOf("blocked.example.com", "blocked.example.org"),
            includeSubdomains = true,
            action = RoutingDecision.Reset
        )
        mqtt(action = RoutingDecision.Redirect(Endpoint("127.0.0.1", 1883)))
    }
}

/** The host only needs to supply an established TUN descriptor. */
@Suppress("unused")
internal fun VpnService.fullApiExample(fd: ParcelFileDescriptor): DyltunSession = dyltun(fd, fullConfig)
