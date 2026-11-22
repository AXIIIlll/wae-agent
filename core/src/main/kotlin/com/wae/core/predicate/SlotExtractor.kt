package com.wae.core.predicate

/**
 * **槽位抽取**（單 App 範圍）：把一句話拆成「時間 / 對象 / 內容」。
 *
 * 方法全部是**減法與標記**，不列舉名詞表：
 *  - `TIME`：時間詞的正則（今天|明天|上午|…|N點|半）
 *  - `PERSON`：介詞標記（給／對／跟／傳給）之後、到下一個動詞或標點之前的片段
 *  - `CONTENT`：把已辨識的片段（開頭動詞、App 名、動作詞、時間、對象）**剪掉**後剩下的
 *
 * 這與「動作詞指標切分」是同一個思路：**用已知片段定位，剩下的就是未知內容**。
 */
object SlotExtractor {

    data class Slots(
        val time: String? = null,
        val person: String? = null,
        val content: String? = null
    )

    // 數字含中文（九點）與阿拉伯（9點 / 9:30）
    // 時間：中文數字（九點）與阿拉伯數字（9點 / 9:30）都支援
    // 時間：中文數字（九點）與阿拉伯數字（9點 / 9:30）都支援
    private val TIME_REGEX = Regex(
        "((今天|明天|後天|昨天|今晚|早上|上午|中午|下午|晚上|凌晨)?" +
            "([0-9]{1,2}|[一二三四五六七八九十]{1,3})[點时時]半?)" +
            "|([0-9]{1,2}:[0-9]{2})" +
            "|(今天|明天|後天|昨天|今晚|早上|上午|中午|下午|晚上|凌晨)"
    )

    /** 介詞標記：之後接的是「對象」。 */
    private val PERSON_MARKERS = listOf("傳給", "分享給", "發給", "寄給", "給", "對", "跟", "找")

    /** 動作詞（資料驅動）＋ 開 App 動詞。 */
    private fun verbs(): List<String> = LanguageWords.taskVerbs() + LanguageWords.openWords()

    /**
     * @param text 原始指令
     * @param knownNames 已知的 App 名稱（用來從 CONTENT 中剪掉）
     */
    fun extract(text: String, knownNames: List<String> = emptyList()): Slots {
        var rest = text.trim()
        if (rest.isEmpty()) return Slots()

        // ① 時間
        var time: String? = null
        TIME_REGEX.find(rest)?.let { m ->
            time = m.value.trim()
            rest = rest.removeRange(m.range).trim()
        }

        // ② 對象：介詞之後、到下一個動詞/標點之前
        var person: String? = null
        for (marker in PERSON_MARKERS) {
            val idx = rest.indexOf(marker)
            if (idx < 0) continue
            val after = rest.substring(idx + marker.length)
            val end = firstVerbIndex(after).let { if (it < 0) after.length else it }
            val candidate = after.substring(0, end)
                .trim()
                .trimEnd('，', ',', '。', '！', '!', '的')
            if (candidate.isNotEmpty() && candidate.length <= 8) {
                person = candidate
                rest = (rest.substring(0, idx) + rest.substring(idx + marker.length + end)).trim()
                break
            }
        }

        // ③ 內容：剪掉動詞、App 名、殘留介詞後的剩餘
        var content = rest
        // **依詞長分級**（通用規則，不是名詞表）：
        //  - 多字動作詞（搜尋／導航到／點擊…）→ 任何位置都可安全移除
        //  - 單字動詞（開／關）→ 只移除句首的那一個，避免吃掉「開會」的「開」
        val multiCharVerbs = verbs().filter { it.length >= 2 }.sortedByDescending { it.length }
        for (v in multiCharVerbs) content = content.replace(v, " ")
        content = content.replace(Regex("\\s+"), " ").trim()
        val prefixWords = (verbs().filter { it.length == 1 } + knownNames).sortedByDescending { it.length }
        var changed = true
        while (changed) {
            changed = false
            for (w in prefixWords) {
                if (w.isNotEmpty() && content.startsWith(w) && content.length > w.length) {
                    content = content.removePrefix(w).trim()
                    changed = true
                }
            }
        }
        // App 名稱可以在任何位置出現，安全地全域移除
        for (name in knownNames) content = content.replace(name, " ")
        for (m in PERSON_MARKERS) content = content.replace(m, " ")
        content = content.replace(Regex("\\s+"), " ")
            .trim()
            .trim('，', ',', '。', '的', '、')

        // 去掉開頭的結構助詞（把／將／幫我／請…），來源是 LanguageWords.leads（資料）
        for (lead in LanguageWords.leads().sortedByDescending { it.length }) {
            if (content.startsWith(lead) && content.length > lead.length) {
                content = content.removePrefix(lead).trim()
            }
        }

        return Slots(
            time = time,
            person = person,
            content = content.ifBlank { null }
        )
    }

    private fun firstVerbIndex(s: String): Int {
        var best = -1
        for (v in verbs()) {
            val i = s.indexOf(v)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }
}
