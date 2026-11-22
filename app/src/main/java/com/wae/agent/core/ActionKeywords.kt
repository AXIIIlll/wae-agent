package com.wae.agent.core

/**
 * 基礎動作的中文觸發詞（語言層資料，**不含任何 App 資料**）。
 *
 * 目的：讓使用者能用自然語言直接呼叫六個補齊的基礎動作，
 * 例如「往上滑」「長按這個」「清除搜尋框」「送出」。
 *
 * 與 `command_lexicon.tsv` 的關係：該詞庫提供既有的動作詞（開/點/搜尋…），
 * 這裡補的是新動作的觸發詞；之後可整併進詞庫（欄位格式已預留）。
 */
object ActionKeywords {

    data class Hit(val kind: ActionKind, val payload: String = "")

    private val table: List<Pair<List<String>, ActionKind>> = listOf(
        listOf("往上滑", "向上滑", "上滑", "回頂", "回到頂部", "往上滾") to ActionKind.SCROLL_UP,
        listOf("左滑", "右滑", "往左滑", "往右滑", "橫向滑", "横向滑", "滑動切換", "滑动切换") to
            ActionKind.SWIPE_HORIZONTAL,
        listOf("長按", "长按", "長壓", "长压", "按住") to ActionKind.LONG_PRESS,
        listOf("送出", "提交", "確認搜尋", "确认搜索", "直接搜尋", "直接搜索") to ActionKind.SUBMIT,
        listOf("清除輸入", "清空輸入", "清除搜尋", "清空搜尋", "清除内容", "清除內容", "清空") to
            ActionKind.CLEAR_TEXT,
        listOf("替換成", "替换成", "改成", "改為", "改为", "重輸", "重输") to ActionKind.REPLACE_TEXT
    )

    /** 依指令片段判斷是否為新動作；REPLACE_TEXT 會把後面的文字當參數 */
    fun detect(segment: String): Hit? {
        // 數值調整優先：它同時帶「方向/幅度」語意，先攔下來才不會被當成一般點擊。
        // 但只有「強證據」才算（動詞方向、動詞+數字、最大/最小/一半），
        // 單獨出現的百分比（50%折扣）不足以證明使用者想調數值。
        detectValueAdjustment(segment)?.let { return it }

        val lower = segment.lowercase()
        for ((keywords, kind) in table) {
            for (kw in keywords) {
                val idx = lower.indexOf(kw)
                if (idx < 0) continue
                val payload = if (kind == ActionKind.REPLACE_TEXT) {
                    segment.substring(idx + kw.length).trim().trim('，', '。', ' ', ',', '.')
                } else {
                    ""
                }
                return Hit(kind, payload)
            }
        }
        return null
    }

    private fun detectValueAdjustment(segment: String): Hit? {
        val intent = com.wae.agent.core.component.ValueIntent.parse(segment)
        if (!intent.requested) return null
        val strongEvidence = intent.direction != 0 ||
            intent.targetRaw != null ||
            intent.fractionSource == "percent+verb" ||
            intent.fractionSource == "word"
        if (!strongEvidence) return null
        return Hit(ActionKind.ADJUST_VALUE, intent.targetNoun)
    }
}
