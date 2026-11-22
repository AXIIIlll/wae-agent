package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 第 4 層：策略自動機 + 有界模型檢查。
 *
 * 守住：
 *  - 沒有 App Model → UNDECIDABLE（不可證明安全，不是 SAFE）
 *  - 資料流缺變數 → UNSAFE
 *  - COMMIT → NEED_CONFIRM
 *  - 風險標註被策略阻擋（PERMISSION）→ UNSAFE
 *  - 正常計畫在模型充足時 → SAFE 且有證明書雜湊
 */
class PolicyVerifierTest {

    @Before
    fun setUp() {
        PolicyVerifier.resetForTest()
        PolicyVerifier.loadFromLines(
            listOf(
                "policy\tPAYMENT\t0\t1",
                "policy\tDESTRUCTIVE\t0\t1",
                "policy\tPUBLIC_WRITE\t0\t1",
                "policy\tPERMISSION\t1\t0",
                "policy\tPRIVATE_READ\t0\t1"
            )
        )
    }

    private fun modelWithRisk(kind: RiskKind, selector: String): AppModel = AppModel(
        pkg = "com.example",
        states = listOf(
            AppState("home", listOf(1L), setOf(UiOntology.UiType.BUTTON, UiOntology.UiType.TEXT_FIELD))
        ),
        risks = listOf(RiskMark(kind, selector))
    ).withComputedHash()

    private fun plan(vararg steps: TaskIrStep): TaskIrPlan = TaskIrPlan(
        id = "ir-1",
        command = "test",
        steps = steps.toList()
    )

    @Test
    fun `empty plan is unsafe`() {
        val v = PolicyVerifier.verify(plan(), null)
        assertEquals(PolicyVerifier.Verdict.UNSAFE, v.verdict)
    }

    @Test
    fun `no model means undecidable not safe`() {
        val v = PolicyVerifier.verify(
            plan(TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "確定"))),
            null
        )
        assertEquals(PolicyVerifier.Verdict.UNDECIDABLE, v.verdict)
        assertFalse(v.safeToExecuteWithoutPrompt())
    }

    @Test
    fun `safe plan with model gets certificate`() {
        val m = modelWithRisk(RiskKind.PAYMENT, "id:pay") // 不碰 pay
        val v = PolicyVerifier.verify(
            plan(
                TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "確定"))
            ),
            m
        )
        assertEquals(PolicyVerifier.Verdict.SAFE, v.verdict)
        assertTrue(v.safeToExecuteWithoutPrompt())
        assertTrue(v.certificateHash.length == 64)
        assertTrue(v.certificate.first().startsWith("# WAE_POLICY_CERT"))
    }

    @Test
    fun `missing dataflow variable is unsafe`() {
        val m = modelWithRisk(RiskKind.PAYMENT, "id:pay")
        val v = PolicyVerifier.verify(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.WRITE,
                    TargetRef(UiOntology.UiType.TEXT_FIELD, fieldName = "comment"),
                    ValueRef.variable("missing")
                )
            ),
            m
        )
        assertEquals(PolicyVerifier.Verdict.UNSAFE, v.verdict)
        assertTrue(v.checks.first().reason.contains("missing_var"))
    }

    @Test
    fun `write without value is unsafe`() {
        val v = PolicyVerifier.verify(
            plan(TaskIrStep(TaskIrStep.Intent.WRITE, TargetRef(UiOntology.UiType.TEXT_FIELD))),
            modelWithRisk(RiskKind.PAYMENT, "id:pay")
        )
        assertEquals(PolicyVerifier.Verdict.UNSAFE, v.verdict)
    }

    @Test
    fun `commit requires confirmation`() {
        val v = PolicyVerifier.verify(
            plan(TaskIrStep(TaskIrStep.Intent.COMMIT, TargetRef(UiOntology.UiType.BUTTON, "送出"))),
            modelWithRisk(RiskKind.PAYMENT, "id:pay")
        )
        assertEquals(PolicyVerifier.Verdict.NEED_CONFIRM, v.verdict)
        assertFalse(v.safeToExecuteWithoutPrompt())
    }

    @Test
    fun `blocked risk permission is unsafe`() {
        val m = modelWithRisk(RiskKind.PERMISSION, "id:allow")
        val v = PolicyVerifier.verify(
            plan(TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "id:allow"))),
            m
        )
        assertEquals(PolicyVerifier.Verdict.UNSAFE, v.verdict)
    }

    @Test
    fun `type not in model is unsafe`() {
        val m = modelWithRisk(RiskKind.PAYMENT, "id:pay")
        val v = PolicyVerifier.verify(
            plan(TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.SLIDER, "音量"))),
            m
        )
        assertEquals(PolicyVerifier.Verdict.UNSAFE, v.verdict)
    }

    @Test
    fun `plan too long is unsafe`() {
        val steps = (0 until PolicyVerifier.MAX_PLAN_STEPS + 1).map {
            TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "x"))
        }
        val v = PolicyVerifier.verify(TaskIrPlan("x", "cmd", steps), modelWithRisk(RiskKind.PAYMENT, "id:pay"))
        assertEquals(PolicyVerifier.Verdict.UNSAFE, v.verdict)
    }

    @Test
    fun `dataflow through read then write is safe`() {
        val m = modelWithRisk(RiskKind.PAYMENT, "id:pay")
        val v = PolicyVerifier.verify(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.READ,
                    TargetRef(UiOntology.UiType.TEXT_FIELD, fieldName = "version"),
                    ValueRef.variable("v")
                ),
                TaskIrStep(
                    TaskIrStep.Intent.WRITE,
                    TargetRef(UiOntology.UiType.TEXT_FIELD, fieldName = "target"),
                    ValueRef.variable("v")
                )
            ),
            m
        )
        assertEquals(PolicyVerifier.Verdict.SAFE, v.verdict)
    }

    @Test
    fun `rule labels are human readable`() {
        assertEquals("金流", PolicyVerifier.labelOf(RiskKind.PAYMENT))
        assertTrue(PolicyVerifier.ruleCount() >= 5)
    }
}
