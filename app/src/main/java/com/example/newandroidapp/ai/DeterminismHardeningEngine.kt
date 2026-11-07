package com.example.newandroidapp.ai

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppOperationStage
import com.example.newandroidapp.vision.RawVisionNode
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 本地端 AI 決策管線「確定性加固引擎」（DeterminismHardeningEngine）
 *
 * 專責解決同一畫面重複執行三次卻出現「兩次正確、一次錯誤」的隨機抖動問題，實作四大確定性加固支柱：
 *
 * 1. 【鎖死模型推論為貪婪解碼 (Zero-Temperature Greedy Decoding & Fixed Seeds)】：
 *    - 強制全域 `GREEDY_TEMPERATURE = 0.0f`、`GREEDY_TOP_K = 1`、`RANDOM_SAMPLING_ENABLED = false`，
 *      並固定所有演算法隨機種子 `DETERMINISTIC_SEED = 42L`。
 *    - 移除基於記憶體位址 (`System.identityHashCode`) 的浮動節點 ID，改用結構與 10px 空間網格雜湊。
 *
 * 2. 【候選節點代號 (A~H) 的空間固定排序 (Canonical Spatial Sorting)】：
 *    - 將所有螢幕座標四捨五入至 `10px` 網格 (`roundTo10PxGrid`) 以吸收微小渲染或捲動偏移（±4px）。
 *    - 在同分或相近分數區間（`SCORE_BUCKET_WIDTH = 0.02f`）內，強制依據「先依 Y 座標由上至下、再依 X 座標由左至右」
 *      進行確定性排序，確保同一畫面下的按鈕在每次掃描時永遠對應到完全相同的 `#shortMarkId` 與 `A~H` 代號。
 *
 * 3. 【Fast-Path 雙重防搶跑鎖 (Confidence + Margin + Attribute Gate)】：
 *    - 快速通道觸發條件必須同時滿足：
 *      (a) `top1Score >= FAST_PATH_CONFIDENCE_THRESHOLD (0.85f)`
 *      (b) `top1Score - top2Score >= FAST_PATH_MARGIN_THRESHOLD (0.15f)`
 *      (c) 節點元件屬性 (`shortTypeTag` / 可互動性) 與當前子目標動作嚴格相符（例如 `INPUT` 動作僅限可編輯 `INP` 節點）。
 *    - 任一條件不滿足即嚴禁搶跑，強制交由完整三腦精排管線決策。
 *
 * 4. 【動態背景雜訊隔離 (Dynamic Background Noise Stripping)】：
 *    - 在建立畫面特徵與計算畫面雜湊前，強制剔除系統狀態列、導覽列、非當前目標 App 包名（如系統懸浮窗、通知欄、輸入法懸浮條）
 *      以及動態變化的時間（如 `12:34`、`下午 11:48`）、電量（如 `98%`）與網速文字節點，確保重複測試輸入給 AI 的上下文 100% 一致。
 */
object DeterminismHardeningEngine {

    // =========================================================================
    // 支柱 1：零溫度貪婪解碼與固定種子常數 (Zero-Temperature Greedy Decoding)
    // =========================================================================
    const val GREEDY_TEMPERATURE: Float = 0.0f
    const val GREEDY_TOP_K: Int = 1
    const val RANDOM_SAMPLING_ENABLED: Boolean = false
    const val DETERMINISTIC_SEED: Long = 42L

    /**
     * 同分或相近分數區間寬度（0.02f = 2% 分數帶）：
     * 在此區間內的候選節點視為同級競爭，強制以 10px 空間座標（先上後下、先左後右）決定先後順序。
     */
    const val SCORE_BUCKET_WIDTH: Float = 0.02f

    /**
     * 空間座標對齊網格大小（10px）
     */
    const val SPATIAL_GRID_PX: Int = 10

    // =========================================================================
    // 支柱 3：Fast-Path 雙重防搶跑鎖閾值 (Confidence + Margin Gate)
    // =========================================================================
    const val FAST_PATH_CONFIDENCE_THRESHOLD: Float = 0.85f
    const val FAST_PATH_MARGIN_THRESHOLD: Float = 0.15f

    data class FastPathGateEvaluation(
        val allowed: Boolean,
        val top1Score: Float,
        val top2Score: Float,
        val scoreMargin: Float,
        val confidencePassed: Boolean,
        val marginPassed: Boolean,
        val attributeCompatible: Boolean,
        val blockReason: String
    )

    // =========================================================================
    // 支柱 4：動態背景雜訊正則與系統包名黑名單 (Noise Stripping)
    // =========================================================================

    /**
     * 純時間文字節點正則：匹配 "12:34"、"09:05:22"、"上午 11:48"、"下午3:25"、"11:48 AM"、"週三 12:34" 等
     */
    private val PURE_CLOCK_TIME_REGEX = Regex(
        """^\s*(?:(?:週|星期)[一二三四五六日天]\s*)?(?:上午|下午|凌晨|清晨|中午|傍晚|晚上|AM|PM|am|pm)?\s*\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|am|pm)?\s*$"""
    )

    /**
     * 純電量百分比或充電狀態節點正則：匹配 "98%"、"100%"、"電量 85%"、"充電中 92%"、"85% 充電中"、"Battery 90%" 等
     */
    private val PURE_BATTERY_STATUS_REGEX = Regex(
        """^\s*(?:電量|電池|電池電量|充電中|快速充電|閃充中|無線充電|已充飽|Battery|Charging)?\s*\d{1,3}\s*%\s*(?:電量|充電中|已充飽)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 純動態網速與行動網路狀態列徽章正則：匹配 "12.4 KB/s"、"1.2 MB/s"、"0.0 B/s"、"5G"、"4G+"、"VoLTE" 等
     */
    private val PURE_NETWORK_STATUS_REGEX = Regex(
        """^\s*(?:\d+(?:\.\d+)?\s*[KMGT]?B/s|5G\+|5G|4G\+|4G|LTE\+|LTE|VoLTE|VoWiFi|HD\s*通話)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 嵌入於複合容器標籤首尾的動態時鐘/電量/網速雜訊 Token（用於清理頂部容器誤合併的狀態列文字）
     */
    private val EMBEDDED_DYNAMIC_NOISE_TOKEN_REGEX = Regex(
        """(?:\b\d+(?:\.\d+)?\s*[KMGT]?B/s\b|(?<=^|\s)(?:上午|下午|AM|PM)?\s*\d{1,2}:\d{2}(?::\d{2})?(?=\s|$)|(?<=^|\s)\d{1,3}%(?=\s|$))""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 系統狀態列與導覽列專屬 Resource ID 關鍵字
     */
    private val SYSTEM_BAR_RESOURCE_ID_HINTS = listOf(
        "status_bar",
        "statusbar",
        "system_icon_area",
        "notification_icon_area",
        "clock",
        "battery",
        "battery_percentage",
        "network_speed",
        "carrier_label",
        "navigationbarbackground",
        "navigation_bar",
        "nav_bar",
        "ends_group",
        "ime_switcher"
    )

    /**
     * 永遠視為背景/懸浮雜訊的系統或懸浮窗包名前綴與完整包名（同時對齊正式產品包名 com.localai.visionassistant）
     */
    private val ALWAYS_NOISE_PACKAGES = setOf(
        "com.android.systemui",
        "com.localai.visionassistant",
        "com.example.newandroidapp",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.app.clipboardedge",
        "com.samsung.android.app.taskedge",
        "com.samsung.android.app.appsedge",
        "com.samsung.android.app.aodservice",
        "com.miui.securitycenter",
        "com.miui.CleanMaster",
        "com.miui.touchassistant",
        "com.miui.freeform",
        "com.coloros.floatassistant",
        "com.coloros.smartsidebar",
        "com.oplus.floatassistant",
        "com.oplus.smartsidebar",
        "com.vivo.smartmultiwindow",
        "com.vivo.floatingball",
        "com.huawei.android.FloatTasks",
        "com.asus.gamewidget"
    )

    private val SYSTEM_FLOATING_LABEL_NOISE_HINTS = listOf(
        "側螢幕面板",
        "側邊欄",
        "智慧側邊欄",
        "懸浮球",
        "懸浮選單",
        "輔助觸控",
        "遊戲工具箱",
        "edge panel",
        "floating ball",
        "quick ball",
        "assistive ball"
    )

    /**
     * 允許跨包名保留的系統權限或選擇器對話框包名（避免誤刪合法權限彈窗）
     */
    private val ALLOWED_SYSTEM_DIALOG_PACKAGES = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
        "com.android.intentresolver",
        "android"
    )

    // =========================================================================
    // 支柱 1 & 2 實作：10px 網格對齊與確定性空間排序
    // =========================================================================

    /**
     * 將像素座標四捨五入至 10px 網格，吸收 ±4px 的微小排版或捲動抖動。
     */
    fun roundTo10PxGrid(px: Int): Int {
        if (px >= 0) {
            return ((px + (SPATIAL_GRID_PX / 2)) / SPATIAL_GRID_PX) * SPATIAL_GRID_PX
        }
        return -(((-px + (SPATIAL_GRID_PX / 2)) / SPATIAL_GRID_PX) * SPATIAL_GRID_PX)
    }

    fun roundPointTo10PxGrid(point: Point): Point {
        val rx = roundTo10PxGrid(point.x)
        val ry = roundTo10PxGrid(point.y)
        return Point(rx, ry).apply {
            x = rx
            y = ry
        }
    }

    fun roundRectTo10PxGrid(rect: Rect): Rect {
        val rl = roundTo10PxGrid(rect.left)
        val rt = roundTo10PxGrid(rect.top)
        val rr = roundTo10PxGrid(rect.right)
        val rb = roundTo10PxGrid(rect.bottom)
        return Rect(rl, rt, rr, rb).apply {
            left = rl
            top = rt
            right = rr
            bottom = rb
        }
    }

    /**
     * 將正規化座標 [0.0, 1.0] 對齊至 10px 網格
     */
    fun snapNormalizedCoordTo10PxGrid(normCoord: Float, screenDimPx: Int): Float {
        val safeDim = screenDimPx.coerceAtLeast(100)
        val rawPx = (normCoord.coerceIn(0f, 1f) * safeDim).roundToInt()
        val snappedPx = roundTo10PxGrid(rawPx).coerceIn(0, safeDim)
        return snappedPx.toFloat() / safeDim.toFloat()
    }

    /**
     * 產生 100% 確定性的結構與空間節點識別碼（取代 `System.identityHashCode`）。
     */
    fun computeDeterministicNodeCode(
        windowId: Int,
        depth: Int,
        childIndex: Int,
        className: String,
        viewIdResourceName: String,
        rawBounds: Rect,
        extraSalt: Int = 0
    ): Int {
        var h = 17
        h = 31 * h + windowId
        h = 31 * h + depth
        h = 31 * h + childIndex
        h = 31 * h + className.hashCode()
        h = 31 * h + viewIdResourceName.hashCode()
        h = 31 * h + roundTo10PxGrid(rawBounds.left)
        h = 31 * h + roundTo10PxGrid(rawBounds.top)
        h = 31 * h + roundTo10PxGrid(rawBounds.right)
        h = 31 * h + roundTo10PxGrid(rawBounds.bottom)
        if (extraSalt != 0) {
            h = 31 * h + extraSalt
        }
        return h and 0x7FFFFFFF
    }

    /**
     * 將浮點分數映射為離散分數桶（Score Bucket），在 `bucketWidth` (預設 0.02f) 內的微小分差視為同分區間。
     */
    fun quantizeScoreBucket(score: Float, bucketWidth: Float = SCORE_BUCKET_WIDTH): Int {
        if (score.isNaN() || score.isInfinite()) return 0
        val safeWidth = bucketWidth.coerceAtLeast(0.001f)
        return (score / safeWidth).roundToInt()
    }

    /**
     * 針對 `RawVisionNode` 的確定性空間排序比較器：
     * 先依 Y 座標由上至下（10px 網格），再依 X 座標由左至右（10px 網格）。
     */
    fun compareRawNodesCanonically(a: RawVisionNode, b: RawVisionNode): Int {
        val ay = roundTo10PxGrid((a.bounds.top + a.bounds.bottom) / 2)
        val by = roundTo10PxGrid((b.bounds.top + b.bounds.bottom) / 2)
        if (ay != by) return ay.compareTo(by)

        val ax = roundTo10PxGrid((a.bounds.left + a.bounds.right) / 2)
        val bx = roundTo10PxGrid((b.bounds.left + b.bounds.right) / 2)
        if (ax != bx) return ax.compareTo(bx)

        val aTop = roundTo10PxGrid(a.bounds.top)
        val bTop = roundTo10PxGrid(b.bounds.top)
        if (aTop != bTop) return aTop.compareTo(bTop)

        val aLeft = roundTo10PxGrid(a.bounds.left)
        val bLeft = roundTo10PxGrid(b.bounds.left)
        if (aLeft != bLeft) return aLeft.compareTo(bLeft)

        val clsCmp = a.className.compareTo(b.className)
        if (clsCmp != 0) return clsCmp

        return a.text.compareTo(b.text)
    }

    /**
     * 針對 `Stage2ConvergedNode` 的確定性空間排序比較器（Canonical Spatial Comparator）：
     * 1. 先依點擊中心 Y 座標（四捨五入至 10px 網格）由上至下
     * 2. 再依點擊中心 X 座標（四捨五入至 10px 網格）由左至右
     * 3. 再依可見邊界 Top / Left（10px 網格）
     * 4. 最後依元件類型標籤 (`shortTypeTag`) 與文字標籤 (`label`) 字典序
     */
    fun compareNodesCanonically(a: Stage2ConvergedNode, b: Stage2ConvergedNode): Int {
        val ay = roundTo10PxGrid(a.finalTapPx.y)
        val by = roundTo10PxGrid(b.finalTapPx.y)
        if (ay != by) return ay.compareTo(by)

        val ax = roundTo10PxGrid(a.finalTapPx.x)
        val bx = roundTo10PxGrid(b.finalTapPx.x)
        if (ax != bx) return ax.compareTo(bx)

        val aTop = roundTo10PxGrid(a.convergedBounds.top)
        val bTop = roundTo10PxGrid(b.convergedBounds.top)
        if (aTop != bTop) return aTop.compareTo(bTop)

        val aLeft = roundTo10PxGrid(a.convergedBounds.left)
        val bLeft = roundTo10PxGrid(b.convergedBounds.left)
        if (aLeft != bLeft) return aLeft.compareTo(bLeft)

        val typeCmp = a.shortTypeTag.compareTo(b.shortTypeTag)
        if (typeCmp != 0) return typeCmp

        return a.label.trim().compareTo(b.label.trim())
    }

    /**
     * 將未計分節點清單依 10px 空間網格（先上後下、先左後右）進行確定性排序。
     */
    fun sortUnscoredNodesCanonically(nodes: List<Stage2ConvergedNode>): List<Stage2ConvergedNode> {
        if (nodes.size <= 1) return nodes
        return nodes.sortedWith { a, b -> compareNodesCanonically(a, b) }
    }

    /**
     * 將計分候選節點清單在同分或相近分數區間（`bucketWidth`）內，
     * 強制依據「先依 Y 座標由上至下、再依 X 座標由左至右（10px 網格）」進行確定性排序。
     */
    fun sortScoredCandidatesCanonically(
        candidates: List<Pair<Stage2ConvergedNode, Float>>,
        bucketWidth: Float = SCORE_BUCKET_WIDTH
    ): List<Pair<Stage2ConvergedNode, Float>> {
        if (candidates.size <= 1) return candidates
        return candidates.sortedWith { a, b ->
            val bucketA = quantizeScoreBucket(a.second, bucketWidth)
            val bucketB = quantizeScoreBucket(b.second, bucketWidth)
            if (bucketA != bucketB) {
                bucketB.compareTo(bucketA) // 高分桶優先
            } else {
                compareNodesCanonically(a.first, b.first)
            }
        }
    }

    /**
     * 將收斂節點清單依 10px 空間網格固定排序後，重新賦予確定性的 `#shortMarkId` (1..N)，
     * 確保同一畫面的按鈕在每次掃描時永遠對應到完全相同的編號。
     */
    fun assignCanonicalMarkIds(nodes: List<Stage2ConvergedNode>): List<Stage2ConvergedNode> {
        if (nodes.isEmpty()) return emptyList()
        val sorted = sortUnscoredNodesCanonically(nodes)
        return sorted.mapIndexed { index, node ->
            val canonicalId = index + 1
            if (node.shortMarkId == canonicalId) {
                node
            } else {
                node.copy(shortMarkId = canonicalId)
            }
        }
    }

    /**
     * 將粗篩選出的 Top-K 候選節點映射為代號 `A~H` 前，強制依空間座標（先上後下、先左後右，10px 網格）
     * 或分數桶+空間座標進行固定化映射，消除小模型對順序的敏感度偏差。
     */
    fun sortTopKCandidatesForSlotMapping(
        topCandidates: List<Stage2ConvergedNode>,
        maxSlots: Int = 8
    ): List<Stage2ConvergedNode> {
        if (topCandidates.isEmpty()) return emptyList()
        return sortUnscoredNodesCanonically(topCandidates.take(maxSlots))
    }

    // =========================================================================
    // 支柱 3 實作：Fast-Path 雙重防搶跑鎖 (Confidence + Margin + Attribute Gate)
    // =========================================================================

    /**
     * 檢驗候選節點的元件屬性 (`shortTypeTag` / 標籤語義) 是否與當前子目標動作相符：
     * - 若當前階段或動作為 `INPUT_TEXT` / `TYPE_COMMIT_QUERY`，節點必須是可編輯輸入框 (`shortTypeTag == "INP"`)。
     * - 若當前階段為 `FOCUS_SEARCH_ENTRY`，節點必須是 `INP` 或具備搜尋語義的可點擊入口。
     * - 若當前階段為 `SELECT_RESULT_ITEM`，節點嚴禁為純輸入框 (`shortTypeTag != "INP"`) 且必須具有有效標題。
     * - 若當前子目標要求 `CHK` / 開關切換，節點必須為 `CHK` 或可點擊設定列 (`BTN`)。
     */
    fun isNodeAttributeCompatibleWithAction(
        node: Stage2ConvergedNode?,
        activeSubGoal: CooperativeSubGoal,
        inAppStage: InAppOperationStage,
        resolvedActionType: AiActionType? = null
    ): Boolean {
        if (node == null) return false
        if (InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) return false

        val expectsInputAction = resolvedActionType == AiActionType.INPUT_TEXT ||
            (inAppStage == InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT && resolvedActionType != AiActionType.TAP)

        if (expectsInputAction) {
            // INPUT 動作僅限可編輯節點 (INP)
            return node.shortTypeTag == "INP"
        }

        if (inAppStage == InAppOperationStage.STAGE_B_INPUT_AND_SUBMIT && resolvedActionType == AiActionType.TAP) {
            return node.shortTypeTag != "INP" && node.label.isNotBlank()
        }

        if (inAppStage == InAppOperationStage.STAGE_A_EXPAND_SEARCH) {
            if (node.shortTypeTag == "INP" || node.nanoDetCategory == "UI_SEARCH_BAR") return true
            val labelLower = node.label.trim().lowercase()
            val hasSearchHint = labelLower.contains("搜尋") ||
                labelLower.contains("搜索") ||
                labelLower.contains("查找") ||
                labelLower.contains("查詢") ||
                labelLower.contains("search")
            return (node.shortTypeTag == "BTN" || node.shortTypeTag == "TXT" || node.shortTypeTag == "ICO") && hasSearchHint
        }

        if (inAppStage == InAppOperationStage.STAGE_C_SELECT_RESULT) {
            if (node.shortTypeTag == "INP") return false
            return node.label.isNotBlank()
        }

        if (activeSubGoal.intentCategory == "TOGGLE_SWITCH" || activeSubGoal.desiredToggleState != null) {
            return node.shortTypeTag == "CHK" || node.shortTypeTag == "BTN"
        }

        if (activeSubGoal.intentCategory == "TAP_TARGET") {
            // 【畫面終審鐵律】當子目標為純點擊按鈕 (TAP_TARGET / c+B) 時，嚴禁選中可編輯輸入框 (INP)
            return node.shortTypeTag != "INP" && node.shortTypeTag in setOf("BTN", "CHK", "TXT", "ICO", "ROI")
        }

        return node.shortTypeTag in setOf("BTN", "CHK", "INP", "TXT", "ICO", "ROI")
    }

    /**
     * 執行 Fast-Path 雙重防搶跑鎖評估：
     * 1. `top1Score >= FAST_PATH_CONFIDENCE_THRESHOLD (0.85f)`
     * 2. `scoreMargin = top1Score - top2Score >= FAST_PATH_MARGIN_THRESHOLD (0.15f)`
     * 3. `isNodeAttributeCompatibleWithAction == true`
     */
    fun evaluateFastPathGate(
        scoredCandidates: List<Pair<Stage2ConvergedNode, Float>>,
        activeSubGoal: CooperativeSubGoal,
        inAppStage: InAppOperationStage,
        resolvedActionType: AiActionType? = null,
        totalScreenNodeCount: Int = scoredCandidates.size
    ): FastPathGateEvaluation {
        if (scoredCandidates.isEmpty()) {
            return FastPathGateEvaluation(
                allowed = false,
                top1Score = 0f,
                top2Score = 0f,
                scoreMargin = 0f,
                confidencePassed = false,
                marginPassed = false,
                attributeCompatible = false,
                blockReason = "NO_CANDIDATES"
            )
        }

        val top1Node = scoredCandidates[0].first
        val top1Score = scoredCandidates[0].second
        // 若粗篩漏斗只有 1 個節點但畫面上其實有多個節點，給予保守的基線第二名分數 0.72f，防止單節點假性搶跑
        val top2Score = when {
            scoredCandidates.size >= 2 -> scoredCandidates[1].second
            totalScreenNodeCount > 1 -> 0.72f
            else -> 0.0f
        }
        val margin = top1Score - top2Score

        val confidencePassed = top1Score >= FAST_PATH_CONFIDENCE_THRESHOLD
        val marginPassed = margin >= FAST_PATH_MARGIN_THRESHOLD
        val attributeCompatible = isNodeAttributeCompatibleWithAction(
            node = top1Node,
            activeSubGoal = activeSubGoal,
            inAppStage = inAppStage,
            resolvedActionType = resolvedActionType
        )

        val allowed = confidencePassed && marginPassed && attributeCompatible
        val blockReason = when {
            !confidencePassed -> "LOW_CONFIDENCE(${String.format("%.2f", top1Score)}<${FAST_PATH_CONFIDENCE_THRESHOLD})"
            !marginPassed -> "LOW_MARGIN(${String.format("%.2f", margin)}<${FAST_PATH_MARGIN_THRESHOLD})"
            !attributeCompatible -> "ATTRIBUTE_MISMATCH(role=${top1Node.shortTypeTag},intent=${activeSubGoal.intentCategory},stage=${inAppStage.name})"
            else -> "PASSED"
        }

        return FastPathGateEvaluation(
            allowed = allowed,
            top1Score = top1Score,
            top2Score = top2Score,
            scoreMargin = margin,
            confidencePassed = confidencePassed,
            marginPassed = marginPassed,
            attributeCompatible = attributeCompatible,
            blockReason = blockReason
        )
    }

    // =========================================================================
    // 支柱 4 實作：動態背景雜訊隔離 (Dynamic Background Noise Stripping)
    // =========================================================================

    /**
     * 判斷文字是否為純動態變化的時間、電量百分比或網速/狀態列雜訊。
     */
    fun isDynamicClockOrBatteryNoise(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return false
        return PURE_CLOCK_TIME_REGEX.matches(trimmed) ||
            PURE_BATTERY_STATUS_REGEX.matches(trimmed) ||
            PURE_NETWORK_STATUS_REGEX.matches(trimmed)
    }

    /**
     * 清理複合標籤中夾帶的動態時間、電量百分比或網速 Token，確保三次掃描的標籤文字完全一致。
     */
    fun sanitizeDynamicNoiseFromLabel(rawLabel: String): String {
        val trimmed = rawLabel.trim()
        if (trimmed.isEmpty()) return ""
        if (isDynamicClockOrBatteryNoise(trimmed)) return ""
        return trimmed
            .replace(EMBEDDED_DYNAMIC_NOISE_TOKEN_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * 判斷 Resource ID 是否屬於系統狀態列或導覽列元件。
     */
    fun isSystemStatusOrNavBarResourceId(viewIdResourceName: String): Boolean {
        if (viewIdResourceName.isBlank()) return false
        val lower = viewIdResourceName.lowercase()
        if (lower.startsWith("com.android.systemui:")) return true
        val shortId = lower.substringAfterLast('/')
        return SYSTEM_BAR_RESOURCE_ID_HINTS.any { hint ->
            shortId == hint || shortId.startsWith("${hint}_") || shortId.endsWith("_$hint")
        }
    }

    /**
     * 從原始節點集合中選取當前主前景應用程式包名（優先選取非系統權限彈窗且節點數量最多的非雜訊包名，
     * 防止小型子母畫面 PiP、懸浮窗或權限彈窗誤奪主包名而將底下真正的主 App 全數濾空）。
     */
    fun selectPrimaryForegroundPackage(nodes: List<RawVisionNode>): String {
        val counts = LinkedHashMap<String, Int>()
        for (n in nodes) {
            val pkg = n.packageName.trim()
            if (pkg.isEmpty() || isForeignNoisePackage(pkg)) continue
            if (pkg in ALLOWED_SYSTEM_DIALOG_PACKAGES) continue
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        if (counts.isNotEmpty()) {
            return counts.entries.maxByOrNull { it.value }?.key.orEmpty()
        }
        return nodes.firstOrNull {
            val pkg = it.packageName.trim()
            pkg.isNotEmpty() && !isForeignNoisePackage(pkg)
        }?.packageName?.trim().orEmpty()
    }

    /**
     * 判斷節點所屬包名是否為非當前目標 App 的背景/懸浮窗雜訊包名。
     * 注意：無論 `expectedTargetPackage` 是否殘留舊 App 包名，只要節點屬於當前真實前景 App (`foregroundAppPackage`)
     * 或目標 App (`expectedTargetPackage`)，一律不得視為外來雜訊。
     */
    fun isForeignNoisePackage(
        nodePackageName: String,
        foregroundAppPackage: String = "",
        expectedTargetPackage: String = ""
    ): Boolean {
        val pkg = nodePackageName.trim()
        if (pkg.isEmpty()) return false
        if (pkg in ALWAYS_NOISE_PACKAGES) return true
        if (pkg.contains(".inputmethod") || pkg.contains(".keyboard") || pkg.contains(".float")) {
            return true
        }

        val cleanForeground = foregroundAppPackage.trim()
        val cleanExpected = expectedTargetPackage.trim()
        if (cleanForeground.isNotEmpty() && pkg == cleanForeground) return false
        if (cleanExpected.isNotEmpty() && pkg == cleanExpected) return false

        val activeTarget = cleanExpected.ifEmpty { cleanForeground }
        if (activeTarget.isNotEmpty() && pkg != activeTarget) {
            if (pkg in ALLOWED_SYSTEM_DIALOG_PACKAGES) return false
            return true
        }
        return false
    }

    /**
     * 判斷原始無障礙節點 (`RawVisionNode`) 是否應在建立畫面特徵前被強制剔除。
     */
    fun shouldStripRawVisionNode(
        node: RawVisionNode,
        @Suppress("UNUSED_PARAMETER") screenHeightPx: Int,
        statusBarCeilingPx: Int,
        navBarFloorPx: Int,
        foregroundAppPackage: String = "",
        expectedTargetPackage: String = ""
    ): Boolean {
        // 1. 剔除非當前目標 App 包名（系統狀態列/懸浮窗/通知欄/輸入法）
        if (isForeignNoisePackage(node.packageName, foregroundAppPackage, expectedTargetPackage)) {
            return true
        }

        // 2. 剔除狀態列與導覽列 Resource ID
        if (isSystemStatusOrNavBarResourceId(node.viewIdResourceName)) {
            return true
        }

        // 3. 剔除幾何位置落在系統狀態列頂部或系統導覽列底部的非目標元件
        val bounds = node.bounds
        if (bounds.bottom <= statusBarCeilingPx || bounds.top >= navBarFloorPx) {
            return true
        }

        // 4. 剔除純動態時間 / 電量 / 網速文字節點
        val combinedText = listOf(node.text.trim(), node.contentDescription.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (combinedText.isNotEmpty() && isDynamicClockOrBatteryNoise(combinedText)) {
            // 若並非可編輯輸入框，直接作為動態雜訊剔除
            if (!node.isEditable) {
                return true
            }
        }

        return false
    }

    /**
     * 判斷收斂後節點 (`Stage2ConvergedNode`) 是否屬於動態背景雜訊而應被剔除。
     */
    fun shouldStripConvergedNode(
        node: Stage2ConvergedNode,
        screenHeightPx: Int,
        statusBarCeilingPx: Int = (screenHeightPx * 0.042f).toInt().coerceIn(56, 128),
        navBarFloorPx: Int = screenHeightPx - (screenHeightPx * 0.048f).toInt().coerceIn(64, 156),
        foregroundAppPackage: String = "",
        expectedTargetPackage: String = ""
    ): Boolean {
        // 1. 非當前目標 App 包名隔離
        if (node.packageName.isNotBlank() &&
            isForeignNoisePackage(node.packageName, foregroundAppPackage, expectedTargetPackage)
        ) {
            return true
        }

        // 2. 狀態列與導覽列幾何隔離（含 gridTapPoint 千分比座標保護：y <= 38 為頂部狀態列、y >= 960 為底部導覽列）
        val bounds = node.convergedBounds
        val isInStatusStrip = (bounds.bottom in 1..statusBarCeilingPx) ||
            (node.finalTapPx.y in 1 until statusBarCeilingPx) ||
            (node.gridTapPoint.y in 1..36)
        val isInNavStrip = (bounds.top >= navBarFloorPx && screenHeightPx > 0) ||
            (node.finalTapPx.y >= navBarFloorPx && screenHeightPx > 0) ||
            (node.gridTapPoint.y >= 962)

        if (isInStatusStrip || isInNavStrip) {
            return true
        }

        // 3. 動態時間 / 電量 / 網速文字節點隔離
        val rawLabel = node.label.trim()
        if (rawLabel.isNotEmpty() && node.shortTypeTag != "INP" && isDynamicClockOrBatteryNoise(rawLabel)) {
            return true
        }

        // 4. 系統側螢幕面板、懸浮球與頂部外來通知橫幅隔離
        if (isSystemEdgeOverlayOrFloatingBall(node, screenHeightPx, foregroundAppPackage, expectedTargetPackage)) {
            return true
        }

        return false
    }

    /**
     * 偵測節點是否為系統邊緣覆蓋層（如三星側螢幕面板、智慧側邊欄、懸浮球）或頂部外來通知橫幅
     */
    fun isSystemEdgeOverlayOrFloatingBall(
        node: Stage2ConvergedNode,
        screenHeightPx: Int = 2400,
        foregroundAppPackage: String = "",
        expectedTargetPackage: String = ""
    ): Boolean {
        val lowerLabel = node.label.trim().lowercase()
        val lowerRes = node.nodeId.lowercase()
        if (SYSTEM_FLOATING_LABEL_NOISE_HINTS.any { lowerLabel.contains(it) || lowerRes.contains(it) }) {
            return true
        }

        val pkg = node.packageName.trim()
        if (pkg.isNotEmpty() && (
                pkg.contains("cocktailbar") ||
                    pkg.contains("smartsidebar") ||
                    pkg.contains("touchassistant") ||
                    pkg.contains("floatassistant") ||
                    pkg.contains("floatingball") ||
                    pkg.contains("floattasks")
                )
        ) {
            return true
        }

        // 貼齊螢幕左右極窄邊緣（寬度 <= 44px 且緊貼左右邊緣）的側螢幕面板拉柄
        val b = node.convergedBounds
        val bw = b.right - b.left
        val bh = b.bottom - b.top
        if (bw in 1..44 && bh >= 90 && (node.gridTapPoint.x <= 22 || node.gridTapPoint.x >= 978)) {
            return true
        }

        // 頂部外來通知橫幅（既非目標包名亦非當前前景包名，且位於螢幕頂部 y <= 210）
        val expPkg = expectedTargetPackage.trim()
        val fgPkg = foregroundAppPackage.trim()
        val isTargetOrForeground = (expPkg.isNotEmpty() && pkg == expPkg) || (fgPkg.isNotEmpty() && pkg == fgPkg)
        if (!isTargetOrForeground && (expPkg.isNotEmpty() || fgPkg.isNotEmpty()) && pkg.isNotEmpty() && pkg !in ALLOWED_SYSTEM_DIALOG_PACKAGES) {
            if (node.gridTapPoint.y <= 210 || (screenHeightPx > 0 && b.bottom <= (screenHeightPx * 0.22f).toInt())) {
                return true
            }
        }
        return false
    }

    /**
     * 在建立畫面特徵與進入 AI 決策管線前，對 `VisionPipelineReport` 執行完整確定性淨化：
     * 1. 剔除狀態列、導覽列、非目標包名與動態時間/電量/網速節點
     * 2. 將所有節點座標四捨五入對齊至 10px 網格
     * 3. 強制依「先上後下、先左後右（10px 網格）」固定排序並重新賦予 `#shortMarkId (1..N)`
     */
    fun sanitizeVisionReport(
        report: VisionPipelineReport,
        expectedTargetPackage: String = "",
        foregroundAppPackage: String = ""
    ): VisionPipelineReport {
        val screenW = report.screenWidth.coerceAtLeast(100)
        val screenH = report.screenHeight.coerceAtLeast(100)
        val statusCeiling = (screenH * 0.042f).toInt().coerceIn(56, 128)
        val navFloor = screenH - (screenH * 0.048f).toInt().coerceIn(64, 156)

        val majorityNonNoisePkg = run {
            val pkgCounts = LinkedHashMap<String, Int>()
            for (n in report.finalNodes) {
                val p = n.packageName.trim()
                if (p.isNotEmpty() && !isForeignNoisePackage(p)) {
                    pkgCounts[p] = (pkgCounts[p] ?: 0) + 1
                }
            }
            pkgCounts.entries.maxByOrNull { it.value }?.key.orEmpty()
        }

        val rawForeground = foregroundAppPackage.trim()
        val rawExpected = expectedTargetPackage.trim()
        val hasAnyMatchingTargetNodes = report.finalNodes.any { n ->
            val p = n.packageName.trim()
            p.isEmpty() ||
                (rawForeground.isNotEmpty() && p == rawForeground) ||
                (rawExpected.isNotEmpty() && p == rawExpected)
        }

        val effectiveForegroundPkg = if (hasAnyMatchingTargetNodes) {
            rawForeground.ifEmpty { rawExpected.ifEmpty { majorityNonNoisePkg } }
        } else {
            majorityNonNoisePkg
        }
        val effectiveExpectedPkg = if (hasAnyMatchingTargetNodes) {
            rawExpected
        } else {
            majorityNonNoisePkg
        }

        val cleanedNodes = ArrayList<Stage2ConvergedNode>(report.finalNodes.size)
        for (node in report.finalNodes) {
            if (shouldStripConvergedNode(
                    node = node,
                    screenHeightPx = screenH,
                    statusBarCeilingPx = statusCeiling,
                    navBarFloorPx = navFloor,
                    foregroundAppPackage = effectiveForegroundPkg,
                    expectedTargetPackage = effectiveExpectedPkg
                )
            ) {
                continue
            }

            val cleanedLabel = if (node.shortTypeTag == "INP") {
                node.label.trim()
            } else {
                sanitizeDynamicNoiseFromLabel(node.label)
            }

            // 若原本有文字但清理動態時間/電量後變為空字串，代表該節點純粹是動態雜訊，予以剔除
            if (node.label.isNotBlank() && cleanedLabel.isEmpty() && node.shortTypeTag != "INP") {
                continue
            }

            val snappedTapPx = roundPointTo10PxGrid(node.finalTapPx)
            val snappedGridX = ((snappedTapPx.x.toFloat() / screenW.toFloat()) * 999f)
                .roundToInt()
                .coerceIn(0, 999)
            val snappedGridY = ((snappedTapPx.y.toFloat() / screenH.toFloat()) * 999f)
                .roundToInt()
                .coerceIn(0, 999)

            cleanedNodes.add(
                node.copy(
                    label = cleanedLabel,
                    convergedBounds = roundRectTo10PxGrid(node.convergedBounds),
                    rawOriginalBounds = roundRectTo10PxGrid(node.rawOriginalBounds),
                    finalTapPx = snappedTapPx,
                    gridTapPoint = Point(snappedGridX, snappedGridY).apply {
                        x = snappedGridX
                        y = snappedGridY
                    }
                )
            )
        }

        val alreadyCanonicallyOrderedAndNumbered = cleanedNodes.size == report.finalNodes.size &&
            cleanedNodes.isNotEmpty() &&
            cleanedNodes.indices.all { idx ->
                val curr = cleanedNodes[idx]
                curr.shortMarkId > 0 &&
                    (idx == 0 || (
                        compareNodesCanonically(cleanedNodes[idx - 1], curr) <= 0 &&
                            cleanedNodes[idx - 1].shortMarkId < curr.shortMarkId
                        ))
            }

        val canonicalNodes = if (alreadyCanonicallyOrderedAndNumbered) {
            cleanedNodes
        } else {
            assignCanonicalMarkIds(cleanedNodes)
        }
        val canonicalMarkMap = LinkedHashMap<Int, Point>(canonicalNodes.size)
        for (n in canonicalNodes) {
            canonicalMarkMap[n.shortMarkId] = n.finalTapPx
        }
        return report.copy(
            finalNodes = canonicalNodes,
            markIdToPixelMap = canonicalMarkMap
        )
    }
}
