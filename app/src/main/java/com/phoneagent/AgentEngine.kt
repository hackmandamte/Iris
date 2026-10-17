package com.phoneagent

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the face on the island is doing. */
enum class Mood { IDLE, THINKING, ACTING, READING, SPEAKING, REPORT, ERROR }

/** One expression update: a mood plus optional text shown beside the face. */
data class Expr(val mood: Mood, val text: String = "")

/** One job. Each task has its own conversation and runs on its own thread. */
class Task(
    val id: Int,
    var title: String,
    val quiet: Boolean = false,
    val notifyTitle: String? = null,
    val priority: Boolean = false,
    val postId: String? = null,
    val summarize: Boolean = false
) {
    val history = JSONArray()
    var remote: PcDevice? = null
    @Volatile var remoteId = 0
    var display: String? = null
    @Volatile var cancelled = false
    @Volatile var state = "queued"
    val active: Boolean get() = state == "queued" || state == "working" || state == "needs screen" || state == "needs approval"
}

/**
 * The phone has one screen, so tasks take turns using it.
 */
object ScreenLease {
    private var owner = -1
    private val waiting = ArrayList<Task>()

    @Synchronized private fun tryTake(t: Task): Boolean {
        if (owner == t.id) return true
        if (!waiting.contains(t)) waiting.add(t)
        if (owner == -1) {
            val next = waiting.sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.id }).first()
            if (next.id == t.id) { owner = t.id; waiting.remove(t); return true }
        }
        return false
    }

    @Synchronized private fun leave(t: Task) { waiting.remove(t) }
    @Synchronized fun holder(): Int = owner
    @Synchronized fun release(t: Task) { if (owner == t.id) owner = -1; waiting.remove(t) }

    fun acquire(t: Task, timeoutMs: Long): Boolean {
        val end = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
        while (true) {
            if (t.cancelled) { leave(t); return false }
            if (tryTake(t)) return true
            if (System.currentTimeMillis() > end) { leave(t); return false }
            Thread.sleep(300)
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentEngine.app = this
    }
}

data class PcApproval(val id: String, val title: String, val detail: String)

object AgentEngine {
    const val MAX_STEPS = 1_000_000_000_000L
    private const val MAX_PARALLEL = 5
    private const val MAX_MODEL_CALLS = 3

    private val UI_TOOLS = setOf("screen_read", "tap_text", "tap_xy", "type_text", "scroll", "press",
        "open_app", "open_url", "dial", "compose_sms")

    lateinit var app: Context
    val tasks = CopyOnWriteArrayList<Task>()
    val approvals = CopyOnWriteArrayList<PcApproval>()
    val entries = ArrayList<Pair<Int, String>>()
    val logListeners = CopyOnWriteArraySet<(Int, String) -> Unit>()
    val stateListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    val taskListeners = CopyOnWriteArraySet<() -> Unit>()
    val exprListeners = CopyOnWriteArraySet<(Expr) -> Unit>()

    private val slots = Semaphore(MAX_PARALLEL, true)
    private val modelCalls = Semaphore(MAX_MODEL_CALLS, true)
    private var nextId = 1

    val running: Boolean get() = tasks.any { it.active }
    fun task(id: Int): Task? = tasks.firstOrNull { it.id == id }

    private fun prefs() = app.getSharedPreferences("agent", Context.MODE_PRIVATE)

    private fun emit(id: Int, s: String) {
        logListeners.forEach { it(id, s) }
        synchronized(entries) {
            entries.add(id to s)
            while (entries.size > 200) entries.removeAt(0)
        }
    }

    private fun express(t: Task, e: Expr) {
        if (!t.quiet) exprListeners.forEach { it(e) }
    }

    private fun notifyState() {
        stateListeners.forEach { it(running) }
        taskListeners.forEach { it() }
    }

    private fun newTask(title: String, quiet: Boolean = false, notify: String? = null, priority: Boolean = false, postId: String? = null, summarize: Boolean = false): Task {
        val t = Task(nextId++, title.take(40), quiet, notify, priority, postId, summarize)
        tasks.add(0, t)
        notifyState()
        return t
    }

    fun send(text: String) {
        val t = newTask(text.take(40))
        launch(t, text, false)
    }

    fun reply(id: Int, text: String) {
        val t = task(id) ?: return
        emit(id, "You: $text")
        // continue same task history
        t.history.put(JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))
        launchContinue(t)
    }

    fun stop(id: Int) {
        task(id)?.let {
            it.cancelled = true
            it.state = "stopped"
            emit(id, "Stopped")
            ScreenLease.release(it)
            notifyState()
        }
    }

    fun stopAll() {
        tasks.forEach {
            if (it.active) {
                it.cancelled = true
                it.state = "stopped"
                ScreenLease.release(it)
            }
        }
        notifyState()
    }

    fun summarizeScreen() {
        val t = newTask("Screen summary", summarize = true)
        launch(t, "Summarize what is currently on the screen. Use screen_read first.", true)
    }

    fun dailySummary() {
        val t = newTask("Daily summary", quiet = true, notify = "Daily summary", priority = true)
        launch(t, "Give a brief daily summary of recent notifications and activity.", false)
    }

    fun runScheduledPost(platform: String, text: String, id: String) {
        val t = newTask("Post: $platform", quiet = true, notify = "Scheduled post", priority = true, postId = id)
        launch(t, "Post the following on $platform exactly as written:\n\n$text", true)
    }

    fun runScheduledPost(id: String) {
        val o = Posts.get(app, id) ?: return
        runScheduledPost(o.optString("platform"), o.optString("text"), id)
    }

    private fun startFgs() {
        try { app.startForegroundService(Intent(app, OverlayService::class.java)) } catch (e: Exception) { }
    }

    private fun launch(t: Task, text: String, needsUnlock: Boolean) {
        startFgs()
        thread(name = "iris-task-${t.id}") {
            try {
                slots.acquire()
                if (t.cancelled) return@thread
                t.state = "working"
                notifyState()
                express(t, Expr(Mood.THINKING, t.title))
                emit(t.id, "You: ${t.display ?: text}")

                if (needsUnlock) {
                    t.state = "needs screen"
                    notifyState()
                    wakeAndUnlock()
                    var waited = 0
                    while (isLocked() && waited < 15000 && !t.cancelled) {
                        Thread.sleep(500)
                        waited += 500
                    }
                    if (t.cancelled) return@thread
                    t.state = "working"
                    notifyState()
                }

                val key = prefs().getString("api_key", "") ?: ""
                if (key.isBlank()) {
                    emit(t.id, "⚠ Paste a Gemini API key in Settings first.")
                    t.state = "error"
                    express(t, Expr(Mood.ERROR, "No API key"))
                    notifyState()
                    return@thread
                }

                t.history.put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text))))

                runLoop(t, key)
            } catch (e: Exception) {
                if (!t.cancelled) {
                    emit(t.id, "⚠ ${e.message ?: "error"}")
                    t.state = "error"
                    express(t, Expr(Mood.ERROR, e.message ?: "error"))
                }
            } finally {
                ScreenLease.release(t)
                slots.release()
                if (t.state == "working" || t.state == "needs screen") t.state = "done"
                notifyState()
            }
        }
    }

    private fun launchContinue(t: Task) {
        startFgs()
        thread(name = "iris-task-${t.id}-cont") {
            try {
                slots.acquire()
                if (t.cancelled) return@thread
                t.state = "working"
                notifyState()
                val key = prefs().getString("api_key", "") ?: ""
                if (key.isBlank()) {
                    emit(t.id, "⚠ No API key")
                    t.state = "error"
                    notifyState()
                    return@thread
                }
                runLoop(t, key)
            } catch (e: Exception) {
                if (!t.cancelled) {
                    emit(t.id, "⚠ ${e.message ?: "error"}")
                    t.state = "error"
                    express(t, Expr(Mood.ERROR, e.message ?: "error"))
                }
            } finally {
                ScreenLease.release(t)
                slots.release()
                if (t.state == "working" || t.state == "needs screen") t.state = "done"
                notifyState()
            }
        }
    }

    private fun isLocked(): Boolean {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    private fun wakeAndUnlock() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "iris:wake"
            )
            wl.acquire(3000)
            wl.release()
        } catch (_: Exception) { }
    }

    private val SYSTEM = """
You are Iris, an autonomous agent on the user's Android phone.
Reach the user's goal by calling tools step by step. Check each result before the next step.
When you have the final answer or the task is done, reply with plain text (no more tool calls).
When screen control tools are available: call screen_read to see the screen, act (tap_text, tap_xy, type_text, scroll, press), then screen_read again to verify. Tap a text field before typing into it.
Be concise in tool args. Prefer tap_text over coordinates when the label is visible.
Do not invent tool results. If a tool fails, adapt or ask the user.
Social: draft captions/replies in plain text; only schedule or send after the user approves the exact wording.
""".trim()

    private fun runLoop(t: Task, key: String) {
        val p = prefs()
        val model = p.getString("model", "")!!.trim().ifEmpty { "gemini-2.5-flash" }
        var step = 0L
        var screen = AgentAccessibilityService.instance != null

        while (step < MAX_STEPS && !t.cancelled) {
            step++
            express(t, Expr(Mood.THINKING))
            trimHistory(t.history)

            val resp = callGemini(t, key, model, screen)
            val candidates = resp.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                emit(t.id, "⚠ empty model response")
                t.state = "error"
                express(t, Expr(Mood.ERROR, "empty response"))
                return
            }
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: JSONObject()
            val parts = content.optJSONArray("parts") ?: JSONArray()

            val calls = ArrayList<JSONObject>()
            var textOut = ""
            for (i in 0 until parts.length()) {
                val pt = parts.getJSONObject(i)
                pt.optJSONObject("functionCall")?.let { calls.add(it) }
                if (pt.has("text")) textOut += pt.optString("text")
            }

            t.history.put(JSONObject().put("role", "model").put("parts", parts))

            if (calls.isEmpty()) {
                val reply = textOut.ifBlank { "(done)" }
                emit(t.id, "🤖 $reply")
                express(t, Expr(Mood.REPORT, reply.take(80)))
                if (p.getBoolean("speak", false) && !t.quiet) Voice.say(app, reply)
                t.state = "done"
                t.postId?.let { Posts.setStatus(app, it, "done") }
                return
            }

            val results = JSONArray()
            for (fc in calls) {
                if (t.cancelled) break
                val name = fc.optString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                val needsScreen = name in UI_TOOLS || name == "screenshot"
                if (needsScreen) {
                    t.state = "needs screen"
                    notifyState()
                    if (!ScreenLease.acquire(t, 60_000)) {
                        results.put(JSONObject().put("functionResponse", JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", "ERROR: could not acquire screen"))))
                        continue
                    }
                    t.state = "working"
                    notifyState()
                    screen = true
                }
                express(t, toolExpr(name, args))
                emit(t.id, "→ $name ${args.toString().take(120)}")
                val out = try {
                    Tools.run(app, name, args, screen)
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }
                emit(t.id, "← ${out.take(300)}")
                results.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", out.take(8000)))))
            }
            t.history.put(JSONObject().put("role", "user").put("parts", results))
        }
        if (!t.cancelled) {
            emit(t.id, "⚠ step limit")
            t.state = "error"
            express(t, Expr(Mood.ERROR, "step limit"))
        }
    }

    private fun toolExpr(name: String, a: JSONObject): Expr = when (name) {
        "screen_read" -> Expr(Mood.READING, "reading screen")
        "tap_text", "tap_xy", "type_text", "scroll", "press" -> Expr(Mood.ACTING, name)
        "speak" -> Expr(Mood.SPEAKING, a.optString("text").take(40))
        "analyze_upload", "web_fetch", "list_uploads" -> Expr(Mood.READING, name)
        "screenshot" -> Expr(Mood.ACTING, "screenshot")
        else -> Expr(Mood.ACTING, name)
    }

    private fun trimHistory(history: JSONArray) {
        while (history.length() > 40) {
            var cut = 0
            for (i in 1 until history.length()) {
                val m = history.optJSONObject(i) ?: continue
                val first = m.optJSONArray("parts")?.optJSONObject(0)
                if (m.optString("role") == "user" && first != null && first.has("text")) { cut = i; break }
            }
            if (cut > 0) {
                for (k in 0 until cut) history.remove(0)
            } else if (history.length() > 80) {
                history.remove(1); history.remove(1)
            } else break
        }
    }

    private fun callGemini(t: Task, key: String, model: String, screen: Boolean): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", t.history)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations(screen))))
            .toString().toByteArray()
        var got = false
        while (!got) {
            if (t.cancelled) throw RuntimeException("cancelled")
            got = modelCalls.tryAcquire(500, TimeUnit.MILLISECONDS)
        }
        try {
            var lastErr = "unknown error"
            for (attempt in 0 until 4) {
                if (t.cancelled) throw RuntimeException("cancelled")
                try {
                    val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                        .openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 30000
                    c.readTimeout = 120000
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("x-goog-api-key", key)
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val text = stream.bufferedReader().use { it.readText() }
                    if (code in 200..299) return JSONObject(text)
                    lastErr = "HTTP $code: ${text.take(300)}"
                    if (code !in intArrayOf(429, 500, 503)) break
                } catch (e: java.io.IOException) {
                    lastErr = e.message ?: "network error"
                }
                Thread.sleep(2000L * (attempt + 1))
            }
            throw RuntimeException(lastErr)
        } finally {
            modelCalls.release()
        }
    }
}
