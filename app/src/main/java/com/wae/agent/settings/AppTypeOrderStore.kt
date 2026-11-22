package com.wae.agent.settings

import android.content.Context

/**
 * 每個「型別」的 App 優先順序（左邊最常用、右邊最晚用）。
 *
 * 為什麼要使用者自己排：同一個型別在這支手機上可能有好幾個 App
 * （兩三個瀏覽器、兩個地圖），哪個先開只有使用者知道。
 * 這個順序會直接影響名詞 fallback 選哪個 App。
 *
 * 只存「型別 → 套件順序」，不含任何畫面或文字內容。
 */
object AppTypeOrderStore {

    private const val PREFS = "wae_type_order"

    fun order(context: Context, type: String): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(type, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun save(context: Context, type: String, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(type, packages.joinToString("|")).apply()
    }

    fun clear(context: Context, type: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(type).apply()
    }

    /** 依使用者順序排；不在順序裡的排在後面（保持原順序）。 */
    fun <T> apply(context: Context, type: String, candidates: List<T>, pkgOf: (T) -> String): List<T> {
        val order = order(context, type)
        if (order.isEmpty()) return candidates
        val index = order.withIndex().associate { (i, pkg) -> pkg to i }
        return candidates.sortedBy { index[pkgOf(it)] ?: Int.MAX_VALUE }
    }
}
