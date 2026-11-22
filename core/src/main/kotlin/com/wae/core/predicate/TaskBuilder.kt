package com.wae.core.predicate

/**
 * **句子 → 任務樹**的極簡版（只處理「開 App + 後續動作」）。
 *
 * 例：「打開地圖搜尋臺北101並點擊路線」
 *   → Seq(LAUNCH(地圖), FIND(臺北101), PICK(臺北101), PICK(路線))
 *
 * 為什麼只做這一種：這是目前**唯一會讓整句被吃掉**的情況
 * （以「打開」開頭 → 被編譯成 NAVIGATE → 交回舊管線 → 只開 App 就結束）。
 *
 * 動作詞全部來自 `ui_language_words.tsv`（kind=TASKVERB），程式不內建任何詞。
 */
object TaskBuilder {

    /**
     * @return 任務樹；不是「開 App + 後續」的句子就回 null（交給原本的流程）
     */
    fun fromCommand(command: String): TaskNode? {
        val text = command.trim()
        if (text.isEmpty()) return null

        // 找第一個動作詞（搜尋／點擊／導航…），它之前是「開 App」，之後是「要做的事」
        val verbs = LanguageWords.taskVerbs()
        if (verbs.isEmpty()) return null
        var cutAt = -1
        var verb = ""
        for (v in verbs) {
            val i = text.indexOf(v)
            if (i > 0) {
                cutAt = i
                verb = v
                break
            }
        }
        if (cutAt <= 0) return null

        val head = text.substring(0, cutAt).trim()
        val app = stripOpenWord(head)
        if (app.isEmpty()) return null

        val tail = text.substring(cutAt + verb.length).trim()
        if (tail.isEmpty()) return null

        // 尾段可能還有「並點選路線」這種後續動作 → 用資料裡的連接詞切開
        val segments = splitByVerbs(tail)
        val query = segments.firstOrNull().orEmpty()
        if (query.isEmpty()) return null

        val steps = ArrayList<TaskNode>()
        steps.add(TaskNode.Leaf(TaskNode.Op.LAUNCH, app))

        // 搜尋 → 打關鍵字 + 選第一個結果
        if (LanguageWords.searchWords().any { verb.contains(it) || it.contains(verb) }) {
            steps.add(TaskNode.Leaf(TaskNode.Op.FIND, query))
            steps.add(TaskNode.Leaf(TaskNode.Op.PICK, query))
        } else {
            steps.add(TaskNode.Leaf(TaskNode.Op.PICK, query))
        }

        // 後續的每一段都是一個「點某個東西」
        for (rest in segments.drop(1)) {
            val cleaned = stripActionVerb(rest)
            if (cleaned.isNotEmpty()) steps.add(TaskNode.Leaf(TaskNode.Op.PICK, cleaned))
        }
        return TaskNode.Seq(steps)
    }


    /**
     * **指標切分**（不是連接詞切分）：以「動作詞」的出現位置當切點。
     *
     * 好處：不需要知道「並／然後／再」這些連接詞，
     * 只要知道哪些詞是動作（`TASKVERB`，骨架選擇本來就需要它）。
     *
     * 「臺北101並點選路線」→ [臺北101並, 路線]
     * 尾端殘留的連接字（並）**不特別處理**——交給下游的模糊比對吸收。
     */
    private fun splitByVerbs(tail: String): List<String> {
        val verbs = LanguageWords.taskVerbs()
        if (verbs.isEmpty()) return listOf(tail)

        // 找出所有動作詞的起點（長詞優先，避免重疊）
        val cuts = ArrayList<Int>()
        var i = 0
        while (i < tail.length) {
            var matched = 0
            for (v in verbs) {
                if (v.isNotEmpty() && tail.startsWith(v, i)) {
                    matched = maxOf(matched, v.length)
                }
            }
            if (matched > 0) {
                cuts.add(i)
                i += matched
            } else {
                i += 1
            }
        }
        if (cuts.isEmpty()) return listOf(tail)

        val out = ArrayList<String>()
        // 第一個動作詞之前（通常是空的，因為搜尋就是第一個動作）
        if (cuts[0] > 0) out.add(tail.substring(0, cuts[0]).trim())
        for (k in cuts.indices) {
            val start = cuts[k]
            val end = if (k + 1 < cuts.size) cuts[k + 1] else tail.length
            val seg = tail.substring(start, end).trim()
            if (seg.isNotEmpty()) out.add(seg)
        }
        return out.map { stripActionVerb(it) }.filter { it.isNotEmpty() }
    }

    /** 去掉動作詞開頭（「點選路線」→「路線」）。 */
    private fun stripActionVerb(s: String): String {
        var out = s.trim()
        for (v in LanguageWords.taskVerbs()) {
            if (out.startsWith(v) && out.length > v.length) out = out.removePrefix(v).trim()
        }
        return out
    }

    /** 去掉開頭的「打開／開啟／開」。 */
    private fun stripOpenWord(head: String): String {
        for (w in LanguageWords.openWords()) {
            if (head.startsWith(w)) return head.removePrefix(w).trim()
        }
        return head
    }
}
