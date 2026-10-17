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

enum class Mood { IDLE, THINKING, ACTING, READING, SPEAKING, REPORT, ERROR }
data class Expr(val mood: Mood, val text: String = "")

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

object AgentEngine {
    const val MAX_STEPS = 1_000_000_000_000L
    private const val MAX_PARALLEL = 5
    private const val MAX_MODEL_CALLS = 3
    private val UI_TOOLS = setOf("screen_read", "tap_text", "tap_xy", "type_text", "scroll", "press",
        "open_app", "open_url", "dial", "compose_sms", "screenshot")

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
    private var wasRunning = false

    val running: Boolean get() = tasks.any { it.active }
    fun task(id: Int): Task? = tasks.firstOrNull { it.id == id }

    private val SYSTEM = """You are Iris, an autonomous agent on the user's Android phone.
Reach the goal by calling tools step by step. Check each result before the next step.
When screen tools are available: screen_read, then act, then screen_read again.
Before irreversible actions, ask the user for confirmation.
Use list_uploads, analyze_upload, screenshot, schedule_post (after user approves exact text/platform/time).
Call now for the date before scheduling. Keep final answers short for TTS."""

    private fun prefs() = app.getSharedPreferences("agent", Context.MODE_PRIVATE)

    @Synchronized private fun changed() {
        taskListeners.forEach { it() }
        val r = running
        if (r != wasRunning) { wasRunning = r; stateListeners.forEach { it(r) } }
    }

    @Synchronized private fun newTask(
        title: String, quiet: Boolean = false, notify: String? = null,
        priority: Boolean = false, postId: String? = null, summarize: Boolean = false
    ): Task {
        val t = Task(nextId++, title.take(40), quiet, notify, priority, postId, summarize)
        tasks.add(0, t)
        while (tasks.size > 40) {
            val old = tasks.lastOrNull { !it.active } ?: break
            tasks.remove(old)
        }
        changed()
        return t
    }

    private fun emit(t: Task, s: String) {
        logListeners.forEach { it(t.id, s) }
        synchronized(entries) {
            entries.add(t.id to s)
            if (entries.size > 400) entries.removeAt(0)
        }
    }

    fun express(t: Task?, e: Expr) {
        if (t == null || !t.quiet) exprListeners.forEach { it(e) }
    }

    fun send(text: String, targetId: Int? = null): Int {
        val existing = targetId?.let { task(it) }
        val rdev = existing?.remote
        if (existing != null && rdev != null && !existing.active) {
            sendToPc(rdev, text, existing)
            return existing.id
        }
        val t = if (existing != null && !existing.active) existing else newTask(text)
        launch(t, text, false)
        return t.id
    }

    /** Continue an existing task (used by MainActivity reply flow). */
    fun reply(id: Int, text: String): Int = send(text, id)

    fun summarizeScreen() {
        if (!prefs().getBoolean("screen", false)) {
            val t = newTask("Screen summary")
            t.state = "error"
            emit(t, "⚠ Turn on Screen control first.")
            express(t, Expr(Mood.ERROR, "Turn on Screen control first"))
            changed()
            return
        }
        launch(newTask("Screen summary", summarize = true),
            "Summarize what is on my screen right now in 2 or 3 short sentences.", false)
    }

    fun stop(id: Int) { task(id)?.let { it.cancelled = true }; Voice.stop() }
    fun stopAll() { tasks.forEach { if (it.active) it.cancelled = true }; Voice.stop() }
    fun stop() = stopAll()
    fun remove(id: Int) { task(id)?.let { if (!it.active) tasks.remove(it) }; changed() }

    fun runDigest() {
        launch(newTask("Daily summary", quiet = true, notify = "Daily summary", priority = true),
            "Write my daily notification digest. Call list_notifications, group by app, mention anything that needs a reply, keep under 120 words.",
            false)
    }

    /** Alias used by older Social/MainActivity code. */
    fun dailySummary() = runDigest()

    fun runScheduledPost(id: String) {
        val post = Posts.get(app, id) ?: return
        if (post.optString("status") != "scheduled") return
        if (post.optLong("at") > System.currentTimeMillis() + 60_000L) return
        if (!prefs().getBoolean("screen", false)) {
            Posts.setStatus(app, id, "failed")
            notifyUser("Scheduled post not published", "Turn on Screen control in Iris.")
            return
        }
        Posts.setStatus(app, id, "running")
        val plat = Posts.name(post.getString("platform"))
        val media = post.optString("media").ifBlank { "" }
        val mediaHint = if (media.isNotEmpty()) " Use upload id $media as media." else ""
        val prompt = "This is a pre-approved scheduled post. Publish it on $plat exactly as written without asking.$mediaHint\n\n${post.getString("text")}"
        launch(newTask("Post: $plat", quiet = true, notify = "Scheduled post", priority = true, postId = id), prompt, true)
    }

    fun sendToPc(dev: PcDevice, text: String, existing: Task? = null): Int {
        val t = existing ?: newTask(text.take(40))
        t.remote = dev
        t.state = "working"
        changed()
        express(t, Expr(Mood.THINKING, "Working on ${dev.name}"))
        emit(t, "You: $text")
        thread(name = "pc-${t.id}") {
            try {
                val req = JSONObject().put("text", text)
                if (t.remoteId > 0) req.put("task", t.remoteId)
                val res = Pc.call(dev, "task", req)
                t.remoteId = res.optInt("task", t.remoteId)
                var since = 0
                while (!t.cancelled) {
                    val poll = Pc.call(dev, "poll", JSONObject().put("task", t.remoteId).put("since", since))
                    val lines = poll.optJSONArray("lines") ?: JSONArray()
                    for (i in 0 until lines.length()) {
                        val line = lines.getString(i)
                        since++
                        emit(t, line)
                        if (line.startsWith("🤖 ")) express(t, Expr(Mood.REPORT, "${dev.name}: " + line.substring(3).take(200)))
                        else if (line.startsWith("→ ")) express(t, Expr(Mood.ACTING, "${dev.name}: " + line.substring(2).substringBefore(' ')))
                    }
                    syncApprovals(t, dev, poll.optJSONArray("approvals"))
                    if (poll.optBoolean("done")) {
                        t.state = if (t.cancelled) "stopped" else "done"
                        changed()
                        break
                    }
                    Thread.sleep(1200)
                }
                if (t.cancelled) {
                    try { Pc.call(dev, "stop", JSONObject().put("task", t.remoteId)) } catch (_: Exception) { }
                    t.state = "stopped"
                    emit(t, "⏹ Stopped.")
                }
            } catch (e: Exception) {
                emit(t, "⚠ PC: ${e.message}")
                express(t, Expr(Mood.ERROR, "PC: ${e.message}".take(120)))
                t.state = "error"
            } finally {
                approvals.removeAll { it.taskId == t.id }
                changed()
            }
        }
        return t.id
    }

    private fun syncApprovals(t: Task, dev: PcDevice, arr: JSONArray?) {
        val ids = HashSet<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val id = a.getString("id")
                ids.add(id)
                if (approvals.none { it.id == id }) {
                    approvals.add(PcApproval(id, dev, t.id, a.optString("kind"), a.optString("text")))
                    changed()
                }
            }
        }
        if (approvals.removeAll { it.taskId == t.id && it.id !in ids }) changed()
    }

    fun answerApproval(a: PcApproval, allow: Boolean) {
        approvals.remove(a)
        changed()
        thread {
            try { Pc.call(a.dev, "approve", JSONObject().put("id", a.id).put("allow", allow)) } catch (_: Exception) { }
        }
    }

    fun handoffToPc(t: Task, dev: PcDevice) {
        thread(name = "pc-handoff") {
            try {
                val es = synchronized(entries) { entries.filter { it.first == t.id }.map { it.second } }
                val r = Pc.call(dev, "handoff", JSONObject().put("title", t.title).put("entries", JSONArray(es)))
                emit(t, if (r.optBoolean("ok")) "📤 Handed off to ${dev.name}. Open Iris on the PC and press Continue."
                else "⚠ Handoff refused: ${r.optString("error")}")
            } catch (e: Exception) {
                emit(t, "⚠ Handoff failed: ${e.message}")
            }
        }
    }

    fun startHandoff(title: String, from: String, entries: List<String>): Int {
        val ctx = entries.takeLast(40).joinToString("\n") { it.take(300) }
        val prompt = "Handed off from $from. Continue on this phone.\nTask: $title\nSo far:\n$ctx"
        val t = newTask("Handoff: $title")
        t.display = "Continue: $title (from $from)"
        launch(t, prompt, false)
        return t.id
    }

    private fun notifyUser(title: String, msg: String) {
        try { Tools.run(app, "notify", JSONObject().put("title", title).put("text", msg), false) } catch (_: Exception) { }
    }

    private fun startFgs() {
        try { app.startForegroundService(Intent(app, OverlayService::class.java)) } catch (_: Exception) { }
    }

    private fun maybeStopService() {
        if (!running && !prefs().getBoolean("bubble", false) && !prefs().getBoolean("quick_on", true)) {
            try { app.startService(Intent(app, OverlayService::class.java).setAction("STOP")) } catch (_: Exception) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "iris:screen")
                .acquire(5 * 60 * 1000L)
        } catch (_: Exception) { }
    }

    private fun waitUnlocked(t: Task): Boolean {
        wakeScreen()
        val kg = app.getSystemService(KeyguardManager::class.java)
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        var warned = false
        while (System.currentTimeMillis() < deadline && !t.cancelled) {
            if (!kg.isKeyguardLocked) return true
            if (!warned) { warned = true; notifyUser(t.notifyTitle ?: "Iris", "Unlock your phone to continue.") }
            Thread.sleep(3000)
        }
        return false
    }

    private fun launch(t: Task, text: String, needsUnlock: Boolean) {
        startFgs()
        thread(name = "iris-task-${t.id}") {
            var gotSlot = false
            var wl: PowerManager.WakeLock? = null
            try {
                t.state = "queued"
                changed()
                slots.acquire()
                gotSlot = true
                if (t.cancelled) return@thread
                if (needsUnlock) {
                    t.state = "needs screen"
                    changed()
                    if (!waitUnlocked(t)) {
                        t.state = "error"
                        t.postId?.let { Posts.setStatus(app, it, "failed") }
                        emit(t, "⚠ Phone stayed locked")
                        express(t, Expr(Mood.ERROR, "Phone stayed locked"))
                        return@thread
                    }
                }
                @Suppress("DEPRECATION")
                wl = app.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iris:task")
                wl?.acquire(30 * 60 * 1000L)
                t.state = "working"
                changed()
                emit(t, "You: ${t.display ?: text}")
                express(t, Expr(Mood.THINKING, t.title))
                loop(t, text)
                if (t.state == "working") t.state = if (t.cancelled) "stopped" else "done"
            } catch (e: Exception) {
                if (t.cancelled) {
                    t.state = "stopped"
                    emit(t, "⏹ Stopped.")
                    express(t, Expr(Mood.IDLE))
                } else {
                    t.state = "error"
                    emit(t, "⚠ ${e.message}")
                    express(t, Expr(Mood.ERROR, (e.message ?: "error").take(120)))
                }
            } finally {
                wl?.let { if (it.isHeld) it.release() }
                if (gotSlot) slots.release()
                ScreenLease.release(t)
                t.postId?.let { id ->
                    if (Posts.get(app, id)?.optString("status") == "running") Posts.setStatus(app, id, "unverified")
                }
                changed()
                maybeStopService()
            }
        }
    }

    private fun toolExpr(name: String, a: JSONObject): Expr = when (name) {
        "screen_read" -> Expr(Mood.READING, "Reading the screen")
        "web_fetch", "analyze_upload", "list_uploads" -> Expr(Mood.READING, name)
        "speak" -> Expr(Mood.SPEAKING, a.optString("text").take(120))
        "schedule_post" -> Expr(Mood.ACTING, "Scheduling post")
        else -> Expr(Mood.ACTING, name.replace('_', ' '))
    }

    private fun loop(t: Task, userText: String) {
        val p = prefs()
        val key = p.getString("api_key", "")!!.trim()
        if (key.isEmpty()) {
            emit(t, "⚠ Paste a Gemini API key in Settings first.")
            t.state = "error"
            express(t, Expr(Mood.ERROR, "No API key"))
            return
        }
        val model = p.getString("model", "")!!.trim().ifEmpty { "gemini-2.5-flash" }
        t.history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
        var step = 0L
        var screen = AgentAccessibilityService.instance != null && p.getBoolean("screen", false)

        while (step < MAX_STEPS && !t.cancelled) {
            step++
            express(t, Expr(Mood.THINKING))
            trimHistory(t.history)
            val resp = callGemini(t, key, model, screen)
            val candidates = resp.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                emit(t, "⚠ empty model response")
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
                emit(t, "🤖 $reply")
                if (p.getBoolean("speak", true) && !t.quiet) {
                    express(t, Expr(Mood.SPEAKING, reply.take(80)))
                    Voice.say(app, reply)
                } else express(t, Expr(Mood.REPORT, reply.take(80)))
                t.state = "done"
                t.postId?.let { Posts.setStatus(app, it, "done") }
                return
            }
            val results = JSONArray()
            for (fc in calls) {
                if (t.cancelled) break
                val name = fc.optString("name")
                val args = fc.optJSONObject("args") ?: JSONObject()
                if (name in UI_TOOLS) {
                    t.state = "needs screen"
                    changed()
                    if (!ScreenLease.acquire(t, 60_000)) {
                        results.put(JSONObject().put("functionResponse", JSONObject()
                            .put("name", name)
                            .put("response", JSONObject().put("result", "ERROR: could not acquire screen"))))
                        continue
                    }
                    t.state = "working"
                    changed()
                    screen = true
                }
                express(t, toolExpr(name, args))
                emit(t, "→ $name ${args.toString().take(120)}")
                val out = try { Tools.run(app, name, args, screen) } catch (e: Exception) { "ERROR: ${e.message}" }
                emit(t, "← ${out.take(300)}")
                results.put(JSONObject().put("functionResponse", JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", out.take(8000)))))
            }
            t.history.put(JSONObject().put("role", "user").put("parts", results))
        }
        if (!t.cancelled) {
            emit(t, "⚠ step limit")
            t.state = "error"
            express(t, Expr(Mood.ERROR, "step limit"))
        }
    }

    private fun trimHistory(history: JSONArray) {
        while (history.length() > 40) {
            var cut = 0
            for (i in 1 until history.length()) {
                val m = history.optJSONObject(i) ?: continue
                val first = m.optJSONArray("parts")?.optJSONObject(0)
                if (m.optString("role") == "user" && first != null && first.has("text")) { cut = i; break }
            }
            if (cut > 0) for (k in 0 until cut) history.remove(0)
            else if (history.length() > 80) { history.remove(1); history.remove(1) }
            else break
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
