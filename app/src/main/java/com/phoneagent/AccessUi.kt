package com.phoneagent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Side bubble + quick menu opened by volume chord or the bubble itself. */
object Quick {
    private var host: Context? = null
    private var accessibilityHost = false
    private var bubble: View? = null
    private var menu: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun enable(ctx: Context, fromAccessibility: Boolean) {
        host = ctx.applicationContext
        accessibilityHost = fromAccessibility
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ui.post { showBubble() }
    }

    fun disable() {
        ui.post {
            removeBubble()
            hideMenu()
            host = null
            wm = null
        }
    }

    fun disableIfHost(ctx: Context) {
        if (host === ctx.applicationContext || (host == null)) disable()
    }

    fun summon(): Boolean {
        val h = host ?: return false
        ui.post { showMenu(h) }
        return true
    }

    private fun type(): Int =
        if (accessibilityHost) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showBubble() {
        val ctx = host ?: return
        val w = wm ?: return
        if (bubble != null) return
        val size = dp(ctx, 48)
        val v = TextView(ctx).apply {
            text = "◉"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xE00A84FF.toInt())
                cornerRadius = size / 2f
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(ctx, 120)
        }
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                        if (moved) { lp.x = sx + dx; lp.y = sy + dy; w.updateViewLayout(view, lp) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) showMenu(ctx)
                        else {
                            // snap to edge
                            val sw = ctx.resources.displayMetrics.widthPixels
                            lp.x = if (lp.x + size / 2 < sw / 2) 0 else sw - size
                            w.updateViewLayout(view, lp)
                        }
                    }
                }
                return true
            }
        })
        try {
            w.addView(v, lp)
            bubble = v
        } catch (e: Exception) { }
    }

    private fun removeBubble() {
        val w = wm ?: return
        bubble?.let { try { w.removeView(it) } catch (e: Exception) { } }
        bubble = null
    }

    private fun showMenu(ctx: Context) {
        if (menu != null) return
        val w = wm ?: return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            background = GradientDrawable().apply {
                setColor(0xF2202124.toInt())
                cornerRadius = dp(ctx, 16).toFloat()
            }
        }
        fun addBtn(label: String, action: () -> Unit) {
            box.addView(Button(ctx).apply {
                text = label
                setOnClickListener { hideMenu(); action() }
            })
        }
        addBtn("Speak") {
            // voice entry – open main and rely on mic, or toast
            Toast.makeText(ctx, "Open Iris and tap the mic", Toast.LENGTH_SHORT).show()
            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        addBtn("Type") {
            // simple prompt
            val et = EditText(ctx).apply { hint = "Task for Iris"; setTextColor(Color.WHITE); setHintTextColor(0x99FFFFFF.toInt()) }
            val dlg = android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Iris")
                .setView(et)
                .setPositiveButton("Send") { _, _ ->
                    val t = et.text.toString().trim()
                    if (t.isNotEmpty()) AgentEngine.send(t)
                }
                .setNegativeButton("Cancel", null)
                .create()
            dlg.window?.setType(type())
            try { dlg.show() } catch (e: Exception) {
                ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        addBtn("Tasks") {
            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val lp = WindowManager.LayoutParams(
            dp(ctx, 280), WindowManager.LayoutParams.WRAP_CONTENT, type(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            w.addView(box, lp)
            menu = box
        } catch (e: Exception) { }
    }

    private fun hideMenu() {
        val w = wm ?: return
        menu?.let { try { w.removeView(it) } catch (e: Exception) { } }
        menu = null
    }
}
