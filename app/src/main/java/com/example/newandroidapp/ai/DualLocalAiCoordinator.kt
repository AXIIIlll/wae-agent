package com.example.newandroidapp.ai

import android.content.Context
import android.graphics.Point
import android.os.SystemClock
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.stability.BackwardChainLevel
import com.example.newandroidapp.stability.BackwardChainingPlanner
import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.EpisodicAppMemory
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.stability.PlanScript
import com.example.newandroidapp.stability.SafetyGateManager
import com.example.newandroidapp.stability.TerminalVerb
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 雙模型四階合作流水線協調器（零預設詞庫，支援動態探索手機任意已安裝 App、App 內三階段搜尋、走錯路自動返回與跨任務經驗記憶庫）
 *
 * - 第一階 [任務拆解、App 自動匹配與 App 內操作鏈路由]：
 *   結合安卓原生語法解析、Needle 3 (`ROLE_GOAL_EXTRACTOR`)、`InstalledAppLauncher` 與 `InAppOperationPlanner`：
 *   自動將跨 App 與 App 內多步任務拆解為：`啟動目標 App` -> `自動排除彈窗並展開搜尋/導航入口` -> `填入關鍵字並送出搜尋` -> `於結果列表定位目標並點擊操作`。
 * - 第二階 [頂層阻擋彈窗排除 + 視覺拓樸快篩 + 經驗記憶庫 (`EpisodicAppMemory`) + 走錯路自動返回 (`Auto-Backtrack`)]：
 *   優先透過 `detectTopLayerBlocker()` 攔截廣告/權限/更新彈窗；結合三階段搜尋狀態機 (Stage A/B/C)、工作記憶防重複點擊濾波器 (`-45f` / `-60f`)、
 *   跨任務經驗記憶庫 (`+24f` 搜尋入口 / `+20f` 成功路徑 / `-55f` 死胡同陷阱) 與走錯路自動退回上一頁。
 * - 第三階 [神經語義向量精排與 Tool-Call 仲裁 (Needle 3 主導)]：
 *   僅針對 Top-6 漏斗候選執行 `needle_embed` (1024 維向量 Cosine Similarity) 與 `needle_complete` (Schema Tool-Call)。
 * - 第四階 [高風險安全閘門與未遮擋座標驗證 (雙模型聯合把關)]：
 *   強制檢查敏感操作 (`C!`) 並綁定四階段視覺未遮擋安全座標 (Pole of Inaccessibility)。
 */
object DualLocalAiCoordinator {

    private const val FAST_PATH_CONFIDENCE_THRESHOLD = DeterminismHardeningEngine.FAST_PATH_CONFIDENCE_THRESHOLD
    private const val FAST_PATH_MARGIN_THRESHOLD = DeterminismHardeningEngine.FAST_PATH_MARGIN_THRESHOLD
    private const val FUNNEL_TOP_K = 6

    private val HIGH_RISK_KEYWORDS get() = SafetyGateManager.HIGH_RISK_KEYWORDS

    fun decideNextAction(
        mode: LocalAiBackendMode,
        userGoal: String,
        report: VisionPipelineReport,
        context: Context?,
        currentForegroundPackage: String? = null,
        alreadyLaunchedApp: Boolean = false,
        activeSubGoalIndex: Int = 0,
        lockToCurrentApp: Boolean = false,
        consecutiveScrollNoChangeCount: Int = 0,
        consecutiveStuckCount: Int = 0
    ): AiActionDecision {
        return decide(
            mode = mode,
            userGoal = userGoal,
            report = report,
            context = context,
            currentForegroundPackage = currentForegroundPackage,
            alreadyLaunchedApp = alreadyLaunchedApp,
            activeSubGoalIndex = activeSubGoalIndex,
            lockToCurrentApp = lockToCurrentApp,
            consecutiveScrollNoChangeCount = consecutiveScrollNoChangeCount,
            consecutiveStuckCount = consecutiveStuckCount
        )
    }

    fun decide(
        mode: LocalAiBackendMode,
        userGoal: String,
        report: VisionPipelineReport,
        context: Context?,
        currentForegroundPackage: String? = null,
        alreadyLaunchedApp: Boolean = false,
        activeSubGoalIndex: Int = 0,
        lockToCurrentApp: Boolean = false,
        consecutiveScrollNoChangeCount: Int = 0,
        consecutiveStuckCount: Int = 0
    ): AiActionDecision {
        return when (mode) {
            LocalAiBackendMode.ANDROID_NATIVE_AI -> {
                executeFourStageCooperativePipeline(
                    rawUserGoal = userGoal,
                    report = report,
                    context = context,
                    currentForegroundPackage = currentForegroundPackage,
                    alreadyLaunchedApp = alreadyLaunchedApp,
                    activeSubGoalIndex = activeSubGoalIndex,
                    lockToCurrentApp = lockToCurrentApp,
                    consecutiveScrollNoChangeCount = consecutiveScrollNoChangeCount,
                    consecutiveStuckCount = consecutiveStuckCount,
                    forceNativeOnly = true
                )
            }
            LocalAiBackendMode.NEEDLE_3 -> {
                executeFourStageCooperativePipeline(
                    rawUserGoal = userGoal,
                    report = report,
                    context = context,
                    currentForegroundPackage = currentForegroundPackage,
                    alreadyLaunchedApp = alreadyLaunchedApp,
                    activeSubGoalIndex = activeSubGoalIndex,
                    lockToCurrentApp = lockToCurrentApp,
                    consecutiveScrollNoChangeCount = consecutiveScrollNoChangeCount,
                    consecutiveStuckCount = consecutiveStuckCount,
                    forceNeedleOnly = true
                )
            }
            LocalAiBackendMode.DUAL_FUSION -> {
                executeFourStageCooperativePipeline(
                    rawUserGoal = userGoal,
                    report = report,
                    context = context,
                    currentForegroundPackage = currentForegroundPackage,
                    alreadyLaunchedApp = alreadyLaunchedApp,
                    activeSubGoalIndex = activeSubGoalIndex,
                    lockToCurrentApp = lockToCurrentApp,
                    consecutiveScrollNoChangeCount = consecutiveScrollNoChangeCount,
                    consecutiveStuckCount = consecutiveStuckCount
                )
            }
        }
    }

    private fun safeElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun executeFourStageCooperativePipeline(
        rawUserGoal: String,
        report: VisionPipelineReport,
        context: Context?,
        currentForegroundPackage: String?,
        alreadyLaunchedApp: Boolean,
        activeSubGoalIndex: Int,
        lockToCurrentApp: Boolean,
        consecutiveScrollNoChangeCount: Int = 0,
        consecutiveStuckCount: Int = 0,
        forceNativeOnly: Boolean = false,
        forceNeedleOnly: Boolean = false
    ): AiActionDecision {
        val startMs = safeElapsedRealtime()
        val cleanGoal = rawUserGoal.trim()

        EpisodicAppMemory.ensureLoaded(context)
        InAppWorkingMemory.ensureGoalContext(cleanGoal)

        if (lockToCurrentApp && !currentForegroundPackage.isNullOrBlank() &&
            !DeterminismHardeningEngine.isForeignNoisePackage(currentForegroundPackage)
        ) {
            InAppWorkingMemory.setExpectedTargetPackage(currentForegroundPackage)
        }

        // 【確定性加固第 4 項：動態背景雜訊隔離與 10px 空間固定排序】
        // 在建立畫面特徵前，強制剔除系統狀態列、導覽列、非當前目標 App 包名以及動態時間/電量/網速文字節點
        @Suppress("NAME_SHADOWING")
        val report = DeterminismHardeningEngine.sanitizeVisionReport(
            report = report,
            expectedTargetPackage = InAppWorkingMemory.expectedTargetPackage,
            foregroundAppPackage = currentForegroundPackage.orEmpty()
        )

        val turnCtx = ConversationManager.getTurnContext()

        // 【安全閘門二次確認檢查（「確認執行」或「取消」）】
        val safetyCheck = SafetyGateManager.checkSafetyConfirmation(cleanGoal, turnCtx)
        when (safetyCheck) {
            is SafetyGateManager.SafetyConfirmationResult.Confirmed -> {
                SafetyGateManager.clearPendingAction()
                val pending = safetyCheck.pendingAction
                val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                return AiActionDecision(
                    engineName = "雙模型合作 (安全閘門二次確認)",
                    actionType = AiActionType.TAP,
                    targetNode = null,
                    targetMarkId = pending.targetMarkId,
                    tapPixel = Point(pending.tapPixelX, pending.tapPixelY).apply { x = pending.tapPixelX; y = pending.tapPixelY },
                    tapGrid0to999 = Point(pending.gridX, pending.gridY).apply { x = pending.gridX; y = pending.gridY },
                    compactOpcode = "TAP(CONFIRMED_#${pending.targetMarkId ?: 0})",
                    confidence = 0.99f,
                    latencyMs = totalMs,
                    reasoningTrace = "安全閘門二次確認通過：確認執行「${pending.label}」@${pending.tapPixelX},${pending.tapPixelY}"
                )
            }
            is SafetyGateManager.SafetyConfirmationResult.Cancelled -> {
                SafetyGateManager.clearPendingAction()
                val pending = safetyCheck.pendingAction
                val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                val cancelTrace = "已取消高風險操作「${pending.label}」，安全閘門待確認動作已清除。"
                return AiActionDecision(
                    engineName = "雙模型合作 (安全閘門取消)",
                    actionType = AiActionType.ANSWER_QUESTION,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    inputTextValue = cancelTrace,
                    compactOpcode = "CANCEL_SAFE",
                    confidence = 0.99f,
                    latencyMs = totalMs,
                    reasoningTrace = cancelTrace
                )
            }
            is SafetyGateManager.SafetyConfirmationResult.NoPendingAction -> {
                val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                return AiActionDecision(
                    engineName = "雙模型合作 (安全閘門狀態)",
                    actionType = AiActionType.ANSWER_QUESTION,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    inputTextValue = safetyCheck.message,
                    compactOpcode = "ANS",
                    confidence = 0.99f,
                    latencyMs = totalMs,
                    reasoningTrace = safetyCheck.message
                )
            }
            SafetyGateManager.SafetyConfirmationResult.NotApplicable -> {
                // 繼續後續正常管線
            }
        }

        // 【零手勢即時問答 (Zero-Tap: 算術 / 進度 / 畫面重點 / 日常對話)】
        val zeroTapAns = AndroidNativeAiEngine.checkZeroTapQuery(cleanGoal, report)
        if (zeroTapAns != null) {
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "雙模型合作 (Zero-Tap)",
                actionType = AiActionType.ANSWER_QUESTION,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                inputTextValue = zeroTapAns,
                compactOpcode = "ANS",
                confidence = 0.99f,
                latencyMs = totalMs,
                reasoningTrace = "零手勢即時問答回覆：$zeroTapAns"
            )
        }

        // 【口語追問多輪直接動作 (Multi-Turn Follow-Up: 滑動 / 候選項目直選)】
        val lowerGoal = cleanGoal.lowercase()
        if (lowerGoal.contains("往下滑") || lowerGoal.contains("向下滑") || lowerGoal == "下滑") {
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "雙模型合作 (口語追問滑動)",
                actionType = AiActionType.SCROLL_DOWN,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SD",
                confidence = 0.96f,
                latencyMs = totalMs,
                reasoningTrace = "口語追問指令解析為向下捲動列表 (SD)"
            )
        }
        if (lowerGoal.contains("往上滑") || lowerGoal.contains("向上滑") || lowerGoal == "上滑") {
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "雙模型合作 (口語追問滑動)",
                actionType = AiActionType.SCROLL_UP,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SU",
                confidence = 0.96f,
                latencyMs = totalMs,
                reasoningTrace = "口語追問指令解析為向上捲動列表 (SU)"
            )
        }

        if (lowerGoal.contains("往左滑") || lowerGoal.contains("向左滑") || lowerGoal.contains("左滑") || lowerGoal.contains("左翻") || lowerGoal == "swipe left") {
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "雙模型合作 (口語追問滑動)",
                actionType = AiActionType.SWIPE_LEFT,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SL",
                confidence = 0.96f,
                latencyMs = totalMs,
                reasoningTrace = "口語追問指令解析為向左滑動手勢 (SL)"
            )
        }

        if (lowerGoal.contains("往右滑") || lowerGoal.contains("向右滑") || lowerGoal.contains("右滑") || lowerGoal.contains("右翻") || lowerGoal == "swipe right") {
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "雙模型合作 (口語追問滑動)",
                actionType = AiActionType.SWIPE_RIGHT,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SR",
                confidence = 0.96f,
                latencyMs = totalMs,
                reasoningTrace = "口語追問指令解析為向右滑動手勢 (SR)"
            )
        }

        // =========================================================================
        // 【第一階：口語常識轉譯、代名詞指代消解、任務拆解與 App 動態匹配（純單指令獨立執行模式）】
        // =========================================================================
        val colloquialRes = CognitiveReasoningEngine.resolveColloquialAndContextualGoal(cleanGoal)
        val effectiveGoal = colloquialRes.normalizedGoal
        val subGoals = decomposeUserGoalCooperatively(
            rawGoal = effectiveGoal,
            context = context,
            lockToCurrentApp = lockToCurrentApp
        )
        var safeIdx = activeSubGoalIndex.coerceIn(0, (subGoals.size - 1).coerceAtLeast(0))
        var activeSubGoal = subGoals[safeIdx]

        // 檢查當前子目標是否為「開啟手機已安裝之目標 App」（支援跨 App 第二/第三階段再次啟動新 App！）
        val appHintToUse = activeSubGoal.targetAppHint ?: colloquialRes.inferredAppHint
        val isSelfAppInForeground = !currentForegroundPackage.isNullOrBlank() &&
            (currentForegroundPackage == context?.packageName || AppIdentityConfig.isSelfAssistantPackage(currentForegroundPackage))
        val isCrossAppLaunchStep = activeSubGoal.intentCategory == "LAUNCH_APP" && activeSubGoal.appPhaseIndex >= 2
        val canAttemptLaunch = (!alreadyLaunchedApp || isCrossAppLaunchStep || isSelfAppInForeground) && !lockToCurrentApp && appHintToUse != null
        var targetAppAlreadyInForeground = false
        if (canAttemptLaunch && (context != null || !currentForegroundPackage.isNullOrBlank())) {
            val matchedApp = if (context != null) {
                InstalledAppLauncher.resolveTargetAppForGoal(
                    context = context,
                    rawUserGoal = appHintToUse ?: effectiveGoal,
                    needleAppHint = appHintToUse
                )
            } else {
                CrossAppWorkflowEngine.resolveFallbackAppInfo(appHintToUse)?.let { it to 0.96f }
            }
            if (matchedApp != null) {
                if (isCrossAppLaunchStep) {
                    InAppWorkingMemory.transitionToNextAppPhase(matchedApp.first.packageName)
                } else {
                    InAppWorkingMemory.setExpectedTargetPackage(matchedApp.first.packageName)
                }
                if (matchedApp.first.packageName == currentForegroundPackage) {
                    targetAppAlreadyInForeground = true
                    InAppWorkingMemory.recordAppLaunched(matchedApp.first.packageName)
                }
            }
            if (matchedApp != null && matchedApp.first.packageName != currentForegroundPackage) {
                val appInfo = matchedApp.first
                InAppWorkingMemory.recordAppLaunched(appInfo.packageName)
                val memBadge = InAppWorkingMemory.summaryBadge()
                val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                val coopLog = CooperativePipelineLog(
                    stage1Decomposition = "動態探索手機已安裝 App (階段 ${activeSubGoal.appPhaseIndex}) -> 命中「${appInfo.label}」(${appInfo.packageName}, 信心=${"%.2f".format(matchedApp.second)})",
                    stage2NativeFunnel = "目標 App 尚未處於前景，優先執行系統級 Intent 啟動",
                    stage3NeedleNeural = "待目標 App 啟動並擷取首屏視覺樹後再執行內部元件精排",
                    stage4SafetyAndCoord = "自動啟動 App：OPEN_APP(${appInfo.label}) $memBadge",
                    fastPathTriggered = true
                )
                return AiActionDecision(
                    engineName = "雙模型四階合作 (動態啟動 App)",
                    actionType = AiActionType.LAUNCH_APP,
                    targetAppPackage = appInfo.packageName,
                    targetAppLabel = appInfo.label,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    compactOpcode = "OPEN_APP(${appInfo.label})",
                    confidence = matchedApp.second,
                    latencyMs = totalMs,
                    reasoningTrace = "自動啟動手機已安裝 App：「${appInfo.label}」(${appInfo.packageName}) $memBadge",
                    cooperativeLog = coopLog,
                    decomposedSubGoals = subGoals
                )
            }
        }

        // 若這是純「打開某 App」的單步指令且該 App 已經開啟，直接宣告完成
        if (activeSubGoal.intentCategory == "LAUNCH_APP" && subGoals.size == 1 && (alreadyLaunchedApp || targetAppAlreadyInForeground)) {
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            val memBadge = InAppWorkingMemory.summaryBadge()
            return AiActionDecision(
                engineName = "雙模型四階合作",
                actionType = AiActionType.FINISH_DONE,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "F1 (DONE)",
                confidence = 0.99f,
                latencyMs = totalMs,
                reasoningTrace = "目標 App 已成功開啟至前景，任務完成 (F1) $memBadge",
                decomposedSubGoals = subGoals
            )
        }

        // 若當前子目標為 LAUNCH_APP，但目標 App 已經在前景（或已啟動過），且後面還有 App 內操作子目標（如 INPUT_SEARCH），自動推進至下一子目標！
        if (activeSubGoal.intentCategory == "LAUNCH_APP" && safeIdx + 1 < subGoals.size) {
            safeIdx += 1
            activeSubGoal = subGoals[safeIdx]
        }

        // 【三階段指令編譯管線 Stage 3：硬性驗收條件（Exit Condition）閘門】
        // 針對 SEARCH_IN_APP(keyword) 原語，必須同時滿足：
        // 1. 關鍵字已注入並送出 (hasSubmittedEquivalentQuery == true)
        // 2. 無殘留未送出之焦點輸入框
        // 3. 非輸入框之結果區域已出現該關鍵字（或搜尋結果頁已載入）
        // 100% 達成硬性驗收條件後，狀態機才可將下一個動作積木推進至執行層！
        while (activeSubGoal.intentCategory in setOf("INPUT_SEARCH", "TAP_TARGET", "TOGGLE_SWITCH") && safeIdx + 1 < subGoals.size) {
            if (activeSubGoal.intentCategory == "TAP_TARGET" && consecutiveStuckCount > 0) {
                break
            }
            val exitVerdict = InstructionCompilerPipeline.evaluateExitCondition(
                subGoal = activeSubGoal,
                report = report,
                currentForegroundPackage = currentForegroundPackage,
                alreadyLaunchedApp = alreadyLaunchedApp
            )
            if (exitVerdict.satisfied) {
                safeIdx += 1
                activeSubGoal = subGoals[safeIdx]
            } else {
                break
            }
        }

        // 若最後一個子目標為 TAP_TARGET（例如「點擊 行車路線」），且在當前畫面上已完成該目標按鈕（或同義詞/同角色按鈕如「路線」）的點擊驗收，
        // 立即撤銷動手權限並回傳 FINISH_DONE (F1)，嚴禁在同一畫面繼續瘋狂點擊其他按鈕！
        if (safeIdx >= subGoals.size - 1 && activeSubGoal.intentCategory == "TAP_TARGET" && consecutiveStuckCount == 0) {
            val finalTapVerdict = InstructionCompilerPipeline.evaluateExitCondition(
                subGoal = activeSubGoal,
                report = report,
                currentForegroundPackage = currentForegroundPackage,
                alreadyLaunchedApp = alreadyLaunchedApp
            )
            if (finalTapVerdict.satisfied) {
                InAppWorkingMemory.revokeMotorPermissionImmediately("FINAL_TAP_GOAL_SATISFIED")
                val memBadge = InAppWorkingMemory.summaryBadge()
                val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                val coopLog = CooperativePipelineLog(
                    stage1Decomposition = "指令編譯原語[${safeIdx + 1}/${subGoals.size}]: ${finalTapVerdict.primitiveCall}",
                    stage2NativeFunnel = "[0ms 終端點擊完成斷電] ${finalTapVerdict.diagnosticReason}",
                    stage3NeedleNeural = "最終點擊目標已完成驗收，啟動動手權限撤銷閘門 (MotorPermissionGate)",
                    stage4SafetyAndCoord = "已撤銷動手權限並終止任務 F1 (DONE) $memBadge",
                    fastPathTriggered = true
                )
                return AiActionDecision(
                    engineName = "三大異質微引擎協同 (0ms完成斷電)",
                    actionType = AiActionType.FINISH_DONE,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    compactOpcode = "F1 (DONE)",
                    confidence = 0.99f,
                    latencyMs = totalMs,
                    reasoningTrace = "[0ms 完成斷電] 最終目標按鈕「${activeSubGoal.targetSemanticPhrase}」已點擊完成 (F1) $memBadge",
                    cooperativeLog = coopLog,
                    decomposedSubGoals = subGoals
                )
            }
        }

        // 【6 大標準動作原語：EXTRACT_TO_MEMORY(extractRule) 純內部資料擷取動作】
        // 不點擊 UI，直接從當前畫面樹提取目標文字清單寫入跨 App 暫存區 ($MEMORY) 與系統剪貼簿；
        // 若為最後一步則自動返回主畫面輸出至對話框！
        if (activeSubGoal.intentCategory == "EXTRACT_SCREEN_INFO") {
            val extracted = CrossAppWorkflowEngine.extractAndStoreFromReport(
                report = report,
                queryTopic = activeSubGoal.targetSemanticPhrase,
                sourceApp = currentForegroundPackage.orEmpty(),
                context = context
            )
            val isFinalStep = safeIdx >= subGoals.size - 1
            if (isFinalStep) {
                InAppWorkingMemory.revokeMotorPermissionImmediately("FINAL_EXTRACT_TO_MEMORY_DONE")
            }
            val primitiveCall = InstructionCompilerPipeline.mapSubGoalToCanonicalPrimitive(activeSubGoal).formalCall
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = "指令編譯原語[${safeIdx + 1}/${subGoals.size}]: $primitiveCall | 跨App階段[${activeSubGoal.appPhaseIndex}]畫面重點擷取: 主題=「${activeSubGoal.targetSemanticPhrase}」",
                stage2NativeFunnel = "掃描當前畫面 ${report.finalNodes.size} 個節點，過濾導覽列雜訊",
                stage3NeedleNeural = "純內部資料擷取（不點擊 UI），提煉高資訊密度結果存入 \$MEMORY 暫存區與系統剪貼簿",
                stage4SafetyAndCoord = "EXTRACT_CROSS_APP(\"$extracted\")",
                fastPathTriggered = true
            )
            return AiActionDecision(
                engineName = "自研跨App協同引擎 (畫面資訊擷取)",
                actionType = AiActionType.ANSWER_QUESTION,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                inputTextValue = extracted,
                compactOpcode = "EXTRACT_CROSS_APP(\"${extracted.take(32)}\")",
                confidence = 0.98f,
                latencyMs = totalMs,
                reasoningTrace = if (isFinalStep) {
                    "[EXTRACT_TO_MEMORY 完成] 已從當前畫面提取：「$extracted」，自動返回主畫面輸出至對話框"
                } else {
                    "[跨App資訊擷取] 已從當前畫面提煉重點：「$extracted」，準備帶往下一個 App 階段"
                },
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        val screenHash = InAppOperationPlanner.computeQuickScreenHash(report)
        val activePkg = currentForegroundPackage.orEmpty().ifBlank { InAppWorkingMemory.expectedTargetPackage }

        // =========================================================================
        // 【第三階段：執行期的「單步視野隔離（Single-Step Tunnel Vision）」】
        // 底層視覺與節點評分引擎嚴禁讀取使用者的原始長句子，每次僅能接收當前正在執行的那一個標準動作積木！
        // =========================================================================
        val tunnelVision = InstructionCompilerPipeline.buildSingleStepTunnelVision(activeSubGoal)
        val isolatedMotorGoal = tunnelVision.isolatedMotorGoal
        val effectiveSubGoalForPlanner = tunnelVision.isolatedSemanticTarget
        activeSubGoal = tunnelVision.isolatedSubGoal

        // =========================================================================
        // 【頂層阻擋彈窗攔截 (Top-Layer Blocker Dismissal)】
        // =========================================================================
        val blockerNode = InAppOperationPlanner.detectTopLayerBlocker(report, isolatedMotorGoal)
        if (blockerNode != null) {
            val blockerSig = "$screenHash|${InAppWorkingMemory.buildCandidateSignature(blockerNode)}"
            InAppWorkingMemory.recordBlockerDismissed(blockerSig)
            InAppWorkingMemory.recordElementClicked(blockerNode, screenHash)
            val memBadge = InAppWorkingMemory.summaryBadge()
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            val blockerReason = "[自動排除阻擋彈窗] 偵測到頂層干擾元件 #${blockerNode.shortMarkId}「${blockerNode.label}」，優先點擊排除 $memBadge"
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = "子目標[${safeIdx + 1}/${subGoals.size}](${tunnelVision.formalPrimitiveCall}): 執行前自動掃描頂層阻擋層",
                stage2NativeFunnel = "[自動排除阻擋彈窗] 命中 #${blockerNode.shortMarkId}「${blockerNode.label}」",
                stage3NeedleNeural = "頂層彈窗優先攔截，免喚醒長耗時推論",
                stage4SafetyAndCoord = "排除阻擋彈窗 TAP(#${blockerNode.shortMarkId}) $memBadge",
                fastPathTriggered = true
            )
            return AiActionDecision(
                engineName = "雙模型四階合作 (自動排除阻擋彈窗)",
                actionType = AiActionType.TAP,
                targetNode = blockerNode,
                targetMarkId = blockerNode.shortMarkId,
                tapPixel = blockerNode.finalTapPx,
                tapGrid0to999 = blockerNode.gridTapPoint,
                compactOpcode = "TAP(#${blockerNode.shortMarkId}) @${blockerNode.gridTapPoint.x},${blockerNode.gridTapPoint.y}",
                confidence = 0.97f,
                latencyMs = totalMs,
                reasoningTrace = blockerReason,
                topCandidates = listOf(blockerNode to 0.97f),
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        val stage1Log = buildString {
            if (colloquialRes.cognitiveNote.isNotBlank()) {
                append("${colloquialRes.cognitiveNote} | ")
            }
            append("[大塊 ${activeSubGoal.appPhaseIndex}/${activeSubGoal.totalAppBlocks}(${activeSubGoal.targetAppHint ?: "當前畫面"})] ")
            append("子目標[${safeIdx + 1}/${subGoals.size}]: 原語=${tunnelVision.formalPrimitiveCall}, 類型=${activeSubGoal.intentCategory}, 單步視野=「$isolatedMotorGoal」, 目標語義=「${activeSubGoal.targetSemanticPhrase}」")
            if (!activeSubGoal.inputPayload.isNullOrBlank()) {
                append(", 待填文字=「${activeSubGoal.inputPayload}」")
            }
        }

        val nativeFirstPass = AndroidNativeAiEngine.decide(
            userGoal = isolatedMotorGoal,
            report = report,
            context = context
        )

        val isPreFillInputSubGoal = (activeSubGoal.intentCategory == "INPUT_SEARCH" || activeSubGoal.intentCategory == "INPUT_TEXT") &&
            !InAppWorkingMemory.hasSubmittedEquivalentQuery(
                activeSubGoal.inputPayload?.takeIf { it.isNotBlank() }
                    ?: InAppOperationPlanner.extractSearchQueryAndFollowUp(activeSubGoal.targetSemanticPhrase).first
            )

        if ((nativeFirstPass.actionType == AiActionType.NAVIGATE_BACK && !isPreFillInputSubGoal) ||
            nativeFirstPass.actionType == AiActionType.SWIPE_LEFT ||
            nativeFirstPass.actionType == AiActionType.SWIPE_RIGHT ||
            nativeFirstPass.actionType == AiActionType.LONG_PRESS ||
            nativeFirstPass.actionType == AiActionType.DOUBLE_TAP ||
            nativeFirstPass.actionType == AiActionType.SCROLL_DOWN && activeSubGoal.intentCategory == "SCROLL" ||
            nativeFirstPass.actionType == AiActionType.SCROLL_UP && activeSubGoal.intentCategory == "SCROLL"
        ) {
            if (nativeFirstPass.actionType == AiActionType.SCROLL_DOWN || nativeFirstPass.actionType == AiActionType.SCROLL_UP) {
                InAppWorkingMemory.recordScroll()
            }
            val memBadge = InAppWorkingMemory.summaryBadge()
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = stage1Log,
                stage2NativeFunnel = "原生 AI 0ms 直接識別系統導航/手勢指令 (${nativeFirstPass.compactOpcode})",
                stage3NeedleNeural = "已由第二階 0ms 快速通道鎖定，免喚醒 Needle 3",
                stage4SafetyAndCoord = "執行手勢/系統動作，耗時 ${totalMs}ms $memBadge",
                fastPathTriggered = true
            )
            return nativeFirstPass.copy(
                engineName = "雙模型四階合作 (Stage2 0ms快篩)",
                latencyMs = totalMs,
                reasoningTrace = "${nativeFirstPass.reasoningTrace} $memBadge",
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        // 結合 InAppOperationPlanner、CognitiveReasoningEngine 與 EpisodicAppMemory 進行三階段搜尋狀態機 (Stage A/B/C)、邏輯診斷、經驗記憶與防重複點擊評估
        val inAppEval = InAppOperationPlanner.evaluateInAppStateOverrideOrAdjustScores(
            report = report,
            userGoal = isolatedMotorGoal,
            currentSubGoal = effectiveSubGoalForPlanner,
            baseCandidates = nativeFirstPass.topCandidates,
            context = context,
            currentPackage = activePkg,
            screenFp = screenHash,
            sessionGoalId = cleanGoal
        )

        // =========================================================================
        // 【升級 #1：走錯路自動返回上一頁 (Auto-Backtrack)】
        // 當誤點進入無目標死胡同內頁時，自動彈出麵包屑退回上一頁並將該誤點元件寫入 EpisodicAppMemory 陷阱庫
        // =========================================================================
        val backtrackBreadcrumb = InAppOperationPlanner.shouldTriggerAutoBacktrack(
            report = report,
            userGoal = isolatedMotorGoal,
            activeSubGoal = activeSubGoal,
            bestScore = inAppEval.bestRawScore,
            consecutiveScrollNoChangeCount = consecutiveScrollNoChangeCount
        )
        if (backtrackBreadcrumb != null) {
            if (activePkg.isNotBlank()) {
                EpisodicAppMemory.recordDeadEndTrap(
                    context = context,
                    packageName = activePkg,
                    screenFp = backtrackBreadcrumb.fromScreenFp,
                    node = backtrackBreadcrumb.clickedNode
                )
            }
            InAppWorkingMemory.recordElementClicked(backtrackBreadcrumb.clickedNode, backtrackBreadcrumb.fromScreenFp)
            val memBadge = InAppWorkingMemory.summaryBadge()
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            val backtrackReason = "[走錯路自動返回] 當前頁面無目標項目，自動退回上一頁並排除「${backtrackBreadcrumb.clickedNode.label}」 $memBadge"
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = stage1Log,
                stage2NativeFunnel = "偵測到死胡同頁面 (最高分=${"%.1f".format(inAppEval.bestRawScore)})，觸發導航堆疊自動回退",
                stage3NeedleNeural = "已將「${backtrackBreadcrumb.clickedNode.label}」記入 EpisodicAppMemory 死胡同陷阱庫",
                stage4SafetyAndCoord = "自動返回上一頁 BACK(AUTO_BACKTRACK) $memBadge",
                fastPathTriggered = true
            )
            return AiActionDecision(
                engineName = "雙模型四階合作 (走錯路自動返回)",
                actionType = AiActionType.NAVIGATE_BACK,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "BACK(AUTO_BACKTRACK)",
                confidence = 0.94f,
                latencyMs = totalMs,
                reasoningTrace = backtrackReason,
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        val adjustedTopCandidates = DeterminismHardeningEngine.sortScoredCandidatesCanonically(
            inAppEval.adjustedCandidates.ifEmpty { nativeFirstPass.topCandidates }
        )
        val fastPathGate = DeterminismHardeningEngine.evaluateFastPathGate(
            scoredCandidates = adjustedTopCandidates,
            activeSubGoal = activeSubGoal,
            inAppStage = inAppEval.stage,
            resolvedActionType = inAppEval.overrideActionType,
            totalScreenNodeCount = report.finalNodes.size
        )
        val topAdjustedPair = adjustedTopCandidates.firstOrNull()
        val topAdjustedScore = if (adjustedTopCandidates.isNotEmpty()) fastPathGate.top1Score else nativeFirstPass.confidence
        val scoreLead = fastPathGate.scoreMargin

        // 【0ms 終端搜尋目標完成斷電閘門（免喚醒 PDDL A* 求解器）】：
        // 若當前已是最後一個子目標 (INPUT_SEARCH)，且工作記憶確認同定義查詢已送出（且非空結果重搜），
        // 立即撤銷 AI 動手權限並回傳 FINISH_DONE，禁止在結果頁重複點擊搜尋框或無差別啟動形式化規劃求解器！
        val terminalCheckQuery = activeSubGoal.inputPayload?.takeIf { it.isNotBlank() }
            ?: InAppOperationPlanner.extractSearchQueryAndFollowUp(activeSubGoal.targetSemanticPhrase).first
        val isTerminalSearchAlreadySubmitted = if (terminalCheckQuery.isNotBlank()) {
            InAppWorkingMemory.hasSubmittedEquivalentQuery(terminalCheckQuery)
        } else {
            InAppWorkingMemory.hasAnySubmittedQuery()
        }
        if (isTerminalSearchAlreadySubmitted &&
            activeSubGoal.intentCategory == "INPUT_SEARCH" &&
            safeIdx >= subGoals.size - 1 &&
            !inAppEval.stageReasoningNote.contains("自我反思與動態改道")
        ) {
            InAppWorkingMemory.revokeMotorPermissionImmediately("FINAL_SEARCH_GOAL_SATISFIED")
            val memBadge = InAppWorkingMemory.summaryBadge()
            val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = stage1Log,
                stage2NativeFunnel = "[0ms 完成斷電] 同定義查詢「$terminalCheckQuery」已送出並顯示結果",
                stage3NeedleNeural = "同定義查詢目標已完成單次搜尋送出，啟動動手權限撤銷閘門 (MotorPermissionGate)",
                stage4SafetyAndCoord = "已撤銷動手權限並終止任務 F1 (DONE) $memBadge",
                fastPathTriggered = true
            )
            return AiActionDecision(
                engineName = "三大異質微引擎協同 (0ms完成斷電)",
                actionType = AiActionType.FINISH_DONE,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "F1 (DONE)",
                confidence = 0.99f,
                latencyMs = totalMs,
                reasoningTrace = "[0ms 完成斷電] 目標查詢已完成送出，已立即關閉動手權限 (F1) $memBadge",
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        val hasInAppStageOverride = inAppEval.overrideTargetNode != null && inAppEval.overrideActionType != null

        // =========================================================================
        // 【動態算力分級門控 (Adaptive Compute Gating)】：
        // 1. 常規畫面優先由輕量級原生漏斗 + 本地情節記憶庫處理，若信心度與領先幅度達標且無異常，
        //    直接以 0ms 極速通道執行動作，嚴禁啟動 CoT 語言推理、MCTS 樹搜尋或形式化規劃求解器。
        // 2. 僅在「畫面指紋連續兩次未變」、「信心度/Margin 不足」或「跨 App 複雜分支 / 未知彈窗遮擋」時，
        //    才按需解鎖喚醒高階協調引擎 (TriBrainCoordinatorEngine)。
        // =========================================================================
        val computeGateDecision = AdaptiveComputeGate.evaluateGate(
            report = report,
            screenHash = screenHash,
            activeSubGoal = activeSubGoal,
            inAppStage = inAppEval.stage,
            fastPathGate = fastPathGate,
            foregroundPackage = activePkg,
            consecutiveStuckCount = consecutiveStuckCount,
            consecutiveScrollNoChangeCount = consecutiveScrollNoChangeCount,
            hasEpisodicMemoryAnchor = !inAppEval.episodicMemoryNote.isNullOrBlank(),
            blockerAlreadyResolved = false,
            forceDeepReasoning = forceNeedleOnly
        )
        val canTakeFastPath = computeGateDecision.canTakeFastPath

        val chosenNode: Stage2ConvergedNode?
        val chosenConfidence: Float
        val finalCandidates: List<Pair<Stage2ConvergedNode, Float>>
        val stage2Log: String
        val stage3Log: String

        if (canTakeFastPath) {
            chosenNode = inAppEval.overrideTargetNode ?: topAdjustedPair?.first ?: nativeFirstPass.targetNode
            chosenConfidence = if (hasInAppStageOverride) inAppEval.overrideConfidence else topAdjustedScore
            finalCandidates = adjustedTopCandidates.take(6)
            val epSuffix = if (!inAppEval.episodicMemoryNote.isNullOrBlank()) " [${inAppEval.episodicMemoryNote}]" else ""
            val nanoSuffix = if (chosenNode?.sourceType == NodeSourceType.NANODET_UI_DETECT) {
                " [五模型協同: NanoDet(${"%.2f".format(chosenNode.visualConfidence)})+OCR]"
            } else ""
            stage2Log = if (inAppEval.stageReasoningNote.isNotBlank()) {
                "${inAppEval.stageReasoningNote} | ${computeGateDecision.diagnosticBadge}$epSuffix$nanoSuffix"
            } else {
                "[Level 0 極速通道(margin=${"%.2f".format(scoreLead)})] 信心度 ${"%.2f".format(chosenConfidence)} 鎖定 #${chosenNode?.shortMarkId}「${chosenNode?.label}」 | ${computeGateDecision.diagnosticBadge}$epSuffix$nanoSuffix"
            }
            stage3Log = "[動態算力門控:LEVEL_0_ZERO_MS_CACHE_AND_NATIVE] 0ms 直接執行，完全跳過 C++ 模型推論與樹搜尋規劃器"
        } else if (computeGateDecision.isLevel1LightVectorOnly) {
            val baseFunnelNodes = buildTopKFunnelForNeedle3(
                activeSubGoal = activeSubGoal,
                report = report,
                nativeRanked = adjustedTopCandidates
            )
            val overrideNode = inAppEval.overrideTargetNode
            val rawFunnelNodes = if (overrideNode != null && baseFunnelNodes.none { it.nodeId == overrideNode.nodeId }) {
                listOf(overrideNode) + baseFunnelNodes
            } else {
                baseFunnelNodes
            }
            val funnelNodes = DeterminismHardeningEngine.sortTopKCandidatesForSlotMapping(rawFunnelNodes, maxSlots = 8)
            val plannerCandidatesForVec = if (overrideNode != null) {
                val filtered = adjustedTopCandidates.filterNot { it.first.nodeId == overrideNode.nodeId }
                DeterminismHardeningEngine.sortScoredCandidatesCanonically(
                    listOf(overrideNode to inAppEval.overrideConfidence) + filtered
                )
            } else {
                adjustedTopCandidates
            }

            val level1Eval = TriBrainCoordinatorEngine.evaluateLevel1LightVector1024Rerank(
                candidates = funnelNodes,
                report = report,
                activeSubGoal = activeSubGoal,
                isolatedMotorGoal = isolatedMotorGoal,
                inAppStage = inAppEval.stage,
                plannerRanked = plannerCandidatesForVec,
                screenHash = screenHash
            )
            val bestCompatibleFromVec = level1Eval.rankedCandidates.firstOrNull { (cand, _) ->
                DeterminismHardeningEngine.isNodeAttributeCompatibleWithAction(
                    node = cand,
                    activeSubGoal = activeSubGoal,
                    inAppStage = inAppEval.stage,
                    resolvedActionType = inAppEval.overrideActionType
                )
            } ?: level1Eval.rankedCandidates.firstOrNull()

            val isOverrideCompatibleVec = overrideNode != null &&
                DeterminismHardeningEngine.isNodeAttributeCompatibleWithAction(
                    node = overrideNode,
                    activeSubGoal = activeSubGoal,
                    inAppStage = inAppEval.stage,
                    resolvedActionType = inAppEval.overrideActionType
                )
            chosenNode = if (isOverrideCompatibleVec && inAppEval.overrideConfidence >= 0.92f) {
                overrideNode
            } else {
                bestCompatibleFromVec?.first ?: overrideNode ?: nativeFirstPass.targetNode
            }
            chosenConfidence = maxOf(bestCompatibleFromVec?.second ?: 0f, topAdjustedScore)
            finalCandidates = level1Eval.rankedCandidates.take(6)
            val epSuffix = if (!inAppEval.episodicMemoryNote.isNullOrBlank()) " [${inAppEval.episodicMemoryNote}]" else ""
            stage2Log = buildString {
                if (inAppEval.stageReasoningNote.isNotBlank()) {
                    append(inAppEval.stageReasoningNote).append(" | ")
                }
                append(computeGateDecision.diagnosticBadge).append(" | ")
                append("[Level 1 輕量1024維向量精排] 初始信心 ${"%.2f".format(topAdjustedScore)}(margin=${"%.2f".format(scoreLead)})$epSuffix")
            }
            stage3Log = "${level1Eval.diagnosticText} | 免喚醒 CoT 思維鏈與 MCTS/PDDL 規劃器"
        } else {
            val baseFunnelNodes = buildTopKFunnelForNeedle3(
                activeSubGoal = activeSubGoal,
                report = report,
                nativeRanked = adjustedTopCandidates
            )
            val overrideNode = inAppEval.overrideTargetNode
            val rawFunnelNodes = if (overrideNode != null && baseFunnelNodes.none { it.nodeId == overrideNode.nodeId }) {
                listOf(overrideNode) + baseFunnelNodes
            } else {
                baseFunnelNodes
            }
            // 【確定性加固第 2 項：候選節點代號 (A~H) 的 10px 空間固定排序】
            val funnelNodes = DeterminismHardeningEngine.sortTopKCandidatesForSlotMapping(rawFunnelNodes, maxSlots = 8)
            val plannerCandidatesForTri = if (overrideNode != null) {
                val filtered = adjustedTopCandidates.filterNot { it.first.nodeId == overrideNode.nodeId }
                DeterminismHardeningEngine.sortScoredCandidatesCanonically(
                    listOf(overrideNode to inAppEval.overrideConfidence) + filtered
                )
            } else {
                adjustedTopCandidates
            }

            val prunedPercent = if (report.finalNodes.isNotEmpty()) {
                (((report.finalNodes.size - funnelNodes.size).toFloat() / report.finalNodes.size) * 100).toInt().coerceAtLeast(0)
            } else 0

            val epSuffix = if (!inAppEval.episodicMemoryNote.isNullOrBlank()) " | ${inAppEval.episodicMemoryNote}" else ""

            // =========================================================================
            // 【全面升格為主腦：三大異質微引擎協同 (Micro-Llama Q4 CoT + MiniSat/PDDL + TRM 3步遞迴 & 64-Rollout MCTS)】
            // 每一輪決策皆經過 C++ MiniSat/SMT 硬約束驗算、PDDL 狀態規劃與 TRM 16維視覺與座標張量評分（移除舊 Needle3 依賴）
            // =========================================================================
            val triBrainEval = TriBrainCoordinatorEngine.evaluateTriBrainPipeline(
                candidates = funnelNodes,
                report = report,
                activeSubGoal = activeSubGoal,
                isolatedMotorGoal = isolatedMotorGoal,
                inAppStage = inAppEval.stage,
                plannerRanked = plannerCandidatesForTri,
                neuralRanked = plannerCandidatesForTri,
                foregroundPackage = activePkg,
                screenHash = screenHash,
                context = context
            )

            val rawReScoredTri = triBrainEval.rankedCandidates.map { (node, triScore) ->
                val plannerScore = plannerCandidatesForTri.firstOrNull { it.first.nodeId == node.nodeId }?.second ?: 0.14f
                val nanoDetBonus = if (node.sourceType == NodeSourceType.NANODET_UI_DETECT) {
                    (node.visualConfidence - 0.45f).coerceAtLeast(0f) * 0.16f
                } else 0f
                val combined = if (plannerScore <= 0.22f && triScore <= 0.22f) {
                    minOf(triScore, plannerScore)
                } else {
                    (triScore * 0.65f + plannerScore * 0.35f + nanoDetBonus).coerceIn(0.05f, 0.99f)
                }
                node to combined
            }
            val reScoredTri = DeterminismHardeningEngine.sortScoredCandidatesCanonically(rawReScoredTri)

            val overrideIdx = if (overrideNode != null) {
                funnelNodes.indexOfFirst { it.nodeId == overrideNode.nodeId }
            } else -1
            val isOverrideLegalBySat = overrideIdx >= 0 &&
                overrideIdx < triBrainEval.satSmtAudit.legalMask.size &&
                triBrainEval.satSmtAudit.legalMask[overrideIdx].toInt() != 0
            val isOverrideAttributeCompatible = DeterminismHardeningEngine.isNodeAttributeCompatibleWithAction(
                node = overrideNode,
                activeSubGoal = activeSubGoal,
                inAppStage = inAppEval.stage,
                resolvedActionType = inAppEval.overrideActionType
            )
            val canOverridePreemptTriBrain = overrideNode != null &&
                isOverrideLegalBySat &&
                isOverrideAttributeCompatible &&
                (fastPathGate.allowed || reScoredTri.firstOrNull()?.first?.nodeId == overrideNode.nodeId)

            val bestCompatibleFromTri = reScoredTri.firstOrNull { (cand, _) ->
                DeterminismHardeningEngine.isNodeAttributeCompatibleWithAction(
                    node = cand,
                    activeSubGoal = activeSubGoal,
                    inAppStage = inAppEval.stage,
                    resolvedActionType = inAppEval.overrideActionType
                )
            } ?: reScoredTri.firstOrNull()

            val isDeterministicFollowUpMatch = nativeFirstPass.engineName.contains("多輪追問快篩") &&
                nativeFirstPass.targetNode != null &&
                nativeFirstPass.confidence >= DeterminismHardeningEngine.FAST_PATH_CONFIDENCE_THRESHOLD

            chosenNode = if (isDeterministicFollowUpMatch) {
                nativeFirstPass.targetNode
            } else if (canOverridePreemptTriBrain) {
                overrideNode
            } else {
                bestCompatibleFromTri?.first
                    ?: (if (isOverrideLegalBySat && isOverrideAttributeCompatible) overrideNode else null)
                    ?: nativeFirstPass.targetNode
            }
            chosenConfidence = if (isDeterministicFollowUpMatch && chosenNode?.nodeId == nativeFirstPass.targetNode?.nodeId) {
                nativeFirstPass.confidence
            } else if (canOverridePreemptTriBrain && chosenNode?.nodeId == overrideNode?.nodeId) {
                maxOf(inAppEval.overrideConfidence, bestCompatibleFromTri?.second ?: 0f)
            } else if ((bestCompatibleFromTri?.second ?: 0f) <= 0.21f && topAdjustedScore <= 0.22f) {
                bestCompatibleFromTri?.second ?: 0.14f
            } else {
                maxOf(bestCompatibleFromTri?.second ?: 0f, topAdjustedScore)
            }
            finalCandidates = reScoredTri.take(6)
            val nanoSuffix = if (chosenNode?.sourceType == NodeSourceType.NANODET_UI_DETECT) {
                " [五模型協同: NanoDet(${"%.2f".format(chosenNode.visualConfidence)})+OCR]"
            } else ""
            val gateNote = if (!fastPathGate.allowed) " [FastPath防搶跑鎖:${fastPathGate.blockReason}]" else ""
            stage2Log = buildString {
                if (inAppEval.stageReasoningNote.isNotBlank()) {
                    append(inAppEval.stageReasoningNote).append(" | ")
                }
                append(computeGateDecision.diagnosticBadge).append(" | ")
                append("[主腦初篩+PDDL/SAT] 初始信心 ${"%.2f".format(topAdjustedScore)}(margin=${"%.2f".format(scoreLead)})$gateNote$epSuffix，已過濾 ${prunedPercent}% 節點 | ")
                append(triBrainEval.satSmtAudit.diagnosticText).append(" | ")
                append(triBrainEval.pddlPlan.diagnosticText)
                append(nanoSuffix)
            }
            val multiModelWeldTag = if (chosenNode?.sourceType == NodeSourceType.NANODET_UI_DETECT) {
                " [五模型協同: NanoDet(${"%.2f".format(chosenNode.visualConfidence)})+OCR+TRM/MCTS]"
            } else ""
            stage3Log = "${triBrainEval.cotThoughtChain} | ${triBrainEval.trmMctsDiagnostic}$multiModelWeldTag"
        }

        val totalMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)

        if (chosenNode == null || chosenConfidence < 0.22f) {
            if (consecutiveScrollNoChangeCount >= 2 ||
                InAppWorkingMemory.currentSubPageScrollCount >= 5 ||
                InAppWorkingMemory.hasAttemptedReverseScrollUpOnSubPage
            ) {
                InAppWorkingMemory.revokeMotorPermissionImmediately("TARGET_NOT_FOUND_AFTER_SCROLL")
                val notFoundMsg = "翻到底都沒看到這個按鍵，沒辦法按。"
                val coopLog = CooperativePipelineLog(
                    stage1Decomposition = stage1Log,
                    stage2NativeFunnel = stage2Log,
                    stage3NeedleNeural = stage3Log,
                    stage4SafetyAndCoord = "已雙向滑動尋找但畫面仍無目標按鍵，立即撤銷動手權限並回報無法執行",
                    fastPathTriggered = false
                )
                return AiActionDecision(
                    engineName = "雙模型四階合作 (找不到目標按鍵)",
                    actionType = AiActionType.ANSWER_QUESTION,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    inputTextValue = notFoundMsg,
                    compactOpcode = "ABORT_NOT_FOUND",
                    confidence = 0.99f,
                    latencyMs = totalMs,
                    reasoningTrace = notFoundMsg,
                    topCandidates = finalCandidates,
                    cooperativeLog = coopLog,
                    decomposedSubGoals = subGoals
                )
            }

            if (consecutiveScrollNoChangeCount == 1) {
                val horizAnchor = if (!InAppWorkingMemory.hasAttemptedHorizontalSwipeOnSubPage) {
                    InAppOperationPlanner.findHorizontalScrollAnchorNode(report)
                } else null

                if (horizAnchor != null) {
                    InAppWorkingMemory.markHorizontalSwipeAttempted()
                    val memBadge = InAppWorkingMemory.summaryBadge()
                    val coopLog = CooperativePipelineLog(
                        stage1Decomposition = stage1Log,
                        stage2NativeFunnel = stage2Log,
                        stage3NeedleNeural = stage3Log,
                        stage4SafetyAndCoord = "垂直滑動已到底，偵測到橫向輪播/膠囊列，自動向左滑動探索 (SL_EXPLORE) $memBadge",
                        fastPathTriggered = false
                    )
                    return AiActionDecision(
                        engineName = "雙模型四階合作 (橫向滑動探索)",
                        actionType = AiActionType.SWIPE_LEFT,
                        targetNode = horizAnchor,
                        targetMarkId = horizAnchor.shortMarkId,
                        tapPixel = horizAnchor.finalTapPx,
                        tapGrid0to999 = horizAnchor.gridTapPoint,
                        compactOpcode = "SL_EXPLORE",
                        confidence = 0.58f,
                        latencyMs = totalMs,
                        reasoningTrace = "垂直滑動到底，向左滑動橫向列尋找隱藏按鍵 $memBadge",
                        topCandidates = finalCandidates,
                        cooperativeLog = coopLog,
                        decomposedSubGoals = subGoals
                    )
                }

                InAppWorkingMemory.markReverseScrollUpAttempted()
                InAppWorkingMemory.recordScroll()
                val memBadge = InAppWorkingMemory.summaryBadge()
                val coopLog = CooperativePipelineLog(
                    stage1Decomposition = stage1Log,
                    stage2NativeFunnel = stage2Log,
                    stage3NeedleNeural = stage3Log,
                    stage4SafetyAndCoord = "下方已滑到底仍未見目標，自動向上回滑一次確認上方是否漏掉目標按鍵 (SU_EXPLORE) $memBadge",
                    fastPathTriggered = false
                )
                return AiActionDecision(
                    engineName = "雙模型四階合作 (向上回滑補查)",
                    actionType = AiActionType.SCROLL_UP,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    compactOpcode = "SU_EXPLORE",
                    confidence = 0.56f,
                    latencyMs = totalMs,
                    reasoningTrace = "下方已到底，往上滑一次確認目標按鍵是否在上方 $memBadge",
                    topCandidates = finalCandidates,
                    cooperativeLog = coopLog,
                    decomposedSubGoals = subGoals
                )
            }

            InAppWorkingMemory.recordScroll()
            val memBadge = InAppWorkingMemory.summaryBadge()
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = stage1Log,
                stage2NativeFunnel = stage2Log,
                stage3NeedleNeural = stage3Log,
                stage4SafetyAndCoord = "當前視野未出現目標按鍵，自動向下滑動探索列表 (SD) $memBadge",
                fastPathTriggered = false
            )
            return AiActionDecision(
                engineName = "雙模型四階合作 (自動下滑探索)",
                actionType = AiActionType.SCROLL_DOWN,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SD",
                confidence = 0.55f,
                latencyMs = totalMs,
                reasoningTrace = "當前畫面沒看到所需按鍵，往下滑動尋找 $memBadge",
                topCandidates = finalCandidates,
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        // =========================================================================
        // 【第四階：高風險安全閘門與未遮擋座標驗證 (雙模型聯合把關)】
        // =========================================================================
        val isHighRisk = activeSubGoal.isHighRiskHint || SafetyGateManager.isHighRiskAction(chosenNode.label, isolatedMotorGoal)

        if (isHighRisk) {
            SafetyGateManager.recordPendingAction(
                node = chosenNode,
                targetMarkId = chosenNode.shortMarkId,
                tapPx = chosenNode.finalTapPx,
                gridPt = chosenNode.gridTapPoint,
                opcode = "C! -> #${chosenNode.shortMarkId} @${chosenNode.gridTapPoint.x},${chosenNode.gridTapPoint.y}",
                packageName = currentForegroundPackage
            )
            val memBadge = InAppWorkingMemory.summaryBadge()
            val stage4Log = "[安全閘門攔截 C!] 目標 #${chosenNode.shortMarkId}「${chosenNode.label}」涉及敏感操作，已強制暫停待確認！ $memBadge"
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = stage1Log,
                stage2NativeFunnel = stage2Log,
                stage3NeedleNeural = stage3Log,
                stage4SafetyAndCoord = stage4Log,
                fastPathTriggered = canTakeFastPath
            )
            return AiActionDecision(
                engineName = "雙模型四階合作 (安全閘門攔截)",
                actionType = AiActionType.CONFIRM_HIGH_RISK,
                targetNode = chosenNode,
                targetMarkId = chosenNode.shortMarkId,
                tapPixel = chosenNode.finalTapPx,
                tapGrid0to999 = chosenNode.gridTapPoint,
                compactOpcode = "C! -> #${chosenNode.shortMarkId} @${chosenNode.gridTapPoint.x},${chosenNode.gridTapPoint.y}",
                confidence = chosenConfidence,
                latencyMs = totalMs,
                reasoningTrace = stage4Log,
                topCandidates = finalCandidates,
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        val rawResolvedPayload = inAppEval.overrideInputText
            ?: activeSubGoal.inputPayload
            ?: inAppEval.extractedQuery.takeIf { it.isNotBlank() }
        val resolvedInputPayload = CrossAppWorkflowEngine.resolveDynamicPayload(rawResolvedPayload)

        // 【確定性加固第 3 項 + 畫面終審鐵律：INPUT 動作僅限於 INPUT_SEARCH/INPUT_TEXT/PASTE_CLIPBOARD 子目標且節點為 INP，TAP_TARGET 100% 禁止輸入文字】
        val isInputAllowedSubGoal = activeSubGoal.intentCategory in setOf("INPUT_SEARCH", "PASTE_CLIPBOARD", "INPUT_TEXT")
        val shouldPerformInputText = isInputAllowedSubGoal &&
            !resolvedInputPayload.isNullOrBlank() &&
            chosenNode.shortTypeTag == "INP" &&
            ((inAppEval.overrideActionType == AiActionType.INPUT_TEXT && !InAppWorkingMemory.hasExactSubmittedQuery(resolvedInputPayload)) ||
                (!InAppWorkingMemory.hasSubmittedQuery(resolvedInputPayload) &&
                    inAppEval.stage != InAppOperationStage.STAGE_C_SELECT_RESULT))

        if (shouldPerformInputText && !resolvedInputPayload.isNullOrBlank()) {
            InAppWorkingMemory.recordTextTypedAndSubmitted(resolvedInputPayload, "")
            InAppWorkingMemory.recordElementClicked(chosenNode, screenHash)
            val memBadge = InAppWorkingMemory.summaryBadge()
            val stage4Log = "[安全放行＋文字輸入送出] #${chosenNode.shortMarkId}「${chosenNode.label}」填入「$resolvedInputPayload」-> (${chosenNode.finalTapPx.x},${chosenNode.finalTapPx.y}px) $memBadge"
            val coopLog = CooperativePipelineLog(
                stage1Decomposition = stage1Log,
                stage2NativeFunnel = stage2Log,
                stage3NeedleNeural = stage3Log,
                stage4SafetyAndCoord = stage4Log,
                fastPathTriggered = canTakeFastPath
            )
            val baseTrace = if (canTakeFastPath) stage2Log else stage3Log
            return AiActionDecision(
                engineName = if (canTakeFastPath) "雙模型合作 (In-App Stage B 輸入)" else "雙模型合作 (Needle3語義精排)",
                actionType = AiActionType.INPUT_TEXT,
                targetNode = chosenNode,
                targetMarkId = chosenNode.shortMarkId,
                tapPixel = chosenNode.finalTapPx,
                tapGrid0to999 = chosenNode.gridTapPoint,
                inputTextValue = resolvedInputPayload,
                compactOpcode = "INPUT(#${chosenNode.shortMarkId}, \"$resolvedInputPayload\") @${chosenNode.gridTapPoint.x},${chosenNode.gridTapPoint.y}",
                confidence = chosenConfidence,
                latencyMs = totalMs,
                reasoningTrace = "$baseTrace $memBadge",
                topCandidates = finalCandidates,
                cooperativeLog = coopLog,
                decomposedSubGoals = subGoals
            )
        }

        // 記錄點擊動作與導航麵包屑至工作記憶（單次計數，不重複累加 globalClickedSignatures）
        if (inAppEval.stage == InAppOperationStage.STAGE_A_EXPAND_SEARCH ||
            (activeSubGoal.intentCategory == "INPUT_SEARCH" && chosenNode.shortTypeTag != "INP")
        ) {
            InAppWorkingMemory.recordSearchEntryClicked("", chosenNode)
        } else {
            InAppWorkingMemory.pushBreadcrumb(
                fromScreenFp = screenHash,
                clickedNode = chosenNode,
                subGoalIndex = safeIdx,
                stage = inAppEval.stage
            )
        }
        InAppWorkingMemory.recordElementClicked(chosenNode, screenHash)
        val memBadge = InAppWorkingMemory.summaryBadge()

        val stage4Log = "[安全放行＋未遮擋座標綁定] #${chosenNode.shortMarkId}「${chosenNode.label}」露出率 ${(chosenNode.visibleRatio * 100).toInt()}% -> 像素 (${chosenNode.finalTapPx.x},${chosenNode.finalTapPx.y}px) $memBadge"
        val coopLog = CooperativePipelineLog(
            stage1Decomposition = stage1Log,
            stage2NativeFunnel = stage2Log,
            stage3NeedleNeural = stage3Log,
            stage4SafetyAndCoord = stage4Log,
            fastPathTriggered = canTakeFastPath
        )
        val baseTrace = if (canTakeFastPath) stage2Log else stage3Log

        val isLongPress = activeSubGoal.intentCategory == "LONG_PRESS" || isolatedMotorGoal.contains("長按") || isolatedMotorGoal.contains("按住")
        val isDoubleTap = activeSubGoal.intentCategory == "DOUBLE_TAP" || isolatedMotorGoal.contains("雙擊") || isolatedMotorGoal.contains("連按兩下") || isolatedMotorGoal.contains("點兩下") || isolatedMotorGoal.contains("按兩次")
        val finalActionType = when {
            isLongPress -> AiActionType.LONG_PRESS
            isDoubleTap -> AiActionType.DOUBLE_TAP
            else -> AiActionType.TAP
        }
        val finalOpcode = when {
            isLongPress -> "LP(#${chosenNode.shortMarkId}) @${chosenNode.gridTapPoint.x},${chosenNode.gridTapPoint.y}"
            isDoubleTap -> "DT(#${chosenNode.shortMarkId}) @${chosenNode.gridTapPoint.x},${chosenNode.gridTapPoint.y}"
            else -> "TAP(#${chosenNode.shortMarkId}) @${chosenNode.gridTapPoint.x},${chosenNode.gridTapPoint.y}"
        }

        return AiActionDecision(
            engineName = if (canTakeFastPath) "雙模型合作 (Stage2 0ms快篩)" else "雙模型合作 (Needle3語義精排)",
            actionType = finalActionType,
            targetNode = chosenNode,
            targetMarkId = chosenNode.shortMarkId,
            tapPixel = chosenNode.finalTapPx,
            tapGrid0to999 = chosenNode.gridTapPoint,
            compactOpcode = finalOpcode,
            confidence = chosenConfidence,
            latencyMs = totalMs,
            reasoningTrace = "$baseTrace $memBadge",
            topCandidates = finalCandidates,
            cooperativeLog = coopLog,
            decomposedSubGoals = subGoals
        )
    }

    @Volatile
    private var cachedPlanScript: PlanScript? = null

    @Volatile
    private var cachedSubGoalsForGoal: Pair<String, List<CooperativeSubGoal>>? = null

    @Volatile
    private var cachedHierarchicalPlan: HierarchicalCommandChunker.HierarchicalExecutionPlan? = null

    fun getActivePlanScript(): PlanScript? = cachedPlanScript

    fun getActiveHierarchicalPlan(): HierarchicalCommandChunker.HierarchicalExecutionPlan? = cachedHierarchicalPlan

    fun resetPlanScriptCache() {
        cachedPlanScript = null
        cachedSubGoalsForGoal = null
        cachedHierarchicalPlan = null
    }

    /**
     * 【優化方向 1：受控重規劃入口】
     * 行動層照 `PlanScript` 執行期間，僅當驗收失敗、出現非預期彈窗或連續無變化時才觸發重規劃；
     * 若重規劃次數超過上限 (`MAX_REPLAN_LIMIT = 3`)，立即回傳 null 通知停止迴圈。
     */
    fun requestReplanIfAllowed(
        rawGoal: String,
        context: Context?,
        lockToCurrentApp: Boolean = false
    ): PlanScript? {
        val clean = rawGoal.trim()
        val prevCount = if (cachedPlanScript?.rawGoal == clean) cachedPlanScript?.replanCount ?: 0 else 0
        val nextCount = prevCount + 1
        if (nextCount > ClosedLoopVerifier.MAX_REPLAN_LIMIT) {
            return null
        }
        val freshSubGoals = decomposeUserGoalInternal(clean, context, lockToCurrentApp)
        val newScript = ClosedLoopVerifier.generatePlanScript(clean, freshSubGoals, replanCount = nextCount)
        cachedSubGoalsForGoal = "$clean|$lockToCurrentApp" to freshSubGoals
        cachedPlanScript = newScript
        return newScript
    }

    /**
     * 第一階（決策層）：一次輸出整串結構化計畫 (`PlanScript` + `List<CooperativeSubGoal>`)。
     * 同一任務期間直接複用已規劃之 `PlanScript`，避免每一步重複呼叫 Needle 3 拆解意圖！
     */
    fun decomposeUserGoalCooperatively(
        rawGoal: String,
        context: Context?,
        lockToCurrentApp: Boolean = false
    ): List<CooperativeSubGoal> {
        val clean = rawGoal.trim()
        val cacheKey = "$clean|$lockToCurrentApp"
        val cached = cachedSubGoalsForGoal
        if (cached != null && cached.first == cacheKey && cached.second.isNotEmpty()) {
            return cached.second
        }

        val subGoals = decomposeUserGoalInternal(clean, context, lockToCurrentApp)
        cachedSubGoalsForGoal = cacheKey to subGoals
        cachedPlanScript = ClosedLoopVerifier.generatePlanScript(clean, subGoals, replanCount = 0)
        return subGoals
    }

    private fun decomposeUserGoalInternal(
        clean: String,
        context: Context?,
        lockToCurrentApp: Boolean = false
    ): List<CooperativeSubGoal> {
        // 統一由「先判斷需要用到幾個 App 切大塊 (AppMacroBlock) -> 再於塊內依序打碎成極簡短句 (AtomicMotorTask)」引擎處理
        val hierarchicalPlan = HierarchicalCommandChunker.chunkInstruction(
            rawGoal = clean,
            context = context,
            highRiskKeywords = HIGH_RISK_KEYWORDS,
            lockToCurrentApp = lockToCurrentApp
        )
        cachedHierarchicalPlan = hierarchicalPlan
        if (hierarchicalPlan.flattenedSubGoals.isNotEmpty()) {
            return hierarchicalPlan.flattenedSubGoals
        }

        val backwardSpec = BackwardChainingPlanner.extractTerminalGoalSpec(clean)
        val matchedInstalledApp = if (context != null && !lockToCurrentApp) {
            InstalledAppLauncher.resolveTargetAppForGoal(context, clean, backwardSpec.appHint)
        } else null

        val detectedAppName = if (lockToCurrentApp) null else (matchedInstalledApp?.first?.label ?: backwardSpec.appHint)
        val toggleHint = when {
            clean.contains("開啟") || clean.contains("打開") || clean.contains("啟用") -> true
            clean.contains("關閉") || clean.contains("關掉") || clean.contains("停用") -> false
            else -> null
        }

        val inAppSearchSubGoals = InAppOperationPlanner.buildCooperativeInAppSubGoals(
            rawGoal = clean,
            detectedAppName = detectedAppName,
            highRiskKeywords = HIGH_RISK_KEYWORDS,
            fallbackExtractor = {
                AndroidNativeAiEngine.extractInputPayload(it)
                    ?: InAppOperationPlanner.extractSearchQueryAndFollowUp(it).first.takeIf { sq -> sq.isNotBlank() }
            },
            toggleStateHint = toggleHint
        )
        if (!inAppSearchSubGoals.isNullOrEmpty()) {
            return inAppSearchSubGoals
        }

        val rawClauses = clean.split(Regex("(?:然後|接著|再來|之後|並|，|,|->)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(clean) }

        val expandedClauses = ArrayList<Pair<String, String?>>()
        for ((i, c) in rawClauses.withIndex()) {
            if (i == 0 && detectedAppName != null) {
                val appIdx = c.indexOf(detectedAppName, ignoreCase = true)
                val afterApp = if (appIdx >= 0) {
                    c.substring(appIdx + detectedAppName.length).trim()
                } else {
                    backwardSpec.targetEntity.trim()
                }
                if (afterApp.length >= 2 && !afterApp.equals(detectedAppName, ignoreCase = true)) {
                    expandedClauses.add("打開$detectedAppName" to detectedAppName)
                    expandedClauses.add(afterApp to null)
                    continue
                } else {
                    expandedClauses.add(c to detectedAppName)
                    continue
                }
            }
            expandedClauses.add(c to null)
        }

        val out = ArrayList<CooperativeSubGoal>(expandedClauses.size + 1)
        for ((idx, pair) in expandedClauses.withIndex()) {
            val (clause, appHint) = pair
            val lower = clause.lowercase()
            val isRisk = HIGH_RISK_KEYWORDS.any { lower.contains(it) }

            if (appHint != null && (
                lower.startsWith("打開") ||
                    lower.startsWith("開啟") ||
                    lower.startsWith("進入") ||
                    lower.startsWith("前往") ||
                    lower.startsWith("去") ||
                    lower == appHint.lowercase()
                )
            ) {
                out.add(
                    CooperativeSubGoal(
                        stepIndex = idx + 1,
                        totalSteps = expandedClauses.size,
                        intentCategory = "LAUNCH_APP",
                        targetAppHint = appHint,
                        targetSemanticPhrase = appHint,
                        isHighRiskHint = false
                    )
                )
                continue
            }

            val hasSearchOrInputIntent = InAppOperationPlanner.hasExplicitSearchOrInputIntent(clause)
            val (plannerQuery, plannerFollowUp) = if (hasSearchOrInputIntent) {
                InAppOperationPlanner.extractSearchQueryAndFollowUp(clause)
            } else {
                "" to ""
            }
            val inputVal = if (hasSearchOrInputIntent) {
                plannerQuery.ifBlank {
                    AndroidNativeAiEngine.extractInputPayload(clause) ?: backwardSpec.targetEntity
                }.takeIf { it.isNotBlank() }
            } else {
                null
            }

            val category = when {
                lower == "返回" || lower == "上一頁" -> "SYSTEM_BACK"
                lower.contains("下滑") || lower.contains("上滑") -> "SCROLL"
                lower.contains("左滑") || lower.contains("右滑") -> "GESTURE_SWIPE"
                lower.contains("長按") || lower.contains("按住") -> "LONG_PRESS"
                lower.contains("雙擊") || lower.contains("連按兩下") || lower.contains("點兩下") || lower.contains("連點兩下") -> "DOUBLE_TAP"
                inputVal != null -> "INPUT_SEARCH"
                lower.contains("開關") || toggleHint != null -> "TOGGLE_SWITCH"
                else -> "TAP_TARGET"
            }

            val targetPhrase = clause
                .replace(Regex("^(請幫我|幫我|我想|我要|請|點擊|點選|點一下|按一下|按下|打開|開啟|進入|選擇|去|把)"), "")
                .trim()
                .ifEmpty { clause }

            out.add(
                CooperativeSubGoal(
                    stepIndex = out.size + 1,
                    totalSteps = expandedClauses.size,
                    intentCategory = category,
                    targetAppHint = appHint,
                    targetSemanticPhrase = targetPhrase,
                    inputPayload = inputVal,
                    desiredToggleState = toggleHint,
                    isHighRiskHint = isRisk
                )
            )

            if (category == "INPUT_SEARCH" && inputVal != null &&
                InAppOperationPlanner.requiresResultSelectionAfterSearch(clean, detectedAppName, plannerFollowUp)
            ) {
                val followTarget = if (plannerFollowUp.isNotBlank()) "$inputVal $plannerFollowUp" else inputVal
                out.add(
                    CooperativeSubGoal(
                        stepIndex = out.size + 1,
                        totalSteps = expandedClauses.size + 1,
                        intentCategory = "TAP_TARGET",
                        targetAppHint = null,
                        targetSemanticPhrase = followTarget,
                        inputPayload = null,
                        desiredToggleState = toggleHint,
                        isHighRiskHint = isRisk
                    )
                )
            }
        }

        val finalTotal = out.size.coerceAtLeast(1)
        return out.mapIndexed { i, sub -> sub.copy(stepIndex = i + 1, totalSteps = finalTotal) }
    }

    private fun buildTopKFunnelForNeedle3(
        activeSubGoal: CooperativeSubGoal,
        report: VisionPipelineReport,
        nativeRanked: List<Pair<Stage2ConvergedNode, Float>>
    ): List<Stage2ConvergedNode> {
        val result = ArrayList<Stage2ConvergedNode>()
        val seenNodeIds = HashSet<String>()

        fun addNode(n: Stage2ConvergedNode) {
            if (seenNodeIds.add(n.nodeId)) {
                result.add(n)
            }
        }

        val queryAlreadySubmitted = !activeSubGoal.inputPayload.isNullOrBlank() &&
            InAppWorkingMemory.hasSubmittedQuery(activeSubGoal.inputPayload)

        if (activeSubGoal.intentCategory == "INPUT_SEARCH" && !queryAlreadySubmitted) {
            report.finalNodes.filter { it.shortTypeTag == "INP" }.forEach { addNode(it) }
        }
        if (activeSubGoal.intentCategory == "TOGGLE_SWITCH") {
            report.finalNodes.filter { it.shortTypeTag == "CHK" }.forEach { addNode(it) }
        }
        for ((node, score) in nativeRanked) {
            if (result.size >= FUNNEL_TOP_K) break
            if (score <= 0.15f) continue
            if (node.shortTypeTag != "TXT") {
                addNode(node)
            }
        }
        for (node in report.finalNodes) {
            if (result.size >= FUNNEL_TOP_K) break
            if (queryAlreadySubmitted && node.shortTypeTag == "INP") continue
            if (node.shortTypeTag != "TXT") {
                addNode(node)
            }
        }
        return DeterminismHardeningEngine.sortTopKCandidatesForSlotMapping(
            topCandidates = result.take(FUNNEL_TOP_K),
            maxSlots = FUNNEL_TOP_K
        )
    }
}
