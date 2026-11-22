package com.wae.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 極簡語音回饋（P1 #16）：只在「開始」與「完成/失敗」各講一句，避免吵。
 * 使用系統 TTS（離線語音包由 Google TTS 提供；沒有中文語音包時自動靜音，不影響功能）。
 */
object VoiceFeedback {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var initializing = false

    private val main = Handler(Looper.getMainLooper())

    private fun ensureInit(context: Context) {
        if (ready || initializing) return
        initializing = true
        val app = context.applicationContext
        main.post {
            runCatching {
                tts = TextToSpeech(app) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    runCatching {
                        tts?.language = Locale.TAIWAN
                    }
                }
            }
        }
    }

    fun speak(context: Context, text: String) {
        ensureInit(context)
        main.post {
            runCatching {
                if (!ready) return@runCatching
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wae_" + System.currentTimeMillis())
            }
        }
    }

    /** 任務完成/失敗（保持極短） */
    fun speakResult(context: Context, success: Boolean) {
        speak(context, if (success) "完成" else "失敗了")
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
