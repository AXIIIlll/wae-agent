package com.example.newandroidapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.newandroidapp.ai.AiActionDecision
import com.example.newandroidapp.ai.AndroidNativeAiEngine
import com.example.newandroidapp.ai.DualLocalAiCoordinator
import com.example.newandroidapp.ai.InstalledAppLauncher
import com.example.newandroidapp.ai.LocalAiBackendMode
import com.example.newandroidapp.chat.ChatMessage
import com.example.newandroidapp.chat.ChatRole
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.chat.ConversationObserver
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.stability.BenchmarkSuiteManager
import com.example.newandroidapp.stability.CrashGuardManager
import com.example.newandroidapp.stability.KeepAliveHelper
import com.example.newandroidapp.stability.NeedleIsolatedClient
import com.example.newandroidapp.stability.PlayStoreComplianceHelper
import com.example.newandroidapp.stability.SafetyGateManager
import com.example.newandroidapp.stability.SmartDailyAssistantHelper
import com.example.newandroidapp.stability.StabilityFlightRecorder
import com.example.newandroidapp.stability.SystemBarBlockerGuard
import com.example.newandroidapp.stability.VoiceCommandAndTtsManager
import com.example.newandroidapp.vision.AiVisionPipeline
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.VisionAccessibilityService
import com.example.newandroidapp.vision.VisionPipelineReport
import com.example.newandroidapp.vision.VisionVisualizerView
import java.util.concurrent.Executors

/**
 * 【AI 助手 — 極簡白藍＋翠綠點綴高訂版主控台】
 *
 * 嚴格實踐四大 Mobile UI/UX 與動效 Skill 規範（且全介面 100% 禁用任何 Emoji 表情符號）：
 * 1. [Sticklight / mobile-design]：
 *    - 採用 Android Material Design 3 (M3) 8dp 空間網格（8dp / 16dp / 24dp / 48dp+ 觸控熱區）。
 *    - 大拇指黃金熱區配置：首屏中央為純白留白輸入框，正下方緊接「1 : 1 : 1.5 黃金比例下懸浮三鍵（語音｜播報｜執行）」。
 * 2. [funboy322 / avoid-ai-design]：
 *    - 100% 零 Emoji 表情包污染，全部改用高訂字體階層與幾何圓點（GradientDrawable.OVAL）表達狀態。
 *    - 首屏僅保留「單行頂欄 -> 極致留白輸入框 -> 下懸浮三鍵 -> 狀態列 -> 進階設定入口」，將防護、省電、壓測與日誌收斂於進階抽屜。
 * 3. [LottieFiles / motion-design-skill]：
 *    - 嚴禁死板 LinearInterpolator，全面套用 M3 貝茲曲線 PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f) 與 OvershootInterpolator(1.38f) 彈簧按壓回饋。
 * 4. [Animate UI / iart-ai / motion-skills]：
 *    - 所有按鈕按壓、卡片階層式入場（Staggered Entrance）與抽屜展開均透過 GPU RenderNode (.withLayer()) 硬體加速執行。
 */
class MainActivity : Activity() {

    private val worker = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "main-ui-worker").apply { isDaemon = true }
    }
    private val workerWatchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "main-ui-worker-watchdog").apply { isDaemon = true }
    }
    private val uiHandler = Handler(Looper.getMainLooper())

    private fun submitWorkerWithTimeout(
        timeoutMs: Long = 10_000L,
        onTimeoutUi: (() -> Unit)? = null,
        task: () -> Unit
    ) {
        val future = worker.submit {
            try {
                task()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "Background worker error: ${t.message}")
            }
        }
        workerWatchdog.schedule({
            if (!future.isDone) {
                future.cancel(true)
                if (onTimeoutUi != null) {
                    uiHandler.post(onTimeoutUi)
                }
            }
        }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // [LottieFiles / motion-design-skill] & [heydamir/unslop Pass 9] 克制流暢的 M3 貝茲曲線與微彈簧插值器
    private val m3DecelerateInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    private val m3StandardInterpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
    private val springOvershootInterpolator = OvershootInterpolator(1.2f)
    private var rootScrollView: ScrollView? = null

    private lateinit var serviceStatusBtn: Button
    private lateinit var batteryKeepAliveBtn: Button
    private lateinit var systemBarBlockerToggleBtn: Button
    private lateinit var aiEngineStatusTv: TextView
    private lateinit var goalInputEt: EditText
    private lateinit var installedAppsRow: LinearLayout
    private lateinit var aiDecisionTv: TextView

    // 底部極簡控制列（語音 ｜ 播報 ｜ 發送對話 ｜ 執行操作）
    private lateinit var voiceInputBtn: Button
    private lateinit var ttsToggleBtn: Button
    private lateinit var sendChatBtn: Button
    private lateinit var runTaskBtn: Button

    // 第 11 項：5 分鐘 + 30+ App 穩定度壓力測試中心與自訂測試集 UI
    private lateinit var startBenchmarkBtn: Button
    private lateinit var benchmarkStatusTv: TextView
    private lateinit var customSuiteToggleBtn: Button
    private lateinit var customSuiteEditorLayout: LinearLayout
    private lateinit var suiteListContainer: LinearLayout
    private lateinit var customAppEt: EditText
    private lateinit var customGoalEt: EditText

    // 進階抽屜（含防護、省電、合規聲明、AI 模式、壓測、黑盒子日誌與視覺預覽）
    private lateinit var advancedToggleTv: Button
    private lateinit var advancedDrawerLayout: LinearLayout
    private lateinit var flightLogsTv: TextView
    private lateinit var crashStatusTv: TextView
    private lateinit var autoScanStatusTv: TextView
    private lateinit var visualizerView: VisionVisualizerView

    private val modeButtons = HashMap<LocalAiBackendMode, Button>()
    private var currentAiMode = LocalAiBackendMode.DUAL_FUSION
    private var latestReport: VisionPipelineReport? = null
    private var selectedAppLabel: String = ""

    private lateinit var chatBubbleContainer: LinearLayout
    private val chatObserver = object : ConversationObserver {
        override fun onMessagesUpdated(messages: List<ChatMessage>) {
            runOnUiThread {
                renderChatBubbles(messages)
            }
        }
        override fun onTurnContextUpdated(context: ConversationTurnContext) {}
    }

    private val uiRefreshTicker = object : Runnable {
        override fun run() {
            val liveRep = VisionAccessibilityService.latestLiveReport
            if (liveRep != null && liveRep !== latestReport) {
                latestReport = liveRep
                updateAutoScanStatusBanner(liveRep)
                if (advancedDrawerLayout.visibility == View.VISIBLE) {
                    visualizerView.updateData(VisionAccessibilityService.latestLiveBitmap, liveRep)
                }
                VisionAccessibilityService.latestLiveDecision?.let { renderDecisionResult(it) }
            }
            updateVoiceAndTtsButtonState()
            refreshBenchmarkAndFlightUi()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuardManager.installGlobalCrashGuard(applicationContext)
        VoiceCommandAndTtsManager.initTtsIfNeeded(applicationContext)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        // [安卓系統上下安全擋板與自適應邊界]
        // 頂部狀態列與底部導覽列採用深色實心擋板 (#09090B)，搭配四向 WindowInsets 動態同步高度
        @Suppress("DEPRECATION")
        runCatching {
            window.statusBarColor = Color.parseColor("#09090B")
            window.navigationBarColor = Color.parseColor("#09090B")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.setSystemBarsAppearance(
                        0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    )
                    controller.show(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                    )
                }
            }
        }

        val letterboxOuterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        // [heydamir/unslop] 極簡深色畫布 (#0A0A0C)，零多餘標題、零綠點、零常駐功能鍵轟炸
        val screenRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
        }

        // =========================================================================
        // 區塊 1：極簡頂部操作條（無標題、無綠點、無「本機離線」；僅右側低調文字入口：新對話 ｜ 懸浮球 ｜ 設定）
        // =========================================================================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }

        val headerLeftCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        aiEngineStatusTv = TextView(this).apply {
            text = ""
            visibility = View.GONE
        }

        val clearChatBtn = createStyledButton(
            text = "新對話",
            bgHex = "#09090B",
            textColor = Color.parseColor("#71717A"),
            strokeHex = "#09090B",
            cornerDp = 8
        ).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                ConversationManager.clearConversation(applicationContext)
            }
        }

        serviceStatusBtn = createStyledButton(
            text = "懸浮球",
            bgHex = "#09090B",
            textColor = Color.parseColor("#71717A"),
            strokeHex = "#09090B",
            cornerDp = 8
        ).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { handleLiveServiceAction() }
        }

        advancedToggleTv = createStyledButton(
            text = "設定",
            bgHex = "#09090B",
            textColor = Color.parseColor("#71717A"),
            strokeHex = "#09090B",
            cornerDp = 8
        ).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                val isHidden = advancedDrawerLayout.visibility == View.GONE
                if (isHidden) {
                    advancedDrawerLayout.visibility = View.VISIBLE
                    advancedDrawerLayout.alpha = 0f
                    advancedDrawerLayout.translationY = -dp(8).toFloat()
                    advancedDrawerLayout.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(m3DecelerateInterpolator)
                        .withLayer()
                        .start()
                    text = "收合"
                    refreshBenchmarkAndFlightUi()
                    val liveRep = VisionAccessibilityService.latestLiveReport
                    val liveBmp = VisionAccessibilityService.latestLiveBitmap
                    if (liveRep != null) {
                        latestReport = liveRep
                        visualizerView.updateData(liveBmp, liveRep)
                        updateAutoScanStatusBanner(liveRep)
                    } else {
                        captureSelfOrLoadLiveReport()
                    }
                    rootScrollView?.post { rootScrollView?.smoothScrollTo(0, 0) }
                } else {
                    advancedDrawerLayout.animate()
                        .alpha(0f)
                        .translationY(-dp(8).toFloat())
                        .setDuration(160L)
                        .setInterpolator(m3StandardInterpolator)
                        .withLayer()
                        .withEndAction {
                            advancedDrawerLayout.visibility = View.GONE
                        }
                        .start()
                    text = "設定"
                }
            }
        }

        headerRow.addView(headerLeftCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(clearChatBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(4)
        })
        headerRow.addView(serviceStatusBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(4)
        })
        headerRow.addView(advancedToggleTv)
        screenRoot.addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        screenRoot.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#18181B"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))

        val statusStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        aiDecisionTv = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#71717A"))
        }
        statusStrip.addView(aiDecisionTv)

        // =========================================================================
        // 區塊 2：中央滿版捲動對話畫布（ScrollView weight = 1f）
        // =========================================================================
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            isFillViewport = true
        }
        rootScrollView = rootScroll

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        chatBubbleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }

        // =========================================================================
        // 區塊 3：底部唯一常駐區（單一輸入框 ＋ 語音/送出二合一鍵）
        // =========================================================================
        val bottomDockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        val appsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        installedAppsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appsScroll.addView(installedAppsRow)

        val defaultStrokePx = dp(1).coerceAtLeast(1)
        val focusStrokePx = (resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(2)

        val inputIslandCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRectBg("#18181B", "#27272A", dp(16).toFloat(), strokePx = defaultStrokePx)
            setPadding(dp(16), dp(6), dp(6), dp(6))
        }

        goalInputEt = EditText(this).apply {
            setText("")
            hint = "輸入或說話…"
            textSize = 14f
            setTextColor(Color.parseColor("#F4F4F5"))
            setHintTextColor(Color.parseColor("#71717A"))
            background = null
            minLines = 1
            maxLines = 4
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(0, dp(8), dp(8), dp(8))
            setOnFocusChangeListener { _, hasFocus ->
                inputIslandCard.background = if (hasFocus) {
                    roundRectBg("#18181B", "#3F3F46", dp(16).toFloat(), strokePx = focusStrokePx)
                } else {
                    roundRectBg("#18181B", "#27272A", dp(16).toFloat(), strokePx = defaultStrokePx)
                }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateVoiceAndTtsButtonState()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            setOnLongClickListener {
                handleVoiceCommandClick()
                true
            }
        }

        // 語音 / 送出 二合一智慧按鈕（無文字時為「語音」，有文字時自動變為高對比「送出」）
        voiceInputBtn = createStyledButton(
            text = "語音",
            bgHex = "#27272A",
            textColor = Color.parseColor("#F4F4F5"),
            strokeHex = "#27272A",
            cornerDp = 12
        ).apply {
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(38)
            minimumHeight = dp(38)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { handleUnifiedUserSubmit() }
        }

        inputIslandCard.addView(
            goalInputEt,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        inputIslandCard.addView(
            voiceInputBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
        )

        val floatingThreeBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        ttsToggleBtn = createStyledButton(
            text = "播報·開",
            bgHex = "#F8FAFC",
            textColor = Color.parseColor("#334155"),
            strokeHex = "#E2E8F0",
            cornerDp = 12
        ).apply {
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            maxLines = 1
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                val enabled = VoiceCommandAndTtsManager.toggleTts(this@MainActivity)
                updateVoiceAndTtsButtonState()
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "已開啟語音播報回饋" else "已關閉語音播報回饋",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        sendChatBtn = createStyledButton(
            text = "發送",
            bgHex = "#F1F5F9",
            textColor = Color.parseColor("#0F172A"),
            strokeHex = "#CBD5E1",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { handleChatSendOnly() }
        }

        runTaskBtn = createStyledButton(
            text = "執行",
            bgHex = "#2563EB",
            textColor = Color.WHITE,
            strokeHex = "#1D4ED8",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { handleAutonomousAppOpenAndRun() }
        }

        floatingThreeBtnRow.addView(
            ttsToggleBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                rightMargin = dp(8)
            }
        )
        floatingThreeBtnRow.addView(
            sendChatBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                rightMargin = dp(8)
            }
        )
        floatingThreeBtnRow.addView(
            runTaskBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        bottomDockCard.addView(inputIslandCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        advancedDrawerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundRectBg("#FFFFFF", "#E2E8F0", dp(16).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 5A. 防護、省電與 Google Play 合規設定區
        val guardSectionTitleTv = TextView(this).apply {
            text = "系統防護與合規設定"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        systemBarBlockerToggleBtn = createStyledButton(
            text = if (SystemBarBlockerGuard.isBlockerEnabled) {
                "頂底系統欄防護（真人三鍵穿透 / AI 禁區） · 已啟用"
            } else {
                "頂底系統欄防護（真人三鍵穿透 / AI 禁區） · 已關閉"
            },
            bgHex = if (SystemBarBlockerGuard.isBlockerEnabled) "#ECFDF5" else "#F8FAFC",
            textColor = if (SystemBarBlockerGuard.isBlockerEnabled) Color.parseColor("#047857") else Color.parseColor("#475569"),
            strokeHex = if (SystemBarBlockerGuard.isBlockerEnabled) "#A7F3D0" else "#E2E8F0",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener {
                SystemBarBlockerGuard.toggleBlocker()
                updateSystemBarBlockerButtonUi()
                VisionAccessibilityService.instance?.syncSystemBarBlockerOverlay()
                Toast.makeText(
                    this@MainActivity,
                    SystemBarBlockerGuard.statusSummary(this@MainActivity, resources.displayMetrics.heightPixels),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val subProtectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        batteryKeepAliveBtn = createStyledButton(
            text = "省電最佳化設定",
            bgHex = "#F8FAFC",
            textColor = Color.parseColor("#334155"),
            strokeHex = "#E2E8F0",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener {
                KeepAliveHelper.requestIgnoreBatteryOptimizations(this@MainActivity)
            }
        }

        val complianceDisclosureBtn = createStyledButton(
            text = "無障礙與隱私聲明",
            bgHex = "#F8FAFC",
            textColor = Color.parseColor("#334155"),
            strokeHex = "#E2E8F0",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener {
                PlayStoreComplianceHelper.showProminentDisclosureDialog(
                    activity = this@MainActivity,
                    forceReviewMode = true
                )
            }
        }

        subProtectionRow.addView(batteryKeepAliveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        subProtectionRow.addView(complianceDisclosureBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 5B. 本地 AI 協同推理模式切換
        val modeTitleTv = TextView(this).apply {
            text = "本地 AI 推理架構"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }

        val aiModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRectBg("#F1F5F9", "#E2E8F0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val modeLabels = listOf(
            LocalAiBackendMode.DUAL_FUSION to "雙核協同",
            LocalAiBackendMode.ANDROID_NATIVE_AI to "自研結構核",
            LocalAiBackendMode.NEEDLE_3 to "自研向量核"
        )

        for ((m, shortTitle) in modeLabels) {
            val b = createStyledButton(shortTitle, "#F1F5F9", Color.parseColor("#475569"), cornerDp = 8).apply {
                textSize = 13f
                minHeight = dp(40)
                minimumHeight = dp(40)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    selectAiBackendMode(m)
                    runAiDecisionOnCurrentReport()
                }
            }
            modeButtons[m] = b
            aiModeRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
            })
        }

        // 5C. 第 11 項：5 分鐘 ＋ 30+ 種 App 穩定度壓力測試中心（含自訂測試集）
        val benchTitleTv = TextView(this).apply {
            text = "穩定度基準壓測（5 分鐘 · 30+ App）"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }

        benchmarkStatusTv = TextView(this).apply {
            text = "正在載入基礎測試集與本機 App 測試池..."
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(8))
        }

        startBenchmarkBtn = createStyledButton(
            text = "啟動 5 分鐘 (30+ App) 穩定度測試",
            bgHex = "#2563EB",
            textColor = Color.WHITE,
            strokeHex = "#1D4ED8",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener { handleBenchmarkButtonClick() }
        }

        customSuiteToggleBtn = createStyledButton(
            text = "自訂與管理測試集",
            bgHex = "#F8FAFC",
            textColor = Color.parseColor("#334155"),
            strokeHex = "#E2E8F0",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener {
                val hidden = customSuiteEditorLayout.visibility == View.GONE
                if (hidden) {
                    customSuiteEditorLayout.visibility = View.VISIBLE
                    text = "收合自訂測試集管理"
                    rebuildSuiteListView()
                } else {
                    customSuiteEditorLayout.visibility = View.GONE
                    text = "自訂與管理測試集"
                }
            }
        }

        customSuiteEditorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundRectBg("#F8FAFC", "#E2E8F0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val addHeaderTv = TextView(this).apply {
            text = "新增自訂測試項目"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        customAppEt = EditText(this).apply {
            hint = "目標 App 名稱（例如：YouTube、設定、Chrome）"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundRectBg("#FFFFFF", "#CBD5E1", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        customGoalEt = EditText(this).apply {
            hint = "要執行的測試指令（例如：打開 YouTube 搜尋新聞）"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundRectBg("#FFFFFF", "#CBD5E1", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val suiteManageBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val addCustomCaseBtn = createStyledButton("新增測試項", "#2563EB", Color.WHITE, strokeHex = "#1D4ED8", cornerDp = 12).apply {
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                val appHint = customAppEt.text?.toString()?.trim().orEmpty()
                val goalTxt = customGoalEt.text?.toString()?.trim().orEmpty()
                if (appHint.isEmpty() && goalTxt.isEmpty()) {
                    Toast.makeText(this@MainActivity, "請輸入目標 App 或測試指令", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val added = BenchmarkSuiteManager.addCustomTestCase(
                    context = applicationContext,
                    customTitle = "",
                    targetAppHint = appHint,
                    commandGoal = goalTxt
                )
                customAppEt.setText("")
                customGoalEt.setText("")
                rebuildSuiteListView()
                refreshBenchmarkAndFlightUi()
                Toast.makeText(this@MainActivity, "已新增自訂測試：${added.title}", Toast.LENGTH_SHORT).show()
            }
        }

        val syncAllPhoneAppsBtn = createStyledButton("匯入全機 App", "#FFFFFF", Color.parseColor("#0F172A"), strokeHex = "#CBD5E1", cornerDp = 12).apply {
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                submitWorkerWithTimeout(timeoutMs = 8_000L) {
                    val count = BenchmarkSuiteManager.autoMergeInstalledDeviceApps(applicationContext, saveAfter = true)
                    runOnUiThread {
                        rebuildSuiteListView()
                        refreshBenchmarkAndFlightUi()
                        Toast.makeText(this@MainActivity, "已同步本機 App（新增 $count 項，共 ${BenchmarkSuiteManager.ensureLoaded(applicationContext).size} 項）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val resetSuiteBtn = createStyledButton("重置預設集", "#F1F5F9", Color.parseColor("#475569"), strokeHex = "#E2E8F0", cornerDp = 12).apply {
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                submitWorkerWithTimeout(timeoutMs = 8_000L) {
                    val list = BenchmarkSuiteManager.resetToDefaultSuite(applicationContext)
                    runOnUiThread {
                        rebuildSuiteListView()
                        refreshBenchmarkAndFlightUi()
                        Toast.makeText(this@MainActivity, "已重置為 ${list.size} 項基礎＋本機測試集", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        suiteManageBtnRow.addView(addCustomCaseBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        suiteManageBtnRow.addView(syncAllPhoneAppsBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        suiteManageBtnRow.addView(resetSuiteBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        suiteListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val suiteScroll = ScrollView(this).apply {
            addView(suiteListContainer)
        }

        customSuiteEditorLayout.addView(addHeaderTv)
        customSuiteEditorLayout.addView(customAppEt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        customSuiteEditorLayout.addView(customGoalEt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        customSuiteEditorLayout.addView(suiteManageBtnRow)
        customSuiteEditorLayout.addView(suiteScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))

        // 5D. 第 9 項：每一步結構化執行軌跡日誌 & 實時視覺預覽
        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }

        val logTitleTv = TextView(this).apply {
            text = "執行軌跡日誌"
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val copyLogBtn = createStyledButton("複製日誌", "#F8FAFC", Color.parseColor("#0F172A"), strokeHex = "#CBD5E1", cornerDp = 8).apply {
            textSize = 11f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("FLIGHT_LOGS", StabilityFlightRecorder.getRecentLogsText(100)))
                Toast.makeText(this@MainActivity, "已複製執行軌跡日誌", Toast.LENGTH_SHORT).show()
            }
        }

        val clearLogBtn = createStyledButton("清空", "#F8FAFC", Color.parseColor("#64748B"), strokeHex = "#E2E8F0", cornerDp = 8).apply {
            textSize = 11f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                StabilityFlightRecorder.clearLogs(applicationContext)
                CrashGuardManager.clearCrashLogs(applicationContext)
                refreshBenchmarkAndFlightUi()
            }
        }

        logHeaderRow.addView(logTitleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logHeaderRow.addView(copyLogBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        logHeaderRow.addView(clearLogBtn)

        flightLogsTv = TextView(this).apply {
            text = StabilityFlightRecorder.getRecentLogsText(25)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1E293B"))
            background = roundRectBg("#F8FAFC", "#E2E8F0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        crashStatusTv = TextView(this).apply {
            text = CrashGuardManager.getRecentCrashSummary(applicationContext)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#475569"))
            background = roundRectBg("#F8FAFC", "#E2E8F0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        autoScanStatusTv = TextView(this).apply {
            text = "雙鎖定即時掃描已啟用"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#047857"))
            background = roundRectBg("#ECFDF5", "#A7F3D0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        visualizerView = VisionVisualizerView(this)

        // 將工程測試與黑盒子日誌收納進預設摺疊的「工程診斷與壓力測試」區塊，讓「設定」面板保持極簡清爽
        val devDiagContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        devDiagContainer.addView(benchTitleTv)
        devDiagContainer.addView(benchmarkStatusTv)
        devDiagContainer.addView(startBenchmarkBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        devDiagContainer.addView(customSuiteToggleBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        devDiagContainer.addView(customSuiteEditorLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        devDiagContainer.addView(logHeaderRow)
        devDiagContainer.addView(flightLogsTv)
        devDiagContainer.addView(crashStatusTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        devDiagContainer.addView(autoScanStatusTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        devDiagContainer.addView(visualizerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))

        val devDiagToggleBtn = createStyledButton(
            text = "展開工程診斷與壓力測試",
            bgHex = "#F8FAFC",
            textColor = Color.parseColor("#475569"),
            strokeHex = "#E2E8F0",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener {
                val isHidden = devDiagContainer.visibility == View.GONE
                if (isHidden) {
                    devDiagContainer.visibility = View.VISIBLE
                    text = "收合工程診斷與壓力測試"
                    refreshBenchmarkAndFlightUi()
                } else {
                    devDiagContainer.visibility = View.GONE
                    text = "展開工程診斷與壓力測試"
                }
            }
        }

        advancedDrawerLayout.addView(guardSectionTitleTv)
        advancedDrawerLayout.addView(systemBarBlockerToggleBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        advancedDrawerLayout.addView(subProtectionRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        advancedDrawerLayout.addView(modeTitleTv)
        advancedDrawerLayout.addView(aiModeRow)
        advancedDrawerLayout.addView(devDiagToggleBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        advancedDrawerLayout.addView(devDiagContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        mainContainer.addView(advancedDrawerLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        mainContainer.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        mainContainer.addView(chatBubbleContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        rootScroll.addView(mainContainer)
        screenRoot.addView(rootScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        screenRoot.addView(bottomDockCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val screenHInit = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        val initialTopBarPx = SystemBarBlockerGuard.getStatusBarHeightPx(this, screenHInit)
        val initialBottomBarPx = SystemBarBlockerGuard.getNavigationBarHeightPx(this, screenHInit)

        // 1. 頂部 Android 系統狀態列安全擋板（#09090B 實心擋板，動態對齊系統狀態列與鏡頭挖孔高度）
        val topLetterboxBar = View(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        // 2. 底部 Android 系統導覽列安全擋板（#09090B 實心擋板 + 頂部 1dp 分隔線，動態對齊底部導覽列與鍵盤高度）
        val bottomShieldDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#18181B"))
        }
        val bottomLetterboxBar = View(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        letterboxOuterContainer.addView(topLetterboxBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, initialTopBarPx))
        letterboxOuterContainer.addView(screenRoot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        letterboxOuterContainer.addView(bottomShieldDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))
        letterboxOuterContainer.addView(bottomLetterboxBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, initialBottomBarPx))

        // 動態監聽實機四向 WindowInsets，確保頂部與底部系統擋板精確貼合系統欄與軟鍵盤：
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            letterboxOuterContainer.setOnApplyWindowInsetsListener { v, windowInsets ->
                val sysInsets = windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars() or
                        android.view.WindowInsets.Type.displayCutout() or
                        android.view.WindowInsets.Type.tappableElement()
                )
                val imeInsets = windowInsets.getInsets(android.view.WindowInsets.Type.ime())

                v.setPadding(sysInsets.left, 0, sysInsets.right, 0)

                val exactTopPx = maxOf(sysInsets.top, initialTopBarPx)
                val exactBottomPx = maxOf(sysInsets.bottom, imeInsets.bottom, initialBottomBarPx)

                val topLp = topLetterboxBar.layoutParams
                if (topLp != null && topLp.height != exactTopPx) {
                    topLp.height = exactTopPx
                    topLetterboxBar.layoutParams = topLp
                }
                val botLp = bottomLetterboxBar.layoutParams
                if (botLp != null && botLp.height != exactBottomPx) {
                    botLp.height = exactBottomPx
                    bottomLetterboxBar.layoutParams = botLp
                }
                android.view.WindowInsets.CONSUMED
            }
        }

        setContentView(letterboxOuterContainer)

        selectAiBackendMode(LocalAiBackendMode.DUAL_FUSION)

        // 背景初始化雙本地 AI、本機已安裝 App 與 36+ 基礎測試集
        submitWorkerWithTimeout(timeoutMs = 30_000L) {
            NeedleIsolatedClient.init(applicationContext)
            NeedleIsolatedClient.bind()
            NeedleIsolatedClient.awaitReady(applicationContext, 25000L)
            val apps = InstalledAppLauncher.getInstalledLaunchableApps(applicationContext, forceRefresh = true)
            val suite = BenchmarkSuiteManager.ensureLoaded(applicationContext)
            runOnUiThread {
                aiEngineStatusTv.text = "雙模型就緒 · ${apps.size} 款應用 · 測試集 ${suite.size} 項"
                populateInstalledAppsRow(apps)
                refreshBenchmarkAndFlightUi()
            }
        }

        ConversationManager.initialize(applicationContext)
        ConversationManager.addObserver(chatObserver)
        renderChatBubbles(ConversationManager.getMessages())
    }

    override fun onDestroy() {
        super.onDestroy()
        ConversationManager.removeObserver(chatObserver)
        try {
            if (!worker.isShutdown) {
                worker.shutdown()
            }
        } catch (_: Throwable) {
        }
    }

    private fun copyChatTextToClipboard(rawText: String) {
        val clean = rawText.trim()
        if (clean.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("AI_CHAT_MESSAGE", clean))
        Toast.makeText(this, "已複製對話內容", Toast.LENGTH_SHORT).show()
    }

    private fun renderChatBubbles(messages: List<ChatMessage>) {
        chatBubbleContainer.removeAllViews()
        if (messages.isEmpty()) {
            // [heydamir/unslop] 極簡空狀態：無外框盒、無綠點、無裝飾圖示、無廢話副標，僅留 3 個範例句
            val emptyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(0, dp(16), 0, dp(4))
            }

            val hintTv = TextView(this).apply {
                text = "可以直接說："
                textSize = 12f
                setTextColor(Color.parseColor("#71717A"))
                setPadding(dp(4), 0, 0, dp(8))
            }
            emptyContainer.addView(hintTv)

            val quickPrompts = listOf(
                "記下買牛奶" to false,
                "去 YouTube 查詢周杰倫" to true,
                "你可以做什麼" to false,
                "陪看影片" to true
            )
            for ((promptText, autoExecute) in quickPrompts) {
                val pill = createStyledButton(
                    text = promptText,
                    bgHex = "#18181B",
                    textColor = Color.parseColor("#E4E4E7"),
                    strokeHex = "#27272A",
                    cornerDp = 12
                ).apply {
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minHeight = dp(48)
                    minimumHeight = dp(48)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener {
                        goalInputEt.setText(promptText)
                        goalInputEt.setSelection(promptText.length)
                        if (autoExecute) {
                            handleAutonomousAppOpenAndRun()
                        } else {
                            handleChatSendOnly()
                        }
                    }
                }
                emptyContainer.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                })
            }

            chatBubbleContainer.addView(emptyContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }

        for (msg in messages.takeLast(40)) {
            val bubble = when (msg.role) {
                ChatRole.USER -> {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(0, dp(4), 0, dp(4))
                        val tv = TextView(this@MainActivity).apply {
                            text = msg.text
                            textSize = 14f
                            setLineSpacing(0f, 1.3f)
                            setTextColor(Color.parseColor("#F4F4F5"))
                            background = roundRectBg("#27272A", "#27272A", dp(16).toFloat(), strokePx = 0)
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                            isLongClickable = true
                            setOnLongClickListener {
                                copyChatTextToClipboard(msg.text)
                                true
                            }
                        }
                        isLongClickable = true
                        setOnLongClickListener {
                            copyChatTextToClipboard(msg.text)
                            true
                        }
                        addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            leftMargin = dp(56)
                        })
                    }
                }
                ChatRole.AI -> {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.START
                        setPadding(0, dp(4), 0, dp(4))
                        val card = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = roundRectBg("#18181B", "#27272A", dp(16).toFloat(), strokePx = dp(1).coerceAtLeast(1))
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            isLongClickable = true
                            setOnLongClickListener {
                                copyChatTextToClipboard(msg.text)
                                true
                            }
                        }

                        val isNoteMsg = msg.text.startsWith("已記下筆記：") ||
                            msg.text.startsWith("最近的筆記：") ||
                            msg.text.startsWith("目前還沒有任何已記錄的筆記")
                        val isPermPrompt = msg.metadataHeader == "PERMISSION_PROMPT" && !msg.executableCommand.isNullOrBlank()

                        if (isNoteMsg) {
                            val notes = com.example.newandroidapp.action.SystemCapabilityHub.getNotesSnapshot(this@MainActivity)
                            if (notes.isEmpty()) {
                                val emptyNoteTv = TextView(this@MainActivity).apply {
                                    text = "目前沒有筆記。"
                                    textSize = 14f
                                    setTextColor(Color.parseColor("#A1A1AA"))
                                    isLongClickable = true
                                    setOnLongClickListener {
                                        copyChatTextToClipboard(msg.text)
                                        true
                                    }
                                }
                                card.addView(emptyNoteTv)
                            } else {
                                val headerTitle = if (msg.text.startsWith("已記下筆記：")) {
                                    msg.text.substringBefore("（").replace("筆記：", "")
                                } else {
                                    "筆記"
                                }
                                val titleTv = TextView(this@MainActivity).apply {
                                    text = headerTitle
                                    textSize = 12f
                                    setTextColor(Color.parseColor("#A1A1AA"))
                                    setPadding(0, 0, 0, dp(6))
                                    isLongClickable = true
                                    setOnLongClickListener {
                                        copyChatTextToClipboard(msg.text)
                                        true
                                    }
                                }
                                card.addView(titleTv)

                                for ((idx, note) in notes.withIndex()) {
                                    if (idx > 0) {
                                        card.addView(View(this@MainActivity).apply {
                                            setBackgroundColor(Color.parseColor("#27272A"))
                                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)))
                                    }
                                    val row = LinearLayout(this@MainActivity).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        gravity = Gravity.CENTER_VERTICAL
                                        setPadding(0, dp(8), 0, dp(8))
                                        isLongClickable = true
                                        setOnLongClickListener {
                                            copyChatTextToClipboard(note.content)
                                            true
                                        }
                                    }
                                    val contentTv = TextView(this@MainActivity).apply {
                                        text = note.content
                                        textSize = 14f
                                        setTextColor(Color.parseColor("#F4F4F5"))
                                        isLongClickable = true
                                        setOnLongClickListener {
                                            copyChatTextToClipboard(note.content)
                                            true
                                        }
                                    }
                                    val timeTv = TextView(this@MainActivity).apply {
                                        text = note.formattedTime
                                        textSize = 12f
                                        setTextColor(Color.parseColor("#71717A"))
                                        setPadding(dp(8), 0, dp(8), 0)
                                    }
                                    val delBtn = createStyledButton(
                                        text = "刪除",
                                        bgHex = "#18181B",
                                        textColor = Color.parseColor("#71717A"),
                                        strokeHex = "#18181B",
                                        cornerDp = 6
                                    ).apply {
                                        textSize = 12f
                                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                                        minWidth = 0
                                        minimumWidth = 0
                                        minHeight = 0
                                        minimumHeight = 0
                                        setPadding(dp(6), dp(4), dp(6), dp(4))
                                        setOnClickListener {
                                            com.example.newandroidapp.action.SystemCapabilityHub.deleteNoteByTimestamp(note.timestampMs, this@MainActivity)
                                            renderChatBubbles(ConversationManager.getMessages())
                                        }
                                    }
                                    row.addView(contentTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                                    row.addView(timeTv)
                                    row.addView(delBtn)
                                    card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                                }
                            }
                        } else {
                            val bodyTv = TextView(this@MainActivity).apply {
                                text = msg.text
                                textSize = 14f
                                setTextColor(Color.parseColor("#F4F4F5"))
                                setLineSpacing(0f, 1.32f)
                                isLongClickable = true
                                setOnLongClickListener {
                                    copyChatTextToClipboard(msg.text)
                                    true
                                }
                            }
                            card.addView(bodyTv)
                        }

                        // 若有候選清單，渲染極簡可點擊項目
                        if (msg.candidates.isNotEmpty()) {
                            val candCol = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, dp(8), 0, 0)
                            }
                            for (cand in msg.candidates.take(5)) {
                                val candBtn = createStyledButton(
                                    text = "執行第 ${cand.index} 項：${cand.label}",
                                    bgHex = "#27272A",
                                    textColor = Color.parseColor("#F4F4F5"),
                                    strokeHex = "#3F3F46",
                                    cornerDp = 10
                                ).apply {
                                    textSize = 13f
                                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                                    minHeight = dp(40)
                                    minimumHeight = dp(40)
                                    setPadding(dp(12), dp(8), dp(12), dp(8))
                                    setOnClickListener {
                                        handleAutonomousAppOpenAndRun("點選第 ${cand.index} 個 ${cand.label}")
                                    }
                                }
                                candCol.addView(candBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                    topMargin = dp(4)
                                })
                            }
                            card.addView(candCol)
                        }

                        // 權限說明氣泡（「允許」單一按鈕）或可執行指令按鈕
                        val execCmd = msg.executableCommand?.trim().orEmpty()
                        if (isPermPrompt) {
                            val allowBtn = createStyledButton(
                                text = "允許",
                                bgHex = "#F4F4F5",
                                textColor = Color.parseColor("#09090B"),
                                strokeHex = "#F4F4F5",
                                cornerDp = 10
                            ).apply {
                                textSize = 13f
                                minHeight = dp(40)
                                minimumHeight = dp(40)
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                                setOnClickListener {
                                    val missing = com.example.newandroidapp.action.SystemCapabilityHub.getMissingRuntimePermissions(execCmd, this@MainActivity)
                                    if (missing.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        pendingCapabilityPermissionGoal = execCmd
                                        pendingCapabilityIsAutonomousMode = false
                                        requestPermissions(missing.toTypedArray(), 1003)
                                    } else {
                                        completeChatSendReply(execCmd)
                                    }
                                }
                            }
                            card.addView(allowBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(10)
                            })
                        } else if (execCmd.isNotEmpty()) {
                            val btnTitle = if (execCmd == "確認執行") "確認執行" else "執行：$execCmd"
                            val inlineExecBtn = createStyledButton(
                                text = if (btnTitle.length > 22) btnTitle.take(22) + "..." else btnTitle,
                                bgHex = "#F4F4F5",
                                textColor = Color.parseColor("#09090B"),
                                strokeHex = "#F4F4F5",
                                cornerDp = 10
                            ).apply {
                                textSize = 13f
                                minWidth = 0
                                minimumWidth = 0
                                minHeight = dp(40)
                                minimumHeight = dp(40)
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                                setOnClickListener {
                                    handleAutonomousAppOpenAndRun(execCmd)
                                }
                            }
                            card.addView(inlineExecBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(10)
                            })
                        }

                        // 隱私小標：敏感回覆僅低調顯示「不留存紀錄」
                        if (msg.isSensitive && !isPermPrompt) {
                            val privTv = TextView(this@MainActivity).apply {
                                text = "不留存紀錄"
                                textSize = 11f
                                setTextColor(Color.parseColor("#71717A"))
                                setPadding(0, dp(6), 0, 0)
                            }
                            card.addView(privTv)
                        }

                        val cardWidth = if (isNoteMsg || isPermPrompt) {
                            (resources.displayMetrics.widthPixels * 0.84f).toInt()
                        } else {
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        }
                        addView(card, LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(32)
                        })
                    }
                }
            }
            chatBubbleContainer.addView(bubble)
        }
        rootScrollView?.post {
            rootScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceButtonState()
        updateVoiceAndTtsButtonState()
        updateSystemBarBlockerButtonUi()
        refreshInstalledAppsAsync(force = false)
        refreshBenchmarkAndFlightUi()
        uiHandler.removeCallbacks(uiRefreshTicker)
        uiHandler.postDelayed(uiRefreshTicker, 500L)

        val liveRep = VisionAccessibilityService.latestLiveReport
        if (liveRep != null && liveRep !== latestReport) {
            latestReport = liveRep
            updateAutoScanStatusBanner(liveRep)
            if (advancedDrawerLayout.visibility == View.VISIBLE) {
                visualizerView.updateData(VisionAccessibilityService.latestLiveBitmap, liveRep)
            }
            VisionAccessibilityService.latestLiveDecision?.let { renderDecisionResult(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshTicker)
    }

    private var pendingCapabilityPermissionGoal: String? = null
    private var pendingCapabilityIsAutonomousMode: Boolean = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                startVoiceListeningSession()
            } else {
                Toast.makeText(this, "未取得麥克風權限，無法使用語音下令", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 1003) {
            val resumeGoal = pendingCapabilityPermissionGoal
            val isAuto = pendingCapabilityIsAutonomousMode
            pendingCapabilityPermissionGoal = null
            if (!resumeGoal.isNullOrBlank()) {
                val anyGranted = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (!anyGranted) {
                    Toast.makeText(this, "未授予權限，將改用系統備援畫面開啟", Toast.LENGTH_SHORT).show()
                }
                if (isAuto) {
                    resumeAutonomousRunAfterPermission(resumeGoal)
                } else {
                    completeChatSendReply(resumeGoal)
                }
            }
        }
    }

    private fun updateSystemBarBlockerButtonUi() {
        val enabled = SystemBarBlockerGuard.isBlockerEnabled
        systemBarBlockerToggleBtn.text = if (enabled) {
            "頂底系統欄防護（真人三鍵穿透 / AI 禁區） · 已啟用"
        } else {
            "頂底系統欄防護（真人三鍵穿透 / AI 禁區） · 已關閉"
        }
        systemBarBlockerToggleBtn.setTextColor(
            if (enabled) Color.parseColor("#047857") else Color.parseColor("#475569")
        )
        systemBarBlockerToggleBtn.background = roundRectBg(
            fillHex = if (enabled) "#ECFDF5" else "#F8FAFC",
            strokeHex = if (enabled) "#A7F3D0" else "#E2E8F0",
            radius = dp(12).toFloat(),
            strokePx = dp(1).coerceAtLeast(1)
        )
    }

    private fun handleVoiceCommandClick() {
        if (!VoiceCommandAndTtsManager.hasRecordAudioPermission(this)) {
            Toast.makeText(this, "請允許麥克風權限以使用一鍵語音下令", Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1002)
            return
        }
        if (VoiceCommandAndTtsManager.isListening) {
            VoiceCommandAndTtsManager.stopListening()
            updateVoiceAndTtsButtonState()
            aiDecisionTv.text = "已停止語音聆聽。"
            return
        }
        startVoiceListeningSession()
    }

    private fun startVoiceListeningSession() {
        voiceInputBtn.text = "聆聽中"
        voiceInputBtn.setTextColor(Color.WHITE)
        voiceInputBtn.background = roundRectBg("#2563EB", "#1D4ED8", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
        aiDecisionTv.text = "正在聆聽語音指令..."

        VoiceCommandAndTtsManager.startListening(
            context = this,
            languageTag = "zh-TW",
            onPartialResult = { partial ->
                goalInputEt.setText(partial)
                goalInputEt.setSelection(partial.length)
                aiDecisionTv.text = "語音即時辨識中：「$partial」"
            },
            onFinalResult = { spokenText ->
                updateVoiceAndTtsButtonState()
                val clean = spokenText.trim()
                goalInputEt.setText(clean)
                goalInputEt.setSelection(clean.length)
                aiDecisionTv.text = "語音已轉成文字填入輸入框，請確認後點擊「發送」或「執行」。"
            },
            onStatusOrError = { msg ->
                updateVoiceAndTtsButtonState()
                aiDecisionTv.text = msg
            }
        )
    }

    private fun updateVoiceAndTtsButtonState() {
        val hasInputText = goalInputEt.text?.toString()?.trim()?.isNotEmpty() == true
        when {
            VoiceCommandAndTtsManager.isListening -> {
                voiceInputBtn.text = "聆聽中"
                voiceInputBtn.setTextColor(Color.parseColor("#09090B"))
                voiceInputBtn.background = roundRectBg("#F4F4F5", "#F4F4F5", dp(12).toFloat(), strokePx = 0)
            }
            hasInputText -> {
                voiceInputBtn.text = "送出"
                voiceInputBtn.setTextColor(Color.parseColor("#09090B"))
                voiceInputBtn.background = roundRectBg("#F4F4F5", "#F4F4F5", dp(12).toFloat(), strokePx = 0)
            }
            else -> {
                voiceInputBtn.text = "語音"
                voiceInputBtn.setTextColor(Color.parseColor("#F4F4F5"))
                voiceInputBtn.background = roundRectBg("#27272A", "#27272A", dp(12).toFloat(), strokePx = 0)
            }
        }

        if (VoiceCommandAndTtsManager.isTtsEnabled) {
            ttsToggleBtn.text = "播報·開"
            ttsToggleBtn.setTextColor(Color.parseColor("#047857"))
            ttsToggleBtn.background = roundRectBg("#ECFDF5", "#A7F3D0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
        } else {
            ttsToggleBtn.text = "播報·關"
            ttsToggleBtn.setTextColor(Color.parseColor("#64748B"))
            ttsToggleBtn.background = roundRectBg("#F8FAFC", "#E2E8F0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
        }
    }

    private fun refreshBenchmarkAndFlightUi() {
        val suite = BenchmarkSuiteManager.ensureLoaded(applicationContext)
        val prog = BenchmarkSuiteManager.latestProgress
        val running = VisionAccessibilityService.isBenchmarkRunning

        if (running) {
            startBenchmarkBtn.text = "停止 5 分鐘穩定度壓力測試 (${prog.formatElapsedMmSs()})"
            startBenchmarkBtn.background = roundRectBg("#DC2626", "#B91C1C", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            val passRate = if (prog.totalCasesCompleted > 0) (prog.passedCasesCount * 100) / prog.totalCasesCompleted else 100
            benchmarkStatusTv.text = buildString {
                appendLine("正在執行 5 分鐘 · 30+ App 穩定度壓力測試：")
                appendLine("• 已執行時間：${prog.formatElapsedMmSs()} (目標 >= 05:00)")
                appendLine("• 已開啟測試 App / 系統模組：${prog.distinctAppsOpenedCount} / ${prog.targetAppsCount}+ 種")
                appendLine("• 正在測試項目 [${prog.currentCaseIndex}/${prog.totalSuiteSize}]：${prog.currentCaseTitle}")
                append("• 完成回合：${prog.totalCasesCompleted} 次 (穩定率 $passRate%) | 視覺均耗 ${prog.avgVisionLatencyMs}ms | RAM ${prog.usedRamMb}MB")
            }
        } else {
            startBenchmarkBtn.text = "啟動 5 分鐘 (30+ App) 穩定度測試"
            startBenchmarkBtn.background = roundRectBg("#2563EB", "#1D4ED8", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            val interruptedNote = if (BenchmarkSuiteManager.wasLastRunInterrupted(applicationContext) && prog.finalSummaryReport.isBlank()) {
                "偵測到上一次壓力測試未正常結束，請查看下方閃退紀錄與執行日誌。\n\n"
            } else ""
            if (prog.finalSummaryReport.isNotBlank()) {
                benchmarkStatusTv.text = prog.finalSummaryReport
            } else {
                benchmarkStatusTv.text = interruptedNote + "目前測試集共 ${suite.size} 項（內建 36 項基礎測試 ＋ 自動融合本機已安裝 App）。點擊下方按鈕將連續測試至少 5 分鐘並實際切換至少 30 種不同 App。"
            }
        }

        if (customSuiteEditorLayout.visibility == View.GONE) {
            customSuiteToggleBtn.text = "自訂與管理測試集（共 ${suite.size} 項）"
        }

        if (advancedDrawerLayout.visibility == View.VISIBLE) {
            flightLogsTv.text = StabilityFlightRecorder.getRecentLogsText(25)
            crashStatusTv.text = CrashGuardManager.getRecentCrashSummary(applicationContext)
        }
    }

    private fun rebuildSuiteListView() {
        suiteListContainer.removeAllViews()
        val cases = BenchmarkSuiteManager.ensureLoaded(applicationContext)
        for (c in cases) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val infoTv = TextView(this).apply {
                text = "${c.title}\n指令：${c.commandGoal}"
                textSize = 11f
                setTextColor(if (c.isCustomUserAdded) Color.parseColor("#047857") else Color.parseColor("#334155"))
            }
            val delBtn = createStyledButton("刪除", "#FEF2F2", Color.parseColor("#DC2626"), strokeHex = "#FECACA", cornerDp = 8).apply {
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    BenchmarkSuiteManager.removeTestCase(applicationContext, c.id)
                    rebuildSuiteListView()
                    refreshBenchmarkAndFlightUi()
                }
            }
            row.addView(infoTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            row.addView(delBtn)
            suiteListContainer.addView(row)
        }
    }

    private fun handleBenchmarkButtonClick() {
        val svc = VisionAccessibilityService.instance
        if (VisionAccessibilityService.isBenchmarkRunning) {
            svc?.stopBenchmarkSuite("使用者手動停止測試")
            refreshBenchmarkAndFlightUi()
            Toast.makeText(this, "已停止穩定度壓力測試", Toast.LENGTH_SHORT).show()
            return
        }

        if (svc == null) {
            PlayStoreComplianceHelper.ensureDisclosureBeforeAccessibilityAction(this) {
                Toast.makeText(
                    this,
                    "壓力測試需要跨 App 系統手勢，請先在無障礙設定中開啟「AI 助手」",
                    Toast.LENGTH_LONG
                ).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            return
        }

        BenchmarkSuiteManager.latestProgress = BenchmarkSuiteManager.BenchmarkLiveProgress()
        svc.startFiveMinuteBenchmarkSuite(
            minDurationSeconds = BenchmarkSuiteManager.DEFAULT_MIN_DURATION_SECONDS,
            minAppsTarget = BenchmarkSuiteManager.DEFAULT_MIN_APPS_TARGET
        )
        refreshBenchmarkAndFlightUi()
        Toast.makeText(this, "已啟動 5 分鐘 (30+ 種不同 App) 穩定度壓力測試", Toast.LENGTH_LONG).show()
    }

    private fun refreshInstalledAppsAsync(force: Boolean) {
        submitWorkerWithTimeout(timeoutMs = 10_000L) {
            val apps = InstalledAppLauncher.getInstalledLaunchableApps(applicationContext, forceRefresh = force)
            BenchmarkSuiteManager.autoMergeInstalledDeviceApps(applicationContext, saveAfter = true)
            val suite = BenchmarkSuiteManager.ensureLoaded(applicationContext)
            runOnUiThread {
                aiEngineStatusTv.text = "原生 AI + Needle 3 隔離守護 · 已載入 ${apps.size} 款應用 · 測試集 ${suite.size} 項"
                populateInstalledAppsRow(apps)
                refreshBenchmarkAndFlightUi()
            }
        }
    }

    private fun populateInstalledAppsRow(apps: List<InstalledAppLauncher.InstalledAppInfo>) {
        installedAppsRow.removeAllViews()

        val readScreenChip = createStyledButton(
            text = "朗讀畫面",
            bgHex = "#F8FAFC",
            textColor = Color.parseColor("#0F172A"),
            strokeHex = "#CBD5E1",
            cornerDp = 12
        ).apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                goalInputEt.setText("讀畫面")
                goalInputEt.setSelection(goalInputEt.text.length)
                handleAutonomousAppOpenAndRun()
            }
        }
        installedAppsRow.addView(readScreenChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        for (app in apps.take(40)) {
            val isSelected = app.label == selectedAppLabel
            val chip = createStyledButton(
                text = app.label,
                bgHex = if (isSelected) "#EFF6FF" else "#F8FAFC",
                textColor = if (isSelected) Color.parseColor("#2563EB") else Color.parseColor("#475569"),
                strokeHex = if (isSelected) "#93C5FD" else "#E2E8F0",
                cornerDp = 12
            ).apply {
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(36)
                minimumHeight = dp(36)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    selectedAppLabel = app.label
                    populateInstalledAppsRow(apps)
                    val currentText = goalInputEt.text?.toString()?.trim().orEmpty()
                    val newGoal = if (currentText.isEmpty() || currentText.startsWith("打開 ")) {
                        "打開 ${app.label} "
                    } else {
                        "打開 ${app.label} 然後 $currentText"
                    }
                    goalInputEt.setText(newGoal)
                    goalInputEt.setSelection(newGoal.length)
                }
            }
            installedAppsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
    }

    private fun isDirectPhoneControlGoal(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if (com.example.newandroidapp.action.SystemCapabilityHub.parseRequest(clean) != null) {
            return false
        }
        if (com.example.newandroidapp.vision.ScreenTextCapture.isCaptureRequest(clean)) {
            return false
        }
        if (SmartDailyAssistantHelper.isScreenReadingCommand(clean)) return true
        if (com.example.newandroidapp.vision.LiveScreenCompanionEngine.isStartCompanionCommand(clean) ||
            com.example.newandroidapp.vision.LiveScreenCompanionEngine.isStopCompanionCommand(clean)
        ) {
            return true
        }
        if (com.example.newandroidapp.action.AndroidNativeSystemActionExecutor.parseCommand(clean) != null) return true
        return Regex("^(?:幫我|請|替我)?(?:去|到|打開|開啟|啟動|執行|點選|點擊|滑動|往下滑|往上滑)\\s*\\S+.*").containsMatchIn(clean)
    }

    private fun handleUnifiedUserSubmit() {
        val text = goalInputEt.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            handleVoiceCommandClick()
            return
        }
        if (isDirectPhoneControlGoal(text)) {
            handleAutonomousAppOpenAndRun()
        } else {
            handleChatSendOnly()
        }
    }

    /**
     * 極簡純對話模式：
     * 100% 保證在對話串流給出自然繁體中文回覆，絕不盲目在畫面上捲動或點擊。
     * 若訊息包含可執行的手機操作指令，會在 AI 氣泡內嵌「執行」鍵供使用者按需觸發。
     */
    private fun handleChatSendOnly() {
        val text = goalInputEt.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            return
        }
        ConversationManager.appendUserMessage(text)
        goalInputEt.setText("")

        // 若使用者輸入的是高風險安全閘門「確認執行」，且確實有待確認動作，直接放行執行
        val turnCtx = ConversationManager.getTurnContext()
        val safetyRes = SafetyGateManager.checkSafetyConfirmation(text, turnCtx)
        if (safetyRes is SafetyGateManager.SafetyConfirmationResult.Confirmed) {
            val svc = VisionAccessibilityService.instance
            if (svc != null) {
                svc.startAutonomousTaskLoop(text, currentAiMode, lockToCurrentApp = false)
                updateServiceButtonState()
                aiDecisionTv.text = "已確認執行高風險操作：「${safetyRes.pendingAction.label}」"
                return
            }
        }

        val missingPerms = com.example.newandroidapp.action.SystemCapabilityHub.getMissingRuntimePermissions(text, this)
        if (missingPerms.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val permName = when {
                missingPerms.contains(android.Manifest.permission.READ_CONTACTS) -> "通訊錄"
                missingPerms.contains(android.Manifest.permission.READ_CALENDAR) -> "行事曆"
                else -> "位置"
            }
            ConversationManager.appendAiMessage(
                text = "查詢「$text」需要${permName}權限",
                metadataHeader = "PERMISSION_PROMPT",
                isProactive = false,
                executableCommand = text,
                isSensitive = true
            )
            return
        }

        completeChatSendReply(text)
    }

    private fun completeChatSendReply(text: String) {
        val turnCtx = ConversationManager.getTurnContext()
        val liveRep = VisionAccessibilityService.latestLiveReport ?: latestReport
        val (replyText, execCmd) = AndroidNativeAiEngine.generateConversationalReply(
            userText = text,
            report = liveRep,
            context = applicationContext
        )

        // 回覆含私人內容（畫面文字、通知、剪貼簿等）時，只顯示不落地
        val replyIsSensitive = com.example.newandroidapp.action.SystemCapabilityHub.isPrivateContentQuery(text)
        ConversationManager.appendAiMessage(
            text = replyText,
            metadataHeader = "AI 助手",
            isProactive = false,
            executableCommand = execCmd,
            isSensitive = replyIsSensitive
        )

        ConversationManager.updateTurnContext(
            turnCtx.copy(
                lastSearchQuery = if (replyIsSensitive) turnCtx.lastSearchQuery else (execCmd ?: turnCtx.lastSearchQuery),
                lastAiPrompt = if (replyIsSensitive) turnCtx.lastAiPrompt else replyText,
                timestamp = System.currentTimeMillis()
            )
        )

        aiDecisionTv.text = replyText.lines().firstOrNull()?.take(60) ?: replyText
        VoiceCommandAndTtsManager.speak(this, replyText, flush = true)
    }

    /**
     * 實際操控手機執行模式（「執行」按鈕 或 AI 氣泡內嵌「執行」鍵）：
     * 若輸入框為空，會自動抓取上一輪對話中待執行的指令直接執行！
     */
    private fun handleAutonomousAppOpenAndRun(explicitGoal: String? = null) {
        val rawInput = explicitGoal?.trim().takeIf { !it.isNullOrEmpty() }
            ?: goalInputEt.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: ConversationManager.getMessages().lastOrNull { it.role == ChatRole.AI && !it.executableCommand.isNullOrBlank() }?.executableCommand
            ?: ConversationManager.getTurnContext().lastSearchQuery.orEmpty()

        val rawGoal = rawInput.trim()
        if (rawGoal.isEmpty()) {
            Toast.makeText(this, "請先輸入想執行的手機操作指令（例如：打開 YouTube 搜尋周杰倫）", Toast.LENGTH_SHORT).show()
            return
        }

        // 執行前先透過規劃引擎將疑問句或查詢（如「誰是蔣介石」）轉化為完整可執行命令（「打開 Chrome 搜尋 蔣介石」）
        val liveRepForPlan = VisionAccessibilityService.latestLiveReport ?: latestReport
        val goal = com.example.newandroidapp.ai.LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal(
            rawGoal = rawGoal,
            context = applicationContext,
            currentReport = liveRepForPlan
        )

        // 若是使用者從輸入框按「執行」或點擊內嵌「執行」，寫入對話紀錄讓上下文完整
        if (explicitGoal != null || !goalInputEt.text.isNullOrBlank()) {
            ConversationManager.appendUserMessage(
                if (explicitGoal != null) "執行：$goal" else rawGoal
            )
            goalInputEt.setText("")
        }

        val missingPerms = com.example.newandroidapp.action.SystemCapabilityHub.getMissingRuntimePermissions(goal, this)
        if (missingPerms.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingCapabilityPermissionGoal = goal
            pendingCapabilityIsAutonomousMode = true
            requestPermissions(missingPerms.toTypedArray(), 1003)
            return
        }

        resumeAutonomousRunAfterPermission(goal)
    }

    private fun resumeAutonomousRunAfterPermission(goal: String) {
        VisionAccessibilityService.activeGoalForHud = goal
        VisionAccessibilityService.selectedAiMode = currentAiMode

        val svc = VisionAccessibilityService.instance
        if (com.example.newandroidapp.vision.LiveScreenCompanionEngine.isStopCompanionCommand(goal)) {
            svc?.stopCompanionModeFromUser() ?: com.example.newandroidapp.vision.LiveScreenCompanionEngine.stopCompanionMode()
            val stopMsg = "已經幫你結束陪看模式，有需要隨時再叫我！"
            aiDecisionTv.text = stopMsg
            ConversationManager.appendAiMessage(text = stopMsg, metadataHeader = "陪看模式 · 已結束")
            return
        }
        if (com.example.newandroidapp.vision.LiveScreenCompanionEngine.isStartCompanionCommand(goal)) {
            if (svc != null) {
                svc.startCompanionModeFromUser()
                updateServiceButtonState()
                val startMsg = "陪看模式已開啟！現在去滑短影片（Shorts / Reels / TikTok）或瀏覽任何畫面，我會在圓形懸浮球旁跳出文字氣泡即時陪你聊（純文字不發聲）。想結束時說「停止陪看」即可。"
                aiDecisionTv.text = startMsg
                ConversationManager.appendAiMessage(text = startMsg, metadataHeader = "即時陪看 · 已啟動")
            } else {
                com.example.newandroidapp.vision.LiveScreenCompanionEngine.startCompanionMode()
                val needHudMsg = "已準備好開啟陪看模式！請先允許並開啟側邊懸浮球，就能在看影片時即時跳出文字氣泡陪聊。"
                aiDecisionTv.text = needHudMsg
                ConversationManager.appendAiMessage(
                    text = needHudMsg,
                    metadataHeader = "需要開啟懸浮球",
                    executableCommand = "陪看影片"
                )
                handleLiveServiceAction()
            }
            return
        }
        if (SmartDailyAssistantHelper.isScreenReadingCommand(goal)) {
            if (svc != null) {
                svc.readCurrentScreenSummaryAloud { summary ->
                    aiDecisionTv.text = summary
                    ConversationManager.appendAiMessage(text = summary, metadataHeader = "畫面朗讀", isSensitive = true)
                }
            } else {
                val rep = latestReport
                if (rep != null) {
                    val (displaySummary, spokenSummary) = SmartDailyAssistantHelper.buildSmartScreenSummary("AI 助手", rep)
                    aiDecisionTv.text = displaySummary
                    ConversationManager.appendAiMessage(text = displaySummary, metadataHeader = "畫面朗讀", isSensitive = true)
                    VoiceCommandAndTtsManager.speakForced(this, spokenSummary, flush = true)
                } else {
                    val msg = "目前尚未開啟無障礙視覺服務，請先點擊右上角「開啟懸浮球」啟用服務以朗讀跨 App 畫面。"
                    ConversationManager.appendAiMessage(text = msg, metadataHeader = "系統提示")
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // 【Android 原生系統指令直通車】：調音量、設鬧鐘/計時器、開關手電筒、系統動作、設定子頁直達（0 秒免開 App 點按鈕）
        val nativeSysRes = com.example.newandroidapp.action.AndroidNativeSystemActionExecutor.tryExecuteIfMatched(
            rawGoal = goal,
            context = applicationContext,
            accessibilityService = svc
        )
        if (nativeSysRes.handled) {
            aiDecisionTv.text = nativeSysRes.summaryText
            ConversationManager.appendAiMessage(
                text = nativeSysRes.summaryText,
                metadataHeader = if (nativeSysRes.succeeded) "原生系統指令 · 已完成" else "原生系統指令 · 提示",
                isSensitive = com.example.newandroidapp.action.SystemCapabilityHub.isPrivateContentQuery(goal)
            )
            VoiceCommandAndTtsManager.speak(this, nativeSysRes.summaryText, flush = true)
            return
        }

        if (svc != null) {
            ConversationManager.appendAiMessage(
                text = "正在為您自動執行：「$goal」...",
                metadataHeader = "執行中"
            )
            svc.startAutonomousTaskLoop(goal, currentAiMode, lockToCurrentApp = false)
            updateServiceButtonState()
            aiDecisionTv.text = "正在執行任務：「$goal」"
            return
        }

        submitWorkerWithTimeout(
            timeoutMs = 8_000L,
            onTimeoutUi = { aiDecisionTv.text = "啟動應用程式逾時，請稍後再試。" }
        ) {
            val appMatch = InstalledAppLauncher.resolveTargetAppForGoal(
                context = applicationContext,
                rawUserGoal = goal,
                needleAppHint = null
            )
            runOnUiThread {
                if (appMatch != null) {
                    val (matchedApp, matchScore) = appMatch
                    val launched = InstalledAppLauncher.launchApp(applicationContext, matchedApp.packageName)
                    val statusMsg = buildString {
                        appendLine("已為您啟動應用程式：「${matchedApp.label}」（匹配度 ${(matchScore * 100).toInt()}%）。")
                        if (!launched) appendLine("無法直接啟動該應用程式。")
                        append("若要讓 AI 進入 App 後繼續自動點擊或輸入搜尋，請點右上角「開啟懸浮球」啟用無障礙服務。")
                    }
                    aiDecisionTv.text = statusMsg
                    ConversationManager.appendAiMessage(text = statusMsg, metadataHeader = "應用啟動")
                } else {
                    val needSvcMsg = "要自動執行手機畫面操作（「$goal」），需先開啟無障礙服務。已為您開啟引導設定。"
                    ConversationManager.appendAiMessage(
                        text = needSvcMsg,
                        metadataHeader = "需要無障礙權限",
                        executableCommand = goal
                    )
                    Toast.makeText(this, "請先開啟無障礙服務以執行跨 App 自動操作", Toast.LENGTH_LONG).show()
                    handleLiveServiceAction()
                }
            }
        }
    }

    private fun selectAiBackendMode(mode: LocalAiBackendMode) {
        currentAiMode = mode
        VisionAccessibilityService.selectedAiMode = mode
        for ((m, btn) in modeButtons) {
            if (m == mode) {
                btn.setTextColor(Color.WHITE)
                btn.background = roundRectBg("#0F172A", "#0F172A", dp(8).toFloat(), strokePx = 0)
            } else {
                btn.setTextColor(Color.parseColor("#475569"))
                btn.background = roundRectBg("#F1F5F9", "#F1F5F9", dp(8).toFloat(), strokePx = 0)
            }
        }
    }

    private fun runAiDecisionOnCurrentReport() {
        val rep = latestReport ?: return
        val goal = goalInputEt.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) return
        VisionAccessibilityService.activeGoalForHud = goal

        submitWorkerWithTimeout(
            timeoutMs = 8_000L,
            onTimeoutUi = { aiDecisionTv.text = "單步推論逾時，請重試。" }
        ) {
            val dec: AiActionDecision = DualLocalAiCoordinator.decide(
                mode = currentAiMode,
                userGoal = goal,
                report = rep,
                context = applicationContext,
                currentForegroundPackage = null,
                alreadyLaunchedApp = false,
                activeSubGoalIndex = 0
            )
            runOnUiThread {
                renderDecisionResult(dec)
            }
        }
    }

    private fun renderDecisionResult(dec: AiActionDecision) {
        visualizerView.aiLockedNode = dec.targetNode
        val targetDesc = dec.targetNode?.label?.takeIf { it.isNotBlank() }
            ?: dec.targetAppLabel
            ?: dec.compactOpcode

        aiDecisionTv.text = buildString {
            appendLine("決策動作：$targetDesc （信心度 ${(dec.confidence * 100).toInt()}% | 耗時 ${dec.latencyMs}ms）")
            dec.cooperativeLog?.let { coop ->
                append("流程：${coop.stage1Decomposition} -> ${coop.stage4SafetyAndCoord}")
            } ?: append("依據：${dec.reasoningTrace}")
        }
    }

    private fun updateServiceButtonState() {
        val svc = VisionAccessibilityService.instance
        if (svc != null && svc.isFloatingHudShown()) {
            serviceStatusBtn.text = "關閉懸浮球"
            serviceStatusBtn.setTextColor(Color.parseColor("#E4E4E7"))
            serviceStatusBtn.background = roundRectBg("#09090B", "#09090B", dp(8).toFloat(), strokePx = 0)
        } else {
            serviceStatusBtn.text = "懸浮球"
            serviceStatusBtn.setTextColor(Color.parseColor("#71717A"))
            serviceStatusBtn.background = roundRectBg("#09090B", "#09090B", dp(8).toFloat(), strokePx = 0)
        }

        val whitelisted = KeepAliveHelper.isIgnoringBatteryOptimizations(this)
        if (whitelisted) {
            batteryKeepAliveBtn.text = "電池設定 · 已最佳化"
            batteryKeepAliveBtn.setTextColor(Color.parseColor("#047857"))
            batteryKeepAliveBtn.background = roundRectBg("#ECFDF5", "#A7F3D0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
        } else {
            batteryKeepAliveBtn.text = "省電最佳化設定"
            batteryKeepAliveBtn.setTextColor(Color.parseColor("#334155"))
            batteryKeepAliveBtn.background = roundRectBg("#F8FAFC", "#E2E8F0", dp(12).toFloat(), strokePx = dp(1).coerceAtLeast(1))
        }
    }

    private fun handleLiveServiceAction() {
        val svc = VisionAccessibilityService.instance
        if (svc != null) {
            if (svc.isFloatingHudShown()) {
                com.example.newandroidapp.vision.HudPersistentStateManager.setHudPreferredEnabled(this, false)
                svc.removeFloatingHud()
                updateServiceButtonState()
                Toast.makeText(this, "已關閉側邊懸浮球", Toast.LENGTH_SHORT).show()
            } else {
                PlayStoreComplianceHelper.ensureDisclosureBeforeAccessibilityAction(this) {
                    com.example.newandroidapp.vision.HudPersistentStateManager.setHudPreferredEnabled(this, true)
                    VisionAccessibilityService.activeGoalForHud = goalInputEt.text?.toString()?.trim().orEmpty()
                    VisionAccessibilityService.selectedAiMode = currentAiMode
                    svc.toggleFloatingHud(true)
                    serviceStatusBtn.postDelayed({ updateServiceButtonState() }, 150L)
                    Toast.makeText(this, "已開啟側邊 AI 懸浮球（點擊可展開指令與語音搜尋）", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // 若系統無障礙設定中其實已經開啟本服務（例如剛完成 APK 更新或服務正在背景毫秒級重連中），
        // 先設定偏好並等待 450ms 讓無障礙服務自動完成重連與掛載，絕不誤跳至系統設定頁面！
        if (com.example.newandroidapp.vision.HudPersistentStateManager.isAccessibilityServiceEnabledInSettings(this)) {
            PlayStoreComplianceHelper.ensureDisclosureBeforeAccessibilityAction(this) {
                com.example.newandroidapp.vision.HudPersistentStateManager.setHudPreferredEnabled(this, true)
                VisionAccessibilityService.activeGoalForHud = goalInputEt.text?.toString()?.trim().orEmpty()
                VisionAccessibilityService.selectedAiMode = currentAiMode
                Toast.makeText(this, "正在喚醒側邊懸浮球...", Toast.LENGTH_SHORT).show()
                uiHandler.postDelayed({
                    val reconnectedSvc = VisionAccessibilityService.instance
                    if (reconnectedSvc != null) {
                        reconnectedSvc.toggleFloatingHud(true)
                        updateServiceButtonState()
                        Toast.makeText(this, "已開啟側邊 AI 懸浮球", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "系統無障礙服務需重新授權，請在設定中重新切換「AI 助手」", Toast.LENGTH_LONG).show()
                        runCatching {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                }, 450L)
            }
            return
        }

        PlayStoreComplianceHelper.ensureDisclosureBeforeAccessibilityAction(this) {
            com.example.newandroidapp.vision.HudPersistentStateManager.setHudPreferredEnabled(this, true)
            Toast.makeText(this, "請在無障礙設定中開啟「AI 助手」", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun updateAutoScanStatusBanner(rep: VisionPipelineReport) {
        val frameSeq = VisionAccessibilityService.liveFrameSeq
        val totalLatencyMs = rep.pipelineLatencyMs
        val candidateCount = rep.finalNodes.size
        val barSummary = SystemBarBlockerGuard.statusSummary(this, resources.displayMetrics.heightPixels)
        autoScanStatusTv.text = "雙鎖定即時掃描已啟用 · 幀序 #$frameSeq | 耗時 ${totalLatencyMs}ms | 候選節點 ${candidateCount} 個\n$barSummary"
    }

    private fun captureSelfOrLoadLiveReport() {
        val liveRep = VisionAccessibilityService.latestLiveReport
        val liveBmp = VisionAccessibilityService.latestLiveBitmap
        if (liveRep != null) {
            latestReport = liveRep
            visualizerView.updateData(liveBmp, liveRep)
            updateAutoScanStatusBanner(liveRep)
            return
        }

        window.decorView.post {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.coerceAtLeast(720)
            val sh = dm.heightPixels.coerceAtLeast(1280)
            val rawNodes = ArrayList<RawVisionNode>()
            var idSeq = 1

            fun collectViewTree(view: View, depth: Int, parentId: String?): String? {
                if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) return null
                if (view === visualizerView) return null
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
                if (rect.width() <= 2 || rect.height() <= 2) return null

                val myId = "v_${idSeq++}"
                val childIds = ArrayList<String>()
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val cid = collectViewTree(view.getChildAt(i), depth + 1, myId)
                        if (cid != null) childIds.add(cid)
                    }
                }

                val txt = when (view) {
                    is Button -> view.text?.toString().orEmpty()
                    is EditText -> view.text?.toString().orEmpty()
                    is TextView -> view.text?.toString().orEmpty()
                    else -> ""
                }
                val hintTxt = if (view is EditText) view.hint?.toString().orEmpty() else ""

                rawNodes.add(
                    RawVisionNode(
                        id = myId,
                        windowId = 1,
                        windowLayer = 0,
                        zOrderRank = depth,
                        className = view.javaClass.name,
                        packageName = packageName,
                        bounds = rect,
                        text = txt,
                        contentDescription = view.contentDescription?.toString().orEmpty(),
                        hintText = hintTxt,
                        isClickable = view.isClickable,
                        isCheckable = view is CheckBox,
                        isChecked = (view as? CheckBox)?.isChecked == true,
                        isSelected = view.isSelected,
                        isEditable = view is EditText,
                        isScrollable = view is ScrollView || view is HorizontalScrollView,
                        isEnabled = view.isEnabled,
                        isOpaqueOverlay = view.background != null && view !is ScrollView,
                        hasRenderedContent = txt.isNotBlank() || hintTxt.isNotBlank() || view is Button,
                        parentId = parentId,
                        childIds = childIds
                    )
                )
                return myId
            }

            collectViewTree(window.decorView, 0, null)
            rawNodes.reverse()
            for (i in rawNodes.indices) {
                rawNodes[i] = rawNodes[i].copy(zOrderRank = i)
            }

            val snapBmp = runCatching {
                val b = Bitmap.createBitmap(
                    window.decorView.width.coerceAtLeast(1),
                    window.decorView.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val c = Canvas(b)
                window.decorView.draw(c)
                b
            }.getOrNull()

            submitWorkerWithTimeout(timeoutMs = 6_000L) {
                val report = AiVisionPipeline.execute(
                    rawNodes = rawNodes,
                    screenWidth = sw,
                    screenHeight = sh,
                    screenBitmap = snapBmp,
                    useNormalizedGrid = true
                )
                runOnUiThread {
                    latestReport = report
                    visualizerView.updateData(snapBmp, report)
                    updateAutoScanStatusBanner(report)
                }
            }
        }
    }

    /**
     * [LottieFiles / motion-design-skill] & [Animate UI / motion-skills]
     * 為每個互動按鈕掛載 GPU 硬體加速 (.withLayer()) 的按壓下沉 (90ms -> 0.95f) 與彈簧釋放回彈 (220ms -> 1.0f) 微動效
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTactileSpringMotion(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(90L)
                        .setInterpolator(m3StandardInterpolator)
                        .withLayer()
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220L)
                        .setInterpolator(springOvershootInterpolator)
                        .withLayer()
                        .start()
                }
            }
            false
        }
    }

    private fun createStyledButton(
        text: String,
        bgHex: String,
        textColor: Int,
        strokeHex: String = bgHex,
        cornerDp: Int = 12
    ): Button {
        return Button(this).apply {
            this.text = text
            this.textSize = 13f
            this.isAllCaps = false
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            stateListAnimator = null
            background = roundRectBg(bgHex, strokeHex, dp(cornerDp).toFloat(), strokePx = dp(1).coerceAtLeast(1))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            attachTactileSpringMotion(this)
        }
    }

    private fun roundRectBg(
        fillHex: String,
        strokeHex: String,
        radius: Float,
        strokePx: Int = dp(1).coerceAtLeast(1)
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = radius
            if (strokePx > 0) {
                setStroke(strokePx, Color.parseColor(strokeHex))
            }
        }
    }
}

