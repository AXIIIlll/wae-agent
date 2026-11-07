package com.example.newandroidapp.vision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.newandroidapp.vision.dataset.Phase1TreeImmunityConfig
import com.example.newandroidapp.vision.dataset.Phase2ButtonFunctionalityClassifier
import com.example.newandroidapp.vision.dataset.Phase3ScreenPostureClassifier
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.newandroidapp.MainActivity
import com.example.newandroidapp.ai.AiActionDecision
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.CrossAppWorkflowEngine
import com.example.newandroidapp.ai.DualLocalAiCoordinator
import com.example.newandroidapp.ai.InstalledAppLauncher
import com.example.newandroidapp.ai.LocalAiBackendMode
import com.example.newandroidapp.stability.BenchmarkSuiteManager
import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.CrashGuardManager
import com.example.newandroidapp.stability.EpisodicAppMemory
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.stability.KeepAliveHelper
import com.example.newandroidapp.stability.HeadsUpNotificationDefenseGuard
import com.example.newandroidapp.stability.HumanTouchArbitrator
import com.example.newandroidapp.stability.SmartDailyAssistantHelper
import com.example.newandroidapp.stability.NeedleIsolatedAiService
import com.example.newandroidapp.stability.NeedleIsolatedClient
import com.example.newandroidapp.stability.StabilityFlightRecorder
import com.example.newandroidapp.stability.SystemBarBlockerGuard
import com.example.newandroidapp.stability.VoiceCommandAndTtsManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全系統實時 AI 視覺感知、極致穩定閉環執行與 5 分鐘 30+ App 壓力測試服務
 *
 * 完整實作 11 項極致穩定機制：
 * 1. 動作後畫面指紋驗證與第 2/3 候選自動降級重試
 * 2. 卡關偵測與防盲目滑動（連續 2 次滑動無變化自動停止）
 * 3. 目標達成語義驗證（ClosedLoopVerifier）
 * 4. 畫面穩定等待器（Frame Stability Gate，防轉場動畫搶跑）
 * 5. Needle 3 獨立 `:ai` 隔離行程守護 + C++ 互斥鎖與異常防護
 * 6. Bitmap 安全環形記憶體池（自動回收舊截圖、防 OOM）
 * 7. AccessibilityNodeInfo.refresh() 活性驗證與失效重抓
 * 8. 前景常駐通知保活（KeepAliveHelper）
 * 9. 每一步黑盒子軌跡記錄（StabilityFlightRecorder）
 * 10. 全域 Java/JNI 崩潰攔截器（CrashGuardManager）
 * 11. 至少 5 分鐘（300 秒）＋ 30 種以上 App 的自動化穩定度基準壓力測試引擎
 */
class VisionAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VisionAccessibilityService? = null
            private set

        @Volatile
        var latestLiveReport: VisionPipelineReport? = null
            private set

        @Volatile
        var latestLiveBitmap: Bitmap? = null
            private set

        @Volatile
        var latestLiveDecision: AiActionDecision? = null
            private set

        @Volatile
        var latestForegroundPackage: String? = null
            private set

        @Volatile
        var latestForegroundAppLabel: String = "當前 App"
            private set

        @Volatile
        var liveFrameSeq: Int = 0
            private set

        @Volatile
        var selectedAiMode: LocalAiBackendMode = LocalAiBackendMode.DUAL_FUSION

        @Volatile
        var activeGoalForHud: String = ""

        @Volatile
        var isAutoLoopRunning: Boolean = false
            private set

        @Volatile
        var isBenchmarkRunning: Boolean = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private var windowManager: WindowManager? = null
    private var floatingRoot: View? = null
    private var floatingRootLp: WindowManager.LayoutParams? = null
    private var floatingOrbView: TextView? = null
    private var arOverlayView: LiveArOverlayView? = null
    private var hudExpandedPanel: LinearLayout? = null
    private var hudLiveAppBadgeTv: TextView? = null
    private var hudCommandInputEt: EditText? = null
    private var hudCurrentAppChipsRow: LinearLayout? = null
    private var hudOutputText: TextView? = null
    private var autoRunStatusBtn: Button? = null
    private var capsuleChatContainer: LinearLayout? = null
    private var capsuleObserver: com.example.newandroidapp.chat.ConversationObserver? = null
    private var capsuleChatScrollView: ScrollView? = null

    @Volatile
    var isHandoverPaused: Boolean = false

    @Volatile
    var handoverCategory: com.example.newandroidapp.stability.SafetyGateManager.HandoverCategory =
        com.example.newandroidapp.stability.SafetyGateManager.HandoverCategory.NONE

    private var handoverDetail: String = ""
    private var handoverPausedGoal: String = ""
    private var handoverPausedSubGoalIndex: Int = 0
    private var handoverPausedStep: Int = 0
    private var currentLoopReplanCount: Int = 0
    private var handoverAlertCard: LinearLayout? = null
    private var handoverTitleTv: TextView? = null
    private var handoverDetailTv: TextView? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderCapsuleBubbles(messages: List<com.example.newandroidapp.chat.ChatMessage>) {
        val container = capsuleChatContainer ?: return
        container.removeAllViews()
        val stroke1dp = dp(1).coerceAtLeast(1)
        for (msg in messages.takeLast(10)) {
            val bubble = when (msg.role) {
                com.example.newandroidapp.chat.ChatRole.USER -> {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(0, dp(4), 0, dp(4))
                        val tv = TextView(this@VisionAccessibilityService).apply {
                            text = msg.text
                            textSize = 13f
                            setTextColor(Color.WHITE)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#2563EB"))
                                cornerRadius = dp(12).toFloat()
                            }
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                        }
                        addView(tv)
                    }
                }
                com.example.newandroidapp.chat.ChatRole.AI -> {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.START
                        setPadding(0, dp(4), 0, dp(4))
                        val card = LinearLayout(this@VisionAccessibilityService).apply {
                            orientation = LinearLayout.VERTICAL
                            background = GradientDrawable().apply {
                                setColor(Color.WHITE)
                                cornerRadius = dp(12).toFloat()
                                setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                            }
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                        }
                        val hRow = LinearLayout(this@VisionAccessibilityService).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val dot = View(this@VisionAccessibilityService).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#10B981"))
                            }
                        }
                        hRow.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
                        val tagText = msg.metadataHeader ?: if (msg.isProactiveReport) "主動回報" else "AI 助手"
                        val tagTv = TextView(this@VisionAccessibilityService).apply {
                            text = tagText
                            textSize = 11f
                            setTextColor(Color.parseColor("#64748B"))
                            setPadding(dp(8), 0, 0, 0)
                        }
                        hRow.addView(tagTv)
                        card.addView(hRow)

                        val bodyTv = TextView(this@VisionAccessibilityService).apply {
                            text = msg.text
                            textSize = 13f
                            setTextColor(Color.parseColor("#0F172A"))
                            setPadding(0, dp(4), 0, 0)
                        }
                        card.addView(bodyTv)
                        val execCmd = msg.executableCommand?.trim().orEmpty()
                        if (execCmd.isNotEmpty()) {
                            val inlineExecBtn = makeCompactHudButton(
                                label = if (execCmd == "確認執行") "確認執行" else "執行此指令",
                                bgHex = "#2563EB",
                                textColor = Color.WHITE
                            ).apply {
                                textSize = 11f
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                                setOnClickListener {
                                    setHudFocusable(false)
                                    startAutonomousTaskLoop(execCmd, selectedAiMode, lockToCurrentApp = false)
                                }
                            }
                            card.addView(inlineExecBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(8)
                            })
                        }
                        addView(card)
                    }
                }
            }
            container.addView(bubble)
        }
        capsuleChatScrollView?.post {
            capsuleChatScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    @Volatile
    private var isHudAttached = false
    private var isArOverlayShown = false
    private val attachedOverlayViews = ArrayList<View>()
    private val isRealTimePassRunning = AtomicBoolean(false)
    private var lastRealTimePassElapsedMs = 0L
    private var lastScreenshotElapsedMs = 0L
    private var lastChipSignature = ""
    private var previousSampledReport: VisionPipelineReport? = null

    // 第 5 項：Needle 3 全部經由獨立 `:ai` 行程（NeedleIsolatedClient），原生層崩潰只會重啟 :ai，不影響本服務

    // 自動巡航閉環狀態機變數（含第 1~4 項防點錯與防卡關追蹤）
    private var currentLoopStep = 0
    private var currentSubGoalIndex = 0
    private var hasLaunchedTargetAppInLoop = false
    private var hasTypedTextInCurrentStep = false
    private var loopLockedToCurrentApp = false
    private var consecutiveScrollNoChangeCount = 0
    private var consecutiveStuckCount = 0
    private var stabilityWaitRetries = 0
    private val attemptedNodeKeysInStep = HashSet<String>()

    // 第 11 項：5 分鐘 + 30+ App 壓力測試狀態機變數
    private var benchmarkStartElapsedMs = 0L
    private var benchmarkTargetDurationSec = BenchmarkSuiteManager.DEFAULT_MIN_DURATION_SECONDS
    private var benchmarkTargetAppsCount = BenchmarkSuiteManager.DEFAULT_MIN_APPS_TARGET
    private var benchmarkCaseQueue = ArrayList<BenchmarkSuiteManager.BenchmarkTestCase>()
    private var benchmarkCursor = 0
    private val benchmarkDistinctAppsOpened = LinkedHashSet<String>()
    private val benchmarkFailedLaunchPkgs = HashSet<String>()
    private val benchmarkFailureNotes = ArrayList<String>()
    private var benchmarkTotalCasesDone = 0
    private var benchmarkPassedCases = 0
    private var benchmarkFailedCases = 0
    private var benchmarkLaunchFailures = 0
    private var benchmarkPeakRamMb = 0
    private var benchmarkErrorBaseline = 0
    private var benchmarkAiRestartBaseline = 0
    private var benchmarkAiTimeoutBaseline = 0
    private var benchmarkTotalVisionMs = 0L
    private var benchmarkTotalAiMs = 0L

    private val realTimeDebounceRunnable = Runnable {
        runRealTimeVisionStreamTick()
    }

    private val realTimeHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isHudAttached) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRealTimePassElapsedMs >= 450L && !isAutoLoopRunning && !isBenchmarkRunning) {
                runRealTimeVisionStreamTick()
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val benchmarkSecondTicker = object : Runnable {
        override fun run() {
            if (!isBenchmarkRunning) return
            val elapsedSec = ((SystemClock.elapsedRealtime() - benchmarkStartElapsedMs) / 1000L).toInt()
            val curProg = BenchmarkSuiteManager.latestProgress
            BenchmarkSuiteManager.latestProgress = curProg.copy(
                isRunning = true,
                elapsedSeconds = elapsedSec,
                usedRamMb = CrashGuardManager.checkMemoryPressureAndGc()
            )
            val p = BenchmarkSuiteManager.latestProgress
            benchmarkPeakRamMb = maxOf(benchmarkPeakRamMb, p.usedRamMb)
            hudOutputText?.text = "[壓力測試 ${p.formatElapsedMmSs()}] 已測 ${p.distinctAppsOpenedCount}/${p.targetAppsCount} 種App | 當前：${p.currentCaseTitle}"

            // 同時達成「至少 5 分鐘」與「至少 30 種不同 App」才結束；超時則誠實回報未達標
            if (elapsedSec >= benchmarkTargetDurationSec && benchmarkDistinctAppsOpened.size >= benchmarkTargetAppsCount) {
                finishBenchmarkRun("已達成測試條件")
                return
            }
            if (elapsedSec >= benchmarkTargetDurationSec * 2) {
                finishBenchmarkRun("已超過 ${benchmarkTargetDurationSec * 2} 秒上限")
                return
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    fun isFloatingHudShown(): Boolean = isHudAttached

    private fun getLiveScreenSizePx(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return SystemBarBlockerGuard.getRealScreenSizePx(
            this,
            dm.widthPixels.coerceAtLeast(720),
            dm.heightPixels.coerceAtLeast(720)
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            val wm = windowManager ?: return@post
            val (liveW, liveH) = getLiveScreenSizePx()
            val root = floatingRoot
            val lp = floatingRootLp
            if (isHudAttached && root != null && lp != null) {
                val curW = root.width.coerceAtLeast(116)
                val curH = root.height.coerceAtLeast(116)
                val maxX = (liveW - curW).coerceAtLeast(0)
                val maxY = (liveH - curH).coerceAtLeast(0)
                lp.x = lp.x.coerceIn(0, maxX)
                lp.y = lp.y.coerceIn(0, maxY)
                runCatching { wm.updateViewLayout(root, lp) }
            }
            syncSystemBarBlockerOverlay()
        }
    }

    fun syncSystemBarBlockerOverlay() {
        mainHandler.post {
            val (screenW, screenH) = getLiveScreenSizePx()
            SystemBarBlockerGuard.syncPhysicalShieldOverlays(
                context = this,
                wm = windowManager,
                screenWidth = screenW,
                screenHeight = screenH,
                shouldShow = SystemBarBlockerGuard.isBlockerEnabled
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = runCatching { getSystemService(WINDOW_SERVICE) as WindowManager }.getOrNull()
        CrashGuardManager.installGlobalCrashGuard(applicationContext)
        // 將磁碟與資產檔 I/O 移至背景執行緒，確保 onServiceConnected 瞬間完成，絕不觸發系統無障礙綁定逾時 (顯示「此服務無法正常運作」)
        visionExecutor.execute {
            runCatching { EpisodicAppMemory.ensureLoaded(applicationContext) }
            runCatching { Phase1TreeImmunityConfig.loadFromAssets(applicationContext) }
            runCatching { Phase2ButtonFunctionalityClassifier.loadFromAssets(applicationContext) }
            runCatching { Phase3ScreenPostureClassifier.loadFromAssets(applicationContext) }
            runCatching { VoiceCommandAndTtsManager.initTtsIfNeeded(applicationContext) }
            runCatching { NeedleIsolatedClient.init(applicationContext) }
            runCatching { NeedleIsolatedClient.bind() }
        }
        runCatching { KeepAliveHelper.startForegroundKeepAlive(this, "實時視覺與雙本地 AI 防殺保活中") }
        syncSystemBarBlockerOverlay()
        if (HudPersistentStateManager.shouldAutoAttachHud(this)) {
            mainHandler.postDelayed({
                if (!isHudAttached) {
                    toggleFloatingHud(true)
                }
            }, 150L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isHudAttached && HudPersistentStateManager.shouldAutoAttachHud(this)) {
            toggleFloatingHud(true)
        }
        val evType = event.eventType
        val pkg = event.packageName?.toString()
        // 【系統通知橫幅防禦】忽略 com.android.systemui 與通知事件 (TYPE_NOTIFICATION_STATE_CHANGED) 篡改前景 App Package
        val isForeignNotificationEvent = evType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
            pkg == "com.android.systemui" ||
            (isAutoLoopRunning && InAppWorkingMemory.expectedTargetPackage.isNotBlank() &&
                pkg != null && pkg != InAppWorkingMemory.expectedTargetPackage &&
                evType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        if (pkg != null && pkg != packageName && !isForeignNotificationEvent) {
            latestForegroundPackage = pkg
            latestForegroundAppLabel = InstalledAppLauncher.getAppLabelByPackage(this, pkg)
        }
        if (!isHudAttached && !isAutoLoopRunning && !isBenchmarkRunning) return

        // 【雙模態真人觸控意圖仲裁器】偵測到真人觸碰螢幕時，依包名敏感度與畫面語義判定「立即強制停機」或「誤觸評估與自動回跳」
        if ((evType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
                evType == AccessibilityEvent.TYPE_GESTURE_DETECTION_START) &&
            SmartDailyAssistantHelper.checkAndRecordHumanTakeover(isAutoLoopRunning)
        ) {
            handleHumanTouchDetectedOnScreen()
            return
        }

        if (evType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            evType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            evType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            evType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            mainHandler.removeCallbacks(realTimeDebounceRunnable)
            mainHandler.postDelayed(realTimeDebounceRunnable, 65L)
        }
    }

    override fun onInterrupt() {
        stopAutonomousLoop("服務中斷")
        stopBenchmarkSuite("服務中斷")
    }

    override fun onDestroy() {
        stopAutonomousLoop("服務關閉")
        stopBenchmarkSuite("服務關閉")
        NeedleIsolatedClient.unbind()
        removeFloatingHud()
        instance = null
        super.onDestroy()
    }

    /**
     * 實時背景視覺簡化流（5~15ms 完成當前 App 畫面的 Z-Order 遮擋剔除、熱區收斂與極限壓縮）
     */
    private fun runRealTimeVisionStreamTick() {
        if (!isHudAttached) return
        if (!isRealTimePassRunning.compareAndSet(false, true)) return

        val dm: DisplayMetrics = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(720)
        val sh = dm.heightPixels.coerceAtLeast(1280)

        val winList = runCatching { windows }.getOrDefault(emptyList())
        val rootFallback = runCatching { rootInActiveWindow }.getOrNull()
        val activePkg = rootFallback?.packageName?.toString()
        if (!activePkg.isNullOrBlank() && activePkg != packageName) {
            latestForegroundPackage = activePkg
            latestForegroundAppLabel = InstalledAppLauncher.getAppLabelByPackage(this, activePkg)
        }

        visionExecutor.execute {
            try {
                val rawNodes = ZOrderTreeCollector.collectFromAccessibilityWindows(
                    windows = winList,
                    rootFallback = rootFallback,
                    screenWidth = sw,
                    screenHeight = sh,
                    ignorePackageName = packageName
                )
                val report = AiVisionPipeline.execute(
                    rawNodes = rawNodes,
                    screenWidth = sw,
                    screenHeight = sh,
                    screenBitmap = latestLiveBitmap,
                    useNormalizedGrid = true
                )
                previousSampledReport = latestLiveReport
                latestLiveReport = report
                lastRealTimePassElapsedMs = SystemClock.elapsedRealtime()
                val frameNo = ++liveFrameSeq

                mainHandler.post {
                    if (!isHudAttached) return@post
                    updateLiveHudHeaderAndChips(report, frameNo)
                    if (isArOverlayShown) {
                        arOverlayView?.report = report
                        arOverlayView?.visibility = View.VISIBLE
                        arOverlayView?.invalidate()
                    }
                }
            } catch (t: Throwable) {
                CrashGuardManager.recordThrowable(applicationContext, "RealTimeVisionStreamTick", t)
            } finally {
                isRealTimePassRunning.set(false)
            }
        }
    }

    private fun updateLiveHudHeaderAndChips(report: VisionPipelineReport, frameNo: Int) {
        val appName = latestForegroundAppLabel
        hudLiveAppBadgeTv?.text = "實時 #$frameNo · $appName (${report.finalNodes.size} 元件 · ${report.pipelineLatencyMs}ms)"

        val chipsRow = hudCurrentAppChipsRow ?: return
        val topInteractive = report.finalNodes
            .filter { it.label.isNotBlank() && (it.shortTypeTag == "INP" || it.shortTypeTag == "BTN" || it.shortTypeTag == "ICO" || it.shortTypeTag == "CHK") }
            .take(6)
        val sig = "$appName|" + topInteractive.joinToString(",") { "${it.shortTypeTag}:${it.label}" }
        if (sig == lastChipSignature) return
        lastChipSignature = sig

        chipsRow.removeAllViews()

        fun addQuickActionChip(title: String, bgHex: String, onClick: () -> Unit) {
            val chip = makeCompactHudButton(title, bgHex, Color.WHITE).apply {
                textSize = 10.5f
                setPadding(16, 8, 16, 8)
                setOnClickListener { onClick() }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = 8
            })
        }

        // 【功能 4：畫面重點智慧朗讀】一鍵過濾雜訊並以繁體中文語音播報當前頁面重點
        addQuickActionChip("朗讀畫面", "#059669") {
            setHudFocusable(false)
            readCurrentScreenSummaryAloud()
        }

        for (node in topInteractive) {
            val shortLbl = if (node.label.length > 7) node.label.take(7) + "…" else node.label
            if (node.shortTypeTag == "INP") {
                addQuickActionChip("輸入:$shortLbl", "#0284C7") {
                    hudCommandInputEt?.let { et ->
                        val cur = et.text?.toString()?.trim().orEmpty()
                        if (cur.isEmpty()) {
                            et.setText("搜尋 ")
                            et.setSelection(et.text.length)
                            setHudFocusable(true)
                        } else {
                            val textToType = cur.replace(Regex("^(搜尋|輸入)\\s*"), "").ifEmpty { cur }
                            setHudFocusable(false)
                            performRealTextInputAt(node.finalTapPx.x.toFloat(), node.finalTapPx.y.toFloat(), textToType) {
                                hudOutputText?.text = "已在「$appName」輸入：$textToType"
                            }
                        }
                    }
                }
            } else {
                addQuickActionChip("點擊「$shortLbl」", "#1E293B") {
                    setHudFocusable(false)
                    performRealTapAt(node.finalTapPx.x.toFloat(), node.finalTapPx.y.toFloat(), node.label) {
                        hudOutputText?.text = "已在「$appName」實時點擊：「${node.label}」"
                    }
                }
            }
        }

        addQuickActionChip("往下滑", "#334155") {
            setHudFocusable(false)
            performRealScroll(down = true) {
                hudOutputText?.text = "已在「$appName」向下滑動畫面"
            }
        }
        addQuickActionChip("往上滑", "#334155") {
            setHudFocusable(false)
            performRealScroll(down = false) {
                hudOutputText?.text = "已在「$appName」向上滑動畫面"
            }
        }
        addQuickActionChip("返回", "#334155") {
            setHudFocusable(false)
            performGlobalAction(GLOBAL_ACTION_BACK)
            hudOutputText?.text = "已在「$appName」執行返回"
        }
    }

    private fun setHudFocusable(focusable: Boolean) {
        val wm = windowManager ?: return
        val root = floatingRoot ?: return
        val lp = floatingRootLp ?: return

        if (focusable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            runCatching { wm.updateViewLayout(root, lp) }
            hudCommandInputEt?.post {
                hudCommandInputEt?.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                hudCommandInputEt?.let { imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            }
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            hudCommandInputEt?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
            hudCommandInputEt?.clearFocus()
            lp.flags = lp.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    private class LiveArOverlayView(context: Context) : View(context) {
        var report: VisionPipelineReport? = null
        var aiDecision: AiActionDecision? = null

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#10B981")
        }
        private val roiBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#F59E0B")
        }
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#EF4444")
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rep = report ?: return

            for (s1 in rep.stage1AllNodes) {
                if (!s1.isCulled) continue
                val b = s1.raw.bounds
                canvas.drawRoundRect(RectF(b), 8f, 8f, ghostPaint)
            }

            for (node in rep.finalNodes) {
                val r = RectF(node.convergedBounds)
                val isRoi = node.sourceType == NodeSourceType.ROI_OCR_TEXT ||
                    node.sourceType == NodeSourceType.ROI_EDGE_BUTTON
                canvas.drawRoundRect(r, 8f, 8f, if (isRoi) roiBoxPaint else boxPaint)

                val tx = node.finalTapPx.x.toFloat()
                val ty = node.finalTapPx.y.toFloat()
                fillPaint.color = if (isRoi) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
                canvas.drawCircle(tx, ty, 8f, fillPaint)
                fillPaint.color = Color.WHITE
                canvas.drawCircle(tx, ty, 3f, fillPaint)

                if (aiDecision?.targetMarkId == node.shortMarkId) {
                    boxPaint.color = Color.parseColor("#0EA5E9")
                    boxPaint.strokeWidth = 4.5f
                    canvas.drawCircle(tx, ty, 24f, boxPaint)
                    canvas.drawCircle(tx, ty, 34f, boxPaint)
                    boxPaint.color = Color.parseColor("#10B981")
                    boxPaint.strokeWidth = 3f
                }

                val shortLbl = if (node.label.length > 8) node.label.take(8) + "…" else node.label
                val tag = "#${node.shortMarkId} $shortLbl @${node.gridTapPoint.x},${node.gridTapPoint.y}"
                val tw = textPaint.measureText(tag)
                val bx = r.left.coerceIn(4f, (width - tw - 20f).coerceAtLeast(4f))
                val by = (r.top - 32f).coerceAtLeast(4f)
                fillPaint.color = Color.argb(225, 15, 23, 42)
                canvas.drawRoundRect(RectF(bx, by, bx + tw + 16f, by + 30f), 8f, 8f, fillPaint)
                canvas.drawText(tag, bx + 8f, by + 22f, textPaint)
            }
        }
    }

    // =========================================================================
    // 【第 11 項】至少 5 分鐘 ＋ 至少 30 種「不同」App 穩定度壓力測試引擎
    //  - 只統計實機真實量測結果：不同 App 以「啟動後前景實際為該 App 的 packageName」計算，啟動失敗不算
    //  - 通過標準：前景已切到真實 App ＋ 取得有效畫面元件 ＋ 雙 AI 產出決策 ＋ 系統手勢實際完成
    // =========================================================================
    fun startFiveMinuteBenchmarkSuite(
        minDurationSeconds: Int = BenchmarkSuiteManager.DEFAULT_MIN_DURATION_SECONDS,
        minAppsTarget: Int = BenchmarkSuiteManager.DEFAULT_MIN_APPS_TARGET
    ) {
        if (isBenchmarkRunning) return
        if (isAutoLoopRunning) {
            stopAutonomousLoop("切換至 5 分鐘穩定度壓力測試")
        }
        val allCases = BenchmarkSuiteManager.ensureLoaded(applicationContext).filter { it.enabled }
        if (allCases.isEmpty()) {
            Toast.makeText(this, "測試集為空，請先重設或新增測試項目", Toast.LENGTH_SHORT).show()
            return
        }

        benchmarkCaseQueue = ArrayList(allCases)
        benchmarkCursor = 0
        benchmarkDistinctAppsOpened.clear()
        benchmarkFailedLaunchPkgs.clear()
        benchmarkFailureNotes.clear()
        benchmarkTotalCasesDone = 0
        benchmarkPassedCases = 0
        benchmarkFailedCases = 0
        benchmarkLaunchFailures = 0
        benchmarkPeakRamMb = 0
        benchmarkTotalVisionMs = 0L
        benchmarkTotalAiMs = 0L
        benchmarkTargetDurationSec = minDurationSeconds.coerceAtLeast(60)
        benchmarkTargetAppsCount = minAppsTarget.coerceAtLeast(1)
        benchmarkErrorBaseline = CrashGuardManager.recordedErrorCount()
        benchmarkAiRestartBaseline = NeedleIsolatedClient.restartCount.get()
        benchmarkAiTimeoutBaseline = NeedleIsolatedClient.timeoutCount.get()
        benchmarkStartElapsedMs = SystemClock.elapsedRealtime()

        BenchmarkSuiteManager.markRunStart(applicationContext)
        isBenchmarkRunning = true

        BenchmarkSuiteManager.latestProgress = BenchmarkSuiteManager.BenchmarkLiveProgress(
            isRunning = true,
            elapsedSeconds = 0,
            targetDurationSeconds = benchmarkTargetDurationSec,
            distinctAppsOpenedCount = 0,
            targetAppsCount = benchmarkTargetAppsCount,
            totalCasesCompleted = 0,
            passedCasesCount = 0,
            currentCaseIndex = 1,
            totalSuiteSize = benchmarkCaseQueue.size,
            currentCaseTitle = benchmarkCaseQueue.first().title,
            usedRamMb = CrashGuardManager.checkMemoryPressureAndGc()
        )

        KeepAliveHelper.startForegroundKeepAlive(this, "正在執行 5 分鐘 (30+ App) 穩定度壓力測試...")
        toggleFloatingHud(true)

        mainHandler.removeCallbacks(benchmarkSecondTicker)
        mainHandler.postDelayed(benchmarkSecondTicker, 1000L)
        mainHandler.postDelayed({
            updateAutoRunButtonUi()
            executeNextBenchmarkCase()
        }, 300L)
    }

    fun stopBenchmarkSuite(reason: String = "已手動停止壓力測試") {
        if (!isBenchmarkRunning) return
        finishBenchmarkRun("[已停止] $reason")
    }

    private fun finishBenchmarkRun(summaryBanner: String) {
        if (!isBenchmarkRunning) return
        isBenchmarkRunning = false
        mainHandler.removeCallbacks(benchmarkSecondTicker)

        val elapsedSec = ((SystemClock.elapsedRealtime() - benchmarkStartElapsedMs) / 1000L).toInt().coerceAtLeast(1)
        val total = benchmarkTotalCasesDone
        val avgVis = if (total > 0) benchmarkTotalVisionMs / total else 0L
        val avgAi = if (total > 0) benchmarkTotalAiMs / total else 0L
        val passRate = if (total > 0) (benchmarkPassedCases * 100) / total else 0
        val ramMb = CrashGuardManager.checkMemoryPressureAndGc()
        benchmarkPeakRamMb = maxOf(benchmarkPeakRamMb, ramMb)

        val errorCount = (CrashGuardManager.recordedErrorCount() - benchmarkErrorBaseline).coerceAtLeast(0)
        val aiRestarts = (NeedleIsolatedClient.restartCount.get() - benchmarkAiRestartBaseline).coerceAtLeast(0)
        val aiTimeouts = (NeedleIsolatedClient.timeoutCount.get() - benchmarkAiTimeoutBaseline).coerceAtLeast(0)

        val durationOk = elapsedSec >= benchmarkTargetDurationSec
        val appsOk = benchmarkDistinctAppsOpened.size >= benchmarkTargetAppsCount
        val headline = if (durationOk && appsOk) {
            "已達成測試條件（>= ${benchmarkTargetDurationSec / 60} 分鐘 且 >= $benchmarkTargetAppsCount 種不同 App）"
        } else {
            "$summaryBanner\n未達標：時長${if (durationOk) "已達" else "不足"}、不同 App ${benchmarkDistinctAppsOpened.size}/$benchmarkTargetAppsCount 種${if (appsOk) "已達" else "不足"}"
        }

        val reportText = buildString {
            appendLine(headline)
            appendLine("• 實測時長：${elapsedSec / 60} 分 ${elapsedSec % 60} 秒（目標 >= ${benchmarkTargetDurationSec / 60} 分鐘）")
            appendLine("• 實際切到前景的不同 App：${benchmarkDistinctAppsOpened.size} 種（目標 >= $benchmarkTargetAppsCount 種）")
            appendLine("• 回合：通過 $benchmarkPassedCases / 共 $total（通過率 $passRate%）｜啟動失敗 $benchmarkLaunchFailures 次｜其他失敗 ${benchmarkFailedCases - benchmarkLaunchFailures} 次")
            appendLine("• 平均視覺耗時 ${avgVis} ms｜平均 AI 決策耗時 ${avgAi} ms｜峰值記憶體 ${benchmarkPeakRamMb} MB")
            appendLine("• 期間記錄例外 $errorCount 次｜Needle 3 隔離行程重啟 $aiRestarts 次｜AI 呼叫逾時 $aiTimeouts 次")
            append("• 前景保活服務：${KeepAliveHelper.statusLine()}")
            if (benchmarkFailureNotes.isNotEmpty()) {
                append("\n• 最近失敗原因：\n  - ")
                append(benchmarkFailureNotes.takeLast(6).joinToString("\n  - "))
            }
        }

        BenchmarkSuiteManager.markRunFinish(applicationContext)
        BenchmarkSuiteManager.latestProgress = BenchmarkSuiteManager.latestProgress.copy(
            isRunning = false,
            elapsedSeconds = elapsedSec,
            distinctAppsOpenedCount = benchmarkDistinctAppsOpened.size,
            totalCasesCompleted = total,
            passedCasesCount = benchmarkPassedCases,
            avgVisionLatencyMs = avgVis,
            avgAiLatencyMs = avgAi,
            usedRamMb = ramMb,
            finalSummaryReport = reportText
        )

        KeepAliveHelper.startForegroundKeepAlive(this, "壓力測試結束：通過率 $passRate%（${benchmarkDistinctAppsOpened.size} 種 App）")
        mainHandler.post {
            updateAutoRunButtonUi()
            hudOutputText?.text = "測試結束：${benchmarkDistinctAppsOpened.size} 種 App｜通過率 $passRate%"
        }
    }

    /** 前景是否為「不算數」的畫面（桌面、系統 UI、本工具自己） */
    private fun isNonTargetForeground(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName || pkg == "com.android.systemui") return true
        val homes = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        return pkg in homes
    }

    private fun failBenchmarkCase(
        testCase: BenchmarkSuiteManager.BenchmarkTestCase,
        appLabel: String,
        reason: String,
        isLaunchFailure: Boolean,
        nodeCount: Int = 0,
        visionMs: Long = 0L,
        aiOpcode: String = "-",
        aiConfidence: Float = 0f,
        aiMs: Long = 0L
    ) {
        benchmarkTotalCasesDone++
        benchmarkFailedCases++
        if (isLaunchFailure) benchmarkLaunchFailures++
        benchmarkTotalVisionMs += visionMs
        benchmarkTotalAiMs += aiMs
        benchmarkFailureNotes.add("[${testCase.title}] $reason")
        while (benchmarkFailureNotes.size > 40) benchmarkFailureNotes.removeAt(0)

        StabilityFlightRecorder.recordStep(
            context = applicationContext,
            stepIndex = benchmarkTotalCasesDone,
            appLabel = appLabel,
            packageName = latestForegroundPackage.orEmpty().ifBlank { "unknown" },
            screenFingerprint = "bench-fail",
            visibleNodesCount = nodeCount,
            visionLatencyMs = visionMs,
            aiOpcode = aiOpcode,
            aiConfidence = aiConfidence,
            aiLatencyMs = aiMs,
            postActionVerified = false,
            retryOrFallbackInfo = "壓測失敗",
            outcomeNote = "${testCase.title}：$reason"
        )
        publishBenchmarkProgress(testCase.title, appLabel)
    }

    private fun publishBenchmarkProgress(caseTitle: String, appLabel: String) {
        val total = benchmarkTotalCasesDone.coerceAtLeast(1)
        val ram = CrashGuardManager.checkMemoryPressureAndGc()
        benchmarkPeakRamMb = maxOf(benchmarkPeakRamMb, ram)
        BenchmarkSuiteManager.latestProgress = BenchmarkSuiteManager.latestProgress.copy(
            isRunning = true,
            distinctAppsOpenedCount = benchmarkDistinctAppsOpened.size,
            totalCasesCompleted = benchmarkTotalCasesDone,
            passedCasesCount = benchmarkPassedCases,
            currentCaseTitle = caseTitle,
            currentAppLabel = appLabel,
            avgVisionLatencyMs = benchmarkTotalVisionMs / total,
            avgAiLatencyMs = benchmarkTotalAiMs / total,
            usedRamMb = ram
        )
    }

    /** 壓測專用滑動：回報系統手勢是否「真的完成」（而不是只回報已送出） */
    private fun performBenchmarkScroll(onResult: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(720)
        val sh = dm.heightPixels.coerceAtLeast(1280)
        val cx = sw * 0.5f
        val startPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, sh * 0.72f, sw, sh).first
        val endPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, sh * 0.25f, sw, sh).first
        val startY = startPt.y.toFloat()
        val endY = endPt.y.toFloat()

        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
        }
        mainHandler.postDelayed({
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            mainHandler.post {
                                if (isHudAttached) floatingRoot?.visibility = View.VISIBLE
                                onResult(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            mainHandler.post {
                                if (isHudAttached) floatingRoot?.visibility = View.VISIBLE
                                onResult(false)
                            }
                        }
                    },
                    null
                )
            }.getOrDefault(false)
            if (!dispatched) {
                if (isHudAttached) floatingRoot?.visibility = View.VISIBLE
                onResult(false)
            }
        }, 100L)
    }

    private fun executeNextBenchmarkCase() {
        if (!isBenchmarkRunning) return
        val elapsedSec = ((SystemClock.elapsedRealtime() - benchmarkStartElapsedMs) / 1000L).toInt()
        if (elapsedSec >= benchmarkTargetDurationSec && benchmarkDistinctAppsOpened.size >= benchmarkTargetAppsCount) {
            finishBenchmarkRun("已達成測試條件")
            return
        }
        // 安全上限：超過目標時長 2 倍仍湊不到足夠不同 App，就誠實回報未達標
        if (elapsedSec >= benchmarkTargetDurationSec * 2) {
            finishBenchmarkRun("已超過 ${benchmarkTargetDurationSec * 2} 秒上限")
            return
        }
        if (benchmarkCaseQueue.isEmpty()) {
            finishBenchmarkRun("無可用測試項目")
            return
        }

        val idx = benchmarkCursor % benchmarkCaseQueue.size
        val testCase = benchmarkCaseQueue[idx]
        benchmarkCursor++

        // 1. 啟動目標 App / 系統頁面（失敗不灌水，會如實記為啟動失敗）
        var launchOk = false
        var launchedLabel = testCase.targetAppHint
        var expectedPkg: String? = null

        val matchedApp = InstalledAppLauncher.resolveTargetAppForGoal(
            context = applicationContext,
            rawUserGoal = testCase.commandGoal,
            needleAppHint = testCase.targetAppHint
        )
        if (matchedApp != null) {
            launchOk = InstalledAppLauncher.launchApp(applicationContext, matchedApp.first.packageName)
            if (launchOk) {
                expectedPkg = matchedApp.first.packageName
                launchedLabel = matchedApp.first.label
            } else {
                benchmarkFailedLaunchPkgs.add(matchedApp.first.packageName)
            }
        }

        if (!launchOk && !testCase.fallbackSystemIntentAction.isNullOrBlank()) {
            launchOk = runCatching {
                val intent = Intent(testCase.fallbackSystemIntentAction).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (launchOk) launchedLabel = testCase.title
        }

        // 此測試項對應的 App 沒裝：改挑一個「尚未成功測過」的真實已安裝 App 補位
        if (!launchOk) {
            val pick = InstalledAppLauncher.getInstalledLaunchableApps(applicationContext).firstOrNull {
                it.packageName !in benchmarkDistinctAppsOpened && it.packageName !in benchmarkFailedLaunchPkgs
            }
            if (pick != null) {
                launchOk = InstalledAppLauncher.launchApp(applicationContext, pick.packageName)
                if (launchOk) {
                    expectedPkg = pick.packageName
                    launchedLabel = pick.label
                } else {
                    benchmarkFailedLaunchPkgs.add(pick.packageName)
                }
            }
        }

        if (!launchOk) {
            failBenchmarkCase(testCase, launchedLabel, "無法啟動（手機未安裝或系統拒絕）", isLaunchFailure = true)
            mainHandler.postDelayed({ executeNextBenchmarkCase() }, 800L)
            return
        }

        BenchmarkSuiteManager.latestProgress = BenchmarkSuiteManager.latestProgress.copy(
            isRunning = true,
            elapsedSeconds = elapsedSec,
            currentCaseIndex = idx + 1,
            totalSuiteSize = benchmarkCaseQueue.size,
            currentCaseTitle = testCase.title,
            currentAppLabel = launchedLabel
        )

        // 2. 等 App 首屏展開，再抓實時畫面並驗證「前景真的是 App」
        mainHandler.postDelayed({
            if (!isBenchmarkRunning) return@postDelayed
            captureLiveScreenAndRunPipeline benchCapture@{ rep ->
                if (!isBenchmarkRunning) return@benchCapture

                val fgPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                    ?: latestForegroundPackage.orEmpty()

                if (isNonTargetForeground(fgPkg)) {
                    failBenchmarkCase(
                        testCase, launchedLabel,
                        "啟動後前景不是目標 App（目前為 ${fgPkg.ifBlank { "未知" }}）",
                        isLaunchFailure = true,
                        nodeCount = rep.finalNodes.size,
                        visionMs = rep.pipelineLatencyMs
                    )
                    mainHandler.postDelayed({ executeNextBenchmarkCase() }, 600L)
                    return@benchCapture
                }

                // 真實前景 App：以 packageName 計入「不同 App」
                benchmarkDistinctAppsOpened.add(fgPkg)
                val redirectNote = if (expectedPkg != null && expectedPkg != fgPkg) "（預期 $expectedPkg，實際重導向到 $fgPkg）" else ""

                val beforeFp = ClosedLoopVerifier.computeScreenFingerprint(fgPkg, rep)
                val nodesOk = rep.finalNodes.isNotEmpty()

                val dec = DualLocalAiCoordinator.decide(
                    mode = selectedAiMode,
                    userGoal = testCase.commandGoal,
                    report = rep,
                    context = this@VisionAccessibilityService,
                    currentForegroundPackage = fgPkg,
                    alreadyLaunchedApp = true,
                    activeSubGoalIndex = 0,
                    lockToCurrentApp = true
                )
                latestLiveDecision = dec
                val aiOk = dec.compactOpcode.isNotBlank()

                // 3. 在該 App 內送出一次真實系統手勢，並確認手勢「確實完成」
                performBenchmarkScroll { gestureOk ->
                    if (!isBenchmarkRunning) return@performBenchmarkScroll

                    val problems = ArrayList<String>()
                    if (!nodesOk) problems.add("取不到任何可見元件")
                    if (!aiOk) problems.add("AI 未產出決策")
                    if (!gestureOk) problems.add("系統手勢未完成/被取消")
                    val casePassed = problems.isEmpty()

                    benchmarkTotalCasesDone++
                    benchmarkTotalVisionMs += rep.pipelineLatencyMs
                    benchmarkTotalAiMs += dec.latencyMs
                    if (casePassed) {
                        benchmarkPassedCases++
                    } else {
                        benchmarkFailedCases++
                        benchmarkFailureNotes.add("[${testCase.title}] ${problems.joinToString("、")}")
                        while (benchmarkFailureNotes.size > 40) benchmarkFailureNotes.removeAt(0)
                    }

                    StabilityFlightRecorder.recordStep(
                        context = applicationContext,
                        stepIndex = benchmarkTotalCasesDone,
                        appLabel = launchedLabel,
                        packageName = fgPkg,
                        screenFingerprint = beforeFp.shortCode(),
                        visibleNodesCount = rep.finalNodes.size,
                        visionLatencyMs = rep.pipelineLatencyMs,
                        aiOpcode = dec.compactOpcode,
                        aiConfidence = dec.confidence,
                        aiLatencyMs = dec.latencyMs,
                        postActionVerified = casePassed,
                        retryOrFallbackInfo = "壓測 #${benchmarkDistinctAppsOpened.size} 種App$redirectNote",
                        outcomeNote = if (casePassed) testCase.title else "${testCase.title}：${problems.joinToString("、")}"
                    )
                    publishBenchmarkProgress(testCase.title, launchedLabel)

                    mainHandler.postDelayed({ executeNextBenchmarkCase() }, 900L)
                }
            }
        }, 1500L)
    }

    // =========================================================================
    // 【第 1 ~ 4 項】極致穩定閉環任務執行器（畫面穩定等待 + 決策 + 執行 + 動作後驗證 + 降級重試 + 防卡關）
    // =========================================================================
    fun startAutonomousTaskLoop(
        userGoal: String,
        mode: LocalAiBackendMode,
        lockToCurrentApp: Boolean = false
    ) {
        val rawCleanGoal = userGoal.trim()
        if (rawCleanGoal.isEmpty()) {
            Toast.makeText(this, "請先輸入要讓 AI 執行的指令", Toast.LENGTH_SHORT).show()
            return
        }
        // 【功能 4：畫面重點智慧朗讀】若指令為「讀畫面 / 唸這頁 / 朗讀」，直接擷取當前畫面重點並語音朗讀
        if (SmartDailyAssistantHelper.isScreenReadingCommand(rawCleanGoal)) {
            setHudFocusable(false)
            toggleFloatingHud(true)
            readCurrentScreenSummaryAloud()
            return
        }
        // 【Android 原生系統指令直通車】：調音量、設鬧鐘/計時器、開關手電筒、全域動作、設定子頁直達
        val nativeSysRes = com.example.newandroidapp.action.AndroidNativeSystemActionExecutor.tryExecuteIfMatched(
            rawGoal = rawCleanGoal,
            context = applicationContext,
            accessibilityService = this
        )
        if (nativeSysRes.handled) {
            setHudFocusable(false)
            hudOutputText?.text = nativeSysRes.summaryText
            com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                text = nativeSysRes.summaryText,
                metadataHeader = if (nativeSysRes.succeeded) "原生系統指令 · 已完成" else "原生系統指令 · 提示"
            )
            VoiceCommandAndTtsManager.speak(this, nativeSysRes.summaryText, flush = true)
            Toast.makeText(this, nativeSysRes.summaryText, Toast.LENGTH_SHORT).show()
            return
        }
        if (isHandoverPaused) {
            val lower = rawCleanGoal.lowercase()
            val isResume = rawCleanGoal == "確認繼續" || rawCleanGoal == "確定繼續" || rawCleanGoal == "繼續執行" ||
                rawCleanGoal == "我已完成" || rawCleanGoal == "確認完成" || rawCleanGoal == "繼續" ||
                lower == "resume" || lower == "continue"
            val isCancel = rawCleanGoal == "取消" || rawCleanGoal == "取消任務" || lower == "cancel" || lower == "abort"
            if (isResume) {
                resumeFromHumanHandover()
                return
            } else if (isCancel) {
                cancelFromHumanHandover()
                return
            }
        }
        val cleanGoal = com.example.newandroidapp.ai.LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal(
            rawGoal = rawCleanGoal,
            context = applicationContext,
            currentReport = latestLiveReport
        )
        isHandoverPaused = false
        if (isBenchmarkRunning) {
            stopBenchmarkSuite("切換至使用者任務")
        }
        setHudFocusable(false)
        activeGoalForHud = cleanGoal
        selectedAiMode = mode
        isAutoLoopRunning = true
        currentLoopStep = 0
        currentSubGoalIndex = 0
        val chunkPreview = com.example.newandroidapp.ai.HierarchicalCommandChunker.chunkInstruction(
            rawGoal = cleanGoal,
            context = applicationContext,
            lockToCurrentApp = false
        )
        val explicitSwitchApp = cleanGoal.startsWith("打開") ||
            cleanGoal.startsWith("開啟") ||
            cleanGoal.startsWith("切換到") ||
            cleanGoal.startsWith("去") ||
            cleanGoal.startsWith("到") ||
            cleanGoal.startsWith("進入") ||
            chunkPreview.totalAppBlocks >= 2 ||
            chunkPreview.macroBlocks.firstOrNull()?.targetAppHint != null
        loopLockedToCurrentApp = lockToCurrentApp && !explicitSwitchApp
        hasLaunchedTargetAppInLoop = loopLockedToCurrentApp
        hasTypedTextInCurrentStep = false
        consecutiveScrollNoChangeCount = 0
        consecutiveStuckCount = 0
        stabilityWaitRetries = 0
        lastSmartVisualFallbackFp = ""
        isInspectingAccidentalTouch = false
        HumanTouchArbitrator.resetState()
        HeadsUpNotificationDefenseGuard.resetState()
        CrossAppWorkflowEngine.resetClipboard()
        attemptedNodeKeysInStep.clear()
        currentLoopReplanCount = 0
        DualLocalAiCoordinator.resetPlanScriptCache()
        InAppWorkingMemory.resetForNewGoal(cleanGoal)
        InAppWorkingMemory.grantMotorPermission(cleanGoal)
        if (loopLockedToCurrentApp && !latestForegroundPackage.isNullOrBlank()) {
            InAppWorkingMemory.setExpectedTargetPackage(latestForegroundPackage!!)
        }

        VoiceCommandAndTtsManager.speak(this, "開始執行：$cleanGoal", flush = true)
        KeepAliveHelper.startForegroundKeepAlive(this, "正在執行任務：$cleanGoal")
        toggleFloatingHud(true)
        val wasExpandedHudOpen = hudExpandedPanel?.visibility == View.VISIBLE
        if (wasExpandedHudOpen) {
            hudExpandedPanel?.visibility = View.GONE
            floatingOrbView?.visibility = View.VISIBLE
        }
        val initialDelayMs = if (wasExpandedHudOpen) 160L else 35L
        mainHandler.postDelayed({
            updateAutoRunButtonUi()
            hudCommandInputEt?.setText(cleanGoal)
            val scopeTag = if (loopLockedToCurrentApp) "當前 App ($latestForegroundAppLabel)" else "全機自動巡航"
            hudOutputText?.text = "[$scopeTag] 實時閉環執行：「$cleanGoal」"
            executeNextAutonomousStep()
        }, initialDelayMs)
    }

    /**
     * 【極速化升級 1：就緒即觸發（Smart Poll）】
     * 啟動目標 App 後不再固定傻等 1800ms，每 90ms 檢測一次前景視窗是否已切換至目標 App 並載入元件樹，一旦就緒立即進入下一步！
     */
    private fun waitForAppWindowReadyAndStep(targetPkg: String, attempt: Int = 0) {
        if (!isAutoLoopRunning) return
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val curPkg = root?.packageName?.toString().orEmpty()
        val hasContent = (root?.childCount ?: 0) > 0
        if ((curPkg == targetPkg && hasContent && attempt >= 4) || attempt >= 14) {
            executeNextAutonomousStep()
        } else {
            mainHandler.postDelayed({
                waitForAppWindowReadyAndStep(targetPkg, attempt + 1)
            }, 90L)
        }
    }

    @Volatile
    private var isInspectingAccidentalTouch = false

    /**
     * 【雙模態真人觸控意圖仲裁器與誤觸自動回跳引擎】
     * 1. 若處於股票交易、購物電商、金融銀行等高敏感包名，或畫面出現「下單、付款、結帳、轉帳」等高敏感語義，或 2.5 秒內連續觸碰兩次 -> 立即強制停機讓位。
     * 2. 若處於聽歌、影音等一般場景 -> 判定為潛在誤觸，等待 350ms 觀察觸碰後畫面：
     *    - 若畫面未跳轉（無影響） -> 不按返回、直接繼續執行。
     *    - 若誤觸跳到廣告或同 App 其他介面 -> 自動執行返回鍵跳回誤觸前原介面。
     *    - 若誤觸跳出到外部 App 或瀏覽器 -> 自動返回並拉回原 App 介面。
     */
    private fun handleHumanTouchDetectedOnScreen() {
        if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return
        val curPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: latestForegroundPackage
        val arb = HumanTouchArbitrator.arbitrateOnTouchStart(
            currentPackage = curPkg,
            currentAppLabel = latestForegroundAppLabel,
            currentReport = latestLiveReport,
            userGoal = activeGoalForHud
        )

        if (arb.verdict == HumanTouchArbitrator.TouchInitialVerdict.IMMEDIATE_HARD_STOP) {
            isInspectingAccidentalTouch = false
            stopAutonomousLoop(arb.reasonMessage)
            return
        }

        if (isInspectingAccidentalTouch) return
        isInspectingAccidentalTouch = true
        mainHandler.post {
            hudOutputText?.text = arb.reasonMessage
        }

        mainHandler.postDelayed({
            if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) {
                isInspectingAccidentalTouch = false
                return@postDelayed
            }
            captureLiveScreenAndRunPipeline { postRep ->
                isInspectingAccidentalTouch = false
                if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@captureLiveScreenAndRunPipeline

                val afterPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: latestForegroundPackage
                val eval = HumanTouchArbitrator.evaluatePostTouchImpact(
                    preTouchSnapshot = arb.preTouchSnapshot,
                    postTouchPackage = afterPkg,
                    postTouchAppLabel = latestForegroundAppLabel,
                    postTouchReport = postRep,
                    userGoal = activeGoalForHud
                )

                when (eval.action) {
                    HumanTouchArbitrator.PostTouchRecoveryAction.POST_TOUCH_ENTERED_HIGH_RISK_STOP -> {
                        stopAutonomousLoop(eval.reasonMessage)
                    }
                    HumanTouchArbitrator.PostTouchRecoveryAction.NO_IMPACT_CONTINUE -> {
                        mainHandler.post {
                            hudOutputText?.text = eval.reasonMessage
                        }
                    }
                    HumanTouchArbitrator.PostTouchRecoveryAction.JUMPED_WITHIN_APP_BACK -> {
                        if (!InAppWorkingMemory.hasMotorPermission()) return@captureLiveScreenAndRunPipeline
                        mainHandler.post {
                            hudOutputText?.text = eval.reasonMessage
                        }
                        SmartDailyAssistantHelper.markAiActionWindow(750L)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    HumanTouchArbitrator.PostTouchRecoveryAction.JUMPED_EXTERNAL_APP_RECOVER -> {
                        if (!InAppWorkingMemory.hasMotorPermission()) return@captureLiveScreenAndRunPipeline
                        mainHandler.post {
                            hudOutputText?.text = eval.reasonMessage
                        }
                        SmartDailyAssistantHelper.markAiActionWindow(950L)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        val restorePkg = eval.targetPackageToRestore
                        if (restorePkg.isNotBlank()) {
                            mainHandler.postDelayed(restorePkgTask@{
                                if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@restorePkgTask
                                val checkPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                                    ?: latestForegroundPackage
                                if (checkPkg != restorePkg) {
                                    InstalledAppLauncher.launchApp(applicationContext, restorePkg)
                                }
                            }, 450L)
                        }
                    }
                }
            }
        }, 350L)
    }

    fun stopAutonomousLoop(reason: String = "已手動停止") {
        InAppWorkingMemory.revokeMotorPermissionImmediately(reason)
        isAutoLoopRunning = false
        isHandoverPaused = false
        isInspectingAccidentalTouch = false
        if (VoiceCommandAndTtsManager.isListening) {
            VoiceCommandAndTtsManager.stopListening()
        }
        if (reason.isNotEmpty() && reason != "切換至使用者任務") {
            VoiceCommandAndTtsManager.speak(this, reason, flush = true)
        }
        mainHandler.post {
            hideHandoverAlertOverlay()
            updateAutoRunButtonUi()
            if (hudOutputText != null && reason.isNotEmpty()) {
                hudOutputText?.text = reason
            }
        }
    }

    /**
     * 【R7 人工安全接管】安全暫停執行迴圈，顯示懸浮提示卡，保留任務意圖、子目標與上下文
     */
    fun pauseForHumanHandover(
        category: com.example.newandroidapp.stability.SafetyGateManager.HandoverCategory,
        detail: String
    ) {
        if (isHandoverPaused) return
        InAppWorkingMemory.revokeMotorPermissionImmediately("HUMAN_HANDOVER_PAUSED")
        isHandoverPaused = true
        isAutoLoopRunning = false
        handoverCategory = category
        handoverDetail = detail
        handoverPausedGoal = activeGoalForHud
        handoverPausedSubGoalIndex = currentSubGoalIndex
        handoverPausedStep = currentLoopStep

        val prompt = "偵測到${category.displayLabel}，AI 已暫停讓位，請您手動完成後點擊繼續"
        VoiceCommandAndTtsManager.speak(this, prompt)

        com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
            text = "[安全暫停] 偵測到${category.displayLabel}。請您手動完成輸入/驗證。完成後請點擊「我已完成，繼續執行」或回覆「確認繼續」。",
            metadataHeader = "人工安全接管 (R7)"
        )

        mainHandler.post {
            showHandoverAlertOverlay(category, detail)
        }
    }

    /**
     * 【R7 人工安全接管】使用者完成手動操作後，恢復自主迴圈執行
     */
    fun resumeFromHumanHandover() {
        if (!isHandoverPaused) return
        isHandoverPaused = false
        mainHandler.post {
            hideHandoverAlertOverlay()
        }

        VoiceCommandAndTtsManager.speak(this, "收到確認，AI 繼續執行任務")
        com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
            text = "使用者已確認接管完成，AI 自當前畫面繼續執行「$handoverPausedGoal」。",
            metadataHeader = "人工接管恢復"
        )

        activeGoalForHud = handoverPausedGoal
        currentSubGoalIndex = handoverPausedSubGoalIndex
        currentLoopStep = handoverPausedStep
        InAppWorkingMemory.grantMotorPermission(handoverPausedGoal)
        isAutoLoopRunning = true

        mainHandler.postDelayed({
            if (isAutoLoopRunning) {
                executeNextAutonomousStep()
            }
        }, 350L)
    }

    /**
     * 【R7 人工安全接管】使用者取消任務
     */
    fun cancelFromHumanHandover() {
        if (!isHandoverPaused) return
        isHandoverPaused = false
        mainHandler.post {
            hideHandoverAlertOverlay()
        }
        stopAutonomousLoop("使用者已手動取消任務")
    }

    private fun showHandoverAlertOverlay(
        category: com.example.newandroidapp.stability.SafetyGateManager.HandoverCategory,
        detail: String
    ) {
        if (floatingRoot == null) {
            toggleFloatingHud(true)
        }
        handoverTitleTv?.text = "[安全暫停] 偵測到${category.displayLabel}"
        handoverDetailTv?.text = if (detail.isNotBlank()) {
            "$detail\n完成後點擊「我已完成，繼續執行」恢復任務。"
        } else {
            "請您手動完成輸入或驗證操作。\n完成後點擊「我已完成，繼續執行」恢復任務。"
        }
        floatingOrbView?.visibility = View.GONE
        hudExpandedPanel?.visibility = View.GONE
        handoverAlertCard?.visibility = View.VISIBLE
    }

    private fun hideHandoverAlertOverlay() {
        handoverAlertCard?.visibility = View.GONE
        floatingOrbView?.visibility = View.VISIBLE
    }

    /**
     * 【R3 跨頁面經驗記憶圖譜】從導航麵包屑堆疊建構跨頁面跳轉步進並記入 EpisodicAppMemory
     */
    fun recordGraphMemoryFromNavigationStack(packageName: String, goalIntent: String, finalScreenFp: String = "") {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || goalIntent.isBlank()) return
        val breadcrumbs = InAppWorkingMemory.navigationStack.toList()
        if (breadcrumbs.isEmpty()) return

        val steps = ArrayList<EpisodicAppMemory.GraphTransitionStep>()
        for (i in breadcrumbs.indices) {
            val curr = breadcrumbs[i]
            val nextFp = if (i + 1 < breadcrumbs.size) {
                breadcrumbs[i + 1].fromScreenFp
            } else {
                finalScreenFp.ifBlank { "SCREEN_GOAL_REACHED" }
            }
            val anchor = EpisodicAppMemory.LearnedNodeAnchor(
                label = curr.clickedNode.label.trim().take(24),
                shortTypeTag = curr.clickedNode.shortTypeTag,
                gridX = curr.clickedNode.gridTapPoint.x,
                gridY = curr.clickedNode.gridTapPoint.y,
                intentKey = goalIntent.trim().take(24),
                successCount = 1
            )
            steps.add(EpisodicAppMemory.GraphTransitionStep(fromScreenFp = curr.fromScreenFp, targetAnchor = anchor, toScreenFp = nextFp))
        }

        if (steps.isNotEmpty()) {
            EpisodicAppMemory.recordTransitionPath(
                context = applicationContext,
                packageName = pkg,
                goalIntent = goalIntent,
                steps = steps
            )
        }
    }

    private fun updateAutoRunButtonUi() {
        val busy = isAutoLoopRunning || isBenchmarkRunning
        val strokePx = dp(2).coerceAtLeast(2)
        // [funboy322 / avoid-ai-design] 懸浮球為純粹基礎白色圓（無任何 Emoji 表情符號）
        floatingOrbView?.apply {
            text = ""
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(strokePx, if (busy) Color.parseColor("#2563EB") else Color.parseColor("#CBD5E1"))
            }
        }
        autoRunStatusBtn?.apply {
            if (busy) {
                text = "停止"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#DC2626"))
                    cornerRadius = dp(12).toFloat()
                }
            } else {
                text = "執行"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB"))
                    cornerRadius = dp(12).toFloat()
                }
            }
        }
    }

    /**
     * 【功能 4：畫面重點智慧朗讀】
     * 即時掃描當前畫面元件樹與局部 OCR，過濾雜訊後產生結構化重點摘要並以繁體中文 TTS 朗讀
     */
    fun readCurrentScreenSummaryAloud(onSummaryReady: ((String) -> Unit)? = null) {
        hudOutputText?.text = "正在解析當前畫面重點並準備朗讀..."
        captureLiveScreenAndRunPipeline { rep ->
            val appName = latestForegroundAppLabel
            val (displaySummary, spokenSummary) = SmartDailyAssistantHelper.buildSmartScreenSummary(appName, rep)
            mainHandler.post {
                hudOutputText?.text = displaySummary
                onSummaryReady?.invoke(displaySummary)
                if (hudExpandedPanel?.visibility != View.VISIBLE) {
                    Toast.makeText(this, spokenSummary.take(85), Toast.LENGTH_LONG).show()
                }
                VoiceCommandAndTtsManager.speakForced(this, spokenSummary, flush = true)
            }
        }
    }

    /**
     * 【R2：軟鍵盤遮擋自動收合（IME 避讓）】
     * 當 AI 已經完成搜尋詞輸入並準備在結果列表點選或滑動時，若輸入法軟鍵盤仍遮擋下半部螢幕或目標可互動元件，自動將鍵盤收起。
     * 整合 ImeDismissGuard 空間遮擋判定、冷卻時間機制 (800ms) 與 FOCUS_INPUT 狀態防護，避免連續返回誤退當前頁面。
     */
    private fun dismissBlockingImeIfNeeded(onCleared: () -> Unit) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onCleared()
            return
        }
        val hasSubmittedOrTyped = hasTypedTextInCurrentStep || InAppWorkingMemory.hasAnySubmittedQuery()
        if (!hasSubmittedOrTyped) {
            onCleared()
            return
        }
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        val winList = runCatching { windows }.getOrDefault(emptyList())
        val (hasIme, imeHeight) = SmartDailyAssistantHelper.detectImeKeyboardOcclusion(winList, sh)
        if (!hasIme) {
            onCleared()
            return
        }

        val imeTopY = sh - imeHeight
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val focusedInput = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val hasFocusedInput = focusedInput != null && focusedInput.isEditable
        val now = SystemClock.elapsedRealtime()

        // 若已點進子頁面/網站且當前無任何聚焦輸入框，禁止僅因畫面下半部遮擋而派發返回鍵，避免誤退剛進入的網頁！
        val isOnNavigatedSubPage = InAppWorkingMemory.navigationStack.isNotEmpty()
        val isTargetInLowerOrOccluded = (!isOnNavigatedSubPage || hasFocusedInput) &&
            isTargetInteractiveElementOccluded(root, imeTopY)

        if (hasFocusedInput &&
            com.example.newandroidapp.stability.ImeDismissGuard.shouldDismissIme(
                hasTypedOrSubmittedText = true,
                hasImeOcclusion = true,
                hasFocusedInput = hasFocusedInput,
                isTargetInLowerOrOccluded = isTargetInLowerOrOccluded,
                currentElapsedMs = now
            )
        ) {
            com.example.newandroidapp.stability.ImeDismissGuard.recordDismiss(now)
            SmartDailyAssistantHelper.markAiActionWindow(700L)
            hudOutputText?.text = "偵測到軟鍵盤遮擋下半部互動區域，自動收起軟鍵盤"
            performGlobalAction(GLOBAL_ACTION_BACK)
            mainHandler.postDelayed({ onCleared() }, 320L)
        } else {
            onCleared()
        }
    }

    private fun isTargetInteractiveElementOccluded(root: AccessibilityNodeInfo?, imeTopY: Int): Boolean {
        if (root == null) return true
        val actionableBounds = mutableListOf<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val tempRect = Rect()
        var visited = 0

        while (queue.isNotEmpty() && visited < 220) {
            val node = queue.removeFirst()
            visited++
            if (node.isClickable && !node.isEditable && !isAccessibilityNodeVoiceOrLensButton(node)) {
                runCatching { node.getBoundsInScreen(tempRect) }
                val tw = tempRect.right - tempRect.left
                val th = tempRect.bottom - tempRect.top
                if (tw > 0 && th > 0) {
                    val label = "${node.text ?: ""} ${node.contentDescription ?: ""}".trim()
                    if (label.isNotEmpty()) {
                        actionableBounds.add(Rect(tempRect))
                    }
                }
            }
            val count = runCatching { node.childCount }.getOrDefault(0).coerceIn(0, 80)
            for (i in 0 until count) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }

        val hasOccludedBottomActions = actionableBounds.any { it.bottom > imeTopY }
        val upperActionableCount = actionableBounds.count { it.bottom <= imeTopY && it.top > 120 }

        return hasOccludedBottomActions || upperActionableCount < 2
    }

    /**
     * 【R1: 智慧載入狀態自適應等待器 (Smart Loading Wait)】
     * 直接檢測即時視窗樹 (`rawWindows` + `rootInActiveWindow`) 的 ProgressBar、圓形/線性載入進度條、SpinKit、骨架屏與空白轉場狀態（不讀取舊 `latestLiveReport`）。
     * 若偵測到載入中或網頁轉場樹為空，動態循環輪詢 (120ms)，自適應等待直到內容渲染完成再擷取畫面或進行決策，防止網頁尚未載入完就搶跑誤判。
     */
    private fun waitForSmartLoadingSettled(attempt: Int = 0, onSettled: () -> Unit) {
        val winList = runCatching { windows }.getOrDefault(emptyList())
        val rootFallback = runCatching { rootInActiveWindow }.getOrNull()

        visionExecutor.execute {
            val check = try {
                val liveCheck = SmartLoadingDetector.detectLoading(
                    report = null,
                    rawWindows = winList,
                    rootNode = rootFallback
                )
                if (!liveCheck.isLoading && attempt < 4 && (rootFallback == null || rootFallback.childCount == 0)) {
                    LoadingDetectionResult(isLoading = true, indicatorType = "WINDOW_TRANSITION")
                } else {
                    liveCheck
                }
            } catch (t: Throwable) {
                CrashGuardManager.recordThrowable(applicationContext, "WaitForSmartLoadingSettled", t)
                LoadingDetectionResult(isLoading = false, indicatorType = "NONE")
            }
            mainHandler.post {
                if (!isAutoLoopRunning) return@post
                val totalWaitedMs = attempt * SmartLoadingDetector.POLL_INTERVAL_MS
                if (check.isLoading && totalWaitedMs < SmartLoadingDetector.MAX_WAIT_TIMEOUT_MS) {
                    hudOutputText?.text = "載入中 (${check.indicatorType})，智慧等待畫面就緒 (${totalWaitedMs}ms)..."
                    mainHandler.postDelayed({
                        waitForSmartLoadingSettled(attempt + 1, onSettled)
                    }, SmartLoadingDetector.POLL_INTERVAL_MS)
                } else {
                    if (attempt > 0 && totalWaitedMs < SmartLoadingDetector.MIN_WAIT_MS) {
                        mainHandler.postDelayed({ onSettled() }, SmartLoadingDetector.MIN_WAIT_MS - totalWaitedMs)
                    } else {
                        onSettled()
                    }
                }
            }
        }
    }

    @Volatile
    private var lastSmartVisualFallbackFp: String = ""

    private fun executeNextAutonomousStep(forcePixelVisionCapture: Boolean = false) {
        if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return
        val isLongOrCrossAppWorkflow = latestLiveDecision?.decomposedSubGoals?.let { goals ->
            goals.any { it.appPhaseIndex >= 2 } || goals.size >= 4
        } == true || CrossAppWorkflowEngine.latestClipboardEntry != null
        val maxStepBudget = if (isLongOrCrossAppWorkflow) 28 else 18
        if (currentLoopStep >= maxStepBudget) {
            stopAutonomousLoop("已達單次任務安全步數上限 (${maxStepBudget}步)")
            return
        }

        dismissBlockingImeIfNeeded autoIme@{
            if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@autoIme
            waitForSmartLoadingSettled autoLoading@{
                if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@autoLoading
                captureLiveScreenAndRunPipeline(forcePixelVisionCapture = forcePixelVisionCapture) { rep ->
                    if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@captureLiveScreenAndRunPipeline

                    // 【第 4 項：極速畫面穩定等待器】僅在首次轉場劇烈變動時快速等待 75ms，不拖慢節奏
                    if (!forcePixelVisionCapture && !ClosedLoopVerifier.isFrameStable(previousSampledReport, rep) && stabilityWaitRetries < 1) {
                        stabilityWaitRetries++
                        hudOutputText?.text = "等待畫面穩定 ($stabilityWaitRetries/1)..."
                        mainHandler.postDelayed({ executeNextAutonomousStep() }, 75L)
                        return@captureLiveScreenAndRunPipeline
                    }
                    stabilityWaitRetries = 0
            currentLoopStep++

            val fgPkg = resolveCurrentTopApplicationPackage()
            val beforeFp = ClosedLoopVerifier.computeScreenFingerprint(fgPkg, rep)
            if (!isInspectingAccidentalTouch) {
                HumanTouchArbitrator.updateCleanPreTouchAnchor(
                    packageName = fgPkg.orEmpty(),
                    appLabel = latestForegroundAppLabel,
                    report = rep,
                    userGoal = activeGoalForHud
                )
            }

            // 【R7 人工安全接管】自動辨識 CAPTCHA、銀行交易密碼、生物指紋/臉部辨識、SMS OTP 驗證畫面
            val handover = com.example.newandroidapp.stability.SafetyGateManager.detectHumanHandoverRequirement(rep)
            if (handover != null && handover.first != com.example.newandroidapp.stability.SafetyGateManager.HandoverCategory.NONE) {
                pauseForHumanHandover(handover.first, handover.second)
                return@captureLiveScreenAndRunPipeline
            }

            // 【升級 #1：跨 App 廣告誤跳與外部劫持防護（若當前子目標本身即為跨 App 第二階段 LAUNCH_APP，則放行不攔截）】
            val pendingCrossAppLaunch = latestLiveDecision?.decomposedSubGoals
                ?.getOrNull(currentSubGoalIndex)
                ?.let { it.intentCategory == "LAUNCH_APP" && it.appPhaseIndex >= 2 } == true
            if (hasLaunchedTargetAppInLoop && !pendingCrossAppLaunch && InAppOperationPlanner.isUnexpectedExternalAppHijack(fgPkg.orEmpty())) {
                val expectedPkg = InAppWorkingMemory.expectedTargetPackage
                val popped = InAppWorkingMemory.popBreadcrumbForBacktrack()
                if (popped != null && expectedPkg.isNotBlank()) {
                    EpisodicAppMemory.recordDeadEndTrap(
                        context = applicationContext,
                        packageName = expectedPkg,
                        screenFp = popped.fromScreenFp,
                        node = popped.clickedNode
                    )
                }
                hudOutputText?.text = "偵測到誤跳外部 App ($fgPkg)，自動拉回目標 App！"
                VoiceCommandAndTtsManager.speak(this@VisionAccessibilityService, "偵測到誤跳外部程式，自動拉回目標程式")
                StabilityFlightRecorder.recordStep(
                    context = applicationContext,
                    stepIndex = currentLoopStep,
                    appLabel = latestForegroundAppLabel,
                    packageName = fgPkg.orEmpty(),
                    screenFingerprint = beforeFp.shortCode(),
                    visibleNodesCount = rep.finalNodes.size,
                    visionLatencyMs = rep.pipelineLatencyMs,
                    aiOpcode = "PULLBACK_EXTERNAL_HIJACK",
                    aiConfidence = 0.99f,
                    aiLatencyMs = 1L,
                    postActionVerified = true,
                    retryOrFallbackInfo = "防誤跳外部App($fgPkg->$expectedPkg)",
                    outcomeNote = "自動返回並拉回目標 App"
                )
                performGlobalAction(GLOBAL_ACTION_BACK)
                mainHandler.postDelayed({
                    if (!isAutoLoopRunning) return@postDelayed
                    val curPkgAfterBack = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: latestForegroundPackage
                    if (expectedPkg.isNotBlank() && curPkgAfterBack != expectedPkg) {
                        InstalledAppLauncher.launchApp(this@VisionAccessibilityService, expectedPkg)
                    }
                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 1200L)
                }, 550L)
                return@captureLiveScreenAndRunPipeline
            }

            // 【終極目標達成即停閘門 (Terminal Goal Completion Gate)】
            // 每一輪決策前先檢查：若使用者給定的終極目標已經在當前畫面上達成，立即撤銷動手權限並停止，絕不多做任何多餘動作！
            if (ClosedLoopVerifier.isTerminalGoalAlreadySatisfied(
                    userGoal = activeGoalForHud,
                    currentPackage = fgPkg,
                    report = rep,
                    hasTypedTextInLoop = hasTypedTextInCurrentStep,
                    activeSubGoalIndex = currentSubGoalIndex,
                    totalSubGoals = latestLiveDecision?.decomposedSubGoals?.size ?: 1
                )
            ) {
                InAppWorkingMemory.revokeMotorPermissionImmediately("TERMINAL_GOAL_ALREADY_SATISFIED")
                val reportText = "已經幫你弄好了。"
                com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                    text = reportText,
                    metadataHeader = "剛剛 · 已完成",
                    isProactive = true,
                    candidates = emptyList()
                )
                stopAutonomousLoop(reportText)
                return@captureLiveScreenAndRunPipeline
            }

            val modeSnap = selectedAiMode
            val goalSnap = activeGoalForHud
            val alreadyLaunchedSnap = hasLaunchedTargetAppInLoop
            val subIdxSnap = currentSubGoalIndex
            val lockSnap = loopLockedToCurrentApp
            val scrollNoChangeSnap = consecutiveScrollNoChangeCount
            val stuckCountSnap = consecutiveStuckCount

            visionExecutor.execute {
                val dec = try {
                    DualLocalAiCoordinator.decide(
                        mode = modeSnap,
                        userGoal = goalSnap,
                        report = rep,
                        context = this@VisionAccessibilityService,
                        currentForegroundPackage = fgPkg,
                        alreadyLaunchedApp = alreadyLaunchedSnap,
                        activeSubGoalIndex = subIdxSnap,
                        lockToCurrentApp = lockSnap,
                        consecutiveScrollNoChangeCount = scrollNoChangeSnap,
                        consecutiveStuckCount = stuckCountSnap
                    )
                } catch (t: Throwable) {
                    CrashGuardManager.recordThrowable(applicationContext, "AutonomousStepDecide", t)
                    mainHandler.post {
                        if (isAutoLoopRunning) {
                            stopAutonomousLoop("AI 決策異常保護攔截：${t.javaClass.simpleName}")
                        }
                    }
                    return@execute
                }
                // 診斷輸出：`adb logcat -s AiFlight` 可看到 AI 看到的節點、決策與推理過程
                runCatching {
                    val nodeDump = rep.finalNodes.joinToString(" ; ") { nd ->
                        "#${nd.shortMarkId}[${nd.shortTypeTag}/${nd.sourceType.name}/${nd.predictedFunctionRole}]" +
                            "「${nd.inlineStatePrefix}${nd.label}」@${nd.finalTapPx.x},${nd.finalTapPx.y}"
                    }
                    android.util.Log.i("AiFlight", "STEP#$currentLoopStep pkg=$fgPkg goal=\"$goalSnap\" sub=$subIdxSnap stuck=$stuckCountSnap")
                    android.util.Log.i("AiFlight", "  NODES(${rep.finalNodes.size}): $nodeDump")
                    android.util.Log.i(
                        "AiFlight",
                        "  DECISION=${dec.actionType} target=#${dec.targetMarkId}「${dec.targetNode?.label}」 conf=${dec.confidence} engine=${dec.engineName}"
                    )
                    android.util.Log.i("AiFlight", "  TRACE=${dec.reasoningTrace}")
                }
                mainHandler.post {
                    if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@post
                    handleAutonomousDecisionOnMainThread(
                        rep = rep,
                        fgPkg = fgPkg,
                        beforeFp = beforeFp,
                        dec = dec,
                        forcePixelVisionCapture = forcePixelVisionCapture,
                        maxStepBudget = maxStepBudget
                    )
                }
            }
                }
            }
        }
    }

    private fun handleAutonomousDecisionOnMainThread(
        rep: VisionPipelineReport,
        fgPkg: String?,
        beforeFp: ClosedLoopVerifier.ScreenFingerprint,
        dec: AiActionDecision,
        forcePixelVisionCapture: Boolean,
        maxStepBudget: Int
    ) {
            latestLiveDecision = dec

            // 【智慧按需視覺補強 (Smart Visual Fallback)】
            // 平常優先走 0ms Fast-Tree；當三合一主腦在無障礙樹找不到匹配目標而準備向下滑動尋找 (SD_EXPLORE) 時，
            // 若當前畫面尚未執行過像素視覺掃描，立即先對當前畫面觸發一次截圖執行 NanoDet + OCR 與幾何圖示補盲，
            // 並重新送入三合一主腦評估，確認當前畫面確實無目標後才滑動！
            if (dec.compactOpcode == "SD_EXPLORE" &&
                !forcePixelVisionCapture &&
                rep.nanoDetDetectedCount == 0 &&
                rep.roiRecoveredCount == 0 &&
                lastSmartVisualFallbackFp != beforeFp.shortCode() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                lastSmartVisualFallbackFp = beforeFp.shortCode()
                InAppWorkingMemory.rollbackScroll()
                currentLoopStep = (currentLoopStep - 1).coerceAtLeast(0)
                hudOutputText?.text = "[智慧視覺補盲] 節點樹未見目標，啟動 NanoDet + OCR 像素掃描..."
                executeNextAutonomousStep(forcePixelVisionCapture = true)
                return
            }

            arOverlayView?.report = rep
            arOverlayView?.aiDecision = dec
            arOverlayView?.visibility = if (isArOverlayShown) View.VISIBLE else View.GONE
            arOverlayView?.invalidate()

            val totalSubGoals = dec.decomposedSubGoals.size.coerceAtLeast(1)
            // 若當前子目標原本是 LAUNCH_APP（目標 App 已在前景）或 INPUT_SEARCH（同定義查詢已送出且結果頁已載入），同步推進 currentSubGoalIndex！
            while (currentSubGoalIndex + 1 < totalSubGoals) {
                val curSub = dec.decomposedSubGoals.getOrNull(currentSubGoalIndex) ?: break
                if (curSub.intentCategory == "LAUNCH_APP" && dec.actionType != AiActionType.LAUNCH_APP) {
                    hasLaunchedTargetAppInLoop = true
                    currentSubGoalIndex++
                    continue
                }
                if (curSub.intentCategory == "INPUT_SEARCH" && dec.actionType != AiActionType.INPUT_TEXT) {
                    val exitVerdict = com.example.newandroidapp.ai.InstructionCompilerPipeline.evaluateExitCondition(
                        subGoal = curSub,
                        report = rep,
                        currentForegroundPackage = fgPkg,
                        alreadyLaunchedApp = hasLaunchedTargetAppInLoop
                    )
                    if (exitVerdict.satisfied) {
                        currentSubGoalIndex++
                        continue
                    }
                }
                break
            }
            val activeSub = dec.decomposedSubGoals.getOrNull(currentSubGoalIndex)

            when (dec.actionType) {
                AiActionType.LAUNCH_APP -> {
                    val pkg = dec.targetAppPackage
                    if (!pkg.isNullOrBlank()) {
                        val ok = InstalledAppLauncher.launchApp(this@VisionAccessibilityService, pkg)
                        hasLaunchedTargetAppInLoop = true
                        hasTypedTextInCurrentStep = false
                        attemptedNodeKeysInStep.clear()
                        InAppWorkingMemory.recordAppLaunched(pkg)
                        InAppWorkingMemory.setExpectedTargetPackage(pkg)
                        StabilityFlightRecorder.recordStep(
                            context = applicationContext,
                            stepIndex = currentLoopStep,
                            appLabel = dec.targetAppLabel ?: pkg,
                            packageName = pkg,
                            screenFingerprint = beforeFp.shortCode(),
                            visibleNodesCount = rep.finalNodes.size,
                            visionLatencyMs = rep.pipelineLatencyMs,
                            aiOpcode = dec.compactOpcode,
                            aiConfidence = dec.confidence,
                            aiLatencyMs = dec.latencyMs,
                            postActionVerified = ok,
                            retryOrFallbackInfo = "啟動App",
                            outcomeNote = "開啟 ${dec.targetAppLabel ?: pkg}"
                        )
                        if (currentSubGoalIndex + 1 < totalSubGoals) {
                            currentSubGoalIndex++
                        } else {
                            mainHandler.postDelayed({
                                stopAutonomousLoop("已成功為您打開「${dec.targetAppLabel ?: pkg}」！")
                            }, 280L)
                            return
                        }
                        if (ok) {
                            waitForAppWindowReadyAndStep(pkg)
                        } else {
                            stopAutonomousLoop("無法啟動目標 App: $pkg")
                        }
                    } else {
                        stopAutonomousLoop("找不到對應的已安裝 App")
                    }
                }

                AiActionType.TAP -> {
                    // 【第 1 項：檢查是否需要切換為第 2 / 第 3 名候選節點】
                    var chosenNode = dec.targetNode
                    if (chosenNode != null && attemptedNodeKeysInStep.contains(ClosedLoopVerifier.makeNodeAttemptKey(chosenNode))) {
                        val fallbackNode = ClosedLoopVerifier.pickNextFallbackCandidate(dec, attemptedNodeKeysInStep)
                        if (fallbackNode != null) {
                            chosenNode = fallbackNode
                        }
                    }

                    val px = chosenNode?.finalTapPx ?: dec.tapPixel
                    if (px != null && chosenNode != null) {
                        reverifyAndLockTargetCoordinateAsync(chosenNode, px) { lockedPx, reLockNote ->
                            if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@reverifyAndLockTargetCoordinateAsync
                            val isBlockerDismiss = dec.reasoningTrace.contains("自動排除阻擋彈窗") || dec.compactOpcode.contains("BLOCKER")
                            val rawSubQuery = activeSub?.inputPayload?.takeIf { it.isNotBlank() } ?: activeSub?.targetSemanticPhrase.orEmpty()
                            val activeSubQuery = CrossAppWorkflowEngine.resolveDynamicPayload(rawSubQuery) ?: rawSubQuery
                            val isActiveQueryAlreadySubmitted = if (activeSubQuery.isNotBlank() && activeSubQuery != CrossAppWorkflowEngine.CLIPBOARD_PLACEHOLDER) {
                                InAppWorkingMemory.hasSubmittedEquivalentQuery(activeSubQuery)
                            } else {
                                InAppWorkingMemory.hasAnySubmittedQuery()
                            }
                            val isExpandingSearchEntry = !hasTypedTextInCurrentStep &&
                                !isActiveQueryAlreadySubmitted &&
                                (activeSub?.intentCategory == "INPUT_SEARCH" ||
                                    dec.reasoningTrace.contains("Stage A") ||
                                    dec.reasoningTrace.contains("L3A") ||
                                    dec.reasoningTrace.contains("L3B")) &&
                                chosenNode.shortTypeTag != "INP"

                            val nodeKey = ClosedLoopVerifier.makeNodeAttemptKey(chosenNode)
                            val isFallbackAttempt = attemptedNodeKeysInStep.isNotEmpty()
                            if (!isBlockerDismiss) {
                                attemptedNodeKeysInStep.add(nodeKey)
                            }

                            val screenHashForMem = InAppOperationPlanner.computeQuickScreenHash(rep)
                            // 僅在降級切換了不同候選節點時補記點擊（DualLocalAiCoordinator.decide 已針對首選節點記錄過 1 次，避免重複累加導致搜尋鍵被永久封鎖）
                            if (chosenNode != dec.targetNode) {
                                InAppWorkingMemory.recordElementClicked(chosenNode, screenHashForMem)
                            }
                            if (isBlockerDismiss) {
                                InAppWorkingMemory.recordBlockerDismissed(chosenNode.label)
                            } else if (isExpandingSearchEntry) {
                                InAppWorkingMemory.recordSearchEntryClicked("", chosenNode)
                            }

                            hudOutputText?.text = "[步數 $currentLoopStep] 點擊「${chosenNode.label}」$reLockNote ${if (isFallbackAttempt) "(候選降級重試)" else ""}"

                            val tapDefense = HeadsUpNotificationDefenseGuard.evaluateTopBannerOcclusionStrategy(
                                actionType = AiActionType.TAP,
                                targetX = lockedPx.x,
                                targetY = lockedPx.y,
                                targetNode = chosenNode,
                                hasUnderlyingEditableNode = false,
                                screenWidth = rep.screenWidth,
                                screenHeight = rep.screenHeight
                            )

                            val runTapBlock: () -> Unit = {
                                performRealTapAt(lockedPx.x.toFloat(), lockedPx.y.toFloat(), chosenNode.label) {
                                    if (!isAutoLoopRunning) return@performRealTapAt
                                    if (isBlockerDismiss) {
                                        StabilityFlightRecorder.recordStep(
                                            context = applicationContext,
                                            stepIndex = currentLoopStep,
                                            appLabel = latestForegroundAppLabel,
                                            packageName = fgPkg.orEmpty(),
                                            screenFingerprint = beforeFp.shortCode(),
                                            visibleNodesCount = rep.finalNodes.size,
                                            visionLatencyMs = rep.pipelineLatencyMs,
                                            aiOpcode = "DISMISS_BLOCKER(${chosenNode.label})",
                                            aiConfidence = dec.confidence,
                                            aiLatencyMs = dec.latencyMs,
                                            postActionVerified = true,
                                            retryOrFallbackInfo = "排除彈窗|$reLockNote",
                                            outcomeNote = "已點擊阻擋彈窗按鈕「${chosenNode.label}」，繼續原任務"
                                        )
                                        mainHandler.postDelayed({ executeNextAutonomousStep() }, 200L)
                                        return@performRealTapAt
                                    }

                                    // 【第 1 & 第 3 項：動作後等待網頁/介面穩定載入與目標到達確認】
                                    SmartDailyAssistantHelper.markAiActionWindow(1500L)
                                    val initialPostTapWaitMs = if (isExpandingSearchEntry) 220L else 380L
                                    mainHandler.postDelayed({
                                        if (!isAutoLoopRunning) return@postDelayed
                                        waitForSmartLoadingSettled tapLoading@{
                                            if (!isAutoLoopRunning) return@tapLoading
                                            fun evaluatePostTapReport(afterRep: VisionPipelineReport, hasWaitedExtraForDom: Boolean) {
                                                if (!isAutoLoopRunning) return
                                                val needsExtraWebDomSettle = !isExpandingSearchEntry &&
                                                    !hasWaitedExtraForDom &&
                                                    (afterRep.finalNodes.size <= 2 ||
                                                        com.example.newandroidapp.stability.SmartLoadingDetector.isScreenCurrentlyLoading(afterRep) ||
                                                        !ClosedLoopVerifier.isFrameStable(rep, afterRep))
                                                if (needsExtraWebDomSettle) {
                                                    hudOutputText?.text = "等待網頁或新介面內容渲染穩定..."
                                                    mainHandler.postDelayed(extraDelay@{
                                                        if (!isAutoLoopRunning) return@extraDelay
                                                        waitForSmartLoadingSettled extraSettle@{
                                                            if (!isAutoLoopRunning) return@extraSettle
                                                            captureLiveScreenAndRunPipeline { settledRep ->
                                                                evaluatePostTapReport(settledRep, hasWaitedExtraForDom = true)
                                                            }
                                                        }
                                                    }, 480L)
                                                    return
                                                }

                                                val rawAfterPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: latestForegroundPackage
                                                val afterPkg = if (rawAfterPkg == "com.android.systemui" && InAppWorkingMemory.expectedTargetPackage.isNotBlank()) {
                                                    InAppWorkingMemory.expectedTargetPackage
                                                } else rawAfterPkg
                                                val afterFp = ClosedLoopVerifier.computeScreenFingerprint(afterPkg, afterRep)
                                                val changed = ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp) ||
                                                    (isExpandingSearchEntry && afterRep.finalNodes.any { it.shortTypeTag == "INP" })
                                                com.example.newandroidapp.ai.AdaptiveComputeGate.onScreenTransitionVerified(
                                                    screenChanged = changed,
                                                    afterScreenHash = InAppOperationPlanner.computeQuickScreenHash(afterRep)
                                                )

                                                if (!changed) {
                                                    // 【動態抗改版與向量自癒記憶】點擊後閉環驗證發現畫面結構指紋未如預期改變：
                                                    // 自動將該筆記憶信心值歸零 (confidenceWeight = 0.0f)，不報錯中斷，下一輪無縫切換回全畫面掃描與餘弦相似度匹配新替身
                                                    EpisodicAppMemory.onAnchorClickVerificationFailed(
                                                        context = applicationContext,
                                                        packageName = (afterPkg ?: fgPkg).orEmpty(),
                                                        clickedNode = chosenNode,
                                                        stage = if (isExpandingSearchEntry) com.example.newandroidapp.stability.InAppOperationStage.STAGE_A_EXPAND_SEARCH else com.example.newandroidapp.stability.InAppOperationStage.IN_APP_NAVIGATION,
                                                        intentKeyword = activeSub?.targetSemanticPhrase ?: activeGoalForHud,
                                                        screenFp = beforeFp.shortCode()
                                                    )
                                                    // 嘗試第 7 項：直接透過新鮮 AccessibilityNodeInfo.refresh() 觸發節點級 ACTION_CLICK 補償（不虛報 changed = true，交由下一輪驗證）
                                                    SmartDailyAssistantHelper.markAiActionWindow(850L)
                                                    performAccessibilityNodeClickAt(lockedPx.x, lockedPx.y)
                                                    if ((isExpandingSearchEntry || consecutiveStuckCount == 0) && consecutiveStuckCount < 2) {
                                                        // 若搜尋按鈕或目標按鈕因剛開 App / 動畫尚未就緒而未觸發轉場，第 1 次撤銷預記的封鎖標記並保留於首選重試！
                                                        InAppWorkingMemory.rollbackUnverifiedSearchEntryClick(chosenNode, screenHashForMem)
                                                        attemptedNodeKeysInStep.remove(nodeKey)
                                                    }
                                                } else {
                                                    if (!afterPkg.isNullOrBlank() &&
                                                        InAppOperationPlanner.isBrowserOrSearchEquivalentTransition(afterPkg, InAppWorkingMemory.expectedTargetPackage)
                                                    ) {
                                                        InAppWorkingMemory.setExpectedTargetPackage(afterPkg)
                                                    }
                                                    HumanTouchArbitrator.updateCleanPreTouchAnchor(
                                                        packageName = (afterPkg ?: fgPkg).orEmpty(),
                                                        appLabel = latestForegroundAppLabel,
                                                        report = afterRep,
                                                        userGoal = activeGoalForHud
                                                    )
                                                    if (isExpandingSearchEntry && afterRep.finalNodes.any { it.shortTypeTag == "INP" }) {
                                                        EpisodicAppMemory.recordVerifiedSearchEntry(
                                                            context = applicationContext,
                                                            packageName = (afterPkg ?: fgPkg).orEmpty(),
                                                            appLabel = latestForegroundAppLabel,
                                                            node = chosenNode,
                                                            screenWidth = afterRep.screenWidth,
                                                            screenHeight = afterRep.screenHeight
                                                        )
                                                    }
                                                }

                                                val fallbackTag = if (isFallbackAttempt) "降級候選#${attemptedNodeKeysInStep.size}" else "首選命中"
                                                StabilityFlightRecorder.recordStep(
                                                    context = applicationContext,
                                                    stepIndex = currentLoopStep,
                                                    appLabel = latestForegroundAppLabel,
                                                    packageName = afterPkg.orEmpty(),
                                                    screenFingerprint = "${beforeFp.shortCode()}->${afterFp.shortCode()}",
                                                    visibleNodesCount = afterRep.finalNodes.size,
                                                    visionLatencyMs = afterRep.pipelineLatencyMs,
                                                    aiOpcode = "TAP(${chosenNode.label})",
                                                    aiConfidence = dec.confidence,
                                                    aiLatencyMs = dec.latencyMs,
                                                    postActionVerified = changed,
                                                    retryOrFallbackInfo = "$fallbackTag|$reLockNote",
                                                    outcomeNote = if (changed) "畫面已響應變化" else "畫面未變，準備重試/換候選"
                                                )

                                                if (!changed) {
                                                    // 【P6 圖標動作後驗證與自救】檢查是否需要切換動作模式 ('c' <-> 'r')、微調座標偏移或換候選圖標
                                                    val p6Rescue = com.example.newandroidapp.ai.CommandLexiconEngine.verifyIconActionAndRecover(
                                                        packageName = (afterPkg ?: fgPkg).orEmpty(),
                                                        targetIconNode = chosenNode,
                                                        executedActionCode = 'c',
                                                        beforeNodes = rep.finalNodes,
                                                        afterNodes = afterRep.finalNodes
                                                    )
                                                    when (p6Rescue.rescueStrategy) {
                                                        com.example.newandroidapp.ai.CommandLexiconEngine.IconRescueStrategy.SWITCH_ACTION_MODE -> {
                                                            hudOutputText?.text = p6Rescue.diagnosticMessage
                                                            if (p6Rescue.recommendedActionCode == 'r') {
                                                                performRealVerticalDragFrom(
                                                                    x = p6Rescue.recommendedPointPx.x,
                                                                    y = p6Rescue.recommendedPointPx.y,
                                                                    up = true
                                                                )
                                                            } else {
                                                                performAccessibilityNodeClickAt(
                                                                    p6Rescue.recommendedPointPx.x,
                                                                    p6Rescue.recommendedPointPx.y
                                                                )
                                                            }
                                                        }
                                                        com.example.newandroidapp.ai.CommandLexiconEngine.IconRescueStrategy.APPLY_POSITION_OFFSET -> {
                                                            hudOutputText?.text = p6Rescue.diagnosticMessage
                                                            performAccessibilityNodeClickAt(
                                                                p6Rescue.recommendedPointPx.x,
                                                                p6Rescue.recommendedPointPx.y
                                                            )
                                                        }
                                                        com.example.newandroidapp.ai.CommandLexiconEngine.IconRescueStrategy.SWITCH_CANDIDATE_ICON -> {
                                                            hudOutputText?.text = p6Rescue.diagnosticMessage
                                                            attemptedNodeKeysInStep.add(nodeKey)
                                                        }
                                                        else -> {}
                                                    }

                                                    consecutiveStuckCount++
                                                    if (consecutiveStuckCount >= 3) {
                                                        stopAutonomousLoop("連續點擊候選元件後畫面均無反應，已安全停止避免誤觸")
                                                        return
                                                    }
                                                    // 留在同一子目標，下一輪自動挑選第 2/3 名候選重試
                                                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 200L)
                                                    return
                                                }

                                                consecutiveStuckCount = 0
                                                attemptedNodeKeysInStep.clear()

                                                val nextSub = dec.decomposedSubGoals.getOrNull(currentSubGoalIndex + 1)
                                                // 【雙驗收官分級審核：BASIC 單驗收官(原生) / COMPLEX 或 IRREVERSIBLE 雙驗收官(原生+Needle3)】
                                                val audit = ClosedLoopVerifier.evaluateDualInspectorVerification(
                                                    userGoal = activeGoalForHud,
                                                    activeSubGoal = activeSub,
                                                    nextSubGoal = nextSub,
                                                    actionType = AiActionType.TAP,
                                                    targetNodeLabel = chosenNode.label,
                                                    beforeFp = beforeFp,
                                                    afterFp = afterFp,
                                                    afterReport = afterRep,
                                                    hasTypedTextInStep = hasTypedTextInCurrentStep,
                                                    hasTypedTextInLoop = hasTypedTextInCurrentStep,
                                                    isExpandingSearchEntry = isExpandingSearchEntry,
                                                    currentReplanCount = currentLoopReplanCount,
                                                    consecutiveNoChangeCount = consecutiveScrollNoChangeCount,
                                                    context = applicationContext
                                                )
                                                val subGoalSatisfied = audit.subGoalSatisfied

                                                if (!isBlockerDismiss && !isExpandingSearchEntry && subGoalSatisfied) {
                                                    EpisodicAppMemory.recordVerifiedTargetClick(
                                                        context = applicationContext,
                                                        packageName = afterPkg.orEmpty(),
                                                        appLabel = latestForegroundAppLabel,
                                                        intentKeyword = activeSub?.targetSemanticPhrase ?: activeGoalForHud,
                                                        node = chosenNode,
                                                        screenWidth = afterRep.screenWidth,
                                                        screenHeight = afterRep.screenHeight
                                                    )
                                                }

                                                // 若已確認到達最終目標網頁或介面，第一時間立刻封鎖動手權限並終止迴圈，嚴禁後續任何退回或移動！
                                                if (!isExpandingSearchEntry && (audit.terminalGoalReached || (subGoalSatisfied && currentSubGoalIndex + 1 >= totalSubGoals))) {
                                                    InAppWorkingMemory.revokeMotorPermissionImmediately("FINAL_TAP_SUBGOAL_COMPLETED")
                                                    recordGraphMemoryFromNavigationStack(
                                                        packageName = afterPkg ?: fgPkg.orEmpty(),
                                                        goalIntent = activeGoalForHud,
                                                        finalScreenFp = afterFp.shortCode()
                                                    )
                                                    val reportText = "已經幫你弄好了。"
                                                    com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                                                        text = reportText,
                                                        metadataHeader = "剛剛 · 已完成",
                                                        isProactive = true,
                                                        candidates = emptyList()
                                                    )
                                                    stopAutonomousLoop(reportText)
                                                } else if (!isExpandingSearchEntry && !subGoalSatisfied && audit.requiresReplan) {
                                                    val replanned = DualLocalAiCoordinator.requestReplanIfAllowed(
                                                        rawGoal = activeGoalForHud,
                                                        context = this@VisionAccessibilityService,
                                                        lockToCurrentApp = loopLockedToCurrentApp
                                                    )
                                                    if (replanned == null) {
                                                        stopAutonomousLoop("${audit.auditBadge} 驗收未通過且重規劃已達上限 (3次)，安全停止")
                                                        return
                                                    }
                                                    currentLoopReplanCount = replanned.replanCount
                                                    hudOutputText?.text = "${audit.auditBadge} 觸發決策層重規劃 (${currentLoopReplanCount}/3)..."
                                                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                                                } else if ((isExpandingSearchEntry || (nextSub != null && nextSub.appPhaseIndex > (activeSub?.appPhaseIndex ?: 1))) && !subGoalSatisfied) {
                                                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 220L)
                                                } else if (!audit.terminalGoalReached && currentSubGoalIndex + 1 < totalSubGoals) {
                                                    currentSubGoalIndex++
                                                    hasTypedTextInCurrentStep = false
                                                    attemptedNodeKeysInStep.clear()
                                                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                                                } else {
                                                    InAppWorkingMemory.revokeMotorPermissionImmediately("FINAL_TAP_SUBGOAL_COMPLETED")
                                                    recordGraphMemoryFromNavigationStack(
                                                        packageName = afterPkg ?: fgPkg.orEmpty(),
                                                        goalIntent = activeGoalForHud,
                                                        finalScreenFp = afterFp.shortCode()
                                                    )
                                                    val reportText = "已經幫你弄好了。"
                                                    com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                                                        text = reportText,
                                                        metadataHeader = "剛剛 · 已完成",
                                                        isProactive = true,
                                                        candidates = emptyList()
                                                    )
                                                    stopAutonomousLoop(reportText)
                                                }
                                            }
                                            captureLiveScreenAndRunPipeline afterTapCapture@{ afterRep ->
                                                evaluatePostTapReport(afterRep, hasWaitedExtraForDom = false)
                                            }
                                        }
                                    }, initialPostTapWaitMs)
                                }
                            }

                            if (tapDefense.strategy == HeadsUpNotificationDefenseGuard.DefenseStrategy.STRATEGY_A_SWIPE_UP_DISMISS &&
                                tapDefense.swipeTrajectory != null
                            ) {
                                hudOutputText?.text = tapDefense.diagnosticNote
                                performHeadsUpDismissSwipeUp(tapDefense.swipeTrajectory) {
                                    runTapBlock()
                                }
                            } else {
                                runTapBlock()
                            }
                        }
                    } else {
                        stopAutonomousLoop("無有效點擊座標")
                    }
                }

                AiActionType.INPUT_TEXT -> {
                    val px = dec.targetNode?.finalTapPx ?: dec.tapPixel
                    val textToInput = dec.inputTextValue.orEmpty()
                    if (px != null && textToInput.isNotEmpty()) {
                        reverifyAndLockTargetCoordinateAsync(dec.targetNode, px) { lockedPx, reLockNote ->
                            if (!isAutoLoopRunning) return@reverifyAndLockTargetCoordinateAsync
                            hudOutputText?.text = "[步數 $currentLoopStep] 填入「$textToInput」$reLockNote"
                            performRealTextInputAt(
                                x = lockedPx.x.toFloat(),
                                y = lockedPx.y.toFloat(),
                                textValue = textToInput,
                                targetNode = dec.targetNode
                            ) {
                                if (!isAutoLoopRunning) return@performRealTextInputAt
                                hasTypedTextInCurrentStep = true
                                attemptedNodeKeysInStep.clear()
                                InAppWorkingMemory.recordTextTypedAndSubmitted(textToInput, dec.targetNode?.label.orEmpty())
                                StabilityFlightRecorder.recordStep(
                                    context = applicationContext,
                                    stepIndex = currentLoopStep,
                                    appLabel = latestForegroundAppLabel,
                                    packageName = fgPkg.orEmpty(),
                                    screenFingerprint = beforeFp.shortCode(),
                                    visibleNodesCount = rep.finalNodes.size,
                                    visionLatencyMs = rep.pipelineLatencyMs,
                                    aiOpcode = dec.compactOpcode,
                                    aiConfidence = dec.confidence,
                                    aiLatencyMs = dec.latencyMs,
                                    postActionVerified = true,
                                    retryOrFallbackInfo = "文字寫入+送出|$reLockNote",
                                    outcomeNote = "輸入「$textToInput」"
                                )
                                val nextSub = dec.decomposedSubGoals.getOrNull(currentSubGoalIndex + 1)
                                val isCrossAppNext = nextSub != null && nextSub.appPhaseIndex > (activeSub?.appPhaseIndex ?: 1)
                                if (isCrossAppNext) {
                                    dismissBlockingImeIfNeeded textIme@{
                                        if (!isAutoLoopRunning) return@textIme
                                        // 【鎖定跨 App 切換完成條件】必須確認目標關鍵字已載入出實際結果內容，才可推進至下一個 App
                                        waitForSmartLoadingSettled postInputLoading@{
                                            if (!isAutoLoopRunning) return@postInputLoading
                                            captureLiveScreenAndRunPipeline postInputCapture@{ afterInputRep ->
                                                if (!isAutoLoopRunning) return@postInputCapture
                                                val afterInputPkg = InAppWorkingMemory.expectedTargetPackage.ifBlank { fgPkg.orEmpty() }
                                                val afterInputFp = ClosedLoopVerifier.computeScreenFingerprint(afterInputPkg, afterInputRep)
                                                val readyToAdvance = HeadsUpNotificationDefenseGuard.verifyStrictSubGoalAndCrossAppCompletion(
                                                    activeSubGoal = activeSub,
                                                    nextSubGoal = nextSub,
                                                    beforeFp = beforeFp,
                                                    afterFp = afterInputFp,
                                                    afterReport = afterInputRep,
                                                    hasTypedTextInStep = true,
                                                    expectedTargetPackage = afterInputPkg
                                                )
                                                com.example.newandroidapp.ai.AdaptiveComputeGate.onScreenTransitionVerified(
                                                    screenChanged = readyToAdvance || ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterInputFp),
                                                    afterScreenHash = InAppOperationPlanner.computeQuickScreenHash(afterInputRep)
                                                )
                                                if (readyToAdvance && currentSubGoalIndex + 1 < totalSubGoals) {
                                                    currentSubGoalIndex++
                                                    hasTypedTextInCurrentStep = false
                                                    attemptedNodeKeysInStep.clear()
                                                }
                                                mainHandler.postDelayed({ executeNextAutonomousStep() }, 280L)
                                            }
                                        }
                                    }
                                } else if (currentSubGoalIndex + 1 < totalSubGoals) {
                                    mainHandler.postDelayed({
                                        if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@postDelayed
                                        val checkRoot = findTargetAppRootWindowNode() ?: runCatching { rootInActiveWindow }.getOrNull()
                                        val alreadyShowingResults = checkRoot != null &&
                                            isAccessibilityTreeAlreadyShowingSearchResults(checkRoot, textToInput)
                                        val stillFocused = checkRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                        val isMapPkg = fgPkg?.contains("maps", ignoreCase = true) == true ||
                                            InAppWorkingMemory.expectedTargetPackage.contains("maps", ignoreCase = true)
                                        if (!alreadyShowingResults && stillFocused != null && stillFocused.isEditable) {
                                            // 仍在輸入框焦點內且尚未跳轉結果頁：降級為「已填入但尚未送出」，保持在當前子目標進入 Stage B 二次送出/點選建議列
                                            InAppWorkingMemory.downgradeSubmittedToTypedOnly(textToInput)
                                            executeNextAutonomousStep()
                                        } else if (isMapPkg && !alreadyShowingResults) {
                                            // 地圖 App 尚未載入地點詳情卡：保留在搜尋子目標，由下一輪 evaluateExitCondition / Stage B / Stage C 點擊精確地點建議
                                            executeNextAutonomousStep()
                                        } else {
                                            currentSubGoalIndex++
                                            hasTypedTextInCurrentStep = false
                                            attemptedNodeKeysInStep.clear()
                                            if (nextSub?.intentCategory == "EXTRACT_SCREEN_INFO") {
                                                mainHandler.postDelayed({ executeNextAutonomousStep() }, 650L)
                                            } else {
                                                executeNextAutonomousStep()
                                            }
                                        }
                                    }, 280L)
                                } else {
                                    // 檢查輸入文字後是否已進入搜尋結果頁，或仍停留在未送出的搜尋輸入建議頁
                                    mainHandler.postDelayed({
                                        if (!isAutoLoopRunning || !InAppWorkingMemory.hasMotorPermission()) return@postDelayed
                                        val checkRoot = findTargetAppRootWindowNode() ?: runCatching { rootInActiveWindow }.getOrNull()
                                        val alreadyShowingResults = checkRoot != null &&
                                            isAccessibilityTreeAlreadyShowingSearchResults(checkRoot, textToInput)
                                        val stillFocused = checkRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                        if (!alreadyShowingResults && stillFocused != null && stillFocused.isEditable && currentLoopStep < maxStepBudget - 1) {
                                            // 仍在純輸入建議頁且尚未跳轉結果，進入下一輪 Stage B 點擊搜尋鍵或精確建議項送出
                                            InAppWorkingMemory.downgradeSubmittedToTypedOnly(textToInput)
                                            executeNextAutonomousStep()
                                            return@postDelayed
                                        }
                                        // 已經成功送出並載入搜尋結果頁：【第一時間立即撤銷動手權限】並完成停止，禁止再派發任何返回鍵或多點元件！
                                        InAppWorkingMemory.revokeMotorPermissionImmediately("FINAL_INPUT_SEARCH_COMPLETED")
                                        val reportText = "已經幫你弄好了。"
                                        com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                                            text = reportText,
                                            metadataHeader = "剛剛 · 已完成",
                                            isProactive = true,
                                            candidates = emptyList()
                                        )
                                        stopAutonomousLoop(reportText)
                                    }, 320L)
                                }
                            }
                        }
                    } else {
                        stopAutonomousLoop("缺少輸入框座標或文字")
                    }
                }

                AiActionType.SCROLL_DOWN, AiActionType.SCROLL_UP -> {
                    val isDown = dec.actionType == AiActionType.SCROLL_DOWN
                    if (dec.compactOpcode != "SD_EXPLORE" && dec.compactOpcode != "SU_EXPLORE") {
                        InAppWorkingMemory.recordScroll()
                    }
                    val targetScrollNode = dec.targetNode
                    val targetIconCtx = if (!isDown && targetScrollNode != null) {
                        com.example.newandroidapp.ai.CommandLexiconEngine.resolveIconInContext(
                            iconNameOrLabel = targetScrollNode.label,
                            bounds = targetScrollNode.convergedBounds,
                            screenWidth = rep.screenWidth,
                            screenHeight = rep.screenHeight,
                            allNodes = rep.finalNodes,
                            context = applicationContext
                        )
                    } else null
                    val isSheetExpandHandle = targetIconCtx != null &&
                        targetIconCtx.resolvedFunctionalRole == "SWIPE_UP_EXPAND_SHEET" &&
                        targetIconCtx.primaryActionCode == 'r'

                    val onScrollOrDragFinished: () -> Unit = {
                        if (isAutoLoopRunning) {
                            // 【第 2 項：卡關偵測與防盲目滑動 + P6 圖標動作後自救】比對滑動前後的畫面指紋
                            mainHandler.postDelayed({
                                if (!isAutoLoopRunning) return@postDelayed
                                waitForSmartLoadingSettled scrollLoading@{
                                    if (!isAutoLoopRunning) return@scrollLoading
                                    captureLiveScreenAndRunPipeline afterScrollCapture@{ afterRep ->
                                        if (!isAutoLoopRunning) return@afterScrollCapture
                                        val afterPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: latestForegroundPackage
                                        val afterFp = ClosedLoopVerifier.computeScreenFingerprint(afterPkg, afterRep)
                                        val scrollMoved = ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp)
                                        com.example.newandroidapp.ai.AdaptiveComputeGate.onScreenTransitionVerified(
                                            screenChanged = scrollMoved,
                                            afterScreenHash = InAppOperationPlanner.computeQuickScreenHash(afterRep)
                                        )

                                        StabilityFlightRecorder.recordStep(
                                            context = applicationContext,
                                            stepIndex = currentLoopStep,
                                            appLabel = latestForegroundAppLabel,
                                            packageName = afterPkg.orEmpty(),
                                            screenFingerprint = "${beforeFp.shortCode()}->${afterFp.shortCode()}",
                                            visibleNodesCount = afterRep.finalNodes.size,
                                            visionLatencyMs = afterRep.pipelineLatencyMs,
                                            aiOpcode = dec.compactOpcode,
                                            aiConfidence = dec.confidence,
                                            aiLatencyMs = dec.latencyMs,
                                            postActionVerified = scrollMoved,
                                            retryOrFallbackInfo = "連續無變動滑動:$consecutiveScrollNoChangeCount",
                                            outcomeNote = if (scrollMoved) "滑動成功展開新內容" else "畫面已到底/無法滾動"
                                        )

                                        if (!scrollMoved) {
                                            if (targetScrollNode != null) {
                                                val p6Rescue = com.example.newandroidapp.ai.CommandLexiconEngine.verifyIconActionAndRecover(
                                                    packageName = (afterPkg ?: fgPkg).orEmpty(),
                                                    targetIconNode = targetScrollNode,
                                                    executedActionCode = 'r',
                                                    beforeNodes = rep.finalNodes,
                                                    afterNodes = afterRep.finalNodes,
                                                    beforeSheetTopY = targetScrollNode.convergedBounds.top,
                                                    afterSheetTopY = afterRep.finalNodes.minOfOrNull { it.convergedBounds.top } ?: targetScrollNode.convergedBounds.top
                                                )
                                                if (p6Rescue.rescueStrategy == com.example.newandroidapp.ai.CommandLexiconEngine.IconRescueStrategy.SWITCH_ACTION_MODE &&
                                                    p6Rescue.recommendedActionCode == 'c'
                                                ) {
                                                    hudOutputText?.text = p6Rescue.diagnosticMessage
                                                    performAccessibilityNodeClickAt(
                                                        p6Rescue.recommendedPointPx.x,
                                                        p6Rescue.recommendedPointPx.y
                                                    )
                                                } else if (p6Rescue.rescueStrategy == com.example.newandroidapp.ai.CommandLexiconEngine.IconRescueStrategy.SWITCH_CANDIDATE_ICON) {
                                                    hudOutputText?.text = p6Rescue.diagnosticMessage
                                                    attemptedNodeKeysInStep.add(ClosedLoopVerifier.makeNodeAttemptKey(targetScrollNode))
                                                }
                                            }
                                            consecutiveScrollNoChangeCount++
                                            if (consecutiveScrollNoChangeCount >= 2) {
                                                InAppWorkingMemory.revokeMotorPermissionImmediately("TARGET_NOT_FOUND_AFTER_SCROLL")
                                                stopAutonomousLoop("翻到底都沒看到這個按鍵，沒辦法按。")
                                                return@afterScrollCapture
                                            }
                                        } else {
                                            consecutiveScrollNoChangeCount = 0
                                        }

                                        if (activeSub?.intentCategory == "SCROLL") {
                                            if (currentSubGoalIndex + 1 < totalSubGoals) {
                                                currentSubGoalIndex++
                                                mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                                            } else {
                                                stopAutonomousLoop("已完成畫面滑動")
                                            }
                                        } else {
                                            mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                                        }
                                    }
                                }
                            }, 260L)
                        }
                    }

                    if (isSheetExpandHandle && targetScrollNode != null) {
                        performRealVerticalDragFrom(
                            x = targetScrollNode.finalTapPx.x,
                            y = targetScrollNode.finalTapPx.y,
                            up = true,
                            onDone = onScrollOrDragFinished
                        )
                    } else {
                        performRealScroll(down = isDown, customReport = rep, intentCategory = dec.actionType.name, onDone = onScrollOrDragFinished)
                    }
                }

                AiActionType.NAVIGATE_BACK -> {
                    if (!InAppWorkingMemory.hasMotorPermission()) return
                    val isAutoBacktrack = dec.compactOpcode.contains("AUTO_BACKTRACK") ||
                        dec.reasoningTrace.contains("走錯路自動返回")
                    if (isAutoBacktrack) {
                        consecutiveScrollNoChangeCount = 0
                        hudOutputText?.text = "偵測到死胡同，自動按返回鍵退回上一頁並改選下一候選"
                        VoiceCommandAndTtsManager.speak(this@VisionAccessibilityService, "偵測到死胡同，自動返回上一頁重試")
                        SmartDailyAssistantHelper.markAiActionWindow(750L)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        StabilityFlightRecorder.recordStep(
                            context = applicationContext,
                            stepIndex = currentLoopStep,
                            appLabel = latestForegroundAppLabel,
                            packageName = fgPkg.orEmpty(),
                            screenFingerprint = beforeFp.shortCode(),
                            visibleNodesCount = rep.finalNodes.size,
                            visionLatencyMs = rep.pipelineLatencyMs,
                            aiOpcode = dec.compactOpcode,
                            aiConfidence = dec.confidence,
                            aiLatencyMs = dec.latencyMs,
                            postActionVerified = true,
                            retryOrFallbackInfo = "走錯路自動返回上一頁",
                            outcomeNote = "退回上一頁改試下一候選"
                        )
                        mainHandler.postDelayed({ executeNextAutonomousStep() }, 1000L)
                    } else {
                        SmartDailyAssistantHelper.markAiActionWindow(750L)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        if (currentSubGoalIndex + 1 < totalSubGoals) {
                            currentSubGoalIndex++
                            mainHandler.postDelayed({ executeNextAutonomousStep() }, 1200L)
                        } else {
                            stopAutonomousLoop("已執行返回動作")
                        }
                    }
                }

                AiActionType.CONFIRM_HIGH_RISK -> {
                    val px = dec.targetNode?.finalTapPx ?: dec.tapPixel ?: Point(0, 0)
                    val grid = dec.targetNode?.gridTapPoint ?: dec.tapGrid0to999 ?: Point(0, 0)
                    val label = dec.targetNode?.label?.trim()?.ifBlank { "高風險操作" } ?: "高風險操作"
                    com.example.newandroidapp.stability.SafetyGateManager.recordPendingAction(
                        node = dec.targetNode,
                        targetMarkId = dec.targetMarkId,
                        tapPx = px,
                        gridPt = grid,
                        opcode = dec.compactOpcode,
                        packageName = fgPkg
                    )
                    val alertText = "安全閘門攔截：目標「$label」涉及敏感高風險操作，已強制暫停待確認。請在對話框回覆「確認執行」或「取消」。"
                    com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                        text = alertText,
                        metadataHeader = "安全閘門 (C!) 攔截"
                    )
                    stopAutonomousLoop(alertText)
                }

                AiActionType.FINISH_DONE -> {
                    InAppWorkingMemory.revokeMotorPermissionImmediately("AI_DECISION_FINISH_DONE")
                    recordGraphMemoryFromNavigationStack(
                        packageName = fgPkg.orEmpty(),
                        goalIntent = activeGoalForHud,
                        finalScreenFp = beforeFp.shortCode()
                    )
                    val reportText = "已經幫你弄好了。"
                    com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                        text = reportText,
                        metadataHeader = "任務完成 · 主動回報",
                        isProactive = true,
                        candidates = emptyList()
                    )
                    stopAutonomousLoop(reportText)
                }

                AiActionType.ANSWER_QUESTION -> {
                    if (dec.compactOpcode.startsWith("EXTRACT_CROSS_APP") && currentSubGoalIndex + 1 < totalSubGoals) {
                        val extractedSnippet = dec.inputTextValue.orEmpty()
                        hudOutputText?.text = "[跨App資訊擷取] 已提煉「$extractedSnippet」，正在切換至下一階段 App..."
                        StabilityFlightRecorder.recordStep(
                            context = applicationContext,
                            stepIndex = currentLoopStep,
                            appLabel = latestForegroundAppLabel,
                            packageName = fgPkg.orEmpty(),
                            screenFingerprint = beforeFp.shortCode(),
                            visibleNodesCount = rep.finalNodes.size,
                            visionLatencyMs = rep.pipelineLatencyMs,
                            aiOpcode = dec.compactOpcode,
                            aiConfidence = dec.confidence,
                            aiLatencyMs = dec.latencyMs,
                            postActionVerified = true,
                            retryOrFallbackInfo = "跨App語義剪貼簿寫入",
                            outcomeNote = "提煉畫面資訊: $extractedSnippet"
                        )
                        currentSubGoalIndex++
                        mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                        return
                    }
                    if (dec.compactOpcode.startsWith("EXTRACT_CROSS_APP")) {
                        val extractedFinal = dec.inputTextValue.orEmpty().ifBlank { dec.reasoningTrace }
                        val finalChatMsg = "已為您擷取畫面資訊並寫入剪貼簿：$extractedFinal"
                        com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                            text = finalChatMsg,
                            metadataHeader = "EXTRACT_TO_MEMORY · 擷取完成"
                        )
                        VoiceCommandAndTtsManager.speak(this@VisionAccessibilityService, finalChatMsg, flush = true)
                        runCatching {
                            val homeIntent = Intent(this@VisionAccessibilityService, com.example.newandroidapp.MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(homeIntent)
                        }
                        stopAutonomousLoop(finalChatMsg)
                        return
                    }
                    val answerText = dec.reasoningTrace.ifBlank { "已為您處理問答" }
                    hudOutputText?.text = answerText
                    VoiceCommandAndTtsManager.speak(this@VisionAccessibilityService, answerText)
                    com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                        text = answerText,
                        metadataHeader = "零點擊問答"
                    )
                    stopAutonomousLoop(answerText)
                }

                AiActionType.LONG_PRESS -> {
                    val px = dec.targetNode?.finalTapPx ?: dec.tapPixel
                    if (px != null) {
                        reverifyAndLockTargetCoordinateAsync(dec.targetNode, px) { lockedPx, reLockNote ->
                            if (!isAutoLoopRunning) return@reverifyAndLockTargetCoordinateAsync
                            hudOutputText?.text = "[步數 $currentLoopStep] 長按「${dec.targetNode?.label.orEmpty()}」$reLockNote"
                            performRealLongPressAt(lockedPx.x.toFloat(), lockedPx.y.toFloat()) {
                                if (!isAutoLoopRunning) return@performRealLongPressAt
                                attemptedNodeKeysInStep.clear()
                                StabilityFlightRecorder.recordStep(
                                    context = applicationContext,
                                    stepIndex = currentLoopStep,
                                    appLabel = latestForegroundAppLabel,
                                    packageName = fgPkg.orEmpty(),
                                    screenFingerprint = beforeFp.shortCode(),
                                    visibleNodesCount = rep.finalNodes.size,
                                    visionLatencyMs = rep.pipelineLatencyMs,
                                    aiOpcode = dec.compactOpcode,
                                    aiConfidence = dec.confidence,
                                    aiLatencyMs = dec.latencyMs,
                                    postActionVerified = true,
                                    retryOrFallbackInfo = "長按元件|$reLockNote",
                                    outcomeNote = "長按「${dec.targetNode?.label.orEmpty()}」"
                                )
                                if (currentSubGoalIndex + 1 < totalSubGoals) {
                                    currentSubGoalIndex++
                                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 280L)
                                } else {
                                    stopAutonomousLoop("已完成長按動作")
                                }
                            }
                        }
                    } else {
                        stopAutonomousLoop("無有效長按座標")
                    }
                }

                AiActionType.DOUBLE_TAP -> {
                    val px = dec.targetNode?.finalTapPx ?: dec.tapPixel
                    if (px != null) {
                        reverifyAndLockTargetCoordinateAsync(dec.targetNode, px) { lockedPx, reLockNote ->
                            if (!isAutoLoopRunning) return@reverifyAndLockTargetCoordinateAsync
                            hudOutputText?.text = "[步數 $currentLoopStep] 雙擊「${dec.targetNode?.label.orEmpty()}」$reLockNote"
                            performRealDoubleTapAt(lockedPx.x.toFloat(), lockedPx.y.toFloat()) {
                                if (!isAutoLoopRunning) return@performRealDoubleTapAt
                                attemptedNodeKeysInStep.clear()
                                StabilityFlightRecorder.recordStep(
                                    context = applicationContext,
                                    stepIndex = currentLoopStep,
                                    appLabel = latestForegroundAppLabel,
                                    packageName = fgPkg.orEmpty(),
                                    screenFingerprint = beforeFp.shortCode(),
                                    visibleNodesCount = rep.finalNodes.size,
                                    visionLatencyMs = rep.pipelineLatencyMs,
                                    aiOpcode = dec.compactOpcode,
                                    aiConfidence = dec.confidence,
                                    aiLatencyMs = dec.latencyMs,
                                    postActionVerified = true,
                                    retryOrFallbackInfo = "雙擊元件|$reLockNote",
                                    outcomeNote = "雙擊「${dec.targetNode?.label.orEmpty()}」"
                                )
                                if (currentSubGoalIndex + 1 < totalSubGoals) {
                                    currentSubGoalIndex++
                                    mainHandler.postDelayed({ executeNextAutonomousStep() }, 280L)
                                } else {
                                    stopAutonomousLoop("已完成雙擊動作")
                                }
                            }
                        }
                    } else {
                        stopAutonomousLoop("無有效雙擊座標")
                    }
                }

                AiActionType.SWIPE_LEFT, AiActionType.SWIPE_RIGHT -> {
                    val isLeft = dec.actionType == AiActionType.SWIPE_LEFT
                    val gestureName = if (isLeft) "向左滑動" else "向右滑動"
                    hudOutputText?.text = "[步數 $currentLoopStep] $gestureName"
                    val targetY = dec.targetNode?.convergedBounds?.centerY()?.toFloat()
                    performRealHorizontalSwipe(isLeft = isLeft, targetY = targetY) {
                        if (!isAutoLoopRunning) return@performRealHorizontalSwipe
                        mainHandler.postDelayed({
                            if (!isAutoLoopRunning) return@postDelayed
                            waitForSmartLoadingSettled swipeLoading@{
                                if (!isAutoLoopRunning) return@swipeLoading
                                captureLiveScreenAndRunPipeline afterSwipeCapture@{ afterRep ->
                                    if (!isAutoLoopRunning) return@afterSwipeCapture
                                    val afterPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: latestForegroundPackage
                                    val afterFp = ClosedLoopVerifier.computeScreenFingerprint(afterPkg, afterRep)
                                    val swipeMoved = ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp)

                                    StabilityFlightRecorder.recordStep(
                                        context = applicationContext,
                                        stepIndex = currentLoopStep,
                                        appLabel = latestForegroundAppLabel,
                                        packageName = afterPkg.orEmpty(),
                                        screenFingerprint = "${beforeFp.shortCode()}->${afterFp.shortCode()}",
                                        visibleNodesCount = afterRep.finalNodes.size,
                                        visionLatencyMs = afterRep.pipelineLatencyMs,
                                        aiOpcode = dec.compactOpcode,
                                        aiConfidence = dec.confidence,
                                        aiLatencyMs = dec.latencyMs,
                                        postActionVerified = swipeMoved,
                                        retryOrFallbackInfo = gestureName,
                                        outcomeNote = if (swipeMoved) "$gestureName 成功翻頁" else "$gestureName 已至邊界"
                                    )

                                    if (dec.compactOpcode == "SL_EXPLORE") {
                                        mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                                    } else if (currentSubGoalIndex + 1 < totalSubGoals) {
                                        currentSubGoalIndex++
                                        mainHandler.postDelayed({ executeNextAutonomousStep() }, 240L)
                                    } else {
                                        stopAutonomousLoop("已完成$gestureName")
                                    }
                                }
                            }
                        }, 260L)
                    }
                }
            }
    }

    /**
     * 【毫秒級雙重鎖定 (Dual-Lock Auto-Scan) — 點擊/輸入前瞬間座標重鎖定】
     * 關鍵效能優化 (R2.1)：全數移至背景執行緒 (visionExecutor) 執行，避免在 UI 主執行緒進行高耗時遍歷造成掉幀。
     */
    private fun reverifyAndLockTargetCoordinateAsync(
        targetNode: Stage2ConvergedNode?,
        fallbackPx: Point,
        onLocked: (Point, String) -> Unit
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(720)
        val sh = dm.heightPixels.coerceAtLeast(1280)
        val winList = runCatching { windows }.getOrDefault(emptyList())
        val rootFallback = runCatching { rootInActiveWindow }.getOrNull()

        visionExecutor.execute {
            val (lockedPx, reLockNote) = try {
                computeTargetLock(
                    targetNode = targetNode,
                    fallbackPx = fallbackPx,
                    winList = winList,
                    rootFallback = rootFallback,
                    sw = sw,
                    sh = sh,
                    t0 = t0
                )
            } catch (t: Throwable) {
                CrashGuardManager.recordThrowable(applicationContext, "ReverifyTargetCoordinate", t)
                val safeFallback = SystemBarBlockerGuard.clampPointToSafeAppZone(
                    this,
                    fallbackPx.x.toFloat(),
                    fallbackPx.y.toFloat(),
                    sw,
                    sh
                ).first
                safeFallback to "原座標降級鎖定"
            }
            mainHandler.post {
                onLocked(lockedPx, reLockNote)
            }
        }
    }

    private fun computeTargetLock(
        targetNode: Stage2ConvergedNode?,
        fallbackPx: Point,
        winList: List<AccessibilityWindowInfo>,
        rootFallback: AccessibilityNodeInfo?,
        sw: Int,
        sh: Int,
        t0: Long
    ): Pair<Point, String> {
        val freshReport = runCatching {
            val rawNodes = ZOrderTreeCollector.collectFromAccessibilityWindows(
                windows = winList,
                rootFallback = rootFallback,
                screenWidth = sw,
                screenHeight = sh,
                ignorePackageName = packageName
            )
            AiVisionPipeline.execute(
                rawNodes = rawNodes,
                screenWidth = sw,
                screenHeight = sh,
                screenBitmap = null,
                useNormalizedGrid = true
            )
        }.getOrNull()

        val freshScanMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
        if (freshReport == null || targetNode == null || freshReport.finalNodes.isEmpty()) {
            val safeFallback = SystemBarBlockerGuard.clampPointToSafeAppZone(
                this,
                fallbackPx.x.toFloat(),
                fallbackPx.y.toFloat(),
                sw,
                sh
            ).first
            return safeFallback to "原座標鎖定(${freshScanMs}ms)"
        }

        val targetLabel = targetNode.label.trim().lowercase()
        var bestMatch: Stage2ConvergedNode? = null
        var bestDistSq = Double.MAX_VALUE

        for (candidate in freshReport.finalNodes) {
            if (InAppOperationPlanner.isForbiddenVoiceOrCameraNode(candidate)) continue
            if (candidate.shortTypeTag != targetNode.shortTypeTag) continue
            val candLabel = candidate.label.trim().lowercase()
            val labelMatches = (targetLabel.isNotEmpty() && (candLabel == targetLabel || candLabel.contains(targetLabel) || targetLabel.contains(candLabel))) ||
                (targetLabel.isEmpty() && candLabel.isEmpty())
            val dx = (candidate.finalTapPx.x - fallbackPx.x).toDouble()
            val dy = (candidate.finalTapPx.y - fallbackPx.y).toDouble()
            val distSq = dx * dx + dy * dy
            if (labelMatches && distSq <= 360.0 * 360.0) {
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestMatch = candidate
                }
            } else if (distSq <= 220.0 * 220.0) {
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestMatch = candidate
                }
            }
        }

        val locked = bestMatch?.finalTapPx
        return if (locked != null) {
            val safeLocked = SystemBarBlockerGuard.clampPointToSafeAppZone(
                this,
                locked.x.toFloat(),
                locked.y.toFloat(),
                sw,
                sh
            ).first
            val dx = safeLocked.x - fallbackPx.x
            val dy = safeLocked.y - fallbackPx.y
            safeLocked to "毫秒重鎖定(${freshScanMs}ms, d=${dx},${dy}px)"
        } else {
            val safeFallback = SystemBarBlockerGuard.clampPointToSafeAppZone(
                this,
                fallbackPx.x.toFloat(),
                fallbackPx.y.toFloat(),
                sw,
                sh
            ).first
            safeFallback to "原座標鎖定(${freshScanMs}ms)"
        }
    }

    private fun reverifyAndLockTargetCoordinate(
        targetNode: Stage2ConvergedNode?,
        fallbackPx: Point
    ): Pair<Point, String> {
        val t0 = SystemClock.elapsedRealtime()
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(720)
        val sh = dm.heightPixels.coerceAtLeast(1280)
        val winList = runCatching { windows }.getOrDefault(emptyList())
        val rootFallback = runCatching { rootInActiveWindow }.getOrNull()
        return computeTargetLock(targetNode, fallbackPx, winList, rootFallback, sw, sh, t0)
    }

    /**
     * 【第 7 項：`AccessibilityNodeInfo.refresh()` 活性驗證與節點級點擊補償】
     * 當系統手勢點擊因微小位移未觸發時，直接定位座標下的無障礙節點、驗證 `refresh()` 活性並發送 `ACTION_CLICK`
     */
    private fun performAccessibilityNodeClickAt(x: Int, y: Int): Boolean {
        val sh = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        if (SystemBarBlockerGuard.isCoordinateInForbiddenSystemBarZone(this, y, sh)) {
            return false
        }
        val topH = SystemBarBlockerGuard.getStatusBarHeightPx(this, sh)
        val botH = SystemBarBlockerGuard.getNavigationBarHeightPx(this, sh)

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestClickable: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        var visited = 0

        while (queue.isNotEmpty() && visited < 220) {
            val cur = queue.removeFirst()
            visited++
            val b = Rect()
            runCatching { cur.getBoundsInScreen(b) }
            val pkg = cur.packageName?.toString().orEmpty()
            if (b.contains(x, y) && !SystemBarBlockerGuard.isNodeInSystemBarZone(b, pkg, sh, topH, botH)) {
                val area = b.width() * b.height()
                if (cur.isClickable && !isAccessibilityNodeVoiceOrLensButton(cur) && area in 1..bestArea) {
                    bestArea = area
                    bestClickable = cur
                }
                val count = runCatching { cur.childCount }.getOrDefault(0).coerceIn(0, 80)
                for (i in 0 until count) {
                    runCatching { cur.getChild(i) }.getOrNull()?.let { queue.add(it) }
                }
            }
        }

        val target = bestClickable ?: return false
        SmartDailyAssistantHelper.markAiActionWindow(650L)
        return runCatching {
            target.refresh()
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    private fun makeCompactHudButton(label: String, bgHex: String, textColor: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun toggleFloatingHud(enable: Boolean) {
        HudPersistentStateManager.setHudPreferredEnabled(this, enable)
        if (!enable) {
            removeFloatingHud()
            return
        }
        mainHandler.post {
            if (isHudAttached) return@post
            val wm = windowManager ?: return@post

            clearAllAttachedViews(wm)

            val dm = resources.displayMetrics
            val (screenW, screenH) = getLiveScreenSizePx()
            val (savedX, savedY) = HudPersistentStateManager.getSavedOrbPosition(
                context = this,
                defaultX = screenW - 150,
                defaultY = (screenH * 0.30f).toInt()
            )

            val arLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            val arView = LiveArOverlayView(this).apply {
                visibility = if (isArOverlayShown) View.VISIBLE else View.GONE
            }
            runCatching {
                wm.addView(arView, arLp)
                arOverlayView = arView
                attachedOverlayViews.add(arView)
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX.coerceIn(8, (screenW - 80).coerceAtLeast(8))
                y = savedY.coerceIn(8, (screenH - 80).coerceAtLeast(8))
            }
            floatingRootLp = lp

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }

            val orbSizePx = (48 * dm.density).toInt().coerceAtLeast(124)
            val orbRadiusF = orbSizePx / 2f
            val innerItemHeight = (orbSizePx - 24).coerceAtLeast(72)
            val innerRadiusF = innerItemHeight / 2f

            val stroke1dp = dp(1).coerceAtLeast(1)
            val stroke2dp = dp(2).coerceAtLeast(2)

            // [funboy322 / avoid-ai-design] 側邊懸浮小助手改為純粹基礎白色圓（無任何 Emoji 表情符號）
            val orbTv = TextView(this).apply {
                text = ""
                gravity = Gravity.CENTER
                elevation = dp(6).toFloat()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(stroke2dp, if (isAutoLoopRunning || isBenchmarkRunning) Color.parseColor("#2563EB") else Color.parseColor("#CBD5E1"))
                }
            }
            floatingOrbView = orbTv

            val panelWidthPx = (screenW - dp(24)).coerceIn(560, 960)
            // [等高膠囊變形] 展開後的膠囊高度（orbSizePx）與兩端半圓直徑（cornerRadius = orbSizePx / 2f）100% 等於圓球直徑！
            val expandedCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                elevation = dp(8).toFloat()
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = orbRadiusF
                    setStroke(stroke1dp, Color.parseColor("#CBD5E1"))
                }
                setPadding(dp(8), 0, dp(8), 0)
            }
            hudExpandedPanel = expandedCard
            hudLiveAppBadgeTv = null
            hudCurrentAppChipsRow = null
            hudOutputText = null
            autoRunStatusBtn = null
            this@VisionAccessibilityService.capsuleChatContainer = null
            capsuleChatScrollView = null

            // 閒置 3 秒自動半透明化（不擋畫面），觸碰時立即恢復 1.0f
            val idleTranslucentRunnable = Runnable {
                if (orbTv.visibility == View.VISIBLE && !VoiceCommandAndTtsManager.isListening) {
                    orbTv.animate().alpha(0.42f).setDuration(280L).withLayer().start()
                }
            }

            fun scheduleOrbIdleFade() {
                mainHandler.removeCallbacks(idleTranslucentRunnable)
                mainHandler.postDelayed(idleTranslucentRunnable, 3000L)
            }

            fun snapOrbToNearestEdge() {
                val (liveW, liveH) = getLiveScreenSizePx()
                val edgeMargin = 8
                val leftEdgeX = edgeMargin
                val rightEdgeX = (liveW - orbSizePx - edgeMargin).coerceAtLeast(edgeMargin)
                val targetX = if (lp.x + orbSizePx / 2 < liveW / 2) leftEdgeX else rightEdgeX
                lp.y = lp.y.coerceIn(edgeMargin, (liveH - orbSizePx - edgeMargin).coerceAtLeast(edgeMargin))
                HudPersistentStateManager.saveOrbPosition(this@VisionAccessibilityService, targetX, lp.y)
                val startSnapX = lp.x
                if (startSnapX == targetX) {
                    runCatching { wm.updateViewLayout(root, lp) }
                    scheduleOrbIdleFade()
                    return
                }
                android.animation.ValueAnimator.ofInt(startSnapX, targetX).apply {
                    duration = 200L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { anim ->
                        if (isHudAttached && orbTv.visibility == View.VISIBLE) {
                            lp.x = anim.animatedValue as Int
                            runCatching { wm.updateViewLayout(root, lp) }
                        }
                    }
                    start()
                }
                scheduleOrbIdleFade()
            }

            lateinit var cmdInputEt: EditText
            fun submitExpandedCommand() {
                val goal = cmdInputEt.text?.toString()?.trim().orEmpty()
                setHudFocusable(false)
                if (isHandoverPaused) {
                    val lower = goal.lowercase()
                    val isResume = goal == "確認繼續" || goal == "確定繼續" || goal == "繼續執行" ||
                        goal == "我已完成" || goal == "確認完成" || goal == "繼續" ||
                        lower == "resume" || lower == "continue"
                    val isCancel = goal == "取消" || goal == "取消任務" || lower == "cancel" || lower == "abort"
                    if (isResume) {
                        cmdInputEt.setText("")
                        resumeFromHumanHandover()
                        return
                    } else if (isCancel) {
                        cmdInputEt.setText("")
                        cancelFromHumanHandover()
                        return
                    }
                }
                if (goal.isNotEmpty()) {
                    com.example.newandroidapp.chat.ConversationManager.appendUserMessage(goal)
                    cmdInputEt.setText("")
                    if (SmartDailyAssistantHelper.isScreenReadingCommand(goal)) {
                        readCurrentScreenSummaryAloud { summary ->
                            com.example.newandroidapp.chat.ConversationManager.appendAiMessage(
                                text = summary,
                                metadataHeader = "畫面朗讀"
                            )
                        }
                    } else {
                        startAutonomousTaskLoop(goal, selectedAiMode, lockToCurrentApp = false)
                    }
                } else {
                    Toast.makeText(this@VisionAccessibilityService, "請輸入指令或長按輸入框語音輸入", Toast.LENGTH_SHORT).show()
                }
            }

            cmdInputEt = EditText(this).apply {
                setText(activeGoalForHud)
                hint = "輸入指令（長按語音）..."
                textSize = 13f
                setTextColor(Color.parseColor("#0F172A"))
                setHintTextColor(Color.parseColor("#94A3B8"))
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                imeOptions = EditorInfo.IME_ACTION_SEND
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    cornerRadius = innerRadiusF
                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(12), 0, dp(12), 0)
                setOnEditorActionListener { _, actionId, keyEvent ->
                    val isEnterKey = keyEvent != null &&
                        keyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        keyEvent.action == android.view.KeyEvent.ACTION_DOWN
                    if (actionId == EditorInfo.IME_ACTION_SEND ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_ACTION_GO ||
                        actionId == EditorInfo.IME_ACTION_SEARCH ||
                        isEnterKey
                    ) {
                        submitExpandedCommand()
                        true
                    } else false
                }
            }
            hudCommandInputEt = cmdInputEt

            var inputDownX = 0f
            var inputDownY = 0f
            var inputMoved = false
            var inputLongPressed = false
            val inputLongPressVoiceRunnable = Runnable {
                if (!inputMoved) {
                    inputLongPressed = true
                    setHudFocusable(false)
                    startExpandedInputVoiceListening(cmdInputEt)
                }
            }

            cmdInputEt.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        inputDownX = ev.rawX
                        inputDownY = ev.rawY
                        inputMoved = false
                        inputLongPressed = false
                        mainHandler.removeCallbacks(inputLongPressVoiceRunnable)
                        mainHandler.postDelayed(inputLongPressVoiceRunnable, 360L)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - inputDownX
                        val dy = ev.rawY - inputDownY
                        if (dx * dx + dy * dy > 18f * 18f) {
                            inputMoved = true
                            mainHandler.removeCallbacks(inputLongPressVoiceRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(inputLongPressVoiceRunnable)
                        if (!inputMoved && !inputLongPressed && ev.actionMasked == MotionEvent.ACTION_UP) {
                            if (VoiceCommandAndTtsManager.isListening) {
                                VoiceCommandAndTtsManager.stopListening()
                                cmdInputEt.hint = "輸入指令（長按語音）..."
                                cmdInputEt.background = GradientDrawable().apply {
                                    setColor(Color.parseColor("#F8FAFC"))
                                    cornerRadius = innerRadiusF
                                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                                }
                            } else {
                                setHudFocusable(true)
                            }
                        }
                    }
                }
                inputLongPressed
            }

            val runCmdBtn = makeCompactHudButton("執行", "#2563EB", Color.WHITE).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB"))
                    cornerRadius = innerRadiusF
                }
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener {
                    submitExpandedCommand()
                }
            }

            val collapseBtn = makeCompactHudButton("縮小", "#F8FAFC", Color.parseColor("#334155")).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    cornerRadius = innerRadiusF
                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    setHudFocusable(false)
                    val collapsedScaleX = (orbSizePx.toFloat() / panelWidthPx.toFloat()).coerceIn(0.12f, 0.35f)
                    expandedCard.pivotY = orbRadiusF
                    expandedCard.animate()
                        .scaleX(collapsedScaleX)
                        .scaleY(1f)
                        .alpha(0f)
                        .setDuration(170L)
                        .withLayer()
                        .withEndAction {
                            expandedCard.visibility = View.GONE
                            expandedCard.scaleX = 1f
                            expandedCard.scaleY = 1f
                            expandedCard.alpha = 1f
                            orbTv.scaleX = 0.85f
                            orbTv.scaleY = 0.85f
                            orbTv.alpha = 1f
                            orbTv.visibility = View.VISIBLE
                            orbTv.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(180L)
                                .withLayer()
                                .start()
                            snapOrbToNearestEdge()
                        }
                        .start()
                }
            }

            val closeBtn = makeCompactHudButton("關閉", "#F8FAFC", Color.parseColor("#64748B")).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    cornerRadius = innerRadiusF
                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    mainHandler.removeCallbacks(idleTranslucentRunnable)
                    setHudFocusable(false)
                    HudPersistentStateManager.setHudPreferredEnabled(this@VisionAccessibilityService, false)
                    removeFloatingHud()
                    Toast.makeText(this@VisionAccessibilityService, "已關閉懸浮球", Toast.LENGTH_SHORT).show()
                }
            }

            expandedCard.addView(cmdInputEt, LinearLayout.LayoutParams(0, innerItemHeight, 1f).apply {
                rightMargin = dp(8)
            })
            expandedCard.addView(runCmdBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, innerItemHeight).apply {
                rightMargin = dp(4)
            })
            expandedCard.addView(collapseBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, innerItemHeight).apply {
                rightMargin = dp(4)
            })
            expandedCard.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, innerItemHeight))

            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var longPressVoiceTriggered = false
            val touchSlopPx = 18f

            val longPressOrbVoiceRunnable = Runnable {
                if (!moved && orbTv.visibility == View.VISIBLE) {
                    longPressVoiceTriggered = true
                    startDirectOrbVoiceListening(orbTv, cmdInputEt)
                }
            }

            val dragAndTapListener = View.OnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        // 【雙模態真人觸控意圖仲裁器】當 AI 執行中偵測到真人手指觸碰懸浮球外的螢幕區域，自動仲裁停機或誤觸回跳
                        if (SmartDailyAssistantHelper.checkAndRecordHumanTakeover(isAutoLoopRunning)) {
                            handleHumanTouchDetectedOnScreen()
                        }
                        false
                    }
                    MotionEvent.ACTION_DOWN -> {
                        mainHandler.removeCallbacks(idleTranslucentRunnable)
                        orbTv.animate().cancel()
                        orbTv.alpha = 1f
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = lp.x
                        startY = lp.y
                        moved = false
                        longPressVoiceTriggered = false
                        if (view === orbTv) {
                            orbTv.animate().scaleX(0.92f).scaleY(0.92f).setDuration(85L).withLayer().start()
                            mainHandler.removeCallbacks(longPressOrbVoiceRunnable)
                            mainHandler.postDelayed(longPressOrbVoiceRunnable, 360L)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) {
                            moved = true
                            mainHandler.removeCallbacks(longPressOrbVoiceRunnable)
                            val (liveW, liveH) = getLiveScreenSizePx()
                            val activeW = if (expandedCard.visibility == View.VISIBLE) panelWidthPx else orbSizePx
                            val maxX = (liveW - activeW).coerceAtLeast(0)
                            val maxY = (liveH - orbSizePx).coerceAtLeast(0)
                            lp.x = (startX + dx.toInt()).coerceIn(0, maxX)
                            lp.y = (startY + dy.toInt()).coerceIn(0, maxY)
                            runCatching { wm.updateViewLayout(root, lp) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressOrbVoiceRunnable)
                        if (view === orbTv && !longPressVoiceTriggered) {
                            orbTv.animate().scaleX(1f).scaleY(1f).setDuration(180L).withLayer().start()
                        }
                        if (moved && orbTv.visibility == View.VISIBLE) {
                            snapOrbToNearestEdge()
                        } else if (!moved && !longPressVoiceTriggered && view === orbTv && ev.actionMasked == MotionEvent.ACTION_UP) {
                            if (VoiceCommandAndTtsManager.isListening) {
                                VoiceCommandAndTtsManager.stopListening()
                                updateAutoRunButtonUi()
                                scheduleOrbIdleFade()
                                Toast.makeText(this@VisionAccessibilityService, "已停止語音聆聽", Toast.LENGTH_SHORT).show()
                            } else {
                                val (liveW, liveH) = getLiveScreenSizePx()
                                val fromRightSide = (lp.x + orbSizePx / 2) >= (liveW / 2)
                                orbTv.visibility = View.GONE
                                val initialScaleX = (orbSizePx.toFloat() / panelWidthPx.toFloat()).coerceIn(0.12f, 0.35f)
                                expandedCard.pivotX = if (fromRightSide) panelWidthPx.toFloat() else 0f
                                expandedCard.pivotY = orbRadiusF
                                expandedCard.scaleX = initialScaleX
                                expandedCard.scaleY = 1f
                                expandedCard.alpha = 0f
                                expandedCard.visibility = View.VISIBLE
                                lp.x = lp.x.coerceIn(12, (liveW - panelWidthPx - 12).coerceAtLeast(12))
                                lp.y = lp.y.coerceIn(12, (liveH - orbSizePx - 12).coerceAtLeast(12))
                                runCatching { wm.updateViewLayout(root, lp) }
                                expandedCard.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(220L)
                                    .withLayer()
                                    .start()
                                lastChipSignature = ""
                                runRealTimeVisionStreamTick()
                            }
                        } else if (orbTv.visibility == View.VISIBLE) {
                            scheduleOrbIdleFade()
                        }
                        true
                    }
                    else -> false
                }
            }
            orbTv.setOnTouchListener(dragAndTapListener)
            expandedCard.setOnTouchListener(dragAndTapListener)
            root.setOnTouchListener(dragAndTapListener)

            // [heydamir/unslop Pass 2, 5, 6, 7] 純白高階層安全接管卡片，取代刺眼黃底紅綠雙色按鈕
            val alertCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                visibility = View.GONE
                elevation = dp(12).toFloat()
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(16).toFloat()
                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            handoverAlertCard = alertCard

            val alertBadge = TextView(this).apply {
                text = "SECURITY HANDOVER"
                textSize = 11f
                setTextColor(Color.parseColor("#B45309"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.05f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FEF3C7"))
                    cornerRadius = dp(6).toFloat()
                }
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            val alertTitle = TextView(this).apply {
                text = "[安全暫停] 偵測到人機驗證碼 (CAPTCHA)"
                textSize = 15f
                setTextColor(Color.parseColor("#0F172A"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.START
                setPadding(0, dp(8), 0, 0)
            }
            handoverTitleTv = alertTitle

            val alertDesc = TextView(this).apply {
                text = "請您手動完成輸入或驗證操作。\n完成後點擊「我已完成，繼續執行」恢復任務。"
                textSize = 13f
                setTextColor(Color.parseColor("#475569"))
                setLineSpacing(0f, 1.3f)
                gravity = Gravity.START
                setPadding(0, dp(4), 0, dp(16))
            }
            handoverDetailTv = alertDesc

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val resumeBtn = Button(this).apply {
                text = "我已完成，繼續執行"
                textSize = 13f
                isAllCaps = false
                minHeight = dp(44)
                minimumHeight = dp(44)
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB"))
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    resumeFromHumanHandover()
                }
            }

            val cancelBtn = Button(this).apply {
                text = "取消任務"
                textSize = 13f
                isAllCaps = false
                minHeight = dp(44)
                minimumHeight = dp(44)
                setTextColor(Color.parseColor("#475569"))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F1F5F9"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    cancelFromHumanHandover()
                }
            }

            btnRow.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
            btnRow.addView(resumeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            alertCard.addView(alertBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            alertCard.addView(alertTitle)
            alertCard.addView(alertDesc)
            alertCard.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            root.addView(orbTv, LinearLayout.LayoutParams(orbSizePx, orbSizePx))
            root.addView(expandedCard, LinearLayout.LayoutParams(panelWidthPx, orbSizePx))
            root.addView(alertCard, LinearLayout.LayoutParams(panelWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT))
            scheduleOrbIdleFade()

            runCatching {
                wm.addView(root, lp)
                floatingRoot = root
                attachedOverlayViews.add(root)
                isHudAttached = true

                SystemBarBlockerGuard.syncPhysicalShieldOverlays(
                    context = this@VisionAccessibilityService,
                    wm = wm,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    shouldShow = true
                )
                runRealTimeVisionStreamTick()
                mainHandler.postDelayed(realTimeHeartbeatRunnable, 500L)
            }
        }
    }

    /**
     * 懸浮球展開後，長按白色輸入框直接啟動語音輸入
     */
    private fun startExpandedInputVoiceListening(cmdInputEt: EditText) {
        val stroke1dp = dp(1).coerceAtLeast(1)
        val stroke2dp = dp(2).coerceAtLeast(2)
        if (!VoiceCommandAndTtsManager.hasRecordAudioPermission(this)) {
            Toast.makeText(this, "請先授予錄音權限", Toast.LENGTH_LONG).show()
            runCatching {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            return
        }
        cmdInputEt.hint = "正在聆聽語音..."
        cmdInputEt.background = GradientDrawable().apply {
            setColor(Color.parseColor("#EFF6FF"))
            cornerRadius = dp(16).toFloat()
            setStroke(stroke2dp, Color.parseColor("#2563EB"))
        }
        Toast.makeText(this, "開始語音輸入，請說話...", Toast.LENGTH_SHORT).show()
        VoiceCommandAndTtsManager.startListening(
            context = this,
            onPartialResult = { partial ->
                cmdInputEt.setText(partial)
                cmdInputEt.setSelection(partial.length)
            },
            onFinalResult = { spoken ->
                cmdInputEt.hint = "輸入指令（長按語音）..."
                cmdInputEt.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                }
                val clean = spoken.trim()
                cmdInputEt.setText(clean)
                cmdInputEt.setSelection(clean.length)
                if (clean.isNotEmpty()) {
                    Toast.makeText(this, "已填入語音文字，請確認後點「執行」", Toast.LENGTH_SHORT).show()
                }
            },
            onStatusOrError = { msg ->
                if (!VoiceCommandAndTtsManager.isListening) {
                    cmdInputEt.hint = "輸入指令（長按語音）..."
                    cmdInputEt.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        cornerRadius = dp(16).toFloat()
                        setStroke(stroke1dp, Color.parseColor("#E2E8F0"))
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * 【功能 1：白圓球「長按語音轉文字」】
     * 在任何 App 長按側邊純白圓球 360ms 啟動語音聆聽，
     * 說完話後自動展開輸入膠囊並將辨識文字填入輸入框，等待使用者確認後手動按「執行」發送。
     */
    private fun startDirectOrbVoiceListening(orbTv: TextView, cmdInputEt: EditText?) {
        val stroke2dp = dp(2).coerceAtLeast(2)
        val stroke3dp = dp(3).coerceAtLeast(3)
        if (!VoiceCommandAndTtsManager.hasRecordAudioPermission(this)) {
            orbTv.animate().scaleX(1f).scaleY(1f).setDuration(180L).withLayer().start()
            orbTv.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(stroke2dp, Color.parseColor("#CBD5E1"))
            }
            Toast.makeText(this, "請先授予錄音權限", Toast.LENGTH_LONG).show()
            hudOutputText?.text = "未取得麥克風權限，請先在主畫面授予錄音權限"
            runCatching {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            return
        }
        if (isAutoLoopRunning) {
            stopAutonomousLoop("已切換為長按語音聆聽")
        }
        orbTv.text = ""
        orbTv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(stroke3dp, Color.parseColor("#2563EB"))
        }
        orbTv.animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.25f))
            .withLayer()
            .start()

        Toast.makeText(this, "請說話，說完將填入輸入框...", Toast.LENGTH_SHORT).show()

        VoiceCommandAndTtsManager.startListening(
            context = this,
            onPartialResult = { partial ->
                cmdInputEt?.setText(partial)
                cmdInputEt?.setSelection(partial.length)
                hudOutputText?.text = "正在聽您說：$partial"
            },
            onFinalResult = { spoken ->
                orbTv.animate().scaleX(1f).scaleY(1f).setDuration(200L).withLayer().start()
                updateAutoRunButtonUi()
                val clean = spoken.trim()
                cmdInputEt?.setText(clean)
                cmdInputEt?.setSelection(clean.length)
                if (clean.isNotEmpty()) {
                    orbTv.visibility = View.GONE
                    hudExpandedPanel?.apply {
                        scaleX = 1f
                        scaleY = 1f
                        alpha = 1f
                        visibility = View.VISIBLE
                    }
                    Toast.makeText(this, "已填入語音文字，請確認後點「執行」", Toast.LENGTH_SHORT).show()
                }
            },
            onStatusOrError = { msg ->
                if (!VoiceCommandAndTtsManager.isListening) {
                    orbTv.animate().scaleX(1f).scaleY(1f).setDuration(200L).withLayer().start()
                    updateAutoRunButtonUi()
                }
                hudOutputText?.text = msg
            }
        )
    }

    fun performRealTapAt(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") labelInfo: String, onDone: (() -> Unit)? = null) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onDone?.invoke()
            return
        }
        val sessionTokenAtDispatch = InAppWorkingMemory.currentMotorSessionToken()
        SmartDailyAssistantHelper.markAiActionWindow(650L)
        val (sw, sh) = getLiveScreenSizePx()
        val (safePt, _) = SystemBarBlockerGuard.clampPointToSafeAppZone(this, x, y, sw, sh)
        val tapX = safePt.x.toFloat()
        val tapY = safePt.y.toFloat()

        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            if (!InAppWorkingMemory.hasMotorPermission(sessionTokenAtDispatch)) {
                if (isHudAttached) {
                    floatingRoot?.visibility = View.VISIBLE
                    if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                    SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                }
                return@postDelayed
            }
            val path = Path().apply { moveTo(tapX, tapY) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 48L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            mainHandler.postDelayed({
                                if (isHudAttached) {
                                    floatingRoot?.visibility = View.VISIBLE
                                    if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                    SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                                    runRealTimeVisionStreamTick()
                                }
                                onDone?.invoke()
                            }, 45L)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            val nodeClicked = performAccessibilityNodeClickAt(safePt.x, safePt.y)
                            if (!nodeClicked) {
                                visionExecutor.execute {
                                    com.example.newandroidapp.stability.ActionExecutionRouter.routeTap(
                                        x = safePt.x,
                                        y = safePt.y,
                                        service = this@VisionAccessibilityService,
                                        context = this@VisionAccessibilityService,
                                        accessibilitySucceededHint = false
                                    )
                                }
                            }
                            mainHandler.post {
                                if (isHudAttached) {
                                    floatingRoot?.visibility = View.VISIBLE
                                    if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                    SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                                }
                                onDone?.invoke()
                            }
                        }
                    },
                    null
                )
            }.getOrDefault(false)
            if (!dispatched) {
                val nodeClicked = performAccessibilityNodeClickAt(safePt.x, safePt.y)
                if (!nodeClicked) {
                    visionExecutor.execute {
                        com.example.newandroidapp.stability.ActionExecutionRouter.routeTap(
                            x = safePt.x,
                            y = safePt.y,
                            service = this@VisionAccessibilityService,
                            context = this@VisionAccessibilityService,
                            accessibilitySucceededHint = false
                        )
                    }
                }
                mainHandler.post {
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                    }
                    onDone?.invoke()
                }
            }
        }, 25L)
    }

    /**
     * 【通知防禦 — 策略 A：主動向上滑除通知橫幅 (Swipe Up Dismissal)】
     * 當頂部突發彈出 LINE 或系統通知橫幅並遮擋目標座標時，快速對頂部橫幅執行向上滑動推回頂部隱藏，隨後繼續原動作。
     */
    fun performHeadsUpDismissSwipeUp(
        trajectory: HeadsUpNotificationDefenseGuard.SwipeUpDismissTrajectory,
        onDone: (() -> Unit)? = null
    ) {
        SmartDailyAssistantHelper.markAiActionWindow(500L)
        HeadsUpNotificationDefenseGuard.resetState()
        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
        }
        mainHandler.postDelayed({
            val path = Path().apply {
                moveTo(trajectory.startX, trajectory.startY)
                lineTo(trajectory.endX, trajectory.endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, trajectory.durationMs.coerceIn(80L, 220L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            mainHandler.postDelayed({
                                if (isHudAttached) floatingRoot?.visibility = View.VISIBLE
                                onDone?.invoke()
                            }, 90L)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            mainHandler.post {
                                if (isHudAttached) floatingRoot?.visibility = View.VISIBLE
                                onDone?.invoke()
                            }
                        }
                    },
                    null
                )
            }.getOrDefault(false)
            if (!dispatched) {
                if (isHudAttached) floatingRoot?.visibility = View.VISIBLE
                onDone?.invoke()
            }
        }, 15L)
    }

    /**
     * 【極速化升級 2 & 通知防禦（策略 A 上滑消除 + 策略 B 焦點強制鎖定直寫）】
     */
    fun performRealTextInputAt(
        x: Float,
        y: Float,
        textValue: String,
        targetNode: Stage2ConvergedNode? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onDone?.invoke()
            return
        }
        SmartDailyAssistantHelper.markAiActionWindow(900L)
        // 優先使用無障礙 Bundle (ACTION_SET_TEXT) 整段瞬間灌入文字，絕不預先佔用使用者系統剪貼簿！
        // 若極少數元件不支援 ACTION_SET_TEXT，由 GestureExecutor.autoClearAndSetText 內部啟動無痕瞬時借用並立即還原。

        val (sw, sh) = getLiveScreenSizePx()
        // 【通知防禦 — 策略 B（焦點強制鎖定）】：直接穿透上層通知橫幅，鎖定底層目標 App 視窗內的 EditText 節點
        val directEditable = findTargetAppEditableNodeBypassingBanners(x.toInt(), y.toInt(), sw, sh)
        val defenseEval = HeadsUpNotificationDefenseGuard.evaluateTopBannerOcclusionStrategy(
            actionType = AiActionType.INPUT_TEXT,
            targetX = x.toInt(),
            targetY = y.toInt(),
            targetNode = targetNode,
            hasUnderlyingEditableNode = directEditable != null,
            screenWidth = sw,
            screenHeight = sh
        )

        if (directEditable != null) {
            if (defenseEval.strategy == HeadsUpNotificationDefenseGuard.DefenseStrategy.STRATEGY_B_DIRECT_NODE_FOCUS) {
                hudOutputText?.text = defenseEval.diagnosticNote
            }
            val directInjected = runCatching {
                directEditable.refresh()
                directEditable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                directEditable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                com.example.newandroidapp.action.GestureExecutor.autoClearAndSetText(
                    node = directEditable,
                    newText = textValue,
                    context = this
                )
            }.getOrDefault(false)

            if (directInjected) {
                verifyAndSubmitSearchAfterTextInjection(textValue, sw) {
                    if (isHudAttached) runRealTimeVisionStreamTick()
                    onDone?.invoke()
                }
                return
            }
        }

        val tapAndInjectBlock: () -> Unit = {
            performRealTapAt(x, y, "聚焦輸入框") {
                fun tryInjectTextIntoEditable(attempt: Int) {
                    var editableNode = findTargetAppEditableNodeBypassingBanners(x.toInt(), y.toInt(), sw, sh)
                    if (editableNode != null) {
                        val alive = runCatching { editableNode!!.refresh() }.getOrDefault(false)
                        if (!alive) {
                            editableNode = findTargetAppEditableNodeBypassingBanners(x.toInt(), y.toInt(), sw, sh)
                        }
                    }
                    if (editableNode == null && attempt < 2) {
                        // 若點擊搜尋欄後新頁面 EditText 仍在展開動畫中，再等待 180ms 重抓一次
                        mainHandler.postDelayed({ tryInjectTextIntoEditable(attempt + 1) }, 180L)
                        return
                    }
                    editableNode?.let { validNode ->
                        com.example.newandroidapp.action.GestureExecutor.autoClearAndSetText(
                            node = validNode,
                            newText = textValue,
                            context = this@VisionAccessibilityService
                        )
                    }
                    verifyAndSubmitSearchAfterTextInjection(textValue, sw) {
                        if (isHudAttached) runRealTimeVisionStreamTick()
                        onDone?.invoke()
                    }
                }
                mainHandler.postDelayed({ tryInjectTextIntoEditable(1) }, 160L)
            }
        }

        // 若底層尚未展開 EditText 且頂部被通知橫幅遮擋 -> 執行【策略 A：先主動上滑消除通知橫幅】再點擊展開並寫入
        if (defenseEval.strategy == HeadsUpNotificationDefenseGuard.DefenseStrategy.STRATEGY_A_SWIPE_UP_DISMISS &&
            defenseEval.swipeTrajectory != null
        ) {
            hudOutputText?.text = defenseEval.diagnosticNote
            performHeadsUpDismissSwipeUp(defenseEval.swipeTrajectory) {
                tapAndInjectBlock()
            }
        } else {
            tapAndInjectBlock()
        }
    }

    /**
     * 取得排除 `com.android.systemui` 與外來通知橫幅後的目標 App 根節點
     */
    private fun findTargetAppRootWindowNode(): AccessibilityNodeInfo? {
        val expPkg = InAppWorkingMemory.expectedTargetPackage.trim()
        val winList = runCatching { windows }.getOrDefault(emptyList())
        if (expPkg.isNotEmpty() && winList.isNotEmpty()) {
            val matchedWin = winList.firstOrNull { win ->
                win.root?.packageName?.toString() == expPkg
            }
            if (matchedWin?.root != null) return matchedWin.root
        }
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null && activeRoot.packageName?.toString() != "com.android.systemui") {
            return activeRoot
        }
        return winList.firstOrNull { win ->
            val p = win.root?.packageName?.toString().orEmpty()
            p.isNotEmpty() && p != "com.android.systemui" && p != packageName
        }?.root ?: activeRoot
    }

    /**
     * 【策略 B 核心：穿透頂部通知橫幅直接鎖定底層目標 App 的 EditText 節點】
     */
    private fun findTargetAppEditableNodeBypassingBanners(x: Int, y: Int, sw: Int, sh: Int): AccessibilityNodeInfo? {
        val expPkg = InAppWorkingMemory.expectedTargetPackage.trim()
        val rootCandidates = ArrayList<AccessibilityNodeInfo>(3)
        findTargetAppRootWindowNode()?.let { rootCandidates.add(it) }
        runCatching { rootInActiveWindow }.getOrNull()?.let { r ->
            if (r !in rootCandidates && r.packageName?.toString() != "com.android.systemui") {
                rootCandidates.add(r)
            }
        }

        for (root in rootCandidates) {
            val found = findEditableNodeAtOrFocused(root, x, y, expPkg, sw, sh)
            if (found != null) return found
        }
        return null
    }

    /**
     * 【優化 #4 & #5：搜尋送出與可編輯節點定位統一委派至 AccessibilitySearchSubmitter】
     */
    private fun isAccessibilityNodeVoiceOrLensButton(node: AccessibilityNodeInfo): Boolean {
        return AccessibilitySearchSubmitter.isAccessibilityNodeVoiceOrLensButton(node)
    }

    private fun verifyAndSubmitSearchAfterTextInjection(
        expectedText: String,
        sw: Int,
        onSubmitted: () -> Unit
    ) {
        AccessibilitySearchSubmitter.verifyAndSubmitSearchAdaptively(
            handler = mainHandler,
            expectedText = expectedText,
            sw = sw,
            rootProvider = { findTargetAppRootWindowNode() ?: runCatching { rootInActiveWindow }.getOrNull() },
            windowsProvider = { runCatching { windows }.getOrDefault(emptyList()) },
            tapFallbackExecutor = { x, y, reason, done -> performRealTapAt(x, y, reason, done) },
            onSubmitted = onSubmitted
        )
    }

    private fun isAccessibilityTreeAlreadyShowingSearchResults(
        root: AccessibilityNodeInfo,
        expectedQuery: String
    ): Boolean {
        return AccessibilitySearchSubmitter.isAccessibilityTreeAlreadyShowingSearchResults(root, expectedQuery)
    }

    private fun findEditableNodeAtOrFocused(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        expectedTargetPackage: String = "",
        sw: Int = 1080,
        sh: Int = 2340
    ): AccessibilityNodeInfo? {
        return AccessibilitySearchSubmitter.findEditableNodeAtOrFocused(root, x, y, expectedTargetPackage, sw, sh)
    }

    fun performRealScroll(
        down: Boolean,
        customReport: VisionPipelineReport? = null,
        intentCategory: String? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onDone?.invoke()
            return
        }
        SmartDailyAssistantHelper.markAiActionWindow(750L)
        val (sw, sh) = getLiveScreenSizePx()
        val report = customReport ?: latestLiveReport

        val rawNodes = report?.stage1AllNodes?.map { it.raw } ?: emptyList()
        val convergedNodes = report?.finalNodes ?: emptyList()

        val params = ScrollDensityAnalyzer.calculateScrollParameters(
            rawNodes = rawNodes,
            convergedNodes = convergedNodes,
            down = down,
            screenWidth = sw,
            screenHeight = sh,
            intentCategory = intentCategory
        )

        hudOutputText?.text = "執行${params.mode.description} (幅度: ${(params.strideFraction * 100).toInt()}%)"

        val cb = params.containerBounds
        val cx = if (cb != null) (((cb.left + cb.right) / 2f)).coerceIn(sw * 0.2f, sw * 0.8f) else (sw * 0.5f)
        val rawStartY = sh * params.startYFraction
        val rawEndY = sh * params.endYFraction

        val startPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, rawStartY, sw, sh).first
        val endPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, cx, rawEndY, sw, sh).first

        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            val path = Path().apply {
                moveTo(startPt.x.toFloat(), startPt.y.toFloat())
                lineTo(endPt.x.toFloat(), endPt.y.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, params.durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.postDelayed({
                            if (isHudAttached) {
                                floatingRoot?.visibility = View.VISIBLE
                                if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                                runRealTimeVisionStreamTick()
                            }
                            onDone?.invoke()
                        }, 40L)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        visionExecutor.execute {
                            com.example.newandroidapp.stability.ActionExecutionRouter.routeSwipe(
                                x1 = startPt.x,
                                y1 = startPt.y,
                                x2 = endPt.x,
                                y2 = endPt.y,
                                durationMs = params.durationMs,
                                service = this@VisionAccessibilityService,
                                context = this@VisionAccessibilityService,
                                accessibilitySucceededHint = false
                            )
                        }
                        mainHandler.post {
                            if (isHudAttached) {
                                floatingRoot?.visibility = View.VISIBLE
                                if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                            }
                            onDone?.invoke()
                        }
                    }
                },
                null
            )
        }, 20L)
    }

    fun performRealLongPressAt(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onDone?.invoke()
            return
        }
        SmartDailyAssistantHelper.markAiActionWindow(750L)
        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            com.example.newandroidapp.action.GestureExecutor.executeLongPress(this, x, y, 600L) {
                mainHandler.postDelayed({
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                        runRealTimeVisionStreamTick()
                    }
                    onDone?.invoke()
                }, 30L)
            }
        }, 20L)
    }

    fun performRealDoubleTapAt(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onDone?.invoke()
            return
        }
        SmartDailyAssistantHelper.markAiActionWindow(600L)
        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            com.example.newandroidapp.action.GestureExecutor.executeDoubleTap(this, x, y, 100L) {
                mainHandler.postDelayed({
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                        runRealTimeVisionStreamTick()
                    }
                    onDone?.invoke()
                }, 30L)
            }
        }, 20L)
    }

    fun performRealHorizontalSwipe(isLeft: Boolean, targetY: Float? = null, onDone: (() -> Unit)? = null) {
        SmartDailyAssistantHelper.markAiActionWindow(750L)
        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            val callback: () -> Unit = {
                mainHandler.postDelayed({
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                        runRealTimeVisionStreamTick()
                    }
                    onDone?.invoke()
                }, 30L)
            }
            if (isLeft) {
                com.example.newandroidapp.action.GestureExecutor.executeSwipeLeft(this, targetY, callback)
            } else {
                com.example.newandroidapp.action.GestureExecutor.executeSwipeRight(this, targetY, callback)
            }
        }, 20L)
    }

    /**
     * 從指定元件（如底部抽屜把手 `drag_handle`）中心點起算執行垂直拖曳：
     * 起點為 `(x, y)`，終點為 `y ± 螢幕高度 35%`，持續時間約 280ms。
     */
    fun performRealVerticalDragFrom(x: Int, y: Int, up: Boolean, onDone: (() -> Unit)? = null) {
        if (!InAppWorkingMemory.hasMotorPermission()) {
            onDone?.invoke()
            return
        }
        SmartDailyAssistantHelper.markAiActionWindow(850L)
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(1)
        val sh = dm.heightPixels.coerceAtLeast(1)
        val deltaY = (sh * 0.35f).toInt().coerceAtLeast(180)
        val rawEndY = if (up) (y - deltaY) else (y + deltaY)
        val startPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, x.toFloat(), y.toFloat(), sw, sh).first
        val endPt = SystemBarBlockerGuard.clampPointToSafeAppZone(this, x.toFloat(), rawEndY.toFloat(), sw, sh).first
        val durationMs = 280L

        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            val path = Path().apply {
                moveTo(startPt.x.toFloat(), startPt.y.toFloat())
                lineTo(endPt.x.toFloat(), endPt.y.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.postDelayed({
                            if (isHudAttached) {
                                floatingRoot?.visibility = View.VISIBLE
                                if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                                runRealTimeVisionStreamTick()
                            }
                            onDone?.invoke()
                        }, 40L)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        visionExecutor.execute {
                            com.example.newandroidapp.stability.ActionExecutionRouter.routeSwipe(
                                x1 = startPt.x,
                                y1 = startPt.y,
                                x2 = endPt.x,
                                y2 = endPt.y,
                                durationMs = durationMs,
                                service = this@VisionAccessibilityService,
                                context = this@VisionAccessibilityService,
                                accessibilitySucceededHint = false
                            )
                        }
                        mainHandler.post {
                            if (isHudAttached) {
                                floatingRoot?.visibility = View.VISIBLE
                                if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                            }
                            onDone?.invoke()
                        }
                    }
                },
                null
            )
        }, 20L)
    }

    fun performRealSliderSet(bounds: Rect, percentage: Float, isVertical: Boolean = false, onDone: (() -> Unit)? = null) {
        SmartDailyAssistantHelper.markAiActionWindow(750L)
        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            com.example.newandroidapp.action.GestureExecutor.executeSliderSet(this, bounds, percentage, isVertical) {
                mainHandler.postDelayed({
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                        runRealTimeVisionStreamTick()
                    }
                    onDone?.invoke()
                }, 30L)
            }
        }, 20L)
    }

    fun performRealPickerNudge(bounds: Rect, increment: Boolean, onDone: (() -> Unit)? = null) {
        SmartDailyAssistantHelper.markAiActionWindow(600L)
        mainHandler.post {
            floatingRoot?.visibility = View.INVISIBLE
            arOverlayView?.visibility = View.INVISIBLE
            SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(false)
        }
        mainHandler.postDelayed({
            com.example.newandroidapp.action.GestureExecutor.executePickerNudge(this, bounds, increment) {
                mainHandler.postDelayed({
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                        SystemBarBlockerGuard.setPhysicalShieldTemporaryVisibility(true)
                        runRealTimeVisionStreamTick()
                    }
                    onDone?.invoke()
                }, 30L)
            }
        }, 20L)
    }

    private fun clearAllAttachedViews(wm: WindowManager) {
        SystemBarBlockerGuard.removePhysicalShieldOverlays(wm)
        val toRemove = LinkedHashSet<View>()
        floatingRoot?.let { toRemove.add(it) }
        arOverlayView?.let { toRemove.add(it) }
        toRemove.addAll(attachedOverlayViews)
        attachedOverlayViews.clear()

        for (v in toRemove) {
            v.visibility = View.GONE
            val removedImmediate = runCatching {
                wm.removeViewImmediate(v)
                true
            }.getOrDefault(false)
            if (!removedImmediate) {
                runCatching { wm.removeView(v) }
            }
        }
        floatingRoot = null
        floatingRootLp = null
        floatingOrbView = null
        arOverlayView = null
        hudExpandedPanel = null
        hudLiveAppBadgeTv = null
        hudCommandInputEt = null
        hudCurrentAppChipsRow = null
        hudOutputText = null
        autoRunStatusBtn = null
    }

    /**
     * 關閉懸浮球：只停止「使用者任務」並移除視圖；
     * 5 分鐘壓力測試不受影響（測試有獨立的停止按鈕，且由 stopBenchmarkSuite 負責結束）。
     */
    fun removeFloatingHud() {
        isAutoLoopRunning = false
        isHudAttached = false
        capsuleObserver?.let {
            com.example.newandroidapp.chat.ConversationManager.removeObserver(it)
        }
        capsuleObserver = null
        capsuleChatContainer = null
        // 只移除與懸浮窗相關的排程，絕不能清掉壓力測試的計時與回合排程
        mainHandler.removeCallbacks(realTimeDebounceRunnable)
        mainHandler.removeCallbacks(realTimeHeartbeatRunnable)
        val cleanupAction = Runnable {
            val wm = windowManager
            if (wm != null) {
                clearAllAttachedViews(wm)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanupAction.run()
        } else {
            mainHandler.post(cleanupAction)
        }
    }

    private fun resolveCurrentTopApplicationPackage(): String? {
        val activeRootPkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val winList = runCatching { windows }.getOrDefault(emptyList())
        val appWinPkg = winList
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName != null }
            ?.root?.packageName?.toString()
        if (!appWinPkg.isNullOrBlank()) {
            if (appWinPkg != packageName) {
                latestForegroundPackage = appWinPkg
                latestForegroundAppLabel = InstalledAppLauncher.getAppLabelByPackage(this, appWinPkg)
            }
            return appWinPkg
        }
        if (!activeRootPkg.isNullOrBlank()) {
            if (activeRootPkg != packageName) {
                latestForegroundPackage = activeRootPkg
                latestForegroundAppLabel = InstalledAppLauncher.getAppLabelByPackage(this, activeRootPkg)
            }
            return activeRootPkg
        }
        return latestForegroundPackage
    }

    fun captureLiveScreenAndRunPipeline(
        forcePixelVisionCapture: Boolean = false,
        onComplete: (VisionPipelineReport) -> Unit
    ) {
        val (sw, sh) = getLiveScreenSizePx()

        val winList = runCatching { windows }.getOrDefault(emptyList())
        val rootFallback = runCatching { rootInActiveWindow }.getOrNull()
        val appWinPkg = winList
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName != null }
            ?.root?.packageName?.toString()
        val activePkg = appWinPkg ?: rootFallback?.packageName?.toString()
        if (!activePkg.isNullOrBlank() && activePkg != packageName) {
            latestForegroundPackage = activePkg
            latestForegroundAppLabel = InstalledAppLauncher.getAppLabelByPackage(this, activePkg)
        }
        val isSelfMainActivityInForeground = (activePkg == packageName)

        // 【極速化升級 4：Fast-Tree 零截圖快車道 + 智慧按需視覺補強 (Smart Visual Fallback)】
        // 先在背景執行緒以 5~10ms 收集無障礙元件樹；若畫面已具備 >= 3 個有意義的文字/標籤互動節點且非強制視覺補盲，
        // 直接免截圖完成管線收斂並回傳，省去 90ms 隱藏等待 + 200ms 截圖與 OCR 耗時！
        // 當三合一主腦在無障礙樹找不到匹配目標時，以 forcePixelVisionCapture = true 觸發一次硬體截圖執行 NanoDet + OCR！
        visionExecutor.execute {
            try {
                var fastNodes = ZOrderTreeCollector.collectFromAccessibilityWindows(
                    windows = winList,
                    rootFallback = rootFallback,
                    screenWidth = sw,
                    screenHeight = sh,
                    ignorePackageName = packageName
                )
                if (fastNodes.isEmpty() && !isSelfMainActivityInForeground) {
                    val fallbackNodes = com.example.newandroidapp.stability.ActionExecutionRouter.routeBlockedTreePerception(
                        isFlagSecure = false,
                        isTreeEmptyOrBlocked = true,
                        screenWidth = sw,
                        screenHeight = sh,
                        context = this@VisionAccessibilityService
                    )
                    if (fallbackNodes.isNotEmpty()) {
                        fastNodes = fallbackNodes
                    }
                }
                val labeledInteractiveCount = fastNodes.count {
                    (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.hintText.isNotBlank()) &&
                        (it.isClickable || it.isEditable || it.isScrollable)
                }
                val mainViewportTopY = (sh * 0.05f).toInt()
                val mainViewportBottomY = (sh * 0.78f).toInt()
                val mainViewportInteractiveCount = fastNodes.count {
                    val cy = (it.bounds.top + it.bounds.bottom) / 2
                    cy in mainViewportTopY..mainViewportBottomY &&
                        (it.isClickable || it.isEditable || it.isCheckable || it.isLongClickable) &&
                        (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.hintText.isNotBlank())
                }
                val hasEditableInput = fastNodes.any { it.isEditable }
                val minMainViewportNodes = Phase1TreeImmunityConfig.minMainViewportInteractiveNodesForFastTree
                val fastTreeHasSufficientCoverage = !forcePixelVisionCapture &&
                    labeledInteractiveCount >= 3 &&
                    (mainViewportInteractiveCount >= minMainViewportNodes || hasEditableInput)

                val now = SystemClock.elapsedRealtime()
                val minScreenshotCooldownMs = if (forcePixelVisionCapture) 450L else 1200L
                val canTakeNewScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (now - lastScreenshotElapsedMs >= minScreenshotCooldownMs)

                if (fastTreeHasSufficientCoverage || !canTakeNewScreenshot || isSelfMainActivityInForeground) {
                    val report = AiVisionPipeline.execute(
                        rawNodes = fastNodes,
                        screenWidth = sw,
                        screenHeight = sh,
                        screenBitmap = null,
                        useNormalizedGrid = true
                    )
                    previousSampledReport = latestLiveReport
                    latestLiveReport = report
                    lastRealTimePassElapsedMs = SystemClock.elapsedRealtime()
                    val frameNo = ++liveFrameSeq
                    mainHandler.post {
                        if (isHudAttached) {
                            updateLiveHudHeaderAndChips(report, frameNo)
                        }
                        onComplete(report)
                    }
                    return@execute
                }

                // 僅當無障礙節點樹不足（例如純自繪 Canvas / 遊戲畫面）時，才啟動硬體截圖 + OCR 補盲
                mainHandler.post {
                    floatingRoot?.visibility = View.INVISIBLE
                    arOverlayView?.visibility = View.INVISIBLE
                    mainHandler.postDelayed({
                        val freshWinList = runCatching { windows }.getOrDefault(emptyList())
                        val freshRoot = runCatching { rootInActiveWindow }.getOrNull()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            lastScreenshotElapsedMs = SystemClock.elapsedRealtime()
                            takeScreenshot(
                                Display.DEFAULT_DISPLAY,
                                visionExecutor,
                                object : TakeScreenshotCallback {
                                    override fun onSuccess(screenshot: ScreenshotResult) {
                                        try {
                                            val rawNodes = ZOrderTreeCollector.collectFromAccessibilityWindows(
                                                windows = freshWinList,
                                                rootFallback = freshRoot,
                                                screenWidth = sw,
                                                screenHeight = sh,
                                                ignorePackageName = packageName
                                            )
                                            // 【全鏈路零拷貝 (Zero-Copy) 視覺加速】：
                                            // 直接將 HardwareBuffer 送入 C++ NDK (AHardwareBuffer_lock) 進行零拷貝色域轉換、dHash64 計算、FLAG_SECURE 黑屏偵測與 INT8 推論
                                            val zeroCopyRes = runCatching {
                                                NanoDetUiDetector.detectZeroCopyOnHardwareBuffer(
                                                    hardwareBuffer = screenshot.hardwareBuffer,
                                                    screenWidth = sw,
                                                    screenHeight = sh
                                                )
                                            }.getOrNull()
                                            if (zeroCopyRes?.first?.isFlagSecureBlack == true) {
                                                com.example.newandroidapp.stability.ActionExecutionRouter.onAnomalyDetectedSwitchToFallback(
                                                    reason = com.example.newandroidapp.stability.FallbackTriggerReason.FLAG_SECURE_PROTECTED,
                                                    context = this@VisionAccessibilityService
                                                )
                                            }
                                            val softBmp = runCatching {
                                                val hwBmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                                val copied = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                                hwBmp?.recycle()
                                                screenshot.hardwareBuffer.close()
                                                copied
                                            }.getOrNull()

                                            CrashGuardManager.registerNewScreenshotAndRecycleOld(softBmp)

                                            val report = AiVisionPipeline.execute(
                                                rawNodes = rawNodes,
                                                screenWidth = sw,
                                                screenHeight = sh,
                                                screenBitmap = softBmp,
                                                useNormalizedGrid = true
                                            )
                                            latestLiveBitmap = softBmp
                                            previousSampledReport = latestLiveReport
                                            latestLiveReport = report
                                            lastRealTimePassElapsedMs = SystemClock.elapsedRealtime()
                                            val frameNo = ++liveFrameSeq
                                            mainHandler.post {
                                                if (isHudAttached) {
                                                    floatingRoot?.visibility = View.VISIBLE
                                                    if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                                    updateLiveHudHeaderAndChips(report, frameNo)
                                                }
                                                onComplete(report)
                                            }
                                        } catch (t: Throwable) {
                                            CrashGuardManager.recordThrowable(applicationContext, "ScreenshotPipelineOnSuccess", t)
                                            val fallbackReport = latestLiveReport ?: AiVisionPipeline.execute(
                                                rawNodes = emptyList(),
                                                screenWidth = sw,
                                                screenHeight = sh,
                                                screenBitmap = null,
                                                useNormalizedGrid = true
                                            )
                                            mainHandler.post {
                                                if (isHudAttached) {
                                                    floatingRoot?.visibility = View.VISIBLE
                                                    if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                                }
                                                onComplete(fallbackReport)
                                            }
                                        }
                                    }

                                    override fun onFailure(errorCode: Int) {
                                        // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW == 3：偵測到 FLAG_SECURE 防截圖保護時，自動降級切換至 Shizuku / Root 備援通道
                                        if (errorCode == 3) {
                                            com.example.newandroidapp.stability.ActionExecutionRouter.onAnomalyDetectedSwitchToFallback(
                                                reason = com.example.newandroidapp.stability.FallbackTriggerReason.FLAG_SECURE_PROTECTED,
                                                context = this@VisionAccessibilityService
                                            )
                                        }
                                        val effectiveFallbackNodes = if (fastNodes.isEmpty() && !isSelfMainActivityInForeground) {
                                            com.example.newandroidapp.stability.ActionExecutionRouter.routeBlockedTreePerception(
                                                isFlagSecure = errorCode == 3,
                                                isTreeEmptyOrBlocked = true,
                                                screenWidth = sw,
                                                screenHeight = sh,
                                                context = this@VisionAccessibilityService
                                            )
                                        } else {
                                            fastNodes
                                        }
                                        val report = runCatching {
                                            AiVisionPipeline.execute(
                                                rawNodes = effectiveFallbackNodes,
                                                screenWidth = sw,
                                                screenHeight = sh,
                                                screenBitmap = null,
                                                useNormalizedGrid = true
                                            )
                                        }.getOrElse {
                                            latestLiveReport ?: AiVisionPipeline.execute(
                                                rawNodes = emptyList(),
                                                screenWidth = sw,
                                                screenHeight = sh,
                                                screenBitmap = null,
                                                useNormalizedGrid = true
                                            )
                                        }
                                        previousSampledReport = latestLiveReport
                                        latestLiveReport = report
                                        lastRealTimePassElapsedMs = SystemClock.elapsedRealtime()
                                        val frameNo = ++liveFrameSeq
                                        mainHandler.post {
                                            if (isHudAttached) {
                                                floatingRoot?.visibility = View.VISIBLE
                                                if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                                                updateLiveHudHeaderAndChips(report, frameNo)
                                            }
                                            onComplete(report)
                                        }
                                    }
                                }
                            )
                        }
                    }, 25L)
                }
            } catch (t: Throwable) {
                CrashGuardManager.recordThrowable(applicationContext, "CaptureLiveScreenAndRunPipeline", t)
                val fallbackReport = latestLiveReport ?: AiVisionPipeline.execute(
                    rawNodes = emptyList(),
                    screenWidth = sw,
                    screenHeight = sh,
                    screenBitmap = null,
                    useNormalizedGrid = true
                )
                mainHandler.post {
                    if (isHudAttached) {
                        floatingRoot?.visibility = View.VISIBLE
                        if (isArOverlayShown) arOverlayView?.visibility = View.VISIBLE
                    }
                    onComplete(fallbackReport)
                }
            }
        }
    }
}
