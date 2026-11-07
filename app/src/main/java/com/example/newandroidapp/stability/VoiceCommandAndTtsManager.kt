package com.example.newandroidapp.stability

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 升級第 4 項：一鍵語音下令 (Speech-to-Action) 與全程自然語音回報 (TTS) 管理器
 *
 * 1. 所有 SpeechRecognizer 與 TextToSpeech 操作強制在主執行緒 (Main Looper) 執行，防止跨執行緒崩潰。
 * 2. 啟動麥克風聆聽前自動暫停 TTS 播報，避免系統將自己的語音播報誤判為使用者指令。
 * 3. 自動過濾 Emoji、Markdown 與控制符號，讓繁體中文語音播報自然流暢。
 */
object VoiceCommandAndTtsManager {

    private val mainHandler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    @Volatile
    var isTtsEnabled: Boolean = true

    @Volatile
    var isListening: Boolean = false
        private set

    private var tts: TextToSpeech? = null

    @Volatile
    private var isTtsReady: Boolean = false

    @Volatile
    var isAudioFocusDuckingHeld: Boolean = false
        private set

    private var activeAudioFocusRequest: AudioFocusRequest? = null
    private var lastAudioManagerRef: AudioManager? = null

    private var pendingUtterance: String? = null
    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * 音訊焦點壓制（Audio Ducking）：
     * 在 TTS 語音合成播報前申請系統音訊焦點 (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)，
     * 自動壓低背景正在播放的媒體音量。
     */
    @Synchronized
    fun requestTransientDuckingAudioFocus(context: Context?): Boolean {
        isAudioFocusDuckingHeld = true
        if (context == null) return true
        return runCatching {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@runCatching true
            lastAudioManagerRef = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = activeAudioFocusRequest ?: AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                    .also { activeAudioFocusRequest = it }
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(true)
    }

    /**
     * 播報完畢或中斷後釋放音訊焦點，讓背景媒體恢復正常音量
     */
    @Synchronized
    fun abandonTransientDuckingAudioFocus(context: Context? = null) {
        isAudioFocusDuckingHeld = false
        runCatching {
            val am = (context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?: lastAudioManagerRef
                ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeAudioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    /**
     * 初始化繁體中文 TTS 引擎（可在 Application / Activity / Service 啟動時呼叫）
     */
    fun initTtsIfNeeded(context: Context) {
        if (tts != null && isTtsReady) return
        val appCtx = context.applicationContext
        mainHandler?.post {
            if (tts != null) return@post
            runCatching {
                val hasGoogleTts = runCatching {
                    appCtx.packageManager.getPackageInfo("com.google.android.tts", 0) != null
                }.getOrDefault(false)
                val preferredEnginePkg = if (hasGoogleTts) "com.google.android.tts" else null

                val initListener = TextToSpeech.OnInitListener { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val engine = tts ?: return@OnInitListener
                        val resTw = engine.setLanguage(Locale.TAIWAN)
                        if (resTw == TextToSpeech.LANG_MISSING_DATA || resTw == TextToSpeech.LANG_NOT_SUPPORTED) {
                            val resTrad = engine.setLanguage(Locale.TRADITIONAL_CHINESE)
                            if (resTrad == TextToSpeech.LANG_MISSING_DATA || resTrad == TextToSpeech.LANG_NOT_SUPPORTED) {
                                engine.setLanguage(Locale.CHINESE)
                            }
                        }
                        // 鎖定單一台灣女性發音人 (cmn-tw-x-ctc / f00) 並設定自然溫潤語速與音高（避免過高尖銳）
                        CuteGirlNeuralTtsEngine.lockSingleCuteFemaleVoice(engine)
                        engine.setSpeechRate(0.98f)
                        engine.setPitch(1.05f)
                        isTtsReady = true
                        pendingUtterance?.let { pending ->
                            pendingUtterance = null
                            if (isTtsEnabled && pending.isNotBlank()) {
                                CuteGirlNeuralTtsEngine.speakWithCuteProsody(
                                    context = appCtx,
                                    tts = engine,
                                    rawText = pending,
                                    flush = true
                                )
                            }
                        }
                    } else {
                        isTtsReady = false
                    }
                }
                tts = if (preferredEnginePkg != null) {
                    TextToSpeech(appCtx, initListener, preferredEnginePkg)
                } else {
                    TextToSpeech(appCtx, initListener)
                }
            }
        }
    }

    /**
     * 播報自然繁體中文語音（自動過濾 Emoji 與特殊符號）
     */
    fun speak(context: Context, text: String, flush: Boolean = true) {
        if (!isTtsEnabled || text.isBlank()) return
        speakInternal(context, text, flush, maxLen = 180, ignoreToggle = false)
    }

    /**
     * 強制朗讀（用於使用者明確下令「讀畫面 / 唸這頁」時，即使平常關閉步驟播報也會為使用者朗讀畫面重點）
     */
    fun speakForced(context: Context, text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        speakInternal(context, text, flush, maxLen = 360, ignoreToggle = true)
    }

    private fun speakInternal(
        context: Context,
        text: String,
        flush: Boolean,
        maxLen: Int,
        ignoreToggle: Boolean
    ) {
        val cleaned = cleanTextForTts(text, maxLen)
        if (cleaned.isBlank()) return
        val appCtx = context.applicationContext
        mainHandler?.post {
            if (!ignoreToggle && !isTtsEnabled) return@post
            if (tts == null || !isTtsReady) {
                pendingUtterance = cleaned
                initTtsIfNeeded(appCtx)
                return@post
            }
            runCatching {
                val engine = tts ?: return@runCatching
                requestTransientDuckingAudioFocus(appCtx)
                CuteGirlNeuralTtsEngine.speakWithCuteProsody(
                    context = appCtx,
                    tts = engine,
                    rawText = cleaned,
                    flush = flush
                )
            }
        }
    }

    /**
     * 切換語音播報開關，並回傳切換後的最新狀態
     */
    fun toggleTts(context: Context): Boolean {
        val nextState = !isTtsEnabled
        isTtsEnabled = nextState
        if (nextState) {
            speak(context, "語音播報已開啟", flush = true)
        } else {
            mainHandler?.post {
                CuteGirlNeuralTtsEngine.stopActivePlayback()
                runCatching { tts?.stop() }
            }
        }
        return nextState
    }

    /**
     * 檢查是否已取得 RECORD_AUDIO 麥克風權限
     */
    fun hasRecordAudioPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 啟動一鍵語音辨識（串流即時部分辨識結果 + 最終完整語句回呼）
     */
    fun startListening(
        context: Context,
        languageTag: String = "zh-TW",
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onStatusOrError: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        mainHandler?.post {
            if (!hasRecordAudioPermission(appCtx)) {
                isListening = false
                onStatusOrError("請先在主畫面點擊「語音」授權麥克風權限")
                return@post
            }

            // 停止進行中的 TTS 播報，避免麥克風收錄到系統自己的播報聲
            CuteGirlNeuralTtsEngine.stopActivePlayback()
            runCatching { tts?.stop() }

            // 銷毀舊的 SpeechRecognizer 實例
            destroyRecognizerOnMainThread()

            if (!SpeechRecognizer.isRecognitionAvailable(appCtx)) {
                isListening = false
                onStatusOrError("此裝置目前不支援系統語音辨識服務")
                return@post
            }

            runCatching {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appCtx)
                speechRecognizer = recognizer
                isListening = true

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        onStatusOrError("請說話（例如：打開 YouTube 搜尋周杰倫）...")
                    }

                    override fun onBeginningOfSpeech() {
                        isListening = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        val wasListening = isListening
                        isListening = false
                        destroyRecognizerOnMainThread()
                        if (!wasListening && error == SpeechRecognizer.ERROR_CLIENT) {
                            return
                        }
                        onStatusOrError(mapSpeechErrorToChinese(error))
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        val best = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                        destroyRecognizerOnMainThread()
                        if (best.isNotEmpty()) {
                            onFinalResult(best)
                        } else {
                            onStatusOrError("未能聽清楚語音內容，請再試一次")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() }
                            ?.trim()
                            .orEmpty()
                        if (partial.isNotEmpty()) {
                            onPartialResult(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                recognizer.startListening(intent)
            }.onFailure { e ->
                isListening = false
                destroyRecognizerOnMainThread()
                onStatusOrError("啟動語音辨識失敗：${e.message ?: "未知錯誤"}")
            }
        }
    }

    /**
     * 停止並釋放目前活動中的語音辨識器
     */
    fun stopListening() {
        mainHandler?.post {
            isListening = false
            destroyRecognizerOnMainThread()
        }
    }

    private fun destroyRecognizerOnMainThread() {
        val active = speechRecognizer ?: return
        speechRecognizer = null
        runCatching { active.stopListening() }
        runCatching { active.cancel() }
        runCatching { active.destroy() }
    }

    private fun mapSpeechErrorToChinese(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "沒有辨識到清楚的語音指令，請再說一次"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未偵測到說話聲音，已結束聆聽"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麥克風權限，請先授權錄音權限"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "語音辨識網路連線逾時，請檢查網路狀態"
            SpeechRecognizer.ERROR_AUDIO -> "麥克風音訊讀取異常，請稍後再試"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音辨識引擎忙碌中，請稍候再試"
            SpeechRecognizer.ERROR_SERVER -> "語音辨識服務暫時無法回應"
            SpeechRecognizer.ERROR_CLIENT -> "已停止語音聆聽"
            else -> "語音辨識中斷（錯誤碼 $error），請再試一次"
        }
    }

    private fun cleanTextForTts(raw: String, maxLen: Int = 180): String {
        return raw
            .replace(Regex("[\\u2794\\u2192]|->"), "，接著")
            .replace(Regex("[*_`~#\\[\\]|<>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLen)
    }
}
