package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Tools {
    private const val NOSVC = "ERROR: screen control not connected. Enable Iris in Accessibility."

    fun run(ctx: Context, name: String, args: JSONObject, screen: Boolean = false): String {
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
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, args.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, args.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("message", "Iris"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                "alarm set"
            }
            "list_uploads" -> {
                val a = Media.list(ctx)
                if (a.length() == 0) "(no uploads yet — ask the user to attach a file or take a screenshot)"
                else (0 until a.length()).joinToString("\n") { i ->
                    val o = a.getJSONObject(i)
                    "${o.optString("id")}  ${o.optString("kind")}  ${o.optString("name")}  (${o.optString("added")}, ${o.optLong("bytes")} bytes)"
                }
            }
            "analyze_upload" -> analyzeUpload(
                ctx,
                args.getString("id"),
                args.optString("question", "Describe this and extract any useful information.")
            )
            "delete_upload" -> if (Media.delete(ctx, args.getString("id"))) "deleted" else "ERROR: unknown id"
            "screenshot" -> {
                val svc = AgentAccessibilityService.instance ?: return NOSVC
                val id = svc.captureToLibrary(ctx)
                if (id.startsWith("ERROR")) id else "saved screenshot as upload id=$id"
            }
            "speak" -> {
                val text = args.optString("text")
                Voice.say(ctx, text)
                "speaking"
            }
            else -> "ERROR: unknown tool $name"
        }
    }

    /** Schema list for the model (subset focused on media + basics). */
    fun definitions(): JSONArray {
        fun t(name: String, desc: String, props: JSONObject = JSONObject(), required: JSONArray = JSONArray()) =
            JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", required))
        return JSONArray()
            .put(t("list_uploads", "List images and documents the user uploaded or screenshots."))
            .put(t("analyze_upload", "Analyse an uploaded image or text file by id.",
                JSONObject().put("id", JSONObject().put("type", "string")).put("question", JSONObject().put("type", "string")),
                JSONArray().put("id")))
            .put(t("delete_upload", "Remove an upload by id.",
                JSONObject().put("id", JSONObject().put("type", "string")), JSONArray().put("id")))
            .put(t("screenshot", "Capture the screen into the upload library (needs Accessibility, Android 11+)."))
            .put(t("notify", "Show a notification.",
                JSONObject().put("title", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string"))))
            .put(t("open_url", "Open a URL.",
                JSONObject().put("url", JSONObject().put("type", "string")), JSONArray().put("url")))
            .put(t("speak", "Speak text aloud.",
                JSONObject().put("text", JSONObject().put("type", "string")), JSONArray().put("text")))
    }

    private fun analyzeUpload(ctx: Context, id: String, question: String): String {
        val meta = Media.get(ctx, id) ?: return "ERROR: unknown upload id"
        val kind = meta.optString("kind")
        val key = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getString("api_key", "") ?: ""
        val model = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getString("model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        if (key.isBlank()) return "ERROR: no API key"
        return when (kind) {
            "text" -> {
                val body = Media.textContent(ctx, id) ?: return "ERROR: could not read file"
                geminiText(key, model, "File: ${meta.optString("name")}\nQuestion: $question\n\nContent:\n${body.take(40000)}")
            }
            "image" -> {
                val b64 = Media.imageBase64(ctx, id) ?: return "ERROR: could not decode image"
                geminiVision(key, model, b64, "image/jpeg", question.ifBlank { "Describe this image and extract any text or useful data." })
            }
            else -> "ERROR: kind '$kind' not analysed automatically; file is stored as id=$id"
        }
    }

    private fun geminiText(key: String, model: String, prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", prompt))))).toString()
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 30000; c.readTimeout = 90000
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) return "ERROR: Gemini HTTP $code ${resp.take(200)}"
        return JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: "ERROR: empty model reply"
    }

    private fun geminiVision(key: String, model: String, b64: String, mime: String, question: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val parts = JSONArray()
            .put(JSONObject().put("text", question))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).toString()
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 30000; c.readTimeout = 120000
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) return "ERROR: Gemini HTTP $code ${resp.take(200)}"
        return JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: "ERROR: empty model reply"
    }
}
