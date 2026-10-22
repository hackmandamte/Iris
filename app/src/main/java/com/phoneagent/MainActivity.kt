package com.phoneagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Grok-like shell: minimal chat, paperclip compose, accordion Settings with icons. */
class MainActivity : Activity() {
    companion object {
        private const val REQ_VOICE = 42
        private const val REQ_ATTACH = 43
    }

    private var ACCENT = 0xFF6B8AFF.toInt()
    private var INK = 0xFF1C1C1E.toInt()
    private var MUTED = 0xFF8E8E93.toInt()
    private var LINE = 0xFFE5E5EA.toInt()
    private var SURFACE = 0xFFF2EEF5.toInt()
    private var SURFACE2 = 0xFFE8E2F0.toInt()
    private var BG = 0xFFFAF7F5.toInt()
    private var USER_BUBBLE = 0xFFFFE8D6.toInt()
    private var USER_INK = 0xFF4A3428.toInt()
    private var AGENT_BUBBLE = 0xFFE8E4F5.toInt()
    private var AGENT_INK = 0xFF2A2640.toInt()
    private var RED = 0xFFC62828.toInt()
    private var RED_BG = 0xFFFDE8E8.toInt()
    private var PALETTE = intArrayOf(
        0xFF6B8AFF.toInt(), 0xFF9B6DFF.toInt(), 0xFFE8A87C.toInt(),
        0xFF7B9EFF.toInt(), 0xFFD4849A.toInt()
    )
    private var dark = false
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
    private lateinit var connectionsBox: LinearLayout

    private var settingsOpen = false
    private var targetId: Int? = null
    private var lastTag = -1
    private val steps = HashMap<Int, Pair<TextView, String>>()
    private val ui = Handler(Looper.getMainLooper())
    private val expandedSections = HashSet<String>()

    private val SOCIAL_TARGETS = listOf(
        "com.instagram.android" to "Instagram",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram (web)",
        "com.twitter.android" to "X",
        "com.google.android.youtube" to "YouTube",
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.snapchat.android" to "Snapchat",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.linkedin.android" to "LinkedIn"
    )

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

    private fun blendShape(radius: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0xFF6B8AFF.toInt(), 0xFF9B6DFF.toInt(),
                0xFFE8A87C.toInt(), 0xFFD4849A.toInt()
            )
        ).apply { cornerRadius = dp(radius).toFloat() }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun isInstalled(pkg: String): Boolean {
        return try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
    }

    private fun resolveTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        dark = night
        if (night) {
            ACCENT = 0xFF8BA3FF.toInt(); INK = 0xFFF5F3F8.toInt(); MUTED = 0xFF9A94A8.toInt()
            LINE = 0xFF2C2838.toInt(); SURFACE = 0xFF1C1A24.toInt(); SURFACE2 = 0xFF252230.toInt()
            BG = 0xFF0C0B10.toInt()
            USER_BUBBLE = 0xFF3D322C.toInt(); USER_INK = 0xFFFFE8D6.toInt()
            AGENT_BUBBLE = 0xFF2A2740.toInt(); AGENT_INK = 0xFFE8E4F8.toInt()
            RED = 0xFFFF6B6B.toInt(); RED_BG = 0xFF3D2020.toInt()
        } else {
            ACCENT = 0xFF5B7CFF.toInt(); INK = 0xFF1C1C1E.toInt(); MUTED = 0xFF8E8E93.toInt()
            LINE = 0xFFE8E0EC.toInt(); SURFACE = 0xFFF5F0F7.toInt(); SURFACE2 = 0xFFEDE6F2.toInt()
            BG = 0xFFFAF7F5.toInt()
            USER_BUBBLE = 0xFFFFE8D6.toInt(); USER_INK = 0xFF4A3428.toInt()
            AGENT_BUBBLE = 0xFFE8E4F5.toInt(); AGENT_INK = 0xFF2A2640.toInt()
            RED = 0xFFC62828.toInt(); RED_BG = 0xFFFDE8E8.toInt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolveTheme()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 22f; setTextColor(INK); setTypeface(null, Typeface.BOLD)
        }, lp(0, WRAP, 1f))
        status = TextView(this).apply {
            text = "Ready"; textSize = 13f; setTextColor(MUTED); setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                if (AgentEngine.running) {
                    AgentEngine.stopAll()
                    Toast.makeText(this@MainActivity, "Stopping…", Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(status, lp(WRAP, WRAP, r = 4))
        header.addView(TextView(this).apply {
            text = "⚙"; textSize = 20f; setTextColor(MUTED); setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { toggleSettings() }
        })
        root.addView(header)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(chat) }
        root.addView(scroll, lp(MATCH, 0, 1f, t = 10))
        settingsPanel = buildSettingsPanel()
        settingsPanel.visibility = View.GONE
        root.addView(settingsPanel, lp(MATCH, 0, 1f, t = 10))
        composeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(SURFACE, 28, LINE); setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val attach = TextView(this).apply {
            text = "📎"; textSize = 20f; gravity = Gravity.CENTER; contentDescription = "Attach file"
            setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { pickFile() }
        }
        input = EditText(this).apply {
            hint = "Message Iris"; textSize = 15f; setTextColor(INK); setHintTextColor(MUTED)
            background = null; setPadding(dp(4), dp(10), dp(4), dp(10)); maxLines = 5
        }
        val mic = TextView(this).apply {
            text = "🎤"; textSize = 18f; gravity = Gravity.CENTER; contentDescription = "Voice"
            setPadding(dp(10), dp(8), dp(6), dp(8))
            setOnClickListener {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                try { startActivityForResult(i, REQ_VOICE) } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "No speech recognizer", Toast.LENGTH_LONG).show()
                }
            }
        }
        val send = TextView(this).apply {
            text = "↑"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = blendShape(22); setOnClickListener { sendMessage() }
        }
        composeBar.addView(attach, lp(WRAP, WRAP))
        composeBar.addView(input, lp(0, WRAP, 1f))
        composeBar.addView(mic, lp(WRAP, WRAP))
        composeBar.addView(send, lp(dp(44), dp(44)))
        root.addView(composeBar, lp(MATCH, WRAP, t = 10))
        setContentView(root)
        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
        AgentEngine.taskListeners.add(taskL)
        refreshStatus()
        if (prefs().getString("api_key", "").isNullOrBlank() &&
            prefs().getString("openai_key", "").isNullOrBlank()) {
            expandedSections.add("account"); openSettings()
        }
    }

    private fun sendMessage() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        val hasKey = prefs().getString("api_key", "")!!.trim().isNotEmpty() ||
            prefs().getString("openai_key", "")!!.trim().isNotEmpty()
        if (!hasKey) {
            Toast.makeText(this, "Add a Gemini or OpenAI key in Settings", Toast.LENGTH_LONG).show()
            expandedSections.add("account"); openSettings(); return
        }
        input.setText("")
        val id = if (targetId != null) AgentEngine.reply(targetId!!, t) else AgentEngine.send(t)
        targetId = id; refreshStatus()
    }

    private fun toggleSettings() { if (settingsOpen) closeSettings() else openSettings() }

    private fun openSettings() {
        settingsOpen = true
        scroll.visibility = View.GONE; composeBar.visibility = View.GONE; settingsPanel.visibility = View.VISIBLE
        rebuildTasks(); rebuildMedia(); rebuildConnections(); hideKeyboard()
    }

    private fun closeSettings() {
        settingsOpen = false
        settingsPanel.visibility = View.GONE; scroll.visibility = View.VISIBLE; composeBar.visibility = View.VISIBLE
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun accordion(
        parent: LinearLayout, id: String, icon: String, title: String,
        defaultOpen: Boolean = false, buildBody: (LinearLayout) -> Unit
    ) {
        if (defaultOpen) expandedSections.add(id)
        val open = id in expandedSections
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(SURFACE, 16, LINE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14)); isClickable = true; isFocusable = true
        }
        header.addView(TextView(this).apply { text = icon; textSize = 18f; setPadding(0, 0, dp(10), 0) })
        header.addView(TextView(this).apply {
            text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(INK)
        }, lp(0, WRAP, 1f))
        val chevron = TextView(this).apply {
            text = if (open) "▾" else "▸"; textSize = 16f; setTextColor(MUTED)
        }
        header.addView(chevron)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12))
            visibility = if (open) View.VISIBLE else View.GONE
        }
        buildBody(body)
        header.setOnClickListener {
            if (id in expandedSections) {
                expandedSections.remove(id); body.visibility = View.GONE; chevron.text = "▸"
            } else {
                expandedSections.add(id); body.visibility = View.VISIBLE; chevron.text = "▾"
                if (id == "tasks") rebuildTasks()
                if (id == "media") rebuildMedia()
                if (id == "connected") rebuildConnections()
            }
        }
        card.addView(header); card.addView(body)
        parent.addView(card, lp(MATCH, WRAP, b = 10))
    }

    private fun fieldIn(parent: LinearLayout, hint: String, key: String, password: Boolean = false) {
        parent.addView(EditText(this).apply {
            this.hint = hint; setText(prefs().getString(key, "") ?: "")
            setTextColor(INK); setHintTextColor(MUTED)
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = shape(SURFACE2, 12, LINE); setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnFocusChangeListener { _, has -> if (!has) prefs().edit().putString(key, text.toString().trim()).apply() }
        }, lp(MATCH, WRAP, b = 8))
    }

    private fun toggleIn(parent: LinearLayout, label: String, key: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(INK) }, lp(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = prefs().getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean(key, on).apply(); onChange?.invoke(on) }
        })
        parent.addView(row)
    }

    private fun linkIn(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = label; textSize = 15f; setTextColor(ACCENT); setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { action() }
        })
    }

    private fun buildSettingsPanel(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }
        col.addView(TextView(this).apply {
            text = "Settings"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(INK)
            setPadding(0, 0, 0, dp(12))
        })
        accordion(col, "account", "🔑", "Account & models", defaultOpen = true) { body ->
            fieldIn(body, "Gemini API key", "api_key", password = true)
            fieldIn(body, "Preferred Gemini model (auto-fallback)", "model")
            fieldIn(body, "Provider: auto | gemini | openai", "provider")
            fieldIn(body, "OpenAI-compatible API key", "openai_key", password = true)
            fieldIn(body, "OpenAI base URL", "openai_base")
            fieldIn(body, "OpenAI model (e.g. gpt-4o-mini)", "openai_model")
            fieldIn(body, "GitHub token (self-modify)", "github_token", password = true)
        }
        accordion(col, "control", "🎛", "Control") { body ->
            body.addView(TextView(this).apply {
                text = "Screen control needs Accessibility (Android rule). One-time enable for Iris."
                textSize = 12f; setTextColor(MUTED); setPadding(0, 0, 0, dp(8))
            })
            toggleIn(body, "Screen control", "screen", false) { on ->
                if (on && AgentAccessibilityService.instance == null) {
                    Toast.makeText(this, "Enable Iris in Accessibility settings", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            toggleIn(body, "Speak replies", "speak", true)
            toggleIn(body, "Face island", "bubble", false) { on ->
                if (on) {
                    if (!Settings.canDrawOverlays(this))
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    else try { startForegroundService(Intent(this, OverlayService::class.java)) } catch (_: Exception) { }
                }
            }
            toggleIn(body, "Quick access", "quick_on", true) { on ->
                if (on) {
                    try { startForegroundService(Intent(this, OverlayService::class.java)) } catch (_: Exception) { }
                    AgentAccessibilityService.instance?.let { Quick.enable(it, true) }
                } else Quick.disable()
            }
            toggleIn(body, "Daily summary", "digest_on", false)
            linkIn(body, "Keep alive (battery)") {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(packageName))
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                else Toast.makeText(this, "Already unrestricted.", Toast.LENGTH_LONG).show()
            }
        }
        accordion(col, "pc", "💻", "PC") { body ->
            linkIn(body, "Pair PC (paste link from clipboard)") { pastePairLink() }
        }
        accordion(col, "connected", "🔗", "Connected apps") { body ->
            connectionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(connectionsBox)
            linkIn(body, "Enable notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            linkIn(body, "Enable screen control (Accessibility)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            linkIn(body, "Refresh list") { rebuildConnections() }
        }
        accordion(col, "media", "🖼", "Media library") { body ->
            mediaBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(mediaBox)
            linkIn(body, "Add image or document") { pickFile() }
        }
        accordion(col, "posts", "📅", "Post queue") { body ->
            linkIn(body, "Open post queue") { openPostsScreen() }
        }
        accordion(col, "tasks", "⚡", "Active tasks") { body ->
            tasksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(tasksBox)
        }
        val close = TextView(this).apply {
            text = "Done"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = blendShape(16); setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { closeSettings() }
        }
        col.addView(close, lp(MATCH, WRAP, t = 8))
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    private fun rebuildTasks() {
        if (!::tasksBox.isInitialized) return
        tasksBox.removeAllViews()
        val active = AgentEngine.tasks.filter { it.active }
        if (active.isEmpty()) {
            tasksBox.addView(TextView(this).apply { text = "No active tasks"; setTextColor(MUTED); textSize = 13f })
            return
        }
        for (t in active) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = "#${t.id} ${t.title} · ${t.state}"; setTextColor(INK); textSize = 13f
            }, lp(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = "Stop"; setTextColor(RED); textSize = 13f; setPadding(dp(8), 0, 0, 0)
                setOnClickListener { AgentEngine.stop(t.id) }
            })
            tasksBox.addView(row)
        }
    }

    private fun rebuildMedia() {
        if (!::mediaBox.isInitialized) return
        mediaBox.removeAllViews()
        val files = Media.list(this)
        if (files.isEmpty()) {
            mediaBox.addView(TextView(this).apply { text = "No uploads yet"; setTextColor(MUTED); textSize = 13f })
            return
        }
        for (f in files.take(20)) {
            mediaBox.addView(TextView(this).apply {
                text = f; setTextColor(INK); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    private fun rebuildConnections() {
        if (!::connectionsBox.isInitialized) return
        connectionsBox.removeAllViews()
        for ((pkg, name) in SOCIAL_TARGETS) {
            val on = isInstalled(pkg)
            connectionsBox.addView(TextView(this).apply {
                text = "$name — ${if (on) "installed" else "not installed"}"
                setTextColor(if (on) INK else MUTED); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
            })
        }
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        connectionsBox.addView(TextView(this).apply {
            text = "Notification access — ${if (notif) "on" else "off"}"
            setTextColor(if (notif) INK else MUTED); textSize = 13f; setPadding(0, dp(8), 0, dp(4))
        })
        val screen = AgentAccessibilityService.instance != null
        connectionsBox.addView(TextView(this).apply {
            text = "Screen control service — ${if (screen) "running" else "off"}"
            setTextColor(if (screen) INK else MUTED); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun openPostsScreen() {
        try {
            startActivity(Intent(this, Class.forName("com.phoneagent.PostsActivity")))
        } catch (_: Exception) {
            val posts = Posts.all(this)
            val msg = if (posts.isEmpty()) "Queue empty. Ask Iris to schedule a post."
            else posts.take(8).joinToString("\n") { p ->
                "${p.optString("platform")} · ${p.optString("status")} · ${p.optString("text").take(40)}"
            }
            AlertDialog.Builder(this).setTitle("Post queue").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }

    private fun pastePairLink() {
        val clip = getSystemService(android.content.ClipboardManager::class.java).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Copy a pair link first", Toast.LENGTH_LONG).show()
            return
        }
        threadPair(text)
    }

    private fun threadPair(link: String) {
        Thread {
            try {
                val r = Pc.pair(this, link)
                runOnUiThread {
                    Toast.makeText(this, if (r) "Paired" else "Pair failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Pair error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/*"))
        }
        try { startActivityForResult(Intent.createChooser(i, "Attach"), REQ_ATTACH) }
        catch (_: Exception) { Toast.makeText(this, "No file picker", Toast.LENGTH_LONG).show() }
    }

    private fun refreshStatus() {
        val n = AgentEngine.tasks.count { it.active }
        status.text = when {
            n > 0 -> "Working · $n"
            else -> "Ready"
        }
        status.setTextColor(if (n > 0) ACCENT else MUTED)
        if (targetId != null && AgentEngine.task(targetId!!)?.active != true) targetId = null
    }

    private fun renderLine(id: Int, s: String) {
        val isYou = s.startsWith("You: ")
        val isBot = s.startsWith("🤖 ")
        val isErr = s.startsWith("⚠ ")
        val text = when {
            isYou -> s.removePrefix("You: ")
            isBot -> s.removePrefix("🤖 ")
            isErr -> s.removePrefix("⚠ ")
            else -> s
        }
        if (s.startsWith("→ ") || s.startsWith("← ") || s.startsWith("↻ ")) {
            val step = steps[id]
            if (step != null) {
                step.first.text = s.take(120)
            } else {
                val tv = TextView(this).apply {
                    this.text = s.take(120); textSize = 12f; setTextColor(MUTED)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                }
                steps[id] = tv to s
                chat.addView(tv)
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        steps.remove(id)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            if (isYou) {
                setTextColor(USER_INK)
                background = shape(USER_BUBBLE, 18)
            } else if (isErr) {
                setTextColor(RED)
                background = shape(RED_BG, 18)
            } else {
                setTextColor(AGENT_INK)
                background = shape(AGENT_BUBBLE, 18)
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isYou) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
            addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                width = 0; weight = 1f
                marginStart = if (isYou) dp(48) else 0
                marginEnd = if (isYou) 0 else dp(48)
            })
        }
        // fix width
        wrap.removeAllViews()
        wrap.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            marginStart = if (isYou) dp(48) else 0
            marginEnd = if (isYou) 0 else dp(48)
        })
        chat.addView(wrap)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_VOICE -> {
                val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spoken = matches?.firstOrNull()?.trim().orEmpty()
                if (spoken.isNotEmpty()) {
                    input.setText(spoken)
                    sendMessage()
                }
            }
            REQ_ATTACH -> {
                val uri = data.data ?: return
                Thread {
                    try {
                        val id = Media.importUri(this, uri)
                        runOnUiThread {
                            Toast.makeText(this, "Saved upload $id", Toast.LENGTH_SHORT).show()
                            if (settingsOpen) rebuildMedia()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        }
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.stateListeners.remove(stateL)
        AgentEngine.taskListeners.remove(taskL)
        super.onDestroy()
    }
}
