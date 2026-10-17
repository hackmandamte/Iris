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
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(SURFACE, 12)
            visibility = if (p.getString("api_key", "").isNullOrBlank()) View.VISIBLE else View.GONE
        }
        settingsCard.addView(keyField, lp(MATCH, WRAP))
        settingsCard.addView(modelField, lp(MATCH, WRAP, t = 6))
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

        val queueChip = chip("Post queue")
        styleChip(queueChip, false)
        queueChip.setOnClickListener { showPostQueue() }
        val digestChip = chip("Daily summary")
        styleChip(digestChip, prefs().getBoolean("digest_on", false))
        digestChip.setOnClickListener { showDigestMenu(digestChip) }
        val quickChip = chip("Quick access")
        styleChip(quickChip, p.getBoolean("quick_on", true))
        quickChip.setOnClickListener {
            val on = !prefs().getBoolean("quick_on", true)
            prefs().edit().putBoolean("quick_on", on).apply()
            styleChip(quickChip, on)
            if (on) {
                try { startForegroundService(Intent(this, OverlayService::class.java)) } catch (e: Exception) { }
                val svc = AgentAccessibilityService.instance
                if (svc != null) Quick.enable(svc, true)
            } else {
                Quick.disable()
            }
        }
        aliveChip = chip("Keep alive")
        aliveChip.setOnClickListener {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Battery limits are already off for Iris. Also allow Autostart for Iris and lock it in Recents.", Toast.LENGTH_LONG).show()
            }
        }
        refreshAlive()
        val socialRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        socialRow.addView(queueChip, lp(WRAP, WRAP, r = 8))
        socialRow.addView(digestChip, lp(WRAP, WRAP, r = 8))
        socialRow.addView(quickChip, lp(WRAP, WRAP, r = 8))
        socialRow.addView(aliveChip, lp(WRAP, WRAP, r = 8))
        speakChip = chip("Speak")
        styleChip(speakChip, p.getBoolean("speak", true))
        speakChip.setOnClickListener {
            val on = !prefs().getBoolean("speak", true)
            prefs().edit().putBoolean("speak", on).apply()
            styleChip(speakChip, on)
            Toast.makeText(this, if (on) "Speaking enabled" else "Muted — no audio", Toast.LENGTH_SHORT).show()
        }
        socialRow.addView(speakChip, lp(WRAP, WRAP, r = 8))
        pcChip = chip("Pair PC")
        pcChip.setOnClickListener { pcMenu() }
        socialRow.addView(pcChip, lp(WRAP, WRAP))
        runOnPc = p.getBoolean("run_on_pc", false)
        refreshPcChip()
        val social = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(socialRow)
        }
        root.addView(social, lp(MATCH, WRAP, t = 8))

        root.addView(taskScroll, lp(MATCH, WRAP, t = 8))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, lp(MATCH, dp(1), t = 12))

        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat) }
        root.addView(scroll, lp(MATCH, 0, 1f))

        val mic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(ACCENT)
            background = shape(Color.WHITE, 20, LINE)
            contentDescription = "Voice input"
            setOnClickListener {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Iris what to do")
                try {
                    this@MainActivity.startActivityForResult(i, REQ_VOICE)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No speech recognizer available on this phone", Toast.LENGTH_LONG).show()
                }
            }
        }
        input = EditText(this).apply {
            hint = "What should I do?"
            textSize = 14f
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = shape(SURFACE, 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            save()
            val key = keyField.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Paste a Gemini API key in Settings first", Toast.LENGTH_LONG).show()
                settingsCard.visibility = View.VISIBLE
                return@setOnClickListener
            }
            input.setText("")
            val tid = targetId
            targetId = null
            if (tid != null) {
                AgentEngine.reply(tid, t)
            } else {
                val pc = if (runOnPc) Pc.primary(this) else null
                if (pc != null) AgentEngine.sendToPc(pc, t) else AgentEngine.send(t)
            }
            refreshTasks()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        bar.addView(mic, lp(dp(40), dp(40), r = 8))
        bar.addView(input, lp(0, WRAP, 1f, r = 8))
        bar.addView(send, lp(dp(40), dp(40)))
        root.addView(bar)

        setContentView(root)

        AgentEngine.logListeners.add(logL)
        AgentEngine.stateListeners.add(stateL)
        AgentEngine.taskListeners.add(taskL)
        Pc.inboxListeners.add(inboxL)
        refreshTasks()
    }

    private fun refreshAlive() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        styleChip(aliveChip, pm.isIgnoringBatteryOptimizations(packageName))
    }

    private fun refreshPcChip() {
        val dev = Pc.primary(this)
        styleChip(pcChip, runOnPc && dev != null)
        pcChip.text = when {
            runOnPc && dev != null -> "PC: ${dev.name}"
            else -> "Pair PC"
        }
    }

    private fun pcMenu() {
        val devices = Pc.devices(this)
        val items = ArrayList<CharSequence>()
        val acts = ArrayList<() -> Unit>()
        if (devices.isEmpty()) {
            items.add("Paste pairing link…")
            acts.add { pastePairLink() }
        } else {
            val primary = Pc.primary(this)
            items.add(if (runOnPc) "Run new tasks on this phone" else "Run new tasks on ${primary?.name ?: "PC"}")
            acts.add {
                runOnPc = !runOnPc
                prefs().edit().putBoolean("run_on_pc", runOnPc).apply()
                refreshPcChip()
            }
            items.add("Paste another pairing link…")
            acts.add { pastePairLink() }
            for (d in devices) {
                items.add("Unpair ${d.name}")
                acts.add {
                    Pc.remove(this, d.id)
                    if (devices.size == 1) {
                        runOnPc = false
                        prefs().edit().putBoolean("run_on_pc", false).apply()
                    }
                    refreshPcChip()
                    Toast.makeText(this, "Unpaired ${d.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Pair PC")
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pastePairLink() {
        val field = EditText(this).apply {
            hint = "iris://pair?h=…&p=…&t=…"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Paste pairing link")
            .setMessage("On the PC, start pairing and copy the iris:// link (or scan the QR).")
            .setView(field)
            .setPositiveButton("Pair") { _, _ ->
                val raw = field.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val link = Pc.parseLink(raw)
                if (link == null) {
                    Toast.makeText(this, "Invalid pairing link", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val err = Pc.pair(this, link, android.os.Build.MODEL, true)
                if (err != null) {
                    Toast.makeText(this, "Pair failed: $err", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Paired with ${link.pcName}", Toast.LENGTH_SHORT).show()
                    refreshPcChip()
                    Pc.startPolling(applicationContext)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPostQueue() {
        val arr = Posts.all(this)
        if (arr.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("Post queue")
                .setMessage("No scheduled posts. Ask Iris to schedule one (it will confirm text, platform and time first).")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val posts = (0 until arr.length()).map { arr.getJSONObject(it) }
        val items = posts.map { p ->
            val st = p.optString("status")
            val whenStr = try {
                val at = p.optLong("at")
                if (at > 0) java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
                else "?"
            } catch (_: Exception) { "?" }
            "${Posts.name(p.optString("platform"))}  $whenStr  [$st]\n${p.optString("text").take(60)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Post queue (${posts.size})")
            .setItems(items.toTypedArray()) { _, i ->
                val p = posts[i]
                val id = p.optString("id")
                AlertDialog.Builder(this)
                    .setTitle("Cancel this post?")
                    .setMessage(p.optString("text").take(200))
                    .setPositiveButton("Cancel post") { _, _ ->
                        Posts.cancel(this, id)
                        Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDigestMenu(chip: TextView) {
        val on = prefs().getBoolean("digest_on", false)
        val hour = prefs().getInt("digest_hour", 8)
        val items = arrayOf(
            if (on) "Turn daily summary off" else "Turn daily summary on",
            "Set hour (currently ${String.format("%02d", hour)}:00)",
            "Run summary now"
        )
        AlertDialog.Builder(this)
            .setTitle("Daily summary")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val next = !on
                        prefs().edit().putBoolean("digest_on", next).apply()
                        styleChip(chip, next)
                        if (next) Posts.armDigest(this) else Posts.clearDigest(this)
                        Toast.makeText(this, if (next) "Daily summary on" else "Daily summary off", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val hours = (0..23).map { String.format("%02d:00", it) }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Digest hour")
                            .setItems(hours) { _, h ->
                                prefs().edit().putInt("digest_hour", h).apply()
                                if (prefs().getBoolean("digest_on", false)) Posts.armDigest(this)
                                Toast.makeText(this, "Digest at ${String.format("%02d", h)}:00", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    2 -> {
                        AgentEngine.runDigest()
                        Toast.makeText(this, "Running daily summary…", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showNextApproval() {
        val a = AgentEngine.approvals.firstOrNull { it.id !in shownApprovals } ?: return
        shownApprovals.add(a.id)
        AlertDialog.Builder(this)
            .setTitle("Approve? ${a.kind}")
            .setMessage(a.text.take(500))
            .setPositiveButton("Allow") { _, _ -> AgentEngine.answerApproval(a, true) }
            .setNegativeButton("Deny") { _, _ -> AgentEngine.answerApproval(a, false) }
            .setOnDismissListener { showNextApproval() }
            .show()
    }

    private fun checkInbox() {
        if (inboxOpen) return
        val items = Pc.inbox(this)
        if (items.isEmpty()) return
        val first = items.first()
        inboxOpen = true
        val title = first.optString("title", "From PC")
        val body = first.optString("text", first.optString("body", ""))
        val id = first.optString("id")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body.take(1000))
            .setPositiveButton("Open") { _, _ ->
                Pc.removeInbox(this, id)
                inboxOpen = false
                val prompt = first.optString("prompt", body)
                if (prompt.isNotBlank()) AgentEngine.send(prompt)
                refreshTasks()
            }
            .setNegativeButton("Dismiss") { _, _ ->
                Pc.removeInbox(this, id)
                inboxOpen = false
                checkInbox()
            }
            .setOnCancelListener { inboxOpen = false }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input.setText(spoken)
                input.setSelection(input.text.length)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val sp = prefs()
        val quick = sp.getBoolean("quick_on", true)
        if (quick || (sp.getBoolean("bubble", false) && Settings.canDrawOverlays(this))) {
            try {
                startForegroundService(Intent(this, OverlayService::class.java))
            } catch (e: Exception) { }
        }
        if (quick) AgentAccessibilityService.instance?.let { Quick.enable(it, true) }
        refreshAlive()
        if (::speakChip.isInitialized) styleChip(speakChip, sp.getBoolean("speak", true))
        refreshTasks()
        if (Pc.devices(this).isNotEmpty()) Pc.startPolling(applicationContext)
        checkInbox()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.stateListeners.remove(stateL)
        AgentEngine.taskListeners.remove(taskL)
        Pc.inboxListeners.remove(inboxL)
        super.onDestroy()
    }
}
