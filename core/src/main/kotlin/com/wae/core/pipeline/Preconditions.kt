package com.wae.core.pipeline

import com.wae.core.model.Effect

/**
 * 一般前置條件（C2）：這個效果需要「先在哪個 App」才做得到。
 *
 * 之前只硬處理了系統設定（Wi-Fi 面板）；這裡把它變成資料驅動：
 * `preconditions.tsv` 寫「效果前綴 → 要先開的 App 標籤」，程式不認得任何 App 名稱。
 */
class Preconditions(table: Map<String, String> = emptyMap()) {

    data class Nav(val key: String, val appLabel: String)

    private val rules: List<Pair<String, String>> by lazy {
        table.entries.sortedByDescending { it.key.length }.map { it.key to it.value }
    }

    /**
     * 這個任務需不需要先開某個 App；`tried` 用來避免同一輪反覆開同一個 App。
     */
    fun navigationFor(effects: List<Effect>, tried: Set<String>): Nav? {
        for (effect in effects) {
            for ((prefix, label) in rules) {
                if (!effect.target.startsWith(prefix)) continue
                if (effect.key() in tried) continue
                return Nav(key = effect.key(), appLabel = label)
            }
        }
        return null
    }

    fun isEmpty(): Boolean = rules.isEmpty()

    companion object {
        fun parse(lines: Sequence<String>): Preconditions {
            val out = LinkedHashMap<String, String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cells = line.split('\t')
                if (cells.size < 2) continue
                val prefix = cells[0].trim()
                val label = cells[1].trim()
                if (prefix.isNotEmpty() && label.isNotEmpty()) out[prefix] = label
            }
            return Preconditions(out)
        }
    }
}
