package com.r4x.rxcontrolzone

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles taps on the "✓ Yes" / "✗ No" buttons of a confirm notification.
 * Works without opening the app — as long as the foreground bridge service
 * (and therefore the process + MainActivity.instance) is alive.
 */
class ConfirmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val answer = intent.getStringExtra("answer") ?: "no"
        val notifId = intent.getIntExtra("notif_id", 0)

        context.getSystemService(NotificationManager::class.java)?.cancel(notifId)

        MainActivity.instance?.handleConfirmAnswer(answer)
    }
}
