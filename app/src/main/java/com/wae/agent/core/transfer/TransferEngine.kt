package com.wae.agent.core.transfer

import android.content.Context
import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonEdge

/**
 * S6：跨 App 同構遷移（架構書 §8）。
 *
 * 觀察：Material/AndroidX 的 App 有大量同構結構——「工具列 + 搜尋框 + 列表 + 底部導覽」。
 * 做法：用 edge_sig 做圖同構映射，把 App X 的邊「借」給 App Y，標記為 BORROWED，
 * 成功率信賴度打折（0.6）；失敗一次降權、三次永久移除。
 *
 * ```
 * edge_sig = ( op, 選擇器家族 )      // 選擇器家族由 ui_selector_families.tsv 決定
 * 借邊使用順序：自家邊 → 借用邊 → 分類器
 * ```
 *
 * 這裡不碰裝置、不改圖，只產生「可借用的邊」與借用後的信賴度。
 */
object TransferEngine {

    private const val DB_PATH = "open_source_ai_databases/ui_selector_families.tsv"

    /** 借用邊的信賴度折扣（架構書 §8.2） */
    const val BORROW_CONFIDENCE = 0.60f

    /** 借用邊失敗一次的信賴度乘數 */
    const val FAIL_DECAY = 0.80f

    /** 失敗幾次後永久移除 */
    const val MAX_BORROW_FAILURES = 3

    @Volatile
    private var families: List<Pair<String, String>> = emptyList()

    @Volatile
    private var loadAttempted = false

    private val DEFAULT_FAMILIES = listOf(
        "search" to "SEARCH_ENTRY", "query" to "SEARCH_ENTRY",
        "src_text" to "TEXT_FIELD", "edit" to "TEXT_FIELD", "input" to "TEXT_FIELD",
        "list_item" to "LIST_ITEM", "row" to "LIST_ITEM", "item" to "LIST_ITEM",
        "card" to "CARD", "tab" to "TAB", "back" to "NAV", "nav" to "NAV",
        "switch" to "TOGGLE", "toggle" to "TOGGLE", "checkbox" to "TOGGLE",
        "seek" to "VALUE", "slider" to "VALUE", "progress" to "VALUE",
        "button" to "BUTTON", "btn" to "BUTTON", "icon" to "ICON"
    )

    private val failures = HashMap<String, Int>()

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            families = DEFAULT_FAMILIES
        }
    }

    fun loadFromLines(lines: List<String>) {
        val list = ArrayList<Pair<String, String>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            list.add(p[0].trim().lowercase() to p[1].trim().uppercase())
        }
        families = list.sortedByDescending { it.first.length }
    }

    fun resetForTest() {
        families = emptyList()
        loadAttempted = false
        failures.clear()
    }

    private fun effectiveFamilies(): List<Pair<String, String>> =
        if (families.isEmpty()) DEFAULT_FAMILIES else families

    /** 選擇器家族（例如 id:search_box → SEARCH_ENTRY） */
    fun selectorFamily(selector: String): String {
        val s = selector.lowercase()
        for ((kw, fam) in effectiveFamilies()) if (s.contains(kw)) return fam
        // 用角色當後備
        return when {
            s.startsWith("role:list_item") -> "LIST_ITEM"
            s.startsWith("role:button") -> "BUTTON"
            s.startsWith("grid:") -> "POSITION"
            else -> "UNKNOWN"
        }
    }

    /** edge_sig = op + 家族（架構書 §8.2） */
    fun edgeSignature(edge: AutomatonEdge): String = edge.op.uppercase() + "|" + selectorFamily(edge.selector)

    fun borrowedKey(donor: String, edge: AutomatonEdge): String =
        "$donor\u0001${edge.from}\u0001${edge.op}\u0001${edge.selector}\u0001${edge.to}"

    /**
     * 產生「可借用的邊」。
     *
     * 條件（全部必須成立）：
     *  1. donor 的邊 signature 在 recipient 中**不存在**（避免覆蓋自家知識）
     *  2. donor 的邊足夠可靠（成功率高於門檻）
     *  3. 該邊未被累計失敗移除
     */
    fun borrowableEdges(
        recipientPkg: String,
        recipient: Automaton?,
        donorPkg: String,
        donor: Automaton,
        minDonorSuccess: Float = 0.60f,
        maxEdges: Int = 8
    ): List<AutomatonEdge> {
        if (donorPkg == recipientPkg) return emptyList()
        val ownSigs = recipient?.edges?.map { edgeSignature(it) }?.toHashSet() ?: HashSet()
        return donor.edges
            .filter { it.successRate >= minDonorSuccess }
            .filter { edgeSignature(it) !in ownSigs }
            .filter { (failures[borrowedKey(donorPkg, it)] ?: 0) < MAX_BORROW_FAILURES }
            .sortedWith(compareBy({ edgeSignature(it) }, { it.selector }, { it.from }))
            .take(maxEdges)
    }

    /** 借用邊的信賴度（會隨失敗次數衰減） */
    fun confidenceOf(donorPkg: String, edge: AutomatonEdge): Float {
        val f = failures[borrowedKey(donorPkg, edge)] ?: 0
        if (f >= MAX_BORROW_FAILURES) return 0f
        var c = BORROW_CONFIDENCE * edge.successRate
        repeat(f) { c *= FAIL_DECAY }
        return c
    }

    /** 回報一次借用邊的執行結果 */
    fun recordResult(donorPkg: String, edge: AutomatonEdge, ok: Boolean) {
        val key = borrowedKey(donorPkg, edge)
        if (ok) {
            failures.remove(key)
        } else {
            // 保留失敗計數（不要在第 3 次時刪掉，否則「永久移除」的判定會失效）
            val n = ((failures[key] ?: 0) + 1).coerceAtMost(MAX_BORROW_FAILURES)
            failures[key] = n
        }
    }

    fun failureCount(donorPkg: String, edge: AutomatonEdge): Int =
        failures[borrowedKey(donorPkg, edge)] ?: 0

    fun isPermanentlyRemoved(donorPkg: String, edge: AutomatonEdge): Boolean =
        (failures[borrowedKey(donorPkg, edge)] ?: 0) >= MAX_BORROW_FAILURES
}
