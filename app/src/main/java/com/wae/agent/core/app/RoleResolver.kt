package com.wae.agent.core.app

import com.wae.agent.agent.ObservedElement

/**
 * 通用角色解析：把計畫裡的 `$role:<角色>` 對到畫面上的元素。
 *
 * 角色是跨 App 的抽象（input / first_result / play / cart…），關鍵詞放在
 * `ui_role_keywords.tsv`，所以新增角色或改說法只要改資料，不用改程式。
 *
 * 純函式（只吃 `ObservedElement` 清單），JVM 可測。
 */
object RoleResolver {

    data class Rule(
        val role: String,
        val keywords: List<String>,
        val prefer: String,
        val fallback: String
    )

    private val rules = LinkedHashMap<String, Rule>()

    @Volatile
    var loaded: Boolean = false
        private set

    fun resetForTest() {
        synchronized(rules) { rules.clear() }
        loaded = false
    }

    fun loadFromLines(lines: Sequence<String>) {
        val parsed = LinkedHashMap<String, Rule>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 4) continue
            val role = p[0].trim()
            if (role.isEmpty()) continue
            val keywords = p[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val prefer = p[2].trim().ifEmpty { "clickable" }
            val fallback = p[3].trim().ifEmpty { "scroll" }
            parsed[role] = Rule(role, keywords, prefer, fallback)
        }
        synchronized(rules) {
            rules.clear()
            rules.putAll(parsed)
        }
        loaded = true
    }

    fun ruleFor(role: String): Rule? = synchronized(rules) { rules[role.lowercase()] }

    fun roleCount(): Int = synchronized(rules) { rules.size }

    /**
     * 回傳符合角色的元素 index；找不到回 null（呼叫端依 `fallback` 決定下一步）。
     *
     * 比對順序：
     *   1. prefer=editable → 第一個可編輯且可見的元素
     *   2. 關鍵詞命中（可點優先、再來是任何可見元素）
     */
    fun resolve(role: String, elements: List<ObservedElement>): Int? {
        val rule = ruleFor(role) ?: return null
        val visible = elements.filter { it.visible }

        if (rule.prefer == "editable") {
            visible.firstOrNull { it.editable }?.let { return it.index }
        }
        if (rule.keywords.isNotEmpty()) {
            val hits = visible.filter { e ->
                val t = e.text.lowercase()
                rule.keywords.any { kw -> t.contains(kw.lowercase()) }
            }
            hits.firstOrNull { it.clickable }?.let { return it.index }
            hits.firstOrNull()?.let { return it.index }
        }
        return null
    }

    /** 這個角色的 fallback 是不是「選內容卡片」。 */
    fun isContentFallback(role: String): Boolean = ruleFor(role)?.fallback == "content"
}
