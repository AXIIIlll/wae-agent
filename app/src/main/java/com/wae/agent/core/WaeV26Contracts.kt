package com.wae.agent.core

import org.json.JSONObject

/**
 * WAE V2.6 核心資料結構與工程契約定義
 */
enum class RunMode {
    /** 測試與校準模式：禁用三級自動恢復，「遇到不知道或有歧義就原地暫停」，並落盤完整診斷快照 */
    TEST,
    /** 生產模式：啟用三級有界自動恢復與 ASK_USER 主動澄清對話框 */
    PRODUCTION
}

enum class AmbiguityPolicy {
    /** 多個高分候選接近時，彈出 Top-3 選項卡請用戶一鍵澄清（購物/外賣/下單/轉帳場景預設） */
    ASK_USER,
    /** 列表瀏覽且未指定特定條件時，按視覺順序優先選第一個 */
    PREFER_FIRST,
    /** 選取置信度最高者自動執行 */
    AUTO_RESOLVE
}

enum class ActionKind {
    LAUNCH_APP,
    TAP,
    TYPE,
    SCROLL_DOWN,
    WAIT,
    BACK,
    DEEP_LINK,

    // ── 基礎動作補齊（M5 前置）：涵蓋使用者最常用的操作 ──
    /** 向上滾動（列表回退/回到頂部） */
    SCROLL_UP,
    /** 水平滑動（Tab / Carousel / 左滑返回） */
    SWIPE_HORIZONTAL,
    /** 長按（多選、拖曳、上下文菜單） */
    LONG_PRESS,
    /** 送出目前輸入（IME action / Enter / 建議列） */
    SUBMIT,
    /** 清除輸入框內容（用 ACTION_SET_TEXT 而非退格） */
    CLEAR_TEXT,
    /** 整段替換輸入內容（保留語意，值由參數提供） */
    REPLACE_TEXT,

    /**
     * 調整數值（亮度 / 音量 / 字體大小 / 任何滑桿或數值選擇器）。
     *
     * 與 TAP 的差別：TAP 只會「按一下」，而這個動作要知道**方向與幅度**，
     * 並在派發前由元件能力圖譜確認「這個元件真的可被設定數值」。
     */
    ADJUST_VALUE
}

enum class CandidateSource {
    A11Y_TREE,
    NANODET_ROI,
    HYBRID_WELDED
}

enum class PagePosture {
    LIST_FEED,
    FORM_INPUT,
    MODAL_DIALOG,
    BOTTOM_SHEET,
    DETAIL_PAGE,
    NAV_HOME,
    SEARCH_RESULTS,
    WATERFALL_GRID,
    EMPTY_OR_UNKNOWN
}

enum class ComponentLibraryType {
    MATERIAL,
    CUPERTINO,
    FLUTTER_OR_CUSTOM,
    ANDROID_NATIVE
}

enum class CausalAttributionState {
    /** 2 層父容器子樹改變 / 目標輸入框 IME 升起 / ReplacementRate > 0.60 */
    VALID_CAUSAL,
    /** 跨容器局部更新（如點加入購物車後頂欄角標 +1） */
    UNCERTAIN_CROSS_CONTAINER,
    /** 零變化或僅背景輪播雜訊 */
    BACKGROUND_NOISE
}

enum class DecisionOutcomeType {
    CLEAR_WIN,
    AMBIGUOUS,
    REJECT,
    WAIT,
    ESCALATE
}

/**
 * 雙層頁面姿態與環境非平穩度狀態
 */
data class DualPostureState(
    val primaryPosture: PagePosture,
    val overlayPosture: PagePosture = PagePosture.EMPTY_OR_UNKNOWN,
    val componentLib: ComponentLibraryType = ComponentLibraryType.ANDROID_NATIVE,
    val dynamicScore: Float = 0f
) {
    val effectivePosture: PagePosture
        get() = if (overlayPosture != PagePosture.EMPTY_OR_UNKNOWN) overlayPosture else primaryPosture

    val isHighDynamicStream: Boolean
        get() = dynamicScore >= 0.75f
}

/**
 * 目標的「具體程度」。
 *  - INSTANCE：使用者指定了可辨識的標籤（例如「晴天」「台北101」），必須精確匹配
 *  - CLASS：使用者只給了類別（例如「隨機點一封信」「任何一個影片」「第一個商品」），
 *           此時不該因為找不到同名文字而 REJECT，而應在「符合類別的候選」中擇一執行。
 *
 * 這是通用機制：判斷依據是**語言層的量詞/不定代詞**，不涉及任何特定 App 的資料。
 */
enum class TargetSpecificity { INSTANCE, CLASS }

/**
 * 結構化局部目標（含 Type 動作之 inputPayload 與歧義策略）
 */
data class LocalGoal(
    val stepIdx: Int,
    val goalId: String,
    val goalText: String,
    val canonicalIntentTag: String,
    val actionKind: ActionKind,
    val inputPayload: String = "",
    val isSensitivePayload: Boolean = false,
    val ambiguityPolicy: AmbiguityPolicy = AmbiguityPolicy.AUTO_RESOLVE,
    val targetPackageHint: String = "",
    val targetSpecificity: TargetSpecificity = TargetSpecificity.INSTANCE,
    /**
     * S28：這一步是否**依賴前一步**（序列指令，例如「關閉飛航再開啟藍牙」）。
     * 前一步失敗時，依賴的後續步驟必須一併停止，不能硬做。
     */
    val dependsOnPrevious: Boolean = false
) {
    fun redactedPayload(): String = if (isSensitivePayload) "***" else inputPayload
}

/**
 * 長任務步驟級檢查點快照（支援熔斷時回退至最近穩定狀態）
 */
data class StepCheckpoint(
    val completedStepIdx: Int,
    val goalId: String,
    val packageName: String,
    val activeWindowId: Int,
    val pageSpineSigs: LongArray,
    val timestampMs: Long
)

/**
 * 單一可選候選項目
 */
data class CandidateOption(
    val markId: Int,
    val nodeStableSig: Long,
    val source: CandidateSource,
    val detectorConfidence: Float,
    val grid: IntArray, // [left, top, right, bottom]
    val coreText: String,
    val weldedDescription: String,
    val hybridSimScore: Float,
    val isEnabled: Boolean = true,
    val isClickable: Boolean = true,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val parentContainerType: String = "CONTAINER",
    val gridZone: String = "CENTER",
    val roleTag: String = "element",
    val viewId: String = "",
    val className: String = "",
    /** 是否為密碼輸入欄位（供安全自動機硬阻斷） */
    val isPassword: Boolean = false,

    // ── 元件能力圖譜（元件家族 + 能力，來自 ui_widget_capability_ontology.tsv）──
    /** 元件家族 id（例如 seek_bar / switch / preference_row），未知則為空字串 */
    val widgetFamily: String = "",
    /** 該元件被判定具備的操作能力 */
    val capabilities: Set<com.wae.agent.core.component.Capability> = emptySet(),
    /** 是否有數值範圍（RangeInfo） */
    val hasRange: Boolean = false,
    /** 融合後判定「真的可以被設定數值」（排除唯讀進度條） */
    val isAdjustable: Boolean = false,
    val rangeMin: Float = 0f,
    val rangeMax: Float = 0f,
    val rangeCurrent: Float = 0f,
    val rangeIsPercent: Boolean = false,
    /** 元件是否可勾選/切換（開關、checkbox、radio） */
    val isCheckable: Boolean = false,
    /** 目前是否為「開」的狀態 */
    val isChecked: Boolean = false
) {
    /** 這個元件是否可被「設定數值」（滑桿、數值選擇器等） */
    val canAdjustValue: Boolean
        get() = isAdjustable

    /** 只會顯示、不該互動（純文字/圖片/進度條） */
    val isReadOnlyOnly: Boolean
        get() = capabilities.isNotEmpty() &&
            capabilities.all { it == com.wae.agent.core.component.Capability.READ_ONLY }

    /** 是否可切換（開關/勾選） */
    val canToggle: Boolean
        get() = capabilities.contains(com.wae.agent.core.component.Capability.TOGGLE)

    /**
     * 是否具備「元件自身」的語意證據。
     *
     * 注意：這**不是**「有沒有文字」。很多 App 的按鈕只有圖標（Material 的 icon button
     * 通常仍有 contentDescription；真的沒有時，圖示語意資料庫會給出 roleTag）。
     * 判定的重點是：這個元素自己說得出它是什麼，而不是只靠旁邊的文字推測。
     * 只靠鄰近文字焊接來的語意（welded-only）不足以支撐「主動導覽」這種高風險決策。
     */
    val hasOwnSemanticEvidence: Boolean
        get() = coreText.isNotBlank() ||
            (roleTag.isNotBlank() && roleTag != "element" && roleTag != "ui_element")

    /**
     * 是否為「搜尋/輸入入口」控件。
     * 注意：Google Maps 的 search_omnibox_text_box 雖然 class 是 EditText，但其 AccessibilityNodeInfo.isEditable
     * 會回報 false（點擊後才展開真正的輸入頁），因此不能只用 isEditable 判斷。
     */
    val isSearchAffordance: Boolean
        get() = isEditable ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoComplete", ignoreCase = true) ||
            className.contains("SearchView", ignoreCase = true) ||
            viewId.contains("search", ignoreCase = true) ||
            viewId.contains("omnibox", ignoreCase = true) ||
            viewId.contains("query", ignoreCase = true) ||
            coreText.contains("搜尋") || coreText.contains("搜索") ||
            coreText.contains("查詢") || coreText.contains("查询") ||
            coreText.contains("search", ignoreCase = true) ||
            coreText.contains("find", ignoreCase = true)

    /**
     * 是否為「輸入框類」控件（class/id 特徵或 isEditable）。
     * 注意 Google Maps 的 search_omnibox_text_box 會回報 isEditable=false，故不能只看 isEditable。
     */
    val isTextInputLike: Boolean
        get() = isEditable ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoComplete", ignoreCase = true) ||
            className.contains("SearchView", ignoreCase = true)

    /**
     * 控件 id 本身是否帶搜尋語意（最強證據，例如 search_omnibox_text_box / et_search_kw）。
     * 抖音、地圖這類 App 的搜尋框常常 class 是 View、isEditable=false，只有 id 可靠。
     */
    val hasSearchIdHint: Boolean
        get() = viewId.contains("search", ignoreCase = true) ||
            viewId.contains("omnibox", ignoreCase = true) ||
            viewId.contains("query", ignoreCase = true)

    /**
     * 這個輸入框是否帶有「搜尋」語意（id/文字/描述線索）。
     * 用來區分「搜尋框」與畫面上其他輸入框（例如抖音的留言輸入框），
     * 否則兩者同分會讓 L3 誤判為歧義或直接選錯。
     */
    val hasSearchHint: Boolean
        get() = viewId.contains("search", ignoreCase = true) ||
            viewId.contains("omnibox", ignoreCase = true) ||
            viewId.contains("query", ignoreCase = true) ||
            coreText.contains("搜尋") || coreText.contains("搜索") ||
            coreText.contains("查詢") || coreText.contains("查询") ||
            coreText.contains("search", ignoreCase = true) ||
            weldedDescription.contains("搜尋") || weldedDescription.contains("搜索")

    /**
     * 是否為底部導航列頁籤（如 Google Maps 的「搜索」頁籤 explore_tab_strip_button）。
     * TYPE 目標必須排除此類控件，否則會誤把與搜尋同名的頁籤當成輸入框反覆點擊。
     */
    val isBottomNavigationTab: Boolean
        get() = parentContainerType == "BOTTOM_NAV" ||
            viewId.contains("tab_strip_button", ignoreCase = true) ||
            viewId.contains("navigation_bar_item", ignoreCase = true) ||
            viewId.contains("bottom_navigation", ignoreCase = true) ||
            viewId.contains("bottom_nav", ignoreCase = true)

    fun centerX(): Int = (grid[0] + grid[2]) / 2
    fun centerY(): Int = (grid[1] + grid[3]) / 2
    fun width(): Int = (grid[2] - grid[0]).coerceAtLeast(0)
    fun height(): Int = (grid[3] - grid[1]).coerceAtLeast(0)
    fun area(): Int = width() * height()
}

/**
 * 強制共享之不可變選項集（保證 L1.5 與 L3 索引絕對一致，並以 windowIdx 區分首輪與重審）
 */
data class ImmutableOptionSet(
    val frameId: Long,
    val windowIdx: Int, // 0 = 首輪 (Top-1~10), 1 = Level-1 解凍重審 (Top-11~18)
    val activeWindowId: Int,
    val packageName: String,
    val postureState: DualPostureState,
    val pageSpineSigs: LongArray, // 256B Head-16 + Tail-16 節點 64-bit 簽名集合
    val pageSpineHash: Long,
    val rejectedContextPrompts: List<String>, // 僅作只讀上下文，禁止被 CUA-S1 選中
    val selectableOptions: List<CandidateOption> // 嚴格 <= 10 個可選候選
)

/**
 * CUA-S1 自適應三條件門檻與歧義校準配置（支援從 cua_calibration.json 熱加載）
 */
data class CuaCalibrationConfig(
    val alpha0: Float = 0.65f,
    val kAlpha: Float = 0.015f,
    val alphaMin: Float = 0.45f,
    val beta0: Float = 0.18f,
    val kBeta: Float = 0.008f,
    val betaMin: Float = 0.08f,
    val simFloor: Float = 0.35f,
    val ambiguityMarginThreshold: Float = 0.10f,
    val ambiguitySecondProbFloor: Float = 0.25f
) {
    fun thetaAbs(optionCount: Int): Float {
        val excess = (optionCount - 3).coerceAtLeast(0)
        return (alpha0 - kAlpha * excess).coerceAtLeast(alphaMin)
    }

    fun thetaMargin(optionCount: Int): Float {
        val excess = (optionCount - 3).coerceAtLeast(0)
        return (beta0 - kBeta * excess).coerceAtLeast(betaMin)
    }

    companion object {
        fun fromJsonString(jsonStr: String): CuaCalibrationConfig {
            return try {
                val obj = JSONObject(jsonStr)
                CuaCalibrationConfig(
                    alpha0 = obj.optDouble("alpha0", 0.65).toFloat(),
                    kAlpha = obj.optDouble("kAlpha", 0.015).toFloat(),
                    alphaMin = obj.optDouble("alphaMin", 0.45).toFloat(),
                    beta0 = obj.optDouble("beta0", 0.18).toFloat(),
                    kBeta = obj.optDouble("kBeta", 0.008).toFloat(),
                    betaMin = obj.optDouble("betaMin", 0.08).toFloat(),
                    simFloor = obj.optDouble("simFloor", 0.35).toFloat(),
                    ambiguityMarginThreshold = obj.optDouble("ambiguityMarginThreshold", 0.10).toFloat(),
                    ambiguitySecondProbFloor = obj.optDouble("ambiguitySecondProbFloor", 0.25).toFloat()
                )
            } catch (_: Throwable) {
                CuaCalibrationConfig()
            }
        }
    }
}

/**
 * L3 CUA-S1 決策輸出結果
 */
data class CuaDecisionResult(
    val frameId: Long,
    val windowIdx: Int,
    val outcomeType: DecisionOutcomeType,
    val selectedOption: CandidateOption?,
    val top1Prob: Float,
    val top2Prob: Float,
    val margin: Float,
    val top3AmbiguousCandidates: List<CandidateOption> = emptyList(),
    val reason: String = "",
    /**
     * 這一步要用的動作（null = 沿用目標的動作）。
     * 用於「目標獲取階梯」：目標是輸入，但畫面上只有入口 → 這一步先點開，下一輪再打字。
     */
    val overrideActionKind: ActionKind? = null,
    /** S27：目標狀態已經成立（例如開關本來就是關的）→ 不需派發任何動作 */
    val alreadySatisfied: Boolean = false,
    /** S27：這一步只是「導覽到目標所在頁」，派發成功後要繼續下一輪（不算目標完成） */
    val continueAfterDispatch: Boolean = false
)

/**
 * 雙層恢復計數器（局部 LocalGoal 獨立計數 + 全局任務熔斷計數）
 */
data class LocalRecoveryCounter(
    var l1Count: Int = 0,
    var l2DismissCount: Int = 0,
    var l2ScrollCount: Int = 0
) {
    fun reset() {
        l1Count = 0
        l2DismissCount = 0
        l2ScrollCount = 0
    }
}

data class GlobalTaskCounter(
    var humanInterventionCount: Int = 0
) {
    val isCircuitBreakerTripped: Boolean
        get() = humanInterventionCount >= 3

    fun reset() {
        humanInterventionCount = 0
    }
}
