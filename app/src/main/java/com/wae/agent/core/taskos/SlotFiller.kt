package com.wae.agent.core.taskos

import android.content.Context
import com.wae.agent.core.data.AssetTable

/**
 * 槽位填充（SlotFiller）：把使用者指令裡的參數抽出來，餵給任務模板。
 *
 * 檔案：`assets/open_source_ai_databases/ui_task_slots.tsv`
 * 格式：`slot<TAB>regex`（regex 必須有第 1 個捕獲組）
 *
 * 刻意保持小而可預測：長任務不需要「大模型 NLU」，
 * 需要的是一組穩定、可測試的抽取器；抽不到就回 null → 上層改成反問使用者
 * （Task OS 的原則：**寧可問，不要猜**）。
 *
 * 純 Kotlin，JVM 可測。
 */
object SlotFiller {

    private const val DB_PATH = "open_source_ai_databases/ui_task_slots.tsv"

    private val slots = LinkedHashMap<String, Regex>()

    private val table = AssetTable(DB_PATH)

    private val DEFAULT_ROWS = listOf(
        "slot\tapp\t(?:打開|開啟|啟動)([^，,。\\s]{1,12})",
        "slot\tquery\t(?:搜尋|搜索|查詢|找)([^，,。\\s]{1,20})",
        "slot\tfirst\t(?:第一個|第一筆|第一項)([^，,。\\s]{0,20})",
        "slot\tversion\t(?:版本號?|version)\\s*[:：]?\\s*([0-9][0-9A-Za-z.\\-_]{0,20})",
        "slot\ttext\t(?:輸入|填寫|填入)([^，,。]{1,40})"
    )

    fun ensureLoaded(context: Context?) {
        loadFromLines(table.lines(context, DEFAULT_ROWS))
    }

    fun loadFromLines(lines: List<String>) {
        val parsed = LinkedHashMap<String, Regex>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 3 || cols[0].trim() != "slot") continue
            val name = cols[1].trim()
            val regex = runCatching { Regex(cols[2].trim()) }.getOrNull() ?: continue
            if (name.isNotEmpty()) parsed[name] = regex
        }
        synchronized(slots) {
            slots.clear()
            slots.putAll(parsed)
        }
    }

    fun resetForTest() {
        synchronized(slots) { slots.clear() }
        table.reset()
    }

    fun count(): Int = synchronized(slots) { slots.size }

    /** 從指令抽取全部槽位；抽到空字串視為沒抽到 */
    fun extract(command: String): Map<String, String> {
        if (command.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val snapshot = synchronized(slots) { LinkedHashMap(slots) }
        for ((name, regex) in snapshot) {
            val m = runCatching { regex.find(command) }.getOrNull() ?: continue
            if (m.groupValues.size < 2) continue
            val value = m.groupValues[1].trim()
            if (value.isNotEmpty()) out[name] = value
        }
        return out
    }
}
