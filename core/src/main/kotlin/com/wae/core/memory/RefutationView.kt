package com.wae.core.memory

import com.wae.core.model.Verdict
import com.wae.core.model.Refutation

/**
 * 反例檢視（C10）：把內部的禁令翻成「人能讀、能判斷要不要刪」的一行。
 *
 * 為什麼要能讀：反例庫是這個產品最重要的資產，也是最大的風險——
 * 一條錯的禁令會讓某個功能永久壞掉，而且使用者看不出原因。
 */
object RefutationView {

    /** 一行式：App／效果／手段／原因／次數／版本。 */
    fun render(r: Refutation): String = buildString {
        append(scopeLabel(r.scope))
        append(" ｜ ")
        append(r.effect)
        append(" ｜ ")
        append(r.means)
        append(" ｜ ")
        append(attributionLabel(r.attribution.name))
        append(" ｜ ")
        append(verdictLabel(r.verdict))
        append(" ×").append(r.count)
        if (r.appVersion > 0) append(" (v").append(r.appVersion).append(')')
    }

    fun renderAll(rows: List<Refutation>): List<String> =
        rows.sortedWith(compareByDescending<Refutation> { it.count }.thenBy { it.scope }).map { render(it) }

    /** 給「反例檢視頁」用的清單：每一列都帶刪除鍵。 */
    data class Entry(val key: String, val text: String)

    fun entries(rows: List<Refutation>): List<Entry> =
        rows.sortedWith(compareByDescending<Refutation> { it.count }.thenBy { it.scope })
            .map { Entry(key = it.key(), text = render(it)) }

    private fun scopeLabel(scope: String): String = when {
        scope == RefutationLog.SCOPE_GLOBAL -> "所有 App"
        scope.startsWith(RefutationLog.SCOPE_FRAME_PREFIX) -> "這個畫面"
        scope.startsWith(RefutationLog.SCOPE_APP_PREFIX) -> "App " + scope.removePrefix(RefutationLog.SCOPE_APP_PREFIX)
        else -> scope
    }

    private fun verdictLabel(verdict: Verdict): String = when (verdict) {
        Verdict.BAN -> "已停用"
        Verdict.LOWER -> "降權"
        Verdict.NEED_CONFIRM -> "需確認"
    }

    private fun attributionLabel(name: String): String = when (name) {
        "PERCEPTION" -> "看不到"
        "LOCATION" -> "點錯位置"
        "TIMING" -> "時機不對"
        "PERMISSION" -> "權限不足"
        "CONTRACT" -> "型別判斷錯"
        else -> name
    }
}
