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

        // 系統目標別名（Wi-Fi／藍牙／飛航／亮度／音量）也在資料裡，一起載入。
        // 沒載到就回空表：normalize 會回 null，流程回到原本的路徑，不會用過期清單硬認。
        val targetLines = com.wae.core.util.Degrade.orEmpty("sysact:loadTargets") {
            context.assets.open("open_source_ai_databases/ui_system_targets.tsv")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
        }
        if (targetLines.isNotEmpty()) {
            com.wae.core.system.SystemTargets.installFrom(targetLines)
            android.util.Log.i("WAE_SYSACT", "loaded system targets ${targetLines.size} lines")
        }
    }
}
