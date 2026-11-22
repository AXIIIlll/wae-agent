package com.wae.agent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M0：schema / grammar 檔案存在且結構合理（防止 prompt/schema 被改壞）。
 * 測試從 app/src/main/assets 讀取（unit test 工作目錄為 app/）。
 */
class AgentSchemaTest {

    private val assets = File("src/main/assets")

    @Test
    fun `plan schema 存在且必填欄位正確`() {
        val f = File(assets, "agent/schema/plan.schema.json")
        assertTrue("缺少 plan.schema.json", f.isFile)
        val obj = JSONObject(f.readText())
        assertEquals("AgentPlan", obj.getString("title"))
        val required = obj.getJSONArray("required").map { it.toString() }
        assertTrue(required.containsAll(listOf("version", "goalKind", "summary", "steps")))
    }

    @Test
    fun `decision schema 的 action 是通用動作`() {
        val f = File(assets, "agent/schema/decision.schema.json")
        assertTrue(f.isFile)
        val actions = JSONObject(f.readText())
            .getJSONObject("properties")
            .getJSONObject("action")
            .getJSONArray("enum")
            .map { it.toString() }
        assertTrue(actions.containsAll(listOf("tap", "type", "scroll", "back", "open_app", "ask_user", "replan", "done")))
    }

    @Test
    fun `action grammar 不含座標與 App 流程`() {
        val f = File(assets, "agent/action_grammar.json")
        assertTrue(f.isFile)
        val obj = JSONObject(f.readText())
        assertTrue(obj.getBoolean("noCoordinates"))
        assertTrue(obj.getBoolean("noAppFlows"))
    }

    @Test
    fun `reranker config 有門檻與權重`() {
        val f = File(assets, "agent/reranker_config.json")
        assertTrue(f.isFile)
        val obj = JSONObject(f.readText())
        assertTrue(obj.getJSONObject("thresholds").getDouble("autoAct") > 0.5)
        assertTrue(obj.getJSONObject("weights").has("crossEncoder"))
    }
}
