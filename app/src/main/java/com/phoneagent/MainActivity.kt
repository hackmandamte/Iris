package com.phoneagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Grok-like chat shell:
 * Home: title, status (tap = stop), settings gear, conversation, bottom compose (+ attach).
 * Settings: full panel; compose hidden; on close, compose returns + focus.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_VOICE = 42
        private const val REQ_ATTACH = 43
    }

    private val ACCENT = 0xFF0A84FF.toInt()
    private val INK = 0xFF1C1C1E.toInt()
    private val MUTED = 0xFF8E8E93.toInt()
    private val LINE = 0xFFE5E5EA.toInt()
    private val SURFACE = 0xFFF2F2F7.toInt()
    private val RED = 0xFFC62828.toInt()
    private val RED_BG = 0xFFFDE8E8.toInt()
    private val PALETTE = intArrayOf(
        0xFF0A84FF.toInt(), 0xFF1B7F3B.toInt(), 0xFF8E44AD.toInt(),
        0xFFD35400.toInt(), 0xFF16A085.toInt(), 0xFFC0392B.toInt()
    )
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var composeBar: LinearLayout
    private lateinit var settingsPanel: ScrollView
    private lateinit var status: TextView
    private lateinit var tasksBox: LinearLayout
    private lateinit var mediaBox: LinearLayout

    private var settingsOpen = false
    private var targetId: Int? = null
    private var lastTag = -1
    private val steps = HashMap<Int, Pair<TextView, String>>()
    private val ui = Handler(Looper.getMainLooper())

    private val logL: (Int, String) -> Unit = { id, s -> ui.post { renderLine(id, s) } }
    private val stateL: (Boolean) -> Unit = { _ -> ui.post { refreshStatus() } }
    private val taskL: () -> Unit = { ui.post { refreshStatus(); if (settingsOpen) rebuildTasks() } }

    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(INK)
            setTypeface(null, Typeface.BOLD)
        }, lp(0, WRAP, 1f))
        status = TextView(this).apply {
            text = "Ready"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                if (AgentEngine.running) {
                    AgentEngine.stopAll()
                    Toast.makeText(this@MainActivity, "Stopping…", Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(status, lp(WRAP, WRAP, r = 8))
        header.addView(TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(MUTED)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { toggleSettings() }
        })
        root.addView(header)

        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat) }
        root.addView(scroll, lp(MATCH, 0, 1f, t = 8))

        settingsPanel = buildSettingsPanel()
        settingsPanel.visibility = View.GONE
        root.addView(settingsPanel, lp(MATCH, 0, 1f, t = 8))

        composeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val attach = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_upload)
            setColorFilter(ACCENT)
            background = shape(Color.WHITE, 20, LINE)
            contentDescription = "Attach"
            setOnClickListener { pickFile() }
        }
        input = EditText(this).apply {
            hint = "Message Iris"
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = shape(SURFACE, 22)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 5
        }
        val mic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(ACCENT)
            background = shape(Color.WHITE, 20, LINE)
            contentDescription = "Voice"
            setOnClickListener {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Iris what to do")
                try {
                    startActivityForResult(i, REQ_VOICE)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "No speech recognizer", Toast.LENGTH_LONG).show()
                }
            }
        }
        val send = TextView(this).apply {
            text = "↑"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = shape(INK, 22)
            setOnClickListener { sendMessage() }
        }
        composeBar.addView(attach, lp(dp(40), dp(40), r = 6))
        composeBar.addView(input, lp(0, WRAP, 1f, r = 6))
        composeBar.addView(mic, lp(dp(40), dp(40), r = 6))
        composeBar.addView(send, lp(dp(44), dp(44)))
        root.addView(composeBar, lp(MATCH, WRAP, t = 8))

        setContentView(root)

        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
        AgentEngine.taskListeners.add(taskL)
        refreshStatus()

        if (prefs().getString("api_key", "").isNullOrBlank()) openSettings()
    }

    private fun sendMessage() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (prefs().getString("api_key", "")!!.trim().isEmpty()) {
            Toast.makeText(this, "Add a Gemini API key in Settings", Toast.LENGTH_LONG).show()
            openSettings()
            return
        }
        input.setText("")
        val id = if (targetId != null) AgentEngine.reply(targetId!!, t) else AgentEngine.send(t)
        targetId = id
        refreshStatus()
    }

    private fun toggleSettings() {
        if (settingsOpen) closeSettings() else openSettings()
    }

    private fun openSettings() {
        settingsOpen = true
        scroll.visibility = View.GONE
        composeBar.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        rebuildTasks()
        rebuildMedia()
        hideKeyboard()
    }

    private fun closeSettings() {
        settingsOpen = false
        settingsPanel.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        composeBar.visibility = View.VISIBLE
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun buildSettingsPanel(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }

        fun section(title: String) {
            col.addView(TextView(this).apply {
                text = title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(MUTED)
                setPadding(0, dp(16), 0, dp(6))
            })
        }

        fun field(hint: String, key: String, password: Boolean = false) {
            val e = EditText(this).apply {
                this.hint = hint
                setText(prefs().getString(key, "") ?: "")
                if (password) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                background = shape(SURFACE, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnFocusChangeListener { _, has ->
                    if (!has) prefs().edit().putString(key, text.toString().trim()).apply()
                }
            }
            col.addView(e, lp(MATCH, WRAP, b = 6))
        }

        fun toggle(label: String, key: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(INK)
            }, lp(0, WRAP, 1f))
            row.addView(Switch(this).apply {
                isChecked = prefs().getBoolean(key, default)
                setOnCheckedChangeListener { _, on ->
                    prefs().edit().putBoolean(key, on).apply()
                    onChange?.invoke(on)
                }
            })
            col.addView(row)
        }

        fun linkRow(label: String, action: () -> Unit) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(ACCENT)
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { action() }
            })
        }

        section("ACCOUNT")
        field("Gemini API key", "api_key", password = true)
        field("Model (e.g. gemini-2.5-flash)", "model")
        field("GitHub token (self-modify / repo tools)", "github_token", password = true)

        section("CONTROL")
        toggle("Screen control", "screen", false) { on ->
            if (on && AgentAccessibilityService.instance == null) {
                Toast.makeText(this, "Enable Iris in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        toggle("Speak replies", "speak", true)
        toggle("Face island", "bubble", false) { on ->
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    try { startForegroundService(Intent(this, OverlayService::class.java)) } catch (_: Exception) { }
                }
            }
        }
        toggle("Quick access", "quick_on", true) { on ->
            if (on) {
                try { startForegroundService(Intent(this, OverlayService::class.java)) } catch (_: Exception) { }
                AgentAccessibilityService.instance?.let { Quick.enable(it, true) }
            } else Quick.disable()
        }
        toggle("Daily summary", "digest_on", false)
        linkRow("Keep alive (battery)") {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Already unrestricted. Allow autostart and lock Iris in Recents.", Toast.LENGTH_LONG).show()
            }
        }

        section("PC")
        linkRow("Pair PC (paste link from clipboard)") { pastePairLink() }

        section("MEDIA LIBRARY")
        mediaBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(mediaBox)
        linkRow("Add image or document") { pickFile() }

        section("POST QUEUE")
        linkRow("Open post queue") { showPostQueue() }

        section("ACTIVE TASKS")
        tasksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tasksBox)

        linkRow("Done — back to chat") { closeSettings() }

        return ScrollView(this).apply { addView(col) }
    }

    private fun rebuildTasks() {
        if (!::tasksBox.isInitialized) return
        tasksBox.removeAllViews()
        val ts = AgentEngine.tasks.filter { it.active }
        if (ts.isEmpty()) {
            tasksBox.addView(TextView(this).apply {
                text = "No active tasks"
                setTextColor(MUTED)
                textSize = 14f
            })
            return
        }
        for (t in ts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = "#${t.id} ${t.title} (${t.state})"
                textSize = 14f
                setTextColor(INK)
            }, lp(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = "Reply"
                setTextColor(ACCENT)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    targetId = t.id
                    closeSettings()
                    input.hint = "Reply to #${t.id}…"
                    input.requestFocus()
                }
            })
            row.addView(TextView(this).apply {
                text = "Stop"
                setTextColor(RED)
                setPadding(dp(8), 0, 0, 0)
                setOnClickListener { AgentEngine.stop(t.id); rebuildTasks() }
            })
            tasksBox.addView(row)
        }
    }

    private fun rebuildMedia() {
        if (!::mediaBox.isInitialized) return
        mediaBox.removeAllViews()
        val a = Media.list(this)
        if (a.length() == 0) {
            mediaBox.addView(TextView(this).apply {
                text = "No uploads yet — paperclip or Add below"
                setTextColor(MUTED)
                textSize = 13f
            })
            return
        }
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optString("id")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = "${o.optString("kind")}  ${o.optString("name")}  ($id)"
                textSize = 13f
                setTextColor(INK)
            }, lp(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = "Remove"
                setTextColor(RED)
                setOnClickListener {
                    Media.delete(this@MainActivity, id)
                    rebuildMedia()
                }
            })
            mediaBox.addView(row)
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/*", "text/csv", "text/markdown"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(i, "Attach file"), REQ_ATTACH)
        } catch (_: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPostQueue() {
        val posts = Posts.all(this)
        val lines = (0 until posts.length()).map { i ->
            val o = posts.getJSONObject(i)
            "${o.optString("status")} · ${o.optString("platform")} · ${o.optString("text").take(48)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Post queue")
            .setMessage(if (lines.isEmpty()) "(empty — schedule via chat after approving text)" else lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun pastePairLink() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val raw = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
        if (raw.isEmpty()) {
            Toast.makeText(this, "Copy the pair link from the PC first", Toast.LENGTH_LONG).show()
            return
        }
        val link = Pc.parseLink(raw)
        if (link == null) {
            Toast.makeText(this, "Not a valid Iris pair link", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val result = try {
                Pc.pair(this, link, Build.MODEL, true)
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            ui.post { Toast.makeText(this, result.toString(), Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun refreshStatus() {
        val n = AgentEngine.tasks.count { it.active }
        status.text = when (n) {
            0 -> "Ready"
            1 -> "Working · tap to stop"
            else -> "$n working · tap to stop"
        }
        status.setTextColor(if (n > 0) ACCENT else MUTED)
        if (targetId != null && AgentEngine.task(targetId!!) == null) {
            targetId = null
            input.hint = "Message Iris"
        }
    }

    private fun add(v: View, g: Int = Gravity.START, top: Int = 8) {
        chat.addView(v, lp(WRAP, WRAP, t = top).apply { gravity = g })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(text: String, fill: Int, ink: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(ink)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = shape(fill, 18)
        maxWidth = (resources.displayMetrics.widthPixels * 0.84).toInt()
        setTextIsSelectable(true)
    }

    private fun note(text: String, italic: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        setTextColor(MUTED)
        maxWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
    }

    private fun finishStep(id: Int) {
        steps.remove(id)?.let { (tv, txt) ->
            tv.text = "✓ $txt"
            tv.setTextColor(MUTED)
        }
    }

    private fun tag(id: Int) {
        if (id == lastTag) return
        lastTag = id
        val title = AgentEngine.task(id)?.title ?: ""
        add(TextView(this).apply {
            text = "#$id" + if (title.isNotEmpty()) "  $title" else ""
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(PALETTE[id % PALETTE.size])
        }, Gravity.START, 14)
    }

    private fun renderLine(id: Int, s: String) {
        if (settingsOpen) return
        tag(id)
        if (s.startsWith("→ ")) {
            finishStep(id)
            val txt = s.substring(2)
            val tv = TextView(this).apply {
                text = "… $txt"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(ACCENT)
            }
            steps[id] = Pair(tv, txt)
            add(tv, top = 4)
            return
        }
        finishStep(id)
        when {
            s.startsWith("You: ") -> add(bubble(s.substring(5), INK, Color.WHITE), Gravity.END, 6)
            s.startsWith("🤖 ") -> add(bubble(s.substring(3), SURFACE, INK), Gravity.START, 6)
            s.startsWith("💭 ") -> add(note(s.substring(3), true), Gravity.START, 6)
            s.startsWith("⚠") -> add(bubble(s.removePrefix("⚠").trim(), RED_BG, RED), Gravity.START, 6)
            else -> add(note(s, false), Gravity.START, 4)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_VOICE -> {
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
                if (!spoken.isNullOrEmpty()) {
                    input.setText(spoken)
                    sendMessage()
                }
            }
            REQ_ATTACH -> {
                val uri = data?.data ?: return
                val id = Media.importUri(this, uri)
                if (id.startsWith("ERROR")) {
                    Toast.makeText(this, id, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Attached · id $id", Toast.LENGTH_LONG).show()
                    if (settingsOpen) rebuildMedia()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (settingsOpen) closeSettings() else super.onBackPressed()
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.stateListeners.remove(stateL)
        AgentEngine.taskListeners.remove(taskL)
        super.onDestroy()
    }
}
