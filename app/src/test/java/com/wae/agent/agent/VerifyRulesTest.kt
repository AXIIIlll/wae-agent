package com.wae.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class VerifyRulesTest {

    private fun obs(title: String, texts: List<String>) = Observation(
        appPackage = "p", title = title, elements = texts.mapIndexed { i, t -> ObservedElement(i, t) })

    @Test
    fun `出現期望文字算 OK`() {
        val r = VerifyRules().check(
            AgentStep(1, "do", "t", "藍牙"),
            obs("設定", listOf("網路")),
            obs("設定", listOf("藍牙", "網路"))
        )
        assertEquals(VerifyOutcome.OK, r.outcome)
    }

    @Test
    fun `畫面沒變算 FAIL`() {
        val r = VerifyRules().check(
            AgentStep(1, "do", "t", "藍牙"),
            obs("設定", listOf("網路")),
            obs("設定", listOf("網路"))
        )
        assertEquals(VerifyOutcome.FAIL, r.outcome)
    }

    @Test
    fun `有變化但沒找到算 UNCERTAIN`() {
        val r = VerifyRules().check(
            AgentStep(1, "do", "t", "藍牙"),
            obs("設定", listOf("網路")),
            obs("其他", listOf("無關"))
        )
        assertEquals(VerifyOutcome.UNCERTAIN, r.outcome)
    }

    @Test
    fun `TYPE 後文字有出現才算成功`() {
        val before = obs("設定", listOf("搜尋"))
        val afterOk = obs("設定", listOf("搜尋", "藍牙"))
        val d = AgentDecision(action = AgentAction.TYPE, index = 0, text = "藍牙")
        assertEquals(VerifyOutcome.OK, VerifyRules().check(AgentStep(1, "輸入", "搜尋", "藍牙"), before, afterOk, d).outcome)
        assertEquals(VerifyOutcome.FAIL, VerifyRules().check(AgentStep(1, "輸入", "搜尋", "藍牙"), before, before, d).outcome)
    }

    @Test
    fun `OPEN_APP 後 App 有變才算成功`() {
        val before = obs("設定", listOf("x")).copy(appPackage = "com.a")
        val after = obs("相簿", listOf("y")).copy(appPackage = "com.b")
        val d = AgentDecision(action = AgentAction.OPEN_APP, appName = "相簿")
        assertEquals(VerifyOutcome.OK, VerifyRules().check(AgentStep(1, "開 App", "\$app", "相簿"), before, after, d).outcome)
        assertEquals(VerifyOutcome.FAIL, VerifyRules().check(AgentStep(1, "開 App", "\$app", "相簿"), before, before, d).outcome)
    }
}
