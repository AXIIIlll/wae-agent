package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 第 6 層：驗證式執行（契約＋證據＋反例）。
 */
class VerifiedExecutorTest {

    @Before
    fun setUp() {
        // 每個測試獨立
    }

    private class FakeBridge(
        var readValue: String? = "1.4.2",
        var executeOk: Boolean = true,
        var confirmOk: Boolean = false,
        var cancelled: Boolean = false,
        var failOn: String? = null
    ) : VerifiedExecutor.ExecutorBridge {
        val executed = ArrayList<String>()
        val progress = ArrayList<String>()

        override fun executeStep(step: TaskIrStep, resolvedValue: String?): Boolean {
            if (failOn != null && step.goalHint.contains(failOn!!)) return false
            executed.add("${step.intent}:${resolvedValue ?: ""}")
            return executeOk
        }

        override fun readField(field: FieldSchema?): String? = readValue

        override fun confirmCommit(step: TaskIrStep): Boolean = confirmOk

        override fun screenTexts(): List<String> = listOf("版本 1.4.2", "確定")

        override fun isCancelled(): Boolean = cancelled

        override fun onProgress(text: String) {
            progress.add(text)
        }
    }

    private fun plan(vararg steps: TaskIrStep): TaskIrPlan =
        TaskIrPlan("ir-run", "cmd", steps.toList())

    @Test
    fun `read then write succeeds with evidence hash`() {
        val bridge = FakeBridge()
        val report = VerifiedExecutor.run(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.READ,
                    TargetRef(UiOntology.UiType.TEXT_LABEL, fieldName = "version"),
                    ValueRef.variable("version")
                ),
                TaskIrStep(
                    TaskIrStep.Intent.WRITE,
                    TargetRef(UiOntology.UiType.TEXT_FIELD, fieldName = "chatInput"),
                    ValueRef.variable("version")
                )
            ),
            bridge
        )
        assertTrue(report.summary(), report.ok)
        assertEquals(2, report.steps.size)
        assertTrue(report.evidenceHash.length == 64)
        assertTrue(bridge.executed.any { it.startsWith("WRITE:1.4.2") })
    }

    @Test
    fun `missing variable fails with counter example`() {
        val report = VerifiedExecutor.run(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.WRITE,
                    TargetRef(UiOntology.UiType.TEXT_FIELD, fieldName = "f"),
                    ValueRef.variable("nope")
                )
            ),
            FakeBridge()
        )
        assertEquals(false, report.ok)
        assertEquals("missing_var", report.message)
        assertNotNull(report.failedStep)
        assertTrue(report.failedStep!!.counterExample.isNotEmpty())
    }

    @Test
    fun `read failure stops execution`() {
        val report = VerifiedExecutor.run(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.READ,
                    TargetRef(UiOntology.UiType.TEXT_LABEL, fieldName = "version"),
                    ValueRef.variable("version")
                )
            ),
            FakeBridge(readValue = null)
        )
        assertEquals(false, report.ok)
        assertEquals("read_failed", report.message)
    }

    @Test
    fun `commit without confirmation stops before executing`() {
        val report = VerifiedExecutor.run(
            plan(TaskIrStep(TaskIrStep.Intent.COMMIT, TargetRef(UiOntology.UiType.BUTTON, "送出"))),
            FakeBridge(confirmOk = false)
        )
        assertEquals("confirmation_required", report.message)
        assertEquals(1, report.steps.size)
    }

    @Test
    fun `commit confirmed executes`() {
        val report = VerifiedExecutor.run(
            plan(TaskIrStep(TaskIrStep.Intent.COMMIT, TargetRef(UiOntology.UiType.BUTTON, "送出"))),
            FakeBridge(confirmOk = true)
        )
        assertTrue(report.ok)
    }

    @Test
    fun `compute stores variable locally`() {
        val vars = HashMap<String, String>()
        val report = VerifiedExecutor.run(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.COMPUTE,
                    value = ValueRef(kind = ValueRef.Kind.COMPUTE, name = "sum", expr = "2+3")
                ),
                TaskIrStep(
                    TaskIrStep.Intent.WRITE,
                    TargetRef(UiOntology.UiType.TEXT_FIELD, fieldName = "f"),
                    ValueRef.variable("sum")
                )
            ),
            FakeBridge(),
            vars
        )
        assertTrue(report.summary(), report.ok)
        assertEquals("5", vars["sum"])
    }

    @Test
    fun `bad compute fails`() {
        val report = VerifiedExecutor.run(
            plan(
                TaskIrStep(
                    TaskIrStep.Intent.COMPUTE,
                    value = ValueRef(kind = ValueRef.Kind.COMPUTE, name = "x", expr = "1/0")
                )
            ),
            FakeBridge()
        )
        assertEquals(false, report.ok)
        assertEquals("compute_failed", report.message)
    }

    @Test
    fun `cancellation stops immediately`() {
        val report = VerifiedExecutor.run(
            plan(TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "x"))),
            FakeBridge(cancelled = true)
        )
        assertEquals(false, report.ok)
        assertEquals("cancelled", report.message)
        assertEquals(0, report.steps.size)
    }

    @Test
    fun `execute failure reports counter example with screen`() {
        val report = VerifiedExecutor.run(
            plan(TaskIrStep(TaskIrStep.Intent.NAVIGATE, TargetRef(UiOntology.UiType.BUTTON, "x"))),
            FakeBridge(executeOk = false)
        )
        assertEquals(false, report.ok)
        assertEquals("execute_failed", report.message)
        assertTrue(report.failedStep!!.counterExample.contains("screen="))
    }
}
