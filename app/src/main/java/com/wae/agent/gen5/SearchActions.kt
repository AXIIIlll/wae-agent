package com.wae.agent.gen5

import android.content.Context

/**
 * 「帶查詢字串的搜尋意圖」對照表（資料驅動）。
 *
 * 為什麼需要：設定頁的搜尋框是 Google 的 `settings.intelligence` 元件，
 * `SET_TEXT` 回 true 卻不吃字、貼上也沒用（實測）。但 Android 有公開的
 * 搜尋 Activity 意圖（例如 `com.android.settings.action.SETTINGS_SEARCH`），
 * 直接把查詢字串當 extra 帶進去就到達同一個結果頁——不需要輸入法。
 *
 * 對照表放 assets，程式不認得任何 App 名稱。
 */
object SearchActions {

    private const val ASSET = "open_source_ai_databases/ui_search_actions.tsv"

    @Volatile
    private var table: Map<String, String>? = null

    fun load(context: Context): Map<String, String> {
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

    /** 這個前景 App 有沒有支援「帶查詢字串」的搜尋意圖。 */
    fun actionFor(context: Context, pkg: String): String? = load(context)[pkg]
}
