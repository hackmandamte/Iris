package com.phoneagent

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Text-to-speech. Replies from parallel tasks queue up; `speaking` drives the talking mouth. */
object Voice {
    private val pending = AtomicInteger(0)
    val speaking: Boolean get() = pending.get() > 0
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    /** Called on the main thread with the current word while speaking (best-effort). */
    @Volatile var onWord: ((String) -> Unit)? = null
    @Volatile var onSpeakDone: (() -> Unit)? = null

    private fun done() {
        pending.updateAndGet { if (it > 0) it - 1 else 0 }
        if (pending.get() == 0) onSpeakDone?.let { cb ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb() }
        }
    }

    fun say(ctx: Context, text: String): Boolean {
        pending.incrementAndGet()
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { st ->
                ready = (st == TextToSpeech.SUCCESS)
                if (ready) {
                    tts?.setLanguage(Locale.getDefault())
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { done() }
                        override fun onError(utteranceId: String?) { done() }

                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            if (Build.VERSION.SDK_INT < 26) return
                            val full = lastText ?: return
                            if (start < 0 || end > full.length || start >= end) return
                            val word = full.substring(start, end).trim()
                            if (word.isNotEmpty()) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onWord?.invoke(word)
                                }
                            }
                        }
                    })
                }
            }
        }
        var w = 0
        while (!ready && w < 30) { Thread.sleep(100); w++ }
        if (!ready) { done(); return false }
        lastText = text.take(1500)
        tts?.speak(lastText, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
        return true
    }

    @Volatile private var lastText: String? = null

    fun stop() {
        pending.set(0)
        tts?.stop()
        onWord = null
    }
}
