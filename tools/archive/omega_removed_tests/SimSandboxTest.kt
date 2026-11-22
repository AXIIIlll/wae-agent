package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAE-Ω 第 5 層：模擬沙盒（先模擬後執行）。
 */
class SimSandboxTest {

    private fun model(): AppModel = AppModel(
        pkg = "com.example",
        states = listOf(
            AppState("home", listOf(1L), setOf(UiOntology.UiType.BUTTON, UiOntology.UiType.TEXT_FIELD)),
            AppState("detail", listOf(2L), setOf(UiOntology.UiType.TEXT_LABEL, UiOntology.UiType.BUTTON))
        ),
        transitions = listOf(
            Transition("home", com.wae.agent.core.ActionKind.TAP, UiOntology.UiType.BUTTON, "id:go", "detail", ok = 9, fail = 1)
        ),
        fields = listOf(
            FieldSchema("version", UiOntology.UiType.TEXT_LABEL, readPattern = "版本"),
            FieldSchema("comment", UiOntology.UiType.TEXT_FIELD, writePattern = "id:comment")
        )
    ).withComputedHash()

    private fun plan(vararg steps: TaskIrStep): TaskIrPlan = TaskIrPlan("ir-sim", "cmd", steps.toList())

    @Test
    fun `known path simulates as feasible`() {
        val result = SimSandbox.simulate(
            plan(
                TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "確定")),
                TaskIrStep(
                    TaskIrStep.Intent.READ,
                    TargetRef(UiOntology.UiType.TEXT_LABEL, fieldName = "version"),
                    ValueRef.variable("version")
                )
            ),
            model()
        )
        assertTrue(result.summary(), result.feasible)
        assertTrue(result.expectedSuccess >= SimSandbox.MIN_EXPECTED_SUCCESS)
        assertTrue(result.uncertainties.isEmpty())
        assertFalse(result.needsConfirmation)
        assertEquals("detail", result.steps.last().stateBefore)
    }

    @Test
    fun `unknown transition becomes uncertainty and probe`() {
        val result = SimSandbox.simulate(
            plan(TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.SLIDER, "音量"))),
            model()
        )
        assertFalse(result.feasible)
        assertTrue(result.uncertainties.any { it.startsWith("nav:") })
        assertTrue(result.probes.isNotEmpty())
        assertEquals(UiOntology.UiType.SLIDER, result.probes.first().targetType)
    }

    @Test
    fun `read without schema is uncertain`() {
        val result = SimSandbox.simulate(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.READ,
                    TargetRef(UiOntology.UiType.TEXT_LABEL, fieldName = "unknownField"),
                    ValueRef.variable("x")
                )
            ),
            model()
        )
        assertTrue(result.uncertainties.any { it.startsWith("read:") })
        assertFalse(result.feasible)
    }

    @Test
    fun `write unsupported type is uncertain`() {
        val m = model().copy(states = listOf(AppState("home", listOf(1L), setOf(UiOntology.UiType.BUTTON))))
            .withComputedHash()
        val result = SimSandbox.simulate(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.WRITE,
                    TargetRef(UiOntology.UiType.SLIDER),
                    ValueRef.literal("50")
                )
            ),
            m
        )
        assertTrue(result.uncertainties.any { it.startsWith("write:") })
    }

    @Test
    fun `commit flags confirmation`() {
        val result = SimSandbox.simulate(
            plan(TaskIrStep(TaskIrStep.Intent.COMMIT, TargetRef(UiOntology.UiType.BUTTON, "送出"))),
            model()
        )
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `compute is fully certain`() {
        val result = SimSandbox.simulate(
            plan(TaskIrStep(TaskIrStep.Intent.COMPUTE, value = ValueRef.compute("1+1"))),
            model()
        )
        assertTrue(result.feasible || result.uncertainties.isEmpty())
        assertEquals(1f, result.steps.first().certainty, 1e-6f)
    }

    @Test
    fun `empty model is infeasible`() {
        val result = SimSandbox.simulate(plan(), AppModel(pkg = "com.empty"))
        assertFalse(result.feasible)
        assertTrue(result.uncertainties.contains("empty_model"))
    }

    @Test
    fun `long unknown chain lowers expected success`() {
        val steps = (0 until 5).map {
            TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.SLIDER, "x"))
        }
        val result = SimSandbox.simulate(TaskIrPlan("ir", "cmd", steps), model())
        assertTrue(result.expectedSuccess < 0.1f)
        assertFalse(result.feasible)
        assertTrue(result.probes.isNotEmpty())
    }
}
