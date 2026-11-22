package com.wae.agent.core

import android.util.Log
import com.wae.agent.agent.AgentAction
import com.wae.agent.agent.AgentDecision
import com.wae.agent.agent.AgentExecutor
import com.wae.agent.agent.AgentStep
import com.wae.agent.agent.DecisionResolver
import com.wae.agent.agent.Observation
import com.wae.agent.agent.Observer

/**
 * **無 LLM 的逐元素決策迴圈**（把「畫面裡每個按鈕」交給打分器決定）。
 *
 * 為什麼要有這個：0.6B GGUF 佔 APK 378MB。多數指令其實不需要「生成計畫」，
 * 只需要「在目前畫面上找出最該動的那個元素」。這個迴圈就是：
 *
 * ```
 * 觀察畫面 → 為每個元素打分（語意/角色/決策頭）→ 選最高分動作
 *   → 執行 → 看畫面有沒有變化 → 重複（最多 N 步）
 * ```
 *
 * 它不生成任何文字，所以不需要 LLM、不需要 tokenizer、不需要 378MB 的模型。
 *
 * 終止條件：
 *  - 畫面上出現指令裡的主要名詞（`goalReached`）→ SUCCESS
 *  - 決策器要求反問 → ASKING
 *  - 連續兩次動作後畫面沒變 → FAIL（no progress）
 *  - 步數用完 → FAIL
 */
class ElementPlanner(
    private val observer: Observer,
    private val resolver: DecisionResolver,
    private val executor: AgentExecutor,
    private val maxSteps: Int = 8
) {

    data class Result(
        val success: Boolean,
        val needsAsk: Boolean,
        val steps: Int,
        val reason: String
    )

    fun run(goal: String): Result {
        var lastSignature = ""
        var stagnant = 0
        for (i in 1..maxSteps) {
            val obs = observer.observe()
            if (goalReached(goal, obs)) {
                return Result(true, false, i - 1, "goal text visible")
            }
            val sig = signature(obs)
            if (sig == lastSignature) {
                stagnant++
                if (stagnant >= 2) return Result(false, false, i - 1, "no progress")
            } else {
                stagnant = 0
                lastSignature = sig
            }

            // 把「目標」當成這一步要動的對象：決策器會比對元素文字與目標、找可點的、
            // 找不到就捲動；這就是「逐元素決策」的全部。
            val step = AgentStep(id = i, doText = goal, target = goal, verify = "")
            val decision = resolver.decide(step, obs, emptyMap())
            when (decision.action) {
                AgentAction.DONE -> return if (goalReached(goal, obs)) {
                    Result(true, false, i - 1, "goal text visible")
                } else {
                    // 決策器說「沒事了」但目標字串沒出現在畫面上 → 我們其實不知道要做什麼，
                    // 交給上層反問，不要假裝成功。
                    Result(false, true, i - 1, "resolver done but goal not visible")
                }
                AgentAction.ASK_USER -> return Result(false, true, i - 1, "need ask")
                AgentAction.SCROLL, AgentAction.TAP, AgentAction.TYPE, AgentAction.OPEN_APP -> {
                    val ok = executor.execute(decision)
                    runCatching {
                        Log.i(
                            "WAE_ELEMENT",
                            "step=$i action=${decision.action} idx=${decision.index} ok=$ok " +
                                "conf=${decision.confidence} reason=${decision.reason}"
                        )
                    }
                    if (!ok) return Result(false, false, i, "execute failed")
                }
                else -> {
                    // BACK / WAIT 之類：執行後繼續
                    executor.execute(decision)
                }
            }
        }
        return Result(false, false, maxSteps, "steps exhausted")
    }

    /**
     * 「目標達成了嗎」：指令裡的主要名詞（去動詞、去虛詞後 ≥2 字的片段）出現在畫面上。
     *
     * 這不是完美的判定，但它是**零模型**的：只用字串包含，不需要 LLM 做 verify 推理。
     */
    fun goalReached(goal: String, obs: Observation): Boolean {
        val texts = buildList {
            add(obs.title)
            obs.elements.forEach { add(it.text) }
            obs.ocrTexts.forEach { add(it) }
        }
        if (texts.isEmpty()) return false
        val keywords = goalKeywords(goal)
        if (keywords.isEmpty()) return false
        var hits = 0
        for (k in keywords) {
            if (texts.any { it.contains(k) }) hits++
        }
        // 至少命中一半的關鍵詞才判定完成，避免只看到一個字就誤判
        return hits > 0 && hits * 2 >= keywords.size
    }

    private fun signature(obs: Observation): String =
        obs.appPackage + "|" + obs.title + "|" + obs.elements.take(8).joinToString(",") { it.text }

    companion object {
        /** 去掉常見動詞與虛詞，留下「主要名詞」給完成判定用。 */
        fun goalKeywords(goal: String): List<String> {
            val stop = listOf(
                "我想", "我要", "幫我", "請", "麻煩", "一下", "這個", "那個", "然後", "可以",
                "打開", "開啟", "啟動", "關掉", "關閉", "播放", "查詢", "查一下", "看一下", "看看"
            )
            var t = goal.trim()
            for (s in stop) t = t.replace(s, "")
            t = t.replace(Regex("[\\s\\p{Punct}，。！？、]"), "")
            return t.split("的")
                .flatMap { chunk -> chunk.chunked(2).take(3) }
                .filter { it.length >= 2 }
                .distinct()
        }
    }
}
