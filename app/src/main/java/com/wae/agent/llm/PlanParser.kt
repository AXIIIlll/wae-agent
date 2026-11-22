package com.wae.agent.llm

import com.wae.agent.agent.AgentPlan
import com.wae.agent.agent.AgentStep
import org.json.JSONObject

/**
 * 把 LLM 輸出解析成 AgentPlan（容忍 markdown fence、前後雜訊）。
 * 解析失敗回 null；不拋例外。
 */
object PlanParser {

    fun parse(raw: String?): AgentPlan? {
        if (raw.isNullOrBlank()) return null
        val json = extractJsonObject(raw) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val stepsArr = obj.optJSONArray("steps")
                ?: obj.optJSONArray("plan")
                ?: return null
            if (stepsArr.length() == 0 || stepsArr.length() > 8) return null
            val steps = ArrayList<AgentStep>()
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.optJSONObject(i) ?: return null
                val doText = s.optString("do").ifBlank { s.optString("action") }
                val verify = s.optString("verify").ifBlank { s.optString("check") }
                if (doText.isBlank() || verify.isBlank()) return null
                val options = ArrayList<String>()
                s.optJSONArray("options")?.let { arr ->
                    for (j in 0 until arr.length()) options.add(arr.optString(j))
                }
                steps.add(
                    AgentStep(
                        id = s.optInt("id", i + 1),
                        doText = doText,
                        target = s.optString("target").ifBlank { null },
                        verify = verify,
                        ask = s.optString("ask").ifBlank { null },
                        options = options,
                        optional = s.optBoolean("optional", false)
                    )
                )
            }
            AgentPlan(
                version = obj.optInt("version", 1),
                goalKind = obj.optString("goalKind").ifBlank { "open_ended" },
                summary = obj.optString("summary"),
                steps = steps
            )
        }.getOrNull()
    }

    /** 從任意文字中抓出第一個看起來完整的 JSON 物件（容忍 ```json fence）。 */
    fun extractJsonObject(raw: String): String? {
        // 移除 Qwen3 的思考區塊（避免裡面的 { 被誤抓）
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return cleaned.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
