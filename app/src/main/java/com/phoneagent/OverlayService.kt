package com.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * Foreground service + face island.
 * Notification actions: Stop · Summarize · Mute · Reply · Open (only Open launches MainActivity).
 * Island tap = overlay panel (send / mute / summarize / stop) without leaving the current app.
 */
class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.phoneagent.ACTION_STOP"
        const val ACTION_SUMMARIZE = "com.phoneagent.ACTION_SUMMARIZE"
        const val ACTION_MUTE = "com.phoneagent.ACTION_MUTE"
        const val ACTION_REPLY = "com.phoneagent.ACTION_REPLY"
        const val ACTION_OPEN = "com.phoneagent.ACTION_OPEN"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var island: LinearLayout? = null
    private var face: FaceView? = null
    private var islandText: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLog: TextView? = null
    private var tail = ""
    private var speakWords: List<String> = emptyList()
    private var speakIndex = 0
    private val wordTick = object : Runnable {
        override fun run() {
            val tv = islandText ?: return
            if (speakIndex >= speakWords.size) return
            tv.text = speakWords[speakIndex]
            tv.visibility = View.VISIBLE
            val w = speakWords[speakIndex]
            speakIndex++
            val delay = (180 + w.length * 38).coerceIn(220, 520).toLong()
            ui.postDelayed(this, delay)
        }
    }

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
    private fun prefs() = getSharedPreferences("agent", MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        AgentEngine.logListeners.add(logL)
        AgentEngine.exprListeners.add(exprL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP", ACTION_STOP -> {
                AgentEngine.stopAll()
                removeViews()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SUMMARIZE -> {
                AgentEngine.summarizeScreen()
                refreshNotification()
                return START_STICKY
            }
            ACTION_MUTE -> {
                val on = !prefs().getBoolean("speak", true)
                prefs().edit().putBoolean("speak", on).apply()
                if (!on) Voice.stop()
                Toast.makeText(this, if (on) "Speak on" else "Muted", Toast.LENGTH_SHORT).show()
                refreshNotification()
                return START_STICKY
            }
            ACTION_REPLY -> {
                if (Settings.canDrawOverlays(this)) {
                    ui.post {
                        if (island == null && prefs().getBoolean("bubble", false)) showIsland()
                        showPanel()
                    }
                } else {
                    Toast.makeText(this, "Enable overlay permission for quick reply", Toast.LENGTH_LONG).show()
                }
                return START_STICKY
            }
            ACTION_OPEN -> {
                val open = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(open)
                return START_STICKY
            }
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("agent_fg", "Agent running", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIF_ID, buildNotification())
        val sp = prefs()
        if (sp.getBoolean("bubble", false) && Settings.canDrawOverlays(this)) showIsland()
        if (sp.getBoolean("quick_on", true)) {
            val svc = AgentAccessibilityService.instance
            if (svc != null) Quick.enable(svc, true)
            else if (Settings.canDrawOverlays(this)) Quick.enable(this, false)
        }
        if (Pc.devices(this).isNotEmpty()) Pc.startPolling(applicationContext)
        return START_STICKY
    }

    private fun pi(action: String): PendingIntent {
        val i = Intent(this, OverlayService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, action.hashCode(), i, flags)
    }

    private fun buildNotification(): Notification {
        val speakOn = prefs().getBoolean("speak", true)
        val muteLabel = if (speakOn) "Mute" else "Unmute"
        val openApp = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentPi = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return Notification.Builder(this, "agent_fg")
            .setSmallIcon(R.drawable.ic_stat_face)
            .setContentTitle("Iris is active")
            .setContentText(if (AgentEngine.running) "Working…" else "Ready")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Stop", pi(ACTION_STOP))
            .addAction(0, "Summarize", pi(ACTION_SUMMARIZE))
            .addAction(0, muteLabel, pi(ACTION_MUTE))
            .addAction(0, "Reply", pi(ACTION_REPLY))
            .addAction(0, "Open", pi(ACTION_OPEN))
            .build()
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        AgentEngine.logListeners.remove(logL)
        AgentEngine.exprListeners.remove(exprL)
        removeViews()
        Quick.disableIfHost(this)
        Pc.stopPolling()
        super.onDestroy()
    }

    private fun applyExpr(e: Expr) {
        val f = face ?: return
        ui.removeCallbacks(collapse)
        ui.removeCallbacks(wordTick)
        speakWords = emptyList()
        speakIndex = 0
        Voice.onWord = null
        f.mood = e.mood
        val tv = islandText
        f.invalidate()

        if (e.mood == Mood.SPEAKING && e.text.isNotEmpty()) {
            val words = e.text.take(220).split(Regex("\\s+")).filter { it.isNotEmpty() }
            speakWords = words
            speakIndex = 0
            tv?.visibility = View.VISIBLE
            Voice.onWord = { word ->
                ui.post {
                    islandText?.text = word
                    islandText?.visibility = View.VISIBLE
                }
            }
            ui.post(wordTick)
            ui.postDelayed(collapse, maxOf(3500L, e.text.length * 55L).coerceAtMost(14000L))
        } else if (e.text.isNotEmpty()) {
            tv?.text = e.text.take(220)
            tv?.visibility = View.VISIBLE
            when (e.mood) {
                Mood.REPORT, Mood.ERROR ->
                    ui.postDelayed(collapse, maxOf(3500L, e.text.length * 55L).coerceAtMost(12000L))
                else -> ui.postDelayed(collapse, 45000L)
            }
        } else {
            tv?.visibility = View.GONE
            if (e.mood != Mood.IDLE) ui.postDelayed(collapse, 45000L)
        }
        refreshNotification()
    }

    private fun showIsland() {
        if (island != null) return
        if (!Settings.canDrawOverlays(this)) {
            prefs().edit().putBoolean("bubble", false).apply()
            return
        }
        try {
            val prefs = prefs()
            val f = FaceView(this)
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                maxWidth = dp(200)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(2), dp(14), dp(2))
                visibility = View.GONE
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(18).toFloat() }
            }
            box.addView(f)
            box.addView(tv)
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
            prefs().edit().putBoolean("bubble", false).apply()
            island = null
            face = null
            islandText = null
        }
    }

    private fun onIslandTap() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        if (panel != null) return
        if (!Settings.canDrawOverlays(this)) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xF2202124.toInt()); cornerRadius = dp(18).toFloat() }
        }
        val lg = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 8
            text = tail
        }
        val et = EditText(this).apply {
            hint = "Message Iris"
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { hidePanel(); AgentEngine.send(t) }
            }
        })
        row.addView(Button(this).apply {
            text = "Summarize"
            setOnClickListener { hidePanel(); AgentEngine.summarizeScreen() }
        })
        val speakOn = prefs().getBoolean("speak", true)
        row.addView(Button(this).apply {
            text = if (speakOn) "Mute" else "Unmute"
            setOnClickListener {
                val next = !prefs().getBoolean("speak", true)
                prefs().edit().putBoolean("speak", next).apply()
                if (!next) Voice.stop()
                Toast.makeText(this@OverlayService, if (next) "Speak on" else "Muted", Toast.LENGTH_SHORT).show()
                hidePanel()
                refreshNotification()
            }
        })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (AgentEngine.running) {
            row2.addView(Button(this).apply {
                text = "Stop all"
                setOnClickListener {
                    AgentEngine.stopAll()
                    hidePanel()
                    refreshNotification()
                }
            })
        }
        row2.addView(Button(this).apply {
            text = "Close"
            setOnClickListener { hidePanel() }
        })
        box.addView(lg)
        box.addView(et)
        box.addView(row)
        box.addView(row2)

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
        ui.removeCallbacks(wordTick)
        Voice.onWord = null
        island?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        island = null
        face = null
        islandText = null
    }
}
