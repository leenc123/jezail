package com.azzahid.jezail.core.api

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.azzahid.jezail.JezailApp
import com.azzahid.jezail.features.managers.AuthManager
import com.azzahid.jezail.features.managers.DeviceManager
import com.topjohnwu.superuser.Shell
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

private const val TAG = "DiscoveryResponder"

/**
 * Answers LAN discovery probes so desktop tooling can find this device
 * without knowing its IP in advance.
 *
 * Protocol (UDP, default port 8090):
 * - manager -> broadcast: ASCII "JEZAIL_DISCOVER_V1"
 * - device  -> unicast reply: JSON object, see [buildReply]
 *
 * Runs on its own daemon thread; started/stopped together with the HTTP
 * server so discovery is only advertised while the API is actually up.
 * Everything here is best-effort: any failure is logged and the HTTP
 * server is unaffected.
 */
object DiscoveryResponder {

    const val MAGIC = "JEZAIL_DISCOVER_V1"
    const val DISCOVERY_PORT = 8090

    /** Minimum interval between two replies to the same sender. */
    private const val REPLY_THROTTLE_MS = 1000L

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    private val lastReply = mutableMapOf<String, Long>()

    @Synchronized
    fun start(serverPort: Int, discoveryPort: Int = DISCOVERY_PORT) {
        if (running) return
        running = true
        thread = thread(isDaemon = true, name = "jezail-discovery") {
            runLoop(serverPort, discoveryPort)
        }
        Log.d(TAG, "Discovery responder started on UDP $discoveryPort")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }
        try {
            thread?.join(1000)
        } catch (_: Exception) {
        } finally {
            thread = null
        }
        Log.d(TAG, "Discovery responder stopped")
    }

    fun isRunning(): Boolean = running

    private fun runLoop(serverPort: Int, discoveryPort: Int) {
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(discoveryPort))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot bind discovery socket on UDP $discoveryPort, discovery disabled", e)
            running = false
            return
        }
        socket = sock
        val buf = ByteArray(256)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                if (msg != MAGIC) continue
                val sender = pkt.address.hostAddress ?: continue
                val now = SystemClock.elapsedRealtime()
                synchronized(lastReply) {
                    if (now - (lastReply[sender] ?: 0L) < REPLY_THROTTLE_MS) return@synchronized
                    lastReply[sender] = now
                }
                val reply = collectAndBuild(serverPort).toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
            } catch (_: SocketTimeoutException) {
                // Wake up periodically so stop() is honored promptly.
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "Discovery loop error", e)
            }
        }
        try {
            sock.close()
        } catch (_: Exception) {
        } finally {
            if (socket === sock) socket = null
        }
    }

    /**
     * Builds the JSON body answering a discovery probe.
     * Kept side-effect free (all inputs are parameters) so it is unit-testable.
     */
    fun buildReply(
        serverPort: Int,
        deviceName: String?,
        androidId: String?,
        appVersion: String?,
        isRooted: Boolean,
        authEnabled: Boolean,
    ): String {
        fun esc(value: String) = buildString {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        fun str(value: String?) = if (value == null) "null" else "\"${esc(value)}\""
        return buildString {
            append("{\"type\":\"jezail-hello\",\"v\":1,")
            append("\"id\":${str(androidId)},")
            append("\"name\":${str(deviceName ?: Build.MODEL)},")
            append("\"manufacturer\":${str(Build.MANUFACTURER)},")
            append("\"model\":${str(Build.MODEL)},")
            append("\"android\":${str(Build.VERSION.RELEASE)},")
            append("\"sdk\":${Build.VERSION.SDK_INT},")
            append("\"http\":$serverPort,")
            append("\"auth\":$authEnabled,")
            append("\"root\":$isRooted,")
            append("\"app\":${str(appVersion)},")
            append("\"api\":1}")
        }
    }

    /** Gathers live values and builds the reply for one probe. Never throws. */
    fun collectAndBuild(serverPort: Int): String {
        return try {
            val ctx = JezailApp.appContext
            val resolver = ctx.contentResolver
            val androidId = runCatching {
                Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val appVersion = runCatching {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull()
            val isRooted = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
            buildReply(
                serverPort = serverPort,
                deviceName = runCatching { DeviceManager.getUserDefinedDeviceName(resolver) }.getOrNull(),
                androidId = androidId,
                appVersion = appVersion,
                isRooted = isRooted,
                authEnabled = runCatching { AuthManager.isEnabled() }.getOrDefault(false),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build discovery reply", e)
            buildReply(serverPort, null, null, null, isRooted = false, authEnabled = false)
        }
    }
}
