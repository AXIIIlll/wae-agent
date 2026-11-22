package com.wae.agent.chat

import android.content.Context
import android.util.Log
import com.wae.agent.gen5.OpExecutor
import com.wae.core.chat.Citation
import com.wae.core.chat.KnowledgeBase
import com.wae.core.rule.OpCall

/**
 * 模型直接輸出操作（op）的規劃器。
 *
 * 與舊 planner 的差別：
 *  - 輸出不是 JSON 計畫，而是 `OP name(args) #ref=P1` 這種**必須引用檢索結果**的格式
 *  - 沒有合法引用就判錯，不執行（治「查了不用」）
 *  - 每一步都交給 OpExecutor（有前置條件與驗證），不經過自由文字解讀
 *
 * 只在 full 版（有模型）且規則／路由／名詞都接手不了時才會走到這裡。
 */
object OpPlanner {

    private const val TAG = "WAE_PLAN"

    /** 可用的操作（白名單，跟 OpCatalog 對齊；模型只能從這裡挑）。 */
    private val OPS = listOf(
        "INTENT_LAUNCH(app=App名稱)",
        "URI_SEARCH(query=查詢字)",
        "IN_APP_SEARCH(query=查詢字)",
        "INTENT_SEARCH(query=查詢字)",
        "A11Y_CLICK(text=畫面上的文字)",
        "A11Y_SET_TEXT(text=要輸入的字)",
        "SYSTEM_VALUE(control=wifi|bluetooth|brightness|volume, value=on|off|up|down)",
        "PANEL(name=wifi|bluetooth|brightness|volume)",
        "BACK", "HOME", "NOOP(wait=毫秒)"
    )

    data class Result(val ok: Boolean, val summary: String, val steps: List<Citation.Step>)

    private fun buildPrompt(context: Context, utterance: String, knowledge: String): String {
        val facts = runCatching { com.wae.agent.gen5.RuleEngineHost.factsText(context) }.getOrDefault("")
        val opList = OPS.joinToString("\n") { "- $it" }
        val system = """
你是手機操作器。只能輸出「操作清單」，不要解釋。
每一步只能是一種：
  OP <名稱>(<參數>) #ref=<片段編號>
  SEARCH <查詢>
  ASK <要問使用者的話>
  DONE
規則：
1. OP 必須引用上面提供的片段（#ref=P1、P2…），沒有引用就不合法。
2. 只能用下面列出的操作，參數要完整。
3. 不確定就輸出 ASK，不要猜。
4. 最多 4 步。
可用操作：
$opList
""".trimIndent()
        val user = buildString {
            appendLine("使用者說：$utterance")
            if (knowledge.isNotBlank()) {
                appendLine()
                appendLine(knowledge)
            }
            if (facts.isNotBlank()) {
                appendLine()
                appendLine(facts.take(400))
            }
        }
        return "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    /**
     * 規劃並執行。
     *
     * @param knowledge 這次檢索到的片段（含 P1/P2 標記）；引用驗證用它。
     */
    fun planAndRun(context: Context, utterance: String, knowledge: String): Result? {
        val raw = LlmPlannerHolder.complete(context, buildPrompt(context, utterance, knowledge)) ?: return null
        Log.i(TAG, "raw=" + raw.replace("\n", " | ").take(300))
        val refs = Regex("P(\\d+)").findAll(knowledge).map { "P" + it.groupValues[1] }.toSet()
        val parsed = Citation.parse(raw, refs)
        if (!parsed.ok) {
            Log.i(TAG, "rejected: " + parsed.errors.joinToString("; "))
            return null
        }
        val service = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
        if (parsed.steps.any { it.action == "ASK" }) {
            val q = parsed.steps.first { it.action == "ASK" }.payload
            return Result(false, "ASK " + q, parsed.steps)
        }
        if (parsed.steps.none { it.action == "OP" }) return null

        var executed = 0
        for (step in parsed.steps.filter { it.action == "OP" }) {
            val call = toOpCall(step.payload) ?: return Result(false, "無法解析操作：" + step.payload, parsed.steps)
            val ok = runCatching { OpExecutor.runSingle(context, service, call) }.getOrDefault(false)
            if (!ok) return Result(false, "第 ${executed + 1} 步失敗：" + call.id + " " + call.args, parsed.steps)
            executed++
        }
        return Result(true, "OP 計畫完成，共 $executed 步", parsed.steps)
    }

    private fun toOpCall(payload: String): OpCall? {
        val name = payload.substringBefore('(').trim()
        val argsPart = payload.substringAfter('(', "").substringBeforeLast(')')
        val args = argsPart.split(',').mapNotNull { pair ->
            val v = pair.substringAfter('=', "").trim().trim('"', '\'')
            v.ifEmpty { null }
        }
        return OpCall(name, args)
    }

    /** 自己的 LlmPlanner 實例（惰性建立；lite 版沒有模型時 completeRaw 會回 null）。 */
    private object LlmPlannerHolder {
        private var planner: com.wae.agent.llm.LlmPlanner? = null

        fun complete(context: Context, prompt: String): String? = runCatching {
            val p = planner ?: com.wae.agent.llm.LlmPlanner(context).also { planner = it }
            p.completeRaw(prompt)
        }.getOrNull()
    }

    fun contextFor(context: Context, utterance: String): String =
        runCatching { KnowledgeBase.render(KnowledgeHost.base(context).retrieve(utterance, 3)) }.getOrDefault("")
}
