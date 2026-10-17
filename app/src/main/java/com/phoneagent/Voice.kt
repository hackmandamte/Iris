package com.phoneagent

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text helpers for chat display and TTS.
 * - [forDisplay] strips raw markdown noise and yields short readable paragraphs.
 * - [forSpeech] further normalises punctuation and symbols so TTS does not read them aloud.
 */
object TextPrep {
    /** Clean text for chat bubbles / island: short paragraphs, no raw # * ` noise. */
    fun forDisplay(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        // Strip code fences
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")
        // Inline code
        s = s.replace(Regex("`([^`]+)`"), "$1")
        // Headings
        s = s.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        // Bold / italic
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_]+)__"), "$1")
        s = s.replace(Regex("\\*([^*]+)\\*"), "$1")
        s = s.replace(Regex("_([^_]+)_"), "$1")
        // Links [text](url) -> text
        s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        // Bare bullets
        s = s.replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "• ")
        // Collapse excess whitespace / blank lines into short paragraphs
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Further clean for TTS so it does not read punctuation or markdown remnants. */
    fun forSpeech(raw: String): String {
        var s = forDisplay(raw)
        // Remove remaining markdown-ish symbols
        s = s.replace(Regex("[#*_`~|>]"), " ")
        // Normalise list bullets to "item"
        s = s.replace(Regex("•\\s*"), "")
        // Numbers with trailing dots (1. 2.) -> keep number
        s = s.replace(Regex("(\\d+)\\.\\s+"), "$1. ")
        // Ellipsis / dashes
        s = s.replace("…", "...")
        s = s.replace(Regex("[—–]"), ", ")
        // Collapse whitespace
        s = s.replace(Regex("\\s+"), " ").trim()
        // Soften trailing punctuation for smoother TTS (keep period/question/exclaim for prosody)
        // but strip isolated symbols
        s = s.replace(Regex("([.!?])\\1+"), "$1")
        return s.take(1500)
    }
}

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

    /**
     * Speak [text]. Applies [TextPrep.forSpeech] so TTS does not read markdown or noisy punctuation.
     * Returns false if TTS is unavailable.
     */
    fun say(ctx: Context, text: String): Boolean {
        val cleaned = TextPrep.forSpeech(text)
        if (cleaned.isBlank()) {
            return false
        }
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
        lastText = cleaned
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
