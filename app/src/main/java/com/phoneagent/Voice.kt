package com.phoneagent

import android.content.Context
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

    private fun done() { pending.updateAndGet { if (it > 0) it - 1 else 0 } }

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
                    })
                }
            }
        }
        var w = 0
        while (!ready && w < 30) { Thread.sleep(100); w++ }
        if (!ready) { done(); return false }
        tts?.speak(text.take(1500), TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
        return true
    }

    fun stop() {
        pending.set(0)
        tts?.stop()
    }
}
