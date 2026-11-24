package com.wae.core.plan

/**
 * 計畫變數（L2）：把「這一步拿到的東西」留給後面的步驟用。
 *
 * 為什麼需要：多步任務（例如「搜尋後打開第一個結果，再回到清單」）必須記住中間結果。
 * 我們在 EXTREME 題目上失敗，很大一部分是每一步都從零開始猜。
 *
 * 語法很簡單：步驟的 target／arg 可以寫 `$name`，執行前會被替換成已捕捉到的值。
 * 變數本身是純資料，可寫進 checkpoint、也可以寫進軌跡。
 */
class PlanVars(initial: Map<String, String> = emptyMap()) {

    private val values = LinkedHashMap<String, String>(initial)

    operator fun get(name: String): String? = values[name.removePrefix("$")]

    fun put(name: String, value: String) {
        val key = name.removePrefix("$").trim()
        if (key.isEmpty() || value.isBlank()) return
        values[key] = value
    }

    /** 把 `$name` 換成實際值；找不到的變數保持原樣（讓上層看得出來「這裡缺資料」）。 */
    fun substitute(text: String): String {
        if (text.isEmpty() || values.isEmpty() || !text.contains('$')) return text
        var out = text
        for ((k, v) in values) {
            out = out.replace("$$k", v)
        }
        return out
    }

    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    fun isEmpty(): Boolean = values.isEmpty()

    override fun toString(): String = values.entries.joinToString(", ") { "${it.key}=${it.value}" }
}
