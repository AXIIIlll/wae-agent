package com.wae.core.plan

/**
 * 切詞器（L0）——最長匹配，詞典由呼叫端提供（來自 `command_lexicon.tsv`）。
 *
 * 為什麼需要：
 *  - 我們現在是靠字串剝除（replace/removePrefix）處理句子，遇到「打開的歷史紀錄」這種
 *    就會把動詞和受詞黏在一起，切出碎片。
 *  - 中文沒有空白，真正該做的是「用詞典做最長匹配」，把句子切成有意義的詞。
 *
 * 設計原則：
 *  - **不內建任何詞**（AGENTS §4）：詞典完全由呼叫端傳入。
 *  - 詞典空的時候退化為「單字切成 token」，至少不會爆掉。
 *  - 保持原文順序與索引，方便上層把 token 對回原句（例如標出「哪一段是目標物」）。
 */
object Tokenizer {

    /** 一個詞：文字、在原文的起始索引、長度、是否來自詞典。 */
    data class Token(val text: String, val start: Int, val end: Int, val fromDictionary: Boolean) {
        fun isWord(): Boolean = text.isNotBlank()
    }

    /**
     * 最長匹配切詞。
     *
     * @param text 原始句子
     * @param dictionary 詞典（呼叫端提供；越長越好，內部會依長度排序）
     * @param maxWordLen 詞典中最長詞的長度上限（避免每次掃太長）
     */
    fun segment(text: String, dictionary: Collection<String>, maxWordLen: Int = 8): List<Token> {
        if (text.isEmpty()) return emptyList()
        val dict = dictionary.filter { it.isNotBlank() }.toSet()
        val maxLen = maxWordLen.coerceIn(1, 16)
        val out = ArrayList<Token>(text.length)
        var i = 0
        while (i < text.length) {
            var matched: String? = null
            var len = minOf(maxLen, text.length - i)
            while (len >= 1) {
                val sub = text.substring(i, i + len)
                if (sub in dict) {
                    matched = sub
                    break
                }
                len--
            }
            if (matched != null) {
                out.add(Token(matched, i, i + matched.length, true))
                i += matched.length
            } else {
                // 詞典沒有 → 單字成 token（標記為非詞典來源）
                val ch = text.substring(i, i + 1)
                out.add(Token(ch, i, i + 1, false))
                i += 1
            }
        }
        return out
    }

    /** 只取文字（除錯與比對用）。 */
    fun words(text: String, dictionary: Collection<String>): List<String> =
        segment(text, dictionary).map { it.text }

    /**
     * 找出「詞典詞」涵蓋了句子的哪些部分——用來判斷一句話是否大部分都由已知詞組成。
     * 回傳 0..1：已知詞字元數 / 總字元數（忽略空白）。
     */
    fun coverage(text: String, dictionary: Collection<String>): Float {
        val tokens = segment(text, dictionary)
        val total = tokens.sumOf { it.end - it.start }
        if (total == 0) return 0f
        val known = tokens.filter { it.fromDictionary }.sumOf { it.end - it.start }
        return known.toFloat() / total
    }
}
