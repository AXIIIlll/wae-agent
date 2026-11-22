package com.wae.agent.llm

import android.content.Context

/** 從 assets 讀 prompt 模板並組裝（程式碼不含任何 App 名稱）。 */
object PromptBuilder {

    fun buildPlanPrompt(context: Context, goal: String, installedApps: List<String>): String {
        val system = readAsset(context, "agent/prompts/plan_system.txt", DEFAULT_SYSTEM)
        val user = readAsset(context, "agent/prompts/plan_user.txt", DEFAULT_USER)
            .replace("{goal}", goal)
            .replace("{selectedApp}", "（無）")
            .replace("{installedApps}", installedApps.take(30).joinToString(", "))
        return system + "\n" + user
    }

    /** Qwen3 對話格式的規劃 prompt。 */
    fun buildChatPlanPrompt(
        context: Context,
        goal: String,
        installedApps: List<String>,
        selectedApp: String? = null,
        /** 裝置事實（App 數、分類、權限、能力表）；空白就不加。 */
        facts: String = "",
        /** 每次自動檢索到的相關資料（目的／操作／App／修正）；空白就不加。 */
        knowledge: String = ""
    ): String {
        val system = readAsset(context, "agent/prompts/plan_system.txt", DEFAULT_SYSTEM)
        val user = readAsset(context, "agent/prompts/plan_user.txt", DEFAULT_USER)
            .replace("{goal}", goal)
            .replace("{selectedApp}", selectedApp?.takeIf { it.isNotBlank() } ?: "（無）")
            .replace("{installedApps}", installedApps.take(30).joinToString(", ")) +
            com.wae.core.chat.FactsBlock.wrap(facts) +
            (if (knowledge.isBlank()) "" else "\n\n$knowledge")
        return wrapChat(system, user)
    }

    /** 兩段式規劃的第一段：從已安裝清單選 App（輸出很小，速度快）。 */
    fun buildChatSelectAppPrompt(context: Context, goal: String, installedApps: List<String>): String {
        val system = readAsset(context, "agent/prompts/select_app.txt", "")
        val body = system
            .replace("{goal}", goal)
            .replace("{installedApps}", installedApps.take(30).joinToString(", "))
        return wrapChat("", body)
    }

    /** Qwen3 對話格式的重規劃 prompt。 */
    fun buildChatReplanPrompt(context: Context, goal: String, step: Int, reason: String): String {
        val system = readAsset(context, "agent/prompts/plan_system.txt", DEFAULT_SYSTEM)
        val user = readAsset(context, "agent/prompts/replan.txt", DEFAULT_REPLAN)
            .replace("{step}", step.toString())
            .replace("{reason}", reason.take(300))
            .replace("{goal}", goal)
        return wrapChat(system, user)
    }

    private fun wrapChat(system: String, user: String): String =
        "<|im_start|>system\n" + system + "<|im_end|>\n" +
            "<|im_start|>user\n" + user + "<|im_end|>\n" +
            "<|im_start|>assistant\n"

    fun buildReplanPrompt(
        context: Context,
        goal: String,
        step: Int,
        reason: String,
        observation: String
    ): String {
        return readAsset(context, "agent/prompts/replan.txt", DEFAULT_REPLAN)
            .replace("{step}", step.toString())
            .replace("{reason}", reason)
            .replace("{observation}", observation.take(1500))
            .replace("{goal}", goal)
    }

    private fun readAsset(context: Context, path: String, fallback: String): String =
        runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrDefault(fallback)

    private const val DEFAULT_SYSTEM =
        "你是手機代理人的規劃器。只輸出 JSON 計畫；不要指定特定 App；需要時加 ask 步驟。"
    private const val DEFAULT_USER = "目標：{goal}\n已安裝 App：{installedApps}\n/no_think"
    private const val DEFAULT_REPLAN =
        "第 {step} 步失敗：{reason}\n畫面：{observation}\n請重新輸出計畫 JSON。\n/no_think"
}
