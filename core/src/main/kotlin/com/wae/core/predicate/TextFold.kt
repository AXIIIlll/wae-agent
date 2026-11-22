package com.wae.core.predicate

/**
 * 文字折疊：讓「Wi-Fi」「wifi」「WiFi」「ｗｉｆｉ」在比對時視為同一個詞。
 *
 * 規則（刻意極簡，避免變成另一套詞表）：
 *  1. 全形 → 半形
 *  2. 小寫
 *  3. 只留字母、數字、CJK（去掉空白、連字號、標點、`+`）
 *
 * 這樣「Disney+」→「disney」、「Wi-Fi」→「wifi」、「藍牙 / 藍芽」→ 由繁簡與 ASR 表處理。
 */
object TextFold {

    fun fold(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch in '０'..'９' -> '0' + (ch - '０')
                ch in 'Ａ'..'Ｚ' -> 'a' + (ch - 'Ａ')
                ch in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                else -> ch
            }
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c.code in 0x4E00..0x9FFF -> sb.append(c)
                c.code in 0x3400..0x4DBF -> sb.append(c)
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** 折疊後是否包含（兩邊都折疊）。 */
    fun containsFold(haystack: String, needle: String): Boolean {
        val h = fold(haystack)
        val n = fold(needle)
        return n.isNotEmpty() && h.contains(n)
    }
}
