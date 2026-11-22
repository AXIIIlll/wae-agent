package com.wae.agent.llm

import android.content.Context
import com.wae.agent.agent.AgentPlan
import com.wae.agent.agent.AgentStep
import com.wae.agent.agent.Planner
import com.wae.agent.core.runtime.Tunables

/**
 * 用內建 Qwen3-0.6B（llama.cpp JNI）產生計畫。
 * - 首次呼叫才載入模型（2–6 秒），失敗回 null（上層可 fallback）。
 * - 所有 prompt 從 assets 模板組裝。
 */
class LlmPlanner(
    private val context: Context,
    private val installedAppsProvider: () -> List<String> = { emptyList() },
    private val nThreads: Int = 4
) : Planner {

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    /** 第一段選出來的 App（由 AgentRunner 設定，第二段規劃會用到）。 */
    @Volatile
    var selectedApp: String? = null

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadAttempted) return false
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            // 首選：直接從 APK 內的 gguf 零拷貝載入（不佔用額外 350MB）
            val afd = GgufModel.openAssetFd(context)
            if (afd != null) {
                val fd = afd.parcelFileDescriptor.fd
                val offset = afd.startOffset
                val ok = runCatching {
                    LlamaBridge.loadFromFd(fd, offset, nCtx = 2048, nThreads = nThreads)
                }.getOrDefault(false)
                runCatching { afd.close() }
                if (ok) {
                    loaded = true
                    android.util.Log.i("WAE_AGENT", "model loaded from APK asset fd (no filesDir copy)")
                    return true
                }
                android.util.Log.w("WAE_AGENT", "asset fd load failed; falling back to filesDir copy")
            }
            val file = GgufModel.ensureAvailable(context) ?: return false
            loaded = LlamaBridge.load(file.absolutePath, nCtx = 2048, nThreads = nThreads)
            return loaded
        }
    }

    fun unload() {
        LlamaBridge.unload()
        loaded = false
    }

    /**
     * 用模型衍生一個 App 的常見中文別名（給 `AliasGenerator` 用）。
     * 只輸出短別名、用逗號分隔；解析失敗回空清單。
     */
    fun generateAliases(appLabel: String): List<String> {
        if (!ensureLoaded()) return emptyList()
        val prompt = "<|im_start|>system\n你只輸出逗號分隔的中文別名，不要解釋。<|im_end|>\n" +
            "<|im_start|>user\n使用者會怎麼稱呼這個 App？輸出 2 到 4 個常見中文別名，逗號分隔。" +
            "App：$appLabel\n/no_think<|im_end|>\n<|im_start|>assistant\n"
        val raw = LlamaBridge.complete(
            prompt, maxTokens = 48,
            temperature = Tunables.getFloat(context, "llm.temperature.extract", 0.0f), topP = 0.9f
        ) ?: return emptyList()
        return raw.replace("<|im_end|>", "")
            .split('，', ',', '、', '|', '\n')
            .map { it.trim().trim('。', '、', '"', '\'', ' ') }
            .filter { it.length in 2..10 && it != appLabel }
            .distinct()
            .take(4)
    }

    /** 第一段：從已安裝清單選 App。回傳 null 代表模型不可用/解析失敗（照舊流程走）。 */
    fun selectApp(goal: String, installedApps: List<String>): AppChoice? {
        if (!ensureLoaded()) return null
        val prompt = PromptBuilder.buildChatSelectAppPrompt(context, goal, installedApps)
        val temp = Tunables.getFloat(context, "llm.temperature.select", 0.1f)
        LlmStats.selectCalls++
        LlmStats.lastSelectTemperature = temp
        val raw = LlamaBridge.complete(prompt, maxTokens = 96, temperature = temp, topP = 0.9f) ?: return null
        android.util.Log.i("WAE_AGENT_SELECT", raw.take(200))
        val parsed = AppChoiceParser.parse(raw)
        if (parsed == null) LlmStats.selectParseFails++
        return parsed
    }

    /** 直接生成（給 OpPlanner 這種「非 JSON 計畫」的輸出路徑用）。 */
    fun completeRaw(prompt: String, maxTokens: Int = 220): String? {
        if (!ensureLoaded()) return null
        return LlamaBridge.complete(
            prompt, maxTokens = maxTokens,
            temperature = com.wae.agent.core.runtime.Tunables.getFloat(context, "llm.temperature.plan", 0.4f),
            topP = 0.9f
        )
    }

    fun isLoaded(): Boolean = loaded

    override fun plan(goal: String): AgentPlan? {
        if (!ensureLoaded()) return null
        val prompt = PromptBuilder.buildChatPlanPrompt(
            context, goal, installedAppsProvider(), selectedApp,
            facts = runCatching { com.wae.agent.gen5.RuleEngineHost.factsText(context) }.getOrDefault(""),
            knowledge = runCatching {
                com.wae.agent.chat.KnowledgeHost.contextFor(context, goal)
            }.getOrDefault("")
        )
        // 320 tokens 足够 6 步以内的计划；原本 512 会让手机上多花 10-20 秒。
        val temp = Tunables.getFloat(context, "llm.temperature.plan", 0.4f)
        LlmStats.planCalls++
        LlmStats.lastPlanTemperature = temp
        val raw = LlamaBridge.complete(prompt, maxTokens = 320, temperature = temp, topP = 0.9f)
        val plan = PlanParser.parse(raw)
        if (plan == null) LlmStats.planParseFails++
        android.util.Log.i(
            "WAE_AGENT_PLAN",
            if (plan == null) "parse failed: " + (raw ?: "").take(200)
            else plan.steps.joinToString(" | ") { it.doText + "/" + (it.target ?: "-") }
        )
        return plan
    }

    override fun replan(goal: String, failedStep: AgentStep?, reason: String): AgentPlan? {
        if (!ensureLoaded()) return null
        val prompt = PromptBuilder.buildChatReplanPrompt(
            context = context,
            goal = goal,
            step = failedStep?.id ?: 0,
            reason = reason
        )
        val raw = LlamaBridge.complete(prompt, maxTokens = 320, temperature = 0.2f, topP = 0.9f)
        return PlanParser.parse(raw)
    }
}

/** 第一段（選 App）的輸出。 */
data class AppChoice(
    val app: String?,
    val ask: String?,
    val options: List<String>
)

/** 解析選 App 的 JSON（容錯：抓第一個 {...}）。 */
object AppChoiceParser {
    fun parse(raw: String?): AppChoice? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = org.json.JSONObject(raw.substring(start, end + 1))
            val app = o.optString("app", "").trim().ifBlank { null }
            val ask = o.optString("ask", "").trim().ifBlank { null }
            val arr = o.optJSONArray("options")
            val options = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) options.add(v)
            }
            if (app == null && ask == null) null else AppChoice(app, ask, options)
        }.getOrNull()
    }
}
