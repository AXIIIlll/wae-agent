package com.wae.agent.core.system

import android.content.Context

/**
 * 把 `ui_system_actions.tsv` 載進 [SystemActions]。
 *
 * 為什麼要獨立一支：資料載入屬於 App 層（要讀 assets），
 * 判斷與執行留在 [SystemActions]（純邏輯，可測試）。
 */
object SystemActionLoader {

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val lines = com.wae.core.util.Degrade.orEmpty("sysact:load") {
            context.assets.open("open_source_ai_databases/ui_system_actions.tsv")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
        }
        if (lines.isNotEmpty()) {
            SystemActions.installFrom(lines)
            loaded = true
            android.util.Log.i("WAE_SYSACT", "loaded ${lines.size} lines")
        }
    }
}
