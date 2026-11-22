package com.wae.agent.agent

import com.wae.agent.llm.PlanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserTest {

    @Test
    fun `解析正常計畫`() {
        val raw = """{"version":1,"goalKind":"x","summary":"s","steps":[{"id":1,"do":"開 App","target":"${'$'}app","verify":"首頁"},{"id":2,"do":"找搜尋","target":"搜尋框","verify":"輸入框"}]}"""
        val plan = PlanParser.parse(raw)
        assertTrue(plan != null)
        assertEquals(2, plan!!.steps.size)
        assertEquals("開 App", plan.steps[0].doText)
        assertEquals("${'$'}app", plan.steps[0].target)
    }

    @Test
    fun `容忍 markdown fence 與前後雜訊`() {
        val raw = "好的，這是計畫：\n```json\n{\"steps\":[{\"id\":1,\"do\":\"a\",\"verify\":\"b\"}]}\n```\n完成"
        val plan = PlanParser.parse(raw)
        assertTrue(plan != null)
        assertEquals(1, plan!!.steps.size)
    }

    @Test
    fun `沒有 steps 回 null`() {
        assertNull(PlanParser.parse("{\"version\":1}"))
    }

    @Test
    fun `step 缺 do 或 verify 回 null`() {
        assertNull(PlanParser.parse("{\"steps\":[{\"id\":1,\"do\":\"a\"}]}"))
    }

    @Test
    fun `非 JSON 回 null`() {
        assertNull(PlanParser.parse("我不知道"))
        assertNull(PlanParser.parse(""))
    }

    @Test
    fun `extractJsonObject 處理字串內大括號`() {
        val raw = "x{\"a\":\"}{\",\"b\":1}y"
        val json = PlanParser.extractJsonObject(raw)
        assertEquals("{\"a\":\"}{\",\"b\":1}", json)
    }

    @Test
    fun `相容 plan 別名`() {
        val raw = """{"plan":[{"do":"open_app","verify":"首頁"},{"do":"tap","verify":"畫面改變"}]}"""
        val plan = PlanParser.parse(raw)
        assertTrue(plan != null)
        assertEquals(2, plan!!.steps.size)
    }
}
