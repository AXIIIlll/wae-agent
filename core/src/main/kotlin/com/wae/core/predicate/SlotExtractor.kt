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
                // 大小寫不敏感：「在chrome搜尋貓咪」的 App 名是小寫，
                // 但資料裡的標籤是「Chrome」；用大小寫敏感比對會剪不到，
                // 整句就被當成查詢字串送出去。
                if (w.isNotEmpty() && content.length > w.length && content.startsWith(w, ignoreCase = true)) {
                    content = content.substring(w.length).trim()
                    changed = true
                }
            }
        }
        // App 名稱可以在任何位置出現，安全地全域移除（同樣不分大小寫）
        for (name in knownNames) {
            if (name.isEmpty()) continue
            content = content.replace(Regex(Regex.escape(name), RegexOption.IGNORE_CASE), " ")
        }
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

        // ④ **只取第一個動作**：後面接的「並打開第一個結果」是下一個動作，不是查詢字。
        //    實測：「在 Chrome 搜尋今天天氣並打開第一個結果」曾把整串丟去搜尋。
        //    切點來自資料：SEPARATOR（然後／接著／再…）與 CONJUNCTION（並／並且…）。
        val cutMarkers = LanguageWords.separators() + LanguageWords.conjunctions()
        val cut = cutMarkers
            .mapNotNull { m -> content.indexOf(m).takeIf { it > 0 } }
            .minOrNull()
        if (cut != null) content = content.substring(0, cut).trim()

        // ⑤ 功能詞不是內容：介詞（在／到／進）與方位助詞（中／裡／内）只是位置標記。
        //    只丟「獨立成詞」的功能詞，避免把「中壢」「內湖」這種地名切壞。
        val functionWords = (LanguageWords.prefixes() + LanguageWords.suffixes()).toSet()
        if (functionWords.isNotEmpty()) {
            content = content.split(' ')
                .filter { it.isNotEmpty() && it !in functionWords }
                .joinToString(" ")
                .trim()
        }
        content = content.replace(Regex("\\s+"), " ")
            .trim()
            .trim('，', ',', '。', '的', '、')

        // ⑥ 最後再剪一次「句首單字動詞」：介詞被拿掉之後動詞才露出來
        //    （「在時鐘看世界時間」→ 去掉「在／時鐘」後剩下「看世界時間」）。
        var trimmed = true
        while (trimmed) {
            trimmed = false
            for (v in verbs().filter { it.length == 1 }) {
                if (content.startsWith(v) && content.length > v.length) {
                    content = content.removePrefix(v).trim()
                    trimmed = true
                }
            }
        }

        // ⑥.2 去掉開頭的「的／之」：「打開 Chrome 的歷史紀錄」→ content 會是「的歷史紀錄」
        while (content.startsWith("的") || content.startsWith("之")) {
            content = content.substring(1).trim()
        }

        // ⑥.5 畫面用詞同義對照：「看世界時間」→ 時鐘的頁籤其實叫「世界時鐘」。
        //      只做整串相等比對，避免把「儲存空間」這種前綴關係誤代換。
        LanguageWords.labelSynonyms()[content]?.let { canonical ->
            content = canonical
        }

        // ⑦ 去掉尾端的「容器詞」：使用者說「查看鬧鐘清單」，但畫面上的頁籤只寫「鬧鐘」。
        //    容器詞來自資料（CONTAINER），不是程式內建清單；只去掉尾端、只去一次。
        for (w in LanguageWords.containerWords()) {
            if (content.endsWith(w) && content.length > w.length + 1) {
                content = content.removeSuffix(w).trim()
                break
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
