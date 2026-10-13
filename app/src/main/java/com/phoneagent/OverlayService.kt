package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Foreground service. Keeps the agent alive and draws the "face island":
 * a black pill at the top of the screen, sized like the iPhone Dynamic Island,
 * with a face that reacts to what the agent does.
 * Tap = open task box (with Stop all while tasks run). Long-press = summarize my screen. Drag = reposition over your camera.
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var island: LinearLayout? = null
    private var face: FaceView? = null
    private var islandText: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLog: TextView? = null
    private var tail = ""

    private val collapse = object : Runnable {
        override fun run() {
            if (Voice.speaking) { ui.postDelayed(this, 700); return }
            face?.mood = Mood.IDLE
            islandText?.visibility = View.GONE
        }
    }

    private val logL: (Int, String) -> Unit = { _, s ->
        ui.post {
            tail = (tail + s + "\n\n").takeLast(700)
            panelLog?.text = tail
            if (s.startsWith("🤖") && island == null && panel == null) {
                Toast.makeText(this, s.take(250), Toast.LENGTH_LONG).show()
            }
        }
    }
    private val exprL: (Expr) -> Unit = { e -> ui.post { applyExpr(e) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        AgentEngine.logListeners.add(logL)
        AgentEngine.exprListeners.add(exprL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            removeViews()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent_fg", "Agent running", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "agent_fg")
            .setSmallIcon(R.drawable.ic_stat_face)
            .setContentTitle("Iris is active").build())
        val sp = getSharedPreferences("agent", MODE_PRIVATE)
        if (sp.getBoolean("bubble", false) && Settings.canDrawOverlays(this)) showIsland()
        if (sp.getBoolean("quick_on", true)) {
            val svc = AgentAccessibilityService.instance
            if (svc != null) Quick.enable(svc, true)
            else if (Settings.canDrawOverlays(this)) Quick.enable(this, false)
        }
        if (Pc.devices(this).isNotEmpty()) Pc.startPolling(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.exprListeners.remove(exprL)
        removeViews()
        Quick.disableIfHost(this)
        Pc.stopPolling()
        super.onDestroy()
    }

    // ---- expressions ----
    private fun applyExpr(e: Expr) {
        val f = face ?: return
        ui.removeCallbacks(collapse)
        f.mood = e.mood
        val tv = islandText
        if (e.text.isNotEmpty()) {
            tv?.text = e.text.take(220)
            tv?.visibility = View.VISIBLE
        } else {
            tv?.visibility = View.GONE
        }
        f.invalidate()
        when (e.mood) {
            Mood.IDLE -> {}
            Mood.REPORT, Mood.SPEAKING, Mood.ERROR ->
                ui.postDelayed(collapse, maxOf(3500L, e.text.length * 55L).coerceAtMost(12000L))
            else -> ui.postDelayed(collapse, 45000L) // fail-safe
        }
    }

    // ---- the island (Dynamic Island proportions) ----
    private fun showIsland() {
        if (island != null) return
        if (!Settings.canDrawOverlays(this)) {
            getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("bubble", false).apply()
            return
        }
        try {
            val prefs = getSharedPreferences("agent", MODE_PRIVATE)
            val f = FaceView(this)
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                maxWidth = dp(200)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(2), dp(14), dp(2))
                visibility = View.GONE
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Pill radius ≈ half of 37 dp height → Dynamic Island look
                background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(18).toFloat() }
            }
            box.addView(f)
            box.addView(tv)
            // Collapsed Dynamic Island footprint (~126×37 pt)
            box.minimumWidth = dp(126)
            box.minimumHeight = dp(37)
            box.setPadding(dp(8), 0, dp(8), 0)

            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = prefs.getInt("island_x", 0)
                y = prefs.getInt("island_y", dp(6))
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            box.setOnTouchListener(object : View.OnTouchListener {
                var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
                var moved = false; var longFired = false
                val longRun = Runnable {
                    if (!moved) {
                        longFired = true
                        box.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        hidePanel()
                        AgentEngine.summarizeScreen()
                    }
                }
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                            moved = false; longFired = false
                            ui.postDelayed(longRun, 650)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - tx).toInt()
                            val dy = (e.rawY - ty).toInt()
                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) { moved = true; ui.removeCallbacks(longRun) }
                            if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(v, lp) }
                        }
                        MotionEvent.ACTION_UP -> {
                            ui.removeCallbacks(longRun)
                            if (moved) {
                                getSharedPreferences("agent", MODE_PRIVATE).edit()
                                    .putInt("island_x", lp.x).putInt("island_y", lp.y).apply()
                            } else if (!longFired) {
                                onIslandTap()
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longRun)
                    }
                    return true
                }
            })
            wm.addView(box, lp)
            island = box
            face = f
            islandText = tv
        } catch (e: Exception) {
            getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("bubble", false).apply()
            island = null
            face = null
            islandText = null
        }
    }

    private fun onIslandTap() {
        if (panel == null) showPanel() else hidePanel()
    }

    // ---- task box, drops down under the island ----
    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xF2202124.toInt()); cornerRadius = dp(18).toFloat() }
        }
        val lg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 8; text = tail }
        val et = EditText(this).apply {
            hint = "Tell the agent what to do"
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { hidePanel(); AgentEngine.send(t) }
            }
        }
        val sum = Button(this).apply {
            text = "Summarize screen"
            setOnClickListener { hidePanel(); AgentEngine.summarizeScreen() }
        }
        val close = Button(this).apply { text = "Close"; setOnClickListener { hidePanel() } }
        row.addView(send); row.addView(sum)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (AgentEngine.running) {
            row2.addView(Button(this).apply {
                text = "Stop all"
                setOnClickListener { AgentEngine.stopAll(); hidePanel() }
            })
        }
        row2.addView(close)
        box.addView(lg); box.addView(et); box.addView(row); box.addView(row2)

        val lp = WindowManager.LayoutParams(
            dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            wm.addView(box, lp)
            panel = box
            panelLog = lg
        } catch (e: Exception) {
            panel = null
            panelLog = null
        }
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null
        panelLog = null
    }

    private fun removeViews() {
        hidePanel()
        ui.removeCallbacks(collapse)
        island?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        island = null
        face = null
        islandText = null
    }
}
