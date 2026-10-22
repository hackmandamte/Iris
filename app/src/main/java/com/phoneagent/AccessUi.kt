package com.phoneagent

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Quick-access Face bubble + overlay toolbar. Stays on current screen. */
object Quick {
    private var host: Context? = null
    private var accessibilityHost = false
    private var bubble: View? = null
    private var face: FaceView? = null
    private var menu: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var idleAlpha = 1f
    private val fadeStep = object : Runnable {
        override fun run() {
            val v = bubble ?: return
            if (menu != null) { idleAlpha = 1f; v.alpha = 1f; return }
            if (idleAlpha > 0.28f) {
                idleAlpha = (idleAlpha - 0.04f).coerceAtLeast(0.28f)
                v.alpha = idleAlpha
                ui.postDelayed(this, 80)
            }
        }
    }
    private val startFade = Runnable {
        idleAlpha = 1f
        ui.removeCallbacks(fadeStep)
        ui.postDelayed(fadeStep, 40)
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun enable(ctx: Context, fromAccessibility: Boolean) {
        host = ctx.applicationContext
        accessibilityHost = fromAccessibility
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ui.post { showBubble() }
    }

    fun disable() {
        ui.post {
            ui.removeCallbacks(startFade); ui.removeCallbacks(fadeStep)
            removeBubble(); hideMenu(); host = null; wm = null
        }
    }

    fun disableIfHost(ctx: Context) {
        if (host === ctx.applicationContext || host == null) disable()
    }

    fun summon(): Boolean {
        val h = host ?: return false
        ui.post { showMenu(h) }
        return true
    }

    private fun type(): Int =
        if (accessibilityHost) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun wakeBubble() {
        val v = bubble ?: return
        ui.removeCallbacks(startFade); ui.removeCallbacks(fadeStep)
        idleAlpha = 1f; v.alpha = 1f
        ui.postDelayed(startFade, 4500)
    }

    private fun showBubble() {
        val ctx = host ?: return
        val w = wm ?: return
        if (bubble != null) return
        val size = dp(ctx, 52)
        val f = FaceView(ctx)
        val holder = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xE6121018.toInt()); cornerRadius = size / 2f
                setStroke(dp(ctx, 1), 0x55C9A227.toInt())
            }
            addView(f); alpha = 1f
        }
        val lp = WindowManager.LayoutParams(
            size, size, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(ctx, 140) }
        holder.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { wakeBubble(); sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                        if (moved) { lp.x = sx + dx; lp.y = sy + dy; try { w.updateViewLayout(view, lp) } catch (_: Exception) { } }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) showMenu(ctx)
                        else {
                            val sw = ctx.resources.displayMetrics.widthPixels
                            lp.x = if (lp.x + size / 2 < sw / 2) 0 else sw - size
                            try { w.updateViewLayout(view, lp) } catch (_: Exception) { }
                        }
                        wakeBubble()
                    }
                }
                return true
            }
        })
        try { w.addView(holder, lp); bubble = holder; face = f; wakeBubble() } catch (_: Exception) { }
    }

    private fun removeBubble() {
        val w = wm ?: return
        bubble?.let { try { w.removeView(it) } catch (_: Exception) { } }
        bubble = null; face = null
    }

    private fun chip(ctx: Context, label: String, accent: Int, action: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(ctx, 14).toFloat() }
            setOnClickListener { action() }
        }

    private fun showMenu(ctx: Context) {
        if (menu != null) { hideMenu(); return }
        val w = wm ?: return
        wakeBubble()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            background = GradientDrawable().apply {
                colors = intArrayOf(0xF21A1824.toInt(), 0xF2121018.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = dp(ctx, 22).toFloat()
                setStroke(dp(ctx, 1), 0x44C9A227.toInt())
            }
            elevation = dp(ctx, 12).toFloat()
        }
        box.addView(TextView(ctx).apply {
            text = "Iris"; textSize = 13f; setTextColor(0xFFC9A227.toInt())
            setPadding(dp(ctx, 4), 0, 0, dp(ctx, 10))
        })
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xFF252230.toInt()); cornerRadius = dp(ctx, 16).toFloat() }
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val et = EditText(ctx).apply {
            hint = "Quick message"; setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt())
            background = null; textSize = 15f; imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(true)
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
        }
        val mic = TextView(ctx).apply {
            text = "🎤"; textSize = 18f; gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            setOnClickListener { listenVoice(ctx, et) }
        }
        val send = TextView(ctx).apply {
            text = "↑"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF1A1508.toInt())
            background = GradientDrawable().apply { setColor(0xFFE0C35A.toInt()); cornerRadius = dp(ctx, 14).toFloat() }
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            setOnClickListener {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { hideMenu(); AgentEngine.send(t) }
            }
        }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { hideMenu(); AgentEngine.send(t) }
                true
            } else false
        }
        inputRow.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(mic); inputRow.addView(send)
        box.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 12) })

        fun row(vararg chips: TextView) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            chips.forEachIndexed { i, c ->
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.marginStart = dp(ctx, 8)
                r.addView(c, lp)
            }
            box.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) })
        }
        val speakOn = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).getBoolean("speak", true)
        row(
            chip(ctx, "Summarize", 0xFF3A3560.toInt()) { hideMenu(); AgentEngine.summarizeScreen() },
            chip(ctx, if (speakOn) "Mute" else "Unmute", 0xFF3A3560.toInt()) {
                val p = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                val next = !p.getBoolean("speak", true)
                p.edit().putBoolean("speak", next).apply()
                if (!next) Voice.stop()
                Toast.makeText(ctx, if (next) "Speak on" else "Muted", Toast.LENGTH_SHORT).show()
                hideMenu()
            }
        )
        if (AgentEngine.running) {
            row(chip(ctx, "Stop", 0xFF5C2A2A.toInt()) { AgentEngine.stopAll(); hideMenu() },
                chip(ctx, "Close", 0xFF2A2838.toInt()) { hideMenu() })
        } else {
            row(chip(ctx, "Close", 0xFF2A2838.toInt()) { hideMenu() })
        }
        box.addView(TextView(ctx).apply {
            text = "Open Iris app"; textSize = 12f; setTextColor(0x88FFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 6), 0, 0)
            setOnClickListener {
                hideMenu()
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        val lp = WindowManager.LayoutParams(
            dp(ctx, 300), WindowManager.LayoutParams.WRAP_CONTENT, type(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { w.addView(box, lp); menu = box } catch (_: Exception) { }
    }

    private fun listenVoice(ctx: Context, et: EditText) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, "Speech recognition unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, "Listening…", Toast.LENGTH_SHORT).show()
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                ui.post { Toast.makeText(ctx, "Mic error ($error)", Toast.LENGTH_SHORT).show() }
                try { sr.destroy() } catch (_: Exception) { }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                ui.post {
                    if (!text.isNullOrEmpty()) { et.setText(text); hideMenu(); AgentEngine.send(text) }
                }
                try { sr.destroy() } catch (_: Exception) { }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        try { sr.startListening(i) } catch (e: Exception) {
            Toast.makeText(ctx, "Mic failed: ${e.message}", Toast.LENGTH_SHORT).show()
            try { sr.destroy() } catch (_: Exception) { }
        }
    }

    private fun hideMenu() {
        val w = wm ?: return
        menu?.let { try { w.removeView(it) } catch (_: Exception) { } }
        menu = null
        wakeBubble()
    }
}
