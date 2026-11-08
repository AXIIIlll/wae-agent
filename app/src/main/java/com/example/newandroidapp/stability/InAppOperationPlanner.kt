package com.example.newandroidapp.stability

import android.content.Context
import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.ai.CognitiveReasoningEngine
import com.example.newandroidapp.ai.CooperativeSubGoal
import com.example.newandroidapp.ai.DeterminismHardeningEngine
import com.example.newandroidapp.ai.LookaheadPlannerEngine
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import java.util.concurrent.ConcurrentHashMap

typealias ActionCandidate = Stage2ConvergedNode

/**
 * 導航麵包屑節點：記錄從哪個畫面指紋、點擊了哪個元件進入下層頁面，供走錯路自動返回 (Auto-Backtrack) 與死胡同標記使用
 */
data class NavBreadcrumb(
    val fromScreenFp: String,
    val clickedNode: Stage2ConvergedNode,
    val subGoalIndex: Int,
    val stage: InAppOperationStage = InAppOperationStage.IN_APP_NAVIGATION
)

/**
 * App 內自主操作階段枚舉
 */
enum class InAppOperationStage(val displayLabel: String) {
    LAUNCH_OR_INIT("啟動與初始化"),
    DISMISS_BLOCKER("排除頂層阻擋彈窗"),
    STAGE_A_EXPAND_SEARCH("階段A: 展開搜尋入口"),
    STAGE_B_INPUT_AND_SUBMIT("階段B: 填入關鍵字並送出"),
    STAGE_C_SELECT_RESULT("階段C: 鎖定結果列表操作"),
    IN_APP_NAVIGATION("App內一般導航操作")
}

/**
 * 執行緒安全之 App 內工作記憶 (InAppWorkingMemory)
 *
 * 負責追蹤單次任務生命週期內的：
 * 1. 已啟動與預期鎖定之目標套件 (`expectedTargetPackage`)，防止外部廣告或惡意連結劫持跳出 App
 * 2. 導航麵包屑堆疊 (`navigationStack`) 與走錯路自動返回計數 (`autoBacktrackCount`，上限 3 次)
 * 3. 已排除之頂層彈窗/廣告簽章與次數（防止無限循環誤點）
 * 4. 已點擊之搜尋入口候選 (`lastTappedSearchEntryCandidate`) 與已輸入/已送出之關鍵字（防止重複無限打字）
 * 5. 每個畫面指紋 (screenHash) 下已點擊過之元件簽章（防止在靜態按鈕或分頁上死循環點擊）
 */
object InAppWorkingMemory {

    private const val MAX_AUTO_BACKTRACKS_PER_TASK = 3

    @Volatile
    private var activeGoal: String = ""

    @Volatile
    private var currentStage: InAppOperationStage = InAppOperationStage.LAUNCH_OR_INIT

    @Volatile
    var expectedTargetPackage: String = ""
        private set

    @Volatile
    private var launchedPackage: String? = null

    @Volatile
    private var searchEntryClickedCount: Int = 0

    @Volatile
    private var scrollCount: Int = 0

    @Volatile
    var currentSubPageScrollCount: Int = 0
        private set

    @Volatile
    var hasAttemptedReverseScrollUpOnSubPage: Boolean = false
        private set

    @Volatile
    var hasAttemptedHorizontalSwipeOnSubPage: Boolean = false
        private set

    @Volatile
    var autoBacktrackCount: Int = 0
        private set

    @Volatile
    var lastTappedSearchEntryCandidate: Stage2ConvergedNode? = null

    val navigationStack: ArrayDeque<NavBreadcrumb> = ArrayDeque()

    private val typedQueries = ConcurrentHashMap.newKeySet<String>()
    private val submittedQueries = ConcurrentHashMap.newKeySet<String>()
    private val dismissedBlockerCounts = ConcurrentHashMap<String, Int>()
    private val clickedElementsByScreen = ConcurrentHashMap<String, MutableSet<String>>()
    private val globalClickedSignatures = ConcurrentHashMap<String, Int>()

    // =========================================================================
    // 【硬體級動手權限閘門 (MotorPermissionGate)】
    // 一任務一授權，當任務最後一個子目標完成或目標已達成時，毫秒級瞬間撤銷動手權限並作廢 Session Token
    // =========================================================================
    private val motorPermissionGranted = java.util.concurrent.atomic.AtomicBoolean(true)
    private val activeMotorSessionToken = java.util.concurrent.atomic.AtomicLong(1L)

    @Volatile
    var lastMotorRevokeReason: String = ""
        private set

    @Synchronized
    fun grantMotorPermission(goal: String = ""): Long {
        if (goal.isNotBlank()) {
            activeGoal = goal.trim()
        }
        lastMotorRevokeReason = ""
        motorPermissionGranted.set(true)
        return activeMotorSessionToken.incrementAndGet()
    }

    @Synchronized
    fun revokeMotorPermissionImmediately(reason: String = "TASK_COMPLETED") {
        lastMotorRevokeReason = reason.trim().ifEmpty { "TASK_COMPLETED" }
        motorPermissionGranted.set(false)
        activeMotorSessionToken.incrementAndGet()
    }

    fun hasMotorPermission(expectedToken: Long? = null): Boolean {
        if (!motorPermissionGranted.get()) return false
        if (expectedToken != null && expectedToken != activeMotorSessionToken.get()) return false
        return true
    }

    fun currentMotorSessionToken(): Long = activeMotorSessionToken.get()

    fun currentGoal(): String = activeGoal

    @Synchronized
    fun resetForNewGoal(goal: String = "") {
        activeGoal = goal.trim()
        currentStage = InAppOperationStage.LAUNCH_OR_INIT
        expectedTargetPackage = ""
        launchedPackage = null
        searchEntryClickedCount = 0
        scrollCount = 0
        currentSubPageScrollCount = 0
        hasAttemptedReverseScrollUpOnSubPage = false
        hasAttemptedHorizontalSwipeOnSubPage = false
        autoBacktrackCount = 0
        lastTappedSearchEntryCandidate = null
        navigationStack.clear()
        typedQueries.clear()
        submittedQueries.clear()
        dismissedBlockerCounts.clear()
        clickedElementsByScreen.clear()
        globalClickedSignatures.clear()
        lastMotorRevokeReason = ""
        motorPermissionGranted.set(true)
        activeMotorSessionToken.incrementAndGet()
        com.example.newandroidapp.ai.AdaptiveComputeGate.reset()
        com.example.newandroidapp.ai.TriBrainCoordinatorEngine.resetTelemetryCounters()
    }

    @Synchronized
    fun ensureGoalContext(goal: String) {
        val clean = goal.trim()
        if (clean.isNotEmpty() && clean != activeGoal) {
            resetForNewGoal(clean)
        }
    }

    @Synchronized
    fun setExpectedTargetPackage(pkg: String) {
        val clean = pkg.trim()
        if (clean.isNotEmpty()) {
            expectedTargetPackage = clean
        }
    }

    @Synchronized
    fun updateStage(stage: InAppOperationStage) {
        currentStage = stage
    }

    fun hasAnySubmittedQuery(): Boolean {
        return submittedQueries.isNotEmpty() || typedQueries.isNotEmpty()
    }

    @Synchronized
    fun recordAppLaunched(pkg: String) {
        val clean = pkg.trim()
        val previousPkg = expectedTargetPackage
        launchedPackage = clean
        if (clean.isNotEmpty()) {
            expectedTargetPackage = clean
        }
        if (previousPkg.isNotEmpty() && clean.isNotEmpty() && previousPkg != clean) {
            // 跨 App 切換至下一個目標 App 時，自動清理上一個 App 的內部導航麵包屑與搜尋入口點擊次數
            searchEntryClickedCount = 0
            currentSubPageScrollCount = 0
            hasAttemptedReverseScrollUpOnSubPage = false
            hasAttemptedHorizontalSwipeOnSubPage = false
            lastTappedSearchEntryCandidate = null
            navigationStack.clear()
        }
        currentStage = InAppOperationStage.IN_APP_NAVIGATION
    }

    /**
     * 【跨 App 流程階段切換】當任務由 App A 進入 App B 階段時，重置單一 App 內的搜尋階段與導航堆疊，並更新預期鎖定 Package
     */
    @Synchronized
    fun transitionToNextAppPhase(newExpectedPackage: String) {
        val clean = newExpectedPackage.trim()
        if (clean.isNotEmpty()) {
            expectedTargetPackage = clean
            launchedPackage = clean
        }
        currentStage = InAppOperationStage.LAUNCH_OR_INIT
        searchEntryClickedCount = 0
        currentSubPageScrollCount = 0
        hasAttemptedReverseScrollUpOnSubPage = false
        hasAttemptedHorizontalSwipeOnSubPage = false
        autoBacktrackCount = 0
        lastTappedSearchEntryCandidate = null
        navigationStack.clear()
    }

    @Synchronized
    fun pushBreadcrumb(
        fromScreenFp: String,
        clickedNode: Stage2ConvergedNode,
        subGoalIndex: Int = 0,
        stage: InAppOperationStage = currentStage
    ) {
        // 避免同一個畫面與同一個元件重複推入頂部
        val top = navigationStack.lastOrNull()
        if (top != null && top.fromScreenFp == fromScreenFp && top.clickedNode.nodeId == clickedNode.nodeId) {
            return
        }
        if (navigationStack.size >= 12) {
            navigationStack.removeFirst()
        }
        currentSubPageScrollCount = 0
        hasAttemptedReverseScrollUpOnSubPage = false
        hasAttemptedHorizontalSwipeOnSubPage = false
        navigationStack.addLast(
            NavBreadcrumb(
                fromScreenFp = fromScreenFp,
                clickedNode = clickedNode,
                subGoalIndex = subGoalIndex,
                stage = stage
            )
        )
    }

    @Synchronized
    fun canAutoBacktrack(): Boolean {
        return navigationStack.isNotEmpty() && autoBacktrackCount < MAX_AUTO_BACKTRACKS_PER_TASK
    }

    @Synchronized
    fun peekBreadcrumb(): NavBreadcrumb? {
        return navigationStack.lastOrNull()
    }

    @Synchronized
    fun popBreadcrumbForBacktrack(): NavBreadcrumb? {
        if (navigationStack.isEmpty() || autoBacktrackCount >= MAX_AUTO_BACKTRACKS_PER_TASK) {
            return null
        }
        autoBacktrackCount++
        currentSubPageScrollCount = 0
        hasAttemptedReverseScrollUpOnSubPage = false
        hasAttemptedHorizontalSwipeOnSubPage = false
        val popped = navigationStack.removeLast()
        // 將導致走錯路的元件於來源畫面標記為已點擊（重罰 -100f），退回上一頁後絕不再點它，直接改試期望值第 2 名的候選！
        recordElementClicked(popped.clickedNode, popped.fromScreenFp)
        return popped
    }

    @Synchronized
    fun recordBlockerDismissed(sig: String) {
        val cleanSig = sig.trim().ifEmpty { "blocker" }
        dismissedBlockerCounts[cleanSig] = (dismissedBlockerCounts[cleanSig] ?: 0) + 1
        currentStage = InAppOperationStage.DISMISS_BLOCKER
    }

    fun getBlockerDismissCount(sig: String): Int {
        val cleanSig = sig.trim().ifEmpty { "blocker" }
        return dismissedBlockerCounts[cleanSig] ?: 0
    }

    @Synchronized
    fun recordSearchEntryClicked(sig: String = "", candidate: Stage2ConvergedNode? = null) {
        searchEntryClickedCount++
        if (candidate != null) {
            lastTappedSearchEntryCandidate = candidate
        }
        if (sig.isNotBlank()) {
            globalClickedSignatures[sig] = (globalClickedSignatures[sig] ?: 0) + 1
        }
        currentStage = InAppOperationStage.STAGE_A_EXPAND_SEARCH
    }

    @Synchronized
    fun recordTextTypedOnly(query: String, sig: String = "") {
        val norm = query.trim().lowercase()
        if (norm.isNotEmpty()) {
            typedQueries.add(norm)
        }
        if (sig.isNotBlank()) {
            globalClickedSignatures[sig] = (globalClickedSignatures[sig] ?: 0) + 1
        }
        currentStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT
    }

    @Synchronized
    fun recordTextTypedAndSubmitted(query: String, sig: String = "") {
        val norm = query.trim().lowercase()
        if (norm.isNotEmpty()) {
            typedQueries.add(norm)
            submittedQueries.add(norm)
        }
        if (sig.isNotBlank()) {
            globalClickedSignatures[sig] = (globalClickedSignatures[sig] ?: 0) + 1
        }
        currentStage = InAppOperationStage.STAGE_C_SELECT_RESULT
    }

    @Synchronized
    fun downgradeSubmittedToTypedOnly(query: String) {
        val targetDef = normalizeQueryDefinition(query)
        val norm = query.trim().lowercase()
        if (norm.isNotEmpty()) {
            typedQueries.add(norm)
        }
        if (targetDef.isNotEmpty()) {
            submittedQueries.removeIf { normalizeQueryDefinition(it) == targetDef }
        }
        currentStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT
    }

    /**
     * 將查詢字詞正規化為純粹的「查詢標的語意定義鍵 (Canonical Query Definition)」，
     * 剝離「搜尋/查詢/查一下/找」等動作前綴與空白標點，
     * 使「周杰倫」、「搜尋 周杰倫」、「查一下周杰倫」指向同一個定義（只准查一次），
     * 而「周杰倫」與「林俊傑」（或「台積電」與「聯發科」）則維持不同定義（不計入封鎖，允許正常先查 A 再查 B）。
     */
    fun normalizeQueryDefinition(rawQuery: String): String {
        var cleaned = rawQuery.trim().lowercase()
        if (cleaned.isEmpty()) return ""
        val leadingPrefixes = listOf(
            "搜尋一下", "搜索一下", "查詢一下", "查找一下", "查一下", "找一下", "搜一下",
            "請幫我查", "幫我搜尋", "幫我查詢", "幫我查", "幫我找", "幫我搜",
            "搜尋", "搜索", "查詢", "查找", "尋找", "查", "找", "搜",
            "search for", "search"
        )
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in leadingPrefixes) {
                if (cleaned.startsWith(prefix) && cleaned.length > prefix.length) {
                    cleaned = cleaned.removePrefix(prefix).trim()
                    stripped = true
                    break
                }
            }
        }
        return cleaned
            .replace('臺', '台')
            .replace(Regex("[\\s\\p{Punct}，。！？、：「」『』（）()《》【】]+"), "")
    }

    /**
     * 判斷「相同語意定義」的目標是否已經送出查詢過一次：
     * - 同定義目標（如已查過「周杰倫」，現在又問「搜尋 周杰倫」）-> 回傳 true（禁止再查第二次）
     * - 不同定義目標（如已查過 A「台積電」，現在子目標要查 B「聯發科」）-> 回傳 false（不計入，開放搜尋框查 B）
     */
    fun hasSubmittedEquivalentQuery(targetQuery: String): Boolean {
        val targetDef = normalizeQueryDefinition(targetQuery)
        if (targetDef.isEmpty()) return false
        return submittedQueries.any { submitted ->
            val subDef = normalizeQueryDefinition(submitted)
            subDef.isNotEmpty() && subDef == targetDef
        }
    }

    fun hasTypedButNotSubmitted(query: String): Boolean {
        val targetDef = normalizeQueryDefinition(query)
        if (targetDef.isEmpty()) return false
        val wasTyped = typedQueries.any { normalizeQueryDefinition(it) == targetDef }
        return wasTyped && !hasSubmittedEquivalentQuery(query)
    }

    fun hasSubmittedQuery(query: String): Boolean {
        return hasSubmittedEquivalentQuery(query)
    }

    fun hasExactSubmittedQuery(query: String): Boolean {
        return hasSubmittedEquivalentQuery(query)
    }

    /**
     * 取得目前工作記憶中最新已送出或已輸入的目標搜尋詞（供後續跨步動作如「點選路線」關聯所屬實體卡片使用）
     */
    fun getActiveSearchQuery(): String {
        return submittedQueries.lastOrNull()?.trim().orEmpty()
            .ifEmpty { typedQueries.lastOrNull()?.trim().orEmpty() }
    }

    @Synchronized
    fun recordElementClicked(candidate: Stage2ConvergedNode, screenHash: String = "default") {
        val sig = buildCandidateSignature(candidate)
        recordElementClicked(sig, screenHash)
    }

    @Synchronized
    fun recordElementClicked(sig: String, screenHash: String = "default") {
        val key = screenHash.ifBlank { "default" }
        val set = clickedElementsByScreen.getOrPut(key) { ConcurrentHashMap.newKeySet() }
        set.add(sig)
        globalClickedSignatures[sig] = (globalClickedSignatures[sig] ?: 0) + 1
    }

    /**
     * 當單次點擊因畫面剛載入或焦點轉場而尚未生效（!changed）且屬於搜尋入口按鈕時，
     * 撤銷本次預先記入的點擊標記（最多容許重試 2 次），避免搜尋按鈕被永久封鎖而點不到。
     */
    @Synchronized
    fun rollbackUnverifiedSearchEntryClick(candidate: Stage2ConvergedNode, screenHash: String = "default") {
        val key = screenHash.ifBlank { "default" }
        val sig = buildCandidateSignature(candidate)
        clickedElementsByScreen[key]?.remove(sig)
        val cur = globalClickedSignatures[sig] ?: 0
        if (cur > 0) {
            globalClickedSignatures[sig] = cur - 1
        }
    }

    fun wasCandidateClickedOnScreen(candidate: Stage2ConvergedNode, screenHash: String): Boolean {
        val key = screenHash.ifBlank { "default" }
        val sig = buildCandidateSignature(candidate)
        val onScreen = clickedElementsByScreen[key]?.contains(sig) == true
        val globalCount = globalClickedSignatures[sig] ?: 0
        return onScreen || globalCount >= 2
    }

    fun getCandidateClickCount(candidate: Stage2ConvergedNode): Int {
        val sig = buildCandidateSignature(candidate)
        return globalClickedSignatures[sig] ?: 0
    }

    @Synchronized
    fun recordScroll() {
        scrollCount++
        currentSubPageScrollCount++
    }

    @Synchronized
    fun rollbackScroll() {
        if (scrollCount > 0) scrollCount--
        if (currentSubPageScrollCount > 0) currentSubPageScrollCount--
    }

    @Synchronized
    fun markReverseScrollUpAttempted() {
        hasAttemptedReverseScrollUpOnSubPage = true
    }

    @Synchronized
    fun markHorizontalSwipeAttempted() {
        hasAttemptedHorizontalSwipeOnSubPage = true
    }

    fun buildCandidateSignature(candidate: Stage2ConvergedNode): String {
        val cleanLabel = candidate.label.trim().take(24)
        val gx = candidate.gridTapPoint.x / 35
        val gy = candidate.gridTapPoint.y / 35
        return "${candidate.shortTypeTag}|$cleanLabel@$gx,$gy"
    }

    /**
     * 產生人類可讀的工作記憶狀態徽章，附加於 AI 決策推論軌跡中
     */
    @Synchronized
    fun summaryBadge(): String {
        val parts = ArrayList<String>(5)
        parts.add(currentStage.displayLabel)
        if (searchEntryClickedCount > 0) {
            parts.add("已展開搜尋x$searchEntryClickedCount")
        }
        if (submittedQueries.isNotEmpty()) {
            parts.add("已送出:${submittedQueries.joinToString("/")}")
        } else if (typedQueries.isNotEmpty()) {
            parts.add("已輸入:${typedQueries.joinToString("/")}")
        }
        val totalDismissed = dismissedBlockerCounts.values.sum()
        if (totalDismissed > 0) {
            parts.add("已清彈窗x$totalDismissed")
        }
        if (autoBacktrackCount > 0) {
            parts.add("已自動返回x$autoBacktrackCount")
        }
        val totalClicked = globalClickedSignatures.values.sum()
        if (totalClicked > 0) {
            parts.add("記憶點擊x$totalClicked")
        }
        return "[記憶:${parts.joinToString(" | ")}]"
    }
}

/**
 * App 內自主操作引擎規劃器 (InAppOperationPlanner)
 *
 * 專責解決「進入 App 後如何像真人一樣自主完成多步任務」：
 * 1. 頂層阻擋彈窗/廣告/權限對話框自動偵測與排除 (`detectTopLayerBlocker`)
 * 2. 自然語言搜尋詞與後續動作意圖精準分離 (`extractSearchQueryAndFollowUp`)
 * 3. App 內三階段搜尋與操作鏈 (`Stage A 展開搜尋入口` -> `Stage B 輸入文字並送出` -> `Stage C 結果列表定位點擊 + 搜尋框 -60f 懲罰防重打字`)
 * 4. 工作記憶防重複點擊濾波器 (`-45f` 懲罰同畫面已點過之靜態元件)
 * 5. 【升級 #1】走錯路自動返回上一頁 (`shouldTriggerAutoBacktrack`) 與跨 App 廣告誤跳攔截 (`isUnexpectedExternalAppHijack`)
 * 6. 【升級 #2】跨任務持久化 App 經驗記憶庫整合 (`EpisodicAppMemory` 搜尋入口 `+24f`、成功目標 `+20f`、死胡同陷阱 `-55f`)
 */
object InAppOperationPlanner {

    val workingMemory: InAppWorkingMemory = InAppWorkingMemory

    private val SYSTEM_ALLOWED_OVERLAY_PACKAGES = setOf(
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.inputmethod.latin",
        "com.google.android.inputmethod.pinyin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "android",
        "com.example.newandroidapp",
        "com.localai.visionassistant"
    )

    // 【優化 #1：統一委派至全域單一詞庫 UiKeywordLexicon】
    val SEARCH_ENTRY_KEYWORDS: List<String>
        get() = UiKeywordLexicon.TOP_SEARCH_ENTRY_KEYWORDS

    fun isForbiddenBrowserToolbarNode(node: Stage2ConvergedNode, userGoal: String = ""): Boolean {
        val goalLower = userGoal.lowercase()
        if (goalLower.contains("無痕") || goalLower.contains("分頁") || goalLower.contains("選單") || goalLower.contains("incognito")) {
            return false
        }
        val fullText = "${node.inlineStatePrefix} ${node.label}".lowercase()
        if (UiKeywordLexicon.FORBIDDEN_BROWSER_TOOLBAR_KEYWORDS.any { fullText.contains(it) }) {
            return true
        }
        if (Regex("查看\\s*\\d+\\s*個分頁").containsMatchIn(fullText)) {
            return true
        }
        val idLower = node.nodeId.lowercase()
        if (UiKeywordLexicon.FORBIDDEN_BROWSER_TOOLBAR_ID_TOKENS.any { idLower.contains(it) }) {
            return true
        }
        return false
    }

    /**
     * 【最高優先級鐵律禁令：嚴禁 AI 點擊任何「語音輸入／語音搜尋／麥克風／相機鏡頭搜尋」或「↖ 調整搜尋字詞」按鈕】
     * 所有搜尋與輸入任務一律強制使用純文字輸入（Text Input）並直接送出。
     */
    fun isForbiddenVoiceOrCameraNode(node: Stage2ConvergedNode): Boolean {
        return UiKeywordLexicon.isForbiddenVoiceCameraOrRefine(
            cleanLabel = node.label,
            fullTextWithPrefix = "${node.inlineStatePrefix} ${node.label}",
            resourceId = node.nodeId,
            maxLabelLengthForVoiceBlock = 28
        )
    }

    /**
     * 檢查是否為真正的「送出搜尋」按鈕（必須位於頂部搜尋列同高度，且標籤精確為搜尋/前往/送出，絕非「調整搜尋字詞」等建議列按鈕）
     */
    fun isTrueSearchSubmitButton(node: Stage2ConvergedNode, primaryInp: Stage2ConvergedNode?): Boolean {
        if (node.shortTypeTag == "INP" || isForbiddenVoiceOrCameraNode(node)) return false
        if (primaryInp != null && kotlin.math.abs(node.gridTapPoint.y - primaryInp.gridTapPoint.y) > 65) {
            return false
        }
        return UiKeywordLexicon.isSearchSubmitText(node.label)
    }

    /**
     * 計算單一動作按鈕（如「行車路線」、「分享」、「購買」）與其所屬父卡片或鄰近標題節點之「實體語境匹配加分 (Card-Entity Context Bonus)」。
     *
     * 當搜尋結果同時呈現多張卡片（例如第一張「台北101購物中心」附帶一顆 `#15「行車路線」@200,1400`，
     * 第二張「台北101」附帶一顆 `#21「行車路線」@200,1900`），且兩顆按鈕的文字皆為純粹的「行車路線」時：
     * - 檢查每顆按鈕空間上隸屬哪一張卡片（其座標落在哪張卡片包圍盒內，或緊鄰哪張卡片標題）。
     * - 將該卡片標題與使用者剛才搜尋的目標實體 `activeEntityQuery`（如 `"台北 101"`）進行精確度比對：
     *   - 所屬卡片與目標實體 100% 精確匹配（如 `"台北101"` == `"台北101"`，`matchScore >= 95f`）給予 `+38f` 強效加分；
     *   - 所屬卡片僅為衍生詞（如 `"台北101購物中心"`，`matchScore` 較低）僅給予較小加分（`+8f`），
     *   確保 AI 在多張卡片並存時，100% 精準點選目標實體卡片底下的那顆動作按鈕！
     */
    fun computeParentCardEntityContextBonus(
        actionButton: Stage2ConvergedNode,
        allNodes: List<Stage2ConvergedNode>,
        activeEntityQuery: String
    ): Float {
        val cleanQuery = activeEntityQuery.trim().lowercase().replace('臺', '台')
        if (cleanQuery.isEmpty()) return 0f

        val btnTap = actionButton.finalTapPx
        val btnBounds = actionButton.convergedBounds
        var bestCardMatchScore = 0f

        for (other in allNodes) {
            if (other.nodeId == actionButton.nodeId) continue
            if (other.shortTypeTag == "INP" || other.shortTypeTag == "SCR") continue
            val otherLabel = other.label.trim()
            if (otherLabel.length < 2) continue
            // 排除與當前動作按鈕同名的重複按鈕本身
            if (otherLabel.equals(actionButton.label.trim(), ignoreCase = true)) continue

            val ob = other.rawOriginalBounds
            val cb = other.convergedBounds
            val enclosesButton = (btnTap.x in (ob.left - 24)..(ob.right + 24) && btnTap.y in (ob.top - 24)..(ob.bottom + 24)) ||
                (btnTap.x in (cb.left - 24)..(cb.right + 24) && btnTap.y in (cb.top - 24)..(cb.bottom + 24))
            val isSameCardBand = kotlin.math.abs(other.finalTapPx.y - btnTap.y) <= 260 &&
                (other.finalTapPx.y <= btnBounds.bottom + 90)

            if (!enclosesButton && !isSameCardBand) continue

            // 若卡片標題包含換行或多段空白，拆出各片段取最佳實體匹配分數（例如「臺北市信義區信義路五段7號 台北101」拆出「台北101」-> 120f）
            var candidateBest = scoreQueryMatchQuality(otherLabel, cleanQuery)
            val tokens = otherLabel.split(Regex("[\\s\\n，,、|｜]+")).filter { it.length >= 2 }
            for (tok in tokens) {
                val tokScore = scoreQueryMatchQuality(tok, cleanQuery)
                if (tokScore > candidateBest) {
                    candidateBest = tokScore
                }
            }
            if (enclosesButton && candidateBest > 0f) {
                candidateBest += 10f
            }
            if (candidateBest > bestCardMatchScore) {
                bestCardMatchScore = candidateBest
            }
        }

        return when {
            bestCardMatchScore >= 115f -> 42f
            bestCardMatchScore >= 90f -> 28f
            bestCardMatchScore >= 60f -> 10f
            bestCardMatchScore >= 35f -> 4f
            else -> 0f
        }
    }

    /**
     * 檢測候選節點的標籤是否仍混雜 >= 2 個彼此獨立的 UI 動作角色（例如殘留「儲存 分享 購票 行車路線」之複合容器），
     * 當目標為點擊單一明確動作按鈕時，對此類複合容器扣分，強制優先選取純原子按鈕節點。
     */
    fun isCompositeMultiActionLabel(label: String, context: Context? = null): Boolean {
        val clean = label.trim()
        if (clean.length < 4) return false
        val tokens = clean.split(Regex("[\\s,，、/｜|]+")).filter { it.length in 2..8 }
        if (tokens.size < 2) return false
        val distinctRoles = HashSet<String>()
        for (tok in tokens) {
            val role = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(tok, context)
            if (role != null) {
                distinctRoles.add(role)
                if (distinctRoles.size >= 2) return true
            }
        }
        return false
    }

    /**
     * 計算候選節點標籤與目標搜尋詞 `query` 的精確匹配品質分數：
     * 1. 完全相等（如「蔣介石」==「蔣介石」或「蔣介石 - Google 搜尋」）：最高分 (+120f)
     * 2. 前綴匹配但多出額外字詞（如「蔣介石的老婆」）：依多出的字數扣減，確保精確詞永遠排第一！
     */
    fun scoreQueryMatchQuality(label: String, query: String): Float {
        val cleanLabel = label.trim().lowercase().replace('臺', '台')
        val cleanQuery = query.trim().lowercase().replace('臺', '台')
        if (cleanLabel.isEmpty() || cleanQuery.isEmpty()) return 0f
        val strippedGoogleSuffix = cleanLabel
            .removeSuffix("- google 搜尋")
            .removeSuffix("- google 搜索")
            .removeSuffix("- google search")
            .removePrefix("搜尋「")
            .removeSuffix("」")
            .trim()
        if (cleanLabel == cleanQuery || strippedGoogleSuffix == cleanQuery) {
            return 120f
        }
        val firstToken = cleanLabel.split(Regex("[\\s,，、·・(（-]+")).firstOrNull()?.trim().orEmpty()
        if (firstToken == cleanQuery) {
            val extraChars = (cleanLabel.length - cleanQuery.length).coerceAtLeast(1)
            return (95f - extraChars * 1.2f).coerceAtLeast(62f)
        }
        if (cleanLabel.startsWith(cleanQuery)) {
            val extraChars = (cleanLabel.length - cleanQuery.length).coerceAtLeast(1)
            return (78f - extraChars * 3.5f).coerceAtLeast(48f)
        }
        if (cleanLabel.contains(cleanQuery)) {
            val extraChars = (cleanLabel.length - cleanQuery.length).coerceAtLeast(1)
            return (58f - extraChars * 2.5f).coerceAtLeast(35f)
        }
        return 0f
    }

    data class InAppPlanEvaluation(
        val stage: InAppOperationStage,
        val screenHash: String,
        val extractedQuery: String,
        val extractedFollowUp: String,
        val blockerCandidate: Stage2ConvergedNode? = null,
        val overrideActionType: AiActionType? = null,
        val overrideTargetNode: Stage2ConvergedNode? = null,
        val overrideInputText: String? = null,
        val overrideConfidence: Float = 0.95f,
        val bestRawScore: Float = 0f,
        val stageReasoningNote: String = "",
        val episodicMemoryNote: String? = null,
        val adjustedCandidates: List<Pair<Stage2ConvergedNode, Float>> = emptyList()
    )

    fun resetForNewGoal(goal: String = "") = InAppWorkingMemory.resetForNewGoal(goal)
    fun summaryBadge(): String = InAppWorkingMemory.summaryBadge()

    val BROWSER_AND_SEARCH_EQUIVALENT_PACKAGES = setOf(
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.brave.browser",
        "com.google.android.webview",
        "com.android.browser"
    )

    fun isBrowserOrSearchEquivalentTransition(pkgA: String, pkgB: String): Boolean {
        val a = pkgA.trim()
        val b = pkgB.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a in BROWSER_AND_SEARCH_EQUIVALENT_PACKAGES && b in BROWSER_AND_SEARCH_EQUIVALENT_PACKAGES) {
            return true
        }
        val goal = InAppWorkingMemory.currentGoal().lowercase()
        val goalWantsWebpage = goal.contains("網站") || goal.contains("網頁") || goal.contains("官網") ||
            goal.contains("瀏覽器") || goal.contains("連結") || goal.contains("網址")
        return goalWantsWebpage && (a in BROWSER_AND_SEARCH_EQUIVALENT_PACKAGES || b in BROWSER_AND_SEARCH_EQUIVALENT_PACKAGES)
    }

    /**
     * 【升級 #1：跨 App 廣告誤跳與外部劫持防護】
     * 檢查當前前景 Package 是否意外脫離了預期鎖定的目標 App（排除系統權限框、輸入法、本機控制器與 Google 搜尋跳轉瀏覽器網頁）
     */
    fun isUnexpectedExternalAppHijack(
        currentPackage: String,
        expectedPackage: String = InAppWorkingMemory.expectedTargetPackage
    ): Boolean {
        val cur = currentPackage.trim()
        val exp = expectedPackage.trim()
        if (cur.isEmpty() || exp.isEmpty()) return false
        if (cur == exp) return false
        if (cur in SYSTEM_ALLOWED_OVERLAY_PACKAGES) return false
        if (isBrowserOrSearchEquivalentTransition(cur, exp)) return false
        if (cur.contains("inputmethod") || cur.contains("keyboard") || cur.contains("permission")) return false
        if (isCompanionPackageOfSameApp(cur, exp)) return false
        return true
    }

    /**
     * 同一個 App 的「姊妹行程」不算外部劫持。
     * 例如「設定」的搜尋頁實際由 `com.android.settings.intelligence` 承載，
     * 點設定首頁的搜尋框後前景套件會變成它，若誤判為劫持並按返回，搜尋頁會被關掉。
     */
    fun isCompanionPackageOfSameApp(pkgA: String, pkgB: String): Boolean {
        val a = pkgA.trim()
        val b = pkgB.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val settingsFamily = setOf(
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.samsung.android.settings",
            "com.samsung.android.settings.intelligence",
            "com.google.android.settings.intelligence"
        )
        if (a in settingsFamily && b in settingsFamily) return true
        // 通用規則：其中一方的套件名稱是另一方加上子層後綴（如 x.y 與 x.y.intelligence）
        return a.startsWith("$b.") || b.startsWith("$a.")
    }

    /**
     * 偵測畫面上是否存在「橫向可滑動容器 / 頂部膠囊篩選列 / 輪播列」：
     * 當同一個水平帶 (`abs(y1 - y2) <= 32`) 存在 `>= 3` 個非底部導覽列 (`gridTapPoint.y < 840`) 之可點擊按鈕或膠囊，
     * 且其橫向跨度 `>= 420` (0..999 座標系)，回傳該橫向列中間的錨點節點，供 `SWIPE_LEFT` 橫向探索使用。
     */
    fun findHorizontalScrollAnchorNode(report: VisionPipelineReport): Stage2ConvergedNode? {
        val candidates = report.finalNodes.filter { node ->
            node.shortTypeTag != "INP" &&
                node.shortTypeTag != "TXT" &&
                node.gridTapPoint.y in 60..820 &&
                !isForbiddenVoiceOrCameraNode(node)
        }
        if (candidates.size < 3) return null

        for (anchor in candidates) {
            val rowPeers = candidates.filter { other ->
                kotlin.math.abs(other.gridTapPoint.y - anchor.gridTapPoint.y) <= 32
            }.sortedBy { it.gridTapPoint.x }
            if (rowPeers.size >= 3) {
                val spanX = rowPeers.last().gridTapPoint.x - rowPeers.first().gridTapPoint.x
                if (spanX >= 420) {
                    return rowPeers[rowPeers.size / 2]
                }
            }
        }
        return null
    }

    /**
     * 【升級 #1：走錯路自動返回上一頁 (Auto-Backtrack) 與目標網頁到達鎖定】
     * 核心原則：
     * 1. 若動手權限已撤銷 (`!hasMotorPermission()`) 或畫面仍在載入中 (`SmartLoadingDetector.isScreenCurrentlyLoading(report)`)，嚴禁觸發返回！
     * 2. 若已確認進入正確的目標網頁或目標子介面（例如搜尋後點進目標網站，或頁面已呈現目標實體），且當前為最後一步，
     *    立即提前封鎖 AI 動手權限 (`revokeMotorPermissionImmediately`) 並回傳 `null`，絕對禁止往後倒退！
     * 3. 唯有在頁面穩定載入完成、確認未達標，且已執行過至少 1 次滑動探索仍無目標 (`hasScrolledOnceOnSubPage && lowScoreAfterScroll`) 時，
     *    才允許觸發 `AiActionType.NAVIGATE_BACK` 退出錯誤頁面！
     */
    fun shouldTriggerAutoBacktrack(
        report: VisionPipelineReport,
        userGoal: String,
        activeSubGoal: CooperativeSubGoal?,
        bestScore: Float,
        consecutiveScrollNoChangeCount: Int = 0
    ): NavBreadcrumb? {
        if (!InAppWorkingMemory.hasMotorPermission()) return null
        if (!InAppWorkingMemory.canAutoBacktrack()) return null
        if (SmartLoadingDetector.isScreenCurrentlyLoading(report)) return null

        val (query, _) = extractSearchQueryAndFollowUp(userGoal)
        val subPayload = activeSubGoal?.inputPayload?.trim().orEmpty()
        val effectiveQuery = subPayload.ifBlank { query }
        val isInputOrSearchSubGoal = activeSubGoal?.intentCategory == "INPUT_SEARCH" ||
            activeSubGoal?.intentCategory == "INPUT_TEXT" ||
            effectiveQuery.isNotBlank()
        // 【嚴格驗收閘門：文字填入前禁止誤觸返回鍵】
        // 當當前子目標為輸入/搜尋且該目標文字尚未完成填入送出前，絕對禁止觸發自動返回上一頁！
        val isCurrentQuerySubmitted = if (effectiveQuery.isNotBlank()) {
            InAppWorkingMemory.hasSubmittedEquivalentQuery(effectiveQuery)
        } else {
            InAppWorkingMemory.hasAnySubmittedQuery()
        }
        if (isInputOrSearchSubGoal && !isCurrentQuerySubmitted) {
            return null
        }
        val hasActiveInputWaiting = report.finalNodes.any { it.shortTypeTag == "INP" } &&
            effectiveQuery.isNotBlank() &&
            !InAppWorkingMemory.hasSubmittedEquivalentQuery(effectiveQuery)
        if (hasActiveInputWaiting) return null

        // 【階段二 & 階段三保護】若當前子介面已經包含目標按鈕或其跨廠牌同義詞（例如找「深色模式」，此頁面有「暗色主題」），絕不誤退回！
        val subGoalPhrase = activeSubGoal?.targetSemanticPhrase.orEmpty()
        val cleanFeature = LookaheadPlannerEngine.extractCleanTargetFeature(userGoal, subGoalPhrase)
        if (cleanFeature.isNotBlank() && LookaheadPlannerEngine.hasTargetOrSynonymOnScreen(report, cleanFeature)) {
            return null
        }

        // 【目標網頁/內頁到達確認與權限提前封鎖】
        // 若剛從搜尋結果列表點進網站 (topBreadcrumb.stage == STAGE_C_SELECT_RESULT) 或目標是「點進網站/網頁」，
        // 且當前畫面已載入出目標內容（包含搜尋詞語義片段或已進入網站文章/內頁），直接封鎖動手權限並禁止返回！
        val topBreadcrumb = InAppWorkingMemory.peekBreadcrumb()
        val isFinalStep = activeSubGoal == null || activeSubGoal.stepIndex >= activeSubGoal.totalSteps
        val combinedGoalLower = "${userGoal.lowercase()} ${subGoalPhrase.lowercase()}"
        val isEnteringWebpageGoal = topBreadcrumb?.stage == InAppOperationStage.STAGE_C_SELECT_RESULT ||
            combinedGoalLower.contains("網站") ||
            combinedGoalLower.contains("網頁") ||
            combinedGoalLower.contains("官網") ||
            combinedGoalLower.contains("點進")
        if (isEnteringWebpageGoal && isCurrentQuerySubmitted) {
            val contentNodes = report.finalNodes.filter { it.shortTypeTag != "INP" && it.shortTypeTag != "SCR" && it.label.isNotBlank() }
            val queryNorm = effectiveQuery.trim().lowercase().replace('臺', '台')
            val queryBigrams = if (queryNorm.length >= 4) {
                com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.meaningfulBigrams(queryNorm)
            } else emptyList()
            val hasQueryEntityOnPage = queryNorm.isNotEmpty() && contentNodes.any { node ->
                val lbl = node.label.lowercase().replace('臺', '台')
                lbl.contains(queryNorm) ||
                    (queryBigrams.isNotEmpty() && queryBigrams.any { bg -> lbl.contains(bg) })
            }
            val leftSearchResultsList = queryNorm.isEmpty() || !ClosedLoopVerifier.isSearchResultsPageLoadedInReport(report, effectiveQuery)
            if (contentNodes.size >= 2 && (hasQueryEntityOnPage || leftSearchResultsList)) {
                if (isFinalStep) {
                    InAppWorkingMemory.revokeMotorPermissionImmediately("TARGET_WEBPAGE_CONFIRMED_LOCK")
                }
                return null
            }
        }

        // 相容原始加權分數 (0..100) 與正規化信心分數 (0..1)
        val lowScoreAfterScroll = if (bestScore <= 1.0f) bestScore < 0.26f else bestScore < 14f

        // 【階段三：必須先等待頁面穩定並至少滑動探索 1 次確認未達標，才允許退出返回上一頁】
        // 禁止在剛進入網頁/新介面的第一幀（尚未滑動探索前）僅因節點少或分數低就立刻退回上一頁！
        val hasScrolledOnceOnSubPage = consecutiveScrollNoChangeCount >= 1 ||
            InAppWorkingMemory.currentSubPageScrollCount >= 1

        val shouldBacktrack = lowScoreAfterScroll && hasScrolledOnceOnSubPage

        if (!shouldBacktrack) return null
        return InAppWorkingMemory.popBreadcrumbForBacktrack()
    }

    /**
     * 決策層判定：檢查指令是否具備明確的「搜尋 / 輸入 / 播放 / 購買 / 提問」意圖。
     * 若指令屬於「隨機挑選 / 序數挑選 / 一般點擊列表項」（例如：「進入gmail隨機選一個mail來點」、「點第一封信」），
     * 嚴格回傳 false，禁止行動層擅自展開搜尋框或把整句話當成搜尋詞輸入！
     */
    fun hasExplicitSearchOrInputIntent(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if (BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(clean)) {
            return false
        }
        val explicitSearchVerbs = listOf(
            "搜尋", "搜索", "查找", "查詢", "查一下", "搜一下", "找一下", "去搜",
            "導航到", "導航去", "路線到", "輸入", "打字", "填入", "search", "find", "look up",
            "買茶歌", "茶歌", "查歌", "找歌", "聽歌", "放歌", "點歌", "點一首歌", "放一首", "播一首",
            "買", "購買", "下單", "誰是", "什麼是", "哪裡是", "為什麼", "如何", "怎麼"
        )
        val lower = clean.lowercase()
        if (explicitSearchVerbs.any { lower.contains(it) }) {
            return true
        }
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec(clean)
        return (spec.terminalVerb == TerminalVerb.SEARCH_FIND ||
            spec.terminalVerb == TerminalVerb.PLAY ||
            spec.terminalVerb == TerminalVerb.BUY) &&
            spec.targetEntity.isNotBlank() &&
            !BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(spec.targetEntity)
    }

    /**
     * 從自然語言指令中精準提取「搜尋關鍵字 (`query`)」與「搜尋後操作目標 (`followUp`)」
     */
    fun extractSearchQueryAndFollowUp(goal: String): Pair<String, String> {
        val clean = goal.trim()
        if (clean.isEmpty()) return "" to ""

        // 決策層防呆：若為隨機/序數挑選列表項（例如「進入gmail隨機選一個mail來點」），絕非關鍵字搜尋！
        if (BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(clean)) {
            return "" to ""
        }

        // 1. 優先透過自研 BackwardChainingPlanner 進行結構化提煉（支援語音同音詞「買茶歌」、口語贅詞「一首歌叫」、App 前綴與「然後/接著」後續動作切分）
        val goalSpec = BackwardChainingPlanner.extractTerminalGoalSpec(clean)
        val isExplicitSearchOrPlay = goalSpec.terminalVerb == TerminalVerb.PLAY ||
            goalSpec.terminalVerb == TerminalVerb.SEARCH_FIND ||
            (goalSpec.terminalVerb == TerminalVerb.BUY && goalSpec.appHint != null)

        if (goalSpec.targetEntity.isNotBlank() && isExplicitSearchOrPlay) {
            val nonSearchNavigationWords = setOf("設定", "藍牙", "wifi", "wi-fi", "網路", "返回", "上一頁", "首頁", "付款", "深色模式", "相簿")
            if (goalSpec.targetEntity.lowercase() !in nonSearchNavigationWords) {
                return cleanQueryToken(goalSpec.targetEntity) to cleanFollowUpToken(goalSpec.followUpAction)
            }
        }

        // 2. 匹配明確的「搜尋/搜索/查找/查詢/查一下/導航到/search/find/look up + 關鍵字 (+ 連接詞 + 後續動作)」
        val explicitRegex = Regex(
            "(?:搜尋|搜索|查找|查詢|查一下|搜一下|找一下|去搜|導航到|導航去|路線到|search\\s+for|search|find|look\\s+up)[:：\\s\"「『]*([^\"」』，,。！!？?]+?)[」』\"]?(?:\\s*(?:並|并|並且|并且|然後|然后|接著|接着|再來|再来|之後|之后|後|，|,|->|and\\s+then|then|to)\\s*(.+))?$",
            RegexOption.IGNORE_CASE
        )
        val mExplicit = explicitRegex.find(clean)
        if (mExplicit != null) {
            var rawQuery = mExplicit.groupValues[1].trim()
            var rawFollowUp = mExplicit.groupValues.getOrNull(2)?.trim().orEmpty()

            // 若 rawQuery 尾端帶有「並播放 / 點擊第一個 / 點第一首 / 點進網站」等未用連接詞隔開的動作，進一步切分
            val inlineFollowUpRegex = Regex("^(.+?)(?:的)?(第一[個首部支篇條則]|最上面那[個首部]|點進網站|點進官網|點進網頁|進入網站|進入官網|點開網站|進網站|播放|打開|點開|留言|分享|訂閱|購買|加入購物車)$")
            val mInline = inlineFollowUpRegex.find(rawQuery)
            if (mInline != null && mInline.groupValues[1].trim().isNotEmpty()) {
                rawQuery = mInline.groupValues[1].trim()
                val tailAction = mInline.groupValues[2].trim()
                rawFollowUp = if (rawFollowUp.isEmpty()) tailAction else "$tailAction $rawFollowUp"
            }

            if (rawQuery.isNotEmpty() && rawQuery != "框" && rawQuery != "輸入框" && rawQuery != "按鈕") {
                return cleanQueryToken(rawQuery) to cleanFollowUpToken(rawFollowUp)
            }
        }

        // 2. 匹配口語「找XXX / 查XXX / 聽XXX / 看XXX」句型（排除「找到設定」這類純導航）
        val colloquialRegex = Regex(
            "(?:幫我找|我想找|我要找|找|查|聽|播放)[:：\\s\"「『]*([^\"」』，,。！!？?]{2,24})[」』\"]?(?:\\s*(?:並|并|並且|并且|然後|然后|接著|接着|再|，|,|->)\\s*(.+))?$"
        )
        val mColloquial = colloquialRegex.find(clean)
        if (mColloquial != null) {
            val candidateQuery = mColloquial.groupValues[1].trim()
            val follow = mColloquial.groupValues.getOrNull(2)?.trim().orEmpty()
            val nonSearchNavigationWords = setOf("設定", "藍牙", "wifi", "wi-fi", "網路", "返回", "上一頁", "首頁")
            if (candidateQuery.lowercase() !in nonSearchNavigationWords) {
                return cleanQueryToken(candidateQuery) to cleanFollowUpToken(follow)
            }
        }

        return "" to ""
    }

    private fun cleanQueryToken(raw: String): String {
        var cleaned = raw.trim()
            .removePrefix("「").removeSuffix("」")
            .removePrefix("『").removeSuffix("』")
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .trim()
        // 清理口語重複輸入的「搜尋搜尋...」前綴（例如「去youtube搜尋搜尋東西」->「東西」）
        val duplicateSearchPrefix = Regex("^(?:搜尋|搜索|查找|查詢|查一下|搜一下|找一下|去搜)[:：\\s]*", RegexOption.IGNORE_CASE)
        while (true) {
            val next = cleaned.replaceFirst(duplicateSearchPrefix, "").trim()
            if (next.isNotEmpty() && next != cleaned) {
                cleaned = next
            } else {
                break
            }
        }
        return cleaned
    }

    private fun cleanFollowUpToken(raw: String): String {
        return raw.trim()
            .replace(Regex("^(請幫我|幫我|請|點擊|點選|點進|點入|點開|點一下|按一下|按下|選擇|進入|進|去)\\s*"), "")
            .trim()
    }

    /**
     * 偵測畫面頂層是否存在阻擋任務的彈窗、廣告或更新提示（僅回傳可自動關閉之 `DISMISS` 元件；
     * 權限允許與條款同意由 `detectPermissionOrTermsGate` 轉交 `CONFIRM_HIGH_RISK` 安全閘門）。
     */
    fun detectTopLayerBlocker(
        report: VisionPipelineReport,
        userGoal: String = "",
        context: Context? = null
    ): Stage2ConvergedNode? {
        if (report.finalNodes.isEmpty()) return null
        val goalLower = userGoal.trim().lowercase()
        val screenHash = computeQuickScreenHash(report)

        val hasLegitTaskSheet = com.example.newandroidapp.ai.PopupDismissalPolicy.screenHasLegitTaskSheet(
            report.finalNodes,
            context
        )
        val hasModalScrim = report.stage1AllNodes.any { it.raw.isModalScrim }
        val hasNanoDetDialog = report.finalNodes.any { it.nanoDetCategory == "UI_DIALOG_CONTAINER" }
        val hasPopupContextOnScreen = hasModalScrim || hasNanoDetDialog || report.finalNodes.any { node ->
            com.example.newandroidapp.ai.PopupDismissalPolicy.isPopupContext(node.label, context)
        }

        var bestBlocker: Stage2ConvergedNode? = null
        var bestBlockerPriority = 0f

        for (node in report.finalNodes) {
            val rawLabel = node.label.trim()
            if (rawLabel.isEmpty()) continue
            val lowerLabel = rawLabel.lowercase()

            // 暗黑模式誘導按鈕（如「免費試用」「立即領取」「開心收下」）嚴禁當成關閉鈕點擊
            if (com.example.newandroidapp.ai.PopupDismissalPolicy.isDarkPatternCta(rawLabel, context)) {
                continue
            }

            // 當畫面存在正常任務底部面板白名單詞（如「選擇商品規格」「加入購物車」）時，不把一般「取消/關閉」當作彈窗攔截
            if (hasLegitTaskSheet && (lowerLabel == "取消" || lowerLabel == "關閉" || lowerLabel == "返回")) {
                continue
            }

            val userExplicitlyWantsDisable = goalLower.isNotEmpty() &&
                goalLower.contains("不要") && goalLower.contains(lowerLabel)
            if (userExplicitlyWantsDisable) continue

            val blockerSig = "$screenHash|${InAppWorkingMemory.buildCandidateSignature(node)}"
            if (InAppWorkingMemory.getBlockerDismissCount(blockerSig) >= 2) {
                continue
            }

            // A. 明確的廣告略過 / 彈窗關閉拒絕按鈕（統一由 PopupDismissalPolicy 裁決與給分）
            val policyMatch = com.example.newandroidapp.ai.PopupDismissalPolicy.matchAction(rawLabel, context)
            if (policyMatch != null && policyMatch.kind == com.example.newandroidapp.ai.PopupDismissalPolicy.Kind.DISMISS) {
                val isUnambiguousDismissButton = policyMatch.priority >= 0.94f ||
                    lowerLabel.contains("略過") || lowerLabel.contains("跳過") || lowerLabel.contains("skip") ||
                    lowerLabel.contains("稍後") || lowerLabel.contains("以後") || lowerLabel.contains("下次") ||
                    lowerLabel.contains("暫不") || lowerLabel.contains("殘忍拒絕") || lowerLabel.contains("放棄優惠") ||
                    lowerLabel.contains("我知道了") || lowerLabel.contains("知道了") || lowerLabel.contains("不再")
                if (isUnambiguousDismissButton || hasPopupContextOnScreen) {
                    if (policyMatch.priority > bestBlockerPriority) {
                        bestBlockerPriority = policyMatch.priority
                        bestBlocker = node
                    }
                    continue
                }
            }

            // B. 當畫面存在模態遮罩 (hasModalScrim)、明顯彈窗語境、或由第 8 號模型 NanoDet-Plus-m 高信心偵測出頂層 `UI_CLOSE_X` 關閉按鈕時
            val isNanoDetCloseX = node.sourceType == NodeSourceType.NANODET_UI_DETECT &&
                node.nanoDetCategory == "UI_CLOSE_X" &&
                node.visualConfidence >= 0.70f
            val isCloseSymbol = isNanoDetCloseX ||
                rawLabel == "關閉" || rawLabel.equals("x", ignoreCase = true) ||
                lowerLabel == "關閉" || lowerLabel == "close" ||
                lowerLabel.contains("關閉(x)") || node.inlineStatePrefix.contains("關閉(X)") ||
                node.inlineStatePrefix.contains("關閉按鈕X")
            val allowVisualPopupClose = (hasPopupContextOnScreen && !hasLegitTaskSheet) ||
                (isNanoDetCloseX && (report.detectedRoiVoids.isNotEmpty() || report.nanoDetDetectedCount > 0))
            if (isCloseSymbol && allowVisualPopupClose) {
                val hasNearbyTopInput = node.gridTapPoint.y < 180 &&
                    report.finalNodes.any { it.shortTypeTag == "INP" && kotlin.math.abs(it.gridTapPoint.y - node.gridTapPoint.y) < 60 }
                if (!hasNearbyTopInput) {
                    val priority = when {
                        isNanoDetCloseX -> (0.85f + node.visualConfidence * 0.11f).coerceAtMost(0.96f)
                        hasModalScrim -> 0.90f
                        else -> 0.82f
                    }
                    if (priority > bestBlockerPriority) {
                        bestBlockerPriority = priority
                        bestBlocker = node
                    }
                }
            }
        }

        return bestBlocker
    }

    /**
     * 偵測畫面是否為系統權限請求或隱私條款同意對話框：
     * 依照安全規則不自動授權，回傳待確認之按鈕與匹配類型，交由 `CONFIRM_HIGH_RISK` 安全閘門處理。
     */
    fun detectPermissionOrTermsGate(
        report: VisionPipelineReport,
        userGoal: String = "",
        context: Context? = null
    ): Pair<Stage2ConvergedNode, com.example.newandroidapp.ai.PopupDismissalPolicy.Match>? {
        if (report.finalNodes.isEmpty()) return null
        val hasPopupOrPermissionContext = report.stage1AllNodes.any { it.raw.isModalScrim } ||
            report.finalNodes.any {
                it.nanoDetCategory == "UI_DIALOG_CONTAINER" ||
                    com.example.newandroidapp.ai.PopupDismissalPolicy.isPopupContext(it.label, context)
            }
        var bestPair: Pair<Stage2ConvergedNode, com.example.newandroidapp.ai.PopupDismissalPolicy.Match>? = null
        for (node in report.finalNodes) {
            val rawLabel = node.label.trim()
            if (rawLabel.isEmpty()) continue
            val m = com.example.newandroidapp.ai.PopupDismissalPolicy.matchAction(rawLabel, context) ?: continue
            if (m.kind == com.example.newandroidapp.ai.PopupDismissalPolicy.Kind.PERMISSION_ALLOW ||
                m.kind == com.example.newandroidapp.ai.PopupDismissalPolicy.Kind.TERMS_AGREE
            ) {
                val isUnambiguousPermissionPhrase = m.priority >= 0.96f ||
                    rawLabel.contains("允許") || rawLabel.contains("同意並繼續") || rawLabel.contains("我同意") ||
                    rawLabel.lowercase().contains("while using the app") || rawLabel.lowercase().contains("only this time")
                if (isUnambiguousPermissionPhrase || hasPopupOrPermissionContext) {
                    if (bestPair == null || m.priority > bestPair.second.priority) {
                        bestPair = node to m
                    }
                }
            }
        }
        return bestPair
    }

    /**
     * 核心：評估 App 內三階段搜尋與操作鏈 + 工作記憶防重複點擊 + 跨任務持久化經驗記憶 (`EpisodicAppMemory`)
     */
    fun evaluateInAppStateOverrideOrAdjustScores(
        report: VisionPipelineReport,
        userGoal: String,
        currentSubGoal: String = "",
        baseCandidates: List<Pair<Stage2ConvergedNode, Float>> = emptyList(),
        context: Context? = null,
        currentPackage: String = "",
        screenFp: String = "",
        sessionGoalId: String = userGoal
    ): InAppPlanEvaluation {
        InAppWorkingMemory.ensureGoalContext(sessionGoalId)
        val screenHash = screenFp.ifBlank { computeQuickScreenHash(report) }
        val activePkg = currentPackage.ifBlank { InAppWorkingMemory.expectedTargetPackage }

        val (goalQuery, goalFollowUp) = extractSearchQueryAndFollowUp(userGoal)
        val (subQuery, subFollowUp) = extractSearchQueryAndFollowUp(currentSubGoal)
        val query = subQuery.ifBlank { goalQuery }
        val rawFollowUp = subFollowUp.ifBlank { goalFollowUp }
        val residualProj = com.example.newandroidapp.ai.UnifiedStageScoringHead.computeResidualIntentProjection(
            fullGoal = userGoal,
            completedQuery = query,
            explicitFollowUp = rawFollowUp
        )
        val followUp = if (rawFollowUp.isNotBlank()) {
            rawFollowUp
        } else if (residualProj.hasWebsiteOrLinkIntent || residualProj.hasPlayOrWatchIntent || residualProj.hasFirstItemOrOpenIntent) {
            residualProj.residualTextPhrase
        } else {
            ""
        }

        // 0. 優先檢查頂層阻擋彈窗（廣告、更新、評分關閉鈕）
        val blocker = detectTopLayerBlocker(report, userGoal, context)
        if (blocker != null) {
            return InAppPlanEvaluation(
                stage = InAppOperationStage.DISMISS_BLOCKER,
                screenHash = screenHash,
                extractedQuery = query,
                extractedFollowUp = followUp,
                blockerCandidate = blocker,
                overrideActionType = AiActionType.TAP,
                overrideTargetNode = blocker,
                overrideConfidence = 0.97f,
                bestRawScore = 97f,
                stageReasoningNote = "[自動排除阻擋彈窗] 偵測到頂層干擾元件 #${blocker.shortMarkId}「${blocker.label}」，優先點擊排除"
            )
        }

        // 0.5 檢查系統權限請求或隱私條款同意對話框：改走 CONFIRM_HIGH_RISK 安全閘門，不自動授權
        val permOrTermsGate = detectPermissionOrTermsGate(report, userGoal, context)
        if (permOrTermsGate != null) {
            val (gateNode, gateMatch) = permOrTermsGate
            SafetyGateManager.recordPendingAction(
                node = gateNode,
                targetMarkId = gateNode.shortMarkId,
                tapPx = gateNode.finalTapPx,
                gridPt = gateNode.gridTapPoint,
                opcode = "C! -> #${gateNode.shortMarkId} @${gateNode.gridTapPoint.x},${gateNode.gridTapPoint.y}",
                packageName = activePkg.ifBlank { null }
            )
            return InAppPlanEvaluation(
                stage = InAppOperationStage.DISMISS_BLOCKER,
                screenHash = screenHash,
                extractedQuery = query,
                extractedFollowUp = followUp,
                blockerCandidate = gateNode,
                overrideActionType = AiActionType.CONFIRM_HIGH_RISK,
                overrideTargetNode = gateNode,
                overrideConfidence = gateMatch.priority,
                bestRawScore = gateMatch.priority * 100f,
                stageReasoningNote = "[安全閘門確認 (${gateMatch.kind})] 偵測到權限或條款授權按鈕 #${gateNode.shortMarkId}「${gateNode.label}」，不自動授權，請回覆「確認執行」"
            )
        }

        val inputNodes = report.finalNodes.filter { it.shortTypeTag == "INP" && !isForbiddenVoiceOrCameraNode(it) }

        // 【升級 #2：自動學習搜尋入口】若上一動點擊了非 INP 的搜尋入口，且本幀成功出現 INP 輸入框，自動寫入 EpisodicAppMemory（支援自癒靜默覆寫）！
        val prevSearchCandidate = InAppWorkingMemory.lastTappedSearchEntryCandidate
        if (inputNodes.isNotEmpty() && prevSearchCandidate != null && activePkg.isNotBlank()) {
            EpisodicAppMemory.recordVerifiedSearchEntry(
                context = context,
                packageName = activePkg,
                appLabel = "",
                node = prevSearchCandidate,
                screenWidth = report.screenWidth,
                screenHeight = report.screenHeight
            )
            InAppWorkingMemory.lastTappedSearchEntryCandidate = null
        }

        // 建立初始分數表
        val scoreMap = LinkedHashMap<String, Float>()
        for (node in report.finalNodes) {
            scoreMap[node.nodeId] = 0.20f
        }
        for ((node, rawScore) in baseCandidates) {
            scoreMap[node.nodeId] = maxOf(scoreMap[node.nodeId] ?: 0f, rawScore)
        }

        // 1. 套用工作記憶防重複點擊濾波器 (-45f)、【嚴禁點擊語音輸入/麥克風鐵律禁令 (-200f)】、【嚴禁誤觸瀏覽器無痕/分頁工具列 (-200f)】與【暗黑模式誘導按鈕攔截 (-200f)】
        for (node in report.finalNodes) {
            if (isForbiddenVoiceOrCameraNode(node) ||
                isForbiddenBrowserToolbarNode(node, userGoal) ||
                com.example.newandroidapp.ai.PopupDismissalPolicy.isDarkPatternCta(node.label, context)
            ) {
                scoreMap[node.nodeId] = -200f
                continue
            }
            if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) {
                scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0f) - 45f
            }
        }

        // 1.5 【四層 AI 認知推理與深度理解大腦 (CognitiveReasoningEngine)】
        // 執行比較級數值推理（最便宜/最貴）、空間方位指代、ReAct 前置條件檢查（結帳前先選商品規格）、
        // 搜尋無結果自動精簡關鍵字重搜、同行關聯控制項替代點擊與底部導覽防誤觸！
        val cogDiag = CognitiveReasoningEngine.diagnoseAndReason(
            report = report,
            userGoal = userGoal,
            activeSubGoalPhrase = currentSubGoal,
            screenHash = screenHash
        )
        for ((nodeId, delta) in cogDiag.candidateScoreDeltas) {
            scoreMap[nodeId] = (scoreMap[nodeId] ?: 0.2f) + delta
        }
        if (cogDiag.overrideTargetNode != null && cogDiag.overrideActionType != null) {
            return InAppPlanEvaluation(
                stage = InAppOperationStage.IN_APP_NAVIGATION,
                screenHash = screenHash,
                extractedQuery = cogDiag.overrideInputText ?: query,
                extractedFollowUp = followUp,
                blockerCandidate = null,
                overrideActionType = cogDiag.overrideActionType,
                overrideTargetNode = cogDiag.overrideTargetNode,
                overrideInputText = cogDiag.overrideInputText,
                overrideConfidence = cogDiag.overrideConfidence,
                bestRawScore = 95f,
                stageReasoningNote = cogDiag.reasoningTraceNote
            )
        }

        val queryLower = query.lowercase()
        val queryNorm = queryLower.replace('臺', '台')
        val followUpLower = followUp.lowercase()

        val inpAlreadyContainsQuery = query.isNotBlank() && inputNodes.any { inp ->
            val l = "${inp.inlineStatePrefix}${inp.label}".lowercase().replace('臺', '台')
            l.contains(queryNorm)
        }
        val topSearchEchoAlreadyContainsQuery = query.isNotBlank() && report.finalNodes.any { node ->
            val boxW = (node.convergedBounds.right - node.convergedBounds.left).coerceAtLeast(1)
            node.gridTapPoint.y <= 220 &&
                node.shortTypeTag != "SCR" &&
                node.predictedFunctionRole != "FILTER_SORT_CHIP" &&
                (node.predictedFunctionRole == "SEARCH_INPUT_BOX" ||
                    node.predictedFunctionRole == "SEARCH_ENTRY" ||
                    (node.shortTypeTag == "BTN" && boxW >= (report.screenWidth * 0.42f).toInt())) &&
                scoreQueryMatchQuality(node.label, query) >= 95f
        }
        val mainViewportContentNodes = report.finalNodes.filter { node ->
            node.shortTypeTag != "INP" &&
                node.shortTypeTag != "SCR" &&
                node.gridTapPoint.y in 180..860 &&
                node.label.trim().isNotEmpty()
        }
        val queryMeaningfulBigrams = if (queryNorm.length >= 4) {
            com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.meaningfulBigrams(queryNorm, context)
        } else emptyList()
        val viewportMatchingCount = if (query.isNotBlank()) {
            mainViewportContentNodes.count { node ->
                val normLbl = node.label.lowercase().replace('臺', '台')
                normLbl.contains(queryNorm) || (queryMeaningfulBigrams.isNotEmpty() && queryMeaningfulBigrams.count { bg -> normLbl.contains(bg) } >= 2)
            }
        } else 0
        val viewportHasMatchingResults = viewportMatchingCount >= 1 &&
            (inpAlreadyContainsQuery ||
                InAppWorkingMemory.hasSubmittedQuery(query) ||
                InAppWorkingMemory.hasTypedButNotSubmitted(query) ||
                (topSearchEchoAlreadyContainsQuery && (viewportMatchingCount >= 2 || com.example.newandroidapp.ai.InstructionCompilerPipeline.isEntityDetailOrPlaceSheetLoaded(report))))

        val hasFocusedInput = inputNodes.any { inp ->
            inp.inlineStatePrefix.contains("焦點") || inp.inlineStatePrefix.contains("FOC")
        }
        val hasTopEditableInput = inputNodes.any { inp -> inp.gridTapPoint.y <= 250 }
        val isPlaceOrDetailLoaded = com.example.newandroidapp.ai.InstructionCompilerPipeline.isEntityDetailOrPlaceSheetLoaded(report)
        val isFullSearchResultLoaded = ClosedLoopVerifier.isSearchResultsPageLoadedInReport(report, query)
        // 若進入 App 時畫面已呈現該搜尋詞的搜尋結果頁或實體詳情卡（頂部搜尋欄已顯示該詞且下方有結果卡片），自動補記已送出狀態，免除重複點開搜尋欄重打字！
        if (query.isNotBlank() && topSearchEchoAlreadyContainsQuery && !hasTopEditableInput && (viewportMatchingCount >= 1 || isPlaceOrDetailLoaded || isFullSearchResultLoaded)) {
            if (!InAppWorkingMemory.hasSubmittedEquivalentQuery(query)) {
                InAppWorkingMemory.recordTextTypedAndSubmitted(query)
            }
        }
        // 通用自動補全狀態判定：當頂部仍有可編輯輸入框 (INP) 且畫面尚未載入正式搜尋結果頁或實體詳情卡時，維持在 Stage B 點選自動補全建議項或送出按鈕
        val isStillInAutocompleteInputMode = inputNodes.isNotEmpty() &&
            !isPlaceOrDetailLoaded &&
            !isFullSearchResultLoaded &&
            (hasFocusedInput || (hasTopEditableInput && inpAlreadyContainsQuery))

        val isQuerySubmitted = query.isNotBlank() &&
            !isStillInAutocompleteInputMode &&
            (InAppWorkingMemory.hasSubmittedQuery(query) || viewportHasMatchingResults)

        val preliminaryStage = when {
            query.isNotBlank() && !isQuerySubmitted && inputNodes.isEmpty() -> InAppOperationStage.STAGE_A_EXPAND_SEARCH
            query.isNotBlank() && !isQuerySubmitted && inputNodes.isNotEmpty() -> InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT
            query.isNotBlank() && isQuerySubmitted -> InAppOperationStage.STAGE_C_SELECT_RESULT
            else -> InAppOperationStage.IN_APP_NAVIGATION
        }

        // 2. 套用跨任務持久化經驗記憶庫與動態抗改版向量自癒機制 (EpisodicAppMemory)
        var episodicHitTag: String? = null
        if (activePkg.isNotBlank()) {
            val subGoalKw = currentSubGoal.ifBlank { userGoal }
            val redesignCheck = EpisodicAppMemory.detectPreClickRedesignAndInvalidate(
                context = context,
                packageName = activePkg,
                visibleNodes = report.finalNodes,
                stage = preliminaryStage,
                query = query,
                subGoalKeyword = subGoalKw,
                screenFp = screenHash,
                screenWidth = report.screenWidth,
                screenHeight = report.screenHeight
            )
            for (node in report.finalNodes) {
                val (epDelta, epReason) = EpisodicAppMemory.computeEpisodicScoreAdjustment(
                    context = context,
                    packageName = activePkg,
                    candidate = node,
                    stage = preliminaryStage,
                    query = query,
                    subGoalKeyword = subGoalKw,
                    screenFp = screenHash,
                    screenWidth = report.screenWidth,
                    screenHeight = report.screenHeight
                )
                if (epDelta != 0f) {
                    scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0f) + epDelta
                    if (epDelta > 0f && episodicHitTag == null && epReason != null) {
                        val healPrefix = if (redesignCheck.redesignDetected) "[偵測改版重搜] " else ""
                        episodicHitTag = "$healPrefix$epReason(#${node.shortMarkId}「${node.label}」+${epDelta.toInt()})"
                    }
                }
            }
        }

        var activeStage = preliminaryStage
        var overrideAction: AiActionType? = null
        var overrideNode: Stage2ConvergedNode? = null
        var overrideInputText: String? = null
        var overrideConf = 0.95f
        var stageNote = ""

        // 逆向目標回推思維引擎 (BackwardChainingPlanner) 評估
        val goalForBackwardSpec = currentSubGoal.ifBlank { userGoal }
        val backwardSpec = BackwardChainingPlanner.extractTerminalGoalSpec(goalForBackwardSpec)
        val effectiveTargetEntity = backwardSpec.targetEntity.ifBlank { query }
        val backwardEval = if (effectiveTargetEntity.isNotBlank()) {
            BackwardChainingPlanner.evaluateBackwardChain(
                goalSpec = backwardSpec.copy(targetEntity = effectiveTargetEntity),
                nodes = report.finalNodes,
                packageName = activePkg,
                hasSubmittedQuery = isQuerySubmitted,
                screenWidth = report.screenWidth,
                screenHeight = report.screenHeight
            )
        } else null

        // 【Level 0 優先命中】：終極目標實體已直接出現在主內容區，直接點擊目標！
        // 注意：1. 當搜尋已送出 (isQuerySubmitted == true) 時，必須進入 Stage C 進行搜尋結果篩選（屏障頂部搜尋框回顯），嚴禁由 Level 0 搶奪！
        // 2. 若屬於明確「搜尋/查找 (SEARCH_FIND)」任務且尚未送出搜尋，僅允許 100% 完全相同之標籤直點，
        // 嚴禁因為首頁某個頻道或推薦卡片包含子字串（如查「AI」時首頁有「人民公园说AI」）而跳過搜尋框展開！
        val isExplicitSearchFindUnsubmitted = backwardSpec.terminalVerb == TerminalVerb.SEARCH_FIND &&
            query.isNotBlank() && !isQuerySubmitted
        val cleanLevel0TargetAction = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
            .extractCleanTargetActionPhrase(currentSubGoal.ifBlank { userGoal })
        val isLevel0TargetMutuallyExcluded = backwardEval?.targetNode?.let { tgt ->
            cleanLevel0TargetAction.isNotEmpty() &&
                com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment(
                    targetPhrase = cleanLevel0TargetAction,
                    candidateLabel = tgt.label,
                    candidatePredictedRoleCode = tgt.predictedFunctionRole,
                    context = context
                ).scoreDelta <= -45f
        } ?: false

        val allowLevel0Direct = !isQuerySubmitted &&
            backwardSpec.terminalVerb != TerminalVerb.TAP_BUTTON &&
            backwardEval != null &&
            backwardEval.level == BackwardChainLevel.LEVEL_0_DIRECT_TARGET &&
            backwardEval.targetNode != null &&
            !isLevel0TargetMutuallyExcluded &&
            (!isExplicitSearchFindUnsubmitted || backwardEval.targetNode.label.trim().equals(query, ignoreCase = true))

        if (allowLevel0Direct && backwardEval != null && backwardEval.targetNode != null) {
            if (cleanLevel0TargetAction.isNotEmpty()) {
                for (node in report.finalNodes) {
                    val mutexEval = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
                        .evaluateCandidateActionAdjustment(
                            targetPhrase = cleanLevel0TargetAction,
                            candidateLabel = node.label,
                            candidatePredictedRoleCode = node.predictedFunctionRole,
                            context = context
                        )
                    if (mutexEval.scoreDelta <= -45f) {
                        scoreMap[node.nodeId] = -115f
                    } else if (mutexEval.scoreDelta > 0f) {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0.5f) + mutexEval.scoreDelta
                    }
                }
            }
            scoreMap[backwardEval.targetNode.nodeId] = (scoreMap[backwardEval.targetNode.nodeId] ?: 0.5f) + 95f
            overrideAction = backwardEval.actionType
            overrideNode = backwardEval.targetNode
            overrideConf = backwardEval.confidence
            stageNote = backwardEval.reasoningTrace
            activeStage = InAppOperationStage.IN_APP_NAVIGATION
        } else if (query.isNotBlank()) {
            if (!isQuerySubmitted && inputNodes.isEmpty()) {
                // =========================================================================
                // 【Stage A：展開搜尋入口】畫面尚無 INP 輸入框，優先尋找放大鏡/搜尋入口或經驗記憶錨點
                // =========================================================================
                activeStage = InAppOperationStage.STAGE_A_EXPAND_SEARCH
                var bestSearchEntry: Stage2ConvergedNode? = null
                var bestEntryBoost = -999f

                val isSearchingSecondDistinctQuery = InAppWorkingMemory.hasAnySubmittedQuery() && !isQuerySubmitted

                for (node in report.finalNodes) {
                    if (isForbiddenVoiceOrCameraNode(node)) continue
                    val fullText = "${node.inlineStatePrefix}${node.label}".lowercase()
                    val cleanLbl = node.label.trim().lowercase()
                    val isExactSearchWord = cleanLbl == "搜尋" || cleanLbl == "搜索" || cleanLbl == "search" || cleanLbl == "查找"
                    val isSearchRole = node.predictedFunctionRole == "SEARCH_ENTRY" || node.predictedFunctionRole == "SEARCH_INPUT_BOX"
                    val isPreviousQueryTopBox = isSearchingSecondDistinctQuery &&
                        node.gridTapPoint.y <= 220 &&
                        InAppWorkingMemory.hasSubmittedEquivalentQuery(node.label)
                    val isSearchEntry = isExactSearchWord || isSearchRole || isPreviousQueryTopBox ||
                        SEARCH_ENTRY_KEYWORDS.any { kw -> fullText.contains(kw) }

                    // 若目前是「先查完 A 再查不同定義的 B」，允許再次點擊頂部搜尋欄以輸入新目標 B
                    if (!isSearchingSecondDistinctQuery || !isSearchEntry) {
                        if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) continue
                    }
                    if (node.predictedFunctionRole == "FILTER_SORT_CHIP" || node.shortTypeTag == "CHK") {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0f) - 45f
                        continue
                    }
                    val currentScore = scoreMap[node.nodeId] ?: 0.2f
                    // 若關鍵字/角色命中或經驗記憶庫已給予 +24f 加分
                    if (isSearchEntry || currentScore >= 15f) {
                        val posBonus = if (node.gridTapPoint.y <= 180) 35f else if (node.gridTapPoint.y <= 260) 20f else 10f
                        val exactBonus = if (isExactSearchWord || isPreviousQueryTopBox) 40f else 0f
                        val roleBonus = if (isSearchRole) 35f * node.functionRoleConfidence else 0f
                        val baseEntryScore = if (isSearchingSecondDistinctQuery && isSearchEntry) {
                            maxOf(currentScore, 0.5f)
                        } else currentScore
                        val newScore = baseEntryScore + (if (isSearchEntry) 60f else 30f) + posBonus + exactBonus + roleBonus
                        scoreMap[node.nodeId] = newScore
                        if (newScore > bestEntryBoost) {
                            bestEntryBoost = newScore
                            bestSearchEntry = node
                        }
                    }
                }

                if (bestSearchEntry != null && bestEntryBoost >= 95f) {
                    overrideAction = AiActionType.TAP
                    overrideNode = bestSearchEntry
                    overrideConf = 0.96f
                    val epSuffix = if (episodicHitTag != null) " [$episodicHitTag]" else ""
                    stageNote = "[Stage A 展開純文字搜尋入口] 畫面尚無輸入框，已屏障語音輸入並鎖定文字搜尋入口 #${bestSearchEntry.shortMarkId}「${bestSearchEntry.label}」$epSuffix"
                } else if (backwardEval != null && (backwardEval.level == BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX || backwardEval.level == BackwardChainLevel.LEVEL_3B_BOTTOM_SEARCH_TAB) && backwardEval.targetNode != null) {
                    val bcNode = backwardEval.targetNode
                    scoreMap[bcNode.nodeId] = (scoreMap[bcNode.nodeId] ?: 0.5f) + 85f
                    overrideAction = backwardEval.actionType
                    overrideNode = bcNode
                    overrideConf = backwardEval.confidence
                    val epSuffix = if (episodicHitTag != null) " [$episodicHitTag]" else ""
                    stageNote = "${backwardEval.reasoningTrace}$epSuffix"
                } else if (bestSearchEntry != null && bestEntryBoost > 0f) {
                    overrideAction = AiActionType.TAP
                    overrideNode = bestSearchEntry
                    overrideConf = 0.96f
                    val epSuffix = if (episodicHitTag != null) " [$episodicHitTag]" else ""
                    stageNote = "[Stage A 展開純文字搜尋入口] 畫面尚無輸入框，已屏障語音輸入並鎖定文字搜尋入口 #${bestSearchEntry.shortMarkId}「${bestSearchEntry.label}」$epSuffix"
                }
            } else if (!isQuerySubmitted && inputNodes.isNotEmpty()) {
                // =========================================================================
                // 【Stage B：填入關鍵字並送出（優化 #3：以多維度計分取代 first / firstOrNull）】畫面已有 INP 輸入框
                // =========================================================================
                activeStage = InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT
                val primaryInp = inputNodes.maxByOrNull { BackwardChainingPlanner.scoreInputBoxCandidate(it) } ?: inputNodes.first()
                val alreadyTyped = inpAlreadyContainsQuery ||
                    InAppWorkingMemory.hasTypedButNotSubmitted(query) ||
                    InAppWorkingMemory.hasSubmittedQuery(query)

                if (alreadyTyped) {
                    for (inp in inputNodes) {
                        scoreMap[inp.nodeId] = (scoreMap[inp.nodeId] ?: 0f) - 55f
                    }

                    val submitBtn = report.finalNodes
                        .filter { node ->
                            node.nodeId != primaryInp.nodeId &&
                                isTrueSearchSubmitButton(node, primaryInp) &&
                                !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)
                        }
                        .maxByOrNull { node ->
                            var s = 50f
                            if (UiKeywordLexicon.isExactSearchWord(node.label)) s += 30f
                            if (node.gridTapPoint.x > primaryInp.gridTapPoint.x) s += 20f
                            s - kotlin.math.abs(node.gridTapPoint.y - primaryInp.gridTapPoint.y) * 0.5f
                        }
                    val bestSuggestionRow = report.finalNodes
                        .filter { node ->
                            val normNodeLbl = node.label.lowercase().replace('臺', '台')
                            node.nodeId != primaryInp.nodeId &&
                                node.shortTypeTag != "INP" &&
                                node.shortTypeTag != "SCR" &&
                                !isForbiddenVoiceOrCameraNode(node) &&
                                node.gridTapPoint.x < 820 &&
                                node.gridTapPoint.y in (primaryInp.gridTapPoint.y + 30)..760 &&
                                (normNodeLbl.contains(queryNorm) || scoreQueryMatchQuality(node.label, query) >= 35f || node.label.length >= 2) &&
                                !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)
                        }
                        .maxByOrNull { node ->
                            scoreQueryMatchQuality(node.label, query) - (node.gridTapPoint.y * 0.02f)
                        }

                    val targetToSubmit = submitBtn ?: bestSuggestionRow
                    if (targetToSubmit != null) {
                        scoreMap[targetToSubmit.nodeId] = (scoreMap[targetToSubmit.nodeId] ?: 0.4f) + 75f
                        overrideAction = AiActionType.TAP
                        overrideNode = targetToSubmit
                        overrideConf = 0.95f
                        stageNote = "[Stage B 送出搜尋/點選精確建議] 輸入框已含「$query」，直接點擊 #${targetToSubmit.shortMarkId}「${targetToSubmit.label}」進入結果頁"
                    }
                } else {
                    scoreMap[primaryInp.nodeId] = (scoreMap[primaryInp.nodeId] ?: 0.5f) + 85f
                    overrideAction = AiActionType.INPUT_TEXT
                    overrideNode = primaryInp
                    overrideInputText = query
                    overrideConf = 0.98f
                    stageNote = "[Stage B 填入搜尋詞] 鎖定輸入框 #${primaryInp.shortMarkId}「${primaryInp.label}」，自動填入關鍵字「$query」並送出"
                }
            } else {
                // =========================================================================
                // 【Stage C：於搜尋結果列表定位目標並點擊操作】
                // 重罰頂部搜尋框、網頁內嵌搜尋框與搜尋按鈕 (-120f)，防止 AI 卡在搜尋框無限重打字或誤開鍵盤！
                // =========================================================================
                activeStage = InAppOperationStage.STAGE_C_SELECT_RESULT

                for (node in report.finalNodes) {
                    val fullText = "${node.inlineStatePrefix}${node.label}".lowercase()
                    val cleanNormLbl = node.label.trim().lowercase().replace('臺', '台')
                    val isTopWebSearchEcho = node.gridTapPoint.y <= 265 && cleanNormLbl == queryNorm
                    val isTopSearchBarOrIcon = node.shortTypeTag == "INP" ||
                        isTopWebSearchEcho ||
                        fullText.contains("輕觸兩下即可在 google 搜尋") ||
                        fullText.contains("google.com/search?q=") ||
                        (node.gridTapPoint.y < 260 && SEARCH_ENTRY_KEYWORDS.any { fullText.contains(it) })
                    if (isTopSearchBarOrIcon) {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0f) - 120f
                    }
                }

                val isWebsiteFollowUp = followUpLower.contains("網站") ||
                    followUpLower.contains("官網") ||
                    followUpLower.contains("網頁") ||
                    followUpLower.contains("點進") ||
                    followUpLower.contains("進入")
                val wantsFirstItem = followUpLower.contains("第一") ||
                    followUpLower.contains("首個") ||
                    followUpLower.contains("最上面") ||
                    followUpLower.contains("播放") ||
                    isWebsiteFollowUp ||
                    followUpLower.isEmpty()

                val searchFilterTabWords = setOf(
                    "全部", "圖片", "新聞", "影片", "地圖", "購物", "書籍", "航班", "財經", "搜尋工具",
                    "all", "images", "news", "videos", "maps", "shopping"
                )

                var bestResultNode: Stage2ConvergedNode? = null
                var bestResultScore = -999f

                val cleanFollowUpAction = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
                    .extractCleanTargetActionPhrase(followUpLower)
                val followUpTargetRole = if (cleanFollowUpAction.isNotEmpty() && !cleanFollowUpAction.contains("第一") && !isWebsiteFollowUp) {
                    com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(cleanFollowUpAction, context)
                } else null

                val SIDE_ACTION_CHIP_ROLES = setOf(
                    "LIKE_FAVORITE_BOOKMARK",
                    "SHARE_FORWARD",
                    "SHARE_EXPORT",
                    "CALL_PHONE_CONTACT",
                    "DELETE_REMOVE_CLEAR",
                    "DELETE_REMOVE_TRASH",
                    "COMMENT_REPLY"
                )

                val sortedViewportNodes = mainViewportContentNodes.sortedBy { it.gridTapPoint.y }
                for ((rankIdx, node) in sortedViewportNodes.withIndex()) {
                    if (isForbiddenVoiceOrCameraNode(node) || isForbiddenBrowserToolbarNode(node, userGoal)) continue
                    val fullNodeText = "${node.inlineStatePrefix}${node.label}".lowercase()
                    if (fullNodeText.contains("輕觸兩下即可在 google 搜尋") || fullNodeText.contains("google.com/search?q=")) continue
                    val cleanLabelTrim = node.label.trim().lowercase()
                    if (cleanLabelTrim in searchFilterTabWords && node.gridTapPoint.y <= 320) {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0f) - 85f
                        continue
                    }
                    if (node.gridTapPoint.y <= 265 && cleanLabelTrim.replace('臺', '台') == queryNorm) continue
                    if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) continue

                    val labelLower = node.label.lowercase()
                    val labelNorm = labelLower.replace('臺', '台')

                    // 【15萬×15萬 動作意圖互斥與詳情卡旁路按鈕防護】
                    if (cleanFollowUpAction.isNotEmpty() && !cleanFollowUpAction.contains("第一") && !isWebsiteFollowUp) {
                        val mutexEval = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
                            .evaluateCandidateActionAdjustment(
                                targetPhrase = cleanFollowUpAction,
                                candidateLabel = node.label,
                                candidatePredictedRoleCode = node.predictedFunctionRole,
                                context = context
                            )
                        if (mutexEval.scoreDelta <= -45f && !labelNorm.contains(queryNorm)) {
                            scoreMap[node.nodeId] = -115f
                            continue
                        }
                    } else {
                        // 當沒有指定儲存/分享/通話等後續動作時，嚴禁在 Stage C 選取結果階段誤點詳情卡上的「儲存地點 / 分享 / 通話」按鈕！
                        val nodeActionRole = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager
                            .resolveUiActionRoleByTermOrIcon(cleanLabelTrim, context) ?: node.predictedFunctionRole
                        if (nodeActionRole in SIDE_ACTION_CHIP_ROLES && !labelNorm.contains(queryNorm)) {
                            scoreMap[node.nodeId] = -115f
                            continue
                        }
                    }

                    val currentRaw = scoreMap[node.nodeId] ?: 0.3f
                    // 若已被經驗記憶標記為死胡同陷阱 (< -20f)，直接跳過
                    if (currentRaw < -20f) continue

                    var boost = 20f

                    if (queryNorm.isNotEmpty() && labelNorm.contains(queryNorm)) {
                        boost += scoreQueryMatchQuality(labelNorm, queryNorm).coerceAtMost(85f)
                    } else if (queryNorm.length >= 2) {
                        val bigramHits = if (queryMeaningfulBigrams.isNotEmpty()) {
                            queryMeaningfulBigrams.count { bg -> labelNorm.contains(bg) }
                        } else 0
                        val overlap = queryNorm.count { ch -> labelNorm.contains(ch) }.toFloat() / queryNorm.length
                        boost += (overlap * 28f) + (bigramHits * 16f).coerceAtMost(48f)
                    }

                    if (isWebsiteFollowUp && (labelNorm.contains("http") || labelNorm.contains(".tw") || labelNorm.contains(".com") || labelNorm.contains("官網") || labelNorm.contains("官方") || node.label.length >= 6)) {
                        boost += 26f
                    } else if (cleanFollowUpAction.isNotEmpty() &&
                        !cleanFollowUpAction.contains("第一") &&
                        !isWebsiteFollowUp
                    ) {
                        val mutexEval = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
                            .evaluateCandidateActionAdjustment(
                                targetPhrase = cleanFollowUpAction,
                                candidateLabel = node.label,
                                candidatePredictedRoleCode = node.predictedFunctionRole,
                                context = context
                            )
                        if (mutexEval.scoreDelta > 0f) {
                            boost += mutexEval.scoreDelta + 25f
                        } else if (labelLower.contains(cleanFollowUpAction) ||
                            (cleanLabelTrim.length >= 2 && cleanFollowUpAction.contains(cleanLabelTrim))
                        ) {
                            boost += 55f
                        }
                    }

                    if (wantsFirstItem && rankIdx == 0 && node.label.length >= 2 && followUpTargetRole == null) {
                        boost += 32f
                    } else if (rankIdx <= 2 && node.label.length >= 4 && followUpTargetRole == null) {
                        boost += (18f - rankIdx * 5f)
                    }

                    val updated = currentRaw + boost
                    scoreMap[node.nodeId] = updated
                    if (updated > bestResultScore) {
                        bestResultScore = updated
                        bestResultNode = node
                    }
                }

                val shouldSelectResult = requiresResultSelectionAfterSearch(userGoal, activePkg, followUp) ||
                    (hasTopEditableInput && !isPlaceOrDetailLoaded && !isFullSearchResultLoaded) ||
                    currentSubGoal.contains("點") || currentSubGoal.contains("進網站") || currentSubGoal.contains("進官網") || currentSubGoal.contains("播放")
                if (shouldSelectResult && bestResultNode != null && bestResultScore > 10f) {
                    overrideAction = AiActionType.TAP
                    overrideNode = bestResultNode
                    overrideConf = 0.94f
                    val targetDesc = if (followUp.isNotBlank()) "「$query ($followUp)」" else "「$query」"
                    val epSuffix = if (episodicHitTag != null) " [$episodicHitTag]" else ""
                    stageNote = "[Stage C 結果列表精準點擊] 已屏障頂部搜尋框(-120f)，於主視窗鎖定最佳結果 #${bestResultNode.shortMarkId}「${bestResultNode.label}」(目標:$targetDesc)$epSuffix"
                }
            }
        } else {
            val targetPhrase = currentSubGoal.ifBlank { userGoal }.trim().lowercase()
            val cleanTargetAction = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
                .extractCleanTargetActionPhrase(targetPhrase)
            val explicitSearchIntent = hasExplicitSearchOrInputIntent(userGoal) || hasExplicitSearchOrInputIntent(currentSubGoal)
            val activeEntityQuery = goalQuery.ifBlank { InAppWorkingMemory.getActiveSearchQuery() }
            var bestResonantActionNode: Stage2ConvergedNode? = null
            var bestResonantActionScore = -999f

            for (node in report.finalNodes) {
                if (isForbiddenVoiceOrCameraNode(node)) continue
                // 當決策層判定並非搜尋意圖時（如「點擊行車路線」或「隨機選一個mail來點」），硬性遮罩頂部搜尋列與所有 INP 輸入框 (-200f)，嚴禁行動層誤點搜尋欄或亂打字！
                if (!explicitSearchIntent) {
                    val fullNodeText = "${node.inlineStatePrefix}${node.label}".lowercase()
                    if (node.shortTypeTag == "INP") {
                        scoreMap[node.nodeId] = -200f
                        continue
                    }
                    val isTopSearchBar = node.gridTapPoint.y <= 240 && SEARCH_ENTRY_KEYWORDS.any { fullNodeText.contains(it) }
                    if (isTopSearchBar) {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0f) - 85f
                        continue
                    }
                }
                if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) {
                    if (!explicitSearchIntent) {
                        scoreMap[node.nodeId] = -150f
                    }
                    continue
                }
                val labelLower = node.label.lowercase().trim()

                // 【15萬×15萬 超大規模語義互斥、反義詞圖譜與五層本體樹決策評估】
                if (cleanTargetAction.isNotEmpty() && labelLower.isNotEmpty()) {
                    val mutexEval = com.example.newandroidapp.ai.ActionIntentMutualExclusionEngine
                        .evaluateCandidateActionAdjustment(
                            targetPhrase = cleanTargetAction,
                            candidateLabel = node.label,
                            candidatePredictedRoleCode = node.predictedFunctionRole,
                            context = context
                        )
                    if (mutexEval.scoreDelta <= -45f) {
                        // 命中同排互斥動作（如目標「規劃路線」時的「儲存地點/分享/通話」）或顯式反義詞 -> 硬性遮罩 -115f
                        scoreMap[node.nodeId] = -115f
                        continue
                    } else if (mutexEval.scoreDelta > 0f) {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0.3f) + mutexEval.scoreDelta
                    }
                }

                if (cleanTargetAction.isNotEmpty() && labelLower.isNotEmpty()) {
                    val cleanCandidate = labelLower.replace("圖示:", "").trim()
                    val isCompositeContainer = isCompositeMultiActionLabel(cleanCandidate, context) ||
                        node.predictedFunctionRole == "CONTENT_CARD_ITEM" ||
                        cleanCandidate.length > cleanTargetAction.length + 12
                    val isDirectMatch = cleanCandidate == cleanTargetAction ||
                        (cleanTargetAction.length >= 2 && cleanCandidate.contains(cleanTargetAction)) ||
                        (cleanCandidate.length >= 2 && cleanTargetAction.contains(cleanCandidate))
                    if (isDirectMatch) {
                        val visualBoost = if (node.sourceType == NodeSourceType.NANODET_UI_DETECT) {
                            node.visualConfidence * 15f
                        } else 0f
                        val exactBonus = when {
                            cleanCandidate == cleanTargetAction -> 58f
                            !isCompositeContainer && cleanCandidate.length <= cleanTargetAction.length + 4 -> 46f
                            isCompositeContainer -> -35f
                            else -> 28f
                        }
                        val cardContextBonus = if (!isCompositeContainer && activeEntityQuery.isNotBlank()) {
                            computeParentCardEntityContextBonus(
                                actionButton = node,
                                allNodes = report.finalNodes,
                                activeEntityQuery = activeEntityQuery
                            )
                        } else 0f
                        val newScore = (scoreMap[node.nodeId] ?: 0.3f) + exactBonus + visualBoost + cardContextBonus
                        scoreMap[node.nodeId] = newScore
                        if (newScore > bestResonantActionScore) {
                            bestResonantActionScore = newScore
                            bestResonantActionNode = node
                        }
                    } else if ((scoreMap[node.nodeId] ?: 0f) >= 60f) {
                        val cardContextBonus = if (!isCompositeContainer && activeEntityQuery.isNotBlank()) {
                            computeParentCardEntityContextBonus(
                                actionButton = node,
                                allNodes = report.finalNodes,
                                activeEntityQuery = activeEntityQuery
                            )
                        } else if (isCompositeContainer) -40f else 0f
                        val newScore = (scoreMap[node.nodeId] ?: 0f) + cardContextBonus
                        scoreMap[node.nodeId] = newScore
                        if (newScore > bestResonantActionScore) {
                            bestResonantActionScore = newScore
                            bestResonantActionNode = node
                        }
                    }
                }
                // 第 8 號模型 NanoDet-Plus-m 視覺類別與文字指令意圖焊接加權
                if (node.sourceType == NodeSourceType.NANODET_UI_DETECT && targetPhrase.isNotEmpty()) {
                    val categoryIntentMatch = when (node.nanoDetCategory) {
                        "UI_CLOSE_X" -> targetPhrase.contains("關閉") || targetPhrase.contains("略過") || targetPhrase.contains("跳過") || targetPhrase.contains("叉")
                        "UI_SEARCH_BAR" -> targetPhrase.contains("搜尋") || targetPhrase.contains("搜索") || targetPhrase.contains("查找") || targetPhrase.contains("輸入")
                        "UI_BUTTON_CTA" -> targetPhrase.contains("確認") || targetPhrase.contains("確定") || targetPhrase.contains("送出") || targetPhrase.contains("同意") || targetPhrase.contains("下一步")
                        "UI_CHECK_SWITCH" -> targetPhrase.contains("開關") || targetPhrase.contains("勾選") || targetPhrase.contains("開啟") || targetPhrase.contains("啟用")
                        "UI_FAB_ADD" -> targetPhrase.contains("新增") || targetPhrase.contains("建立") || targetPhrase.contains("加入")
                        "UI_MEDIA_PLAY" -> targetPhrase.contains("播放") || targetPhrase.contains("暫停")
                        "UI_ICON_BACK" -> targetPhrase.contains("返回") || targetPhrase.contains("上一頁")
                        "UI_ICON_MENU_MORE" -> targetPhrase.contains("選單") || targetPhrase.contains("更多") || targetPhrase.contains("設定")
                        else -> false
                    }
                    if (categoryIntentMatch) {
                        scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0.3f) + (28f * node.visualConfidence)
                    }
                }
                // Phase 2: 離線按鈕功用分類器（AMEX 102K 訓練先驗）與當前指令意圖匹配加權
                val phase2Boost = com.example.newandroidapp.vision.dataset.Phase2ButtonFunctionalityClassifier
                    .computeGoalRoleMatchBoost(
                        userGoal = userGoal,
                        currentSubGoal = currentSubGoal,
                        nodeRoleCode = node.predictedFunctionRole,
                        roleConfidence = node.functionRoleConfidence
                    )
                if (phase2Boost != 0f) {
                    scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0.3f) + phase2Boost
                }
                // Phase 3: 全域畫面姿勢庫（Whole-Screen Posture）與狀態轉移戰術加權
                val (phase3Bonus, _) = com.example.newandroidapp.vision.dataset.Phase3ScreenPostureClassifier
                    .computePostureTacticalBonus(
                        screenPostureCode = report.screenPosture,
                        candidateNode = node,
                        hasExplicitSearchIntent = explicitSearchIntent,
                        hasExplicitCloseIntent = targetPhrase.contains("關閉") || targetPhrase.contains("略過") || targetPhrase.contains("跳過"),
                        hasExplicitBuyIntent = targetPhrase.contains("購買") || targetPhrase.contains("購物車") || targetPhrase.contains("結帳") || targetPhrase.contains("買"),
                        hasExplicitPlayIntent = targetPhrase.contains("播放") || targetPhrase.contains("聽") || targetPhrase.contains("看")
                    )
                val isGenericOrPositional = BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(userGoal) ||
                    BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(currentSubGoal)
                val hasDirectOrPartialLabelMatch = targetPhrase.isNotEmpty() && labelLower.isNotEmpty() &&
                    (labelLower.contains(targetPhrase) || targetPhrase.contains(labelLower))
                if (phase3Bonus != 0f && (node.predictedFunctionRole != "CONTENT_CARD_ITEM" || phase3Bonus < 0f || isGenericOrPositional || hasDirectOrPartialLabelMatch)) {
                    scoreMap[node.nodeId] = (scoreMap[node.nodeId] ?: 0.3f) + phase3Bonus
                }
            }

            val isTargetInViewport = targetPhrase.isNotEmpty() && mainViewportContentNodes.any { node ->
                node.label.lowercase().contains(targetPhrase)
            }

            // 【3 步前瞻推演與跨介面同義詞展開 + DFS 回溯引擎 (LookaheadPlannerEngine)】
            val lookaheadResult = LookaheadPlannerEngine.evaluateLookaheadAndCrossScreenPlan(
                report = report,
                userGoal = userGoal,
                currentSubGoal = currentSubGoal,
                screenHash = screenHash
            )
            for ((nodeId, boost) in lookaheadResult.candidateBoosts) {
                val cur = scoreMap[nodeId] ?: 0.3f
                if (cur > -100f) {
                    scoreMap[nodeId] = cur + boost
                }
            }
            // 【畫面終審鐵律】當目標為明確按鈕點擊 (!explicitSearchIntent) 且非隨機/位置挑選時，
            // 任何完全未命中字面、同義詞、28類UI本體角色、同行控制項或前瞻父分類 (raw < 1.0f) 的無關按鈕，一律封頂為 0.12f (< 0.22f)，
            // 徹底杜絕找不到按鈕（或目標按鈕已點過）時在畫面上瘋狂亂點其他無關按鈕！
            val isGenericOrPositionalGoal = BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(userGoal) ||
                BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(currentSubGoal)
            if (!explicitSearchIntent && cleanTargetAction.isNotEmpty() && !isGenericOrPositionalGoal) {
                for (node in report.finalNodes) {
                    val cur = scoreMap[node.nodeId] ?: 0f
                    val hasLookaheadBoost = (lookaheadResult.candidateBoosts[node.nodeId] ?: 0f) > 0f
                    val hasCogBoost = (cogDiag.candidateScoreDeltas[node.nodeId] ?: 0f) > 0f
                    if (cur in 0f..0.99f && !hasLookaheadBoost && !hasCogBoost) {
                        scoreMap[node.nodeId] = minOf(cur, 0.12f)
                    }
                }
            }
            val isLookaheadTargetAllowed = lookaheadResult.overrideTargetNode?.let {
                (scoreMap[it.nodeId] ?: 0f) > -100f
            } ?: false
            if (lookaheadResult.overrideActionType != null && lookaheadResult.overrideTargetNode != null && isLookaheadTargetAllowed) {
                // 若 15萬×15萬 意圖互斥與父卡片實體關聯引擎已有更高分之精準共鳴節點，優先鎖定共鳴節點
                val chosenNode = if (bestResonantActionNode != null &&
                    bestResonantActionScore > (scoreMap[lookaheadResult.overrideTargetNode.nodeId] ?: 0f)
                ) {
                    bestResonantActionNode
                } else {
                    lookaheadResult.overrideTargetNode
                }
                overrideAction = lookaheadResult.overrideActionType
                overrideNode = chosenNode
                overrideInputText = lookaheadResult.overrideInputText
                overrideConf = lookaheadResult.overrideConfidence
                stageNote = lookaheadResult.lookaheadReasoningTrace
            } else if (bestResonantActionNode != null && bestResonantActionScore >= 40f) {
                overrideAction = AiActionType.TAP
                overrideNode = bestResonantActionNode
                overrideConf = 0.95f
                stageNote = "[15萬×15萬 意圖共鳴與互斥鎖定] 目標「$cleanTargetAction」精準命中 #${bestResonantActionNode.shortMarkId}「${bestResonantActionNode.label}」"
            } else if (lookaheadResult.lookaheadReasoningTrace.isNotBlank()) {
                stageNote = lookaheadResult.lookaheadReasoningTrace
            } else if (explicitSearchIntent && !isTargetInViewport && backwardEval != null && backwardEval.targetNode != null &&
                (backwardEval.level == BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX ||
                 backwardEval.level == BackwardChainLevel.LEVEL_3B_BOTTOM_SEARCH_TAB ||
                 backwardEval.level == BackwardChainLevel.LEVEL_2_INPUT_KEYWORD)
            ) {
                scoreMap[backwardEval.targetNode.nodeId] = (scoreMap[backwardEval.targetNode.nodeId] ?: 0.4f) + 80f
                overrideAction = backwardEval.actionType
                overrideNode = backwardEval.targetNode
                overrideInputText = backwardEval.inputText
                overrideConf = backwardEval.confidence
                stageNote = backwardEval.reasoningTrace
            }
        }

        InAppWorkingMemory.updateStage(activeStage)

        // 【方法 1 + 方案 B + 方案 C + 方案 E：統一階段條件式 16 維特徵權重矩陣 + 軟性階段機率閘門 + 語義殘差向量投射】
        com.example.newandroidapp.ai.UnifiedStageScoringHead.ensureWeightsLoaded(context)
        val alreadyTypedForRegime = inpAlreadyContainsQuery || (query.isNotBlank() && InAppWorkingMemory.hasTypedButNotSubmitted(query))
        val scoringRegime = com.example.newandroidapp.ai.UnifiedStageScoringHead.resolveScoringRegime(
            preliminaryStage = activeStage,
            alreadyTypedInInput = alreadyTypedForRegime
        )
        val matchingVpCount = if (queryNorm.isNotEmpty()) {
            mainViewportContentNodes.count { node ->
                val normLbl = node.label.lowercase().replace('臺', '台')
                normLbl.contains(queryNorm) || (queryMeaningfulBigrams.isNotEmpty() && queryMeaningfulBigrams.count { bg -> normLbl.contains(bg) } >= 2)
            }
        } else 0
        val softStageProbs = com.example.newandroidapp.ai.UnifiedStageScoringHead.computeSoftStageProbabilities(
            report = report,
            query = query,
            isQuerySubmitted = isQuerySubmitted,
            inputNodesCount = inputNodes.size,
            inpAlreadyContainsQuery = alreadyTypedForRegime,
            matchingViewportResultCount = matchingVpCount
        )
        val primaryInpForScoring = inputNodes.maxByOrNull { BackwardChainingPlanner.scoreInputBoxCandidate(it) }
        val sortedViewportIds = mainViewportContentNodes.sortedBy { it.gridTapPoint.y }.map { it.nodeId }
        val explicitSearchForScoring = query.isNotBlank() || hasExplicitSearchOrInputIntent(userGoal) || hasExplicitSearchOrInputIntent(currentSubGoal)

        for (node in report.finalNodes) {
            val vpRank = sortedViewportIds.indexOf(node.nodeId)
            val feat16 = com.example.newandroidapp.ai.UnifiedStageScoringHead.extractNodeFeatures16D(
                node = node,
                report = report,
                userGoal = userGoal,
                currentSubGoal = currentSubGoal,
                query = query,
                followUp = followUp,
                regime = scoringRegime,
                primaryInputNode = primaryInpForScoring,
                viewportRankIndex = vpRank,
                episodicDelta = 0f,
                screenHash = screenHash,
                explicitSearchIntent = explicitSearchForScoring,
                residualProjection = residualProj
            )
            val breakdown = com.example.newandroidapp.ai.UnifiedStageScoringHead.scoreCandidateWithWeights(
                nodeId = node.nodeId,
                features = feat16,
                regime = scoringRegime,
                basePriorScore = (scoreMap[node.nodeId] ?: 0.2f).coerceIn(-1f, 1f),
                stageProbabilities = softStageProbs
            )
            val prevRaw = scoreMap[node.nodeId] ?: 0f
            val hasSemanticOrResidualSignal =
                feat16[0] > 0f || feat16[1] >= 0.35f || feat16[2] >= 0.35f
            val hasRegimeStructuralSignal = when (scoringRegime) {
                com.example.newandroidapp.ai.UnifiedStageScoringHead.ScoringRegime.STAGE_A_EXPAND_SEARCH ->
                    feat16[3] > 0f || feat16[4] > 0f || hasSemanticOrResidualSignal
                com.example.newandroidapp.ai.UnifiedStageScoringHead.ScoringRegime.STAGE_B_INPUT_TYPE ->
                    feat16[3] > 0f
                com.example.newandroidapp.ai.UnifiedStageScoringHead.ScoringRegime.STAGE_B_SUBMIT_QUERY ->
                    feat16[5] > 0f || (feat16[8] > 0f && hasSemanticOrResidualSignal)
                com.example.newandroidapp.ai.UnifiedStageScoringHead.ScoringRegime.STAGE_C_SELECT_RESULT ->
                    feat16[8] > 0f && (hasSemanticOrResidualSignal || feat16[9] > 0f || feat16[10] > 0f)
                else ->
                    feat16[0] > 0f || feat16[1] >= 0.45f
            }
            // 將統一權重矩陣 Logit（含軟性階段機率閘門期望值）與現有分數平滑融合（保留安全硬遮罩 < -100f，並防止無關節點因位置/面積先驗被誤推升超過 0.22 門檻）
            scoreMap[node.nodeId] = when {
                prevRaw <= -100f || breakdown.totalLogitScore <= -100f ->
                    minOf(prevRaw, breakdown.totalLogitScore)
                breakdown.totalLogitScore <= -15f && !hasRegimeStructuralSignal ->
                    if (prevRaw >= 15f) (prevRaw * 0.65f) + (breakdown.totalLogitScore * 0.35f)
                    else minOf(prevRaw, breakdown.totalLogitScore * 0.25f)
                prevRaw >= 1.0f || (hasRegimeStructuralSignal && breakdown.totalLogitScore >= 18f) ->
                    (prevRaw * 0.65f) + (breakdown.totalLogitScore * 0.35f)
                else ->
                    prevRaw.coerceIn(0.05f, 0.85f)
            }
        }

        var highestRawScore = -999f
        val rawMapped = report.finalNodes
            .map { node ->
                val raw = scoreMap[node.nodeId] ?: 0f
                if (raw > highestRawScore) highestRawScore = raw
                val normalized = when {
                    raw >= 50f -> (0.88f + ((raw - 50f) / 200f)).coerceIn(0.88f, 0.99f)
                    raw >= 15f -> (0.65f + ((raw - 15f) / 160f)).coerceIn(0.65f, 0.87f)
                    raw >= 1.0f -> (0.22f + ((raw - 1.0f) / 35f)).coerceIn(0.22f, 0.64f)
                    raw < 0f -> (0.05f + (raw / 200f)).coerceIn(0.01f, 0.12f)
                    else -> raw.coerceIn(0.05f, 0.85f)
                }
                node to normalized
            }
        val rankedAdjusted = DeterminismHardeningEngine.sortScoredCandidatesCanonically(
            candidates = rawMapped,
            bucketWidth = DeterminismHardeningEngine.SCORE_BUCKET_WIDTH
        )

        if (highestRawScore == -999f) highestRawScore = 0f

        return InAppPlanEvaluation(
            stage = activeStage,
            screenHash = screenHash,
            extractedQuery = query,
            extractedFollowUp = followUp,
            blockerCandidate = null,
            overrideActionType = overrideAction,
            overrideTargetNode = overrideNode,
            overrideInputText = overrideInputText,
            overrideConfidence = overrideConf,
            bestRawScore = highestRawScore,
            stageReasoningNote = stageNote,
            episodicMemoryNote = episodicHitTag,
            adjustedCandidates = rankedAdjusted
        )
    }

    /**
     * 判斷搜尋送出後是否需要繼續執行第 3 階段「點選搜尋結果項 (TAP_TARGET)」：
     * - 若屬於純資訊檢索或純搜尋（例如「打開 Chrome 搜尋 蔣介石」、「去 YouTube 查周杰倫」、「去地圖查台北101」）且未指定後續點擊/播放動作 (`followUp.isBlank()`)，
     *   則終極目標在「搜尋結果頁成功顯示」時即已 100% 達成，不需要多餘的 `TAP_TARGET` 子目標！
     * - 只有當使用者明確帶有播放/觀看 (`TerminalVerb.PLAY`)、購買 (`TerminalVerb.BUY`) 動詞，
     *   或明確指定了 `followUp`（如「搜尋 X 並點第一個」、「去 YouTube 聽晴天」），才需要 `TAP_TARGET` 點開目標結果。
     */
    fun requiresResultSelectionAfterSearch(
        rawGoal: String,
        detectedAppOrPackage: String? = null,
        followUp: String = ""
    ): Boolean {
        if (followUp.isNotBlank()) return true
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec(rawGoal)
        if (spec.followUpAction.isNotBlank()) return true
        if (spec.terminalVerb == TerminalVerb.PLAY || spec.terminalVerb == TerminalVerb.BUY) {
            return true
        }
        val goalLower = rawGoal.lowercase()
        val explicitResultActionTokens = listOf(
            "播放", "聽歌", "放歌", "點歌", "買茶歌", "購買", "下單", "點第一", "點開", "點進", "進網站", "進官網", "點網站", "進入網站", "進入官網", "看官網", "看網站", "打開來聽", "觀看", "收聽", "看影片"
        )
        if (explicitResultActionTokens.any { goalLower.contains(it) }) {
            return true
        }
        val resProj = com.example.newandroidapp.ai.UnifiedStageScoringHead.computeResidualIntentProjection(
            fullGoal = rawGoal,
            completedQuery = spec.targetEntity,
            explicitFollowUp = spec.followUpAction,
            appHint = spec.appHint.orEmpty()
        )
        if (resProj.hasWebsiteOrLinkIntent || resProj.hasPlayOrWatchIntent || resProj.hasFirstItemOrOpenIntent) {
            return true
        }
        val browserOrMapTokens = listOf(
            "chrome", "瀏覽器", "google", "地圖", "maps", "com.android.chrome", "com.google.android.apps.maps"
        )
        val appLower = "${detectedAppOrPackage.orEmpty()} ${spec.appHint.orEmpty()} ${rawGoal}".lowercase()
        if (browserOrMapTokens.any { appLower.contains(it) }) {
            return false
        }
        // 任何純「搜尋/查詢/查/找/誰是/什麼是」指令（即使在 YouTube / Spotify / 蝦皮），未要求播放或點開時，送出搜尋顯示結果即完成
        if (spec.terminalVerb == TerminalVerb.SEARCH_FIND) {
            return false
        }
        return true
    }

    /**
     * 將使用者多步指令拆解為具備完整 App 內操作鏈的結構化子目標清單
     */
    fun buildCooperativeInAppSubGoals(
        rawGoal: String,
        detectedAppName: String?,
        highRiskKeywords: List<String>,
        @Suppress("UNUSED_PARAMETER") fallbackExtractor: (String) -> String?,
        toggleStateHint: Boolean?
    ): List<CooperativeSubGoal>? {
        val clean = rawGoal.trim()
        if (clean.isEmpty()) return null

        val (query, followUp) = extractSearchQueryAndFollowUp(clean)
        if (query.isBlank()) return null

        val isRisk = highRiskKeywords.any { clean.lowercase().contains(it) }
        val steps = ArrayList<CooperativeSubGoal>(4)

        val effectiveApp = detectedAppName ?: BackwardChainingPlanner.extractTerminalGoalSpec(clean).appHint
        if (!effectiveApp.isNullOrBlank()) {
            steps.add(
                CooperativeSubGoal(
                    stepIndex = 1,
                    totalSteps = 0,
                    intentCategory = "LAUNCH_APP",
                    targetAppHint = effectiveApp,
                    targetSemanticPhrase = "啟動目標 App：$effectiveApp",
                    isHighRiskHint = false
                )
            )
        }

        steps.add(
            CooperativeSubGoal(
                stepIndex = steps.size + 1,
                totalSteps = 0,
                intentCategory = "INPUT_SEARCH",
                targetAppHint = null,
                targetSemanticPhrase = "搜尋 $query",
                inputPayload = query,
                desiredToggleState = toggleStateHint,
                isHighRiskHint = false
            )
        )

        if (requiresResultSelectionAfterSearch(clean, effectiveApp, followUp)) {
            val stageCTargetPhrase = if (followUp.isNotBlank()) {
                "$query $followUp"
            } else {
                query
            }
            steps.add(
                CooperativeSubGoal(
                    stepIndex = steps.size + 1,
                    totalSteps = 0,
                    intentCategory = "TAP_TARGET",
                    targetAppHint = null,
                    targetSemanticPhrase = stageCTargetPhrase,
                    inputPayload = null,
                    desiredToggleState = toggleStateHint,
                    isHighRiskHint = isRisk
                )
            )
        }

        val total = steps.size
        return steps.mapIndexed { idx, item ->
            item.copy(stepIndex = idx + 1, totalSteps = total)
        }
    }

    /**
     * 計算當前畫面的快速結構雜湊，用於工作記憶防重複點擊比對（先執行確定性雜訊隔離與 10px 空間固定排序）
     */
    fun computeQuickScreenHash(report: VisionPipelineReport): String {
        val cleanReport = DeterminismHardeningEngine.sanitizeVisionReport(
            report = report,
            expectedTargetPackage = InAppWorkingMemory.expectedTargetPackage
        )
        var h = 1469598103934665603L
        for (node in cleanReport.finalNodes.take(16)) {
            val gridY10 = DeterminismHardeningEngine.roundTo10PxGrid(node.finalTapPx.y)
            val gridX10 = DeterminismHardeningEngine.roundTo10PxGrid(node.finalTapPx.x)
            val sig = "${node.shortTypeTag}:${node.label.take(12)}@${gridX10},${gridY10}"
            for (c in sig) {
                h = (h xor c.code.toLong()) * 1099511628211L
            }
        }
        return "${cleanReport.finalNodes.size}n#${java.lang.Long.toHexString(h).takeLast(6)}"
    }
}
