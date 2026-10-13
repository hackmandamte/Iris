package com.phoneagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Face in the island: two eyes with a camera-sized gap in the centre so the
 * front camera / punch-hole sits between them when the pill is aligned over the notch.
 * Mood drift, mouth and expression logic are unchanged — only spacing + blink visibility.
 */
class FaceView(ctx: Context) : View(ctx) {
    @Volatile var mood = Mood.IDLE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private val r = RectF()
    private fun dp(v: Float) = v * d

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // Compact footprint that matches a collapsed Dynamic Island (~126×37)
        setMeasuredDimension(dp(96f).toInt(), dp(36f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = when (mood) {
            Mood.THINKING -> 0xFF8AB4FF.toInt()
            Mood.READING -> 0xFF67E8F9.toInt()
            Mood.REPORT -> 0xFF86EFAC.toInt()
            Mood.ERROR -> 0xFFFF8A80.toInt()
            else -> 0xFFEFF4FF.toInt()
        }

        var gx = 0f
        var gy = 0f
        var open = 1f
        // Mood motion formulas kept identical to previous versions
        when (mood) {
            Mood.IDLE -> {
                gx = (sin(t / 1300.0) * dp(2.5f) + sin(t / 430.0) * dp(0.8f)).toFloat()
                gy = (sin(t / 1900.0) * dp(1f)).toFloat()
            }
            Mood.THINKING -> {
                gx = (cos(t / 520.0) * dp(3f)).toFloat()
                gy = (sin(t / 520.0) * dp(2f) - dp(1.2f)).toFloat()
            }
            Mood.ACTING -> { open = 0.8f; gx = (sin(t / 900.0) * dp(1f)).toFloat() }
            Mood.READING -> { gx = (sin(t / 240.0) * dp(5f)).toFloat(); open = 0.45f }
            Mood.SPEAKING -> { gx = (sin(t / 1500.0) * dp(1.2f)).toFloat() }
            Mood.REPORT -> {}
            Mood.ERROR -> { gx = (sin(t / 70.0) * dp(0.8f)).toFloat() }
        }
        // Blink: full close for ~220 ms every ~3.8 s so it is obvious on the small island
        if (mood == Mood.IDLE || mood == Mood.THINKING || mood == Mood.ACTING || mood == Mood.SPEAKING) {
            val ph = t % 3800
            if (ph < 220) {
                // triangle that bottoms out near 0 for ~80 ms in the middle
                val mid = abs(ph - 110f) / 110f
                open *= mid.coerceIn(0.01f, 1f)
            }
        }

        // Eye centres: gap sized so a typical punch-hole / camera (~10–16 dp) fills the space between the eyes
        val ey = h * 0.42f + gy
        val half = dp(18f)   // centre-to-centre ≈ 36 dp → clear gap ≈ 14–16 dp after eye width
        val rx = dp(5.8f)
        val ry = dp(7.2f) * open
        for (s in intArrayOf(-1, 1)) {
            val cx = w / 2f + s * half + gx
            when (mood) {
                Mood.REPORT -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = dp(2.6f)
                    p.strokeCap = Paint.Cap.ROUND
                    r.set(cx - rx, ey - dp(5f), cx + rx, ey + dp(5f))
                    c.drawArc(r, 200f, 140f, false, p)
                    p.style = Paint.Style.FILL
                }
                Mood.ERROR -> {
                    r.set(cx - rx, ey - ry, cx + rx, ey + ry)
                    c.drawOval(r, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = dp(2.2f)
                    p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(cx + s * rx, ey - ry - dp(2f), cx - s * rx, ey - ry - dp(6f), p)
                    p.style = Paint.Style.FILL
                }
                else -> {
                    r.set(cx - rx, ey - ry, cx + rx, ey + ry)
                    c.drawOval(r, p)
                }
            }
        }

        val mx = w / 2f
        val my = h * 0.82f
        p.strokeWidth = dp(2.4f)
        p.strokeCap = Paint.Cap.ROUND
        if (Voice.speaking) {
            val a = (0.3 + 0.7 * abs(sin(t / 110.0))).toFloat()
            p.style = Paint.Style.FILL
            r.set(mx - dp(6f), my - dp(4f) * a, mx + dp(6f), my + dp(4f) * a)
            c.drawOval(r, p)
        } else {
            p.style = Paint.Style.STROKE
            when (mood) {
                Mood.REPORT -> {
                    r.set(mx - dp(9f), my - dp(8f), mx + dp(9f), my + dp(3f))
                    c.drawArc(r, 15f, 150f, false, p)
                }
                Mood.ERROR -> {
                    r.set(mx - dp(6f), my - dp(1f), mx + dp(6f), my + dp(7f))
                    c.drawArc(r, 200f, 140f, false, p)
                }
                Mood.READING, Mood.THINKING -> c.drawLine(mx - dp(4f), my, mx + dp(4f), my, p)
                else -> {
                    r.set(mx - dp(5f), my - dp(5f), mx + dp(5f), my + dp(1f))
                    c.drawArc(r, 25f, 130f, false, p)
                }
            }
            p.style = Paint.Style.FILL
        }
        postInvalidateDelayed(32)
    }
}
