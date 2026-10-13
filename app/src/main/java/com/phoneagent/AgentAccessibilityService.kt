package com.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
    }

    private var upDown = false
    private var downDown = false
    private var chordFired = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (getSharedPreferences("agent", MODE_PRIVATE).getBoolean("quick_on", true)) Quick.enable(this, true)
    }

    override fun onDestroy() {
        instance = null
        Quick.disableIfHost(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)
            return super.onKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> upDown = down
            KeyEvent.KEYCODE_VOLUME_DOWN -> downDown = down
        }
        if (upDown && downDown) {
            if (!chordFired) {
                chordFired = true
                Quick.summon()
            }
            return true
        }
        if (!upDown && !downDown) chordFired = false
        return super.onKeyEvent(event)
    }

    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    fun nodesByText(text: String): List<AccessibilityNodeInfo> {
        val r = root() ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString() ?: ""
            val d = n.contentDescription?.toString() ?: ""
            if (t.contains(text, true) || d.contains(text, true)) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(r)
        return out
    }

    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun perform(action: Int): Boolean = performGlobalAction(action)

    fun captureToLibrary(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "ERROR: screenshots need Android 11 or newer"
        val latch = CountDownLatch(1)
        val result = AtomicReference("ERROR: timeout")
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, ctx.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hw = screenshot.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            if (bmp == null) result.set("ERROR: could not decode screenshot")
                            else {
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
