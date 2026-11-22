package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAE-Ω 第 2 層：App Model 編譯器（觀察 → 模型；增量合併）。
 */
class AppModelCompilerTest {

    private fun obs1(): AppModelCompiler.Observation = AppModelCompiler.Observation(
        fingerprints = listOf(1L, 2L),
        types = setOf(UiOntology.UiType.BUTTON, UiOntology.UiType.TEXT_FIELD),
        fields = listOf(FieldSchema("version", UiOntology.UiType.TEXT_LABEL)),
        risks = listOf(RiskMark(RiskKind.PAYMENT, "id:pay")),
        transitions = listOf(
            Transition("", com.wae.agent.core.ActionKind.TAP, UiOntology.UiType.BUTTON, "id:go", "s2", ok = 2)
        )
    )

    private fun obs2(): AppModelCompiler.Observation = AppModelCompiler.Observation(
        fingerprints = listOf(1L, 2L),
        types = setOf(UiOntology.UiType.BUTTON),
        transitions = listOf(
            Transition("", com.wae.agent.core.ActionKind.TAP, UiOntology.UiType.BUTTON, "id:go", "s2", ok = 1)
        )
    )

    @Test
    fun `compile produces verifiable model`() {
        val m = AppModelCompiler.compile("com.example", listOf(obs1()))
        assertEquals("com.example", m.pkg)
        assertEquals(1, m.states.size)
        assertTrue(m.verifyIntegrity())
        assertEquals(1, m.transitions.size)
        assertEquals(2, m.transitions.first().ok)
        assertEquals(1, m.fields.size)
        assertEquals(1, m.risks.size)
    }

    @Test
    fun `same fingerprints map to same state id`() {
        val a = AppModelCompiler.stateIdOf(listOf(9L, 3L), 0)
        val b = AppModelCompiler.stateIdOf(listOf(3L, 9L), 5)
        assertEquals(a, b) // 排序後再算，順序不影響
    }

    @Test
    fun `merge increments version and accumulates transition counts`() {
        val base = AppModelCompiler.compile("com.example", listOf(obs1()))
        val merged = AppModelCompiler.merge(base, obs2())
        assertEquals(base.version + 1, merged.version)
        assertEquals(1, merged.states.size) // 同指紋 → 同一狀態
        assertEquals(3, merged.transitions.first().ok) // 2 + 1
        assertTrue(merged.verifyIntegrity())
    }

    @Test
    fun `merge models combines fields and risks`() {
        val a = AppModelCompiler.compile("com.example", listOf(obs1()))
        val other = AppModel(
            pkg = "com.example",
            states = listOf(AppState("x", listOf(7L), setOf(UiOntology.UiType.SLIDER))),
            fields = listOf(FieldSchema("volume", UiOntology.UiType.SLIDER)),
            risks = listOf(RiskMark(RiskKind.DESTRUCTIVE, "id:clear"))
        ).withComputedHash()
        val merged = AppModelCompiler.mergeModels(a, other)
        assertNotNull(merged.fieldByName("version"))
        assertNotNull(merged.fieldByName("volume"))
        assertTrue(merged.risks.any { it.kind == RiskKind.DESTRUCTIVE })
        assertTrue(merged.risks.any { it.kind == RiskKind.PAYMENT })
        assertEquals(2, merged.states.size)
    }

    @Test
    fun `different packages are not merged`() {
        val a = AppModelCompiler.compile("com.a", listOf(obs1()))
        val b = AppModelCompiler.compile("com.b", listOf(obs1()))
        val merged = AppModelCompiler.mergeModels(a, b)
        assertEquals("com.a", merged.pkg)
        assertEquals(1, merged.states.size)
    }

    @Test
    fun `empty inputs produce empty model`() {
        assertTrue(AppModelCompiler.compile("", listOf(obs1())).states.isEmpty())
        assertTrue(AppModelCompiler.compile("com.x", emptyList()).states.isEmpty())
    }
}
