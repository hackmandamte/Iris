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

data class PcApproval(val id: String, val title: String, val detail: String)

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

    lateinit var app: Context
    val tasks = CopyOnWriteArrayList<Task>()
    val approvals = CopyOnWriteArrayList<PcApproval>()
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
    }

    private fun setExpr(e: Expr) {
        exprListeners.forEach { it(e) }
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
        launch(t, text, false)
    }

    fun stop(id: Int) {
        task(id)?.let {
            it.cancelled = true
            it.state = "stopped"
            emit(id, "Stopped")
            notifyState()
        }
    }

    fun stopAll() {
        tasks.forEach {
            if (it.active) {
                it.cancelled = true
                it.state = "stopped"
            }
        }
        notifyState()
    }

    fun summarizeScreen() {
        val t = newTask("Screen summary", summarize = true)
        launch(t, "Summarize what is currently on the screen.", true)
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
            t.state = "working"
            notifyState()
            setExpr(Expr(Mood.THINKING, t.title))
            emit(t.id, "You: $text")
            try {
                if (needsUnlock) {
                    t.state = "needs screen"
                    notifyState()
                    // brief wait for unlock – simplified
                    Thread.sleep(500)
                    t.state = "working"
                    notifyState()
                }
                val key = prefs().getString("api_key", "") ?: ""
                val model = prefs().getString("model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
                if (key.isBlank()) {
                    emit(t.id, "⚠ Paste a Gemini API key in Settings first.")
                    t.state = "error"
                    setExpr(Expr(Mood.ERROR, "No API key"))
                    notifyState()
                    return@thread
                }
                modelCalls.acquire()
                try {
                    setExpr(Expr(Mood.THINKING))
                    val reply = callGemini(key, model, text)
                    emit(t.id, "🤖 $reply")
                    setExpr(Expr(Mood.REPORT, reply.take(80)))
                    if (prefs().getBoolean("speak", false)) Voice.say(app, reply)
                    t.state = "done"
                } finally {
                    modelCalls.release()
                }
            } catch (e: Exception) {
                emit(t.id, "⚠ ${e.message ?: "error"}")
                t.state = "error"
                setExpr(Expr(Mood.ERROR, e.message ?: "error"))
            }
            notifyState()
        }
    }

    private fun callGemini(key: String, model: String, prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .toString()
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream.bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("Gemini HTTP $code: ${resp.take(200)}")
        val o = JSONObject(resp)
        val candidates = o.optJSONArray("candidates") ?: throw RuntimeException("No candidates")
        val content = candidates.getJSONObject(0).optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: throw RuntimeException("No parts")
        return parts.getJSONObject(0).optString("text", "(empty)")
    }
}
