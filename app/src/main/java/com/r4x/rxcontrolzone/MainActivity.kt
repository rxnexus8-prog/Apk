package com.r4x.rxcontrolzone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.os.VibrationEffect
import android.provider.Settings
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
            private set
        private const val CONFIRM_CHANNEL_ID = "rxcz_confirm"
        private const val ALERT_CHANNEL_ID = "rxcz_alert"
    }

    private lateinit var webView: WebView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bridgeClient: BridgeClient? = null
    private var activeConfirmNotifId: Int? = null

    // ── Shizuku permission request code ──────────────────────────────────────
    private val SHIZUKU_REQ = 1001
    private val RUNTIME_PERMS_REQ = 1002

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        instance = this

        setupWebView()
        setupShizuku()
        requestRuntimePermissions()
        startBridgeService()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        bridgeClient?.disconnect()
    }

    override fun onResume() {
        super.onResume()
        // Refresh accessibility status when user returns from settings
        pushStatus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView
    // ─────────────────────────────────────────────────────────────────────────
    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.setBackgroundColor(0xFF07070F.toInt())

        // Inject JavaScript interface as "Android"
        webView.addJavascriptInterface(JsBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Small delay so JS initialises before we push status
                Handler(Looper.getMainLooper()).postDelayed({ pushStatus() }, 400)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Push update to HTML via rxUpdate()
    // ─────────────────────────────────────────────────────────────────────────
    fun pushToWeb(json: JSONObject) {
        val escaped = json.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        runOnUiThread {
            webView.evaluateJavascript("window.rxUpdate('$escaped')", null)
        }
        if (json.has("confirm")) {
            postConfirmNotification(json.optString("confirm", "Confirm action?"))
        }
        if (json.has("alert")) {
            postAlertNotification(json.optString("alert", "Task finished"), json.optString("alertType", "info"))
        }
    }

    /** Called by ConfirmActionReceiver (notification button) or by JsBridge.confirmResult (in-app). */
    fun handleConfirmAnswer(answer: String) {
        activeConfirmNotifId?.let {
            getSystemService(NotificationManager::class.java)?.cancel(it)
        }
        activeConfirmNotifId = null
        scope.launch(Dispatchers.IO) { bridgeClient?.sendCommand("__confirm__:$answer") }
        pushLog("Confirmed: $answer", if (answer == "yes") "ok" else "warn")
    }

    private fun postConfirmNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return  // can't post without permission — WebView popup still works as the fallback

        createConfirmChannel()
        val notifId = (System.currentTimeMillis() % 100000).toInt()
        activeConfirmNotifId = notifId

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val yesPending = PendingIntent.getBroadcast(
            this, notifId * 3,
            Intent(this, ConfirmActionReceiver::class.java).putExtra("answer", "yes").putExtra("notif_id", notifId),
            flags
        )
        val noPending = PendingIntent.getBroadcast(
            this, notifId * 3 + 1,
            Intent(this, ConfirmActionReceiver::class.java).putExtra("answer", "no").putExtra("notif_id", notifId),
            flags
        )
        val allPending = PendingIntent.getBroadcast(
            this, notifId * 3 + 2,
            Intent(this, ConfirmActionReceiver::class.java).putExtra("answer", "all").putExtra("notif_id", notifId),
            flags
        )

        val notification = NotificationCompat.Builder(this, CONFIRM_CHANNEL_ID)
            .setContentTitle("RX Control Zone — confirm action")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "✓ Yes", yesPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "✗ No", noPending)
            .addAction(android.R.drawable.ic_menu_set_as, "✓✓ All (this task)", allPending)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(notifId, notification)
    }

    private fun createConfirmChannel() {
        val channel = NotificationChannel(
            CONFIRM_CHANNEL_ID, "RX Confirmations", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Confirm or deny sensitive AI actions" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Fires at the end of every task — success or problem — so you know it's
     * done even if you're not looking at the phone. Title/icon differ by type. */
    private fun postAlertNotification(message: String, alertType: String = "info") {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val channel = NotificationChannel(
            ALERT_CHANNEL_ID, "RX Task Updates", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Task completed or needs your attention" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val title = if (alertType == "warn") "RX Control Zone — needs attention" else "RX Control Zone — task done"
        val icon = if (alertType == "warn") android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info

        val notifId = ((System.currentTimeMillis() % 100000) + 500000).toInt()
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(notifId, notification)
    }

    fun pushLog(msg: String, type: String = "info") {
        pushToWeb(JSONObject().apply { put("log", msg); put("logType", type) })
    }

    fun pushStatus() {
        val accEnabled = isAccessibilityEnabled()
        val shizukuOk  = isShizukuAvailable()
        val bridgeOk   = bridgeClient?.connected == true

        pushToWeb(JSONObject().apply {
            put("status", JSONObject().apply {
                put("accessibility", accEnabled)
                put("shizuku", shizukuOk)
                put("adb", bridgeOk)
                put("python", bridgeOk)
            })
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessibility check
    // ─────────────────────────────────────────────────────────────────────────
    private fun isAccessibilityEnabled(): Boolean {
        val serviceId = "${packageName}/${RxAccessibilityService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains(serviceId, ignoreCase = true)
        } catch (e: Exception) { false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shizuku
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupShizuku() {
        Shizuku.addBinderReceivedListenerSticky {
            pushLog("Shizuku binder received", "ok")
            pushStatus()
        }
        Shizuku.addBinderDeadListener {
            pushLog("Shizuku binder lost", "warn")
            pushStatus()
        }
    }

    private fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                requestPermissions(arrayOf("moe.shizuku.manager.permission.API_V23"), SHIZUKU_REQ)
            } else {
                Shizuku.requestPermission(SHIZUKU_REQ)
            }
        } catch (e: Exception) {
            pushLog("Shizuku not installed — install from Play Store", "warn")
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), RUNTIME_PERMS_REQ)
        }
    }

    fun hasSmsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasContactsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // Run shell command via Shizuku (returns output string)
    // Shizuku.newProcess() was made private in dev.rikka.shizuku:api 13.x —
    // the method still exists at runtime, so we call it via reflection.
    fun runShizukuCommand(cmd: String): String {
        return try {
            if (!isShizukuAvailable()) return "Shizuku not available"
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null, arrayOf("sh", "-c", cmd), null, null
            ) as rikka.shizuku.ShizukuRemoteProcess
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        } catch (e: Exception) { "Shizuku error: ${e.message}" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Python bridge service
    // ─────────────────────────────────────────────────────────────────────────
    private fun startBridgeService() {
        val intent = Intent(this, BridgeService::class.java)
        startForegroundService(intent)
        bridgeClient = BridgeClient(this)
        bridgeClient?.connect("127.0.0.1", 7070)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JavaScript Interface — HTML calls these via Android.methodName()
    // ─────────────────────────────────────────────────────────────────────────
    inner class JsBridge {

        @JavascriptInterface
        fun executeCommand(cmd: String) {
            pushLog("Command received: $cmd", "cmd")
            pushToWeb(JSONObject().apply { put("task", cmd) })
            // Send to Python bridge
            scope.launch(Dispatchers.IO) {
                val result = bridgeClient?.sendCommand(cmd) ?: "Bridge not connected"
                withContext(Dispatchers.Main) {
                    pushLog(result, "ok")
                    pushToWeb(JSONObject().apply { put("task", "") }) // clear task
                }
            }
        }

        @JavascriptInterface
        fun openAccessibility() {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            pushLog("Accessibility settings opened", "action")
        }

        @JavascriptInterface
        fun bindShizuku() {
            if (Shizuku.pingBinder()) {
                requestShizukuPermission()
                pushLog("Shizuku permission requested", "action")
            } else {
                pushLog("Shizuku not installed — get it from Play Store", "warn")
                Toast.makeText(this@MainActivity, "Install Shizuku from Play Store", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun connectAdb() {
            scope.launch(Dispatchers.IO) {
                val result = runShizukuCommand("adb devices")
                withContext(Dispatchers.Main) {
                    pushLog("ADB: $result", "info")
                    pushStatus()
                }
            }
        }

        @JavascriptInterface
        fun connectBridge(ipPort: String) {
            val parts = ipPort.split(":")
            val host = parts.getOrElse(0) { "127.0.0.1" }
            val port = parts.getOrElse(1) { "7070" }.toIntOrNull() ?: 7070
            bridgeClient?.connect(host, port)
            pushLog("Connecting to Python bridge at $ipPort", "action")
        }

        @JavascriptInterface
        fun checkStatus() { pushStatus() }

        @JavascriptInterface
        fun newChat() {
            scope.launch(Dispatchers.IO) { bridgeClient?.sendCommand("new chat") }
            pushLog("Session memory cleared", "ok")
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            val vib = getSystemService(android.os.Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }

        @JavascriptInterface
        fun applySettings(settingsJson: String) {
            try {
                val s = JSONObject(settingsJson)
                pushLog("Settings updated", "ok")
                // Pass new API key / model to Python bridge
                scope.launch(Dispatchers.IO) { bridgeClient?.sendCommand("__settings__:$settingsJson") }
            } catch (e: Exception) {
                pushLog("Settings parse error", "error")
            }
        }

        @JavascriptInterface
        fun confirmResult(result: String) {
            handleConfirmAnswer(result)
        }

        @JavascriptInterface
        fun storeSave(key: String, value: String) {
            getSharedPreferences("rxcz", MODE_PRIVATE).edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun storeLoad(key: String): String {
            return getSharedPreferences("rxcz", MODE_PRIVATE).getString(key, "") ?: ""
        }
    }
}
