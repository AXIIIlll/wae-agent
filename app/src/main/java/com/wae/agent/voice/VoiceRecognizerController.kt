package com.wae.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 語音辨識控制器（單次辨識 + 語言/離線自動重試）。
 *
 * 實測（Samsung SM-A5560）：預設語言 zh-TW + 強制離線會回 ERROR_LANGUAGE_NOT_SUPPORTED（12）。
 * 因此這裡做「重試階梯」：zh-TW 離線 → zh-TW 線上 → 系統預設語言離線 → 系統預設語言線上，
 * 全部失敗才回報錯誤，並提示可改用手動輸入。
 *
 * 為什麼不自己抓麥克風：系統 SpeechRecognizer 由系統辨識服務處理音訊，
 * 不必讓 App 常駐前景服務使用麥克風（Android 14 限制）。
 */
class VoiceRecognizerController(context: Context) {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    /** 重試階梯：語言（null＝系統預設）＋是否偏好離線 */
    private data class Attempt(val language: String?, val preferOffline: Boolean)

    private val attempts = listOf(
        Attempt("zh-TW", false),
        Attempt("zh-TW", true),
        Attempt(null, false)
    )

    private var attemptIndex = 0
    private var cbPartial: (String) -> Unit = {}
    private var cbFinal: (String) -> Unit = {}
    private var cbError: (String, Int) -> Unit = { _, _ -> }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit,
        onError: (String, Int) -> Unit = { _, _ -> }
    ) {
        cbPartial = onPartial
        cbFinal = onFinal
        cbError = onError
        attemptIndex = 0
        if (!isAvailable()) {
            onError("此裝置沒有可用的語音辨識服務", -2)
            return
        }
        listen()
    }

    private fun listen() {
        stop()
        val attempt = attempts.getOrElse(attemptIndex) { attempts.last() }
        VoiceLog.event(
            "WAE_SYS_ASR",
            "START lang=" + (attempt.language ?: "default") +
                " offline=" + attempt.preferOffline +

                " attempt=" + attemptIndex
        )
        val r = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrElse {
            VoiceLog.event("WAE_SYS_ASR", "CREATE_FAILED attempt=" + attemptIndex)
            recognizer = null
            if (attemptIndex < attempts.lastIndex) {
                attemptIndex += 1
                listen()
            } else {
                cbError("無法建立語音辨識服務", -3)
            }
            return
        }
        recognizer = r
        var finished = false

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                VoiceLog.event("WAE_SYS_ASR", "READY attempt=" + attemptIndex)
            }

            override fun onBeginningOfSpeech() {
                VoiceLog.event("WAE_SYS_ASR", "SPEECH_BEGIN attempt=" + attemptIndex)
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                VoiceLog.event("WAE_SYS_ASR", "SPEECH_END attempt=" + attemptIndex)
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                VoiceLog.event("WAE_SYS_ASR", "ERROR code=" + error + " attempt=" + attemptIndex)
                // 語言/網路/伺服器類錯誤 → 換下一階重試
                if (error in RETRYABLE_ERRORS && attemptIndex < attempts.lastIndex) {
                    attemptIndex += 1
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                    listen()
                    return
                }
                cbError(errorText(error), error)
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                VoiceLog.event("WAE_SYS_ASR", "RESULTS")
                val text = firstResult(results)
                if (text.isNullOrBlank()) cbError("沒有辨識到語音", ERROR_NO_SPEECH_MARK)
                else cbFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (finished) return
                VoiceLog.event("WAE_SYS_ASR", "PARTIAL")
                val text = firstResult(partialResults)
                if (!text.isNullOrBlank()) cbPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            attempt.language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, attempt.preferOffline)
        }
        runCatching { r.startListening(intent) }
            .onFailure {
                if (attemptIndex < attempts.lastIndex) {
                    attemptIndex += 1
                    listen()
                } else {
                    cbError("無法啟動語音辨識：${it.message}", -3)
                }
            }
    }

    fun stop() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    fun destroy() = stop()

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "麥克風錯誤"
        SpeechRecognizer.ERROR_CLIENT -> "辨識用戶端錯誤"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "沒有麥克風權限"
        SpeechRecognizer.ERROR_NETWORK -> "網路錯誤（可改用手動輸入）"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路逾時"
        SpeechRecognizer.ERROR_NO_MATCH -> "沒有聽清楚，請再說一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識服務忙碌中"
        SpeechRecognizer.ERROR_SERVER -> "辨識伺服器錯誤"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "沒有偵測到語音"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "此裝置不支援中文語音辨識"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文語音辨識暫不可用"
        else -> "語音辨識失敗（$code）"
    }

    companion object {
        /** 空結果用的內部錯誤碼（模擬 ERROR_NO_SPEECH） */
        private const val ERROR_NO_SPEECH_MARK = 7

        /** 網路/語言/伺服器/忙線/用戶端類錯誤都換下一階再試 */
        private val RETRYABLE_ERRORS = setOf(1, 2, 4, 5, 8, 10, 11, 12, 13)
    }
}
