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

/** Small animated face drawn on the island. Moods drive eye and mouth behaviour. */
class FaceView(ctx: Context) : View(ctx) {
    @Volatile var mood = Mood.IDLE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private val r = RectF()
    private fun dp(v: Float) = v * d

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // Sized to match iPhone Dynamic Island collapsed proportions (~126×37 pt)
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
        when (mood) {
            Mood.IDLE -> {
                gx = (sin(t / 1300.0) * dp(3f) + sin(t / 430.0) * dp(1f)).toFloat()
                gy = (sin(t / 1900.0) * dp(1f)).toFloat()
            }
            Mood.THINKING -> {
                gx = (cos(t / 520.0) * dp(3.5f)).toFloat()
                gy = (sin(t / 520.0) * dp(2f) - dp(1.5f)).toFloat()
            }
            Mood.ACTING -> { open = 0.8f; gx = (sin(t / 900.0) * dp(1f)).toFloat() }
            Mood.READING -> { gx = (sin(t / 240.0) * dp(7f)).toFloat(); open = 0.45f }
            Mood.SPEAKING -> { gx = (sin(t / 1500.0) * dp(1.5f)).toFloat() }
            Mood.REPORT -> {}
            Mood.ERROR -> { gx = (sin(t / 70.0) * dp(0.8f)).toFloat() }
        }
        if (mood == Mood.IDLE || mood == Mood.THINKING || mood == Mood.ACTING || mood == Mood.SPEAKING) {
            val ph = t % 4300
            if (ph < 140) open *= (abs(ph - 70) / 70f).coerceIn(0.1f, 1f)
        }

        val ey = h * 0.40f + gy
        val half = dp(17f)
        val rx = dp(5.5f)
        val ry = dp(7f) * open
        for (s in intArrayOf(-1, 1)) {
            val cx = w / 2f + s * half + gx
            when (mood) {
                Mood.REPORT -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = dp(2.8f)
                    p.strokeCap = Paint.Cap.ROUND
                    r.set(cx - rx, ey - dp(5f), cx + rx, ey + dp(6f))
                    c.drawArc(r, 200f, 140f, false, p)
                    p.style = Paint.Style.FILL
                }
                Mood.ERROR -> {
                    r.set(cx - rx, ey - ry, cx + rx, ey + ry)
                    c.drawOval(r, p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = dp(2.2f)
                    p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(cx - dp(3f), ey - ry - dp(2f), cx + dp(3f), ey - ry - dp(5f), p)
                    p.style = Paint.Style.FILL
                }
                else -> {
                    r.set(cx - rx, ey - ry, cx + rx, ey + ry)
                    c.drawOval(r, p)
                }
            }
        }

        // mouth
        p.color = when (mood) {
            Mood.ERROR -> 0xFFFF8A80.toInt()
            Mood.REPORT -> 0xFF86EFAC.toInt()
            else -> 0xFFEFF4FF.toInt()
        }
        val my = h * 0.72f
        when (mood) {
            Mood.SPEAKING -> {
                val openM = (sin(t / 90.0) * 0.5 + 0.5).toFloat()
                r.set(w / 2f - dp(8f), my - dp(2f) * openM, w / 2f + dp(8f), my + dp(5f) * openM)
                c.drawOval(r, p)
            }
            Mood.ERROR -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(2f)
                p.strokeCap = Paint.Cap.ROUND
                r.set(w / 2f - dp(10f), my - dp(2f), w / 2f + dp(10f), my + dp(8f))
                c.drawArc(r, 20f, 140f, false, p)
                p.style = Paint.Style.FILL
            }
            Mood.REPORT -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(2.4f)
                p.strokeCap = Paint.Cap.ROUND
                r.set(w / 2f - dp(11f), my - dp(6f), w / 2f + dp(11f), my + dp(6f))
                c.drawArc(r, 20f, 140f, false, p)
                p.style = Paint.Style.FILL
            }
            Mood.THINKING, Mood.ACTING -> {
                p.strokeWidth = dp(2.2f)
                p.style = Paint.Style.STROKE
                p.strokeCap = Paint.Cap.ROUND
                c.drawLine(w / 2f - dp(7f), my, w / 2f + dp(7f), my, p)
                p.style = Paint.Style.FILL
            }
            else -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(2f)
                p.strokeCap = Paint.Cap.ROUND
                r.set(w / 2f - dp(8f), my - dp(3f), w / 2f + dp(8f), my + dp(5f))
                c.drawArc(r, 20f, 140f, false, p)
                p.style = Paint.Style.FILL
            }
        }
    }
}
