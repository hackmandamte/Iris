package com.phoneagent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Fires scheduled posts and the daily summary. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when {
            action == Intent.ACTION_BOOT_COMPLETED -> {
                // re-arm alarms after reboot
                Posts.rearm(ctx)
                try { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) } catch (e: Exception) { }
            }
            action.startsWith("iris.post.") -> {
                val id = action.removePrefix("iris.post.")
                Posts.fire(ctx, id)
            }
            action == "iris.digest" -> {
                AgentEngine.dailySummary()
            }
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
        // Store latest for the agent; kept short
        getSharedPreferences("agent", MODE_PRIVATE).edit()
            .putString("last_notif_pkg", pkg)
            .putString("last_notif_title", title.take(120))
            .putString("last_notif_text", text.take(400))
            .apply()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}

object Posts {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("agent_posts", Context.MODE_PRIVATE)

    fun list(ctx: Context): JSONArray {
        val raw = prefs(ctx).getString("queue", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun save(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString("queue", arr.toString()).apply()
    }

    fun add(ctx: Context, platform: String, text: String, whenMs: Long): String {
        val id = System.currentTimeMillis().toString(36)
        val arr = list(ctx)
        arr.put(JSONObject()
            .put("id", id)
            .put("platform", platform)
            .put("text", text)
            .put("when", whenMs))
        save(ctx, arr)
        schedule(ctx, id, whenMs)
        return id
    }

    fun remove(ctx: Context, id: String) {
        val arr = list(ctx)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) next.put(o)
        }
        save(ctx, next)
        cancel(ctx, id)
    }

    fun fire(ctx: Context, id: String) {
        val arr = list(ctx)
        var found: JSONObject? = null
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) found = o else next.put(o)
        }
        save(ctx, next)
        if (found != null) {
            AgentEngine.runScheduledPost(found.optString("platform"), found.optString("text"), id)
        }
    }

    fun rearm(ctx: Context) {
        val arr = list(ctx)
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val whenMs = o.optLong("when")
            if (whenMs > now) schedule(ctx, o.optString("id"), whenMs)
        }
        // daily digest
        val dig = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getLong("digest_hour", -1)
        if (dig >= 0) scheduleDigest(ctx, dig.toInt())
    }

    private fun schedule(ctx: Context, id: String, whenMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction("iris.post.$id"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 31) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    private fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction("iris.post.$id"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    fun scheduleDigest(ctx: Context, hour: Int) {
        ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().putLong("digest_hour", hour.toLong()).apply()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 99,
            Intent(ctx, AlarmReceiver::class.java).setAction("iris.digest"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 31) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }
}
