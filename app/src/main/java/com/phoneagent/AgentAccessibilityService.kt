package com.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var upDown = false
    private var downDown = false
    private var chordFired = false
    private val chordRun = Runnable {
        if (upDown && downDown) { chordFired = true; Quick.summon() }
    }

    override fun onServiceConnected() {
        instance = this
        if (getSharedPreferences("agent", MODE_PRIVATE).getBoolean("quick_on", true)) Quick.enable(this, true)
    }

    // Both volume keys held for ~0.6 s summons Iris. Normal volume presses pass through untouched.
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val before = upDown && downDown
        if (code == KeyEvent.KEYCODE_VOLUME_UP) upDown = down else downDown = down
        val both = upDown && downDown
        if (both && !before) main.postDelayed(chordRun, 600)
        if (!both) main.removeCallbacks(chordRun)
        if (!upDown && !downDown) { val c = chordFired; chordFired = false; return c }
        return chordFired
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Quick.disableIfHost(this)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "ERROR: no readable window (screen locked or app blocks access)"
        val sb = StringBuilder("package=${root.packageName}\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= 200) return
            if (n.isVisibleToUser) {
                val label = listOfNotNull(
                    n.text?.toString()?.take(80),
                    n.contentDescription?.toString()?.take(80)
                ).filter { it.isNotBlank() }.distinct().joinToString(" | ")
                if (label.isNotEmpty() || n.isClickable || n.isEditable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    sb.append("- ").append(n.className?.toString()?.substringAfterLast('.'))
                    if (label.isNotEmpty()) sb.append(" \"").append(label).append("\"")
                    if (n.isClickable) sb.append(" [click]")
                    if (n.isEditable) sb.append(" [edit]")
                    if (n.isScrollable) sb.append(" [scroll]")
                    sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    fun tapText(text: String): String {
        val root = rootInActiveWindow ?: return "ERROR: no readable window"
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "clicked '$text'"
        }
        val first = nodes.firstOrNull() ?: return "ERROR: no element with text '$text'. Call screen_read."
        val r = Rect()
        first.getBoundsInScreen(r)
        return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun tapXY(x: Float, y: Float): String {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 60)
    }

    fun typeText(text: String, append: Boolean): String {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "ERROR: no focused text field. Tap the field first."
        val base = if (append) (node.text?.toString() ?: "") else ""
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed" else "ERROR: could not set text"
    }

    fun scroll(dir: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir.lowercase()) {
            "down" -> { p.moveTo(w / 2, h * 0.75f); p.lineTo(w / 2, h * 0.25f) }
            "up" -> { p.moveTo(w / 2, h * 0.25f); p.lineTo(w / 2, h * 0.75f) }
            "left" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            "right" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            else -> return "ERROR: direction must be up, down, left or right"
        }
        return gesture(p, 350)
    }

    fun press(key: String): String {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "ERROR: unknown key '$key'"
        }
        return if (performGlobalAction(action)) "pressed $key" else "ERROR: press failed"
    }

    private fun gesture(path: Path, durationMs: Long): String {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!sent) return "ERROR: gesture rejected"
        latch.await(4, TimeUnit.SECONDS)
        return if (ok.get()) "ok" else "ERROR: gesture cancelled"
    }

    /** Take a screenshot into the Media library (API 30+). Returns id or ERROR: message. */
    fun captureToLibrary(ctx: android.content.Context): String {
        if (Build.VERSION.SDK_INT < 30) return "ERROR: screenshots need Android 11 or newer"
        val latch = CountDownLatch(1)
        val result = AtomicReference("ERROR: timeout")
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, ctx.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val color = screenshot.colorSpace
                            val bmp = Bitmap.wrapHardwareBuffer(hw, color)?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            if (bmp == null) {
                                result.set("ERROR: could not decode screenshot")
                            } else {
                                val id = Media.saveBitmap(ctx, bmp, "screenshot")
                                bmp.recycle()
                                result.set(id)
                            }
                        } catch (e: Exception) {
                            result.set("ERROR: ${e.message}")
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        result.set("ERROR: screenshot failed ($errorCode)")
                        latch.countDown()
                    }
                })
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        }
        latch.await(8, TimeUnit.SECONDS)
        return result.get()
    }
}
