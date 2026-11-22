package com.wae.agent.gen5

import android.content.Context

/**
 * App 別名（叫法 → 系統標籤）。
 *
 * 為什麼需要：使用者說「電話」，系統標籤可能是 `Phone`；說「通訊錄」，系統標籤是 `聯絡人`。
 * 這裡只做**字串對照**，真正的套件仍由 `PackageManager` 解析——所以換裝置／換語言也能用。
 */
object AppAliases {

    private const val ASSET = "open_source_ai_databases/ui_app_aliases.tsv"

    @Volatile
    private var table: Map<String, String>? = null

    fun canonical(context: Context, label: String): String? {
        val map = load(context)
        return map[label.trim()] ?: map.entries.firstOrNull {
            it.key.equals(label.trim(), ignoreCase = true)
        }?.value
    }

    private fun load(context: Context): Map<String, String> {
        table?.let { return it }
        val loaded = runCatching {
            val map = LinkedHashMap<String, String>()
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val cells = line.split('\t')
                    if (cells.size < 2) continue
                    map[cells[0].trim()] = cells[1].trim()
                }
            }
            map
        }.getOrDefault(emptyMap())
        table = loaded
        return loaded
    }
}
