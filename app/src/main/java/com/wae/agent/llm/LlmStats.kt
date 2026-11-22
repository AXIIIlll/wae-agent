package com.wae.agent.llm

/**
 * LLM 呼叫統計（給 `!llm` 看）。
 *
 * 為什麼要這個：溫度、取樣次數、解析失敗率如果沒有數字，
 * 「調溫度有沒有變好」就只能靠感覺。
 */
object LlmStats {
    @Volatile var planCalls: Int = 0
    @Volatile var planParseFails: Int = 0
    @Volatile var selectCalls: Int = 0
    @Volatile var selectParseFails: Int = 0
    @Volatile var lastPlanTemperature: Float = -1f
    @Volatile var lastSelectTemperature: Float = -1f

    fun summary(): String {
        val planRate = if (planCalls == 0) "n/a" else "%d/%d".format(planCalls - planParseFails, planCalls)
        val selectRate = if (selectCalls == 0) "n/a" else "%d/%d".format(selectCalls - selectParseFails, selectCalls)
        return "LLM plan=$planCalls(ok=$planRate,temp=$lastPlanTemperature) " +
            "select=$selectCalls(ok=$selectRate,temp=$lastSelectTemperature)"
    }
}
