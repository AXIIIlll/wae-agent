package com.example.newandroidapp.ai

import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.vision.VisionPipelineReport
import java.util.concurrent.atomic.AtomicInteger

/**
 * 動態算力分級門控引擎 (Adaptive Compute Gating Engine)
 *
 * 負責在單步決策與三腦協調管線之間進行「算力按需分級調度」：
 * 1. 常規畫面極速通道 ([ComputeTier.TIER_0_FAST_PATH])：
 *    優先由輕量級原生漏斗篩選 ([AndroidNativeAiEngine] + [com.example.newandroidapp.stability.InAppOperationPlanner])
 *    與本地情節記憶庫 ([com.example.newandroidapp.stability.EpisodicAppMemory]) 處理。
 *    當最高分候選信心度 >= 0.85、領先第二名幅度 (Margin) >= 0.15 且元件屬性相符時，
 *    直接以 0ms 快速通道執行動作，嚴禁啟動 CoT 語言模型推理、MCTS 蒙地卡羅樹搜尋或 PDDL/SAT-SMT 形式化規劃求解器。
 *
 * 2. 複雜與異常狀態按需喚醒 ([ComputeTier.TIER_1_ESCALATED_DEEP])：
 *    僅在以下三種異常或高複雜度情境發生時，才動態解鎖並喚醒高階協調引擎 ([TriBrainCoordinatorEngine])：
 *    - (A) [EscalationReason.FINGERPRINT_STUCK_2X]：閉環驗證模組偵測到畫面結構指紋連續兩次未發生有效改變（疑似卡死或點擊無效）。
 *    - (B) [EscalationReason.LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN]：原生快篩漏斗最高分候選信心值低於安全閾值 (< 0.85)，
 *          或前兩名候選分數過於接近 (Margin < 0.15，語義模糊)，或元件屬性不符。
 *    - (C) [EscalationReason.CROSS_APP_COMPLEX_BRANCH] / [EscalationReason.UNKNOWN_MODAL_OCCLUSION]：
 *          當前處於跨 App 複雜邏輯分支切換點，或存在未知彈窗/對話框遮擋且輕量規則無法直接排除。
 *
 * 3. 算力回收與自動降級 ([onScreenTransitionVerified])：
 *    一旦深層推理成功突破卡點並通過閉環畫面跳轉驗證 ([ClosedLoopVerifier.hasScreenChangedMeaningfully])，
 *    下一步驟立即自動回收暫存算力緩衝並降級回 [ComputeTier.TIER_0_FAST_PATH] 輕量快速通道。
 */
object AdaptiveComputeGate {

    /** 連續相同畫面結構指紋觸發深層算力喚醒的門檻次數 */
    const val STUCK_FINGERPRINT_THRESHOLD = 2

    enum class ComputeTier(val code: String, val displayLabel: String) {
        TIER_0_FAST_PATH("TIER_0_FAST_PATH", "0ms 輕量極速通道"),
        TIER_1_ESCALATED_DEEP("TIER_1_ESCALATED_DEEP", "按需深層三腦推理")
    }

    /**
     * 三階動態算力路由與剪枝等級（Early Exit & Pruning）：
     * - LEVEL_0_ZERO_MS_CACHE_AND_NATIVE：情節記憶庫或原生分類器信心達標，0ms 直接執行動作，完全跳過 C++ 模型推論與樹搜尋規劃器。
     * - LEVEL_1_LIGHT_1024D_VECTOR_RERANK：一般常規畫面（未觸發連續失敗或跨 App 複雜邏輯），僅執行本地 1024 維度向量餘弦相似度精排，不喚醒 CoT 與 MCTS/PDDL/SMT。
     * - LEVEL_2_HEAVY_COT_MCTS_PLANNER：僅在閉環驗證偵測到連續失敗、陷入死胡同、未知彈窗或跨 App 複雜邏輯時，才喚醒 CoT 模型與 MCTS / PDDL / SMT 規劃器（含 Phase 1~3 先驗剪枝）。
     */
    enum class ComputeLevel(val levelIndex: Int, val code: String, val displayLabel: String) {
        LEVEL_0_ZERO_MS_CACHE_AND_NATIVE(0, "LEVEL_0_FAST_CACHE", "Level 0 (0ms 快取與原生快篩)"),
        LEVEL_1_LIGHT_1024D_VECTOR_RERANK(1, "LEVEL_1_1024D_RERANK", "Level 1 (輕量 1024D 向量精排)"),
        LEVEL_2_HEAVY_COT_MCTS_PLANNER(2, "LEVEL_2_HEAVY_PLANNER", "Level 2 (重型 CoT + MCTS/PDDL/SMT 先驗剪枝)")
    }

    enum class EscalationReason(val code: String, val description: String) {
        NONE("NONE", "常規高信心畫面，維持 Level 0 (0ms 極速通道)"),
        FINGERPRINT_STUCK_2X("FINGERPRINT_STUCK_2X", "畫面結構指紋連續兩次未發生有效改變"),
        DEAD_END_OR_ZERO_TARGET("DEAD_END_OR_ZERO_TARGET", "畫面陷入死胡同或無明顯目標元件"),
        LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN("LOW_CONFIDENCE_OR_MARGIN", "快篩信心度不足或前兩名分數過於接近"),
        CROSS_APP_COMPLEX_BRANCH("CROSS_APP_COMPLEX_BRANCH", "跨 App 複雜邏輯分支切換點"),
        UNKNOWN_MODAL_OCCLUSION("UNKNOWN_MODAL_OCCLUSION", "未知彈窗或遮罩阻擋狀態"),
        EXPLICIT_FORCE_DEEP("EXPLICIT_FORCE_DEEP", "手動強制指定深層推理模式")
    }

    data class ComputeGateDecision(
        val tier: ComputeTier,
        val level: ComputeLevel = if (tier == ComputeTier.TIER_0_FAST_PATH) ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE else ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
        val canTakeFastPath: Boolean,
        val isLevel1LightVectorOnly: Boolean = (level == ComputeLevel.LEVEL_1_LIGHT_1024D_VECTOR_RERANK),
        val isLevel2HeavyPlanner: Boolean = (level == ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER),
        val escalationReason: EscalationReason,
        val sameFingerprintStreak: Int,
        val consecutiveStuckCount: Int,
        val top1Score: Float,
        val scoreMargin: Float,
        val diagnosticBadge: String
    )

    @Volatile
    var currentTier: ComputeTier = ComputeTier.TIER_0_FAST_PATH
        private set

    @Volatile
    var currentLevel: ComputeLevel = ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE
        private set

    @Volatile
    var lastEscalationReason: EscalationReason = EscalationReason.NONE
        private set

    @Volatile
    var lastEvaluatedScreenHash: String = ""
        private set

    @Volatile
    var lastEvaluatedSubGoalKey: String = ""
        private set

    @Volatile
    var lastEvaluatedAppPhase: Int = 1
        private set

    @Volatile
    var lastForegroundPackage: String = ""
        private set

    @Volatile
    var sameFingerprintStreak: Int = 0
        private set

    @Volatile
    var consecutiveUnchangedVerifications: Int = 0
        private set

    private val fastPathHits = AtomicInteger(0)
    private val level1VectorHits = AtomicInteger(0)
    private val deepEscalations = AtomicInteger(0)
    private val autoDeescalations = AtomicInteger(0)

    val fastPathHitCount: Int
        get() = fastPathHits.get()

    val level1VectorHitCount: Int
        get() = level1VectorHits.get()

    val deepEscalationCount: Int
        get() = deepEscalations.get()

    val autoDeescalationCount: Int
        get() = autoDeescalations.get()

    @Synchronized
    fun reset() {
        currentTier = ComputeTier.TIER_0_FAST_PATH
        currentLevel = ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE
        lastEscalationReason = EscalationReason.NONE
        lastEvaluatedScreenHash = ""
        lastEvaluatedSubGoalKey = ""
        lastEvaluatedAppPhase = 1
        lastForegroundPackage = ""
        sameFingerprintStreak = 0
        consecutiveUnchangedVerifications = 0
        fastPathHits.set(0)
        level1VectorHits.set(0)
        deepEscalations.set(0)
        autoDeescalations.set(0)
        TriBrainCoordinatorEngine.reclaimTransientComputeBuffers()
    }

    /**
     * 閉環驗證完成後的算力回收與降級回呼：
     * - 當 [screenChanged] 為 true 時：代表深層推理或當前動作已成功突破卡點並通過畫面跳轉驗證，
     *   立即回收暫存推理狀態並強制降級回 [ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE]。
     * - 當 [screenChanged] 為 false 時：累加連續未變動驗證計數，供下一步精準觸發按需升維。
     */
    @Synchronized
    fun onScreenTransitionVerified(
        screenChanged: Boolean,
        afterScreenHash: String = ""
    ) {
        if (screenChanged) {
            if (currentTier == ComputeTier.TIER_1_ESCALATED_DEEP || currentLevel != ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE) {
                autoDeescalations.incrementAndGet()
            }
            currentTier = ComputeTier.TIER_0_FAST_PATH
            currentLevel = ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE
            lastEscalationReason = EscalationReason.NONE
            sameFingerprintStreak = 0
            consecutiveUnchangedVerifications = 0
            if (afterScreenHash.isNotBlank()) {
                lastEvaluatedScreenHash = afterScreenHash
            }
            TriBrainCoordinatorEngine.reclaimTransientComputeBuffers()
        } else {
            consecutiveUnchangedVerifications += 1
        }
    }

    /**
     * 雙指紋閉環驗證便捷入口：直接比對動作前後之 [ClosedLoopVerifier.ScreenFingerprint]，
     * 若通過有效跳轉驗證則立即降級回輕量快速通道。
     */
    @Synchronized
    fun verifyTransitionAndReclaimCompute(
        beforeFp: ClosedLoopVerifier.ScreenFingerprint,
        afterFp: ClosedLoopVerifier.ScreenFingerprint
    ): Boolean {
        val changed = ClosedLoopVerifier.hasScreenChangedMeaningfully(beforeFp, afterFp)
        onScreenTransitionVerified(
            screenChanged = changed,
            afterScreenHash = afterFp.shortCode()
        )
        return changed
    }

    /**
     * 三階動態算力路由與剪枝評估（Level 0 / Level 1 / Level 2）：
     * - Level 0（0ms 快取與原生快篩）：情節記憶庫或原生分類器命中信心值達標，直接執行動作，完全跳過 C++ 模型推論與樹搜尋規劃器。
     * - Level 1（輕量向量精排）：一般常規畫面僅執行本地 1024 維度向量餘弦相似度精排。
     * - Level 2（重型推理與規劃）：僅在閉環驗證偵測到連續失敗、陷入死胡同、未知彈窗或跨 App 複雜邏輯時，才喚醒 CoT 模型與 MCTS / PDDL / SMT 規劃器。
     */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun evaluateGate(
        report: VisionPipelineReport,
        screenHash: String,
        activeSubGoal: CooperativeSubGoal,
        inAppStage: InAppOperationStage,
        fastPathGate: DeterminismHardeningEngine.FastPathGateEvaluation,
        foregroundPackage: String,
        consecutiveStuckCount: Int = 0,
        consecutiveScrollNoChangeCount: Int = 0,
        hasEpisodicMemoryAnchor: Boolean = false,
        blockerAlreadyResolved: Boolean = false,
        forceDeepReasoning: Boolean = false
    ): ComputeGateDecision {
        val subGoalKey = "${activeSubGoal.stepIndex}:${activeSubGoal.intentCategory}:${activeSubGoal.targetSemanticPhrase}"

        // 1. 檢查畫面結構指紋是否已發生跳轉：若指紋已改變且先前處於升維狀態，立即自動降級並回收算力！
        if (lastEvaluatedScreenHash.isNotEmpty() && screenHash != lastEvaluatedScreenHash) {
            if (currentTier == ComputeTier.TIER_1_ESCALATED_DEEP || currentLevel != ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE) {
                autoDeescalations.incrementAndGet()
            }
            currentTier = ComputeTier.TIER_0_FAST_PATH
            currentLevel = ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE
            lastEscalationReason = EscalationReason.NONE
            sameFingerprintStreak = 1
            consecutiveUnchangedVerifications = 0
            TriBrainCoordinatorEngine.reclaimTransientComputeBuffers()
        } else if (screenHash == lastEvaluatedScreenHash && subGoalKey == lastEvaluatedSubGoalKey) {
            sameFingerprintStreak += 1
        } else {
            sameFingerprintStreak = 1
        }

        val previousAppPhase = lastEvaluatedAppPhase
        val previousPkg = lastForegroundPackage
        lastEvaluatedScreenHash = screenHash
        lastEvaluatedSubGoalKey = subGoalKey
        lastEvaluatedAppPhase = activeSubGoal.appPhaseIndex
        if (foregroundPackage.isNotBlank()) {
            lastForegroundPackage = foregroundPackage
        }

        // 強制指定深層模式 (Level 2)
        if (forceDeepReasoning) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER
            lastEscalationReason = EscalationReason.EXPLICIT_FORCE_DEEP
            deepEscalations.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
                reason = EscalationReason.EXPLICIT_FORCE_DEEP,
                consecutiveStuckCount = consecutiveStuckCount,
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 2 重型推理喚醒條件 1：閉環驗證偵測到畫面結構指紋連續兩次未發生有效改變（疑似卡死或點擊無效）
        // =========================================================================
        val isStuck2x = consecutiveStuckCount >= STUCK_FINGERPRINT_THRESHOLD ||
            consecutiveScrollNoChangeCount >= STUCK_FINGERPRINT_THRESHOLD ||
            consecutiveUnchangedVerifications >= STUCK_FINGERPRINT_THRESHOLD ||
            sameFingerprintStreak >= STUCK_FINGERPRINT_THRESHOLD

        if (isStuck2x) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER
            lastEscalationReason = EscalationReason.FINGERPRINT_STUCK_2X
            deepEscalations.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
                reason = EscalationReason.FINGERPRINT_STUCK_2X,
                consecutiveStuckCount = maxOf(consecutiveStuckCount, sameFingerprintStreak),
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 2 重型推理喚醒條件 2：未知彈窗或遮罩阻擋狀態
        // =========================================================================
        if (!blockerAlreadyResolved && hasUnknownModalOcclusion(report)) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER
            lastEscalationReason = EscalationReason.UNKNOWN_MODAL_OCCLUSION
            deepEscalations.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
                reason = EscalationReason.UNKNOWN_MODAL_OCCLUSION,
                consecutiveStuckCount = consecutiveStuckCount,
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 2 重型推理喚醒條件 3：跨 App 複雜邏輯分支切換點
        // =========================================================================
        if (isCrossAppComplexBranchTransition(
                report = report,
                activeSubGoal = activeSubGoal,
                previousAppPhase = previousAppPhase,
                previousPkg = previousPkg,
                currentPkg = foregroundPackage,
                hasEpisodicMemoryAnchor = hasEpisodicMemoryAnchor
            )
        ) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER
            lastEscalationReason = EscalationReason.CROSS_APP_COMPLEX_BRANCH
            deepEscalations.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
                reason = EscalationReason.CROSS_APP_COMPLEX_BRANCH,
                consecutiveStuckCount = consecutiveStuckCount,
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 2 重型推理喚醒條件 4：陷入死胡同（全畫面無任何有效候選或最高分 <= 0.22f）
        // =========================================================================
        if (report.finalNodes.isEmpty() || fastPathGate.top1Score <= 0.22f) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER
            lastEscalationReason = EscalationReason.DEAD_END_OR_ZERO_TARGET
            deepEscalations.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
                reason = EscalationReason.DEAD_END_OR_ZERO_TARGET,
                consecutiveStuckCount = consecutiveStuckCount,
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 2 重型推理喚醒條件 5：最高分候選信心極低 (< 0.55f) 或前兩名候選分數極度重疊 (margin <= 0.04f)
        // =========================================================================
        if (fastPathGate.top1Score < 0.55f || fastPathGate.scoreMargin <= 0.04f) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER
            lastEscalationReason = EscalationReason.LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN
            deepEscalations.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
                reason = EscalationReason.LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN,
                consecutiveStuckCount = consecutiveStuckCount,
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 1（輕量 1024D 向量精排）：
        // 一般常規畫面，若未達 Level 0 快取/原生快篩門檻（信心 < 0.85 或 Margin < 0.15），
        // 僅執行本地 1024 維度向量餘弦相似度精排，不喚醒重型 CoT 與 MCTS/PDDL 規劃器！
        // =========================================================================
        val confidenceOrMarginInsufficient = !fastPathGate.allowed ||
            fastPathGate.top1Score < DeterminismHardeningEngine.FAST_PATH_CONFIDENCE_THRESHOLD ||
            fastPathGate.scoreMargin < DeterminismHardeningEngine.FAST_PATH_MARGIN_THRESHOLD ||
            !fastPathGate.attributeCompatible

        if (confidenceOrMarginInsufficient) {
            currentTier = ComputeTier.TIER_1_ESCALATED_DEEP
            currentLevel = ComputeLevel.LEVEL_1_LIGHT_1024D_VECTOR_RERANK
            lastEscalationReason = EscalationReason.LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN
            level1VectorHits.incrementAndGet()
            return buildDecision(
                tier = ComputeTier.TIER_1_ESCALATED_DEEP,
                level = ComputeLevel.LEVEL_1_LIGHT_1024D_VECTOR_RERANK,
                reason = EscalationReason.LOW_CONFIDENCE_OR_AMBIGUOUS_MARGIN,
                consecutiveStuckCount = consecutiveStuckCount,
                fastPathGate = fastPathGate
            )
        }

        // =========================================================================
        // Level 0（0ms 快取與原生快篩）：
        // 情節記憶庫或原生分類器命中信心與領先幅度達標，0ms 直接執行動作，完全跳過 C++ 模型推論與樹搜尋規劃器！
        // =========================================================================
        currentTier = ComputeTier.TIER_0_FAST_PATH
        currentLevel = ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE
        lastEscalationReason = EscalationReason.NONE
        fastPathHits.incrementAndGet()
        return buildDecision(
            tier = ComputeTier.TIER_0_FAST_PATH,
            level = ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE,
            reason = EscalationReason.NONE,
            consecutiveStuckCount = consecutiveStuckCount,
            fastPathGate = fastPathGate
        )
    }

    /**
     * 偵測當前畫面是否存在「輕量級彈窗規則無法直接排除」的未知彈窗或遮罩狀態
     */
    fun hasUnknownModalOcclusion(report: VisionPipelineReport): Boolean {
        val hasScrim = report.stage1AllNodes.any { it.raw.isModalScrim }
        val hasNanoDialog = report.finalNodes.any { it.nanoDetCategory == "UI_DIALOG_CONTAINER" }
        val isModalPosture = report.screenPosture == "POSTURE_PERMISSION_MODAL" ||
            report.screenPosture == "POSTURE_POPUP_DIALOG"
        val hasDialogNode = report.finalNodes.any { node ->
            node.className.contains("Dialog", ignoreCase = true) ||
                node.className.contains("PopupWindow", ignoreCase = true) ||
                node.inlineStatePrefix.contains("未知彈窗") ||
                node.inlineStatePrefix.contains("遮擋彈窗")
        }
        return hasScrim || hasNanoDialog || isModalPosture || hasDialogNode
    }

    /**
     * 偵測當前是否處於「跨 App 複雜邏輯分支切換點」：
     * 例如剛從上一個 App 階段攜帶動態記憶切換至新目標 App，且面臨多重路線/分享對象/入口分支（且尚無本地情節記憶錨點）。
     */
    fun isCrossAppComplexBranchTransition(
        report: VisionPipelineReport,
        activeSubGoal: CooperativeSubGoal,
        previousAppPhase: Int,
        previousPkg: String,
        currentPkg: String,
        hasEpisodicMemoryAnchor: Boolean
    ): Boolean {
        if (hasEpisodicMemoryAnchor) return false

        val isMultiAppTask = activeSubGoal.totalAppBlocks >= 2 || activeSubGoal.appPhaseIndex >= 2
        if (!isMultiAppTask) return false

        val phaseJustSwitched = activeSubGoal.appPhaseIndex > previousAppPhase ||
            (previousPkg.isNotBlank() && currentPkg.isNotBlank() && previousPkg != currentPkg)

        val goalText = "${activeSubGoal.targetSemanticPhrase} ${activeSubGoal.simplifiedMotorGoal}".lowercase()
        val hasComplexBranchSemantics = goalText.contains("規劃路線") ||
            goalText.contains("路線規劃") ||
            goalText.contains("導航") ||
            goalText.contains("分享給") ||
            goalText.contains("傳給") ||
            goalText.contains("轉發") ||
            activeSubGoal.inputPayload?.contains("\$MEMORY") == true

        val actionableBranchCount = report.finalNodes.count {
            it.shortTypeTag == "BTN" || it.shortTypeTag == "INP" || it.shortTypeTag == "TAB"
        }

        return (phaseJustSwitched && actionableBranchCount >= 2) ||
            (activeSubGoal.appPhaseIndex >= 2 && hasComplexBranchSemantics && actionableBranchCount >= 2)
    }

    private fun buildDecision(
        tier: ComputeTier,
        level: ComputeLevel = if (tier == ComputeTier.TIER_0_FAST_PATH) ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE else ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
        reason: EscalationReason,
        consecutiveStuckCount: Int,
        fastPathGate: DeterminismHardeningEngine.FastPathGateEvaluation
    ): ComputeGateDecision {
        val badge = when (level) {
            ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE ->
                "[算力門控:TIER_0/Level0極速通道(0ms, top1=${"%.2f".format(fastPathGate.top1Score)}, margin=${"%.2f".format(fastPathGate.scoreMargin)})]"
            ComputeLevel.LEVEL_1_LIGHT_1024D_VECTOR_RERANK ->
                "[算力門控:Level1輕量1024D向量精排(免喚醒CoT/MCTS, top1=${"%.2f".format(fastPathGate.top1Score)}, margin=${"%.2f".format(fastPathGate.scoreMargin)})]"
            ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER ->
                "[算力門控:升維TIER_1/Level2深層推理(原因=${reason.code}, stuck=${maxOf(consecutiveStuckCount, sameFingerprintStreak)}, top1=${"%.2f".format(fastPathGate.top1Score)}, margin=${"%.2f".format(fastPathGate.scoreMargin)})]"
        }
        return ComputeGateDecision(
            tier = tier,
            level = level,
            canTakeFastPath = level == ComputeLevel.LEVEL_0_ZERO_MS_CACHE_AND_NATIVE,
            isLevel1LightVectorOnly = level == ComputeLevel.LEVEL_1_LIGHT_1024D_VECTOR_RERANK,
            isLevel2HeavyPlanner = level == ComputeLevel.LEVEL_2_HEAVY_COT_MCTS_PLANNER,
            escalationReason = reason,
            sameFingerprintStreak = sameFingerprintStreak,
            consecutiveStuckCount = consecutiveStuckCount,
            top1Score = fastPathGate.top1Score,
            scoreMargin = fastPathGate.scoreMargin,
            diagnosticBadge = badge
        )
    }
}
