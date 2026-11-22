package com.wae.core.config

/**
 * 參數存取的**唯一介面**（I7）。
 *
 * core 不能碰 `Context`；App 層負責把 `assets` 的 TSV 與 `SharedPreferences`
 * 讀成 `Map<String, String>` 後注入。這樣同一份邏輯在 JVM 測試與真機上行為一致。
 */
interface Config {
    fun str(key: String, def: String): String
    fun int(key: String, def: Int): Int
    fun long(key: String, def: Long): Long
    fun bool(key: String, def: Boolean): Boolean
    fun float(key: String, def: Float): Float
}

/** 最簡單的實作：記憶體中的鍵值表。 */
class MapConfig(private val values: Map<String, String> = emptyMap()) : Config {

    override fun str(key: String, def: String): String = values[key]?.trim().orEmpty().ifEmpty { def }

    override fun int(key: String, def: Int): Int =
        values[key]?.trim()?.toIntOrNull() ?: def

    override fun long(key: String, def: Long): Long =
        values[key]?.trim()?.toLongOrNull() ?: def

    override fun bool(key: String, def: Boolean): Boolean =
        when (values[key]?.trim()?.lowercase()) {
            TRUE_WORD -> true
            FALSE_WORD -> false
            else -> def
        }

    override fun float(key: String, def: Float): Float =
        values[key]?.trim()?.toFloatOrNull() ?: def

    companion object {
        private const val TRUE_WORD = "true"
        private const val FALSE_WORD = "false"
    }
}

/**
 * TSV 解析器（`key<TAB>value<TAB>說明`）。
 *
 * 兩條防呆（對應 §18.2 的 #2 #3）：
 *  1. 自動剝掉 **UTF-8 BOM**（有 BOM 時第一行註解會失效，已踩過 28 個檔）。
 *  2. 缺欄／空值直接略過，不拋例外——但呼叫端要能用 `validate()` 檢查必要鍵。
 */
object ConfigParser {

    private const val BOM = '\uFEFF'
    private const val MIN_COLUMNS = 2
    private const val COMMENT = '#'

    fun parse(lines: Sequence<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in lines) {
            val line = raw.trimStart(BOM).trim()
            if (line.isEmpty() || line.startsWith(COMMENT)) continue
            val cells = line.split('\t')
            if (cells.size < MIN_COLUMNS) continue
            val key = cells[0].trim()
            val value = cells[1].trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /** 必要鍵缺漏檢查：回傳缺的鍵，呼叫端決定要 fail-fast 還是用預設值。 */
    fun missing(values: Map<String, String>, required: Iterable<String>): List<String> =
        required.filter { values[it].isNullOrBlank() }
}
