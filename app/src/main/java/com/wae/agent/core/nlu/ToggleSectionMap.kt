package com.wae.agent.core.nlu

import android.content.Context
import com.wae.agent.core.data.AssetTable

/**
 * 開關目標 → 所在設定分類（資料驅動，**支援 OEM 差異**）。
 *
 * 為什麼需要：很多開關不在設定首頁（三星的「飛航模式」在「連接」，小米在「連接與共享」，
 * 華為在「無線與網路」）。同樣一句話，不同品牌要進不同頁。
 *
 * 檔案格式（`ui_toggle_section_map.tsv`）：
 * ```
 * 目標關鍵字 \t 分類關鍵字(|分隔) \t 備註 \t OEM(可空)
 * ```
 *  - OEM 空 → 通用規則（任何品牌都適用）
 *  - OEM 有值 → 只適用該品牌（`Build.MANUFACTURER` 小寫包含即可，
 *    例如 `xiaomi` 也涵蓋 `redmi`、`poco` 只要在 TSV 寫清楚）
 *
 * 查詢順序：OEM 專屬 → 通用。都找不到回空集合，上層應改走「設定內搜尋」。
 */
object ToggleSectionMap {

    private const val DB_PATH = "open_source_ai_databases/ui_toggle_section_map.tsv"

    data class Entry(val target: String, val sections: List<String>, val oem: String)

    @Volatile
    private var mapping: List<Entry> = emptyList()

    private val table = AssetTable(DB_PATH)

    fun ensureLoaded(context: Context?) {
        val lines = table.linesOrNull(context)
        if (lines == null) {
            mapping = emptyList()
            return
        }
        loadFromLines(lines)
    }

    fun loadFromLines(lines: List<String>) {
        val out = ArrayList<Entry>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            val target = p[0].trim().lowercase()
            val sections = p[1].trim().split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val oem = if (p.size >= 4) p[3].trim().lowercase() else ""
            if (target.isNotEmpty() && sections.isNotEmpty()) out.add(Entry(target, sections, oem))
        }
        mapping = out.sortedByDescending { it.target.length }
    }

    fun resetForTest() {
        mapping = emptyList()
        table.reset()
    }

    /** 通用查詢（不考慮品牌）。 */
    fun sectionHints(target: String): List<String> = sectionHints(target, null)

    /**
     * 目標文字的所在分類關鍵詞。
     *
     * @param manufacturer `Build.MANUFACTURER`（可 null）；有 OEM 專屬規則時優先採用。
     * @return 分類關鍵詞；空集合代表「這個目標沒有對照資料」，上層應改走設定內搜尋。
     */
    fun sectionHints(target: String, manufacturer: String?): List<String> {
        val t = target.lowercase().trim()
        if (t.isEmpty()) return emptyList()
        val mfr = manufacturer?.lowercase()?.trim().orEmpty()

        fun match(entries: List<Entry>): List<String> {
            entries.firstOrNull { t.contains(it.target) || it.target.contains(t) }?.let { return it.sections }
            entries.firstOrNull { t.startsWith(it.target) }?.let { return it.sections }
            return emptyList()
        }

        if (mfr.isNotEmpty()) {
            val specific = mapping.filter { it.oem.isNotEmpty() && mfr.contains(it.oem) }
            match(specific).let { if (it.isNotEmpty()) return it }
        }
        return match(mapping.filter { it.oem.isEmpty() })
    }
}
