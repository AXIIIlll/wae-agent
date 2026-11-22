package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAE-Ω 第 2 層：App Model（本體論型別、內容雜湊、序列化、可達性、風險查詢）。
 */
class AppModelTest {

    private fun model(): AppModel = AppModel(
        pkg = "com.example.app",
        version = 3,
        states = listOf(
            AppState("home", listOf(11L, 22L), setOf(UiOntology.UiType.LIST_ROW)),
            AppState("detail", listOf(33L), setOf(UiOntology.UiType.BUTTON, UiOntology.UiType.TEXT_LABEL)),
            AppState("form", listOf(44L), setOf(UiOntology.UiType.TEXT_FIELD))
        ),
        transitions = listOf(
            Transition("home", com.wae.agent.core.ActionKind.TAP, UiOntology.UiType.LIST_ROW, "id:row", "detail", 5, 1),
            Transition("detail", com.wae.agent.core.ActionKind.TAP, UiOntology.UiType.BUTTON, "id:next", "form", 2, 0)
        ),
        fields = listOf(
            FieldSchema("version", UiOntology.UiType.TEXT_LABEL, readPattern = "版本"),
            FieldSchema("comment", UiOntology.UiType.TEXT_FIELD, writePattern = "id:comment")
        ),
        risks = listOf(
            RiskMark(RiskKind.PAYMENT, "id:pay"),
            RiskMark(RiskKind.PUBLIC_WRITE, "id:post")
        )
    )

    @Test
    fun `hash is stable and integrity verifies`() {
        val a = model().withComputedHash()
        val b = model().withComputedHash()
        assertEquals(a.hash, b.hash)
        assertTrue(a.verifyIntegrity())
        assertFalse(a.copy(pkg = "com.other.app").verifyIntegrity())
    }

    @Test
    fun `serialization round trip keeps everything`() {
        val original = model().withComputedHash()
        val text = original.toLineFormat()
        val restored = AppModel.fromLineFormat(text)
        assertNotNull(restored)
        assertEquals(original.pkg, restored!!.pkg)
        assertEquals(3, restored.version)
        assertEquals(3, restored.states.size)
        assertEquals(2, restored.transitions.size)
        assertEquals(2, restored.fields.size)
        assertEquals(2, restored.risks.size)
        assertTrue("雜湊應可驗證", restored.verifyIntegrity())
    }

    @Test
    fun `serialization escapes pipe and newline`() {
        val m = AppModel(
            pkg = "com.weird|app",
            fields = listOf(FieldSchema("a|b\nc", UiOntology.UiType.TEXT_FIELD, "x|y"))
        ).withComputedHash()
        val restored = AppModel.fromLineFormat(m.toLineFormat())
        assertNotNull(restored)
        assertEquals("com.weird|app", restored!!.pkg)
        assertEquals("a|b\nc", restored.fields.first().name)
        assertEquals("x|y", restored.fields.first().readPattern)
    }

    @Test
    fun `bad text returns null`() {
        assertNull(AppModel.fromLineFormat("not a model"))
        assertNull(AppModel.fromLineFormat("# WAE_APP_MODEL v1\n狀態壞掉"))
    }

    @Test
    fun `reachability follows transitions with depth limit`() {
        val m = model()
        assertTrue(m.reachable("home", "detail"))
        assertTrue(m.reachable("home", "form"))
        assertFalse(m.reachable("form", "home"))
        assertFalse(m.reachable("nope", "home"))
        assertFalse(m.reachable("home", "form", maxDepth = 1))
    }

    @Test
    fun `risk lookup by selector`() {
        val m = model()
        assertEquals(RiskKind.PAYMENT, m.risksOf("id:pay").first().kind)
        assertTrue(m.risksOf("id:post").isNotEmpty())
        assertTrue(m.risksOf("id:unrelated").isEmpty())
    }

    @Test
    fun `supports checks types via fields and states`() {
        val m = model()
        assertTrue(m.supports(UiOntology.UiType.TEXT_FIELD))
        assertTrue(m.supports(UiOntology.UiType.LIST_ROW))
        assertFalse(m.supports(UiOntology.UiType.SLIDER))
    }

    @Test
    fun `transition success rate handles no samples`() {
        val t = Transition("a", com.wae.agent.core.ActionKind.TAP, null, "s", "b")
        assertEquals(0.5f, t.successRate, 1e-6f)
        val t2 = t.copy(ok = 3, fail = 1)
        assertEquals(0.75f, t2.successRate, 1e-6f)
    }
}
