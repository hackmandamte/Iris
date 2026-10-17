package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Tools {
    private const val S = "STRING"
    private const val N = "NUMBER"
    private const val B = "BOOLEAN"
    private const val NOSVC = "ERROR: screen control not connected. Ask the user to enable Iris in Settings > Accessibility."

    private class T(
        val name: String, val desc: String,
        val params: Map<String, String> = emptyMap(),
        val optional: Set<String> = emptySet(),
        val screen: Boolean = false
    )

    private val all = listOf(
        T("open_app", "Launch an installed app by name or package.", mapOf("name" to S)),
        T("open_url", "Open a URL in the browser.", mapOf("url" to S)),
        T("web_fetch", "Fetch a web page as plain text.", mapOf("url" to S)),
        T("notify", "Show a notification.", mapOf("title" to S, "text" to S)),
        T("speak", "Speak text aloud (respects mute).", mapOf("text" to S)),
        T("set_speak", "Turn spoken replies on or off (mute reporting). on=true enables TTS, on=false mutes audio while everything else continues.", mapOf("on" to B)),
        T("battery", "Battery level and charging state."),
        T("clipboard_get", "Read the clipboard (Android may block this in background)."),
        T("clipboard_set", "Copy text to the clipboard.", mapOf("text" to S)),
        T("torch", "Flashlight on or off.", mapOf("on" to B)),
        T("set_alarm", "Set an alarm (24h time).", mapOf("hour" to N, "minute" to N, "label" to S), setOf("label")),
        T("set_timer", "Start a countdown timer.", mapOf("seconds" to N, "label" to S), setOf("label")),
        T("compose_sms", "Open the SMS composer with a draft. It is not sent until send is tapped.", mapOf("number" to S, "text" to S)),
        T("dial", "Open the dialer with a number (does not call).", mapOf("number" to S)),
        T("read_file", "Read a text file from the agent's private storage.", mapOf("path" to S)),
        T("write_file", "Write a text file in the agent's private storage.", mapOf("path" to S, "content" to S)),
        T("list_files", "List files in a directory under the agent's private storage.", mapOf("path" to S), setOf("path")),
        T("list_uploads", "List images and documents the user uploaded or screenshots saved in the library."),
        T("analyze_upload", "Analyse an uploaded image or text document by id (from list_uploads). For images, describe content and answer the question. For text files, extract or summarise.", mapOf("id" to S, "question" to S), setOf("question")),
        T("delete_upload", "Remove an upload by id.", mapOf("id" to S)),
        T("screenshot", "Capture the current screen into the upload library (needs Accessibility on API 30+). Returns the new upload id.", screen = true),
        T("now", "Current local date, time and weekday."),
        T("schedule_post", "Schedule a post for later. platform: instagram, x, telegram or whatsapp. time: local yyyy-MM-dd HH:mm. Only call after the user approved the exact text.", mapOf("platform" to S, "time" to S, "text" to S, "media" to S), setOf("media")),
        T("list_notifications", "Recent notifications. app and hours are optional filters.", mapOf("app" to S, "hours" to N), setOf("app", "hours")),
        T("reply_notification", "Send an inline reply to a notification by its ref. Only after the user approved the exact reply text.", mapOf("ref" to S, "text" to S)),
        T("screen_read", "List visible UI elements with text, flags and tap coordinates.", screen = true),
        T("tap_text", "Tap the element containing this text.", mapOf("text" to S), screen = true),
        T("tap_xy", "Tap screen coordinates.", mapOf("x" to N, "y" to N), screen = true),
        T("type_text", "Type into the focused text field (tap it first).", mapOf("text" to S, "append" to B), setOf("append"), screen = true),
        T("scroll", "Scroll: 'down' reveals content below, 'up' above, 'left'/'right' sideways.", mapOf("direction" to S), screen = true),
        T("press", "Press a system key: back, home, recents, notifications, quick_settings.", mapOf("key" to S), screen = true),
        T("run_shell", "Run a shell command with normal app permissions (no root). Working directory is the agent private storage. Timeout seconds optional (default 15, max 60).", mapOf("command" to S, "timeout" to N), setOf("timeout")),
        T("save_script", "Save a reusable shell script under scripts/ in agent storage. name is basename only (e.g. backup.sh).", mapOf("name" to S, "content" to S)),
        T("list_scripts", "List saved scripts under scripts/."),
        T("run_script", "Run a previously saved script by name (basename). Optional args string appended after the script path.", mapOf("name" to S, "args" to S), setOf("args")),
        T("memory_set", "Store a durable fact about the user or environment under a short key (e.g. name, timezone, preference).", mapOf("key" to S, "value" to S)),
        T("memory_get", "Read a stored memory by key.", mapOf("key" to S)),
        T("memory_list", "List all stored memory keys and values."),
        T("memory_delete", "Delete a memory entry by key.", mapOf("key" to S))
    )

    private val settle = setOf("open_app", "open_url", "tap_text", "tap_xy", "type_text", "scroll",
        "press", "dial", "compose_sms", "set_alarm", "set_timer")

    fun declarations(screenOn: Boolean): JSONArray {
        val arr = JSONArray()
        for (t in all) {
            if (t.screen && !screenOn) continue
            val props = JSONObject()
            val required = JSONArray()
            for ((k, v) in t.params) {
                props.put(k, JSONObject().put("type", when (v) {
                    N -> "number"
                    B -> "boolean"
                    else -> "string"
                }))
                if (k !in t.optional) required.put(k)
            }
            arr.put(JSONObject()
                .put("name", t.name)
                .put("description", t.desc)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", required)))
        }
        return arr
    }

    fun run(ctx: Context, name: String, args: JSONObject, screen: Boolean = false): String {
        return try {
            when (name) {
                "open_app" -> openApp(ctx, args.getString("name"))
                "open_url" -> {
                    val url = args.getString("url")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "opened $url"
                }
                "web_fetch" -> {
                    val url = args.getString("url")
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 20000
                    c.setRequestProperty("User-Agent", "IrisAgent/1.0")
                    val code = c.responseCode
                    val body = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
                    if (code !in 200..299) "ERROR: HTTP $code" else body.take(12000)
                }
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
                "speak" -> {
                    val speakOn = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getBoolean("speak", true)
                    if (!speakOn) "muted (speak is off)"
                    else {
                        Voice.say(ctx, args.getString("text"))
                        "speaking"
                    }
                }
                "set_speak" -> {
                    val on = args.getBoolean("on")
                    ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().putBoolean("speak", on).apply()
                    if (on) "speaking enabled" else "muted — no audio, all other functions still work"
                }
                "battery" -> {
                    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    "${(level * 100 / scale)}% ${if (charging) "charging" else "not charging"}"
                }
                "clipboard_get" -> {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "(empty)"
                }
                "clipboard_set" -> {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("iris", args.getString("text")))
                    "copied"
                }
                "torch" -> {
                    val on = args.getBoolean("on")
                    val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cam.cameraIdList.firstOrNull {
                        cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return "ERROR: no flash"
                    cam.setTorchMode(id, on)
                    if (on) "on" else "off"
                }
                "set_alarm" -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, args.getInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, args.getInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Iris"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    "alarm set"
                }
                "set_timer" -> {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, args.getInt("seconds"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Iris"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    "timer set"
                }
                "compose_sms" -> {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${args.getString("number")}"))
                        .putExtra("sms_body", args.getString("text"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    "composer opened"
                }
                "dial" -> {
                    val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${args.getString("number")}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    "dialer opened"
                }
                "read_file" -> {
                    val f = safe(ctx, args.getString("path"))
                    if (!f.exists()) "ERROR: not found" else f.readText().take(20000)
                }
                "write_file" -> {
                    val f = safe(ctx, args.getString("path"))
                    f.parentFile?.mkdirs()
                    f.writeText(args.getString("content"))
                    "wrote ${f.length()} bytes"
                }
                "list_files" -> {
                    val dir = safe(ctx, args.optString("path", "."))
                    if (!dir.isDirectory) "ERROR: not a directory"
                    else dir.listFiles()?.joinToString("\n") { "${if (it.isDirectory) "d" else "f"} ${it.name} ${it.length()}" } ?: "(empty)"
                }
                "list_uploads" -> {
                    val a = Media.list(ctx)
                    if (a.length() == 0) "(no uploads yet — ask the user to attach a file or take a screenshot)"
                    else (0 until a.length()).joinToString("\n") { i ->
                        val o = a.getJSONObject(i)
                        "${o.optString("id")}  ${o.optString("kind")}  ${o.optString("name")}  (${o.optString("added")}, ${o.optLong("bytes")} bytes)"
                    }
                }
                "analyze_upload" -> analyzeUpload(ctx, args.getString("id"), args.optString("question", "Describe this and extract any useful information."))
                "delete_upload" -> if (Media.delete(ctx, args.getString("id"))) "deleted" else "ERROR: unknown id"
                "screenshot" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    val id = svc.captureToLibrary(ctx)
                    if (id.startsWith("ERROR")) id else "saved screenshot as upload id=$id"
                }
                "now" -> SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE), zzzz", Locale.US).format(Date())
                "schedule_post" -> Posts.schedule(ctx, args.getString("platform"), args.getString("time"), args.getString("text"), args.optString("media", ""))
                "list_notifications" -> {
                    try { NotifLog.read(ctx, args.optString("app", ""), args.optInt("hours", 24)) }
                    catch (_: Exception) { "(notification access not fully wired — enable notification access)" }
                }
                "reply_notification" -> {
                    try { NotifLog.reply(ctx, args.getString("ref"), args.getString("text")) }
                    catch (_: Exception) { "ERROR: reply requires notification listener service" }
                }
                "screen_read" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    svc.readScreen()
                }
                "tap_text" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    svc.tapText(args.getString("text"))
                }
                "tap_xy" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    svc.tapXY(args.getDouble("x").toFloat(), args.getDouble("y").toFloat())
                }
                "type_text" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    svc.typeText(args.getString("text"), args.optBoolean("append", false))
                }
                "scroll" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    svc.scroll(args.getString("direction"))
                }
                "press" -> {
                    val svc = AgentAccessibilityService.instance ?: return NOSVC
                    svc.press(args.getString("key"))
                }
                "run_shell" -> {
                    val cmd = args.getString("command")
                    val timeout = args.optInt("timeout", 15).coerceIn(1, 60)
                    runShell(ctx, cmd, timeout)
                }
                "save_script" -> {
                    val name = args.getString("name").trim().replace("..", "").replace("/", "").replace("\\", "")
                    if (name.isEmpty() || !name.matches(Regex("[A-Za-z0-9._-]+"))) return@run "ERROR: invalid script name"
                    val dir = File(ctx.filesDir, "scripts")
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.writeText(args.getString("content"))
                    f.setExecutable(true)
                    "saved scripts/$name (${f.length()} bytes)"
                }
                "list_scripts" -> {
                    val dir = File(ctx.filesDir, "scripts")
                    if (!dir.isDirectory) "(no scripts yet)"
                    else dir.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
                        "${it.name}  ${it.length()} bytes"
                    } ?: "(empty)"
                }
                "run_script" -> {
                    val name = args.getString("name").trim().replace("..", "").replace("/", "").replace("\\", "")
                    val f = File(File(ctx.filesDir, "scripts"), name)
                    if (!f.isFile) return@run "ERROR: script not found: $name"
                    val extra = args.optString("args", "").trim()
                    val cmd = if (extra.isEmpty()) "sh \"${f.absolutePath}\"" else "sh \"${f.absolutePath}\" $extra"
                    runShell(ctx, cmd, 30)
                }
                "memory_set" -> {
                    val key = normalizeMemKey(args.getString("key"))
                    if (key == null) return@run "ERROR: invalid key (use short alphanumeric/underscore)"
                    val value = args.getString("value").take(2000)
                    val mem = loadMemory(ctx)
                    mem.put(key, value)
                    saveMemory(ctx, mem)
                    "remembered $key"
                }
                "memory_get" -> {
                    val key = normalizeMemKey(args.getString("key"))
                    if (key == null) return@run "ERROR: invalid key"
                    val mem = loadMemory(ctx)
                    if (!mem.has(key)) "ERROR: unknown key" else mem.getString(key)
                }
                "memory_list" -> {
                    val mem = loadMemory(ctx)
                    if (mem.length() == 0) "(empty)"
                    else mem.keys().asSequence().sorted().joinToString("\n") { k -> "$k = ${mem.optString(k)}" }
                }
                "memory_delete" -> {
                    val key = normalizeMemKey(args.getString("key"))
                    if (key == null) return@run "ERROR: invalid key"
                    val mem = loadMemory(ctx)
                    if (!mem.has(key)) return@run "ERROR: unknown key"
                    mem.remove(key)
                    saveMemory(ctx, mem)
                    "deleted $key"
                }
                else -> "ERROR: unknown tool $name"
            }.also {
                if (name in settle) Thread.sleep(600)
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(name)
            ?: pm.getInstalledApplications(0).firstOrNull {
                pm.getApplicationLabel(it).toString().equals(name, ignoreCase = true)
            }?.let { pm.getLaunchIntentForPackage(it.packageName) }
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            "opened $name"
        } else "ERROR: app not found: $name"
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
                geminiVision(key, model, b64, "image/jpeg", question.ifBlank { "Describe this image in detail and extract any text or useful data." })
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
        val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        return parts?.optJSONObject(0)?.optString("text") ?: "ERROR: empty model reply"
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
        val p = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        return p?.optJSONObject(0)?.optString("text") ?: "ERROR: empty model reply"
    }

    private fun runShell(ctx: Context, command: String, timeoutSec: Int): String {
        if (command.isBlank()) return "ERROR: empty command"
        val lower = command.lowercase(Locale.US)
        val blocked = listOf("su ", "su\n", "magisk", "pm uninstall", "rm -rf /", "mkfs", "dd if=")
        for (b in blocked) {
            if (lower.contains(b)) return "ERROR: command blocked for safety (needs explicit user approval path): $b"
        }
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.directory(ctx.filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["HOME"] = ctx.filesDir.absolutePath
            env["TMPDIR"] = ctx.cacheDir.absolutePath
            val proc = pb.start()
            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "ERROR: timed out after ${timeoutSec}s"
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }.take(12000)
            val code = proc.exitValue()
            if (out.isBlank()) "exit $code (no output)" else "exit $code\n$out"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun normalizeMemKey(raw: String): String? {
        val k = raw.trim().lowercase(Locale.US)
        if (k.isEmpty() || k.length > 64) return null
        if (!k.matches(Regex("[a-z0-9_./-]+"))) return null
        return k
    }

    private fun memFile(ctx: Context) = File(ctx.filesDir, "memory.json")

    private fun loadMemory(ctx: Context): JSONObject {
        val f = memFile(ctx)
        if (!f.isFile) return JSONObject()
        return try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun saveMemory(ctx: Context, mem: JSONObject) {
        memFile(ctx).writeText(mem.toString(2))
    }

    private fun safe(ctx: Context, path: String): File {
        val base = ctx.filesDir.canonicalFile
        val f = File(base, path).canonicalFile
        require(f.path.startsWith(base.path)) { "path outside agent storage" }
        return f
    }
}
