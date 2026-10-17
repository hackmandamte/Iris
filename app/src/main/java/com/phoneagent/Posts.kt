package com.phoneagent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Scheduled-post queue (stored locally) plus the alarms that fire it and the daily digest. */
object Posts {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("agent_posts", Context.MODE_PRIVATE)
    fun all(ctx: Context) = JSONArray(sp(ctx).getString("list", "[]"))
    private fun save(ctx: Context, a: JSONArray) { sp(ctx).edit().putString("list", a.toString()).apply() }

    fun name(p: String) = when (p.lowercase(Locale.US)) {
        "x", "twitter" -> "X"
        "instagram", "ig" -> "Instagram"
        "telegram", "tg" -> "Telegram"
        "whatsapp", "wa" -> "WhatsApp"
        else -> p
    }

    @Synchronized fun get(ctx: Context, id: String): JSONObject? {
        val a = all(ctx)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    @Synchronized fun setStatus(ctx: Context, id: String, st: String) {
        val a = all(ctx)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) o.put("status", st)
        }
        save(ctx, a)
    }

    @Synchronized fun cancel(ctx: Context, id: String): Boolean {
        val a = all(ctx)
        val next = JSONArray()
        var found = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) found = true else next.put(o)
        }
        if (found) {
            save(ctx, next)
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, id.hashCode(),
                Intent(ctx, AlarmReceiver::class.java).setAction("POST").putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
        return found
    }

    /**
     * Tool entry point used by Tools.schedule_post.
     * time: local "yyyy-MM-dd HH:mm"
     * Returns a short human status string (or ERROR: ...).
     */
    @Synchronized fun schedule(
        ctx: Context,
        platform: String,
        time: String,
        text: String,
        media: String = ""
    ): String {
        val plat = platform.trim().lowercase(Locale.US)
        if (plat !in setOf("x", "twitter", "instagram", "ig", "telegram", "tg", "whatsapp", "wa")) {
            return "ERROR: platform must be x, instagram, telegram or whatsapp"
        }
        if (text.isBlank()) return "ERROR: post text is empty"
        val whenMs = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(time.trim())!!.time
        } catch (_: Exception) {
            return "ERROR: time must be yyyy-MM-dd HH:mm (local)"
        }
        if (whenMs < System.currentTimeMillis() - 30_000L) {
            return "ERROR: time is in the past"
        }
        val id = UUID.randomUUID().toString().take(8)
        val a = all(ctx)
        a.put(
            JSONObject()
                .put("id", id)
                .put("platform", plat)
                .put("text", text)
                .put("at", whenMs)
                .put("when", whenMs) // legacy alias
                .put("status", "scheduled")
                .put("media", media)
        )
        save(ctx, a)
        armPost(ctx, id, whenMs)
        return "scheduled #$id on ${name(plat)} at ${formatWhen(whenMs)}"
    }

    /** Low-level alarm arm for an existing post id. */
    fun armPost(ctx: Context, id: String, whenMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction("POST").putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
    }

    /** @deprecated use armPost — kept so older call sites compile */
    fun schedule(ctx: Context, id: String, whenMs: Long) = armPost(ctx, id, whenMs)

    fun rearmAll(ctx: Context) {
        val a = all(ctx)
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val st = o.optString("status")
            val at = o.optLong("at", o.optLong("when", 0L))
            if (st in setOf("scheduled", "queued") && at > now) armPost(ctx, o.optString("id"), at)
        }
        armDigest(ctx)
    }

    /** Re-arm the daily digest alarm from the stored hour (or no-op if unset). */
    fun armDigest(ctx: Context) {
        val hour = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getInt("digest_hour", -1)
        if (hour in 0..23) scheduleDigest(ctx, hour)
    }

    fun scheduleDigest(ctx: Context, hour: Int) {
        ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).edit()
            .putInt("digest_hour", hour)
            .putBoolean("digest_on", true)
            .apply()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 99001,
            Intent(ctx, AlarmReceiver::class.java).setAction("DIGEST"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    fun clearDigest(ctx: Context) {
        ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).edit()
            .putBoolean("digest_on", false)
            .putInt("digest_hour", -1)
            .apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 99001,
            Intent(ctx, AlarmReceiver::class.java).setAction("DIGEST"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    fun formatWhen(ms: Long): String =
        SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))
}
