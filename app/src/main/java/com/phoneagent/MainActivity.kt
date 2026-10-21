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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val REQ_VOICE = 42
    }

    private val ACCENT = 0xFF0A84FF.toInt()
    private val ACCENT_BG = 0xFFE8F1FF.toInt()
    private val INK = 0xFF1C1C1E.toInt()
    private val MUTED = 0xFF8E8E93.toInt()
    private val LINE = 0xFFE5E5EA.toInt()
    private val SURFACE = 0xFFF2F2F7.toInt()
    private val GREEN = 0xFF1B7F3B.toInt()
    private val GREEN_BG = 0xFFE8F5E9.toInt()
    private val AMBER = 0xFFB26A00.toInt()
    private val RED = 0xFFC62828.toInt()
    private val RED_BG = 0xFFFDE8E8.toInt()
    private val PALETTE = intArrayOf(0xFF0A84FF.toInt(), 0xFF1B7F3B.toInt(), 0xFF8E44AD.toInt(),
        0xFFD35400.toInt(), 0xFF16A085.toInt(), 0xFFC0392B.toInt())
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var ghTokenField: EditText
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var send: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var taskScroll: HorizontalScrollView
    private lateinit var taskRow: LinearLayout
    private lateinit var aliveChip: TextView
    private var runOnPc = false
    private lateinit var pcChip: TextView
    private lateinit var speakChip: TextView
    private val shownApprovals = HashSet<String>()
    private var inboxOpen = false
    private var targetId: Int? = null
    private var lastTag = -1
    private val steps = HashMap<Int, Pair<TextView, String>>()
    private val ui = Handler(Looper.getMainLooper())

    private val logL: (Int, String) -> Unit = { id, s -> ui.post { renderLine(id, s) } }
    private val stateL: (Boolean) -> Unit = { _ -> ui.post { refreshTasks() } }
    private val taskL: () -> Unit = { ui.post { refreshTasks() } }
    private val inboxL: () -> Unit = { ui.post { checkInbox() } }

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

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }

    private fun styleChip(c: TextView, on: Boolean) {
        c.setTextColor(if (on) ACCENT else MUTED)
        c.background = shape(if (on) ACCENT_BG else Color.WHITE, 18, if (on) 0 else LINE)
    }

    private fun save() {
        prefs().edit()
            .putString("api_key", keyField.text.toString().trim())
            .putString("model", modelField.text.toString().trim())
            .putString("github_token", ghTokenField.text.toString().trim())
            .apply()
    }

    private fun stateIcon(s: String) = when (s) {
        "working" -> "●"; "needs screen" -> "◔"; "needs approval" -> "?"; "queued" -> "○"; "done" -> "✓"; "error" -> "!"; else -> "■"
    }

    private fun stateColor(s: String) = when (s) {
        "working" -> ACCENT; "needs screen" -> AMBER; "needs approval" -> AMBER; "done" -> GREEN; "error" -> RED; else -> MUTED
    }

    private fun refreshTasks() {
        val ts = AgentEngine.tasks.toList()
        val n = ts.count { it.active }
        status.text = when (n) { 0 -> "Ready"; 1 -> "Working ■"; else -> "$n working ■" }
        status.setTextColor(if (n > 0) ACCENT else GREEN)
        status.background = shape(if (n > 0) ACCENT_BG else GREEN_BG, 12)

        taskRow.removeAllViews()
        taskScroll.visibility = if (ts.isEmpty()) View.GONE else View.VISIBLE
        for (t in ts) {
            val pill = chip("${stateIcon(t.state)} #${t.id} ${t.title.take(14)}")
            styleChip(pill, targetId == t.id)
            if (targetId != t.id) pill.setTextColor(stateColor(t.state))
            pill.setOnClickListener { taskMenu(t) }
            taskRow.addView(pill, lp(WRAP, WRAP, r = 6))
        }
        if (targetId != null && AgentEngine.task(targetId!!) == null) targetId = null
        val pcDev = Pc.primary(this)
        input.hint = when {
            targetId != null -> "Reply to #$targetId…"
            runOnPc && pcDev != null -> "Run on ${pcDev.name}…"
            else -> "What should I do?"
        }
        refreshPcChip()
        showNextApproval()
    }

    private fun taskMenu(t: Task) {
        val items = ArrayList<CharSequence>()
        val acts = ArrayList<() -> Unit>()
        if (t.active) {
            items.add("Stop this task"); acts.add { AgentEngine.stop(t.id) }
        }
        items.add("Reply to this task"); acts.add { targetId = t.id; refreshTasks() }
        if (!t.active) {
            items.add("Remove"); acts.add { AgentEngine.tasks.remove(t); refreshTasks() }
        }
        if (t.remote == null && Pc.primary(this) != null) {
            items.add("Hand off to PC"); acts.add {
                val d = Pc.primary(this) ?: return@add
                AgentEngine.handoffToPc(t, d)
                Toast.makeText(this, "Handing off to ${d.name}…", Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("#${t.id}  ${t.title}")
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    private fun add(v: View, g: Int = Gravity.START, top: Int = 8) {
        chat.addView(v, lp(WRAP, WRAP, t = top).apply { gravity = g })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(text: String, fill: Int, ink: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ink)
        setPadding(dp(13), dp(9), dp(13), dp(9))
        background = shape(fill, 16)
        maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        setTextIsSelectable(true)
    }

    private fun note(text: String, italic: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        maxWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
    }

    private fun finishStep(id: Int) {
        steps.remove(id)?.let { (tv, txt) -> tv.text = "✓ $txt"; tv.setTextColor(MUTED) }
    }

    private fun tag(id: Int) {
        if (id == lastTag) return
        lastTag = id
        val title = AgentEngine.task(id)?.title ?: ""
        add(TextView(this).apply {
            text = "#$id" + (if (title.isNotEmpty()) "  $title" else "")
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(PALETTE[id % PALETTE.size])
        }, Gravity.START, 14)
    }

    private fun renderLine(id: Int, s: String) {
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
            else -> add(note(s, false), Gravity.START, 6)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = prefs()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        send = TextView(this).apply {
            text = "↑"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = shape(INK, 20)
        }
        status = TextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                if (AgentEngine.running) {
                    AgentEngine.stopAll()
                    Toast.makeText(this@MainActivity, "Stopping all tasks", Toast.LENGTH_SHORT).show()
                }
            }
        }
        taskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        taskScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(taskRow)
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
            text = "◉"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(ACCENT); background = shape(ACCENT_BG, 8)
        }, lp(dp(28), dp(28), r = 8))
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 20f
            setTextColor(INK); setTypeface(null, Typeface.BOLD)
        }, lp(0, WRAP, 1f))
        val settingsChip = chip("Settings")
        styleChip(settingsChip, false)
        header.addView(settingsChip)
        root.addView(header)

        keyField = EditText(this).apply {
            hint = "Gemini API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.getString("api_key", ""))
        }
        modelField = EditText(this).apply {
            hint = "Model (e.g. gemini-2.0-flash)"
            setText(p.getString("model", "gemini-2.0-flash"))
        }
        ghTokenField = EditText(this).apply {
            hint = "GitHub token (for self-modify / repo tools)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.getString("github_token", ""))
        }
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(SURFACE, 12)
            visibility = if (p.getString("api_key", "").isNullOrBlank()) View.VISIBLE else View.GONE
        }
        settingsCard.addView(keyField, lp(MATCH, WRAP))
        settingsCard.addView(modelField, lp(MATCH, WRAP, t = 6))
        settingsCard.addView(ghTokenField, lp(MATCH, WRAP, t = 6))
        settingsChip.setOnClickListener {
            settingsCard.visibility = if (settingsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        root.addView(settingsCard, lp(MATCH, WRAP, t = 10))

        val screenChip = chip("Screen control")
        styleChip(screenChip, AgentAccessibilityService.instance != null)
        screenChip.setOnClickListener {
            if (AgentAccessibilityService.instance == null) {
                Toast.makeText(this, "Turn on Iris in Accessibility settings", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val islandChip = chip("Face island")
        styleChip(islandChip, p.getBoolean("bubble", false))
        islandChip.setOnClickListener {
            val on = !prefs().getBoolean("bubble", false)
            prefs().edit().putBoolean("bubble", on).apply()
            styleChip(islandChip, on)
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Allow 'Display over other apps', then come back", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    try { startForegroundService(Intent(this, OverlayService::class.java)) } catch (e: Exception) { }
                }
            } else if (!AgentEngine.running && !prefs().getBoolean("quick_on", true)) {
                startService(Intent(this, OverlayService::class.java).setAction("STOP"))
            }
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chips.addView(screenChip, lp(WRAP, WRAP, r = 8))
        chips.addView(islandChip, lp(WRAP, WRAP))
        chips.addView(View(this), lp(0, 1, 1f))
        chips.addView(status)
        root.addView(chips, lp(MATCH, WRAP, t = 10))

        // NOTE: remainder of MainActivity body is required for compile — continue below
        setContentView(root)
    }
}
