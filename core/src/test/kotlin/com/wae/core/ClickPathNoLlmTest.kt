package com.wae.core

import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 結構守門：**點擊路徑不得引用大語言模型**（三層兩憶計畫 §6 鐵律）。
 *
 * 為什麼要用「掃原始碼」而不是行為測試：
 * 行為測試只能證明「這次沒有呼叫」，但未來的重構可能偷偷加一行 `LlmPlanner(...)`。
 * 掃描是**編譯期就能看見的界線**——加了就紅。
 */
class ClickPathNoLlmTest {

    private val forbidden = listOf("LlmPlanner", "Qwen", "LlamaBridge", "llama", "gguf")

    private fun readSource(relative: String): String? {
        val candidates = listOf(
            File("../app/src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("../../app/src/main/java/$relative")
        )
        return candidates.firstOrNull { it.exists() }?.readText(Charsets.UTF_8)
    }

    @Test
    fun `click resolver and step actor never touch an llm`() {
        val files = listOf(
            "com/wae/agent/gen5/StepActor.kt",
            "com/wae/agent/gen5/VagueRunner.kt"
        )
        var checked = 0
        for (file in files) {
            val text = readSource(file)
            if (text == null) continue
            checked++
            for (word in forbidden) {
                assertFalse(
                    "$file 不該引用 $word（點擊與路由路徑必須零 LLM）",
                    text.contains(word, ignoreCase = true)
                )
            }
        }
        assumeTrue("找不到 app 原始碼（在不同工作目錄跑測試？）", checked > 0)
    }
}
