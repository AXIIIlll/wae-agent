package com.wae.core.chat

/**
 * 模型輸出的嚴格格式：每一步都必須引用檢索到的片段。
 *
 * 為什麼：小模型常常「查了不用」。把引用寫進格式並驗證，
 * 就沒有「用了卻沒根據」的輸出——這是結構約束，不是提示詞。
 */
object Citation {

    data class Step(val action: String, val payload: String, val ref: String?)

    data class Parsed(val steps: List<Step>, val errors: List<String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /** 例：`OP URI_SEARCH(query=餐廳) #ref=P1`、`ASK 要訂哪一家？`、`DONE` */
    private val OP_RE = Regex("^OP\\s+([A-Z_]+)\\((.*)\\)\\s*(?:#ref=(\\w+))?\\s*$")
    private val ASK_RE = Regex("^ASK\\s+(.+)$")
    private val DONE_RE = Regex("^DONE\\s*$")
    private val SEARCH_RE = Regex("^SEARCH\\s+(.+)$")

    fun parse(raw: String, allowedRefs: Set<String>): Parsed {
        val steps = ArrayList<Step>()
        val errors = ArrayList<String>()
        for (line in raw.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                OP_RE.matches(line) -> {
                    val m = OP_RE.find(line)!!
                    val ref = m.groupValues[3].ifEmpty { null }
                    if (ref == null) {
                        errors.add("這個操作沒有引用檢索結果：# $line")
                    } else if (ref !in allowedRefs) {
                        errors.add("引用了不存在的片段 $ref：$line")
                    } else {
                        steps.add(Step("OP", m.groupValues[1] + "(" + m.groupValues[2] + ")", ref))
                    }
                }
                ASK_RE.matches(line) -> steps.add(Step("ASK", ASK_RE.find(line)!!.groupValues[1], null))
                SEARCH_RE.matches(line) -> steps.add(Step("SEARCH", SEARCH_RE.find(line)!!.groupValues[1], null))
                DONE_RE.matches(line) -> steps.add(Step("DONE", "", null))
                else -> errors.add("看不懂這一行：$line")
            }
        }
        if (steps.isEmpty() && errors.isEmpty()) errors.add("沒有任何步驟")
        return Parsed(steps, errors)
    }
}
