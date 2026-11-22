package com.wae.agent.core.runtime

import android.content.Context

/**
 * 可調參數的**單一入口**（專案規則：不要在程式碼裡寫魔術數字）。
 *
 * 三層優先序：
 *  1. `assets/open_source_ai_databases/ui_tunables.tsv`（預設值，隨版本出貨）
 *  2. `SharedPreferences("wae_tunables")`（真機覆寫，除錯時不用重編）
 *  3. 程式碼裡的 `default`（連 TSV 都讀不到時的最後防線）
 *
 * 用法：
 * ```kotlin
 * Tunables.ensureLoaded(context)
 * val steps = Tunables.getInt(context, "budget.max_steps", 20)
 * ```
 *
 * 為什麼要這樣做：這個專案會長期微調（門檻、逾時、步數），
 * 把數值集中在一張表＋一個 getter，改參數不必碰邏輯程式碼，也不必重新編譯。
 */
object Tunables {

    private const val DB_PATH = "open_source_ai_databases/ui_tunables.tsv"
    private const val PREFS = "wae_tunables"

    private val values = HashMap<String, String>()
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context?) {
        if (loaded) return
        loaded = true
        if (context != null) {
            runCatching {
                context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).useLines { seq ->
                    loadFromLines(seq)
                }
            }
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    if (v != null) values[k] = v.toString()
                }
            }
        }
    }

    /** 測試用／離線解析：純字串序列，不碰 Android。 */
    fun loadFromLines(lines: Sequence<String>) {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 2) continue
            val k = p[0].trim()
            val v = p[1].trim()
            if (k.isNotEmpty() && v.isNotEmpty()) values[k] = v
        }
    }

    /** 真機覆寫：寫 SharedPreferences，立即生效（下一次 get 就拿到）。 */
    fun override(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
        synchronized(this) { values[key] = value }
    }

    fun clearOverrides(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun raw(key: String): String? = synchronized(this) { values[key] }

    fun getInt(context: Context?, key: String, default: Int): Int {
        ensureLoaded(context)
        return raw(key)?.toIntOrNull() ?: default
    }

    fun getLong(context: Context?, key: String, default: Long): Long {
        ensureLoaded(context)
        return raw(key)?.toLongOrNull() ?: default
    }

    fun getFloat(context: Context?, key: String, default: Float): Float {
        ensureLoaded(context)
        return raw(key)?.toFloatOrNull() ?: default
    }

    fun getBool(context: Context?, key: String, default: Boolean): Boolean {
        ensureLoaded(context)
        val v = raw(key) ?: return default
        return when (v.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }
    }

    /** 診斷用：目前生效的所有參數（含覆寫）。 */
    fun all(): Map<String, String> = synchronized(this) { values.toMap() }

    fun resetForTest() {
        synchronized(this) {
            values.clear()
            loaded = false
        }
    }
}
