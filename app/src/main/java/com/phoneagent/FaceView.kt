package com.phoneagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Small animated face drawn on the island. Moods drive eye and mouth behaviour. */
class FaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mood: Mood = Mood.IDLE
        set(v) { field = v; invalidate() }

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3.5f; strokeCap = Paint.Cap.ROUND
    }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }

    private var lastBlink = 0L
    private var blinking = false
    private var blinkT = 0L
    private var lookX = 0f
    private var lookY = 0f
    private var lookUntil = 0L
    private var talkPhase = 0f

    init {
        // ~16 ms frame cadence when attached
        post(object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (mood == Mood.IDLE || mood == Mood.THINKING) {
                    if (now > lookUntil) {
                        lookX = Random.nextFloat() * 2f - 1f
                        lookY = Random.nextFloat() * 1.2f - 0.4f
                        lookUntil = now + Random.nextLong(800, 2800)
                    }
                    if (!blinking && now - lastBlink > Random.nextLong(2200, 5200)) {
                        blinking = true; blinkT = now; lastBlink = now
                    }
                    if (blinking && now - blinkT > 140) blinking = false
                }
                if (mood == Mood.SPEAKING) talkPhase = (talkPhase + 0.35f) % 6.28f
                invalidate()
                postDelayed(this, 16)
            }
        })
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (40 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val r = w * 0.18f

        // eyes
        val eyeY = cy - h * 0.06f
        val lx = cx - w * 0.18f + lookX * r * 0.35f
        val rx = cx + w * 0.18f + lookX * r * 0.35f
        val ey = eyeY + lookY * r * 0.35f

        when (mood) {
            Mood.READING -> {
                // squint
                c.drawOval(RectF(lx - r, ey - r * 0.45f, lx + r, ey + r * 0.45f), eyePaint)
                c.drawOval(RectF(rx - r, ey - r * 0.45f, rx + r, ey + r * 0.45f), eyePaint)
            }
            Mood.ERROR -> {
                // worried: smaller pupils high
                c.drawCircle(lx, ey - r * 0.2f, r * 0.7f, eyePaint)
                c.drawCircle(rx, ey - r * 0.2f, r * 0.7f, eyePaint)
            }
            Mood.REPORT -> {
                // happy crescents
                mouthPaint.strokeWidth = 3f
                c.drawArc(RectF(lx - r, ey - r * 0.3f, lx + r, ey + r), 200f, 140f, false, mouthPaint)
                c.drawArc(RectF(rx - r, ey - r * 0.3f, rx + r, ey + r), 200f, 140f, false, mouthPaint)
            }
            else -> {
                c.drawCircle(lx, ey, r, eyePaint)
                c.drawCircle(rx, ey, r, eyePaint)
            }
        }

        if (blinking && mood != Mood.REPORT) {
            c.drawRect(lx - r * 1.1f, ey - r * 1.2f, lx + r * 1.1f, ey + r * 0.2f, lidPaint)
            c.drawRect(rx - r * 1.1f, ey - r * 1.2f, rx + r * 1.1f, ey + r * 0.2f, lidPaint)
        }

        // mouth
        val my = cy + h * 0.22f
        when (mood) {
            Mood.SPEAKING -> {
                val open = (sin(talkPhase.toDouble()) * 0.5 + 0.5).toFloat() * r * 0.9f + r * 0.2f
                c.drawOval(RectF(cx - r * 0.9f, my - open, cx + r * 0.9f, my + open * 0.6f), eyePaint)
            }
            Mood.ERROR -> {
                // wavy worried mouth
                val path = Path()
                path.moveTo(cx - r * 1.2f, my)
                path.quadTo(cx - r * 0.4f, my + r * 0.5f, cx, my)
                path.quadTo(cx + r * 0.4f, my - r * 0.4f, cx + r * 1.2f, my)
                c.drawPath(path, mouthPaint)
            }
            Mood.REPORT -> {
                c.drawArc(RectF(cx - r * 1.3f, my - r * 0.8f, cx + r * 1.3f, my + r * 1.2f), 20f, 140f, false, mouthPaint)
            }
            Mood.THINKING, Mood.ACTING -> {
                c.drawLine(cx - r * 0.8f, my, cx + r * 0.8f, my, mouthPaint)
            }
            else -> {
                c.drawArc(RectF(cx - r, my - r * 0.3f, cx + r, my + r * 0.8f), 20f, 140f, false, mouthPaint)
            }
        }
    }
}
