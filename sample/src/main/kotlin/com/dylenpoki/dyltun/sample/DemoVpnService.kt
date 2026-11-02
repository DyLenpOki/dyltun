package com.dylenpoki.dyltun.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dylenpoki.dyltun.ConnectionRule
import com.dylenpoki.dyltun.DyltunSession
import com.dylenpoki.dyltun.DyltunState
import com.dylenpoki.dyltun.Endpoint
import com.dylenpoki.dyltun.MqttInspector
import com.dylenpoki.dyltun.RoutingContext
import com.dylenpoki.dyltun.RoutingDecision
import com.dylenpoki.dyltun.TlsClientHelloInspector
import com.dylenpoki.dyltun.TransportProtocol
import com.dylenpoki.dyltun.dyltun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DemoVpnService : VpnService() {
    private var session: DyltunSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val monitors = mutableListOf<Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(); return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        try {
            startForeground(1, notification())
            val fd = Builder().setSession("dyltun TCP/UDP demo").setMtu(1500).setBlocking(false)
                .addAddress("10.77.0.1", 32).addRoute("198.18.0.1", 32).establish()
                ?: error("VPN permission was revoked")
            val active = try {
                dyltun(fd) {
                    hooks = DemoHttpHooks
                    protocols { install(TlsClientHelloInspector()); install(MqttInspector()) }
                    routing {
                        tlsSni("blocked.example.com", includeSubdomains = true) { reset() }
                        mqtt { redirect("127.0.0.1", 1883) }
                        rule(object : ConnectionRule {
                            override fun evaluate(context: RoutingContext): RoutingDecision =
                                if (context.connection.destinationPort == 53) {
                                    RoutingDecision.Redirect(Endpoint("1.1.1.1", 53))
                                } else if (context.connection.transport == TransportProtocol.TCP && context.connection.destinationPort == 80) {
                                    RoutingDecision.Redirect(Endpoint("example.com", 80))
                                } else if (context.connection.transport == TransportProtocol.UDP) RoutingDecision.Drop
                                else RoutingDecision.Redirect(Endpoint("1.1.1.1", 443))
                        })
                    }
                }
            } catch (error: Throwable) {
                fd.close(); throw error
            }
            session = active
            monitors += scope.launch {
                active.events.collect { event -> Log.w("dyltun-demo", event.toString()) }
            }
            monitors += scope.launch {
                active.state.collect { state ->
                    if (state is DyltunState.Failed) {
                        Log.e("dyltun-demo", "VPN failed", state.cause)
                        main.post { if (session === active) stopVpn() }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("dyltun-demo", "Unable to start VPN", error)
            stopVpn()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopVpn(); super.onRevoke()
    }

    private fun stopVpn() {
        // Android's VPN binding can outlive stopService(), so release the TUN explicitly.
        session?.close()
        session = null
        monitors.forEach { it.cancel() }
        monitors.clear()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    "vpn",
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            Notification.Builder(this, "vpn")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_running)).setContentIntent(pending)
            .setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "com.dylenpoki.dyltun.sample.STOP"
    }
}
