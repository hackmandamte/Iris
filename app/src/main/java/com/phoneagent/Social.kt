package com.phoneagent

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Fires scheduled posts, the daily digest, and re-arms alarms after boot. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Posts.rearmAll(ctx)
                val sp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                if (sp.getBoolean("bubble", false) || sp.getBoolean("quick_on", true)) {
                    try { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) } catch (e: Exception) { }
                }
            }
            "POST" -> intent.getStringExtra("id")?.let { AgentEngine.runScheduledPost(it) }
            "DIGEST" -> AgentEngine.dailySummary()
        }
    }
}

/** Watches notifications for social platforms so the agent can draft replies. */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val platforms = setOf(
            "com.instagram.android", "com.twitter.android", "com.twitter.android.lite",
            "org.telegram.messenger", "com.whatsapp", "com.google.android.youtube"
        )
        if (pkg !in platforms) return
        val n = sbn.notification ?: return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        getSharedPreferences("agent", MODE_PRIVATE).edit()
            .putString("last_notif_pkg", pkg)
            .putString("last_notif_title", title.take(120))
            .putString("last_notif_text", text.take(400))
            .apply()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
