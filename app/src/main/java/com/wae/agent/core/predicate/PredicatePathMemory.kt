package com.wae.agent.core.predicate

import android.content.Context

/**
 * **路徑記憶**：把成功過的「鑽入路徑」記下來，下次同一句指令直接照走。
 *
 * 為什麼需要：目標常常不在第一頁（Wi-Fi 在「連接」底下）。
 * 第一次靠語意下降慢慢鑽（3–5 步），成功後把「連接」這一步記住，
 * 第二次就是 1 步。這是**不用訓練、自己會變快**的機制。
 *
 * 儲存格式（SharedPreferences，極小）：
 * ```
 * key   = "<pkg>|<目標詞>"
 * value = "連接>Wi-Fi"        （以 > 分隔的標籤序列）
 * ```
 * 大小上限：每個 key 只存最近一次成功的路徑；失敗就刪掉重新學。
 */
object PredicatePathMemory {

    private const val PREFS = "wae_predicate_paths"
    private const val MAX_KEYS = 200

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(pkg: String, goal: String) = "$pkg|${com.wae.core.predicate.TextFold.fold(goal)}"

    /** 記住這次成功的路徑（標籤序列）。 */
    fun remember(context: Context, pkg: String, goal: String, labels: List<String>) {
        if (pkg.isEmpty() || goal.isEmpty() || labels.isEmpty()) return
        val p = prefs(context)
        if (p.all.size > MAX_KEYS) p.edit().clear().apply()
        p.edit().putString(key(pkg, goal), labels.joinToString(">")).apply()
    }

    /** 取回上次成功的路徑；沒有就回空清單。 */
    fun recall(context: Context, pkg: String, goal: String): List<String> {
        if (pkg.isEmpty() || goal.isEmpty()) return emptyList()
        val v = prefs(context).getString(key(pkg, goal), null) ?: return emptyList()
        return v.split('>').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 這條記憶失效了就刪掉（下次重新學）。 */
    fun forget(context: Context, pkg: String, goal: String) {
        if (pkg.isEmpty() || goal.isEmpty()) return
        prefs(context).edit().remove(key(pkg, goal)).apply()
    }
}
