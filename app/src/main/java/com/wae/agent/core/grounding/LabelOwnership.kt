package com.wae.agent.core.grounding

/**
 * I3 標籤歸屬推論（label ownership）：**這段文字是屬於哪個可點元素？**
 *
 * 為什麼需要這個：無障礙樹裡「文字」與「可點元素」經常是**不同節點**。
 * 例如一列設定項是 `[標題文字][右側開關]`，可點的其實是開關或整列容器，文字自己不可點；
 * 只按「文字最近的可點東西」做啟發式，很容易把標題配到隔閉的圖示、或把列尾箭頭當成目標。
 *
 * 這裡把「列」當成推論單位，先分三種常見列型（見 [RowType]），再用**可調權重**挑 owner：
 *  - [RowType.SWITCH_ROW]：有 checkable 元件（開關/checkbox）→ owner 優先 checkable 那個
 *  - [RowType.ICON_ROW]：一排小圖示（toolbar/動作列）→ owner 是同列最近的圖示或包含文字的按鈕
 *  - [RowType.LIST_ROW]：整列可點的列表項 → owner 是整列（寬度大、包含文字），列尾 icon 不會搶走
 *
 * 資料驅動：預設權重是內建 [DEFAULT_ROWS]（bootstrap，程式碼不寫死任何 App 名/元件清單），
 * 之後可由飛輪訓練器產生 `rowtype<TAB>trait<TAB>weight` 格式的 TSV 後用
 * [loadFromLines] 覆蓋；壞檔會自動退回 bootstrap，不會讓行為退化。
 * （本 Phase 尚未有對應 asset 檔，資料來源由 Lead 的 G3 整合決定。）
 *
 * 全部純 Kotlin（無 Android import），JVM 可測。
 */
object LabelOwnership {

    /** 三種列型（決定 owner 的挑選策略與權重表前綴） */
    enum class RowType {
        /** 開關列：列內有 checkable 元件（開關 / checkbox / radio） */
        SWITCH_ROW,
        /** 圖示列：一排無文字的小圖示（toolbar / 動作列 / 底部圖示列） */
        ICON_ROW,
        /** 列表列：整列可點的列表項（設定項、搜尋建議、抽屜項） */
        LIST_ROW
    }

    /**
     * 一次歸屬推論的結果。
     *
     * @param owner 判定出的 owner（null＝同列找不到可用 owner）
     * @param rowType 判定出的列型
     * @param confidence 0..1（目前是權重分的單調正規化，非機率；Phase 2 校準由 Lead 處理）
     * @param evidence 可解釋標籤（`switch:flag:checkable` / `self:actionable_text` / `no:owner` 等）
     */
    data class Ownership(
        val owner: GroundingCandidate?,
        val rowType: RowType,
        val confidence: Float,
        val evidence: String
    ) {
        val hasOwner: Boolean get() = owner != null
    }

    // ── 權重表 ──────────────────────────────────────────────────────

    /** 權重 key 的前綴：與 TSV 的 rowtype 欄一致 */
    private const val PREFIX_SWITCH = "switch"
    private const val PREFIX_ICON = "icon"
    private const val PREFIX_LIST = "list"

    /** 列狀元件的寬度門檻（占螢幕寬比例） */
    private const val ROW_LIKE_WIDTH_RATIO = 0.5f

    /** 圖示判定：寬 <= 高 * 此比例（避免把橫長按鈕當圖示） */
    private const val ICON_MAX_ASPECT = 2.2f

    /** 信心正規化基準（權重和 ~2.0 視為滿信心） */
    private const val CONFIDENCE_NORM = 2.0f

    /**
     * Bootstrap 權重（資料檔載入失敗時使用；方向：列型語意 → owner 特徵）。
     *
     * 格式與 DecisionHead 一致：`rowtype<TAB>trait<TAB>weight`，`#` 開頭為註解。
     * 正式權重可由資料飛輪產生後用 [loadFromLines] 覆蓋，這裡只保證「不會退化」。
     */
    private val DEFAULT_ROWS: List<String> = listOf(
        "# rowtype\ttrait\tweight（bootstrap；switch/icon/list 三種列型 owner 推論）",
        "# rowtype = switch / icon / list；trait = flag:* 或 num:*（由 traits() 產生）",
        "switch\tflag:checkable\t1.20",
        "switch\tflag:checked\t0.15",
        "switch\tflag:actionable\t0.40",
        "switch\tflag:enabled\t0.30",
        "switch\tflag:row_like\t0.50",
        "switch\tnum:vertical_contain\t0.50",
        "switch\tnum:horiz_gap\t0.40",
        "switch\tnum:vert_overlap\t0.20",
        "icon\tflag:icon_like\t0.90",
        "icon\tflag:actionable\t0.40",
        "icon\tflag:enabled\t0.30",
        "icon\tflag:contains_text\t0.30",
        "icon\tflag:row_like\t0.15",
        "icon\tnum:vertical_contain\t0.40",
        "icon\tnum:horiz_gap\t0.60",
        "icon\tnum:vert_overlap\t0.20",
        "list\tflag:row_like\t0.90",
        "list\tflag:clickable\t0.60",
        "list\tflag:actionable\t0.50",
        "list\tflag:enabled\t0.30",
        "list\tflag:contains_text\t0.70",
        "list\tflag:icon_like\t-0.40",
        "list\tnum:vertical_contain\t0.50",
        "list\tnum:horiz_gap\t0.20",
        "list\tnum:vert_overlap\t0.20"
    )

    /** 目前權重表（key = "rowtype:trait"）；resetForTest/壞檔都回到 bootstrap */
    @Volatile
    private var weights: Map<String, Float> = parseRows(DEFAULT_ROWS)

    // ── 載入 / 測試 ────────────────────────────────────────────────

    /**
     * 解析權重行：`rowtype \t trait \t weight`。
     *
     * 註解 `#` 與空白行忽略；欄位不足/非數字整列跳過（不拋例外）。
     * 解析不到任何有效列（坏檔）→ 自動退回 [DEFAULT_ROWS] bootstrap。
     */
    fun loadFromLines(lines: List<String>) {
        val parsed = parseRows(lines)
        weights = if (parsed.isEmpty()) parseRows(DEFAULT_ROWS) else parsed
    }

    /** 測試/緊急除錯用：回到 bootstrap 權重 */
    fun resetForTest() {
        weights = parseRows(DEFAULT_ROWS)
    }

    /** 目前權重列數（bootstrap 應 > 20） */
    fun weightCount(): Int = weights.size

    /** 目前某一列型的某一特徵權重（可解釋性/log 用；不存在回 0） */
    fun weightOf(rowType: RowType, trait: String): Float =
        weights[keyOf(prefixOf(rowType), trait)] ?: 0f

    private fun parseRows(lines: List<String>): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 3) continue
            val rowType = p[0].trim().lowercase()
            if (rowType != PREFIX_SWITCH && rowType != PREFIX_ICON && rowType != PREFIX_LIST) continue
            val trait = p[1].trim()
            if (trait.isEmpty()) continue
            val value = p[2].trim().toFloatOrNull() ?: continue
            // "NaN"/"Infinity" 會被 toFloatOrNull 接受，但會污染加權分 → 直接視為坏列
            if (!value.isFinite()) continue
            out[keyOf(rowType, trait)] = value
        }
        return out
    }

    private fun keyOf(prefix: String, trait: String): String = "$prefix:$trait"

    private fun prefixOf(rowType: RowType): String = when (rowType) {
        RowType.SWITCH_ROW -> PREFIX_SWITCH
        RowType.ICON_ROW -> PREFIX_ICON
        RowType.LIST_ROW -> PREFIX_LIST
    }

    // ── 列型分類 ──────────────────────────────────────────────────

    /**
     * 依列內節點結構判定列型（不看文字內容，只看語意 flag 與幾何）。
     *
     * 判定順序：
     *  1. 有 enabled 的 checkable → [RowType.SWITCH_ROW]
     *  2. 兩個以上 icon_like → [RowType.ICON_ROW]
     *  3. 一個 icon_like 且沒有列狀可點元件 → [RowType.ICON_ROW]（單圖示 + 標籤）
     *  4. 其餘 → [RowType.LIST_ROW]（含整列可點與一般文字列）
     */
    fun classifyRow(row: List<GroundingCandidate>, screenW: Int): RowType {
        if (row.isEmpty()) return RowType.LIST_ROW
        if (row.any { it.enabled && it.checkable }) return RowType.SWITCH_ROW
        val icons = row.count { isIconLike(it) }
        val hasRowLikeClickable = row.any { c ->
            c.enabled && c.clickable && SpatialRelation.isValid(c.bounds) &&
                screenW > 0 && SpatialRelation.width(c.bounds) >= screenW * ROW_LIKE_WIDTH_RATIO
        }
        if (icons >= 2) return RowType.ICON_ROW
        if (icons == 1 && !hasRowLikeClickable) return RowType.ICON_ROW
        return RowType.LIST_ROW
    }

    /** 圖示語意：可點、沒文字、且接近正方形（不把橫長按鈕當圖示） */
    private fun isIconLike(c: GroundingCandidate): Boolean {
        if (!c.enabled || !c.clickable || !SpatialRelation.isValid(c.bounds)) return false
        if (c.text.isNotBlank()) return false
        val w = SpatialRelation.width(c.bounds)
        val h = SpatialRelation.height(c.bounds)
        if (w <= 0 || h <= 0) return false
        return w <= h * ICON_MAX_ASPECT
    }

    // ── 特徵抽取 ──────────────────────────────────────────────────

    /**
     * owner 候選的特徵（名稱與 [DEFAULT_ROWS] 的 trait 欄一致）。
     *
     * @param textRect 標籤文字的 rect（可為 null＝只做列型無關的一般評分）
     */
    private fun traits(
        c: GroundingCandidate,
        textRect: IntArray?,
        screenW: Int
    ): Map<String, Float> {
        val f = HashMap<String, Float>(16)
        f["flag:enabled"] = if (c.enabled) 1f else 0f
        f["flag:clickable"] = if (c.clickable) 1f else 0f
        f["flag:checkable"] = if (c.checkable) 1f else 0f
        f["flag:checked"] = if (c.checked) 1f else 0f
        f["flag:editable"] = if (c.editable) 1f else 0f
        f["flag:actionable"] = if (c.isActionable) 1f else 0f
        f["flag:has_text"] = if (c.hasText) 1f else 0f
        f["flag:icon_like"] = if (isIconLike(c)) 1f else 0f
        f["flag:row_like"] =
            if (screenW > 0 && SpatialRelation.width(c.bounds) >= screenW * ROW_LIKE_WIDTH_RATIO) 1f else 0f
        if (textRect != null && SpatialRelation.isValid(textRect)) {
            f["flag:contains_text"] = if (SpatialRelation.contains(c.bounds, textRect)) 1f else 0f
            val cy = SpatialRelation.centerY(textRect)
            val containsCenter = c.bounds.size == 4 && c.bounds[1] <= cy && c.bounds[3] >= cy
            f["num:vertical_contain"] = if (containsCenter) 1f else 0f
            f["num:vert_overlap"] = SpatialRelation.verticalOverlapRatio(c.bounds, textRect)
            val gap = SpatialRelation.edgeDistance(c.bounds, textRect)
            val norm = if (screenW > 0) screenW.toFloat() else 1080f
            f["num:horiz_gap"] =
                if (gap.isFinite()) (1f - (gap / norm)).coerceIn(0f, 1f) else 0f
        }
        return f
    }

    /** 單一列型底下的加權分（線性模型；權重缺漏視為 0） */
    private fun scoreRow(rowType: RowType, traits: Map<String, Float>): Float {
        val prefix = prefixOf(rowType)
        var sum = 0f
        for ((trait, value) in traits) {
            if (value == 0f) continue
            val w = weights[keyOf(prefix, trait)] ?: continue
            sum += w * value
        }
        return sum
    }

    // ── 對外推論 ──────────────────────────────────────────────────

    /**
     * 從一列節點推論 owner。
     *
     * @param row 同一列的節點（通常是 `SpatialRelation.rows(...)` 的一組）
     * @param screenW 螢幕寬（px）
     * @param textRect 標籤文字的 rect（有提供時「包含文字/垂直涵蓋/水平距離」才會計分）
     */
    fun ownerForRow(
        row: List<GroundingCandidate>,
        screenW: Int,
        textRect: IntArray? = null
    ): Ownership {
        val rowType = classifyRow(row, screenW)
        val prefix = prefixOf(rowType)
        val candidates = row.filter {
            it.enabled &&
                (it.clickable || it.checkable || it.editable) &&
                SpatialRelation.isValid(it.bounds)
        }
        if (candidates.isEmpty()) {
            return Ownership(null, rowType, 0f, "$prefix:no_actionable")
        }
        val ranked = candidates
            .map { c -> c to scoreRow(rowType, traits(c, textRect, screenW)) }
            .sortedWith(
                compareByDescending<Pair<GroundingCandidate, Float>> { it.second }
                    .thenByDescending { it.first.score }
                    .thenBy { it.first.id }
            )
        val best = ranked.first()
        val bestTraits = traits(best.first, textRect, screenW)
        return Ownership(
            owner = best.first,
            rowType = rowType,
            confidence = confidenceOf(best.second),
            evidence = "$prefix:${topTrait(prefix, bestTraits).ifEmpty { "fallback" }}"
        )
    }

    /**
     * 從一段文字 rect 推論它屬於哪個可點元素（I3 主入口）。
     *
     * 推論順序（第一個命中就回傳）：
     *  1. 文字本身就是可點元素（可點文字/可點按鈕內的文字）→ owner 就是它，信心 1.0
     *  2. 與文字同列的所有節點 → 用列型權重挑 owner
     *  3. 權重挑不到 → 退回 [SpatialRelation.rowOwner] 純幾何（低信心）
     *
     * @return 永遠不為 null；找不到 owner 時 [Ownership.owner] 為 null、
     *         [Ownership.evidence] 以 `no:` 開頭（呼叫端可據此 abstain）。
     */
    fun ownerOf(
        textRect: IntArray?,
        nodes: List<GroundingCandidate>,
        screenW: Int
    ): Ownership {
        if (textRect == null || !SpatialRelation.isValid(textRect)) {
            return Ownership(null, RowType.LIST_ROW, 0f, "invalid:text_rect")
        }
        // 1) 文字自己就是可點元素（a11y 常見：Button 的 text 與 clickable 在同一個節點）
        val self = nodes.firstOrNull { c ->
            c.enabled &&
                SpatialRelation.isValid(c.bounds) &&
                c.bounds.contentEquals(textRect) &&
                c.hasText &&
                (c.clickable || c.checkable || c.editable)
        }
        if (self != null) {
            val row = SpatialRelation.rows(nodes).firstOrNull { r -> r.any { it.id == self.id } }
            val rowType = if (row != null) classifyRow(row, screenW) else RowType.LIST_ROW
            return Ownership(self, rowType, 1f, "self:actionable_text")
        }
        // 2) 與文字同列的節點（容差用整棵樹的行高中位數自動推估）
        val tol = SpatialRelation.rowTolerance(nodes)
        val rowNodes = nodes.filter {
            SpatialRelation.isValid(it.bounds) && SpatialRelation.sameRow(it.bounds, textRect, tol)
        }
        if (rowNodes.isEmpty()) {
            return Ownership(null, RowType.LIST_ROW, 0f, "no:same_row_nodes")
        }
        val weighted = ownerForRow(rowNodes, screenW, textRect)
        if (weighted.owner != null) return weighted
        // 3) 純幾何後備（權重表完全沒有可用 trait 時的保底）
        val geometric = SpatialRelation.rowOwner(textRect, nodes, screenW)
        return Ownership(
            owner = geometric,
            rowType = weighted.rowType,
            confidence = if (geometric != null) 0.10f else 0f,
            evidence = if (geometric != null) "geo:fallback" else "no:owner"
        )
    }

    // ── 內部工具 ──────────────────────────────────────────────────

    /** 權重分 → 0..1 單調信心（非機率；負分視為 0） */
    private fun confidenceOf(rawScore: Float): Float {
        if (!rawScore.isFinite() || rawScore <= 0f) return 0f
        return (rawScore / CONFIDENCE_NORM).coerceIn(0f, 1f)
    }

    /** 對該列型貢獻最大的 trait（evidence 用；沒有有效貢獻回空字串） */
    private fun topTrait(prefix: String, traits: Map<String, Float>): String {
        var bestName = ""
        var bestValue = 0f
        for ((trait, value) in traits) {
            if (value == 0f) continue
            val w = weights[keyOf(prefix, trait)] ?: continue
            val contribution = w * value
            if (contribution > bestValue) {
                bestValue = contribution
                bestName = trait
            }
        }
        return bestName
    }
}
