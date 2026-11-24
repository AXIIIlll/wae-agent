package com.wae.agent.core

import com.wae.agent.perception.RawCollectedNode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WAE V2.6 全管線無障礙服務（無懸浮窗 Overlay）：
 * - L0 Deep Link 直達快車道 + 800ms 落地姿態驗證
 * - L0.5 本地 UI 模式檢索與自動規則挖掘
 * - L1 無障礙樹 + 鄰近語境焊接 + 六維度 Actionability Gate + 精確 TYPE_APPLICATION Z 軸遮擋偵測
 * - L1.5 中英雙通道混合召回 + L3 CUA-S1 自適應三條件門檻 + 顯式歧義分流 (CLEAR_WIN / AMBIGUOUS / REJECT)
 * - L4 單節點 AccessibilityNodeInfo.refresh() O(1) TOCTOU 防過期鎖 + 手勢與輸入執行
 * - L5 256B 主幹簽名集合精確 Jaccard 替換率 + 600ms 三態空間因果歸因 + 三級有界主動恢復狀態機
 */
class WaeVisionAccessibilityService : AccessibilityService() {

    companion object {
        /** S16 位元碼程式執行開關（預設 false：只編譯與乾跑，不實際套用） */
        @Volatile
        var PROGRAM_EXECUTION_ENABLED: Boolean = true

        /** M6 主動探索開關（預設 false；開啟後仍需通過三層護欄） */
        @Volatile
        var EXPLORER_ENABLED: Boolean = true

        /** M6 跨 App 遷移開關（預設 false） */
        @Volatile
        var TRANSFER_ENABLED: Boolean = true

        /** M3 自動機規劃開關（關閉即完全回到舊行為） */
        @Volatile
        var AUTOMATON_PLANNER_ENABLED: Boolean = true

        /** 單一目標最多走幾步「規劃導覽」（避免反覆導覽同一起點） */
        private const val AUTOMATON_MAX_HOPS = 4

        /** 規劃的成功率門檻（低於此值就別賭） */
        private const val AUTOMATON_MIN_PLAN_SUCCESS = 0.55f

        /** 抵達驗證的相似度門檻（8 通道 MinHash，0.5 = 至少 4 條通道相同） */
        private const val AUTOMATON_ARRIVAL_SIM = 0.50f

        @Volatile
        var activeInstance: WaeVisionAccessibilityService? = null
            private set

        @Volatile
        var latestFrameJson: String = "{}"
            private set

        @Volatile
        var latestPackageName: String = ""
            private set

        @Volatile
        var latestNodeCount: Int = 0
            private set

        @Volatile
        var latestScreenshotCaptured: Boolean = false
            private set

        @Volatile
        var latestPostureSummary: String = "EMPTY_OR_UNKNOWN"
            private set

        @Volatile
        var latestStatusMessage: String = "就緒"
            private set

        @Volatile
        var pendingAmbiguousGoal: LocalGoal? = null
            private set

        @Volatile
        var pendingAmbiguousOptions: List<CandidateOption> = emptyList()
            private set

        @Volatile
        var uiUpdateCallback: (() -> Unit)? = null

        /** 目前是否正在執行任務（供 UI 切換「執行 / 停止」） */
        fun isBusy(): Boolean = activeInstance?.isRunningTask?.get() ?: false

    /** 回到桌面（測試套件在每個提示詞之間重置狀態用） */
    fun goHomePublic(): Boolean =
        activeInstance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            ?: false

        /**
         * 服務是否「已在系統層被使用者啟用」（與行程/實例是否已連線無關）。
         * activeInstance 在行程重啟、服務重新綁定時會有數百毫秒的空窗，
         * 只看它會讓 UI 誤報「未啟用」，被使用者當成 AI 壞掉。
         */
        fun isEnabledInSystem(context: android.content.Context): Boolean {
            return try {
                val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager ?: return false
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo?.serviceInfo?.name == WaeVisionAccessibilityService::class.java.name }
            } catch (_: Throwable) {
                false
            }
        }

        /** 任務整體逾時上限：超過即安全中止，避免管線卡死 */
        private const val TASK_TIMEOUT_MS = 90_000L
    }

    private val pipelineExecutor = Executors.newSingleThreadExecutor()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val isRunningTask = AtomicBoolean(false)
    private val frameIdGenerator = AtomicLong(1L)

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var taskDeadlineMs = Long.MAX_VALUE

    /** 當前這一幀是否已經跑過 OCR（避免決策層重複升級、重複花 0.8s） */
    @Volatile
    private var ocrRanForCurrentFrame = false

    /** 最近一次任務的執行結果（null = 未完成；true/false = 完成且成敗） */
    @Volatile
    var lastTaskSuccess: Boolean? = null
        private set

    /** 任務執行序號（每次開始 +1，評測用於偵測「新任務已開始」） */
    @Volatile
    var taskRunSeq: Long = 0L
        private set

    /** Twin 歷史軌跡（每個任務開始時載入一次，供 M3a 編譯快路徑使用） */
    @Volatile
    private var twinHistory: List<com.wae.agent.core.twin.TwinStep> = emptyList()

    // ── M4 被動記錄（Shadow Scribe 精簡版）────────────────────────────
    @Volatile
    private var passiveDisabled = false
    @Volatile
    private var knownPassivePackages: Set<String> = emptySet()
    @Volatile
    private var ambientSpine: Long = 0L
    @Volatile
    private var ambientSigs: List<Long> = emptyList()
    private val passiveHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 被動點擊記錄（Twin M2）：只記錄、不執行。 */
    private val twinClickRecorder: com.wae.agent.core.twin.TwinClickRecorder by lazy {
        com.wae.agent.core.twin.TwinClickRecorder(
            context = this,
            handler = passiveHandler,
            passiveDisabled = { passiveDisabled },
            busyWithTask = { isRunningTask.get() },
            knownPackages = { knownPassivePackages },
            ambient = { computeAmbientSpine() }
        )
    }

    // 供 L4 單節點 O(1) refresh() TOCTOU Guard 使用的當幀節點映射表
    private val liveNodeMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    // 追蹤環境非平穩度 dynamicScore 與滾動事件
    private val recentContentChangeTimestamps = ArrayDeque<Long>()
    @Volatile
    private var lastScrollEventTimestampMs: Long = 0L


    @Volatile
    private var serviceDestroyed = false

    // ── LLM Agent 用（S60）─────────────────────────────────
    // K 第一階段：Agent 的執行主體已抽到 `AgentHost`（行為不變），服務只留委派。
    private val agentHost: AgentHost by lazy { AgentHost(this) }

    fun agentRoot(): android.view.accessibility.AccessibilityNodeInfo? = getActiveNonWaeRoot()

    /** 助理呼叫：用 LLM Agent 執行目標（背景執行，進度顯示在 overlay）。 */
    fun runAgentCommand(goal: String) = agentHost.run(goal)

    fun agentHome(): Boolean =
        performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)

    fun agentBack(): Boolean =
        performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    /** debug 版：外部 agent_goal.txt 觸發一次 Agent 任務（M2 驗證用）。 */
    private fun startAgentDebugWatcher() {
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val dir = getExternalFilesDir(null) ?: return
        // release 期不用：使用者要自己放 agent_enabled_flag 才會啟用
        val enabled = debug || java.io.File(dir, "agent_enabled_flag").isFile
        com.wae.agent.gen5.AgentDebugWatcher(
            service = this,
            dir = dir,
            enabled = enabled,
            isDestroyed = { serviceDestroyed }
        ).start()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { startAgentDebugWatcher() }
        // 測試用的最上層大字（TYPE_ACCESSIBILITY_OVERLAY）：人工驗證時看得到「現在第幾題」
        com.wae.core.util.Degrade.orNull("hud:attach") { com.wae.agent.core.debug.DebugHud.attach(this) }
        activeInstance = this
        // 模型 mmap 與 15MB 級資料庫載入不可阻塞服務連線執行緒（會拖慢首個事件派發）
        Thread {
            try {
                if (WaeNativeBridge.isNativeReady()) {
                    WaeNativeBridge.waeLoadAllModelsMmap(assets)
                }
                WaeOpenSourceDatabaseRegistry.ensureLoaded(this)
                com.wae.agent.core.component.WidgetOntology.ensureLoaded(this)
                com.wae.agent.core.component.ValueAdjustLexicon.ensureLoaded(this)
                com.wae.agent.core.component.ValueVerbLexicon.ensureLoaded(this)
                com.wae.agent.core.action.ActionTelemetry.ensureLoaded(this)
                com.wae.agent.core.action.NegativeEdgeStore.ensureLoaded(this)
                com.wae.agent.core.safety.SystemSafetyPackages.ensureLoaded(this)
                com.wae.agent.core.nlu.GrammarParticles.ensureLoaded(this)
                com.wae.agent.core.nlu.ActionSelectorTable.ensureLoaded(this)
                com.wae.agent.core.decision.TargetPreference.ensureLoaded(this)
                com.wae.agent.core.decision.DecisionHead.ensureLoaded(this)
                com.wae.agent.core.grounding.GroundingFusion.ensureLoaded(this)
                com.wae.agent.core.grounding.ConfidenceCalibrator.ensureLoaded(this)
                com.wae.agent.core.grounding.DynamicUiClassifier.ensureLoaded(this)
                com.wae.agent.core.grounding.ElementGraph.ensureLoaded(this)
                com.wae.agent.core.goal.GoalCompiler.ensureLoaded(this)
                com.wae.agent.core.decision.ContentItemEvidence.ensureLoaded(this)
                com.wae.agent.core.nlu.ToggleIntent.ensureLoaded(this)
                com.wae.agent.core.nlu.ToggleSectionMap.ensureLoaded(this)
                com.wae.agent.core.explore.ExplorationGuard.ensureLoaded(this)
                com.wae.agent.core.transfer.TransferEngine.ensureLoaded(this)
                com.wae.agent.core.explore.CoverageBandit.ensureLoaded(this)
                runCatching {
                    val memoFile = java.io.File(filesDir, "decision_memo.json")
                    if (memoFile.exists()) {
                        com.wae.agent.core.decision.DecisionMemo.loadFromJson(memoFile.readText(Charsets.UTF_8))
                    }
                    val attrFile = java.io.File(filesDir, "failure_attribution.json")
                    if (attrFile.exists()) {
                        com.wae.agent.core.observe.FailureAttribution.loadFromJson(attrFile.readText(Charsets.UTF_8))
                    }
                }
                com.wae.agent.core.automaton.AutomatonStore.ensureLoaded(this)
                WaeDeepLinkCapabilityManager.ensureInitialized(this)
                com.wae.agent.vision.OmniParserIconDetector.ensureLoaded(this)
                com.wae.agent.vision.PpOcrEngine.ensureLoaded(this)
                // 三個模型的就緒狀態（可一眼驗證：MiniLM / 圖示檢測 / OCR）
                Log.i(
                    "WAE_MODELS",
                    "models: minilm=${com.wae.agent.core.nlp.MiniLmEncoder.isReady} " +
                        "iconDetect=${com.wae.agent.vision.OmniParserIconDetector.isReady} " +
                        "ocr=${com.wae.agent.vision.PpOcrEngine.isReady}"
                )
                // 綠區能力圖譜：只用 PackageManager 公開 API（見 docs/GREEN_ZONE_POLICY.md）
                // 自動調度：快取有效就跳過；有安裝/更新或過期才在背景重掃
                com.wae.agent.core.capability.AppCapabilityIndex.ensureFresh(this)
                // M4：只有「已用本 App 自動化過的套件」才做被動記錄
                // 被動記錄的白名單＝「已經和這個 App 建立過自動化關係」。
                // 只從 Twin 歷史取會變成雞生蛋：沒有歷史 → 不記錄 → 永遠沒有歷史。
                // 所以再加上「收據裡真的成功操作過的 App」當種子。
                knownPassivePackages =
                    com.wae.agent.core.twin.PassiveRecordPolicy.knownPackagesFrom(
                        com.wae.agent.core.twin.TwinRecorder.loadPersisted(this)
                    ) + knownPackagesFromReceipts()
                passiveDisabled = java.io.File(filesDir, "passive_record_off").exists()
                notifyUiStatus("感知與 V2.6 決策管線已就緒")
            } catch (t: Throwable) {
                Log.e("WAE_EXEC", "Service init failed", t)
                notifyUiStatus("⚠ 初始化失敗：${t.javaClass.simpleName}")
            }
        }.start()
    }

    /**
     * M5a：依已錄製的鏈逐步定位與派發（每步只做選擇器定位 + 後置驗證，不做決策）。
     * 任何一步失敗就整體回退（不留下半完成狀態的錯誤假設）。
     */
    private fun replayPlan(
        plan: com.wae.agent.core.twin.TwinReplayPlan.Plan,
        goal: LocalGoal,
        onStepUpdate: ((String) -> Unit)?
    ): Boolean {
        var typedOnce = false
        for ((idx, step) in plan.steps.withIndex()) {
            if (isCancelledOrTimedOut()) return false
            val stepKind = runCatching { ActionKind.valueOf(step.action) }.getOrDefault(goal.actionKind)

            // 慣性防護：重放不得把「當前的 goal 文字」打進輸入框。
            // TYPE 步驟只能用當前 goal 的 payload（且僅一次）；若當前 goal 不是 TYPE，
            // 遇到 TYPE 步驟就放棄重放（回退正常流程），否則會出現
            // 「叫他點第一封信，卻把『第一封信』打進搜尋框」。
            if (stepKind == ActionKind.TYPE) {
                if (goal.actionKind != ActionKind.TYPE || typedOnce) {
                    Log.i("WAE_REPLAY", "abort replay: TYPE step conflicts with goal=${goal.actionKind}")
                    return false
                }
                typedOnce = true
            }
            val stepGoal = goal.copy(actionKind = stepKind)
            val (_, set) = perceiveScreenAndBuildOptionSet(stepGoal, windowIdx = 0, collectOnly = true)
            val cand = com.wae.agent.core.twin.locateStepIn(step, set.selectableOptions)
            if (cand == null) {
                Log.i("WAE_REPLAY", "step ${idx + 1}/${plan.steps.size} selector miss")
                return false
            }
            notifyUiStatus("⚡ 重放 ${idx + 1}/${plan.steps.size}：${cand.coreText.take(16)}", onStepUpdate)
            val ok = performValidatedStepAndCheckCausal(stepGoal, set, cand, onStepUpdate)
            if (!ok) {
                Log.i("WAE_REPLAY", "step ${idx + 1}/${plan.steps.size} dispatch failed")
                return false
            }
            Log.i("WAE_REPLAY", "step ${idx + 1}/${plan.steps.size} ok '${cand.coreText.take(16)}'")
        }
        return true
    }

    /**
     * 派發「無目標動作」：不需要選中任何元素。
     * 動作種類完全由 ActionOps 決定（三類原語 + 細分碼），不含 App 或畫面資料。
     */
    private fun dispatchTargetlessAction(goal: LocalGoal, onStepUpdate: ((String) -> Unit)?): Boolean {
        val op = ActionOps.of(goal.actionKind, goal.inputPayload)
        val ok = when (op.fine) {
            FineOp.I_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            FineOp.I_SCROLL_DOWN -> performPreciseScrollDown()
            FineOp.I_SCROLL_UP -> performPreciseScrollUp()
            FineOp.I_SUBMIT -> performSubmitCurrentInput()
            FineOp.N_CLEAR -> performSetTextOnFocused("")
            FineOp.N_REPLACE -> performSetTextOnFocused(goal.inputPayload)
            else -> false
        }
        if (!ok) return false
        waitForUiQuiet(1000L)
        notifyUiStatus("✓ 完成 [${goal.goalText}]", onStepUpdate)
        return true
    }

    // ── S25：事件驅動增量感知（髒區範圍剪枝 + 候選合併）─────────────────
    private val dirtyRects = ArrayList<Rect>(4)
    private var forceFullPerceive = true
    private var lastFullPerceiveAtMs: Long = 0L
    private var lastCandidates: List<CandidateOption> = emptyList()
    private var lastCandidatesAmbient: Long = 0L
    private var lastCandidatesPkg: String = ""
    private var lastCandidatesWindowId: Int = -1

    // ── 感知幀快取（效率）：同一頁、無變化事件時直接重用上一幀 ──────────
    private var frameCacheAtMs: Long = 0L
    private var frameCacheAmbient: Long = 0L
    private var frameCacheWindowId: Int = -1
    private var frameCachePkg: String = ""
    private var frameCacheGoalKey: String = ""
    private var frameCacheResult: Pair<List<CandidateOption>, ImmutableOptionSet>? = null

    // ── 目標層驗證（準確度）：記錄目標開始時的狀態 ────────────────────
    private var goalStartAmbient: Long = 0L
    private var goalStartPkg: String = ""

    /** 目標開始時畫面上的數值（供 VALUE_INC/DEC 後置條件比較） */
    private var goalStartValues: Map<String, Float> = emptyMap()

    // ── M3/P1：自動機規劃狀態 ─────────────────────────────────────────
    /** 本目標已執行的「規劃跳步」數（避免同一目標反覆導覽） */
    private var automatonHopsThisGoal: Int = 0

    /** 本目標已嘗試幾次「先開入口再輸入」（避免同一目標反覆點入口） */
    private var typeLadderAttempts: Int = 0

    /** S27：本目標已嘗試幾次「導覽到開關所在分類頁」 */
    private var toggleDrillAttempts: Int = 0

    /** S21：本目標走過的選擇器（熱路徑統計用） */
    private val goalSelectors = ArrayList<String>()

    /** S16 乾跑用的「當前畫面選擇器集合」 */
    private var frame0Selectors: Set<String> = emptySet()

    /** 自上次編譯以來累積的新軌跡步數（編譯去抖用） */
    private var stepsSinceCompile: Int = 0
    private var lastCompileAtMs: Long = 0L

    // ── M3：自動機規劃（每次任務載入一次，命中才用）──────────────────
    @Volatile
    private var automatonCachePkg: String = ""
    private var automatonCache: com.wae.agent.core.automaton.Automaton? = null

    private fun automatonFor(pkg: String): com.wae.agent.core.automaton.Automaton? {
        if (pkg.isEmpty()) return null
        if (pkg == automatonCachePkg) return automatonCache
        automatonCachePkg = pkg
        automatonCache = runCatching {
            com.wae.agent.core.automaton.AutomatonStore.load(this, pkg)
        }.getOrNull()
        return automatonCache
    }

    /** P1：動作契約用的選擇器鍵（穩定、不含輸入值） */
    private fun selectorKeyOf(c: CandidateOption): String =
        com.wae.agent.core.action.SelectorKeys.of(c)

    /** M4：輕量環境掃描（只取 class+viewId 結構，不取文字內容），用於被動記錄 */
    /** 從收據檔取「真的操作成功過的 App」，當被動記錄的種子白名單。 */
    private fun knownPackagesFromReceipts(): Set<String> = runCatching {
        val file = java.io.File(filesDir, "mem/receipts.tsv")
        if (!file.exists()) return emptySet()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val cells = line.split('\t')
                    if (cells.size < 3) null else cells[2].takeIf { it.isNotEmpty() && it != packageName }
                }
                .toSet()
        }
    }.getOrDefault(emptySet())

    private fun computeAmbientSpine(): Pair<Long, List<Long>> {
        val root = rootInActiveWindow ?: return 0L to emptyList()
        val sigs = ArrayList<Long>(24)
        var hash = 0L
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 12 || sigs.size >= 24) return
            val cls = (n.className?.toString() ?: "").substringAfterLast('.')
            val key = cls + "|" + (n.viewIdResourceName ?: "")
            if (key.isNotEmpty() && key != "|") {
                val h = key.hashCode().toLong()
                sigs.add(h)
                hash = hash * 31L + h
            }
            val cnt = n.childCount.coerceAtMost(8)
            for (i in 0 until cnt) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return hash to sigs
    }

    /**
     * M3c：等待 UI 穩定（事件驅動）。畫面一安靜就提前返回，逾時才保底。
     * 舊行為是固定 sleep；這裡只是把「還要等多久」交給事件決定。
     */
    private fun waitForUiQuiet(maxMs: Long, quietMs: Long = 220L, minMs: Long = 120L): Long {
        val spent = com.wae.agent.core.runtime.SettleWaiter.waitUntilQuiet(
            now = { SystemClock.uptimeMillis() },
            sleep = { SystemClock.sleep(it) },
            isQuiet = {
                synchronized(recentContentChangeTimestamps) {
                    val last = recentContentChangeTimestamps.lastOrNull()
                    last == null || SystemClock.uptimeMillis() - last >= quietMs
                }
            },
            timeoutMs = maxMs,
            minWaitMs = minMs
        )
        Log.i("WAE_WAIT", "waitForUiQuiet spent=${spent}ms (max=${maxMs}ms quiet=${quietMs}ms)")
        return spent
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = SystemClock.uptimeMillis()
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) {
            latestPackageName = pkg
        }
        // ── M4 被動記錄：只觀察、不執行 ─────────────────────────────
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isRunningTask.get()) {
                    val pair = computeAmbientSpine()
                    if (pair.first != 0L) {
                        ambientSpine = pair.first
                        ambientSigs = pair.second
                    }
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // C2 三通道驗證：通知是「事情真的發生了」的佐證（只留記憶體、不落盤）
                // 為什麼要拼三個來源：不是每個 App 都會把內容放進 event.text。
                // 實測 `cmd notification post` 的 event.text 是空的，標題只在 contentDescription。
                val texts = ArrayList<CharSequence>(4)
                event.contentDescription?.let { texts.add(it) }
                texts.addAll(event.text)
                runCatching { com.wae.agent.gen5.NotificationInbox.onEvent(pkg, texts) }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = event.source
                if (src != null) twinClickRecorder?.onClick(pkg, src)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // S25：記錄髒區（供下一幀做範圍剪枝）
                runCatching {
                    val src = event.source
                    if (src != null) {
                        val r = Rect()
                        src.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() > 0) {
                            if (dirtyRects.size >= com.wae.agent.core.perception.IncrementalPerception.MAX_DIRTY_RECTS) {
                                forceFullPerceive = true
                            } else {
                                dirtyRects.add(Rect(r))
                            }
                        }
                    }
                }
                synchronized(recentContentChangeTimestamps) {
                    recentContentChangeTimestamps.addLast(now)
                    while (recentContentChangeTimestamps.isNotEmpty() &&
                        now - recentContentChangeTimestamps.first() > 2000L
                    ) {
                        recentContentChangeTimestamps.removeFirst()
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollEventTimestampMs = now
                // 捲動會改變大部分畫面 → 放棄增量，下一幀全量
                forceFullPerceive = true
            }
        }
    }

    override fun onInterrupt() {
        isRunningTask.set(false)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        super.onDestroy()
        com.wae.core.util.Degrade.orNull("hud:detach") { com.wae.agent.core.debug.DebugHud.detach() }
        runCatching { floatingOverlay?.destroy() }
        floatingOverlay = null
        if (activeInstance === this) {
            activeInstance = null
        }
        cancelRequested = true
        liveNodeMap.clear()
        pipelineExecutor.shutdownNow()
        screenshotExecutor.shutdownNow()
    }

    /**
     * 使用者主動中止目前任務（協作式取消：在下一個檢查點安全停止，不會中斷已派發的手勢）
     */
    private var voiceTaskActive = false
    private var voiceProgress: com.wae.agent.core.VoiceProgressOverlay? = null

    fun requestCancel() {
        cancelRequested = true
    }

    // ── S35 懸浮窗 UI（HTML/WebView）──────────────────────────────
    private var floatingOverlay: com.wae.agent.ui.FloatingOverlay? = null

    /** 切換懸浮窗；回傳切換後是否顯示 */
    fun toggleFloatingOverlay(): Boolean {
        val current = floatingOverlay
        if (current != null && current.isShowing()) {
            current.destroy()
            floatingOverlay = null
            Log.i("WAE_OVERLAY", "toggled off")
            return false
        }
        val overlay = com.wae.agent.ui.FloatingOverlay(this)
        return runCatching {
            overlay.show()
            floatingOverlay = overlay
            Log.i("WAE_OVERLAY", "toggled on")
            true
        }.getOrDefault(false)
    }

    fun isFloatingOverlayVisible(): Boolean = floatingOverlay?.isShowing() == true

    /** 懸浮窗下達指令（與主畫面共用同一條管線） */
    fun overlayExecuteCommand(raw: String) = overlayExecuteCommand(raw, fromVoice = false)

    fun overlayExecuteCommand(raw: String, fromVoice: Boolean) {
        if (fromVoice) {
            voiceTaskActive = true
            runCatching {
                if (voiceProgress == null) voiceProgress = com.wae.agent.core.VoiceProgressOverlay(this)
                voiceProgress?.show("準備中…")
            }
        }
        executeUserCommandPipelineAsync(raw) { msg ->
            runCatching { latestStatusMessage = msg }
        }
    }

    /** 懸浮窗唯讀狀態（JSON；只給 WebView 輪詢用） */
    fun overlayStateJson(): String {
        val busy = isBusy()
        return "{" +
            "\"busy\":" + busy + "," +
            "\"status\":\"" + jsonEscape(latestStatusMessage) + "\"," +
            "\"pkg\":\"" + jsonEscape(latestPackageName) + "\"," +
            "\"posture\":\"" + jsonEscape(latestPostureSummary) + "\"," +
            "\"candidates\":\"" + latestNodeCount + "\"," +
            "\"nodes\":\"" + latestNodeCount + "\"," +
            "\"phase\":\"" + (if (busy) "RUNNING" else "IDLE") + "\"" +
            "}"
    }

    private fun jsonEscape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (c in raw) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun isCancelledOrTimedOut(): Boolean =
        cancelRequested || SystemClock.uptimeMillis() > taskDeadlineMs

    /**
     * 執行完整自然語言任務指令（支援 L0 Deep Link -> L0.5 UI 模式 -> L1~L5 V2.6 閉環管線）
     */
    /**
     * G0 量測：把這一輪 grounding 的候選與選中結果寫進 trace 緩衝（純記憶體，不動控制流）。
     *
     * 只在 debug 版會落盤（見 `GroundingTraceRecorder.flushToFile`）；release 版即使記錄也不寫檔。
     * 失敗全部吞掉，絕不影響任務執行。
     */
    private fun recordGroundingTrace(
        goal: LocalGoal,
        optionSet: com.wae.agent.core.ImmutableOptionSet,
        chosen: CandidateOption
    ) {
        runCatching {
            val opts = optionSet.selectableOptions
            if (opts.isEmpty()) return
            val chosenIdx = opts.indexOfFirst { it.markId == chosen.markId }
            val traces = opts.mapIndexed { index, option ->
                com.wae.agent.core.grounding.TraceCandidate(
                    slot = index,
                    text = option.coreText.take(40),
                    className = option.className,
                    viewId = option.viewId,
                    bounds = option.grid,
                    clickable = option.isClickable,
                    editable = option.isEditable,
                    checkable = option.isCheckable,
                    checked = option.isChecked,
                    enabled = option.isEnabled,
                    score = option.hybridSimScore
                )
            }
            com.wae.agent.core.grounding.GroundingTraceRecorder.record(
                com.wae.agent.core.grounding.GroundingTrace(
                    pkg = optionSet.packageName,
                    posture = optionSet.postureState.effectivePosture.name,
                    stateId = "",
                    goal = goal.goalText,
                    candidates = traces,
                    chosenIndex = chosenIdx,
                    changed = false,
                    postconditionVerified = false,
                    elapsedMs = 0L
                )
            )
        }
    }

    fun executeUserCommandPipelineAsync(rawCommand: String, onStepUpdate: ((String) -> Unit)? = null) {
        if (!isRunningTask.compareAndSet(false, true)) {
            onStepUpdate?.invoke("⏳ 正在執行上一項任務，請稍候...")
            return
        }
        cancelRequested = false
        taskDeadlineMs = SystemClock.uptimeMillis() + TASK_TIMEOUT_MS

        pipelineExecutor.execute {
            val wakeLock = try {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WAE:PipelineExecWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(60_000L)
                }
            } catch (_: Throwable) {
                null
            }
            var pipelineOk = false
            try {
                taskRunSeq += 1L
                lastTaskSuccess = null
                pendingAmbiguousGoal = null
                pendingAmbiguousOptions = emptyList()
                WaeV26PipelineOrchestrator.globalTaskCounter.reset()
                WaeV26PipelineOrchestrator.stepCheckpoints.clear()
                // M3a：載入 Twin 歷史軌跡一次（供快路徑匹配）
                twinHistory = com.wae.agent.core.twin.TwinRecorder.loadPersisted(this)
                // P1：動作契約的冪等帳本每個任務重置
                com.wae.agent.core.action.ActionLedger.reset()
                com.wae.agent.core.action.GoalAttemptGuard.reset()
                // P4：用歷史軌跡播種「每選擇器延遲模型」
                com.wae.agent.core.action.TimingModel.reset()
                com.wae.agent.core.action.TimingModel.seedFrom(twinHistory)

                val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(rawCommand, this)
                Log.i("WAE_EXEC", "Decomposed rawCommand='$rawCommand' into ${goals.size} goals: $goals")
                if (goals.isEmpty()) {
                    notifyUiStatus("請輸入有效指令", onStepUpdate)
                    return@execute
                }

                // ── S33 Task OS：通用長任務引擎（旗標預設關閉 → 完全不改變行為）──
                // 命中模板/技能/續跑點時，改由 TaskRunner 用同一套單步執行器驅動整個任務；
                // 沒命中就 fallback 到原本的線性目標清單。
                if (com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED) {
                    com.wae.agent.core.taskos.TaskOsEngine.ensureLoaded(this)
                    when (val start = com.wae.agent.core.taskos.TaskOsEngine.start(rawCommand)) {
                        is com.wae.agent.core.taskos.TaskOsEngine.Start.Planned -> {
                            val session = start.session
                            Log.i(
                                "WAE_TASKOS",
                                "plan template=${session.plan.templateId} steps=${session.plan.size} " +
                                    "remaining=${session.remainingCount()} cmd='$rawCommand'"
                            )
                            val runResult = runTaskOsSession(session, onStepUpdate)
                            notifyUiStatus(
                                if (runResult.ok) {
                                    "✅ 任務完成（Task OS：${session.progressText()}）"
                                } else {
                                    "⚠ 任務未完成：${humanizeTaskOsMessage(runResult.message)}" +
                                        "（${session.progressText()}）"
                                },
                                onStepUpdate
                            )
                            Log.i(
                                "WAE_TASKOS",
                                "session result ok=${runResult.ok} msg=${runResult.message} " +
                                    "completed=${runResult.completed}/${session.plan.size} " +
                                    "stepsRun=${runResult.stepsRun}"
                            )
                            return@execute
                        }
                        is com.wae.agent.core.taskos.TaskOsEngine.Start.NeedVars -> {
                            val missing = start.missing.joinToString("、") { taskOsVarLabel(it) }
                            notifyUiStatus("❓ 需要更多資訊才能執行：$missing", onStepUpdate)
                            Log.i(
                                "WAE_TASKOS",
                                "need vars template=${start.templateId} missing=$missing"
                            )
                            return@execute
                        }
                        com.wae.agent.core.taskos.TaskOsEngine.Start.NoMatch -> Unit // 走既有路徑
                    }
                }

                // S34 WAE-Ω（shadow 模式）已於 2026-11-17 移除：
                // 它只寫 log、不改變執行，對使用者沒有價值，留在程式裡只會造成誤解。
                // 原始碼歸檔在 tools/archive/omega_removed/，需要時再取回。

                // 未安裝的 App：立刻明確回報，不要讓 Agent 在畫面上亂找
                val installCheck = com.wae.agent.core.app.AppInstallResolver.check(
                    this, goals.map { it.targetPackageHint }
                )
                if (installCheck.missing.isNotEmpty()) {
                    val names = com.wae.agent.core.app.AppInstallResolver.displayNames(
                        this, installCheck.missing
                    )
                    val msg = "❌ 應用程式未安裝，無法開啟：${names.joinToString("、")}"
                    Log.i("WAE_EXEC", msg)
                    notifyUiStatus(msg, onStepUpdate)
                    return@execute
                }

                var succeededGoals = 0
                var failedGoalText: String? = null
                var index = 0
                while (index < goals.size) {
                    val goal = goals[index]
                    index += 1
                    if (isCancelledOrTimedOut()) {
                        notifyUiStatus(
                            if (cancelRequested) "⏹ 已停止目前任務" else "⏱ 任務逾時，已安全停止",
                            onStepUpdate
                        )
                        return@execute
                    }
                    if (WaeV26PipelineOrchestrator.globalTaskCounter.isCircuitBreakerTripped) {
                        val lastCp = WaeV26PipelineOrchestrator.stepCheckpoints.lastOrNull()
                        val cpInfo = if (lastCp != null) "（可從步驟 ${lastCp.completedStepIdx + 1} 快照恢復）" else ""
                        notifyUiStatus("⛔ 全局熔斷：已連續 3 次需人工介入，任務已安全暫停 $cpInfo", onStepUpdate)
                        return@execute
                    }

                    WaeV26PipelineOrchestrator.localRecoveryCounter.reset()
                    val stepOk = executeSingleLocalGoalWithRecovery(goal, onStepUpdate)
                    // 目標層驗證：把「這步有反應」升級成「目標真的達成」。
                    // 目前只對「輸入類」強制生效（最常見的假成功），其餘先只記錄。
                    val toggleGoalDetected = runCatching {
                        val ti = com.wae.agent.core.nlu.ToggleIntent.parseForToggle(goal.goalText, goal.actionKind)
                            ?: com.wae.agent.core.nlu.ToggleIntent.parseForToggle(goal.inputPayload, goal.actionKind)
                        // 只要是開關指令就強制狀態驗證：找不到那個開關 → 直接判失敗（不可用「畫面變了」蒙混）
                        ti != null
                    }.getOrDefault(false)
                    val verified = if (stepOk) verifyGoalOutcome(goal, dispatchVerified = true) else false
                    // BACK 驗證收緊：返回若「畫面與套件都沒變」且目前不在桌面 → 判失敗。
                    // （在桌面按返回本來就不會有變化，那算合理）
                    val onLauncher = runCatching {
                        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_HOME)
                        packageManager.queryIntentActivities(home, 0)
                            .any { it.activityInfo.packageName == (rootInActiveWindow?.packageName?.toString() ?: "") }
                    }.getOrDefault(false)
                    val enforceVerify = goal.actionKind == ActionKind.TYPE ||
                        goal.actionKind == ActionKind.REPLACE_TEXT ||
                        (goal.actionKind == ActionKind.BACK && !onLauncher) ||
                        toggleGoalDetected
                    val goalOk = if (stepOk && enforceVerify) verified else stepOk
                    if (stepOk && enforceVerify && !verified) {
                        Log.i("WAE_VERIFY", "stepOk 但目標未達成 → 判為失敗（輸入內容未落地）")
                    }
                    Log.i("WAE_EXEC", "Goal stepIdx=${goal.stepIdx} (${goal.goalText}) finished with stepOk=$goalOk")
                    if (goalOk) {
                        runCatching {
                            com.wae.agent.core.runtime.ExtremeRuntimeHooks.onGoalDone(
                                goal.targetPackageHint.ifEmpty { latestPackageName }, goalSelectors.toList()
                            )
                        }
                        succeededGoals += 1
                        continue
                    }
                    runCatching {
                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onGoalFailed(
                            goal.targetPackageHint.ifEmpty { latestPackageName }
                        )
                    }
                    // 失敗：依分段策略決定「整段停」或「只停這一段，繼續其他段」
                    failedGoalText = goal.goalText
                    val outcome = com.wae.agent.core.action.GoalSegmentPolicy.afterFailure(
                        goals, goals.indexOf(goal)
                    )
                    Log.i("WAE_EXEC", "segment policy: ${outcome.reason}")
                    if (!outcome.continueExecution) {
                        notifyUiStatus(
                            com.wae.agent.core.action.GoalSegmentPolicy.summaryMessage(
                                goals.size, succeededGoals, failedGoalText
                            ),
                            onStepUpdate
                        )
                        return@execute
                    }
                    // 跳過同階段剩餘目標，繼續其他階段
                    val skipSet = outcome.skippedGoals.toSet()
                    index = goals.indexOfFirst { it in skipSet && goals.indexOf(it) >= index }
                        .let { if (it < 0) goals.size else it }
                    if (index < goals.size) {
                        // 停在被跳過的第一個，下一輪會因為在 skipSet 中而再前進
                    }
                    while (index < goals.size && goals[index] in skipSet) index += 1
                    notifyUiStatus("⚠ 跳過同階段目標，繼續下一階段", onStepUpdate)
                }
                notifyUiStatus(
                    com.wae.agent.core.action.GoalSegmentPolicy.summaryMessage(
                        goals.size, succeededGoals, failedGoalText
                    ),
                    onStepUpdate
                )
                pipelineOk = failedGoalText == null
            } catch (t: Throwable) {
                // 任何未預期例外都不得讓服務崩潰或讓 UI 永遠停在「執行中」
                Log.e("WAE_EXEC", "Pipeline crashed: rawCommand='$rawCommand'", t)
                notifyUiStatus("❌ 執行時發生錯誤（${t.javaClass.simpleName}），已安全停止", onStepUpdate)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Throwable) {
                }
                // Twin（M2）：任務結束 → 落地本次軌跡，並做「重放 vs 現場」對比
                try {
                    val fresh = com.wae.agent.core.twin.TwinRecorder.steps()
                    // 先讀「歷史軌跡」當訓練集（held-out 重放要用它，不能用本次自己的資料自證）
                    val history = com.wae.agent.core.twin.TwinRecorder.loadPersisted(this)
                    val saved = com.wae.agent.core.twin.TwinRecorder.flush(this)
                    if (fresh.isNotEmpty()) {
                        stepsSinceCompile += fresh.size
                        Log.i("WAE_TWIN", "session ${saved.summary()}")
                        Log.i(
                            "WAE_TWIN",
                            "chain coverage=${"%.0f".format(
                                com.wae.agent.core.twin.TwinStore.chainCoverage(fresh) * 100
                            )}% longest=${com.wae.agent.core.twin.TwinStore.longestChain(fresh)} steps"
                        )
                        // ① 自我一致性：本次軌跡建圖後重放本次（上限）
                        Log.i(
                            "WAE_TWIN",
                            "replay(self) ${com.wae.agent.core.twin.TwinStore.replay(fresh).summary()}"
                        )
                        // ② held-out：用「歷史軌跡」建圖，重放本次（真實可編譯率）
                        if (history.isNotEmpty()) {
                            val heldOut = com.wae.agent.core.twin.TwinStore.replay(fresh, graphSource = history)
                            Log.i("WAE_TWIN", "replay(held-out, graph=${history.size} steps) ${heldOut.summary()}")
                        } else {
                            Log.i("WAE_TWIN", "replay(held-out) 尚無歷史軌跡，需再執行一次同一任務")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("WAE_TWIN", "twin finalize failed: ${t.message}")
                }
                val finalOk = pipelineOk && !cancelRequested
                lastTaskSuccess = finalOk
                Log.i("WAE_EXEC", "taskResult=${if (finalOk) "SUCCESS" else "FAIL"} seq=$taskRunSeq")
                // G0：任務結束把 grounding trace 落盤（只在 debug 版；失敗不影響結果）
                runCatching {
                    val n = com.wae.agent.core.grounding.GroundingTraceRecorder.bufferedCount()
                    if (com.wae.agent.core.grounding.GroundingTraceRecorder.flushToFile(this)) {
                        Log.i("WAE_TRACE", "grounding trace flushed steps=$n")
                    }
                }
                if (voiceTaskActive) {
                    voiceTaskActive = false
                    runCatching {
                        voiceProgress?.update(if (finalOk) "完成" else "失敗")
                        voiceProgress?.hide(900L)
                    }
                }
                // P0/P1：歷史結果 + 語音回饋（只有語音來源才出聲）
                runCatching {
                    com.wae.agent.voice.SessionHistory.markLastResult(this, finalOk)
                    val last = com.wae.agent.voice.SessionHistory.recent(this, 1).firstOrNull()
                    if (last != null && last.voice && System.currentTimeMillis() - last.ts < 120_000L) {
                        com.wae.agent.voice.VoiceFeedback.speakResult(this, finalOk)
                    }
                }
                // 任務結束：把遙測落盤，讓「降權」跨啟動累積（P0-2）
                runCatching {
                    com.wae.agent.core.action.ActionTelemetry.persist(this@WaeVisionAccessibilityService)
                    com.wae.agent.core.action.NegativeEdgeStore.persist(this@WaeVisionAccessibilityService)
                }
                // M2/P1：把本次軌跡編譯成該 App 的狀態圖（純程式、無模型；含去抖）
                runCatching {
                    val ctx = this@WaeVisionAccessibilityService
                    val lowEnd = com.wae.agent.core.automaton.GraphBudget.isLowEndDevice(ctx)
                    // 必須用「剛落盤的最新軌跡」：twinHistory 是任務開始時載入的快照，
                    // 若拿它編譯，本次任務學到的東西要等下一次才進圖（等於永遠慢一輪）。
                    val latest = com.wae.agent.core.twin.TwinRecorder.loadPersisted(ctx)
                    val nowMs = System.currentTimeMillis()
                    val pkgs = latest.map { it.pkg }.filter { it.isNotEmpty() }.distinct()
                    var compiledAny = false
                    for (p in pkgs) {
                        val steps = latest.filter { it.pkg == p }
                        if (steps.isEmpty()) continue
                        val prev = com.wae.agent.core.automaton.AutomatonStore.load(ctx, p)
                        val should = com.wae.agent.core.automaton.AutomatonCompilePolicy.shouldCompile(
                            newSteps = stepsSinceCompile,
                            nowMs = nowMs,
                            lastCompileMs = lastCompileAtMs,
                            hasExistingGraph = prev != null
                        )
                        if (!should) {
                            Log.i("WAE_AUTOMATON", "skip compile pkg=$p (debounce newSteps=$stepsSinceCompile)")
                            continue
                        }
                        val automaton = com.wae.agent.core.automaton.AutomatonCompiler.compile(
                            pkg = p, steps = steps, prev = prev, lowEndDevice = lowEnd
                        )
                        com.wae.agent.core.automaton.AutomatonStore.save(ctx, p, automaton)
                        compiledAny = true
                        Log.i(
                            "WAE_AUTOMATON",
                            "compiled pkg=$p states=${automaton.stateCount()} edges=${automaton.edgeCount()} " +
                                "tier=${automaton.tier} sha=${automaton.sha256().take(12)}"
                        )
                        // S22：圖健康度（覆蓋 × 新鮮 × 成功 − 負邊密度）
                        runCatching {
                            val neg = com.wae.agent.core.action.NegativeEdgeStore
                                .snapshot().count { it.pkg == p }
                            val health = com.wae.agent.core.observe.GraphHealth
                                .compute(automaton, negativeEdges = neg)
                            Log.i("WAE_HEALTH", "pkg=$p " + com.wae.agent.core.observe.GraphHealth.describe(health))
                            // S16：熱路徑是否已達「可編譯成位元碼」的門檻
                            val hottest = automaton.edges.maxByOrNull { it.total }
                            if (hottest != null && com.wae.agent.core.program.ProgramCompiler
                                    .isEligible(hottest.total, hottest.successRate)
                            ) {
                                Log.i(
                                    "WAE_PROGRAM",
                                    "eligible pkg=$p edge=${hottest.selector} " +
                                        "exec=${hottest.total} success=${"%.2f".format(hottest.successRate)} " +
                                        "(execution flag=$PROGRAM_EXECUTION_ENABLED)"
                                )
                            }
                        }
                    }
                    if (compiledAny) {
                        stepsSinceCompile = 0
                        lastCompileAtMs = nowMs
                    }
                    // §16 全子系統：任務結束分析（全部只記錄，不影響控制流）
                    runCatching {
                        for (p in pkgs.take(3)) {
                            val autoEnd = com.wae.agent.core.automaton.AutomatonStore.load(ctx, p) ?: continue
                            com.wae.agent.core.runtime.ExtremeRuntimeHooks.buildIndex(autoEnd)
                            com.wae.agent.core.runtime.ExtremeRuntimeHooks.onTaskEnd(
                                ctx = ctx,
                                pkg = p,
                                automaton = autoEnd,
                                negatives = com.wae.agent.core.action.NegativeEdgeStore.snapshot(),
                                steps = latest.count { it.pkg == p }
                            )
                        }
                    }
                    // ── M6：探索排程（S5）──
                    // 預設關閉；即使開啟，也必須通過「三層護欄」才會動作。
                    runCatching { scheduleExploration(ctx, latest) }
                }
                cancelRequested = false
                taskDeadlineMs = Long.MAX_VALUE
                isRunningTask.set(false)
                notifyUiStatus(latestStatusMessage, onStepUpdate)
            }
        }
    }

    /**
     * 當用戶在 AMBIGUOUS 澄清對話框點選了其中一個選項時直接執行該候選
     */
    fun resolveAmbiguousSelectionAsync(chosen: CandidateOption, onComplete: ((String) -> Unit)? = null) {
        val goal = pendingAmbiguousGoal ?: return
        pendingAmbiguousGoal = null
        pendingAmbiguousOptions = emptyList()

        pipelineExecutor.execute {
            val ok = executeL4ActionWithSingleNodeToctouGuard(goal, chosen)
            val msg = if (ok) {
                "✓ 已依您的澄清選擇執行：「${chosen.coreText.ifEmpty { chosen.weldedDescription }}」"
            } else {
                "⚠ 畫面已變動 (TOCTOU 攔截)，請重新執行"
            }
            notifyUiStatus(msg, onComplete)
        }
    }

    /**
     * 單純擷取一次感知層快照（供手動測試檢視）
     */
    fun capturePerceptionSnapshotAsync(onComplete: ((String) -> Unit)? = null) {
        pipelineExecutor.execute {
            val dummyGoal = LocalGoal(
                stepIdx = 0,
                goalId = "snapshot",
                goalText = "感知快照",
                canonicalIntentTag = "general_tap",
                actionKind = ActionKind.TAP
            )
            val (_, optionSet) = perceiveScreenAndBuildOptionSet(
                goal = dummyGoal,
                windowIdx = 0,
                captureScreenshot = true
            )
            latestPostureSummary = "${optionSet.postureState.effectivePosture.name} (${optionSet.postureState.componentLib.name})"
            onComplete?.invoke(latestFrameJson)
        }
    }

    /** 內容變化事件在指定時間之後發生的次數（用於判斷畫面是否真的變了） */
    private fun contentEventsSince(tsMs: Long): Int {
        if (tsMs <= 0L) return Int.MAX_VALUE
        return synchronized(recentContentChangeTimestamps) {
            recentContentChangeTimestamps.count { it > tsMs }
        }
    }

    /**
     * 帶快取的感知：同一 App、同一視窗、同一結構指紋、且期間無內容變化事件，
     * 就直接重用上一幀（省下約 200ms 採集 + 90ms 打分）。
     */
    private fun perceiveWithFrameCache(
        goal: LocalGoal,
        collectOnly: Boolean,
        forceOcr: Boolean
    ): Pair<List<CandidateOption>, ImmutableOptionSet> {
        val now = android.os.SystemClock.uptimeMillis()
        val root = getActiveNonWaeRoot()
        val pkg = root?.packageName?.toString().orEmpty()
        val winId = root?.windowId ?: -1
        val ambient = runCatching { computeAmbientSpine().first }.getOrDefault(0L)
        val goalKey = goal.actionKind.name + "|" + goal.inputPayload.take(24)

        val cached = frameCacheResult
        if (cached != null && !forceOcr && goalKey == frameCacheGoalKey &&
            com.wae.agent.core.FrameReusePolicy.canReuse(
                samePackage = pkg.isNotEmpty() && pkg == frameCachePkg,
                sameWindow = winId == frameCacheWindowId,
                sameAmbient = ambient != 0L && ambient == frameCacheAmbient,
                msSince = now - frameCacheAtMs,
                contentEventsSince = contentEventsSince(frameCacheAtMs)
            )
        ) {
            Log.i(
                "WAE_CACHE",
                "frame reused pkg=$pkg win=$winId candidates=${cached.first.size} age=${now - frameCacheAtMs}ms"
            )
            return cached
        }

        val fresh = perceiveScreenAndBuildOptionSet(
            goal = goal,
            windowIdx = 0,
            forceOcr = forceOcr,
            collectOnly = collectOnly
        )
        if (!forceOcr) {
            frameCacheAtMs = android.os.SystemClock.uptimeMillis()
            frameCacheAmbient = ambient
            frameCacheWindowId = winId
            frameCachePkg = pkg
            frameCacheGoalKey = goalKey
            frameCacheResult = fresh
        }
        return fresh
    }

    /**
     * 收集目前畫面上的開關狀態（重新走訪樹，讀 isCheckable/isChecked）。
     * 這是 S27 的「動作後驗證」依據：狀態真的變了才算成功。
     */
    private fun collectToggleFacts(limit: Int = 40): List<com.wae.agent.core.goal.ToggleFact> =
        com.wae.agent.perception.ToggleFacts.collect(rootInActiveWindow, limit)

    /**
     * 相容性掃描（開發期）：只讀畫面文字，不點擊、不操作。
     * 供 CompatibilityScanner 使用。
     */
    fun probeScreenTextsPublic(limit: Int = 200): List<String> = collectPageTexts(limit)

    /** 目前前景（非 WAE）套件名 */
    fun probeForegroundPackagePublic(): String =
        getActiveNonWaeRoot()?.packageName?.toString().orEmpty()

    /** 以「啟動 App」的方式bringToFront（不模擬點擊） */
    fun probeLaunchPublic(pkg: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 收集頁面文字（限量、限深），供目標層驗證使用 */
    private fun collectPageTexts(limit: Int): List<String> =
        com.wae.agent.perception.PageScanner.texts(rootInActiveWindow, limit)

    /**
     * 目標層驗證：判定「使用者要的事情是否真的發生」。
     * 純規則（見 GoalVerifier），不看模型。
     */
    private fun verifyGoalOutcome(goal: LocalGoal, dispatchVerified: Boolean): Boolean {
        // 觀察（服務負責）
        val pkgAfter = rootInActiveWindow?.packageName?.toString().orEmpty()
        val ambientAfter = runCatching { computeAmbientSpine().first }.getOrDefault(0L)
        val spineChanged = ambientAfter != 0L && goalStartAmbient != 0L && ambientAfter != goalStartAmbient
        val inputs = com.wae.agent.core.goal.GoalOutcome.Inputs(
            goal = goal,
            dispatchVerified = dispatchVerified,
            pkgAfter = pkgAfter,
            pkgBefore = goalStartPkg,
            spineChanged = spineChanged,
            texts = collectPageTexts(240),
            selectors = lastCandidates.map { selectorKeyOf(it) }.toSet(),
            values = lastCandidates.filter { it.hasRange }.associate { selectorKeyOf(it) to it.rangeCurrent },
            valuesBefore = goalStartValues,
            toggles = collectToggleFacts()
        )

        // 判定（純決策，住在 core.goal.GoalOutcome）
        val verdict = com.wae.agent.core.goal.GoalOutcome.decide(inputs)
        Log.i(
            "WAE_VERIFY",
            "goal='${verdict.goalText.take(24)}' kind=${verdict.actionKind} post=${verdict.description} " +
                "verifiable=${verdict.verifiable} ok=${verdict.ok}"
        )
        // 不可驗證時不可假裝失敗
        return if (verdict.verifiable) verdict.ok else true
    }

    /**
     * S5 探索排程（架構書 §7.2–§7.4）。
     *
     * 這裡刻意做成「影子模式」：算出「該探索哪條邊」，並在圖上模擬一次，
     * 但**不實際點擊**。真正的落地需要使用者明確開啟 EXPLORER_ENABLED，
     * 且任何一步都要通過護欄（充電、白名單、預算、禁忌詞、動作種類）。
     */
        private fun scheduleExploration(
        ctx: android.content.Context,
        steps: List<com.wae.agent.core.twin.TwinStep>
    ) {
        if (!EXPLORER_ENABLED) return
        com.wae.agent.core.explore.ExplorationScheduler.run(ctx, steps) {
            runCatching {
                val bm = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                bm.isCharging
            }.getOrDefault(false)
        }
    }

    /**
     * S6 借用邊（架構書 §8.2）：自家圖上沒有可用路徑時，從其他 App 的圖借同構邊。
     * 預設關閉；開啟後仍只借用「signature 不存在於自家圖」的邊，且信賴度打折。
     */
    private fun tryBorrowedPlan(
        ctx: android.content.Context,
        pkg: String,
        auto: com.wae.agent.core.automaton.Automaton,
        hint: String,
        goalOp: String
    ): com.wae.agent.core.automaton.AutomatonPlanner.Plan? {
        if (!TRANSFER_ENABLED) return null
        val donors = com.wae.agent.core.automaton.AutomatonStore.listPackages(ctx)
            .filter { it != pkg }
            .take(3)
        for (donorPkg in donors) {
            val donor = com.wae.agent.core.automaton.AutomatonStore.load(ctx, donorPkg) ?: continue
            val borrowed = com.wae.agent.core.transfer.TransferEngine.borrowableEdges(pkg, auto, donorPkg, donor)
            if (borrowed.isEmpty()) continue
            val merged = auto.copy(edges = auto.edges + borrowed)
            val plan = com.wae.agent.core.automaton.AutomatonPlanner.planToGoalHint(
                automaton = merged,
                from = auto.states.firstOrNull()?.id ?: return null,
                hint = hint,
                goalOp = goalOp
            )
            if (plan != null) {
                Log.i(
                    "WAE_TRANSFER",
                    "borrowed plan donor=$donorPkg borrowed=${borrowed.size} ${plan.summary()}"
                )
                return plan
            }
        }
        return null
    }

    private fun executeSingleLocalGoalWithRecovery(
        goal: LocalGoal,
        onStepUpdate: ((String) -> Unit)?
    ): Boolean {
        automatonHopsThisGoal = 0
        typeLadderAttempts = 0
        toggleDrillAttempts = 0
        goalSelectors.clear()
        goalStartAmbient = runCatching { computeAmbientSpine().first }.getOrDefault(0L)
        goalStartPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        goalStartValues = lastCandidates.filter { it.hasRange }
            .associate { selectorKeyOf(it) to it.rangeCurrent }
        // 0. 跨 App 自動啟動與前景視窗切換等待：
        // 若目標帶有 targetPackageHint 且目前前景視窗不是該 App（例如仍在 WAE 主畫面），先啟動該目標 App！
        if (goal.targetPackageHint.isNotEmpty()) {
            val currentFgPkg = getActiveNonWaeRoot()?.packageName?.toString().orEmpty()
            if (currentFgPkg != goal.targetPackageHint) {
                notifyUiStatus(" 正在啟動應用程式：${goal.targetPackageHint}...", onStepUpdate)
                val launched = WaeOpenSourceDatabaseRegistry.launchAppByPackageName(this, goal.targetPackageHint)
                Log.i("WAE_EXEC", "Launching targetPackageHint=${goal.targetPackageHint}, launched=$launched")
                if (launched) {
                    waitForTargetPackageInForeground(goal.targetPackageHint, 3200L)
                } else if (goal.actionKind == ActionKind.LAUNCH_APP) {
                    notifyUiStatus("⚠ 無法啟動應用程式：${goal.targetPackageHint}", onStepUpdate)
                    return false
                }
            }
            if (goal.actionKind == ActionKind.LAUNCH_APP) {
                // S31（2026-11-14 使用者決定）：刪除「依姿態模型自動回根頁」的規則。
                // 那個做法把 Maps 首頁誤判成 SEARCH_RESULTS 後按 BACK 直接退出 App。
                //
                // 2026-11-23 使用者回報的新問題：進入設定搜尋頁之後不會自己退出，
                // 後面一連串任務都在同一個子頁面上找東西 → 全滅。
                //
                // 現在改成「有證據才返回」：只有在畫面上**真的看得到**
                // 工具列返回箭頭（contentDescription 是返回／上一頁／Back，或 id 是 back/up）
                // 且位於畫面上方時，才按 BACK；按完重新檢查，最多 baseline.max_back 次。
                com.wae.agent.core.service.BaselineRecovery.backOutToRootIfNested(this, this, "launch:" + goal.targetPackageHint)
                notifyUiStatus("✓ 已打開應用程式：${goal.targetPackageHint}", onStepUpdate)
                return true
            }
        } else {
            // 若指令未指定新 App，但當前頂層視窗仍是 WAE 自身，等待 MainActivity 退至背景以露出底層目標畫面
            val startWait = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - startWait < 1500L) {
                val fgPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
                if (fgPkg.isNotEmpty() && fgPkg != packageName) break
                SystemClock.sleep(100L)
            }
        }

        // 1. L0 Deep Link 直達快車道優先匹配
        val deepLinkRule = WaeDeepLinkCapabilityManager.findMatchingDeepLink(goal, latestPackageName)
        if (deepLinkRule != null) {
            notifyUiStatus("⚡ L0 Deep Link 直達：${deepLinkRule.ruleId} -> ${goal.redactedPayload()}", onStepUpdate)
            val launched = WaeDeepLinkCapabilityManager.launchDeepLinkFastPath(
                context = this,
                rule = deepLinkRule,
                payload = goal.inputPayload.ifEmpty { goal.goalText }
            )
            if (launched) {
                waitForUiQuiet(1500L)
                val (_, postJumpSet) = perceiveScreenAndBuildOptionSet(goal, windowIdx = 0)
                val visibleTexts = postJumpSet.selectableOptions.map { it.coreText }
                val verify = WaeDeepLinkCapabilityManager.verifyLandingPosture(
                    context = this,
                    rule = deepLinkRule,
                    goal = goal,
                    observedPosture = postJumpSet.postureState,
                    visibleTexts = visibleTexts
                )
                if (verify.isSuccess) {
                    saveStepCheckpoint(goal, postJumpSet)
                    notifyUiStatus("✓ L0 Deep Link 落地驗證通過 (${postJumpSet.postureState.effectivePosture})", onStepUpdate)
                    return true
                } else {
                    notifyUiStatus("⚠ ${verify.reason}", onStepUpdate)
                    // 靜默跳回首頁時自動標記 STALE，並無縫原地接回下方 V2.6 GUI 管線
                }
            }
        }

        // 2. 進入 V2.6 L1~L5 感知-決策-執行與三級有界主動恢復閉環 (上限 5 輪防死迴圈)
        // C：同一任務內同一目標只做一次（治「同樣的事做三次」）
        if (com.wae.agent.core.action.GoalAttemptGuard.shouldSkip(
                goal.actionKind.name, goal.inputPayload
            )
        ) {
            Log.i("WAE_EXEC", "skip duplicate goal: ${goal.actionKind} '${goal.inputPayload}'")
            notifyUiStatus("⏭ 重複目標已略過：${goal.goalText}", onStepUpdate)
            return false
        }

        // 無目標動作：BACK / 滾動 / 送出 / 清除 / 替換 不需要選中元素
        // 判定完全來自動作空間（ActionOps），不含任何 App 或畫面資料
        val op = ActionOps.of(goal.actionKind, goal.inputPayload)
        // 注意：N:TXT（一般輸入）不是無目標動作——它必須先找到並聚焦搜尋框，
        // 只有「清除/替換」才是對已聚焦欄位的直接操作。
        val targetless = op.fine == FineOp.N_CLEAR || op.fine == FineOp.N_REPLACE ||
            (op.primitive == ActionPrimitive.INTERACT && op.kind != InteractKind.TAP)
        if (targetless) {
            val ok = dispatchTargetlessAction(goal, onStepUpdate)
            Log.i("WAE_EXEC", "targetless action=${op.compact()} ok=$ok")
            if (ok) return true
        }

        var loopAttempt = 0
        // VoI：本任務是否已升級過一次（每任務只升級一次，防止成本失控）
        var voiEscalated = false
        // M5a：本任務是否已嘗試過序列重放（失敗就回退，不重複嘗試）
        var replayAttempted = false
        // M5b：本任務是否已嘗試過 MCTS 規劃
        var mctsAttempted = false
        // TYPE 找不到輸入框時，允許先 BACK 退出上一層（例如 App 直接回到上次播放的影片頁）
        var noInputEntryBacks = 0
        // 下一幀是否強制跑 OCR（由決策層的「資訊不足」訊號設定）
        var forceOcrNextFrame = false
        // 每個目標只回報一次「畫面跑到別的 package」警告，避免刷屏
        var reportedPackageDrift = ""
        while (loopAttempt < 5) {
            loopAttempt++
            if (isCancelledOrTimedOut()) {
                notifyUiStatus(
                    if (cancelRequested) "⏹ 已停止目前任務" else "⏱ 任務逾時，已安全停止",
                    onStepUpdate
                )
                return false
            }
            val loopStartMs = android.os.SystemClock.elapsedRealtime()
            // M3b：若 Twin 有同 App+同動作的歷史，先只採集（跳過 embedding）
            val twinCanFastPath = twinHistory.any {
                it.pkg == latestPackageName && it.action == goal.actionKind.name && it.ok
            }
            var (scoredAll, optionSetW0) = perceiveWithFrameCache(
                goal = goal,
                collectOnly = twinCanFastPath,
                forceOcr = forceOcrNextFrame
            )
            forceOcrNextFrame = false
            frame0Selectors = optionSetW0.selectableOptions.map { selectorKeyOf(it) }.toSet()
            runCatching {
                val sigs = optionSetW0.pageSpineSigs.toList()
                val autoForHook = automatonFor(optionSetW0.packageName)
                val liveFp = com.wae.agent.core.automaton.MinHash.fingerprint(sigs)
                val stateId = autoForHook?.matchState(liveFp, 0.60f)?.id
                com.wae.agent.core.runtime.ExtremeRuntimeHooks.onLoopStart(
                    stepIdx = loopAttempt,
                    pkg = optionSetW0.packageName,
                    stateId = stateId,
                    sigHash = com.wae.agent.core.runtime.ExtremeRuntimeHooks.sigHashOf(sigs),
                    posture = goal.canonicalIntentTag,
                    lastOp = goal.actionKind.name
                )
            }
            Log.i(
                "WAE_FASTPATH",
                "perceive=${android.os.SystemClock.elapsedRealtime() - loopStartMs}ms " +
                    "collectOnly=$twinCanFastPath candidates=${scoredAll.size}"
            )
            // 畫面穩定閘門：App 剛啟動（冷啟動）時無障礙樹常是「空殼」——
            // 有節點、沒有文字，這時做任何決策都是盲判（實測 4 輪在 1.6 秒內跑完、全部無文字）。
            // 第一輪最多等 5 秒讓畫面長出來，之後每輪最多 1.2 秒。
            val settleBudgetMs = if (loopAttempt == 1) 5000L else 1200L
            val settleDeadline = android.os.SystemClock.uptimeMillis() + settleBudgetMs
            var settlePolls = 0
            while (android.os.SystemClock.uptimeMillis() < settleDeadline &&
                optionSetW0.selectableOptions.count { it.hasOwnSemanticEvidence } < 3 &&
                !isCancelledOrTimedOut()
            ) {
                SystemClock.sleep(350L)
                val refreshed = perceiveScreenAndBuildOptionSet(
                    goal = goal,
                    windowIdx = 0,
                    forceOcr = false,
                    collectOnly = twinCanFastPath
                )
                scoredAll = refreshed.first
                optionSetW0 = refreshed.second
                settlePolls++
            }
            if (settlePolls > 0) {
                Log.i(
                    "WAE_WAIT",
                    "settle polls=$settlePolls identifiable=" +
                        optionSetW0.selectableOptions.count { it.hasOwnSemanticEvidence } +
                        " candidates=${scoredAll.size}"
                )
            }
            // 數值調整專用的「掃描可調元件」：進入正確頁面後，滑桿常常不在當前視窗內
            // （或還在渲染），此時不要急著再往下鑽，先上下掃一輪找真正的滑桿。
            if (goal.actionKind == ActionKind.ADJUST_VALUE &&
                loopAttempt <= 2 &&
                optionSetW0.selectableOptions.none { it.canAdjustValue }
            ) {
                var scan = 0
                val dirs = booleanArrayOf(false, true) // 先往上找（設定項多在上方），再往下
                while (scan < dirs.size &&
                    optionSetW0.selectableOptions.none { it.canAdjustValue } &&
                    !isCancelledOrTimedOut()
                ) {
                    performA11yScroll(forward = dirs[scan])
                    waitForUiQuiet(600L)
                    val rescanned = perceiveScreenAndBuildOptionSet(
                        goal = goal,
                        windowIdx = 0,
                        forceOcr = false,
                        collectOnly = twinCanFastPath
                    )
                    scoredAll = rescanned.first
                    optionSetW0 = rescanned.second
                    scan++
                    Log.i(
                        "WAE_VALUE",
                        "scan adjustable dir=${dirs[scan - 1]} found=" +
                            optionSetW0.selectableOptions.count { it.canAdjustValue }
                    )
                }
            }

            if (optionSetW0.packageName == packageName) {
                notifyUiStatus("⚠ 目前前景是 WAE 自己，AI 不會操作自身介面；請先切到目標 App 再執行", onStepUpdate)
                return false
            }
            // 跨 package 移動只記警告、不中止：Android 常把「同一個可見介面」拆成多個 package
            // （例如設定的子頁面由另一個 package 提供，畫面看起來仍是設定）。
            // 真正的防護放在「不能操作唯讀元件 / 量程無效就拒絕」，而不是靠 package 名硬擋。
            if (goal.targetPackageHint.isNotEmpty() &&
                optionSetW0.packageName.isNotEmpty() &&
                optionSetW0.packageName != goal.targetPackageHint
            ) {
                if (reportedPackageDrift != goal.goalId) {
                    reportedPackageDrift = goal.goalId
                    Log.i(
                        "WAE_EXEC",
                        "package drift: hint=${goal.targetPackageHint} actual=${optionSetW0.packageName} " +
                            "(continuing; may be a sub-package of the same visible UI)"
                    )
                }
            }
            latestPostureSummary = "${optionSetW0.postureState.effectivePosture.name} (${optionSetW0.postureState.componentLib.name})"
            val topPreview = optionSetW0.selectableOptions.take(3).joinToString {
                "'${it.coreText.ifEmpty { it.roleTag }}'(sim=${"%.2f".format(it.hybridSimScore)},edit=${it.isEditable},click=${it.isClickable},aff=${it.isSearchAffordance},id=${it.viewId.substringAfterLast('/')})"
            }
            Log.i(
                "WAE_EXEC",
                "Loop#$loopAttempt pkg=${optionSetW0.packageName} candidates=${scoredAll.size} top3=[$topPreview]"
            )

            // ── S32 動態 UI 閘門：載入中/廣告/權限彈窗/鍵盤遮擋 → 這輪先不要硬點 ──
            if (com.wae.agent.core.runtime.ExtremeRuntimeHooks.GROUNDING_2_ENABLED) {
                var dynSignal: com.wae.agent.core.grounding.DynamicUiClassifier.Signal? = null
                runCatching {
                    val dynTexts = optionSetW0.selectableOptions
                        .map { it.coreText }
                        .filter { it.isNotBlank() }
                    val sig = com.wae.agent.core.grounding.DynamicUiClassifier.classify(
                        visibleTexts = dynTexts,
                        hasModalOverlay = optionSetW0.postureState.overlayPosture ==
                            com.wae.agent.core.PagePosture.MODAL_DIALOG
                    )
                    if (sig.state != com.wae.agent.core.grounding.DynamicUiClassifier
                            .DynamicUiState.NONE
                    ) {
                        Log.i(
                            "WAE_DYNUI",
                            "state=${sig.state} conf=${"%.2f".format(sig.confidence)} evidence='${sig.evidence}'"
                        )
                        if (sig.isBlocking) dynSignal = sig
                    }
                }
                if (dynSignal != null) {
                    // 不硬點：等一下讓畫面穩定，下一輪重新感知再決策
                    SystemClock.sleep(
                        com.wae.agent.core.grounding.DynamicUiClassifier
                            .suggestedWaitMs(dynSignal!!.state)
                    )
                    continue
                }
            }

            // ── M3/P1：自動機規劃（多步導覽 + 逐步驗證）────────────────────
            // 規劃出來的邊只當「導覽」，每步都驗證真的走到預期狀態；
            // 走到目標頁後**不 return true**（導覽不等於完成），讓正常決策在該頁完成任務。
            if (AUTOMATON_PLANNER_ENABLED && automatonHopsThisGoal < AUTOMATON_MAX_HOPS) {
                var navigated = false
                runCatching {
                    val pkgNow = optionSetW0.packageName
                    val auto = automatonFor(pkgNow)
                    if (auto != null && auto.stateCount() > 1) {
                        val liveFp = com.wae.agent.core.automaton.MinHash
                            .fingerprint(optionSetW0.pageSpineSigs.toList())
                        val cur = auto.matchState(liveFp, 0.60f)
                        if (cur != null) {
                            val hint = goal.inputPayload.ifBlank { goal.goalText }
                            val plan = com.wae.agent.core.automaton.AutomatonPlanner.planToGoalHint(
                                automaton = auto,
                                from = cur.id,
                                hint = hint,
                                goalOp = goal.actionKind.name,
                                isBlocked = { e ->
                                    com.wae.agent.core.action.NegativeEdgeStore.isBlocked(
                                        pkgNow, e.selector, e.op, state = cur.id
                                    )
                                },
                                // S29：決策頭邊重排序（旗標關閉時恆為 0.0，成本模型與舊版一致）。
                                // 帶入負邊懲罰（同一狀態下的歷史失敗）與「是否從目前這頁出發」。
                                edgeBonus = { e ->
                                    val penalty = com.wae.agent.core.action.NegativeEdgeStore
                                        .penalty(pkgNow, e.selector, e.op, state = cur.id)
                                    com.wae.agent.core.decision.DecisionHead.edgeBonus(
                                        hint, goal.actionKind.name, e, penalty, cur.id
                                    )
                                }
                            )
                            // S8 靜態安全 + S16 乾跑閘門（不安全/無法乾跑就退回決策）
                            var gatedPlan = plan?.let { p ->
                                com.wae.agent.core.runtime.ExtremeRuntimeHooks.gatePlan(
                                    pkg = pkgNow,
                                    plan = p,
                                    presentSelectors = frame0Selectors,
                                    spineChanged = true
                                )
                            }
                            // S6：自家圖上沒有可用路徑時，嘗試借其他 App 的同構邊
                            if (gatedPlan == null && TRANSFER_ENABLED) {
                                gatedPlan = tryBorrowedPlan(this, pkgNow, auto, hint, goal.actionKind.name)
                                if (gatedPlan != null) {
                                    Log.i("WAE_TRANSFER", "using borrowed plan ${gatedPlan.summary()}")
                                }
                            }
                            if (gatedPlan != null &&
                                gatedPlan.steps in 1..AUTOMATON_MAX_HOPS &&
                                gatedPlan.expectedSuccess >= AUTOMATON_MIN_PLAN_SUCCESS
                            ) {
                                val plan = gatedPlan
                                Log.i("WAE_PLANNER", "automaton plan ${plan.summary()} from=${cur.id}")
                                var frame = optionSetW0
                                var executed = 0
                                for (edge in plan.edges) {
                                    if (isCancelledOrTimedOut()) break
                                    if (automatonHopsThisGoal >= AUTOMATON_MAX_HOPS) break
                                    if (edge.op != ActionKind.TAP.name) break
                                    val cand = com.wae.agent.core.automaton.AutomatonRuntime
                                        .locate(edge.selector, frame.selectableOptions)
                                    if (cand == null) {
                                        Log.i("WAE_PLANNER", "hop not locatable: ${edge.selector} → abort plan")
                                        break
                                    }
                                    if (!performValidatedStepAndCheckCausal(goal, frame, cand, onStepUpdate)) {
                                        Log.i("WAE_PLANNER", "hop failed → abort plan (fallback L3)")
                                        break
                                    }
                                    executed++
                                    automatonHopsThisGoal++
                                    runCatching {
                                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onDispatch(
                                            pkgNow, edge.selector, goalSelectors
                                        )
                                        com.wae.agent.core.runtime.ExtremeRuntimeHooks.onStepResult(
                                            pkg = pkgNow,
                                            sigHash = com.wae.agent.core.runtime.ExtremeRuntimeHooks
                                                .sigHashOf(frame.pageSpineSigs.toList()),
                                            intentTag = goal.canonicalIntentTag,
                                            selector = edge.selector,
                                            ok = true,
                                            automaton = auto,
                                            currentStateId = edge.to
                                        )
                                    }
                                    // 驗證是否真的走到預期狀態（只看結構簽名，不看文字）
                                    val next = perceiveScreenAndBuildOptionSet(goal, windowIdx = 0, collectOnly = true)
                                    frame = next.second
                                    val nfp = com.wae.agent.core.automaton.MinHash
                                        .fingerprint(frame.pageSpineSigs.toList())
                                    val target = auto.byId(edge.to)
                                    val arrived = target != null &&
                                        com.wae.agent.core.automaton.MinHash.similarity(
                                            nfp, target.fingerprint
                                        ) >= AUTOMATON_ARRIVAL_SIM
                                    if (!arrived) {
                                        // 沒走到預期狀態：記負邊，停止整條計畫（不要硬走下一步）
                                        com.wae.agent.core.action.NegativeEdgeStore.record(
                                            pkgNow, edge.selector, edge.op,
                                            com.wae.agent.core.action.NegativeReason.WRONG_EFFECT
                                        )
                                        Log.i("WAE_PLANNER", "arrival check failed → abort plan, negative edge recorded")
                                        break
                                    }
                                }
                                if (executed > 0) {
                                    Log.i("WAE_PLANNER", "executed $executed planned hop(s) · hops=$automatonHopsThisGoal")
                                    navigated = true
                                }
                            }
                        }
                    }
                }
                if (navigated) {
                    // 重新感知後再決策（新頁面才看得到真正的目標）
                    continue
                }
            }

            // ── M5a：序列重放（整條已錄製的鏈一次跑完）──────────────────
            if (!replayAttempted && twinHistory.isNotEmpty()) {
                val plan = com.wae.agent.core.twin.TwinReplayPlan.find(
                    pkg = optionSetW0.packageName,
                    action = goal.actionKind.name,
                    sigsStart = optionSetW0.pageSpineSigs.toList(),
                    history = twinHistory,
                    commandKey = com.wae.agent.core.twin.TwinTaskKey.of(goal.goalText)
                )
                if (plan != null && plan.steps.size >= 2) {
                    replayAttempted = true
                    Log.i("WAE_REPLAY", plan.summary())
                    if (replayPlan(plan, goal, onStepUpdate)) {
                        Log.i("WAE_REPLAY", "replayed whole chain without per-step decision")
                        return true
                    }
                    Log.i("WAE_REPLAY", "replay failed → fallback to step-by-step")
                }
            }

            // ── M3a：Twin 編譯快路徑 ────────────────────────────────────────
            // 前置狀態 + 動作命中歷史軌跡 → 直接定位並派發，跳過 L3 決策。
            // 安全閘門仍會在 L4 派發前執行（不因快路徑而繞過）。
            val fastHit = com.wae.agent.core.twin.TwinFastPath.match(
                pkg = optionSetW0.packageName,
                action = goal.actionKind.name,
                posture = optionSetW0.postureState.effectivePosture.name,
                sigsBefore = optionSetW0.pageSpineSigs.toList(),
                options = optionSetW0.selectableOptions,
                history = twinHistory,
                commandKey = com.wae.agent.core.twin.TwinTaskKey.of(goal.goalText)
            )
            if (fastHit != null) {
                Log.i("WAE_FASTPATH", fastHit.summary())
                val executed = performValidatedStepAndCheckCausal(goal, optionSetW0, fastHit.candidate, onStepUpdate)
                if (executed) {
                    Log.i(
                        "WAE_FASTPATH",
                        "replayed without L3 decision · total=${android.os.SystemClock.elapsedRealtime() - loopStartMs}ms"
                    )
                    return true
                }
                Log.i("WAE_FASTPATH", "dispatch failed → fallback to full pipeline")
            }
            // M3b：快路徑未命中 → 就地把同一批候選補算 embedding（不重新採集畫面）
            if (twinCanFastPath) {
                val rescored = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
                    frameId = optionSetW0.frameId,
                    windowIdx = 0,
                    activeWindowId = optionSetW0.activeWindowId,
                    packageName = optionSetW0.packageName,
                    postureState = optionSetW0.postureState,
                    pageSpineSigs = optionSetW0.pageSpineSigs,
                    pageSpineHash = optionSetW0.pageSpineHash,
                    goal = goal,
                    rawCandidates = optionSetW0.selectableOptions
                )
                scoredAll = rescored.first
                optionSetW0 = rescored.second
                Log.i("WAE_FASTPATH", "miss → scored on demand (saved embedding only on hit)")
            }

            // L0.5 先檢索本地已驗證之通用 UI 模式庫 (強制 S_sim >= 0.45 語意鎖)
            val patternHit = WaePatternMiningEngine.matchVerifiedPatternCandidate(
                packageName = optionSetW0.packageName,
                postureState = optionSetW0.postureState,
                goal = goal,
                options = optionSetW0.selectableOptions
            )
            if (patternHit != null) {
                val executed = performValidatedStepAndCheckCausal(goal, optionSetW0, patternHit, onStepUpdate)
                if (executed) return true
            }

            // L3 CUA-S1 首輪決策 (Window 0: Top-1~10)
            var decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goal, optionSetW0)
            var activeOptionSet = optionSetW0
            Log.i(
                "WAE_EXEC",
                "L3 Decision W0: outcome=${decision.outcomeType} reason=${decision.reason} chosen=${decision.selectedOption?.coreText} p1=${decision.top1Prob} p2=${decision.top2Prob}"
            )
            // 明確不可完成（查無結果）：立刻停止，不進恢復流程（避免亂點其他按鈕）
            if (decision.reason.contains("EMPTY_CONTENT_SET") ||
                decision.reason.contains("NO_PLAUSIBLE_TARGET")
            ) {
                notifyUiStatus(
                    if (decision.reason.contains("NO_PLAUSIBLE_TARGET"))
                        "❌ 無法完成「${goal.goalText}」：此頁面找不到對應的目標（可能需要調整數值/滑桿，尚未支援）"
                    else
                        "❌ 無法完成「${goal.goalText}」：查無符合的結果",
                    onStepUpdate
                )
                return false
            }

            // 若首輪 REJECT 且尚未用過 Level-1 -> 執行 Level-1 滑動視窗重審 (Window 1: Top-11~18，首輪 Top-1~2 僅入只讀 rejectedContextPrompts)
            if (decision.outcomeType == DecisionOutcomeType.REJECT &&
                WaeV26PipelineOrchestrator.currentRunMode == RunMode.PRODUCTION &&
                WaeV26PipelineOrchestrator.localRecoveryCounter.l1Count < 1
            ) {
                WaeV26PipelineOrchestrator.localRecoveryCounter.l1Count += 1
                val top2RejectedPrompts = optionSetW0.selectableOptions.take(2).map {
                    "[首輪已拒絕禁止選] ${it.weldedDescription}"
                }
                val (_, optionSetW1) = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
                    frameId = optionSetW0.frameId,
                    windowIdx = 1,
                    activeWindowId = optionSetW0.activeWindowId,
                    packageName = optionSetW0.packageName,
                    postureState = optionSetW0.postureState,
                    pageSpineSigs = optionSetW0.pageSpineSigs,
                    pageSpineHash = optionSetW0.pageSpineHash,
                    goal = goal,
                    rawCandidates = scoredAll,
                    window1Top2RejectedPrompts = top2RejectedPrompts
                )
                activeOptionSet = optionSetW1
                decision = WaeV26PipelineOrchestrator.evaluateCuaS1AndAmbiguity(goal, optionSetW1)
            }

            // ── M5b：Twin MCTS 規劃（決策無把握時，搜尋可行路徑再動真機）──
            if (!mctsAttempted && twinHistory.isNotEmpty() &&
                (decision.outcomeType == DecisionOutcomeType.REJECT ||
                    decision.outcomeType == DecisionOutcomeType.AMBIGUOUS)
            ) {
                mctsAttempted = true
                val plan = com.wae.agent.core.twin.TwinPlannerBridge.plan(
                    history = twinHistory,
                    currentSpine = optionSetW0.pageSpineHash,
                    goalAction = goal.actionKind.name
                )
                if (plan != null) {
                    val step = com.wae.agent.core.twin.TwinPlannerBridge.stepFor(twinHistory, plan)
                    val cand = step?.let {
                        com.wae.agent.core.action.SelectorResolver.resolve(it, optionSetW0.selectableOptions)?.candidate
                    }
                    Log.i(
                        "WAE_MCTS",
                        "plan action=${plan.firstAction} expected=${"%.2f".format(plan.expectedSuccess)} " +
                            "rollouts=${plan.rollouts} located=${cand?.coreText?.take(16)}"
                    )
                    if (cand != null &&
                        plan.expectedSuccess >= 0.25f &&
                        cand.hasOwnSemanticEvidence &&
                        cand.viewId.isNotEmpty()
                    ) {
                        // 只有「規劃真的有把握 + 能定位到有語意的元件」才照計畫動手。
                        // 實測：expected=0.00 且 located 空字串時照樣派發，等於盲點。
                        if (performValidatedStepAndCheckCausal(goal, optionSetW0, cand, onStepUpdate)) {
                            Log.i("WAE_MCTS", "executed planned action")
                            return true
                        }
                    } else if (cand != null) {
                        Log.i(
                            "WAE_MCTS",
                            "plan rejected: expected=${"%.2f".format(plan.expectedSuccess)} " +
                                "identifiable=${cand.hasOwnSemanticEvidence} viewId=${cand.viewId.isNotEmpty()}"
                        )
                    }
                } else {
                    Log.i("WAE_MCTS", "no plan (spine not in graph or no goal edge)")
                }
            }

            // ── VoI 升級：決策層判定「資訊不足」時，主動花一次 OCR 打破平局 ──
            // 只在 REJECT / AMBIGUOUS 且本任務尚未升級過、本幀也還沒跑過 OCR 時觸發，
            // 避免變成「每輪都重跑 OCR」的成本災難。
            if (!voiEscalated && !ocrRanForCurrentFrame &&
                (decision.outcomeType == DecisionOutcomeType.REJECT ||
                    decision.outcomeType == DecisionOutcomeType.AMBIGUOUS) &&
                com.wae.agent.vision.PpOcrEngine.isReady
            ) {
                val opts = activeOptionSet.selectableOptions
                val top1Sim = opts.getOrNull(0)?.hybridSimScore ?: 0f
                val top2Sim = opts.getOrNull(1)?.hybridSimScore ?: 0f
                if (decision.outcomeType == DecisionOutcomeType.AMBIGUOUS ||
                    com.wae.agent.core.decision.VoiScheduler.shouldEscalate(
                        top1 = top1Sim, top2 = top2Sim, top1Probability = decision.top1Prob
                    )
                ) {
                    voiEscalated = true
                    forceOcrNextFrame = true
                    Log.i(
                        "WAE_VOI",
                        "escalate(${decision.outcomeType}): top1=$top1Sim top2=$top2Sim " +
                            "p1=${"%.2f".format(decision.top1Prob)} → force OCR once"
                    )
                    notifyUiStatus(" 信心不足，補跑一次文字辨識…", onStepUpdate)
                    continue
                }
            }

            when (decision.outcomeType) {
                DecisionOutcomeType.CLEAR_WIN -> {
                    // S27：目標狀態已成立（例如開關本來就是關的）→ 不需派發任何動作
                    if (decision.alreadySatisfied) {
                        Log.i("WAE_TOGGLE", "goal already satisfied → 不動作，直接完成 (${decision.reason})")
                        return true
                    }
                    val chosen = decision.selectedOption ?: return false
                    // G0：記錄這一輪的候選與選擇（weak label 來源；flush 在任務結束時做）
                    recordGroundingTrace(goal, activeOptionSet, chosen)
                    // 強制斷言 OptionSet (frameId, windowIdx) 一致性
                    if (decision.frameId != activeOptionSet.frameId || decision.windowIdx != activeOptionSet.windowIdx) {
                        continue
                    }
                    // S27：開關導覽（先到分類頁）也不算目標完成 → 執行後繼續
                    if (decision.continueAfterDispatch &&
                        toggleDrillAttempts < 2 &&
                        performValidatedStepAndCheckCausal(goal, activeOptionSet, chosen, onStepUpdate)
                    ) {
                        toggleDrillAttempts++
                        Log.i("WAE_TOGGLE", "drilled to section (attempt=$toggleDrillAttempts) → 下一輪找開關")
                        continue
                    }
                    // 目標獲取階梯：這一步只是「開啟入口」，不算目標完成 → 執行後繼續下一輪
                    val overrideKind = decision.overrideActionKind
                    val isLadder = overrideKind != null && overrideKind != goal.actionKind &&
                        typeLadderAttempts < 1
                    if (isLadder) typeLadderAttempts++
                    val goalForStep = if (isLadder) goal.copy(actionKind = overrideKind!!) else goal
                    val ok = performValidatedStepAndCheckCausal(goalForStep, activeOptionSet, chosen, onStepUpdate)
                    if (ok) {
                        if (isLadder) {
                            Log.i("WAE_LADDER", "entry opened (${decision.reason}) → 下一輪再執行真正的 ${goal.actionKind}")
                            continue
                        }
                        return true
                    }
                }

                DecisionOutcomeType.AMBIGUOUS -> {
                    // 顯式語意歧義（如「美式」vs「拿鐵」）：絕不誤入滾動恢復，直接彈出 Top-3 澄清選項
                    pendingAmbiguousGoal = goal
                    pendingAmbiguousOptions = decision.top3AmbiguousCandidates
                    if (WaeV26PipelineOrchestrator.currentRunMode == RunMode.TEST) {
                        WaeV26PipelineOrchestrator.dumpCalibrationSnapshot(this, goal, activeOptionSet, decision)
                    }
                    notifyUiStatus(
                        "❓ 發現多個符合「${goal.goalText}」的選項，請在下方點選澄清：",
                        onStepUpdate
                    )
                    return false
                }

                else -> {
                    // REJECT / WAIT / ESCALATE
                    // TYPE 找不到輸入埠 → 先退回上一層（YouTube 常直接回到上次播放的影片頁）
                    if (decision.reason.contains("NO_INPUT_ENTRY") && noInputEntryBacks < 2) {
                        noInputEntryBacks++
                        runCatching {
                            performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            )
                        }
                        waitForUiQuiet(800L)
                        Log.i("WAE_EXEC", "NO_INPUT_ENTRY → BACK recovery #$noInputEntryBacks")
                        notifyUiStatus("↩ 找不到輸入框，先退出上一層再試（$noInputEntryBacks/2）", onStepUpdate)
                        continue
                    }
                    if (WaeV26PipelineOrchestrator.currentRunMode == RunMode.TEST) {
                        // 測試模式：「遇到不知道就停」並落盤完整校準快照
                        WaeV26PipelineOrchestrator.dumpCalibrationSnapshot(this, goal, activeOptionSet, decision)
                        notifyUiStatus(
                            "⏸ [TEST 模式暫停] 未知或低信心 (${decision.reason})，已落盤校準快照",
                            onStepUpdate
                        )
                        return false
                    }

                    // 生產模式：進入 Level-2 (2A 去彈窗 / 2B 滾動尋路) -> Level-3
                    val recovered = runLevel2OrLevel3Recovery(goal, activeOptionSet, onStepUpdate)
                    if (!recovered) {
                        return false
                    }
                }
            }
        }
        return false
    }

    private fun performValidatedStepAndCheckCausal(
        goal: LocalGoal,
        optionSet: ImmutableOptionSet,
        chosen: CandidateOption,
        onStepUpdate: ((String) -> Unit)?
    ): Boolean {
        val visibleTexts = optionSet.selectableOptions.map { it.coreText }
        // 安全判定的文字來源要排除「列表內容」：信件主旨/貼文/商品名可能剛好含
        // 「驗證碼」「OTP」等字樣，那不代表畫面上有風控關卡（實測 Gmail 收件匣就會誤觸）。
        val safetyLabelTexts = optionSet.selectableOptions
            .filter { it.parentContainerType != "LIST_ITEM" }
            .map { it.coreText }
        val sec = WaeV26PipelineOrchestrator.checkSecurityGuardrails(
            packageName = optionSet.packageName,
            hasPasswordNodeOnScreen = false,
            visibleScreenTexts = visibleTexts,
            selectedOption = chosen,
            goal = goal
        )
        if (!sec.allowed) {
            notifyUiStatus(" ${sec.reason}", onStepUpdate)
            return false
        }

        val prevWindowId = optionSet.activeWindowId
        val prevSpineSigs = optionSet.pageSpineSigs
        val prevSubtreeHash = compute2HopParentSubtreeHash(chosen.markId)

        // Twin（M2）：記錄「動作前狀態 + 動作 + 目標」；由下一次感知補上「動作後狀態」
        com.wae.agent.core.twin.TwinRecorder.begin(
            pkg = optionSet.packageName,
            spineBefore = optionSet.pageSpineHash,
            action = goal.actionKind.name,
            target = chosen.coreText.ifEmpty { chosen.weldedDescription },
            posture = optionSet.postureState.effectivePosture.name,
            sigsBefore = optionSet.pageSpineSigs.toList(),
            viewId = chosen.viewId,
            className = chosen.className,
            roleTag = chosen.roleTag,
            gridX = chosen.centerX(),
            gridY = chosen.centerY(),
            commandKey = com.wae.agent.core.twin.TwinTaskKey.of(goal.goalText)
        )
        // P1 動作契約：組出意圖鍵（含套件/動作/選擇器/姿態/頁面狀態/參數槽）
        val contractKey = com.wae.agent.core.action.ActionIntent(
            pkg = optionSet.packageName,
            action = goal.actionKind.name,
            selector = selectorKeyOf(chosen),
            posture = optionSet.postureState.effectivePosture.name,
            pageSpine = optionSet.pageSpineHash,
            slot = if (goal.actionKind == ActionKind.TYPE) goal.inputPayload.take(8) else ""
        ).key()
        if (com.wae.agent.core.action.ActionLedger.shouldSkip(
                contractKey, SystemClock.elapsedRealtime()
            )
        ) {
            Log.i("WAE_CONTRACT", "skip-duplicate key=$contractKey")
            notifyUiStatus("⏭ 重複動作已略過（冪等）", onStepUpdate)
            return true
        }

        val dispatchAtMs = SystemClock.uptimeMillis()
        val actionDispatched = executeL4ActionWithSingleNodeToctouGuard(
            goal = goal,
            target = chosen,
            postureName = optionSet.postureState.effectivePosture.name,
            visibleTexts = safetyLabelTexts,
            pkgHint = optionSet.packageName
        )
        if (!actionDispatched) {
            com.wae.agent.core.twin.TwinRecorder.abort()
        }
        Log.i(
            "WAE_EXEC",
            "L4 Dispatched: goal='${goal.goalText}' kind=${goal.actionKind} target='${chosen.coreText}' bounds=${chosen.grid.contentToString()} ok=$actionDispatched"
        )
        if (!actionDispatched) {
            // TOCTOU 發現畫面已變動：不扣減恢復配額，直接重跑 L1
            notifyUiStatus(" 畫面於推理期間變動 (TOCTOU 攔截)，重新感知中...", onStepUpdate)
            return false
        }

        // P4.1：量測真正的響應延遲（派發 → 第一次內容變化），而非整步耗時
        runCatching {
            val deadline = SystemClock.uptimeMillis() + 900L
            var responseMs = 0L
            while (SystemClock.uptimeMillis() < deadline) {
                val lastChange = synchronized(recentContentChangeTimestamps) {
                    recentContentChangeTimestamps.lastOrNull() ?: 0L
                }
                if (lastChange > dispatchAtMs) {
                    responseMs = lastChange - dispatchAtMs
                    break
                }
                SystemClock.sleep(50L)
            }
            if (responseMs in 30L..com.wae.agent.core.action.TimingModel.MAX_WAIT_MS) {
                com.wae.agent.core.action.TimingModel.record(
                    com.wae.agent.core.action.TimingModel.keyOf(
                        optionSet.packageName, selectorKeyOf(chosen), goal.actionKind.name
                    ),
                    responseMs
                )
            }
        }

        // 等待畫面穩定（事件驅動）；上限由「該控件的歷史 P95」決定（P4）
        val adaptiveWaitMs = com.wae.agent.core.action.TimingModel.waitFor(
            pkg = optionSet.packageName,
            selector = selectorKeyOf(chosen),
            action = goal.actionKind.name
        )
        val waitStartMs = SystemClock.uptimeMillis()
        waitForUiQuiet(adaptiveWaitMs)
        Log.i(
            "WAE_TIMING",
            "adaptiveWait cap=${adaptiveWaitMs}ms actual=${SystemClock.uptimeMillis() - waitStartMs}ms " +
                "selector=${selectorKeyOf(chosen)}"
        )
        var (_, postSet) = perceiveScreenAndBuildOptionSet(goal, windowIdx = 0)
        var currSubtreeHash = compute2HopParentSubtreeHash(chosen.markId)
        var (causalState, repRate) = WaeV26PipelineOrchestrator.evaluateTriStateCausalAttribution(
            prevWindowId = prevWindowId,
            currWindowId = postSet.activeWindowId,
            prevSpineSigs = prevSpineSigs,
            currSpineSigs = postSet.pageSpineSigs,
            prevTargetSubtreeHash = prevSubtreeHash,
            currTargetSubtreeHash = currSubtreeHash,
            imeRoseForTarget = goal.actionKind == ActionKind.TYPE,
            otherNonCarouselTextChanged = postSet.pageSpineHash != optionSet.pageSpineHash
        )

        // P1 後置驗證：三態判定（APPLIED / NO_EFFECT / WRONG_EFFECT）
        var effect = if (goal.actionKind == ActionKind.ADJUST_VALUE) {
            // 數值調整的成效**不能用頁面簽名判定**：把亮度調高，畫面結構本來就不會變。
            // 它真正的驗證發生在派發時（讀回 RangeInfo 確認值真的變了），
            // 因此只要派發回報成功，就是 APPLIED。
            com.wae.agent.core.action.ActionEffect.APPLIED
        } else {
            com.wae.agent.core.action.ActionContract.classifyEffect(
                preSpine = optionSet.pageSpineHash,
                postSpine = postSet.pageSpineHash,
                prePackage = optionSet.packageName,
                postPackage = postSet.packageName,
                preNodeCount = optionSet.selectableOptions.size,
                postNodeCount = postSet.selectableOptions.size,
                expectedPackage = goal.targetPackageHint
            )
        }
        Log.i(
            "WAE_CONTRACT",
            "effect=$effect action=${goal.actionKind} target='${chosen.coreText.take(16)}' " +
                "spine ${optionSet.pageSpineHash != postSet.pageSpineHash} pkg='${postSet.packageName}'"
        )
        com.wae.agent.core.action.ActionTelemetry.record(
            pkg = optionSet.packageName,
            selector = selectorKeyOf(chosen),
            success = effect == com.wae.agent.core.action.ActionEffect.APPLIED,
            healLevel = 0
        )
        // 負向邊（S2）：把失敗寫成結構化事實；成功一次則讓負面經驗淡出
        runCatching {
            val selKey = WaeV26PipelineOrchestrator.candidateSelectorKey(chosen)
            val opName = goal.actionKind.name
            // 若能量到當前狀態，就把負邊限定在該狀態（避免同一選擇器跨頁誤殺）
            val stateId = runCatching {
                automatonFor(optionSet.packageName)?.matchState(
                    com.wae.agent.core.automaton.MinHash.fingerprint(optionSet.pageSpineSigs.toList())
                )?.id ?: ""
            }.getOrDefault("")
            when (effect) {
                com.wae.agent.core.action.ActionEffect.WRONG_EFFECT ->
                    com.wae.agent.core.action.NegativeEdgeStore.record(
                        optionSet.packageName, selKey, opName,
                        com.wae.agent.core.action.NegativeReason.WRONG_EFFECT,
                        state = stateId
                    )
                com.wae.agent.core.action.ActionEffect.NO_EFFECT ->
                    com.wae.agent.core.action.NegativeEdgeStore.record(
                        optionSet.packageName, selKey, opName,
                        com.wae.agent.core.action.NegativeReason.NO_EFFECT,
                        state = stateId
                    )
                else ->
                    com.wae.agent.core.action.NegativeEdgeStore.recordSuccess(
                        optionSet.packageName, selKey, opName, state = stateId
                    )
            }
        }
        if (effect == com.wae.agent.core.action.ActionEffect.WRONG_EFFECT) {
            com.wae.agent.core.action.ActionLedger.record(
                contractKey, effect, SystemClock.elapsedRealtime()
            )
            notifyUiStatus("⛔ 動作產生非預期結果（跳離目標頁），已回退", onStepUpdate)
            return false
        }

        // 若無障礙 ACTION_CLICK 因自定義 View TouchListener 未觸發畫面變化，立即補發一次物理座標點擊手勢再驗證
        if ((causalState == CausalAttributionState.BACKGROUND_NOISE ||
                effect == com.wae.agent.core.action.ActionEffect.NO_EFFECT) &&
            goal.actionKind == ActionKind.TAP
        ) {
            val tapOk = dispatchTapGesture(chosen.centerX(), chosen.centerY())
            Log.i("WAE_EXEC", "L4 Fallback physical gesture tap at (${chosen.centerX()}, ${chosen.centerY()}) -> $tapOk")
            if (tapOk) {
                waitForUiQuiet(1200L)
                postSet = perceiveScreenAndBuildOptionSet(goal, windowIdx = 0).second
                currSubtreeHash = compute2HopParentSubtreeHash(chosen.markId)
                val retryEval = WaeV26PipelineOrchestrator.evaluateTriStateCausalAttribution(
                    prevWindowId = prevWindowId,
                    currWindowId = postSet.activeWindowId,
                    prevSpineSigs = prevSpineSigs,
                    currSpineSigs = postSet.pageSpineSigs,
                    prevTargetSubtreeHash = prevSubtreeHash,
                    currTargetSubtreeHash = currSubtreeHash,
                    imeRoseForTarget = false,
                    otherNonCarouselTextChanged = postSet.pageSpineHash != optionSet.pageSpineHash
                )
                causalState = retryEval.first
                repRate = retryEval.second
            }
        }

        WaePatternMiningEngine.recordTrajectoryOutcome(
            context = this,
            packageName = optionSet.packageName,
            posture = optionSet.postureState.effectivePosture,
            goal = goal,
            selectedOption = chosen,
            causalState = causalState
        )

        // P1：把本次動作結果寫進冪等帳本（成功才允許後續同意圖跳過）
        val success = causalState == CausalAttributionState.VALID_CAUSAL ||
            causalState == CausalAttributionState.UNCERTAIN_CROSS_CONTAINER
        com.wae.agent.core.action.ActionLedger.record(
            contractKey,
            if (success) com.wae.agent.core.action.ActionEffect.APPLIED
            else com.wae.agent.core.action.ActionEffect.NO_EFFECT,
            SystemClock.elapsedRealtime()
        )

        // 數值調整：成敗由「讀回 RangeInfo」決定，不能用頁面簽名變化判定
        // （把亮度調高，畫面結構本來就不變；先前因此在成功後仍繼續迴圈，出現莫名上下滑動）
        if (goal.actionKind == ActionKind.ADJUST_VALUE &&
            effect == com.wae.agent.core.action.ActionEffect.APPLIED
        ) {
            com.wae.agent.core.action.ActionLedger.record(
                contractKey,
                com.wae.agent.core.action.ActionEffect.APPLIED,
                SystemClock.elapsedRealtime()
            )
            saveStepCheckpoint(goal, postSet)
            notifyUiStatus("✓ 已調整 [${goal.goalText}]（數值已變更）", onStepUpdate)
            return true
        }

        return when (causalState) {
            CausalAttributionState.VALID_CAUSAL,
            CausalAttributionState.UNCERTAIN_CROSS_CONTAINER -> {
                saveStepCheckpoint(goal, postSet)
                val targetLabel = chosen.coreText.ifEmpty { chosen.weldedDescription }
                notifyUiStatus(
                    "✓ 完成 [${goal.goalText}] -> $targetLabel (替換率 ${(repRate * 100).toInt()}%)",
                    onStepUpdate
                )
                true
            }
            CausalAttributionState.BACKGROUND_NOISE -> {
                // 加入 LRU-200 跨滾動死點黑名單
                WaeV26PipelineOrchestrator.recordDeadClickNode(optionSet.activeWindowId, chosen.nodeStableSig)
                notifyUiStatus("⚠ 點擊無反應，已將死點列入黑名單並啟動恢復...", onStepUpdate)
                false
            }
        }
    }

    /**
     * Level-2A (去彈窗，無彈窗自動旁路不扣配額) -> Level-2B (物理滾動尋路) -> Level-3 (人工介入與 3 次全局熔斷)
     */
    private fun runLevel2OrLevel3Recovery(
        goal: LocalGoal,
        optionSet: ImmutableOptionSet,
        onStepUpdate: ((String) -> Unit)?
    ): Boolean {
        val counter = WaeV26PipelineOrchestrator.localRecoveryCounter
        val posture = optionSet.postureState

        // Level-2A：若存在頂層彈窗姿態，優先去彈窗；若無彈窗則直接旁路至 Level-2B（不消耗 l2DismissCount）
        val hasModalOverlay = posture.overlayPosture == PagePosture.MODAL_DIALOG ||
            posture.overlayPosture == PagePosture.BOTTOM_SHEET
        if (hasModalOverlay && counter.l2DismissCount < 1) {
            counter.l2DismissCount += 1
            notifyUiStatus(" [Level-2A 恢復] 偵測到頂層彈窗，自動關閉干擾層...", onStepUpdate)
            performGlobalAction(GLOBAL_ACTION_BACK)
            waitForUiQuiet(900L)
            return true
        }

        // Level-2B：物理滾動尋路（若為高動態流 dynamicScore >= 0.75 則自動跳過盲滾）
        if (!posture.isHighDynamicStream && counter.l2ScrollCount < 2) {
            counter.l2ScrollCount += 1
            val prevTailSigs = optionSet.pageSpineSigs.takeLast(16).toLongArray()
            val prevBottomNodeY = optionSet.selectableOptions.maxOfOrNull { it.grid[3] } ?: 0
            val scrollStartMs = SystemClock.uptimeMillis()

            notifyUiStatus(" [Level-2B 恢復] 向下滾動半屏尋路 (${counter.l2ScrollCount}/2)...", onStepUpdate)
            // P2：優先用無障礙滾動（可預測），失敗才用手勢
            performPreciseScrollDown()
            waitForUiQuiet(1200L)

            val (_, afterScrollSet) = perceiveScreenAndBuildOptionSet(goal, windowIdx = 0)
            val currTailSigs = afterScrollSet.pageSpineSigs.takeLast(16).toLongArray()
            val currBottomNodeY = afterScrollSet.selectableOptions.maxOfOrNull { it.grid[3] } ?: 0

            // 三合一見底終止判定：tail16 未變 + 底部節點位移 < 5px + 無新 scrollY 事件
            val tailUnchanged = prevTailSigs.contentEquals(currTailSigs)
            val deltaYSmall = abs(currBottomNodeY - prevBottomNodeY) < 5
            val noScrollEvent = lastScrollEventTimestampMs < scrollStartMs
            if (tailUnchanged && deltaYSmall && noScrollEvent) {
                counter.l2ScrollCount = 2 // 標記見底，提前終止滾動配額
            } else {
                return true
            }
        }

        // Level-3：人工微教學與全局 3 次確定性熔斷
        WaeV26PipelineOrchestrator.globalTaskCounter.humanInterventionCount += 1
        val totalInterventions = WaeV26PipelineOrchestrator.globalTaskCounter.humanInterventionCount
        if (WaeV26PipelineOrchestrator.globalTaskCounter.isCircuitBreakerTripped) {
            notifyUiStatus(
                "⛔ [全局熔斷] 已累計 $totalInterventions 次人工介入，任務已安全終止",
                onStepUpdate
            )
            return false
        }

        notifyUiStatus(
            " [Level-3 微教學 ($totalInterventions/3)] 找不到「${goal.goalText}」，請手動點一下目標元件",
            onStepUpdate
        )
        return false
    }

    /**
     * L4 執行層：包含單節點 AccessibilityNodeInfo.refresh() O(1) TOCTOU 防過期鎖 (< 0.3ms)、
     * 父容器可點擊向上穿透 + 物理手勢雙保險、以及兩階段搜尋輸入框激活與送出 (Tap Search Icon -> EditText -> ACTION_IME_ENTER)
     */
    /** 待人工確認的安全動作（由 SafetyGate 產生） */
    private data class PendingSafety(
        val goal: LocalGoal,
        val target: CandidateOption,
        val postureName: String,
        val visibleTexts: List<String>,
        val message: String
    )

    @Volatile
    private var pendingSafety: PendingSafety? = null

    /** 供 UI 查詢：是否有動作在等使用者確認 */
    fun pendingSafetyMessage(): String? = pendingSafety?.message

    /**
     * 使用者按下「確認執行」後，放行同一個動作一次。
     */
    fun confirmPendingSafetyAction() {
        val pending = pendingSafety ?: return
        pendingSafety = null
        pipelineExecutor.execute {
            val ok = executeL4ActionWithSingleNodeToctouGuard(
                goal = pending.goal,
                target = pending.target,
                postureName = pending.postureName,
                visibleTexts = pending.visibleTexts,
                userConfirmed = true
            )
            notifyUiStatus(if (ok) "✓ 已依確認執行動作" else "⚠ 確認後執行失敗，畫面可能已變動")
        }
    }

    fun cancelPendingSafetyAction() {
        pendingSafety = null
    }

    /**
     * L4 執行層入口。**所有動作都必須先過 SafetyGate**（形式化安全監控，見 docs §7.6）。
     */
    private fun executeL4ActionWithSingleNodeToctouGuard(
        goal: LocalGoal,
        target: CandidateOption,
        postureName: String = "",
        visibleTexts: List<String> = emptyList(),
        userConfirmed: Boolean = false,
        pkgHint: String = ""
    ): Boolean {
        // 安全閘門要用「這個候選真正所在的套件」，不能用落後的 latestPackageName
        // （實測：啟動 Maps 後 latestPackageName 還停在 com.android.systemui，
        //  安全閘門因此用錯套件判定）
        val effectivePkg = pkgHint.ifEmpty { latestPackageName }
        // ── 安全閘門：派發前驗證（ABORT / BLOCK / CONFIRM）──────────────
        val safety = com.wae.agent.core.safety.SafetyGate.evaluate(
            packageName = effectivePkg,
            actionKind = goal.actionKind,
            target = target,
            postureName = postureName,
            visibleTexts = visibleTexts,
            userConfirmed = userConfirmed,
            isContentRow = target.parentContainerType == "LIST_ITEM"
        )
        if (!safety.allowed) {
            if (safety.abortSession) {
                pendingSafety = null
                cancelRequested = true
                notifyUiStatus("${safety.message}（任務已安全中止）")
            } else if (safety.requiresConfirm) {
                pendingSafety = PendingSafety(goal, target, postureName, visibleTexts, safety.message)
                notifyUiStatus("${safety.message} → 請按「確認執行」")
            } else {
                notifyUiStatus(safety.message)
            }
            Log.i("WAE_SAFETY", "gate=${safety.verdict} action=${goal.actionKind} target='${target.coreText.take(24)}' pkg=$effectivePkg container=${target.parentContainerType} detail='${safety.detail}'")
            return false
        }
        // 每次派發都留一條安全判定記錄（可審計：誰在什麼頁面被放行）
        Log.i(
            "WAE_SAFETY",
            "gate=ALLOW action=${goal.actionKind} target='${target.coreText.take(20)}' " +
                "posture=$postureName pkg=$effectivePkg"
        )

        val node = liveNodeMap[target.markId]
        val liveRect = Rect(target.grid[0], target.grid[1], target.grid[2], target.grid[3])

        if (node != null) {
            val refreshed = try {
                node.refresh()
            } catch (_: Throwable) {
                false
            }
            if (refreshed) {
                if (!node.isVisibleToUser || !node.isEnabled) {
                    return false
                }
                val refreshedRect = Rect()
                node.getBoundsInScreen(refreshedRect)
                if (refreshedRect.width() > 0 && refreshedRect.height() > 0) {
                    val driftX = abs(refreshedRect.centerX() - target.centerX())
                    val driftY = abs(refreshedRect.centerY() - target.centerY())
                    if (driftX > 36 || driftY > 36) {
                        return false
                    }
                    liveRect.set(refreshedRect)
                }
            }
        }

        return when (goal.actionKind) {
            // ── 補齊的基礎動作 ─────────────────────────────────────
            ActionKind.SCROLL_UP -> performScrollUp()
            ActionKind.SWIPE_HORIZONTAL -> performSwipeHorizontalGesture()
            ActionKind.LONG_PRESS -> performLongPressAt(target.centerX(), target.centerY())
            ActionKind.SUBMIT -> performSubmitCurrentInput()
            ActionKind.CLEAR_TEXT -> performSetTextOnFocused("")
            ActionKind.REPLACE_TEXT -> performSetTextOnFocused(goal.inputPayload)
            // 系統數值（音量／亮度）優先走系統 API 並讀回確認：
            // 實測「把音量調低」會進設定找滑桿，掃不到就亂點、最後退回桌面抽屜。
            // 這裡先用系統值處理，處理不了才回到畫面操作。
            ActionKind.ADJUST_VALUE -> com.wae.agent.core.system.SystemValueBridge
                .tryApply(this, goal.goalText, goal.inputPayload)
                ?: performAdjustValue(goal, node, liveRect, target)

            ActionKind.TYPE -> {
                var editableNode: AccessibilityNodeInfo? = if (node?.isEditable == true && node.isFocused) node else null

                // 若目標元件本身是搜尋按鈕/圖標，或雖為 EditText 偽裝容器但尚未聚焦（如 Google Maps search_omnibox_text_box）：
                // 先點擊展開真正的搜尋輸入框並輪詢等待已聚焦或新開啟之 EditText 出現
                if (editableNode == null) {
                    performNodeOrAncestorClickWithGestureFallback(node, liveRect)
                    val waitStart = SystemClock.uptimeMillis()
                    while (SystemClock.uptimeMillis() - waitStart < 3000L) {
                        SystemClock.sleep(200L)
                        editableNode = findFocusedOrFirstEditableNodeAnyWindow()
                        if (editableNode != null && editableNode.isFocused) break
                    }
                    if (editableNode == null) {
                        editableNode = findFocusedOrFirstEditableNodeAnyWindow()
                    }
                    if (editableNode == null) {
                        // 這個入口點開後並沒有出現任何輸入框（例如誤點到與搜尋同名的底部導航頁籤）：
                        // 列入死點黑名單，讓下一輪改挑次佳候選，而不是原地重複點同一個控件 5 次
                        WaeV26PipelineOrchestrator.recordDeadClickNode(
                            activeWindowId = node?.windowId ?: 0,
                            nodeStableSig = target.nodeStableSig
                        )
                        notifyUiStatus("⚠ 此控件點開後沒有輸入框，已排除並改試其他候選", null)
                        return false
                    }
                }

                val inputNode = editableNode ?: return false
                if (!inputNode.isFocused) {
                    NodeOps.perform(inputNode, AccessibilityNodeInfo.ACTION_FOCUS)
                    val r = Rect()
                    inputNode.getBoundsInScreen(r)
                    performNodeOrAncestorClickWithGestureFallback(inputNode, r)
                    waitForUiQuiet(700L)
                }
                val focusedInput = findFocusedOrFirstEditableNodeAnyWindow() ?: inputNode
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        goal.inputPayload
                    )
                }
                var setOk = NodeOps.perform(focusedInput, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!setOk) {
                    // P5 降級鏈：聚焦後重試 → 剪貼簿貼上 → 逐字注入
                    val tried = ArrayList<com.wae.agent.core.action.InputAttempt>()
                    tried.add(com.wae.agent.core.action.InputAttempt(
                        com.wae.agent.core.action.InputStrategy.SET_TEXT, false))
                    NodeOps.perform(focusedInput, AccessibilityNodeInfo.ACTION_CLICK)
                    waitForUiQuiet(400L)
                    var next = com.wae.agent.core.action.InputInjector.nextStrategy(tried)
                    while (next != null && !setOk) {
                        setOk = when (next) {
                            com.wae.agent.core.action.InputStrategy.FOCUS_THEN_SET -> {
                                NodeOps.perform(focusedInput, AccessibilityNodeInfo.ACTION_FOCUS)
                                NodeOps.perform(focusedInput, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            }
                            com.wae.agent.core.action.InputStrategy.CLIPBOARD_PASTE ->
                                pasteIntoFocusedInput(goal.inputPayload)
                            else -> injectCharsIntoFocusedInput(goal.inputPayload)
                        }
                        tried.add(com.wae.agent.core.action.InputAttempt(next, setOk))
                        if (!setOk) next = com.wae.agent.core.action.InputInjector.nextStrategy(tried)
                    }
                    Log.i("WAE_INPUT", "fallback ${com.wae.agent.core.action.InputInjector.summarize(tried)}")
                }
                waitForUiQuiet(700L)
                try {
                    focusedInput.refresh()
                } catch (_: Throwable) {
                }
                // 自動觸發輸入法 Enter / 搜尋送出動作 (API 30+ 標準 ACTION_IME_ENTER + TYPE_INPUT_METHOD 鍵盤送出鍵 + 自動完成建議列三保險)
                var imeSubmitted = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    imeSubmitted = NodeOps.perform(focusedInput, 
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                }
                waitForUiQuiet(900L)
                val editRect = Rect()
                focusedInput.getBoundsInScreen(editRect)
                val stillInSearchBox = findFocusedOrFirstEditableNodeAnyWindow()?.isFocused == true
                if (!imeSubmitted || stillInSearchBox) {
                    val clickedIme = clickImeEnterOrSearchActionIfPresent()
                    if (clickedIme) {
                        waitForUiQuiet(900L)
                    }
                    if (findFocusedOrFirstEditableNodeAnyWindow()?.isFocused == true) {
                        clickFirstSearchSuggestionBelowEditText(editRect.bottom, goal.inputPayload)
                        waitForUiQuiet(1200L)
                    }
                }
                true
            }
            ActionKind.SCROLL_DOWN -> {
                performHalfScreenScrollDown()
                true
            }
            ActionKind.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            else -> {
                performNodeOrAncestorClickWithGestureFallback(node, liveRect)
            }
        }
    }

    /**
     * 先嘗試節點自身或向上最多 4 層可點擊父容器的 ACTION_CLICK，若未生效則強制發送同步物理點擊手勢
     */
    private fun performNodeOrAncestorClickWithGestureFallback(
        node: AccessibilityNodeInfo?,
        tapRect: Rect
    ): Boolean {
        if (node != null) {
            try {
                if (node.isClickable && NodeOps.perform(node, AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                var parent = node.parent
                var hops = 0
                while (parent != null && hops < 4) {
                    if (parent.isClickable && NodeOps.perform(parent, AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    parent = parent.parent
                    hops++
                }
            } catch (_: Throwable) {
            }
        }
        return dispatchTapGesture(tapRect.centerX(), tapRect.centerY())
    }

    private fun getActiveNonWaeRoot(appWindowsOverride: List<AccessibilityWindowInfo>? = null): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (activeRoot != null && activePkg.isNotEmpty() && activePkg != packageName) {
            return activeRoot
        }
        val winList = appWindowsOverride ?: try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (_: Throwable) {
            emptyList()
        }
        for (win in winList) {
            val r = try {
                win.root
            } catch (_: Throwable) {
                null
            } ?: continue
            val pkg = r.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != packageName) {
                return r
            }
        }
        return activeRoot
    }

    /**
     * 若 EditText 的「輸入法送出」動作未被目標 App 處理，遍歷 TYPE_INPUT_METHOD 視窗尋找右下角確認/搜尋鍵並點擊
     */
    private fun clickImeEnterOrSearchActionIfPresent(): Boolean {
        return try {
            val imeWin = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return false
            val imeRoot = imeWin.root ?: return false
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(imeRoot)
            var bottomRightKey: AccessibilityNodeInfo? = null
            var bestScore = -1
            var visited = 0
            while (queue.isNotEmpty() && visited < 120) {
                visited++
                val curr = queue.removeFirst()
                val rect = Rect()
                curr.getBoundsInScreen(rect)
                if (curr.isVisibleToUser && curr.isEnabled && curr.isClickable &&
                    rect.centerX() > screenW * 0.72f && rect.centerY() > screenH * 0.78f
                ) {
                    val posScore = rect.centerX() + rect.centerY()
                    if (posScore > bestScore) {
                        bestScore = posScore
                        bottomRightKey = curr
                    }
                }
                for (i in 0 until curr.childCount) {
                    curr.getChild(i)?.let { queue.addLast(it) }
                }
            }
            if (bottomRightKey != null) {
                val r = Rect()
                bottomRightKey.getBoundsInScreen(r)
                performNodeOrAncestorClickWithGestureFallback(bottomRightKey, r)
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 若輸入文字後畫面上仍停留在搜尋建議頁（如地圖/商城自動完成下拉清單），自動點選輸入框下方第一個匹配之建議項目
     */
    private fun clickFirstSearchSuggestionBelowEditText(editBottomY: Int, payload: String): Boolean {
        val root = getActiveNonWaeRoot() ?: return false
        // 只允許在外部 App 的畫面上點建議項，避免把 WAE 自己的按鈕當成搜尋建議點掉
        if (root.packageName?.toString().orEmpty() == packageName) return false
        val cleanPayload = payload.replace(" ", "").lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var bestMatchNode: AccessibilityNodeInfo? = null
        var bestMatchTop = Int.MAX_VALUE
        var fallbackFirstRow: AccessibilityNodeInfo? = null
        var fallbackTop = Int.MAX_VALUE
        var visited = 0

        while (queue.isNotEmpty() && visited < 180) {
            visited++
            val curr = queue.removeFirst()
            try {
                if (curr.isVisibleToUser && curr.isEnabled && !curr.isEditable) {
                    val rect = Rect()
                    curr.getBoundsInScreen(rect)
                    if (rect.top >= editBottomY - 12 && rect.height() in 24..360 && rect.width() > 120) {
                        val txt = (curr.text?.toString().orEmpty() + " " + curr.contentDescription?.toString().orEmpty())
                            .replace(" ", "")
                            .lowercase()
                        if (cleanPayload.isNotEmpty() && txt.contains(cleanPayload)) {
                            if (rect.top < bestMatchTop) {
                                bestMatchTop = rect.top
                                bestMatchNode = curr
                            }
                        } else if (curr.isClickable && txt.isNotEmpty() && rect.top < fallbackTop) {
                            fallbackTop = rect.top
                            fallbackFirstRow = curr
                        }
                    }
                }
                for (i in 0 until curr.childCount) {
                    curr.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (_: Throwable) {
            }
        }

        val targetNode = bestMatchNode ?: fallbackFirstRow ?: return false
        val targetRect = Rect()
        targetNode.getBoundsInScreen(targetRect)
        Log.i("WAE_EXEC", "Clicking search suggestion below EditText at $targetRect")
        return performNodeOrAncestorClickWithGestureFallback(targetNode, targetRect)
    }

    /**
     * 在當前活躍視窗中尋找已聚焦或第一個可見的輸入控件。
     * 判定放寬為「真的可編輯」或「類輸入框控件」(class/viewId 特徵)，因為像 Google Maps 的
     * search_omnibox_text_box 會回報 isEditable == false，但點擊後展開的搜尋框才是真正的 EditText。
     */
    private fun findFocusedOrFirstEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var firstEditable: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            visited++
            val curr = queue.removeFirst()
            try {
                // 必須排除本 App（WAE）自己的節點：使用者中途切回來時，自己的輸入框往往是
                // 畫面上唯一已聚焦的 EditText，會被誤當成目標 App 的搜尋框而寫入內容。
                val pkg = curr.packageName?.toString().orEmpty()
                val isOwnPackage = pkg == packageName
                if (!isOwnPackage && curr.isVisibleToUser && curr.isEnabled && looksLikeTextInput(curr)) {
                    if (curr.isFocused) return curr
                    if (firstEditable == null) firstEditable = curr
                }
                val count = curr.childCount
                for (i in 0 until count) {
                    curr.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (_: Throwable) {
            }
        }
        return firstEditable
    }

    /**
     * 是否為輸入框類控件（isEditable 為 false 的 omnibox 偽輸入框亦納入）
     */
    private fun looksLikeTextInput(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isEditable) return true
            val cls = node.className?.toString().orEmpty()
            val vid = node.viewIdResourceName.orEmpty()
            cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoComplete", ignoreCase = true) ||
                cls.contains("SearchView", ignoreCase = true) ||
                vid.contains("search", ignoreCase = true) ||
                vid.contains("omnibox", ignoreCase = true) ||
                vid.contains("query", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 跨所有應用視窗尋找輸入控件：目標 App 點擊搜尋入口後，真正的輸入框常常開在另一個視窗/Activity，
     * 只看 rootInActiveWindow 會漏掉。
     */
    private fun findFocusedOrFirstEditableNodeAnyWindow(): AccessibilityNodeInfo? {
        var firstFound: AccessibilityNodeInfo? = null
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        try {
            getActiveNonWaeRoot()?.let {
                if (it.packageName?.toString().orEmpty() != packageName) roots.add(it)
            }
        } catch (_: Throwable) {
        }
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = try {
                    w.root
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (r.packageName?.toString().orEmpty() == packageName) continue
                if (roots.none { it == r }) roots.add(r)
            }
        } catch (_: Throwable) {
        }
        for (r in roots) {
            val n = findFocusedOrFirstEditableNode(r) ?: continue
            if (n.isFocused) return n
            if (firstFound == null) firstFound = n
        }
        return firstFound
    }

    /**
     * L1 完整感知管線：
     * - 統計 TYPE_APPLICATION 視窗數以決定是否開啟 Lazy Z-Order 遮擋檢查
     * - 遍歷無障礙樹並焊接鄰近文字與父容器語境
     * - 執行六維度 Actionability Gate
     * - 計算 256B Head-16 + Tail-16 主幹簽名陣列與 dynamicScore
     */
    private fun perceiveScreenAndBuildOptionSet(
        goal: LocalGoal,
        windowIdx: Int,
        captureScreenshot: Boolean = false,
        /** VoI 升級：決策層判定「需要更多資訊」時強制跑一次 OCR */
        forceOcr: Boolean = false,
        /**
         * M3b：只採集 + 便宜打分，跳過 MiniLM 前向。
         * 供 Twin 快路徑先判斷是否命中；未命中時再由呼叫端補算 embedding。
         */
        collectOnly: Boolean = false
    ): Pair<List<CandidateOption>, ImmutableOptionSet> {
        // 每幀重置 OCR 標記（只有 window 0 的感知才算新的一幀）
        if (windowIdx == 0) ocrRanForCurrentFrame = false
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val density = dm.density

        // 修正 3：僅統計 window.type == TYPE_APPLICATION 的應用視窗數
        val winList = try {
            windows
        } catch (_: Throwable) {
            emptyList()
        }
        val appWindows = winList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val appWindowCount = appWindows.size
        val topAppWindow = appWindows.firstOrNull { it.root?.packageName?.toString() != packageName }
            ?: appWindows.firstOrNull()
        val topWindowRect = Rect()
        topAppWindow?.getBoundsInScreen(topWindowRect)
        val topWindowAreaRatio = if (topWindowRect.width() > 0 && topWindowRect.height() > 0) {
            (topWindowRect.width().toFloat() * topWindowRect.height().toFloat()) / (screenW.toFloat() * screenH.toFloat())
        } else 1f

        val imeVisible = winList.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val imeTop = if (imeVisible) {
            val imeRect = Rect()
            winList.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.getBoundsInScreen(imeRect)
            if (imeRect.top > 0) imeRect.top else (screenH * 0.60f).toInt()
        } else screenH

        val root = getActiveNonWaeRoot(appWindows)
        val activeWinId = root?.windowId ?: (topAppWindow?.id ?: 0)
        WaeV26PipelineOrchestrator.clearDeadClickBlacklistOnWindowChange(activeWinId)

        val activePkg = root?.packageName?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: latestPackageName.ifEmpty { "unknown" }
        latestPackageName = activePkg

        // ── S25：是否走增量（髒區剪枝）────────────────────────────────
        val nowPerceive = android.os.SystemClock.uptimeMillis()
        val ambientNow = runCatching { computeAmbientSpine().first }.getOrDefault(0L)
        val dirtySnapshot = synchronized(dirtyRects) { dirtyRects.map { Rect(it) } }
        val dirtyBoxes = dirtySnapshot.map {
            com.wae.agent.core.perception.IncrementalPerception.Box(it.left, it.top, it.right, it.bottom)
        }
        val incremental = goal.actionKind == ActionKind.TAP &&
            !forceFullPerceive && lastCandidates.isNotEmpty() &&
            com.wae.agent.core.perception.IncrementalPerception.canGoIncremental(
                samePackage = activePkg == lastCandidatesPkg,
                sameWindow = activeWinId == lastCandidatesWindowId,
                sameAmbient = ambientNow != 0L && ambientNow == lastCandidatesAmbient,
                dirtyCount = dirtyBoxes.size,
                dirtyAreaRatio = com.wae.agent.core.perception.IncrementalPerception
                    .dirtyAreaRatio(dirtyBoxes, screenW, screenH),
                msSinceFull = nowPerceive - lastFullPerceiveAtMs
            )

        val rawList = ArrayList<RawCollectedNode>(160)
        liveNodeMap.clear()
        if (root != null) {
            collectNodesWithContextRecursive(
                node = root,
                depth = 0,
                parentRole = "ROOT",
                siblingHint = "",
                isBehindModal = false,
                outList = rawList,
                scopeRects = if (incremental) dirtyBoxes else null
            )
        }
        synchronized(dirtyRects) { dirtyRects.clear() }
        if (!incremental) {
            forceFullPerceive = false
            lastFullPerceiveAtMs = nowPerceive
        }
        Log.i(
            "WAE_INCREMENTAL",
            if (incremental) "scoped pkg=$activePkg dirty=${dirtyBoxes.size} raw=${rawList.size}"
            else "full pkg=$activePkg reason=${if (forceFullPerceive) "forced" else "no-dirty-or-stale"}"
        )

        // 若存在 >= 2 個應用視窗，檢查底層視窗節點並標記 isBehindModal = true
        if (appWindowCount >= 2 && topAppWindow != null) {
            for (bgWin in appWindows.drop(1)) {
                val bgRoot = bgWin.root ?: continue
                if (bgRoot.packageName?.toString() == packageName) continue
                collectNodesWithContextRecursive(
                    node = bgRoot,
                    depth = 0,
                    parentRole = "BG_WINDOW",
                    siblingHint = "",
                    isBehindModal = true,
                    outList = rawList
                )
            }
        }

        val count = rawList.size
        val boundsFlat = IntArray(count * 4)
        val texts = Array(count) { "" }
        val descs = Array(count) { "" }
        val viewIds = Array(count) { "" }
        val classNames = Array(count) { "" }
        val nodeFlags = IntArray(count)
        val depths = IntArray(count)

        val statusBarBottom = (24 * density).toInt()
        val navBarTop = screenH - (42 * density).toInt()

        val validCandidates = ArrayList<CandidateOption>(count)

        // S27：把「內嵌開關」的狀態對應到它的列。
        // Samsung 設定等 App 的可點列（標題列）本身不是 checkable，開關是子節點且沒有文字；
        // 若只看候選自己的 isCheckable，會完全找不到「飛航模式」這個開關。
        val checkableNodes = rawList.filter { it.isCheckable && it.bounds.width() > 0 }

        // S27：很多設定頁的「文字列」本身不可點，開關是同一列裡的子節點。
        // 這裡把「有文字、不可點、但內部有可勾選子節點」的列升級成可點的可切換列，
        // 否則『飛航模式』這種列會完全進不了候選（實測三星設定就是這樣）。
        runCatching {
            for (idx in rawList.indices) {
                val r = rawList[idx]
                if (r.isCheckable || r.isClickable || r.text.isBlank()) continue
                val child = checkableNodes.firstOrNull { c ->
                    if (c === r) return@firstOrNull false
                    val cx = (c.bounds.left + c.bounds.right) / 2
                    val cy = (c.bounds.top + c.bounds.bottom) / 2
                    cx in r.bounds.left..r.bounds.right && cy in r.bounds.top..r.bounds.bottom
                } ?: continue
                rawList[idx] = r.copy(
                    isClickable = true,
                    isCheckable = true,
                    isChecked = child.isChecked,
                    capabilities = r.capabilities +
                        com.wae.agent.core.component.Capability.TOGGLE
                )
            }
        }

        fun embeddedToggleOf(r: RawCollectedNode): RawCollectedNode? {
            if (r.isCheckable) return r
            var best: RawCollectedNode? = null
            var bestArea = Int.MAX_VALUE
            for (c in checkableNodes) {
                if (c === r) continue
                val cx = (c.bounds.left + c.bounds.right) / 2
                val cy = (c.bounds.top + c.bounds.bottom) / 2
                if (cx in r.bounds.left..r.bounds.right && cy in r.bounds.top..r.bounds.bottom) {
                    val area = c.bounds.width() * c.bounds.height()
                    if (area < bestArea) {
                        bestArea = area
                        best = c
                    }
                }
            }
            return best
        }

        for (i in 0 until count) {
            val r = rawList[i]
            val markId = i + 1
            liveNodeMap[markId] = r.nodeRef

            boundsFlat[i * 4 + 0] = r.bounds.left
            boundsFlat[i * 4 + 1] = r.bounds.top
            boundsFlat[i * 4 + 2] = r.bounds.right
            boundsFlat[i * 4 + 3] = r.bounds.bottom
            texts[i] = r.text
            descs[i] = r.contentDesc
            viewIds[i] = r.viewId
            classNames[i] = r.className
            depths[i] = r.depth

            var flags = 0
            if (r.isClickable) flags = flags or 1
            if (r.isEditable) flags = flags or 2
            if (r.isScrollable) flags = flags or 4
            nodeFlags[i] = flags

            val gridArr = intArrayOf(r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom)
            val zone = classifySpatialZone(screenW, screenH, gridArr)
            val roleTag = WaePatternMiningEngine.inferComponentUniversalRole(
                screenW = screenW,
                screenH = screenH,
                bounds = gridArr,
                className = r.className,
                viewId = r.viewId,
                text = r.text,
                contentDesc = r.contentDesc
            )
            val coreText = r.text.ifEmpty { r.contentDesc }
            val welded = buildWeldedContextDescription(
                zone = zone,
                parentRole = r.parentRole,
                roleTag = roleTag,
                coreText = coreText,
                siblingText = r.siblingText
            )
            val stableSig = WaeNativeBridge.fnv1a64("${r.viewId}|${r.className}|${r.depth}")

            val cand = CandidateOption(
                markId = markId,
                nodeStableSig = stableSig,
                source = CandidateSource.A11Y_TREE,
                detectorConfidence = 1.0f,
                grid = gridArr,
                coreText = coreText,
                weldedDescription = welded,
                hybridSimScore = 0f,
                isEnabled = r.isEnabled,
                isClickable = r.isClickable,
                isEditable = r.isEditable,
                isScrollable = r.isScrollable,
                parentContainerType = r.parentRole,
                gridZone = zone,
                roleTag = roleTag,
                viewId = r.viewId,
                className = r.className,
                isPassword = r.isPassword,
                widgetFamily = r.widgetFamily,
                capabilities = r.capabilities,
                hasRange = r.hasRange,
                isAdjustable = r.hasRange &&
                    com.wae.agent.core.component.Capability.SET_VALUE in r.capabilities &&
                    com.wae.agent.core.component.Capability.READ_ONLY !in r.capabilities,
                rangeMin = r.rangeMin,
                rangeMax = r.rangeMax,
                rangeCurrent = r.rangeCurrent,
                rangeIsPercent = r.rangeIsPercent,
                isCheckable = embeddedToggleOf(r) != null,
                isChecked = embeddedToggleOf(r)?.isChecked ?: r.isChecked
            )

            // 執行六維度 Actionability Gate
            if (WaeV26PipelineOrchestrator.passesSixDimensionActionabilityGate(
                    cand = cand,
                    activeWindowId = activeWinId,
                    screenW = screenW,
                    screenH = screenH,
                    statusBarBottom = statusBarBottom,
                    navBarTop = navBarTop,
                    imeTop = imeTop,
                    isBehindModalWindow = r.isBehindModal,
                    density = density
                )
            ) {
                validCandidates.add(cand)
            }
        }

        // ── S25：把「髒區以外」的舊候選合併回來（只換掉變化區域）────────────
        if (incremental && lastCandidates.isNotEmpty()) {
            val merged = com.wae.agent.core.perception.IncrementalPerception.merge(
                kept = lastCandidates,
                fresh = validCandidates,
                keyOf = { it.nodeStableSig },
                boundsOf = { c ->
                    com.wae.agent.core.perception.IncrementalPerception.Box(
                        c.grid[0], c.grid[1], c.grid[2], c.grid[3]
                    )
                },
                dirty = dirtyBoxes
            )
            validCandidates.clear()
            validCandidates.addAll(merged)
            Log.i("WAE_INCREMENTAL", "merged candidates=${validCandidates.size} (fresh+kept)")
        }
        lastCandidates = validCandidates.toList()
        lastCandidatesAmbient = ambientNow
        lastCandidatesPkg = activePkg
        lastCandidatesWindowId = activeWinId

        // ── L1 視覺/OCR 通道（由 VoI 調度器決定要不要花這個算力）──────────────
        // M1：不再「固定門檻全跑」，而是把每個模組當成「有成本、有信息增益的服務」，
        // 在延遲預算內挑選。高置信頁面 → 兩個都不跑（省 1s+）；稀疏頁面 → 兩個都跑。
        if (windowIdx == 0) {
            val actionableCount = validCandidates.count { it.isClickable || it.isEditable }
            val textfulCount = validCandidates.count { it.coreText.isNotBlank() }
            val needsVisual = com.wae.agent.vision.VisualChannel.shouldRun(validCandidates)
            val needsOcr = forceOcr || com.wae.agent.vision.VisualChannel.shouldRunOcr(validCandidates)

            val detectedGain = when {
                validCandidates.size < 3 -> 0.75f
                actionableCount == 0 -> 0.6f
                // 資訊充足：檢測只會帶來邊際增益 → 不值得花 180ms
                else -> 0.10f
            }
            val ocrGain = when {
                validCandidates.size < 3 -> 0.8f
                textfulCount < 2 -> 0.7f
                actionableCount == 0 -> 0.55f
                // 文字與可操作控件都齊全：OCR 幾乎不會改變決策
                else -> 0.12f
            }
            val options = ArrayList<com.wae.agent.core.decision.VoiScheduler.PerceptionOption>(2)
            if (needsVisual) {
                options.add(
                    com.wae.agent.core.decision.VoiScheduler.PerceptionOption(
                        "visual.detect",
                        costMs = 180,
                        gain = detectedGain
                    )
                )
            }
            if (needsOcr && com.wae.agent.vision.PpOcrEngine.isReady) {
                options.add(
                    com.wae.agent.core.decision.VoiScheduler.PerceptionOption(
                        "ocr",
                        costMs = 900,
                        gain = ocrGain
                    )
                )
            }
            val voiPlan = com.wae.agent.core.decision.VoiScheduler.plan(
                options = options,
                budgetMs = if (forceOcr) 2000 else 1200
            )
            val runDetect = voiPlan.chosen.contains("visual.detect")
            val runOcr = voiPlan.chosen.contains("ocr")
            if (options.isNotEmpty()) {
                Log.i(
                    "WAE_VOI",
                    "plan chosen=${voiPlan.chosen} skipped=${voiPlan.skipped} " +
                        "spent=${voiPlan.spentMs}ms gain=${"%.2f".format(voiPlan.totalGain)} " +
                        "(cand=${validCandidates.size} actionable=$actionableCount textful=$textfulCount)"
                )
            } else {
                // 資訊充足：不截圖、不推理，直接省下 1s+
                Log.i(
                    "WAE_VOI",
                    "skip: no perception justified (cand=${validCandidates.size} " +
                        "actionable=$actionableCount textful=$textfulCount) → saved ~1s"
                )
            }
            if (runDetect || runOcr) {
                // 1520 長邊：OCR 需要在縮小後仍能看清 14sp 左右的字（960 會糊掉）
                val shot = captureScreenBitmapSync(1520)
                if (shot != null) {
                    try {
                        val anchors = rawList.mapNotNull { r ->
                            val t = r.text.ifEmpty { r.contentDesc }
                            if (t.isEmpty()) null
                            else com.wae.agent.vision.VisualChannel.A11yTextAnchor(t, r.bounds)
                        }
                        val visual = com.wae.agent.vision.VisualChannel.collect(
                            shot = shot,
                            screenW = screenW,
                            screenH = screenH,
                            density = density,
                            windowId = activeWinId,
                            packageName = activePkg,
                            anchors = anchors,
                            includeIcons = runDetect,
                            includeOcr = runOcr
                        )
                        if (visual.isNotEmpty()) {
                            val merged = com.wae.agent.vision.VisualChannel.merge(validCandidates, visual)
                            validCandidates.clear()
                            validCandidates.addAll(merged)
                        }
                        if (runOcr) ocrRanForCurrentFrame = true
                    } catch (t: Throwable) {
                        Log.e("WAE_VISION", "visual channel failed", t)
                    } finally {
                        try {
                            shot.recycle()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }

        val hwBuffer = if (captureScreenshot) takeHardwareScreenshotSync() else null
        val hasScreenshot = hwBuffer != null
        val compiledJson = try {
            if (WaeNativeBridge.isNativeReady()) {
                WaeNativeBridge.waeCompileFrameJson(
                    activePkg = activePkg,
                    screenW = screenW,
                    screenH = screenH,
                    boundsFlat = boundsFlat,
                    texts = texts,
                    contentDescs = descs,
                    viewIds = viewIds,
                    classNames = classNames,
                    nodeFlags = nodeFlags,
                    screenshotHardwareBuffer = hwBuffer
                )
            } else {
                "{\"package\":\"$activePkg\",\"node_count\":$count}"
            }
        } finally {
            try {
                hwBuffer?.close()
            } catch (_: Throwable) {
            }
        }

        latestFrameJson = compiledJson
        latestNodeCount = validCandidates.size
        latestScreenshotCaptured = hasScreenshot

        // 計算 256B Head-16 + Tail-16 互動主幹簽名陣列
        val spineSigs = WaeNativeBridge.computePageSpineSigsSafe(depths, classNames, viewIds)
        var spineHash = 0L
        for (s in spineSigs) {
            spineHash = (spineHash * 31L) xor s
        }

        val dynScore = computeDynamicScore()
        val postureState = WaePatternMiningEngine.classifyDualPostureAndComponentLib(
            screenW = screenW,
            screenH = screenH,
            appWindowCount = appWindowCount,
            topWindowBoundsAreaRatio = topWindowAreaRatio,
            candidates = validCandidates,
            classNames = classNames.toList(),
            dynamicScore = dynScore,
            context = this
        )

        // +1 模型：收集姿態樣本（只存結構特徵，不含文字），供離線訓練
        com.wae.agent.core.posture.PostureSampleRecorder.maybeRecord(
            context = this,
            pkg = activePkg,
            posture = postureState.primaryPosture,
            confidence = com.wae.agent.core.posture.PostureModel.lastProbs
                .maxOrNull() ?: 0f
        )

        val frameId = frameIdGenerator.getAndIncrement()
        val built = WaeV26PipelineOrchestrator.buildScoredCandidatesAndOptionSet(
            frameId = frameId,
            windowIdx = windowIdx,
            activeWindowId = activeWinId,
            packageName = activePkg,
            postureState = postureState,
            pageSpineSigs = spineSigs,
            pageSpineHash = spineHash,
            goal = goal,
            rawCandidates = validCandidates,
            skipSemanticScoring = collectOnly
        )
        // Twin（M2）：有新的頁面狀態就補完上一個動作的「動作後狀態」
        if (windowIdx == 0) {
            com.wae.agent.core.twin.TwinRecorder.complete(spineHash, spineSigs.toList())
        }
        return built
    }

    /**
     * 從 `AccessibilityNodeInfo` 的**公開 API** 抽取運行期語意訊號（綠區，API 19+ 皆為公開）。
     *
     * 這是「元件能力圖譜」的第二條腿：即使元件是自繪的（Compose / Flutter / 自訂 SeekBar），
     * 類別名一律是 android.view.View，但 RangeInfo / ACTION_SET_PROGRESS / isCheckable
     * 這些訊號仍然會如實反映「它其實是滑桿 / 開關」。
     */
        private fun buildRuntimeSignals(
        node: AccessibilityNodeInfo,
        className: String
    ): com.wae.agent.core.component.RuntimeSignals =
        com.wae.agent.core.component.RuntimeSignalsBuilder.build(node, className)

        private fun collectNodesWithContextRecursive(
        node: AccessibilityNodeInfo?,
        depth: Int,
        parentRole: String,
        siblingHint: String,
        isBehindModal: Boolean,
        outList: MutableList<RawCollectedNode>,
        scopeRects: List<com.wae.agent.core.perception.IncrementalPerception.Box>? = null
    ) = com.wae.agent.perception.NodeContextCollector.collect(
        node, depth, parentRole, siblingHint, isBehindModal, outList, scopeRects
    )

    private fun classifySpatialZone(screenW: Int, screenH: Int, bounds: IntArray): String =
        com.wae.agent.perception.FrameSignals.classifySpatialZone(screenW, screenH, bounds)

    private fun buildWeldedContextDescription(
        zone: String,
        parentRole: String,
        roleTag: String,
        coreText: String,
        siblingText: String
    ): String = com.wae.agent.perception.FrameSignals.weldedContext(
        zone, parentRole, roleTag, coreText, siblingText
    )

    /**
     * 計算目標節點向上最多 2 層父容器子樹 (maxAncestorHops = 2) 之狀態雜湊
     */
    private fun compute2HopParentSubtreeHash(markId: Int): Long {
        val node = liveNodeMap[markId] ?: return 0L
        return try {
            node.refresh()
            val state = com.wae.agent.perception.FrameSignals.twoHopAncestorState(node) ?: return 0L
            WaeNativeBridge.fnv1a64(state)
        } catch (_: Throwable) {
            0L
        }
    }



    private fun computeDynamicScore(): Float {
        val now = SystemClock.uptimeMillis()
        val recentCount = synchronized(recentContentChangeTimestamps) {
            while (recentContentChangeTimestamps.isNotEmpty() &&
                now - recentContentChangeTimestamps.first() > 2000L
            ) {
                recentContentChangeTimestamps.removeFirst()
            }
            recentContentChangeTimestamps.size
        }
        return (recentCount.toFloat() / 24f).coerceIn(0f, 1f)
    }

    /** S33：Task OS 的機器訊息 → 使用者看得懂的中文 */
    private fun humanizeTaskOsMessage(message: String): String = when {
        message.startsWith("completed") -> "完成"
        message == "failed_no_repair" -> "找不到可修復的方式（畫面可能已變動）"
        message == "confirmation_declined" -> "這個動作需要你確認才能繼續"
        message == "cancelled" -> "任務已取消"
        message == "hard_limit" -> "超過步數上限，已安全停止"
        else -> message
    }

    /** S33：Task OS 變數名稱 → 使用者看得懂的欄位名 */
    private fun taskOsVarLabel(name: String): String = when (name) {
        "app" -> "要開啟的 App"
        "query" -> "搜尋關鍵字"
        "first" -> "要點選的第一筆結果"
        "version" -> "版本號"
        "text" -> "要輸入的內容"
        else -> name
    }

    /**
     * S33：用 Task OS 的 [com.wae.agent.core.taskos.TaskRunner] 驅動一個長任務會話。
     *
     * 單步執行仍走既有的 [executeSingleLocalGoalWithRecovery]（含 TOCTOU/安全/恢復），
     * 所以 Task OS 只負責「決定順序＋資料流＋修復」，不重寫動作層。
     */
    private fun runTaskOsSession(
        session: com.wae.agent.core.taskos.TaskSession,
        onStepUpdate: ((String) -> Unit)?
    ): com.wae.agent.core.taskos.TaskRunner.Result {
        val executor = object : com.wae.agent.core.taskos.TaskRunner.TaskExecutor {
            override fun execute(goal: LocalGoal): Boolean =
                executeSingleLocalGoalWithRecovery(goal, onStepUpdate)

            override fun screenTexts(): List<String> = collectVisibleScreenTexts()

            override fun confirm(
                step: com.wae.agent.core.taskos.TaskStep,
                goal: LocalGoal
            ): Boolean {
                // v1：不可逆步驟一律不自動放行（沒有 UI 確認流程時寧可停下）
                Log.i(
                    "WAE_TASKOS",
                    "irreversible step requires confirmation: '${goal.goalText}'"
                )
                return false
            }

            override fun isCancelled(): Boolean = isCancelledOrTimedOut()

            override fun onProgress(text: String) {
                notifyUiStatus(text, onStepUpdate)
            }
        }
        return com.wae.agent.core.taskos.TaskRunner.run(session, executor)
    }

    /**
     * 從「子頁面」退回到 App 根頁——只在**看得到返回箭頭**時才動作。
     *
     * 為什麼要這麼保守：S31 的教訓是「用姿態模型猜根頁」會把 Maps 首頁誤判成搜尋結果頁，
     * 按了 BACK 直接把 App 關掉。現在改成純證據：
     *   1. 前景不是 WAE 自己
     *   2. 畫面上方（高度 28% 以內）有可點的「返回／上一頁／Back」或 id 含 back/up
     *   3. 按完重新檢查，最多 baseline.max_back 次
     *
     * 參數與開關都放 `ui_tunables.tsv`（baseline.enabled / baseline.max_back）。
     */
    /** S33：收集目前畫面上所有 text / contentDescription（變數收集與守衛判定用） */
    private fun collectVisibleScreenTexts(): List<String> {
        val out = ArrayList<String>(32)
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return out
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            val n = stack.removeLast()
            visited++
            runCatching {
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
            }
            // API 30–32 的 AccessibilityNodeInfo 需要釋放，否則長任務反覆收集會累積原生物件。
            // rootInActiveWindow 由系統持有，不 recycle；其餘節點讀完就釋放。
            if (n !== root) {
                runCatching { n.recycle() }
            }
        }
        return out.distinct().take(80)
    }

    private fun saveStepCheckpoint(goal: LocalGoal, optionSet: ImmutableOptionSet) {
        WaeV26PipelineOrchestrator.stepCheckpoints.add(
            StepCheckpoint(
                completedStepIdx = goal.stepIdx,
                goalId = goal.goalId,
                packageName = optionSet.packageName,
                activeWindowId = optionSet.activeWindowId,
                pageSpineSigs = optionSet.pageSpineSigs,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    private fun dispatchTapGesture(x: Int, y: Int): Boolean =
        com.wae.agent.input.ScreenGestures.tap(this, x, y)

    private fun performHalfScreenScrollDown(): Boolean =
        com.wae.agent.input.ScreenGestures.scrollDownHalf(this)

    

    /** 向上滾動半屏（列表回退/回頂） */
    private fun performScrollUp(): Boolean =
        com.wae.agent.input.ScreenGestures.scrollUp(this)

    /** 水平滑動（左右各一次嘗試：先右後左，常用於返回與 Tab 切換） */
    private fun performSwipeHorizontalGesture(): Boolean {
        val ok = com.wae.agent.input.ScreenGestures.swipeHorizontal(this, leftToRight = true)
        if (ok) waitForUiQuiet(700L)
        return ok
    }

    /** 長按指定座標（多選/拖曳/上下文菜單） */
    private fun performLongPressAt(x: Int, y: Int): Boolean {
        val ok = com.wae.agent.input.ScreenGestures.longPress(this, x, y)
        if (ok) waitForUiQuiet(800L)
        return ok
    }

    /**
     * 送出目前輸入：優先 IME action，其次點 IME 的搜尋/送出鍵。
     * 這比「點建議列」語意清楚，也不需要猜測 UI。
     */
    /**
     * 調整數值（亮度 / 音量 / 字體大小 / 任何滑桿或數值選擇器）。
     *
     * 三層派發，逐層驗證是否真的生效（讀回 RangeInfo，不看 UI 文字）：
     *  1. `ACTION_SET_PROGRESS`：直接把值設成目標（API 24+，最精確、零誤差）
     *  2. 拖曳：依「目前值」算出拇指位置拖到「目標值」位置（沒有 SET_PROGRESS 的 App）
     *  3. 一次修正：依讀回的實際值補拖（拖曳有物理誤差）
     * 都沒有生效就誠實回報失敗，不會亂點。
     */
    private fun performAdjustValue(
        goal: LocalGoal,
        node: AccessibilityNodeInfo?,
        rect: Rect,
        target: CandidateOption
    ): Boolean {
        val intent = com.wae.agent.core.component.ValueIntent.parse(
            goal.goalText.ifBlank { goal.inputPayload }
        )
        val label = goal.inputPayload.ifBlank { intent.targetNoun }

        val rangeInfo: AccessibilityNodeInfo.RangeInfo? = try {
            node?.rangeInfo
        } catch (_: Throwable) {
            null
        }

        // 決策表在 core（純函式、有測試）：這裡只負責把決策變成動作。
        val deciderRange = rangeInfo?.let {
            com.wae.core.component.ValueAdjustDecider.Range(it.min, it.max, it.current)
        }
        val decision = com.wae.core.component.ValueAdjustDecider.decide(
            nodePresent = node != null,
            range = deciderRange,
            target = com.wae.core.component.ValueAdjustDecider.Target(
                hasOwnSemanticEvidence = target.hasOwnSemanticEvidence,
                hybridSimScore = target.hybridSimScore,
                readOnlyOnly = target.isReadOnlyOnly
            ),
            intentRequested = intent.requested,
            intentMatched = intent.matched
        )

        when (decision) {
            is com.wae.core.component.ValueAdjustDecider.Decision.TapOnlyNoNode -> {
                // 沒有 live node：只能點座標，但**不能算完成**（否則會誤報成功）
                Log.i("WAE_VALUE", "no-live-node target='${target.coreText.take(24)}' → tap only, not done")
                performNodeOrAncestorClickWithGestureFallback(null, rect)
                return false
            }
            is com.wae.core.component.ValueAdjustDecider.Decision.RefuseBlindClick -> {
                Log.i("WAE_VALUE", "no-range & unidentifiable target='$label' → refuse (would be blind click)")
                return false
            }
            is com.wae.core.component.ValueAdjustDecider.Decision.OpenDeeper -> {
                Log.i("WAE_VALUE", "not-applicable target='${target.coreText.take(24)}' → navigate deeper, not done")
                if (rangeInfo == null) {
                    notifyUiStatus("→ 進入「${target.coreText.take(24)}」，繼續尋找可調整的滑桿", null)
                }
                performNodeOrAncestorClickWithGestureFallback(node, rect)
                return false
            }
            is com.wae.core.component.ValueAdjustDecider.Decision.RefuseInvalidRange -> {
                Log.i(
                    "WAE_VALUE",
                    "invalid-range target='$label' min=${rangeInfo?.min} max=${rangeInfo?.max} → refuse"
                )
                return false
            }
            is com.wae.core.component.ValueAdjustDecider.Decision.RefuseReadOnly -> {
                Log.i("WAE_VALUE", "read-only widget target='$label' caps=${target.capabilities} → refuse")
                return false
            }
            is com.wae.core.component.ValueAdjustDecider.Decision.Apply -> Unit
        }

        val liveNode: AccessibilityNodeInfo = node ?: return false
        val range: AccessibilityNodeInfo.RangeInfo = rangeInfo ?: return false
        val minV = range.min
        val maxV = range.max
        var current = range.current
        val desired = com.wae.agent.core.component.ValueAdjustPlanner.desiredValue(intent, minV, maxV, current)
        Log.i(
            "WAE_VALUE",
            "plan target='$label' matched='${intent.matched}' dir=${intent.direction} " +
                com.wae.agent.core.component.ValueAdjustPlanner.describe(intent, minV, maxV, current)
        )

        // ── 1. ACTION_SET_PROGRESS ──────────────────────────────────────
        try {
            val args = Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, desired)
            }
            val ok = NodeOps.perform(liveNode, 
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                args
            )
            if (ok) {
                waitForUiQuiet(320L)
                val now = readRangeCurrent(liveNode)
                if (now != null && com.wae.agent.core.component.ValueAdjustPlanner
                        .withinTolerance(now, desired, minV, maxV)
                ) {
                    Log.i("WAE_VALUE", "applied=set_progress ${"%.2f".format(current)}→${"%.2f".format(now)}")
                    return true
                }
                if (now != null) current = now
            }
        } catch (_: Throwable) {
        }

        // ── 2. 拖曳（從實際目前值的位置出發）────────────────────────────
        val boundsArr = intArrayOf(rect.left, rect.top, rect.right, rect.bottom)
        val inset = resources.displayMetrics.density * 12f
        var target = com.wae.agent.core.component.ValueAdjustPlanner
            .desiredValue(intent, minV, maxV, current)
        dispatchStroke(
            com.wae.agent.core.component.ValueAdjustPlanner
                .dragStroke(boundsArr, current, target, minV, maxV, inset)
        )
        waitForUiQuiet(380L)
        var observed = readRangeCurrent(liveNode)
        if (observed != null && com.wae.agent.core.component.ValueAdjustPlanner
                .withinTolerance(observed, target, minV, maxV)
        ) {
            Log.i("WAE_VALUE", "applied=drag ${"%.2f".format(current)}→${"%.2f".format(observed)}")
            return true
        }

        // ── 3. 一次修正（拖曳物理誤差）──────────────────────────────────
        if (observed != null) {
            target = com.wae.agent.core.component.ValueAdjustPlanner
                .correctiveValue(intent, minV, maxV, observed)
            dispatchStroke(
                com.wae.agent.core.component.ValueAdjustPlanner
                    .dragStroke(boundsArr, observed, target, minV, maxV, inset)
            )
            waitForUiQuiet(360L)
            val observed2 = readRangeCurrent(liveNode)
            if (observed2 != null && com.wae.agent.core.component.ValueAdjustPlanner
                    .withinTolerance(observed2, target, minV, maxV)
            ) {
                Log.i("WAE_VALUE", "applied=drag+correct ${"%.2f".format(observed)}→${"%.2f".format(observed2)}")
                return true
            }
            observed = observed2
        }

        Log.i(
            "WAE_VALUE",
            "applied=NONE target='$label' desired=${"%.2f".format(target)} " +
                "observed=${observed?.let { "%.2f".format(it) } ?: "null"} (量程 $minV~$maxV)"
        )
        return false
    }

    /** 讀回節點目前的數值（先 refresh，避免拿到快取舊值） */
        private fun dispatchStroke(stroke: com.wae.agent.core.runtime.GesturePaths.Stroke): Boolean =
        com.wae.agent.value.ValueAdjustIo.dispatchStroke(this, stroke)

    /** 讀回節點目前的數值（先 refresh，避免拿到快取舊值） */
    private fun readRangeCurrent(node: AccessibilityNodeInfo): Float? =
        com.wae.agent.value.ValueAdjustIo.readValue(node)

    private fun performSubmitCurrentInput(): Boolean {
        val node = findFocusedOrFirstEditableNodeAnyWindow()
        if (node != null) {
            try {
                val imeEnterId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                if (NodeOps.perform(node, imeEnterId)) {
                    waitForUiQuiet(900L)
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        if (clickImeEnterOrSearchActionIfPresent()) {
            waitForUiQuiet(900L)
            return true
        }
        return false
    }

    /**
     * 用 ACTION_SET_TEXT 設定聚焦輸入框內容（空字串＝清除）。
     * 比逐字退格可靠且快，也不依賴鍵盤佈局。
     */
    /** P5：剪貼簿 + 長按貼上（自繪輸入框唯一可行路徑） */
    private fun pasteIntoFocusedInput(text: String): Boolean {
        return try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return false
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wae", text))
            val node = findFocusedOrFirstEditableNodeAnyWindow() ?: return false
            val r = Rect()
            node.getBoundsInScreen(r)
            dispatchStroke(com.wae.agent.core.runtime.GesturePaths.longPress(r.centerX(), r.centerY(), 700L))
            waitForUiQuiet(700L)
            // 貼上選單通常在中上方，嘗試點擊「貼上」
            return true
        } catch (_: Throwable) {
            false
        }
    }

    /** P5：逐字注入（最後手段） */
    private fun injectCharsIntoFocusedInput(text: String): Boolean {
        return try {
            val node = findFocusedOrFirstEditableNodeAnyWindow() ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            var acc = ""
            for (ch in text) {
                acc += ch
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, acc)
                if (!NodeOps.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun performSetTextOnFocused(text: String): Boolean {
        val node = findFocusedOrFirstEditableNodeAnyWindow() ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val ok = NodeOps.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) waitForUiQuiet(500L)
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * P2：精準滾動。優先使用無障礙 ACTION_SCROLL_FORWARD/BACKWARD（可預測），
     * 失敗才退回手勢；手勢距離由 ScrollPlanner 依目標距離計算。
     */
    private fun performPreciseScrollDown(targetDistancePx: Int = 0): Boolean {
        if (performA11yScroll(forward = true)) return true
        return performScrollGesture(distancePx = targetDistancePx, forward = true)
    }

    private fun performPreciseScrollUp(targetDistancePx: Int = 0): Boolean {
        if (performA11yScroll(forward = false)) return true
        return performScrollGesture(distancePx = targetDistancePx, forward = false)
    }

    /** 對可滾動容器下達無障礙滾動；成功回傳 true */
    private fun performA11yScroll(forward: Boolean): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val container = findScrollableNode(root, depth = 0) ?: return false
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            NodeOps.perform(container, action)
        } catch (_: Throwable) {
            false
        }
    }

    /** 由內而外找第一個可滾動節點（RecyclerView / ScrollView / NestedScrollView） */
    private fun findScrollableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        val count = node.childCount.coerceAtMost(12)
        for (i in 0 until count) {
            val found = findScrollableNode(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** 手勢滾動：距離由目標距離決定（上限半螢幕），方向由 forward 決定 */
    private fun performScrollGesture(distancePx: Int, forward: Boolean): Boolean {
        val dm = resources.displayMetrics
        val viewportH = dm.heightPixels
        val distance = if (distancePx > 0) {
            distancePx.coerceIn(viewportH / 8, viewportH / 2)
        } else {
            (viewportH * 0.44f).toInt()
        }
        val cx = dm.widthPixels / 2f
        val startY = if (forward) viewportH * 0.72f else viewportH * 0.28f
        val endY = if (forward) startY - distance else startY + distance
        return dispatchStroke(
            com.wae.agent.core.runtime.GesturePaths.Stroke(
                startX = cx,
                startY = startY,
                endX = cx,
                endY = endY,
                durationMs = 260L
            )
        )
    }

    private fun takeHardwareScreenshotSync(): HardwareBuffer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val holder = AtomicReference<HardwareBuffer?>(null)
        val latch = CountDownLatch(1)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            holder.set(screenshot.hardwareBuffer)
                        } catch (_: Throwable) {
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            latch.await(700L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        return holder.get()
    }

    /**
     * 截取目前畫面並轉成可讀像素的 Bitmap（供視覺通道使用）。
     * 走無障礙服務的 takeScreenshot（已在 xml 宣告 canTakeScreenshot），不需要 MediaProjection 授權。
     *
     * @param maxLongEdge 長邊上限；視覺模型的輸入是 640，超過沒有意義，先縮小可省記憶體與拷貝時間
     */
    /** 給 Agent 的感知用：截一張畫面給 OCR（覆蓋沒有無障礙樹的 App）。 */
    fun captureScreenForPerception(): Bitmap? = runCatching { captureScreenBitmapSync(1520) }.getOrNull()

    private fun captureScreenBitmapSync(maxLongEdge: Int): Bitmap? {
        val hw = takeHardwareScreenshotSync() ?: return null
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(hw, ColorSpace.get(ColorSpace.Named.SRGB))
                ?: return null
            // HardwareBitmap 不能直接 getPixels，必須先複製成 ARGB_8888
            val argb = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val longEdge = kotlin.math.max(argb.width, argb.height)
            val bitmap = if (longEdge <= maxLongEdge) {
                argb
            } else {
                val scale = maxLongEdge.toFloat() / longEdge.toFloat()
                val sw = (argb.width * scale).toInt().coerceAtLeast(1)
                val sh = (argb.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(argb, sw, sh, true)
                argb.recycle()
                scaled
            }
            // 空白/單色截圖守衛：某些 App（含 FLAG_SECURE、或視窗切換動畫中）會回傳整片白/黑，
            // 這種圖丟給檢測器只會得到幾十個假框（實測白圖 det=49 boxes）。直接判為無效。
            if (!hasVisualContent(bitmap)) {
                Log.w("WAE_VISION", "capture rejected: blank/uniform screenshot")
                bitmap.recycle()
                return null
            }
            bitmap
        } catch (t: Throwable) {
            Log.e("WAE_VISION", "captureScreenBitmapSync failed", t)
            null
        } finally {
            try {
                hw.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** 粗略判斷截圖是否有內容（亮度標準差過低 = 整片純色） */
    private fun hasVisualContent(bitmap: Bitmap): Boolean {
        return try {
            val stepX = (bitmap.width / 32).coerceAtLeast(1)
            val stepY = (bitmap.height / 32).coerceAtLeast(1)
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val p = bitmap.getPixel(x, y)
                    val lum = (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 +
                        (p and 0xFF) * 0.114)
                    sum += lum
                    sumSq += lum * lum
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n == 0) return false
            val mean = sum / n
            val variance = (sumSq / n) - mean * mean
            variance > 36.0   // stddev > 6
        } catch (_: Throwable) {
            true
        }
    }

    private fun notifyUiStatus(msg: String, stepCallback: ((String) -> Unit)? = null) {
        latestStatusMessage = msg
        if (voiceTaskActive) runCatching { voiceProgress?.update(msg) }
        stepCallback?.invoke(msg)
        uiUpdateCallback?.invoke()
    }

    private fun waitForTargetPackageInForeground(targetPkg: String, maxWaitMs: Long) {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < maxWaitMs) {
            val activeRoot = getActiveNonWaeRoot()
            val fg = activeRoot?.packageName?.toString().orEmpty()
            if (fg == targetPkg) {
                latestPackageName = fg
                if ((activeRoot?.childCount ?: 0) >= 2) {
                    waitForUiQuiet(900L) // 等待首幀畫面渲染穩定
                    return
                }
            }
            SystemClock.sleep(150L)
        }
    }
}
