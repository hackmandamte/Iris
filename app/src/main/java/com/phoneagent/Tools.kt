package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONObject

object Tools {
    fun run(ctx: Context, name: String, args: JSONObject, screen: Boolean): String {
        return when (name) {
            "notify" -> {
                val title = args.optString("title", "Iris")
                val text = args.optString("text", "")
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("agent_msgs", "Agent messages", NotificationManager.IMPORTANCE_DEFAULT))
                nm.notify(
                    (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                    Notification.Builder(ctx, "agent_msgs")
                        .setSmallIcon(R.drawable.ic_stat_face)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(Notification.BigTextStyle().bigText(text))
                        .build()
                )
                "ok"
            }
            "open_url" -> {
                val url = args.optString("url")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "opened $url"
            }
            "set_alarm" -> {
                val hour = args.optInt("hour")
                val minute = args.optInt("minute")
                val message = args.optString("message", "Iris")
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                "alarm set"
            }
            else -> "ERROR: unknown tool $name"
        }
    }
}
