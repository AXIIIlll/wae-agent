package com.example.newandroidapp.stability

import android.content.Context
import com.example.newandroidapp.ai.AiActionDecision
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.CooperativeSubGoal
import com.example.newandroidapp.ai.HierarchicalCommandChunker
import com.example.newandroidapp.ai.TriBrainCoordinatorEngine
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import kotlin.math.abs

/**
 * 動作複雜度分級枚舉（用於雙驗收官自動分流）：
 * - BASIC：基礎動作（開 App、滑動、返回、切換開關、一般字面明確按鈕點擊）-> 僅需「驗收官一號：Android 原生驗收官」獨立驗收。
 * - COMPLEX：複雜動作（搜尋輸入送出、隨機挑選、序數挑選、比較級挑選、跨步複合指令）-> 必須由「原生驗收官 + Needle 3 驗收官」雙驗收官聯合會審。
 */
enum class ActionComplexity {
    BASIC,
    COMPLEX
}

/**
 * 動作可逆性風險分級枚舉（優化方向 2）：
 * - REVERSIBLE：可逆動作（滑動、返回、點開信件、開 App、搜尋查詢）-> 依原複雜度分流。
 * - IRREVERSIBLE：不可逆動作（送出郵件、傳送、購買、付款、結帳、刪除、發布、確認訂單）-> 強制雙驗收官會審，且執行前須先詢問使用者確認！
 */
enum class ActionRisk {
    REVERSIBLE,
    IRREVERSIBLE
}

/**
 * 決策層下發給行動層的結構化單步行動指令
 */
data class ActionDirective(
    val stepIndex: Int,
    val totalSteps: Int,
    val intentCategory: String,
    val targetAppHint: String? = null,
    val targetSemanticPhrase: String,
    val inputPayload: String? = null
)

/**
 * 決策層一次產出的結構化計畫步驟（優化方向 1：`PlanStep`）
 */
data class PlanStep(
    val directive: ActionDirective,
    val complexity: ActionComplexity,
    val risk: ActionRisk,
    val expectedOutcome: String
)

/**
 * 決策層一次輸出的完整任務腳本（優化方向 1：`PlanScript`）
 * 行動層照單依序執行，僅在驗收失敗、出現非預期彈窗或連續兩次無變化時才觸發重規劃，且重規劃上限為 3 次。
 */
data class PlanScript(
    val rawGoal: String,
    val steps: List<PlanStep>,
    val replanCount: Int = 0,
    val maxReplans: Int = ClosedLoopVerifier.MAX_REPLAN_LIMIT
) {
    val isReplanLimitExceeded: Boolean
        get() = replanCount >= maxReplans
}

/**
 * 雙驗收官聯合審核裁決報告
 */
data class DualInspectorAuditResult(
    val complexity: ActionComplexity,
    val risk: ActionRisk,
    val nativeInspectorPassed: Boolean,
    val needleInspectorInvoked: Boolean,
    val needleInspectorPassed: Boolean,
    val subGoalSatisfied: Boolean,
    val terminalGoalReached: Boolean,
    val requiresUserConfirmationBeforeExec: Boolean,
    val requiresReplan: Boolean,
    val auditBadge: String,
    val auditDetail: String
)

/**
 * 【第 1、2、3、4 項】極致穩定閉環驗證引擎 (ClosedLoopVerifier)
 *
 * 1. [動作後驗證與候選降級重試]：比對動作前後的畫面結構與內容指紋 (ScreenFingerprint)，若無變化則改點第 2/3 名候選或改發無障礙節點事件。
 * 2. [卡關偵測與防盲目滑動]：追蹤連續相同畫面指紋，連續 2 次滑動畫面不變即判定「已滑到底/頂」，拒絕盲目空滑 12 次。
 * 3. [目標達成語義驗證]：每步動作後驗證目標頁面、輸入結果或目標狀態是否已出現在畫面上。
 * 4. [畫面穩定等待器 (Frame Stability Gate)]：檢查連續兩幀之間的節點變動率與載入狀態，防止轉場動畫半途搶跑點歪。
 */
object ClosedLoopVerifier {

    data class ScreenFingerprint(
        val packageName: String,
        val nodeCount: Int,
        val structuralAndLabelHash: Long,
        val topLabelsPreview: String,
        val dHash64: Long = 0L
    ) {
        fun shortCode(): String {
            val hex = java.lang.Long.toHexString(structuralAndLabelHash).takeLast(6).uppercase()
            val dhex = java.lang.Long.toHexString(dHash64).takeLast(4).uppercase()
            return "${packageName.substringAfterLast('.')}:${nodeCount}n#$hex:$dhex"
        }
    }

    /**
     * 計算當前畫面的 64-bit 結構與視覺空間差異雜湊 (dHash64)：
     * 結合零拷貝截圖 HardwareBuffer dHash64（若有）與 9x8 螢幕網格元件密度水平梯度雜湊。
     */
    fun computeScreenDHash64(report: VisionPipelineReport): Long {
        val grid = IntArray(8 * 9)
        for (node in report.finalNodes) {
            val gx = (node.gridTapPoint.x.coerceIn(0, 999) * 9) / 1000
            val gy = (node.gridTapPoint.y.coerceIn(0, 999) * 8) / 1000
            val weight = node.label.length.coerceIn(1, 16) +
                when (node.shortTypeTag) {
                    "INP" -> 19
                    "BTN" -> 13
                    "ICO" -> 11
                    "CHK" -> 17
                    else -> 7
                }
            grid[gy * 9 + gx] += weight + (node.label.hashCode() and 0x1F)
        }
        var layoutDHash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leftVal = grid[y * 9 + x]
                val rightVal = grid[y * 9 + x + 1]
                if (leftVal > rightVal || (leftVal == rightVal && leftVal > 0 && ((x + y) and 1) == 0)) {
                    layoutDHash = layoutDHash or (1L shl bit)
                }
                bit++
            }
        }
        val hwDHash = com.example.newandroidapp.ai.NeedleNativeBridge.lastFrameDHash64
        return if (hwDHash != 0L) (hwDHash xor layoutDHash) else layoutDHash
    }

    /**
     * 計算當前畫面的結構與語義指紋（結合前景 Package、可見元件數量、前 18 個核心元件的標籤與網格量化座標及 dHash64）
     */
    fun computeScreenFingerprint(foregroundPkg: String?, report: VisionPipelineReport): ScreenFingerprint {
        val pkg = foregroundPkg.orEmpty().ifBlank { "unknown" }
        var hash = 1125899906842597L
        val previewBuilder = StringBuilder()

        for ((idx, node) in report.finalNodes.take(18).withIndex()) {
            val itemSig = "${node.shortTypeTag}|${node.inlineStatePrefix}|${node.label.take(16)}|${node.gridTapPoint.x / 25},${node.gridTapPoint.y / 25}"
            for (ch in itemSig) {
                hash = 31L * hash + ch.code.toLong()
            }
            if (idx < 4 && node.label.isNotBlank()) {
                if (previewBuilder.isNotEmpty()) previewBuilder.append("/")
                previewBuilder.append(node.label.take(6))
            }
        }

        val dHash64 = computeScreenDHash64(report)

        return ScreenFingerprint(
            packageName = pkg,
            nodeCount = report.finalNodes.size,
            structuralAndLabelHash = hash,
            topLabelsPreview = previewBuilder.toString(),
            dHash64 = dHash64
        )
    }

    /**
     * 【第 1 項：動作後驗證】判斷動作執行前後，畫面是否發生了實質變化（同時驗證結構雜湊與 dHash64 差異）
     */
    fun hasScreenChangedMeaningfully(before: ScreenFingerprint?, after: ScreenFingerprint): Boolean {
        if (before == null) return true
        if (before.packageName != after.packageName) return true
        if (abs(before.nodeCount - after.nodeCount) >= 2) return true
        if (before.structuralAndLabelHash != after.structuralAndLabelHash) return true
        if (before.dHash64 != 0L && after.dHash64 != 0L) {
            val hamming = java.lang.Long.bitCount(before.dHash64 xor after.dHash64)
            if (hamming >= 4) return true
        }
        return false
    }

    /**
     * 【第 1 項：候選降級重試】當第一名候選點擊後畫面毫無變化時，從漏斗候選名單挑出尚未嘗試過的第 2 / 第 3 名候選節點
     */
    fun pickNextFallbackCandidate(
        decision: AiActionDecision,
        attemptedNodeKeySet: Set<String>
    ): Stage2ConvergedNode? {
        for ((candNode, score) in decision.topCandidates) {
            if (score < 0.25f) continue
            val key = "${candNode.label}@${candNode.gridTapPoint.x / 20},${candNode.gridTapPoint.y / 20}"
            if (!attemptedNodeKeySet.contains(key)) {
                return candNode
            }
        }
        return null
    }

    fun makeNodeAttemptKey(node: Stage2ConvergedNode): String {
        return "${node.label}@${node.gridTapPoint.x / 20},${node.gridTapPoint.y / 20}"
    }

    /**
     * 【第 4 項：畫面穩定等待器 (Frame Stability Gate)】
     * 比對間隔 140ms 的前後兩幀報告，判斷畫面是否仍在轉場動畫或劇烈載入中
     */
    fun isFrameStable(prevReport: VisionPipelineReport?, currReport: VisionPipelineReport): Boolean {
        if (currReport.finalNodes.isEmpty()) return false
        // 檢查是否有全螢幕載入中提示且元件極少
        val hasLoadingText = currReport.finalNodes.any { n ->
            val l = n.label.lowercase()
            l == "載入中" || l == "載入中..." || l == "loading" || l == "loading..." || l.contains("請稍候")
        }
        if (hasLoadingText && currReport.finalNodes.size <= 3) {
            return false
        }

        if (prevReport == null) return true

        val prevCount = prevReport.finalNodes.size
        val currCount = currReport.finalNodes.size
        val countDiff = abs(currCount - prevCount)
        // 若兩幀之間元件數量變動超過 30%（且差距 >= 4），代表頁面剛切換或列表還在彈出中
        if (countDiff >= 4 && countDiff.toFloat() / prevCount.coerceAtLeast(1).toFloat() > 0.30f) {
            return false
        }

        // 比對前 6 個主要元件的座標是否仍在位移（例如 BottomSheet 滑入動畫）
        val prevTop = prevReport.finalNodes.take(6)
        val currTop = currReport.finalNodes.take(6)
        if (prevTop.size == currTop.size && prevTop.isNotEmpty()) {
            var movingCount = 0
            for (i in prevTop.indices) {
                val p = prevTop[i]
                val c = currTop[i]
                if (p.label == c.label) {
                    val dist = abs(p.finalTapPx.x - c.finalTapPx.x) + abs(p.finalTapPx.y - c.finalTapPx.y)
                    if (dist > 24) movingCount++
                }
            }
            if (movingCount >= 2) return false
        }
        return true
    }

    /**
     * 【第 3 項：目標達成語義驗證】
     * 在動作執行並使畫面改變後，判斷當前子目標或整體任務是否已經在畫面上達成
     */
    fun isSubGoalSatisfiedOnNewScreen(
        activeSubGoal: CooperativeSubGoal?,
        beforeFp: ScreenFingerprint?,
        afterFp: ScreenFingerprint,
        afterReport: VisionPipelineReport,
        hasTypedTextInStep: Boolean,
        nextSubGoal: CooperativeSubGoal? = null,
        isExpandingSearchEntry: Boolean = false
    ): Boolean {
        if (activeSubGoal == null) return hasScreenChangedMeaningfully(beforeFp, afterFp)

        val screenChanged = hasScreenChangedMeaningfully(beforeFp, afterFp)
        if (!screenChanged) return false

        // 結合 HeadsUpNotificationDefenseGuard 嚴格把關子目標完成與跨 App 切換條件
        val strictVerified = HeadsUpNotificationDefenseGuard.verifyStrictSubGoalAndCrossAppCompletion(
            activeSubGoal = activeSubGoal,
            nextSubGoal = nextSubGoal,
            beforeFp = beforeFp,
            afterFp = afterFp,
            afterReport = afterReport,
            hasTypedTextInStep = hasTypedTextInStep
        )
        if (!strictVerified) return false

        return when (activeSubGoal.intentCategory) {
            "INPUT_SEARCH" -> {
                val payload = activeSubGoal.inputPayload?.trim()?.lowercase().orEmpty()
                if (payload.isEmpty()) {
                    screenChanged
                } else {
                    // 已經成功填入文字且畫面出現包含該搜尋詞的結果或新頁面
                    val payloadAppearsOnScreen = afterReport.finalNodes.any {
                        it.label.lowercase().contains(payload)
                    }
                    hasTypedTextInStep && (screenChanged || payloadAppearsOnScreen)
                }
            }
            "TOGGLE_SWITCH" -> {
                // 開關狀態改變即視為完成
                screenChanged
            }
            "TAP_TARGET" -> {
                // TAP_TARGET 防護：若前一步驟為展開搜尋入口 (isExpandingSearchEntry)，
                // 或畫面尚未出現目標實體，絕不可僅因畫面結構改變就提前判定子目標達成！
                if (isExpandingSearchEntry) {
                    val targetPayload = activeSubGoal.targetSemanticPhrase.trim().lowercase()
                    val appears = targetPayload.isNotEmpty() && afterReport.finalNodes.any {
                        it.label.lowercase().contains(targetPayload)
                    }
                    if (!appears) return false
                }
                screenChanged
            }
            else -> {
                // 一般點擊導航：只要點擊目標後畫面結構/頁面已成功跳轉變化，即完成該子目標
                screenChanged
            }
        }
    }

    /**
     * 【網頁與目標介面到達確認閘門 (Target Page Settle & Arrival Verifier)】
     * 在點擊進入網頁或目標子介面後：
     * 1. 確認畫面不在載入中 (`!SmartLoadingDetector.isScreenCurrentlyLoading(afterReport)`) 且具備實質內容節點；
     * 2. 確認畫面結構已實質推進 (`hasScreenChangedMeaningfully(beforeFp, afterFp)`) 且未誤觸頂部搜尋輸入框；
     * 3. 驗證新畫面是否已到達正確目標地方（包含目標關鍵字/實體片段、或已脫離搜尋結果列表進入目標網站內容頁/內頁）；
     * 4. 若確認已到達最終目標 (`lockPermissionOnTerminalSuccess == true`)，立即呼叫 `InAppWorkingMemory.revokeMotorPermissionImmediately` 提前封鎖動手權限！
     */
    fun verifyTargetPageArrivedAndSettled(
        userGoal: String,
        activeSubGoal: CooperativeSubGoal?,
        beforeFp: ScreenFingerprint?,
        afterFp: ScreenFingerprint,
        afterReport: VisionPipelineReport,
        lockPermissionOnTerminalSuccess: Boolean = true
    ): Boolean {
        if (!hasScreenChangedMeaningfully(beforeFp, afterFp)) return false
        if (SmartLoadingDetector.isScreenCurrentlyLoading(afterReport)) return false

        val hasFocusedTopInput = afterReport.finalNodes.any { node ->
            node.shortTypeTag == "INP" &&
                node.gridTapPoint.y <= 250 &&
                node.inlineStatePrefix.contains("焦點")
        }
        if (hasFocusedTopInput) return false

        val contentNodes = afterReport.finalNodes.filter { node ->
            node.shortTypeTag != "INP" &&
                node.shortTypeTag != "SCR" &&
                node.label.trim().isNotEmpty()
        }
        if (contentNodes.isEmpty()) return false

        val (query, _) = InAppOperationPlanner.extractSearchQueryAndFollowUp(userGoal)
        val rawTargetPhrase = activeSubGoal?.targetSemanticPhrase?.trim().orEmpty()
        val normQuery = query.trim().lowercase().replace('臺', '台')
        val normPhrase = rawTargetPhrase.lowercase().replace('臺', '台')
            .replace(Regex("^(?:點擊|點選|點進|進入|打開|開啟)\\s*"), "")
            .trim()

        val queryBigrams = if (normQuery.length >= 4) {
            com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.meaningfulBigrams(normQuery)
        } else emptyList()
        val phraseBigrams = if (normPhrase.length >= 4) {
            com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.meaningfulBigrams(normPhrase)
        } else emptyList()

        // 檢查新頁面是否包含目標實體、搜尋詞片段或網站特徵
        val hasEntityOrKeywordMatch = contentNodes.any { node ->
            val lbl = node.label.lowercase().replace('臺', '台')
            val matchesQuery = normQuery.isNotEmpty() && (
                lbl.contains(normQuery) ||
                    (queryBigrams.isNotEmpty() && queryBigrams.any { bg -> lbl.contains(bg) })
                )
            val matchesPhrase = normPhrase.length >= 2 &&
                normPhrase !in setOf("網站", "網頁", "官網", "第一個", "第一項") &&
                (lbl.contains(normPhrase) ||
                    (phraseBigrams.isNotEmpty() && phraseBigrams.any { bg -> lbl.contains(bg) }))
            matchesQuery || matchesPhrase
        }

        // 若目標為「點進網站 / 網頁 / 官網」，且畫面已改變並載入出 >= 2 個實質內容節點（非純搜尋框）
        val isWebOrSubpageEntered = contentNodes.size >= 2

        val arrived = hasEntityOrKeywordMatch || isWebOrSubpageEntered
        if (arrived && lockPermissionOnTerminalSuccess) {
            InAppWorkingMemory.revokeMotorPermissionImmediately("TARGET_PAGE_CONFIRMED_HARD_LOCK")
        }
        return arrived
    }

    /**
     * 【終極目標達成即停閘門 (Terminal Goal Completion Gate)】
     * 在每一輪動作前與搜尋送出後檢查：若使用者給定的終極目標已經在當前畫面上達成，立即回傳 true 停止迴圈，絕不再多做任何動作！
     */
    fun isTerminalGoalAlreadySatisfied(
        userGoal: String,
        currentPackage: String?,
        report: VisionPipelineReport,
        hasTypedTextInLoop: Boolean,
        activeSubGoalIndex: Int = 0,
        totalSubGoals: Int = 1
    ): Boolean {
        val cleanGoal = userGoal.trim()
        if (cleanGoal.isEmpty()) return false
        if (totalSubGoals > 1 && activeSubGoalIndex < totalSubGoals - 1) {
            return false
        }
        if (cleanGoal.contains("然後") || cleanGoal.contains("接著") || cleanGoal.contains("再來") || cleanGoal.contains("之後") ||
            cleanGoal.contains("并且") || cleanGoal.contains("並且") || cleanGoal.contains("并") || cleanGoal.contains("並") ||
            cleanGoal.contains("直接打開") || cleanGoal.contains("直接打开") || cleanGoal.contains("抓出來") || cleanGoal.contains("抓出来")
        ) {
            return false
        }
        if (HierarchicalCommandChunker.tokenizeChronologicalClauses(cleanGoal).size > 1) {
            return false
        }

        val (query, followUp) = InAppOperationPlanner.extractSearchQueryAndFollowUp(cleanGoal)
        if (query.isBlank()) return false

        if (InAppOperationPlanner.requiresResultSelectionAfterSearch(cleanGoal, currentPackage, followUp)) {
            return false
        }

        val hasSubmittedOrTyped = hasTypedTextInLoop || InAppWorkingMemory.hasSubmittedQuery(query)
        if (!hasSubmittedOrTyped) return false

        return isSearchResultsPageLoadedInReport(report, query)
    }

    /**
     * 根據視覺與無障礙節點報告判斷「搜尋結果頁」是否已經成功載入
     */
    fun isSearchResultsPageLoadedInReport(
        report: VisionPipelineReport,
        query: String
    ): Boolean {
        val nodes = report.finalNodes
        if (nodes.isEmpty()) return false
        val queryLower = query.trim().lowercase()

        val hasSearchResultUrl = nodes.any { node ->
            val full = "${node.inlineStatePrefix}${node.label}".lowercase()
            full.contains("search?q=") || full.contains("google.com/search") || full.contains("bing.com/search")
        }

        val resultPageMarkers = UiKeywordLexicon.SEARCH_RESULTS_PAGE_MARKERS
        val markerHitCount = nodes.count { node ->
            val clean = node.label.trim()
            clean in resultPageMarkers || resultPageMarkers.any { m -> m.length >= 2 && clean.contains(m, ignoreCase = true) }
        }

        val contentNodes = nodes.filter { node ->
            node.shortTypeTag != "INP" &&
                node.shortTypeTag != "SCR" &&
                node.gridTapPoint.y >= 180 &&
                node.label.trim().length >= 2 &&
                !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)
        }
        val queryNorm = queryLower.replace('臺', '台')
        val queryBigrams = if (queryNorm.length >= 4) {
            com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.meaningfulBigrams(queryNorm)
        } else emptyList()
        val matchingContentCount = if (queryNorm.isNotEmpty()) {
            contentNodes.count { node ->
                val normLbl = node.label.lowercase().replace('臺', '台')
                normLbl.contains(queryNorm) || (queryBigrams.isNotEmpty() && queryBigrams.count { bg -> normLbl.contains(bg) } >= 2)
            }
        } else 0
        val hasMatchingContent = matchingContentCount >= 1
        val alreadySubmitted = query.isNotBlank() && InAppWorkingMemory.hasSubmittedEquivalentQuery(query)

        val hasTopEditableSearchInput = nodes.any { node ->
            node.shortTypeTag == "INP" && node.gridTapPoint.y <= 220
        }
        val hasFocusedTopInput = nodes.any { node ->
            node.shortTypeTag == "INP" && node.gridTapPoint.y <= 220 && node.inlineStatePrefix.contains("焦點")
        }
        if (hasTopEditableSearchInput && !hasSearchResultUrl && markerHitCount < 2) {
            if (!(alreadySubmitted && !hasFocusedTopInput && (markerHitCount >= 1 || matchingContentCount >= 2))) {
                return false
            }
        }

        if (hasSearchResultUrl && contentNodes.size >= 2) return true
        if (markerHitCount >= 2 && contentNodes.size >= 3) return true
        if (markerHitCount >= 1 && hasMatchingContent && contentNodes.size >= 2) return true
        if (alreadySubmitted && !hasFocusedTopInput && matchingContentCount >= 2 && contentNodes.size >= 2) return true
        return false
    }

    const val MAX_REPLAN_LIMIT = 3

    private val IRREVERSIBLE_ACTION_KEYWORDS = listOf(
        "送出郵件", "傳送郵件", "寄出信件", "寄出", "傳送訊息", "送出表單", "送出訂單",
        "立即購買", "直接購買", "購買", "立即付款", "確認付款", "付款", "支付",
        "結帳", "去買單", "立即下單", "確認訂單", "提交訂單",
        "永久刪除", "確認刪除", "刪除", "清空垃圾桶", "轉帳", "確認轉帳", "匯款", "發布", "發佈", "送出"
    )

    /**
     * 【優化方向 2：動作可逆性風險判定 (`classifyActionRisk`)】
     * - 滑動、返回、開 App、搜尋輸入、點開信件或商品頁屬於 `REVERSIBLE`（可逆）。
     * - 送出、傳送、購買、付款、結帳、刪除、發布、確認訂單屬於 `IRREVERSIBLE`（不可逆），
     *   強制升級為雙驗收官會審，且執行前須先取得使用者確認。
     */
    fun classifyActionRisk(
        userGoal: String,
        activeSubGoal: CooperativeSubGoal? = null,
        targetNodeLabel: String = "",
        actionType: AiActionType? = null
    ): ActionRisk {
        if (actionType in setOf(
                AiActionType.LAUNCH_APP,
                AiActionType.SCROLL_DOWN,
                AiActionType.SCROLL_UP,
                AiActionType.SWIPE_LEFT,
                AiActionType.SWIPE_RIGHT,
                AiActionType.NAVIGATE_BACK,
                AiActionType.INPUT_TEXT
            )
        ) {
            return ActionRisk.REVERSIBLE
        }
        if (activeSubGoal?.intentCategory in setOf("LAUNCH_APP", "SCROLL", "GESTURE_SWIPE", "SYSTEM_BACK", "INPUT_SEARCH")) {
            return ActionRisk.REVERSIBLE
        }

        // 排除語音同音詞「買茶歌」（意為查歌）
        val sanitizedGoal = userGoal.replace("買茶歌", "查歌")
        val sanitizedSubGoal = activeSubGoal?.targetSemanticPhrase.orEmpty().replace("買茶歌", "查歌")
        val cleanNodeLabel = targetNodeLabel.trim().lowercase()

        // 避免把「搜尋送出」誤判為不可逆交易送出
        val isSearchSubmitLabel = cleanNodeLabel == "搜尋" || cleanNodeLabel == "搜索" || cleanNodeLabel == "search"
        if (isSearchSubmitLabel) return ActionRisk.REVERSIBLE

        if (activeSubGoal?.isHighRiskHint == true) {
            return ActionRisk.IRREVERSIBLE
        }
        if (cleanNodeLabel.isNotEmpty() && IRREVERSIBLE_ACTION_KEYWORDS.any { cleanNodeLabel == it || cleanNodeLabel.contains(it) }) {
            return ActionRisk.IRREVERSIBLE
        }
        val activeTargetText = if (sanitizedSubGoal.isNotBlank()) sanitizedSubGoal else sanitizedGoal
        if (IRREVERSIBLE_ACTION_KEYWORDS.any { activeTargetText.contains(it) }) {
            return ActionRisk.IRREVERSIBLE
        }
        return ActionRisk.REVERSIBLE
    }

    /**
     * 【雙驗收官複雜度分流判定 (`classifyActionComplexity`)】
     * - `BASIC`：基礎點擊、滑動、返回、開 App、切換開關（若為可逆動作，僅由 Android 原生驗收官驗收，0ms 不呼叫 Needle 3）。
     * - `COMPLEX`：關鍵字搜尋與送出、隨機挑選、序數挑選、比較級挑選、多步複合任務、或任何 `IRREVERSIBLE` 不可逆動作
     *   （一律由 Android 原生驗收官 + Needle 3 語義驗收官雙重會審，絕不依賴原生驗收官自身信心分數跳過 Needle 3）。
     */
    fun classifyActionComplexity(
        userGoal: String,
        activeSubGoal: CooperativeSubGoal? = null,
        actionType: AiActionType? = null,
        targetNodeLabel: String = ""
    ): ActionComplexity {
        val risk = classifyActionRisk(userGoal, activeSubGoal, targetNodeLabel, actionType)
        if (risk == ActionRisk.IRREVERSIBLE) {
            return ActionComplexity.COMPLEX
        }

        if (actionType in setOf(
                AiActionType.LAUNCH_APP,
                AiActionType.SCROLL_DOWN,
                AiActionType.SCROLL_UP,
                AiActionType.SWIPE_LEFT,
                AiActionType.SWIPE_RIGHT,
                AiActionType.NAVIGATE_BACK
            )
        ) {
            return ActionComplexity.BASIC
        }

        val cat = activeSubGoal?.intentCategory.orEmpty()
        if (cat in setOf("LAUNCH_APP", "SCROLL", "GESTURE_SWIPE", "SYSTEM_BACK", "TOGGLE_SWITCH")) {
            return ActionComplexity.BASIC
        }
        if (actionType == AiActionType.INPUT_TEXT || cat == "INPUT_SEARCH") {
            return ActionComplexity.COMPLEX
        }

        val combined = "${userGoal.trim()} ${activeSubGoal?.targetSemanticPhrase.orEmpty()}".lowercase()
        if (BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(combined)) {
            return ActionComplexity.COMPLEX
        }
        if (combined.contains("最便宜") || combined.contains("最貴") ||
            combined.contains("最低價") || combined.contains("最高價") ||
            combined.contains("搜尋") || combined.contains("搜索") || combined.contains("查找") ||
            combined.contains("誰是") || combined.contains("什麼是") ||
            combined.contains("然後") || combined.contains("接著") ||
            (activeSubGoal != null && activeSubGoal.totalSteps >= 2 && activeSubGoal.stepIndex >= 2)
        ) {
            return ActionComplexity.COMPLEX
        }

        return ActionComplexity.BASIC
    }

    /**
     * 【優化方向 1：決策層一次輸出整串結構化計畫 (`generatePlanScript`)】
     * 將拆解出的子目標序列封裝為帶 `complexity`、`risk` 與 `expectedOutcome`（驗收條件）的 `PlanScript`。
     */
    fun generatePlanScript(
        rawGoal: String,
        subGoals: List<CooperativeSubGoal>,
        replanCount: Int = 0
    ): PlanScript {
        val steps = subGoals.map { sg ->
            val risk = classifyActionRisk(rawGoal, sg, sg.targetSemanticPhrase)
            val complexity = classifyActionComplexity(rawGoal, sg, null, sg.targetSemanticPhrase)
            val expectedOutcome = when (sg.intentCategory) {
                "LAUNCH_APP" -> "目標 App (${sg.targetAppHint ?: sg.targetSemanticPhrase}) 進入前景且頁面穩定"
                "INPUT_SEARCH" -> "搜尋詞「${sg.inputPayload.orEmpty()}」成功填入並呈現搜尋結果頁"
                "SCROLL", "GESTURE_SWIPE" -> "列表內容發生捲動位移且結構指紋更新"
                "SYSTEM_BACK" -> "成功返回上一層頁面"
                "TOGGLE_SWITCH" -> "目標開關狀態成功切換"
                else -> {
                    if (BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(sg.targetSemanticPhrase)) {
                        "成功從主列表挑中項目並進入內頁（且未誤觸搜尋框）"
                    } else {
                        "點擊「${sg.targetSemanticPhrase}」後頁面成功跳轉至目標畫面"
                    }
                }
            }
            PlanStep(
                directive = ActionDirective(
                    stepIndex = sg.stepIndex,
                    totalSteps = sg.totalSteps,
                    intentCategory = sg.intentCategory,
                    targetAppHint = sg.targetAppHint,
                    targetSemanticPhrase = sg.targetSemanticPhrase,
                    inputPayload = sg.inputPayload
                ),
                complexity = complexity,
                risk = risk,
                expectedOutcome = expectedOutcome
            )
        }
        return PlanScript(
            rawGoal = rawGoal,
            steps = steps,
            replanCount = replanCount.coerceAtLeast(0),
            maxReplans = MAX_REPLAN_LIMIT
        )
    }

    /**
     * 【優化方向 1：重規劃觸發閘門 (`shouldTriggerReplan`)】
     * 行動層照 `PlanScript` 執行期間，僅在以下三種情況之一且未超過上限（3 次）時才呼叫決策層重規劃：
     * 1. 驗收官裁定未達標 (`!inspectorPassed`)
     * 2. 畫面出現非預期阻擋彈窗或外部劫持 (`unexpectedScreenOrPopup`)
     * 3. 連續兩次動作畫面毫無變化 (`consecutiveNoChangeCount >= 2`)
     */
    fun shouldTriggerReplan(
        inspectorPassed: Boolean,
        unexpectedScreenOrPopup: Boolean,
        consecutiveNoChangeCount: Int,
        currentReplanCount: Int
    ): Boolean {
        if (currentReplanCount >= MAX_REPLAN_LIMIT) return false
        return !inspectorPassed || unexpectedScreenOrPopup || consecutiveNoChangeCount >= 2
    }

    /**
     * 【雙驗收官總審核入口 (`evaluateDualInspectorVerification`)】
     * 流程：
     * 1. 驗收官一號（Android 原生驗收官）執行結構指紋、Package 與基礎目標檢查。
     * 2. 若動作為 `BASIC` 且 `REVERSIBLE`（如單純點擊、滑動、返回、開 App）：原生驗收官自己驗收即可，不呼叫 Needle 3。
     * 3. 若動作為 `COMPLEX` 或 `IRREVERSIBLE`（如隨機挑信、搜尋送出、最便宜挑選、不可逆送出/購買/刪除）：
     *    強制由「Android 原生驗收官 + Needle 3 語義驗收官」雙重會審，兩者皆達標才算通過！
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluateDualInspectorVerification(
        userGoal: String,
        activeSubGoal: CooperativeSubGoal?,
        nextSubGoal: CooperativeSubGoal? = null,
        actionType: AiActionType,
        targetNodeLabel: String = "",
        beforeFp: ScreenFingerprint?,
        afterFp: ScreenFingerprint,
        afterReport: VisionPipelineReport,
        hasTypedTextInStep: Boolean,
        hasTypedTextInLoop: Boolean = hasTypedTextInStep,
        isExpandingSearchEntry: Boolean = false,
        currentReplanCount: Int = 0,
        consecutiveNoChangeCount: Int = 0,
        context: Context? = null
    ): DualInspectorAuditResult {
        val risk = classifyActionRisk(userGoal, activeSubGoal, targetNodeLabel, actionType)
        val complexity = classifyActionComplexity(userGoal, activeSubGoal, actionType, targetNodeLabel)

        // 第一關：驗收官一號（Android 原生驗收官）
        val nativeSubGoalPassed = isSubGoalSatisfiedOnNewScreen(
            activeSubGoal = activeSubGoal,
            beforeFp = beforeFp,
            afterFp = afterFp,
            afterReport = afterReport,
            hasTypedTextInStep = hasTypedTextInStep,
            nextSubGoal = nextSubGoal,
            isExpandingSearchEntry = isExpandingSearchEntry
        )
        val nativeTerminalReached = isTerminalGoalAlreadySatisfied(
            userGoal = userGoal,
            currentPackage = afterFp.packageName,
            report = afterReport,
            hasTypedTextInLoop = hasTypedTextInLoop
        )

        val isFinalStep = activeSubGoal == null || activeSubGoal.stepIndex >= activeSubGoal.totalSteps

        // 分流一：基礎可逆動作 (BASIC + REVERSIBLE) -> 原生驗收官獨立完成驗收，免喚醒 Needle 3
        if (complexity == ActionComplexity.BASIC && risk == ActionRisk.REVERSIBLE) {
            val terminalDone = nativeTerminalReached || (nativeSubGoalPassed && isFinalStep && !isExpandingSearchEntry)
            if (terminalDone) {
                InAppWorkingMemory.revokeMotorPermissionImmediately("INSPECTOR_CONFIRMED_TERMINAL_GOAL_LOCK")
            }
            val needsReplan = shouldTriggerReplan(
                inspectorPassed = nativeSubGoalPassed,
                unexpectedScreenOrPopup = false,
                consecutiveNoChangeCount = consecutiveNoChangeCount,
                currentReplanCount = currentReplanCount
            )
            return DualInspectorAuditResult(
                complexity = ActionComplexity.BASIC,
                risk = ActionRisk.REVERSIBLE,
                nativeInspectorPassed = nativeSubGoalPassed,
                needleInspectorInvoked = false,
                needleInspectorPassed = nativeSubGoalPassed,
                subGoalSatisfied = nativeSubGoalPassed,
                terminalGoalReached = terminalDone,
                requiresUserConfirmationBeforeExec = false,
                requiresReplan = needsReplan,
                auditBadge = "[單驗收官:原生]",
                auditDetail = "基礎動作由原生驗收官獨立驗收(通過=$nativeSubGoalPassed, 終極達標=$terminalDone)"
            )
        }

        // 分流二：複雜動作 (COMPLEX) 或 不可逆動作 (IRREVERSIBLE) -> 原生驗收官 + 三合一主腦 PDDL 驗收官聯合會審
        val triVerdict = TriBrainCoordinatorEngine.inspectComplexActionOutcomeWithPddl(
            userGoal = userGoal,
            activeSubGoal = activeSubGoal,
            beforeFp = beforeFp,
            afterFp = afterFp,
            afterReport = afterReport,
            hasTypedTextInStep = hasTypedTextInStep
        )

        val jointSubGoalPassed = nativeSubGoalPassed && triVerdict.subGoalSatisfied && !isExpandingSearchEntry
        val jointTerminalReached = (nativeTerminalReached && triVerdict.subGoalSatisfied) ||
            (jointSubGoalPassed && triVerdict.terminalGoalReached)
        if (jointTerminalReached) {
            InAppWorkingMemory.revokeMotorPermissionImmediately("INSPECTOR_CONFIRMED_TERMINAL_GOAL_LOCK")
        }

        val needsReplan = shouldTriggerReplan(
            inspectorPassed = jointSubGoalPassed,
            unexpectedScreenOrPopup = false,
            consecutiveNoChangeCount = consecutiveNoChangeCount,
            currentReplanCount = currentReplanCount
        )

        val badge = if (risk == ActionRisk.IRREVERSIBLE) {
            "[雙驗收官(不可逆強制):原生+Needle3]"
        } else {
            "[雙驗收官:原生+Needle3]"
        }

        return DualInspectorAuditResult(
            complexity = complexity,
            risk = risk,
            nativeInspectorPassed = nativeSubGoalPassed,
            needleInspectorInvoked = true,
            needleInspectorPassed = triVerdict.subGoalSatisfied,
            subGoalSatisfied = jointSubGoalPassed,
            terminalGoalReached = jointTerminalReached,
            requiresUserConfirmationBeforeExec = (risk == ActionRisk.IRREVERSIBLE),
            requiresReplan = needsReplan,
            auditBadge = badge,
            auditDetail = "$badge 原生=$nativeSubGoalPassed, 三腦PDDL=${triVerdict.subGoalSatisfied} (${triVerdict.inspectorNote})"
        )
    }
}
