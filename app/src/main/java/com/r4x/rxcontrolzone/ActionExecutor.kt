package com.r4x.rxcontrolzone

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Receives structured action JSON from Python and executes via
 * AccessibilityService — no screenshots, no fixed coordinates.
 *
 * Protocol (Python → APK):
 * {"action":"click_text","text":"Search"}
 * {"action":"click_id","id":"search_button"}
 * {"action":"type","text":"hello world"}
 * {"action":"tap","x":540,"y":300}
 * {"action":"swipe","x1":500,"y1":800,"x2":500,"y2":300}
 * {"action":"key","key":"back"|"home"|"enter"|"notifications"}
 * {"action":"open_app","package":"com.android.chrome"}
 * {"action":"get_screen_text"}
 * {"action":"scroll","direction":"down"|"up"}
 * {"action":"wait","ms":1000}
 * {"action":"copy_text","text":"..."}
 * {"action":"read_clipboard"}
 * {"action":"read_otp"}              // returns the most likely OTP digits from the latest SMS
 * {"action":"read_latest_sms"}       // returns the full body of the latest SMS
 * {"action":"find_contact","name":"Mom"}  // returns the matching contact's phone number
 *
 * APK → Python reply:
 * {"ok":true,"result":"done"}
 * {"ok":false,"error":"element not found"}
 * {"ok":true,"result":"screen text here..."}  // for get_screen_text
 */
class ActionExecutor(private val activity: MainActivity) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    suspend fun execute(json: JSONObject): JSONObject {
        val action = json.optString("action", "")
        val acc = RxAccessibilityService.instance

        return when (action) {

            "click_text" -> {
                val text = json.optString("text", "")
                if (acc == null) return err("Accessibility Service not running")
                val ok = acc.clickByText(text)
                if (ok) ok("clicked: $text")
                else {
                    // Fallback: try tap with coordinates if provided
                    val x = json.optInt("x", -1)
                    val y = json.optInt("y", -1)
                    if (x > 0 && y > 0) {
                        val resultDeferred = CompletableDeferred<Boolean>()
                        acc.tapAt(x.toFloat(), y.toFloat()) { success -> resultDeferred.complete(success) }
                        val success = withTimeoutOrNull(1000) { resultDeferred.await() } ?: false
                        if (success) ok("tapped fallback at $x,$y") else err("Element not found: $text (fallback tap also failed)")
                    } else err("Element not found: $text")
                }
            }

            "click_id" -> {
                val id = json.optString("id", "")
                if (acc == null) return err("Accessibility Service not running")
                val ok = acc.clickByText(id)
                if (ok) ok("clicked id: $id") else err("Id not found: $id")
            }

            "type" -> {
                val text = json.optString("text", "")
                val hint = json.optString("hint", "")
                if (acc == null) return err("Accessibility Service not running")
                val ok = acc.typeIntoField(hint, text)
                if (ok) ok("typed: $text") else {
                    // Fallback: use ADB input text
                    val safe = text.replace(" ", "%s")
                    val r = withContext(Dispatchers.IO) { activity.runShizukuCommand("input text $safe") }
                    ok("typed via adb: $r")
                }
            }

            "tap" -> {
                val x = json.optInt("x", 0).toFloat()
                val y = json.optInt("y", 0).toFloat()
                if (acc == null) return err("Accessibility not running")
                val resultDeferred = CompletableDeferred<Boolean>()
                acc.tapAt(x, y) { success -> resultDeferred.complete(success) }
                val success = withTimeoutOrNull(1000) { resultDeferred.await() } ?: false
                if (success) ok("tapped $x,$y") else err("tap failed at $x,$y")
            }

            "swipe" -> {
                val x1 = json.optInt("x1", 0).toFloat()
                val y1 = json.optInt("y1", 0).toFloat()
                val x2 = json.optInt("x2", 0).toFloat()
                val y2 = json.optInt("y2", 0).toFloat()
                val dur = json.optLong("duration_ms", 300)
                if (acc == null) return err("Accessibility not running")
                acc.swipe(x1, y1, x2, y2, dur)
                delay(dur + 100)
                ok("swiped")
            }

            "key" -> {
                val key = json.optString("key", "back")
                if (acc == null) return err("Accessibility not running")
                when (key.lowercase()) {
                    "back"          -> acc.pressBack()
                    "home"          -> acc.pressHome()
                    "notifications" -> acc.openNotifications()
                    "enter"         -> withContext(Dispatchers.IO) { activity.runShizukuCommand("input keyevent KEYCODE_ENTER") }
                    "volume_up"     -> withContext(Dispatchers.IO) { activity.runShizukuCommand("input keyevent KEYCODE_VOLUME_UP") }
                    "volume_down"   -> withContext(Dispatchers.IO) { activity.runShizukuCommand("input keyevent KEYCODE_VOLUME_DOWN") }
                    else            -> withContext(Dispatchers.IO) { activity.runShizukuCommand("input keyevent $key") }
                }
                delay(300)
                ok("key: $key")
            }

            "open_app" -> {
                val pkg = json.optString("package", "")
                if (pkg.isEmpty()) return err("package name required")
                val r = withContext(Dispatchers.IO) {
                    activity.runShizukuCommand(
                        "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                    )
                }
                delay(1500)
                ok("opened: $pkg — $r")
            }

            "get_screen_text" -> {
                if (acc == null) return err("Accessibility not running")
                val text = acc.getScreenText()
                JSONObject().apply {
                    put("ok", true)
                    put("result", text)
                }
            }

            "scroll" -> {
                val dir = json.optString("direction", "down")
                if (acc == null) return err("Accessibility not running")
                val ok = acc.scroll(dir)
                if (ok) ok("scrolled $dir") else err("scroll failed")
            }

            "wait" -> {
                val ms = json.optLong("ms", 1000)
                delay(ms)
                ok("waited ${ms}ms")
            }

            "copy_text" -> {
                val text = json.optString("text", "")
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("rxcz", text))
                ok("copied to clipboard")
            }

            "read_clipboard" -> {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0)
                    clip.getItemAt(0).coerceToText(activity).toString() else ""
                JSONObject().apply { put("ok", true); put("result", text) }
            }

            "read_otp" -> {
                if (!activity.hasSmsPermission()) return err("SMS permission not granted — enable it in phone Settings > Apps > RX Control Zone > Permissions")
                val otp = withContext(Dispatchers.IO) { readLatestOtp() }
                if (otp != null) ok(otp) else err("No OTP-looking number found in recent SMS")
            }

            "read_latest_sms" -> {
                if (!activity.hasSmsPermission()) return err("SMS permission not granted — enable it in phone Settings > Apps > RX Control Zone > Permissions")
                val body = withContext(Dispatchers.IO) { readLatestSmsBody() }
                if (body != null) ok(body) else err("No SMS found")
            }

            "find_contact" -> {
                val name = json.optString("name", "")
                if (name.isEmpty()) return err("contact name required")
                if (!activity.hasContactsPermission()) return err("Contacts permission not granted — enable it in phone Settings > Apps > RX Control Zone > Permissions")
                val number = withContext(Dispatchers.IO) { findContactNumber(name) }
                if (number != null) ok(number) else err("Contact not found: $name")
            }

            else -> err("Unknown action: $action")
        }
    }

    // ── SMS (on-demand read only — no background listener, no forwarding) ──
    private fun readLatestSmsBody(): String? {
        val cursor = activity.contentResolver.query(
            android.provider.Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.DATE),
            null, null,
            "${android.provider.Telephony.Sms.DATE} DESC"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.Telephony.Sms.BODY)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun readLatestOtp(): String? {
        val body = readLatestSmsBody() ?: return null
        // 4-8 digit number — covers almost all OTP formats
        return Regex("\\b\\d{4,8}\\b").find(body)?.value
    }

    // ── Contacts (read-only lookup) ──
    private fun findContactNumber(name: String): String? {
        val cursor = activity.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun ok(msg: String) = JSONObject().apply {
        put("ok", true); put("result", msg)
    }
    private fun err(msg: String) = JSONObject().apply {
        put("ok", false); put("error", msg)
    }
}
