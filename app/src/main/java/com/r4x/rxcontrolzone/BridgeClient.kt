package com.r4x.rxcontrolzone

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * V2 Bridge — handles two modes:
 * 1. cmd mode  : {"cmd":"open youtube"} → run on Python side (legacy)
 * 2. action mode: {"action":"click_text","text":"Search"} → execute via Accessibility
 *
 * IMPORTANT: There is exactly ONE reader of the socket — listenLoop().
 * sendCommand() never reads the socket itself; it registers a pending
 * reply keyed by request_id and listenLoop() resolves it when the
 * matching {"cmd_result":..,"request_id":..} line arrives. This avoids
 * two coroutines racing to read the same BufferedReader.
 */
class BridgeClient(private val activity: MainActivity) {

    companion object { private const val TAG = "RXCZ_BRIDGE" }

    var connected = false
        private set

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = ActionExecutor(activity)
    private val pendingCmdReplies = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun connect(host: String, port: Int) {
        scope.launch {
            try {
                disconnect()
                socket = Socket(host, port)
                val s = socket ?: return@launch
                writer = PrintWriter(s.outputStream, true)
                reader = BufferedReader(InputStreamReader(s.inputStream))
                connected = true
                activity.pushLog("Python bridge connected at $host:$port", "ok")
                activity.pushStatus()
                listenLoop()
            } catch (e: Exception) {
                connected = false
                Log.d(TAG, "Bridge connect failed: ${e.message}")
                activity.pushLog("Bridge not connected — start Python script first", "warn")
                activity.pushStatus()
            }
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; writer = null; reader = null
        connected = false
        // Anyone still waiting on a reply gets unblocked instead of hanging forever
        for ((_, deferred) in pendingCmdReplies) {
            if (!deferred.isCompleted) deferred.complete("Bridge disconnected")
        }
        pendingCmdReplies.clear()
    }

    suspend fun sendCommand(cmd: String, timeoutMs: Long = 30_000): String {
        if (!connected) return "Bridge not connected"
        val w = writer ?: return "Bridge not connected"
        val requestId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<String>()
        pendingCmdReplies[requestId] = deferred
        try {
            val json = JSONObject().apply { put("cmd", cmd); put("request_id", requestId) }
            withContext(Dispatchers.IO) {
                try {
                    w.println(json.toString())
                } catch (e: Exception) {
                    connected = false
                    throw e
                }
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            return result ?: "No response (timeout)"
        } catch (e: Exception) {
            activity.runOnUiThread { activity.pushStatus() }
            return "Bridge error: ${e.message}"
        } finally {
            pendingCmdReplies.remove(requestId)
        }
    }

    /**
     * Listen loop — handles push messages from Python, action requests
     * that APK should execute via Accessibility, AND cmd_result replies
     * that resolve pending sendCommand() calls. This is the ONLY place
     * that calls reader.readLine().
     */
    private suspend fun listenLoop() = withContext(Dispatchers.IO) {
        try {
            while (isActive && connected) {
                val line = reader?.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)

                    // ── Reply to a pending sendCommand() call ──
                    if (json.has("cmd_result")) {
                        val requestId = json.optString("request_id", "")
                        val deferred = pendingCmdReplies.remove(requestId)
                        deferred?.complete(json.optString("cmd_result", "ok"))
                        continue
                    }

                    // ── Action request from Python → execute via Accessibility ──
                    if (json.has("action")) {
                        val requestId = json.optString("request_id", "")
                        scope.launch(Dispatchers.Main) {
                            val result = executor.execute(json)
                            if (requestId.isNotEmpty()) result.put("request_id", requestId)
                            // Send result back to Python
                            withContext(Dispatchers.IO) {
                                try {
                                    writer?.println(result.toString())
                                } catch (_: Exception) {}
                            }
                        }
                        continue
                    }

                    // ── Push update from Python → update WebView ──
                    withContext(Dispatchers.Main) { activity.pushToWeb(json) }

                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        connected = false
        for ((_, deferred) in pendingCmdReplies) {
            if (!deferred.isCompleted) deferred.complete("Bridge disconnected")
        }
        pendingCmdReplies.clear()
        withContext(Dispatchers.Main) {
            activity.pushLog("Bridge disconnected", "warn")
            activity.pushStatus()
        }
    }
}
