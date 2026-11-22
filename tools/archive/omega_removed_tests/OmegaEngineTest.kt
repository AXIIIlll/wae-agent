package com.wae.agent.core.omega

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 門面（OmegaEngine）：編譯 → 驗證 → 模擬 → 執行 → 學習 的整合行為。
 */
class OmegaEngineTest {

    @Before
    fun setUp() {
        OmegaEngine.resetForTest()
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.WAE_OMEGA_ENABLED = false
        UiOntology.loadFromLines(
            listOf(
                "utype\tTEXT_FIELD\tclass\tEditText",
                "utype\tTEXT_LABEL\tclass\tTextView",
                "utype\tBUTTON\tclass\tButton"
            )
        )
        PolicyVerifier.loadFromLines(
            listOf("policy\tPERMISSION\t1\t0", "policy\tPAYMENT\t0\t1")
        )
        IntentCompiler.loadFromLines(
            listOf("ir\tread-version\t(?i)(?:讀|查看|查).*版本\tREAD:version->\$version")
        )
        // 模型：一個狀態、version 欄位
        ModelStore.save(
            AppModel(
                pkg = "com.android.settings",
                states = listOf(AppState("s1", listOf(1L), setOf(UiOntology.UiType.TEXT_LABEL))),
                fields = listOf(FieldSchema("version", UiOntology.UiType.TEXT_LABEL, readPattern = "版本"))
            )
        )
    }

    @After
    fun tearDown() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.WAE_OMEGA_ENABLED = false
        OmegaEngine.resetForTest()
    }

    @Test
    fun `flag defaults off`() {
        assertFalse(OmegaEngine.enabled())
    }

    @Test
    fun `plan without model is not ready`() {
        val r = OmegaEngine.plan("查一下版本", "com.unknown.app")
        assertFalse(r.readyToExecute)
        assertFalse(r.modelFound)
    }

    @Test
    fun `plan with model compiles verifies and simulates`() {
        val r = OmegaEngine.plan("查一下版本", "com.android.settings")
        assertTrue("model 應被找到", r.modelFound)
        assertTrue("應編譯成功：${r.message}", r.compile is IntentCompiler.Result.Compiled)
        assertNotNull(r.verification)
        assertNotNull(r.simulation)
        assertTrue("應為 ready：${r.message}", r.readyToExecute)
        assertEquals(PolicyVerifier.Verdict.SAFE, r.verification!!.verdict)
    }

    @Test
    fun `execute verified plan through fake bridge`() {
        val r = OmegaEngine.plan("查一下版本", "com.android.settings")
        val plan = (r.compile as IntentCompiler.Result.Compiled).plan
        val bridge = object : VerifiedExecutor.ExecutorBridge {
            override fun executeStep(step: TaskIrStep, resolvedValue: String?): Boolean = true
            override fun readField(field: FieldSchema?): String = "1.4.2"
            override fun screenTexts(): List<String> = listOf("版本 1.4.2")
        }
        val report = OmegaEngine.execute(plan, bridge)
        assertTrue(report.summary(), report.ok)
        assertEquals(1, report.steps.size)
    }

    @Test
    fun `learn operators from traces`() {
        val n = OmegaEngine.learnOperators(
            listOf(
                listOf("APP:設定", "TAP:關於", "TAP:電池"),
                listOf("APP:設定", "TAP:關於", "TAP:螢幕")
            )
        )
        assertTrue("應學到至少一個運算子", n >= 1)
        assertTrue(OperatorRegistry.count() >= 1)
    }

    @Test
    fun `merge observation grows model`() {
        val merged = OmegaEngine.mergeObservation(
            "com.android.settings",
            AppModelCompiler.Observation(
                fingerprints = listOf(1L),
                types = setOf(UiOntology.UiType.BUTTON),
                transitions = listOf(
                    Transition(
                        "s1", com.wae.agent.core.ActionKind.TAP,
                        UiOntology.UiType.BUTTON, "id:about", "s2", ok = 1
                    )
                )
            )
        )
        assertTrue(merged.verifyIntegrity())
        assertTrue(merged.version >= 1)
        assertTrue(OmegaEngine.hasModel("com.android.settings"))
    }

    @Test
    fun `summary contains version and readiness`() {
        val r = OmegaEngine.plan("查一下版本", "com.android.settings")
        assertTrue(r.summary().contains(OmegaEngine.VERSION))
        assertTrue(r.summary().contains("ready="))
    }
}
