package com.phoneagent

import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Fires scheduled posts, the daily digest, and re-arms alarms after boot. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Posts.rearmAll(ctx)
                val sp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                if (sp.getBoolean("bubble", false) || sp.getBoolean("quick_on", true)) {
                    try { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) } catch (e: Exception) { }
                }
            }
            "POST" -> i.getStringExtra("id")?.let { AgentEngine.runScheduledPost(it) }
            "DIGEST" -> {
                Posts.armDigest(ctx)
                if (ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getBoolean("digest_on", false)) {
                    AgentEngine.runDigest()
                }
            }
        }
    }
}

/** Collects notifications from the social apps (only after the user grants Notification access). */
class NotifListener : NotificationListenerService() {
    companion object {
        val live = ConcurrentHashMap<String, StatusBarNotification>()
        val APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "org.thunderdog.challegram" to "Telegram X",
            "com.twitter.android" to "X",
            "com.google.android.youtube" to "YouTube"
        )
        fun ref(sbn: StatusBarNotification): String = Integer.toHexString(sbn.key.hashCode())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val r = ref(sbn)
        live[r] = sbn
        NotifLog.add(this, app, title.take(60), text.take(300), sbn.postTime, r)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) live.remove(ref(sbn))
    }
}

object NotifLog {
    private val lock = Any()
    private fun sp(ctx: Context) = ctx.getSharedPreferences("agent_notifs", Context.MODE_PRIVATE)

    fun hasAccess(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(ctx.packageName)
    }

    fun add(ctx: Context, app: String, title: String, text: String, t: Long, ref: String) {
        synchronized(lock) {
            val arr = JSONArray(sp(ctx).getString("l", "[]"))
            if (arr.length() > 0) {
                val last = arr.getJSONObject(arr.length() - 1)
                if (last.getString("app") == app && last.getString("title") == title && last.getString("text") == text) return
            }
            arr.put(JSONObject().put("app", app).put("title", title).put("text", text).put("t", t).put("ref", ref))
            while (arr.length() > 300) arr.remove(0)
            sp(ctx).edit().putString("l", arr.toString()).apply()
        }
    }

    fun read(ctx: Context, app: String, hours: Int): String {
        if (!hasAccess(ctx)) {
            return "ERROR: notification access is off. Ask the user to turn it on in Settings > Notifications > Notification access."
        }
        val arr = synchronized(lock) { JSONArray(sp(ctx).getString("l", "[]")) }
        val cutoff = System.currentTimeMillis() - hours.coerceIn(1, 168) * 3_600_000L
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("t") < cutoff) continue
            if (app.isNotBlank() && !o.getString("app").contains(app, true)) continue
            val canReply = NotifListener.live[o.getString("ref")]?.notification?.actions
                ?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true
            val appName = o.getString("app")
            val title = o.getString("title")
            val body = o.getString("text")
            val ref = o.getString("ref")
            val whenStr = fmt.format(Date(o.getLong("t")))
            out.add("[$appName] $title: $body ($whenStr) ref=$ref" + (if (canReply) " [can reply]" else ""))
        }
        return if (out.isEmpty()) "(no notifications in that period)" else out.takeLast(150).joinToString("\n")
    }

    /** Inline reply through the notification's own reply action (WhatsApp, Telegram and others). */
    fun reply(ctx: Context, ref: String, text: String): String {
        val sbn = NotifListener.live[ref] ?: return "ERROR: that notification is gone. Use screen control instead."
        val acts = sbn.notification.actions ?: return "ERROR: this notification has no inline reply."
        for (a in acts) {
            val ri = a.remoteInputs ?: continue
            if (ri.isEmpty()) continue
            val b = Bundle()
            for (r in ri) b.putCharSequence(r.resultKey, text)
            val i = Intent()
            RemoteInput.addResultsToIntent(ri, i, b)
            a.actionIntent.send(ctx, 0, i)
            return "reply sent"
        }
        return "ERROR: this notification has no inline reply."
    }
}
