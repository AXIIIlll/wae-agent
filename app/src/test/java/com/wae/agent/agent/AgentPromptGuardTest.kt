package com.wae.agent.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M0：prompt 與 agent 資產不得包含任何第三方 App 名稱（維持「無預設」鐵律）。
 */
class AgentPromptGuardTest {

    private val banned = listOf(
        "youtube", "netflix", "instagram", "facebook", "gmail", "line", "spotify",
        "disney", "tiktok", "google photos", "chrome", "whatsapp", "telegram"
    )

    @Test
    fun `prompt 模板不含第三方 App 名稱`() {
        val dir = File("src/main/assets/agent/prompts")
        assertTrue("缺少 prompts 目錄", dir.isDirectory)
        val violations = ArrayList<String>()
        dir.listFiles()?.filter { it.isFile }?.forEach { f ->
            val text = f.readText().lowercase()
            banned.forEach { name ->
                if (text.contains(name)) violations.add(f.name + " 含 " + name)
            }
        }
        assertTrue("prompt 出現第三方 App 名稱：$violations", violations.isEmpty())
    }

    @Test
    fun `agent 資產不含套件字面量`() {
        val dir = File("src/main/assets/agent")
        val violations = ArrayList<String>()
        dir.walkTopDown().filter { it.isFile && it.extension in setOf("json", "txt") }.forEach { f ->
            if (f.readText().contains("com.")) {
                violations.add(f.name)
            }
        }
        assertTrue("agent 資產含套件字面量：$violations", violations.isEmpty())
    }
}
