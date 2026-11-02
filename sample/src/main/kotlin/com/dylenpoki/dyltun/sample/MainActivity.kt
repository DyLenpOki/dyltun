package com.dylenpoki.dyltun.sample

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var probe: Button
    private lateinit var tcpProbe: Button
    private lateinit var httpProbe: Button
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        layout.addView(TextView(this).apply { setText(R.string.description); textSize = 18f })
        layout.addView(Button(this).apply {
            setText(R.string.start)
            setOnClickListener {
                val consent = VpnService.prepare(this@MainActivity)
                if (consent == null) startVpn() else {
                    @Suppress("DEPRECATION")
                    startActivityForResult(consent, 1)
                }
            }
        })
        probe = Button(this).apply { setText(R.string.probe); setOnClickListener { probeDns(false) } }
        layout.addView(probe)
        tcpProbe = Button(this).apply { setText(R.string.probe_tcp); setOnClickListener { probeDns(true) } }
        layout.addView(tcpProbe)
        httpProbe = Button(this).apply { setText(R.string.probe_http); setOnClickListener { probeHttp() } }
        layout.addView(httpProbe)
        layout.addView(Button(this).apply {
            setText(R.string.stop)
            setOnClickListener {
                startService(Intent(this@MainActivity, DemoVpnService::class.java).setAction(DemoVpnService.ACTION_STOP))
                status.setText(R.string.stopped)
            }
        })
        status = TextView(this).apply { setText(R.string.idle) }
        layout.addView(status)
        setContentView(layout)
    }

    @Deprecated("Framework callback used to keep the sample dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) startVpn() else status.setText(R.string.denied)
        }
    }

    private fun startVpn() {
        val intent = Intent(this, DemoVpnService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.setText(R.string.started)
    }

    private fun probeDns(tcp: Boolean) {
        probe.isEnabled = false
        tcpProbe.isEnabled = false
        httpProbe.isEnabled = false
        status.setText(R.string.probing)
        worker.submit {
            val result = try {
                val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                    7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
                val destination = InetAddress.getByAddress(byteArrayOf(198.toByte(), 18, 0, 1))
                val bytes = if (tcp) Socket().use { socket ->
                    socket.connect(InetSocketAddress(destination, 53), 5000)
                    socket.soTimeout = 5000
                    DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
                    socket.shutdownOutput()
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readUnsignedShort()
                    check(length in 12..4096)
                    ByteArray(length).also(input::readFully)
                } else DatagramSocket().use { socket ->
                    socket.soTimeout = 5000
                    socket.send(DatagramPacket(query, query.size, destination, 53))
                    val response = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(response)
                    check(response.address == destination && response.port == 53)
                    response.data.copyOf(response.length)
                }
                check(bytes.size >= 12 && bytes[0] == 0x12.toByte() && bytes[1] == 0x34.toByte() && bytes[2].toInt() and 0x80 != 0)
                getString(if (tcp) R.string.probe_tcp_success else R.string.probe_success, bytes.size, bytes[3].toInt() and 15)
            } catch (error: Exception) { getString(R.string.probe_error, error.message ?: error.javaClass.simpleName) }
            runOnUiThread { if (!isDestroyed) { status.text = result; probe.isEnabled = true; tcpProbe.isEnabled = true; httpProbe.isEnabled = true } }
        }
    }

    private fun probeHttp() {
        probe.isEnabled = false; tcpProbe.isEnabled = false; httpProbe.isEnabled = false
        status.setText(R.string.probing_http)
        worker.submit {
            val result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("198.18.0.1", 80), 5000)
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET /local-health HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                    check(response.startsWith("HTTP/1.1 200 ") && response.contains("X-Dyltun: enabled\r\n") && response.endsWith("""{"status":"ok"}"""))
                    getString(R.string.probe_http_success)
                }
            } catch (error: Exception) { getString(R.string.probe_http_error, error.message ?: error.javaClass.simpleName) }
            runOnUiThread { if (!isDestroyed) { status.text = result; probe.isEnabled = true; tcpProbe.isEnabled = true; httpProbe.isEnabled = true } }
        }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
