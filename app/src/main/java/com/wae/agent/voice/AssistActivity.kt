package com.wae.agent.voice

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wae.agent.core.WaeVisionAccessibilityService

/**
 * Siri 式語音助理（S42）。
 *
 * 版面：
 *  - 底部 2/5：波浪面板（灰 → 白 → 藍 → 紫 → 粉；越上層越透明、振幅越大）
 *  - 全螢幕：視窗模糊 + 低透明白色提亮層（整體亮度提高）
 *  - 輸入框：白霧往外大幅擴散（無硬邊）
 *  - 只留：麥克風 + 輸入框 + 執行（↑）+ 右上 X
 */
class AssistActivity : Activity() {

    companion object {
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"

        private const val REQ_MIC = 2001
        private const val REQ_GOOGLE_VOICE = 3001
        private const val TAG_EVENT = "WAE_VOICE"
        private const val PREFS = "wae_voice"
        private const val KEY_VOICE_EXEC_COUNT = "voice_exec_count"
        private const val KEY_VOICE_LANG = "voice_lang"
        /** 安全模式：前 N 次語音執行都要確認 */
        private const val SAFE_MODE_COMMANDS = 20
        /** 關閉助理介面到送出指令的等待時間（讓視窗真的消失） */
        private const val HANDOFF_DELAY_MS = 500L
    }

    private var controller: VoiceRecognizerController? = null
    private var sherpaAsr: SherpaAsr? = null
    private var bundledVoiceStarted = false
    private lateinit var input: EditText
    private lateinit var wave: WavePanelView
    private lateinit var mic: ImageView
    private lateinit var confirmRow: LinearLayout
    private lateinit var confirmText: TextView
    private lateinit var undoRow: LinearLayout
    private lateinit var undoText: TextView
    private var pendingVoiceText = ""
    private var typingMode = false
    private var submitted = false

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBlurredWindow()
        setContentView(buildUi())
        controller = VoiceRecognizerController(this)
        sherpaAsr = SherpaAsr(this)
        startVoice()
    }

    override fun onDestroy() {
        com.wae.core.util.Degrade.orNull("assist:closeAsr") { sherpaAsr?.close() }
        sherpaAsr = null
        com.wae.core.util.Degrade.orNull("assist:destroyController") { controller?.destroy() }
        controller = null
        super.onDestroy()
    }

    // ── 視窗模糊 + 提亮 + 覆蓋底部三鍵 ─────────────────────
    private fun applyBlurredWindow() {
        com.wae.core.util.Degrade.orNull("assist:applyBlurredWindow") {
            window.setDimAmount(0.0f)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 內容延伸到整個螢幕（含最下方導覽列區域），並隱藏三鍵讓波浪面板覆蓋
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { ctl ->
                    ctl.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctl.hide(android.view.WindowInsets.Type.navigationBars())
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setBackgroundBlurRadius(dp(60))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
        refreshUndoCard()
    }

    /** P0：顯示「上一步」復原卡（10 分鐘內、可復原的動作） */
    private fun refreshUndoCard() {
        val last = com.wae.core.util.Degrade.orNull("assist:lastUndoable") { SessionHistory.lastUndoable(applicationContext) }
        if (last == null) {
            if (::undoRow.isInitialized) undoRow.visibility = View.GONE
            return
        }
        if (!::undoRow.isInitialized) return
        undoText.text = "上一步：" + last.text
        undoRow.visibility = View.VISIBLE
    }

    /** 復原：通用做法是先按 BACK 回到上一層／關閉剛打開的內容 */
    private fun performUndo() {
        com.wae.core.util.Degrade.orNull("assist:performUndo") {
            WaeVisionAccessibilityService.activeInstance
                ?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            SessionHistory.markLastUndone(applicationContext)
        }
        undoRow.visibility = View.GONE
        switchToTyping()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    /** 真正把底部三鍵藏起來（onCreate 時視窗還沒 focus，必須在 focus 後再呼叫一次） */
    private fun hideNavigationBar() {
        com.wae.core.util.Degrade.orNull("assist:hideNavigationBar") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { ctl ->
                    ctl.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctl.hide(WindowInsets.Type.navigationBars())
                }
            }
        }
    }

    // ── 畫面 ──────────────────────────────────────────────
    private fun buildUi(): View {
        val root = FrameLayout(this)
        val screenH = resources.displayMetrics.heightPixels
        val panelH = screenH * 2 / 5

        // 全螢幕壓暗層（S48）：背景壓暗，但波浪與輸入框在它上面，不受影響
        val dim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x59000000, 0x40000000)
            )
        }
        root.addView(dim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        wave = WavePanelView(this).apply {
            visiblePanelHeightPx = panelH.toFloat()
        }
        // View 比可見區域高，讓波浪峰值往透明處自然淡出（不出現截斷直線）
        val waveTopPad = dp(220)
        root.addView(wave, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, panelH + waveTopPad
        ).apply { gravity = Gravity.BOTTOM })

        val layer = FrameLayout(this)
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, panelH
        ).apply { gravity = Gravity.BOTTOM })

        // 關閉（X）：右側、中間偏下（淡灰，壓暗背景上也看得見）
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#E7E7EA"))
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, (screenH * 0.58f).toInt(), dp(6), 0)
        })

        // 輸入框：外層留足 padding 給模糊擴散（不是被外框裁掉）
        val haloPx = dp(26)
        val barWrap = FrameLayout(this).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setClipChildren(false)
            setClipToPadding(false)
            background = BlurPillDrawable(
                blurRadiusPx = dp(12).toFloat(),
                insetPx = dp(12).toFloat()
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(4), dp(4))
        }

        mic = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            colorFilter = PorterDuffColorFilter(Color.parseColor("#27272A"), PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { restartVoice() }
        }
        bar.addView(mic, LinearLayout.LayoutParams(dp(38), dp(38)))

        input = EditText(this).apply {
            hint = "說點什麼…"
            setHintTextColor(0x8A27272A.toInt())
            setTextColor(Color.parseColor("#18181B"))
            textSize = 15f
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(2), dp(4), dp(4), dp(4))
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) switchToTyping() }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    execute(input.text?.toString().orEmpty())
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 執行：只留一個箭頭（沒有黑圈）
        val send = TextView(this).apply {
            text = "↑"
            setTextColor(Color.parseColor("#27272A"))
            textSize = 24f
            gravity = Gravity.CENTER
            background = null
            setOnClickListener { execute(input.text?.toString().orEmpty()) }
        }
        bar.addView(send, LinearLayout.LayoutParams(dp(36), dp(36)))

        val barH = dp(52)
        barWrap.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, barH
        ).apply {
            gravity = Gravity.CENTER
            setMargins(haloPx, 0, haloPx, 0)
        })

        layer.addView(barWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, barH + haloPx * 2
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(18), 0, dp(18), dp(88))
        })

        // A5/D2：確認卡（低信心 / 敏感指令 / 安全模式時出現）
        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE6FFFFFF.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        confirmText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#18181B"))
            maxLines = 2
        }
        confirmRow.addView(
            confirmText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val confirmGo = TextView(this).apply {
            text = "執行"
            textSize = 12.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6D28D9"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                confirmRow.visibility = View.GONE
                execute(pendingVoiceText, fromVoice = true)
            }
        }
        val confirmCancel = TextView(this).apply {
            text = "取消"
            textSize = 12.5f
            setTextColor(Color.parseColor("#71717A"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                confirmRow.visibility = View.GONE
                input.setText(pendingVoiceText)
                switchToTyping()
            }
        }
        confirmRow.addView(confirmGo)
        confirmRow.addView(confirmCancel)
        layer.addView(confirmRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(18), 0, dp(18), dp(88) + barH + haloPx * 2 + dp(8))
        })

        // P0：上一步／復原
        undoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(0xCCFFFFFF.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        undoText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#52525B"))
            maxLines = 1
        }
        undoRow.addView(
            undoText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val undoGo = TextView(this).apply {
            text = "復原"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { performUndo() }
        }
        val undoDismiss = TextView(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setOnClickListener {
                undoRow.visibility = View.GONE
                SessionHistory.markLastUndone(applicationContext)
            }
        }
        undoRow.addView(undoGo)
        undoRow.addView(undoDismiss)
        layer.addView(undoRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(18), 0, dp(18), dp(88) + barH + haloPx * 2 + dp(8))
        })

        return root
    }

    // ── 語音（S57：Google 語音輸入為主，系統辨識備援；已移除內建模型）──
    private fun startVoice() {
        submitted = false
        if (typingMode) return
        input.hint = "在聽…"
        mic.alpha = 1f
        com.wae.core.util.Degrade.orNull("assist:waveSpeed22") { wave.speed = 2.2f }

        // 需要錄音權限
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
            return
        }

        // 主要路徑：內建 sherpa 離線引擎（隨 APK 附帶模型，不需網路、不需下載）
        VoiceLog.event(TAG_EVENT, "BUNDLED_ASR_FIRST")
        startBundledVoice()
    }

    /** 內建離線語音（sherpa，中文）；失敗才退回 Google 引擎 */
    private fun startBundledVoice() {
        if (bundledVoiceStarted) return
        bundledVoiceStarted = true
        sherpaAsr?.prepareAsync(
            onReady = {
                runOnUiThread {
                    if (typingMode) return@runOnUiThread
                    input.hint = "說點什麼…"
                    // 開發驗證：外部 sherpa_test.wav 存在時先跑一次離線辨識
                    runCatching {
                        val testFile = java.io.File(getExternalFilesDir(null), "sherpa_test.wav")
                        if (testFile.isFile) {
                            Thread {
                                val t0 = android.os.SystemClock.elapsedRealtime()
                                val text = com.wae.core.util.Degrade.orNull("assist:recognizeWav") { sherpaAsr?.recognizeWavFile(testFile) }
                                val dt = android.os.SystemClock.elapsedRealtime() - t0
                                VoiceLog.event(TAG_EVENT, "SHERPA_TEST ${dt}ms")
                                // 端到端驗證：sherpa_test_exec 存在 → 辨識結果直接送管線
                                runCatching {
                                    if (java.io.File(getExternalFilesDir(null), "sherpa_test_exec").isFile &&
                                        !text.isNullOrBlank()
                                    ) {
                                        val corrected = AsrCorrector.correct(applicationContext, text)
                                        if (VoiceCommandGate.isCommandLike(applicationContext, corrected) &&
                                            VoiceRiskPolicy.classify(applicationContext, corrected) ==
                                            VoiceRiskPolicy.Risk.SAFE
                                        ) {
                                            runOnUiThread { execute(corrected, fromVoice = true) }
                                            VoiceLog.event(TAG_EVENT, "SHERPA_E2E_EXEC")
                                        } else {
                                            VoiceLog.event(TAG_EVENT, "SHERPA_E2E_SKIP_GATE")
                                        }
                                    }
                                }
                                // 驗證用：把結果寫到外部檔，adb 可直接讀（release 不留使用者文字到 log）
                                runCatching {
                                    val out = java.io.File(getExternalFilesDir(null), "sherpa_test_out.txt")
                                    out.writeText("ms=" + dt + "\ntext=" + (text ?: "<null>") + "\n", Charsets.UTF_8)
                                }
                            }.apply { name = "sherpa-test" }.start()
                        }
                    }
                    sherpaAsr?.startListening(
                        onPartial = { text ->
                            runOnUiThread {
                                if (!typingMode && text.isNotBlank()) input.setText(text)
                            }
                        },
                        onFinal = { text ->
                            runOnUiThread {
                                if (!typingMode) {
                                    input.setText(text)
                                    handleVoiceResult(text, 0.9f)
                                }
                            }
                        },
                        onError = {
                            VoiceLog.event(TAG_EVENT, "BUNDLED_ASR_FAIL_FALLBACK_SYSTEM")
                            startSystemVoice()
                        }
                    )
                }
            },
            onError = {
                VoiceLog.event(TAG_EVENT, "BUNDLED_ASR_INIT_FAIL_FALLBACK_SYSTEM")
                runOnUiThread { startSystemVoice() }
            }
        )
    }

    /** 系統語音辨識（內建引擎不可用時的備援：Google 引擎、不跳 Google 畫面） */
    private fun startSystemVoice() {
        controller?.start(
            onPartial = { text ->
                if (!typingMode && text.isNotBlank()) input.setText(text)
            },
            onFinal = { text ->
                runOnUiThread { handleVoiceResult(text, -1f) }
            },
            onError = { _, code ->
                // 沉默/沒聽清楚 → 留在我們自己的 UI，不要跳出 Google 的醜彈窗
                if (code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    code == SpeechRecognizer.ERROR_NO_MATCH
                ) {
                    VoiceLog.event(TAG_EVENT, "SYSTEM_ASR_NO_SPEECH")
                    switchToTyping()
                } else {
                    // 服務不可用 → 直接轉打字（不再跳 Google 語音畫面）
                    VoiceLog.event(TAG_EVENT, "SYSTEM_ASR_UNAVAILABLE_TYPING")
                    runOnUiThread { switchToTyping() }
                }
            }
        )
    }

    /** Route 2：交給 Google 語音輸入 Activity，取回辨識文字（Google 原生模型、支援多語言） */
    private fun startGoogleVoiceInput() {
        val lang = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_VOICE_LANG, "zh-TW") ?: "zh-TW"
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_GOOGLE_VOICE)
        }.onFailure {
            VoiceLog.error(TAG_EVENT, "GOOGLE_VOICE_INTENT_FAIL", it)
            startSystemVoice()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GOOGLE_VOICE) return
        if (resultCode != RESULT_OK) {
            VoiceLog.event(TAG_EVENT, "GOOGLE_VOICE_CANCEL")
            switchToTyping()
            return
        }
        val list = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
        val text = list?.firstOrNull()?.trim().orEmpty()
        VoiceLog.detail(applicationContext, TAG_EVENT, "GOOGLE_VOICE_RESULT len=${text.length}")
        if (text.isEmpty()) {
            VoiceLog.event(TAG_EVENT, "GOOGLE_VOICE_EMPTY")
            switchToTyping()
        } else {
            handleVoiceResult(text, 1.0f)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startVoice()
        } else if (requestCode == REQ_MIC) {
            switchToTyping()
        }
    }

    /**
     * 語音結果統一入口（A5 / D2）：
     *  - 不像指令 → 切打字
     *  - 敏感指令（BLOCK）→ 絕不自動執行
     *  - 敏感指令（CONFIRM）/ 低信心 / 安全模式 → 顯示「我聽到：…」確認卡
     *  - 其他 → 直接執行
     */
    private fun handleVoiceResult(rawText: String, confidence: Float) {
        if (typingMode) return
        if (rawText.isBlank()) return
        val text = AsrCorrector.correct(applicationContext, rawText)
        input.setText(text)
        if (!VoiceCommandGate.isCommandLike(applicationContext, text)) {
            VoiceLog.event(TAG_EVENT, "VOICE_REJECT_NOT_COMMAND")
            switchToTyping()
            return
        }
        val risk = VoiceRiskPolicy.classify(applicationContext, text)
        if (risk == VoiceRiskPolicy.Risk.BLOCK) {
            VoiceLog.event(TAG_EVENT, "VOICE_BLOCK_RISKY")
            switchToTyping()
            return
        }
        val lowConfidence = confidence in 0.0f..(VoiceRiskPolicy.CONFIDENCE_THRESHOLD - 0.001f)
        val safeMode = !isPastSafeMode()
        if (risk == VoiceRiskPolicy.Risk.CONFIRM || lowConfidence || safeMode) {
            VoiceLog.event(
                TAG_EVENT,
                "VOICE_CONFIRM risk=" + risk.name + " lowConf=" + lowConfidence + " safeMode=" + safeMode
            )
            showConfirm(text, lowConfidence, safeMode)
        } else {
            execute(text, fromVoice = true)
        }
    }

    private fun showConfirm(text: String, lowConfidence: Boolean, safeMode: Boolean) {
        pendingVoiceText = text
        val hint = when {
            lowConfidence -> "我聽到："
            safeMode -> "確認（安全模式）："
            else -> "確認："
        }
        confirmText.text = hint + text
        confirmRow.visibility = View.VISIBLE
        com.wae.core.util.Degrade.orNull("assist:stopController") { controller?.stop() }
    }

    /** 安全模式：前 N 次語音指令一律要確認（可避免剛安裝就誤動作） */
    private fun isPastSafeMode(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getInt(KEY_VOICE_EXEC_COUNT, 0) >= SAFE_MODE_COMMANDS
    }

    private fun countVoiceExecution() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val n = prefs.getInt(KEY_VOICE_EXEC_COUNT, 0)
        prefs.edit().putInt(KEY_VOICE_EXEC_COUNT, n + 1).apply()
    }

    private fun restartVoice() {
        typingMode = false
        submitted = false
        bundledVoiceStarted = false
        input.setText("")
        input.clearFocus()
        com.wae.core.util.Degrade.orNull("assist:stopController") { controller?.stop() }
        runCatching {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
        startVoice()
    }

    private fun switchToTyping() {
        if (typingMode) return
        typingMode = true
        com.wae.core.util.Degrade.orNull("assist:stopController") { controller?.stop() }
        input.hint = "打字後按 ↑"
        mic.alpha = 0.5f
        com.wae.core.util.Degrade.orNull("assist:waveSpeed26") { wave.speed = 2.6f }
        input.requestFocus()
        runCatching {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun execute(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty() || submitted) return
        submitted = true
        // 開放式語句交給開放式執行器（模板／逐元素決策，不用 LLM）；明確指令仍走快速路徑
        val agentSvc = WaeVisionAccessibilityService.activeInstance
        val route = runCatching {
            com.wae.agent.core.ExecutionRouter.route(applicationContext, text)
        }.getOrDefault(com.wae.agent.core.ExecutionRouter.Route.FAST)
        // 規則：拆得出完整目標 → 快速；拆不出但有意圖模板 → 開放式執行器；兩者皆無 → 請使用者打字
        if (route == com.wae.agent.core.ExecutionRouter.Route.ASK) {
            input.hint = "我不確定這是不是指令，請換個說法或打字"
            submitted = false
            switchToTyping()
            return
        }
        if (route == com.wae.agent.core.ExecutionRouter.Route.AGENT && agentSvc != null) {
            try {
                input.setText(text)
            } catch (_: Throwable) {}
            VoiceLog.event(TAG_EVENT, "AGENT_DISPATCH")
            finish()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.wae.core.util.Degrade.orNull("assist:runAgent") { agentSvc.runAgentCommand(text) }
            }, HANDOFF_DELAY_MS)
            return
        }
        if (fromVoice) countVoiceExecution()
        runCatching {
            SessionHistory.append(applicationContext, text, voice = fromVoice)
            if (fromVoice) VoiceFeedback.speak(applicationContext, "好的")
        }
        com.wae.core.util.Degrade.orNull("assist:stopController") { controller?.stop() }
        val svc = WaeVisionAccessibilityService.activeInstance
        if (svc == null) {
            finish()
            return
        }
        // 關鍵：先把助理介面關掉，讓底下的 App 回到前景，再送指令；
        // 否則無障礙管線會把「助理自己的 UI」當成目標畫面（TYPE/TAP 都會失敗）。
        finish()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // H3：語音先走 Gen-5（有動作就算它處理），完全沒動作才退回舊路徑
            val gen5Enabled = com.wae.core.util.Degrade.orElse("assist:gen5Enabled", false) { com.wae.agent.gen5.Gen5Runtime.isEnabled(this) }
            if (gen5Enabled) {
                Thread {
                    val result = runCatching {
                        com.wae.agent.gen5.Gen5Runtime.runDetailed(applicationContext, text)
                    }.getOrNull()
                    val handled = result != null &&
                        (result.outcome is com.wae.core.model.Outcome.Success || result.stepsUsed > 0)
                    if (handled) {
                        VoiceLog.event(TAG_EVENT, "GEN5_HANDLED")
                        runCatching {
                            com.wae.agent.voice.VoiceFeedback.speak(
                                applicationContext,
                                if (result!!.outcome is com.wae.core.model.Outcome.Success) "完成了" else "我停在這裡"
                            )
                        }
                    } else {
                        com.wae.core.util.Degrade.orNull("assist:overlayExecute") { svc.overlayExecuteCommand(text, fromVoice = fromVoice) }
                    }
                }.start()
            } else {
                com.wae.core.util.Degrade.orNull("assist:overlayExecute") { svc.overlayExecuteCommand(text, fromVoice = fromVoice) }
            }
            VoiceLog.event(TAG_EVENT, "HANDOFF_DISPATCHED")
        }, HANDOFF_DELAY_MS)
    }

    override fun onBackPressed() {
        finish()
    }
}
