package com.wae.agent.core.taskos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S33：TaskRunner（長任務執行迴圈：成功/失敗修復/取消/不可逆確認）。
 */
class TaskRunnerTest {

    @Before
    fun setUp() {
        TaskOsEngine.resetForTest()
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = false
    }

    private class FakeExecutor(
        var screen: List<String> = emptyList(),
        var cancelled: Boolean = false,
        var confirmResult: Boolean = false,
        /** 每個目標文字要失敗幾次（第一次失敗、第二次成功） */
        var failFirstTimes: Int = 1
    ) : TaskRunner.TaskExecutor {
        val executed = ArrayList<String>()
        val progress = ArrayList<String>()
        private val failures = HashMap<String, Int>()

        override fun execute(goal: com.wae.agent.core.LocalGoal): Boolean {
            val key = goal.goalText
            val n = failures[key] ?: 0
            if (n < failFirstTimes && key.contains("步驟0")) {
                failures[key] = n + 1
                return false
            }
            executed.add(key)
            return true
        }

        override fun screenTexts(): List<String> = screen

        override fun confirm(
            step: TaskStep,
            goal: com.wae.agent.core.LocalGoal
        ): Boolean = confirmResult

        override fun isCancelled(): Boolean = cancelled

        override fun onProgress(text: String) {
            progress.add(text)
        }
    }

    @Test
    fun `all steps succeed`() {
        val session = sessionOf(3)
        val exec = FakeExecutor(failFirstTimes = 0)
        val result = TaskRunner.run(session, exec)
        assertTrue(result.ok)
        assertEquals(3, result.completed)
        assertEquals(3, result.stepsRun)
        assertEquals(3, exec.executed.size)
    }

    @Test
    fun `failure is repaired then original step retried`() {
        val session = sessionOf(2)
        val exec = FakeExecutor(failFirstTimes = 1)
        val result = TaskRunner.run(session, exec)
        assertTrue("修復後仍應完成：${result.message}", result.ok)
        // 修復步驟（清除）會被執行，然後原步驟重試成功
        assertTrue("修復後應完成至少 2 個計畫步驟：${result.completed}", result.completed >= 2)
        assertTrue(result.stepsRun >= 3)
        assertTrue(exec.progress.any { it.contains("修復") })
    }

    @Test
    fun `cancellation stops the run`() {
        val session = sessionOf(3)
        val exec = FakeExecutor(cancelled = true, failFirstTimes = 0)
        val result = TaskRunner.run(session, exec)
        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertEquals(0, result.stepsRun)
    }

    @Test
    fun `irreversible step needs confirmation`() {
        val session = sessionOf(1, irreversible = true)
        val exec = FakeExecutor(confirmResult = false, failFirstTimes = 0)
        val result = TaskRunner.run(session, exec)
        assertFalse(result.ok)
        assertEquals("confirmation_declined", result.message)
        assertEquals(0, exec.executed.size)
    }

    @Test
    fun `irreversible step runs when confirmed`() {
        val session = sessionOf(1, irreversible = true)
        val exec = FakeExecutor(confirmResult = true, failFirstTimes = 0)
        val result = TaskRunner.run(session, exec)
        assertTrue(result.ok)
        assertEquals(1, exec.executed.size)
    }

    @Test
    fun `no repair available fails the run`() {
        // BACK 失敗時 RepairPlanner 不修 → 直接失敗
        val plan = TaskPlan(
            planId = "tp-x", command = "c", templateId = "t",
            steps = listOf(
                TaskStep(
                    nodeId = "0",
                    goal = com.wae.agent.core.LocalGoal(
                        stepIdx = 0, goalId = "g0", goalText = "返回",
                        canonicalIntentTag = "BACK",
                        actionKind = com.wae.agent.core.ActionKind.BACK
                    )
                )
            ),
            requiredVars = emptySet(), createdAtMs = 1L
        )
        val exec = object : TaskRunner.TaskExecutor {
            override fun execute(goal: com.wae.agent.core.LocalGoal): Boolean = false
            override fun screenTexts(): List<String> = emptyList()
        }
        val result = TaskRunner.run(TaskSession(plan), exec)
        assertFalse(result.ok)
        assertEquals("failed_no_repair", result.message)
        assertNull(result.failedStep?.collect)
    }

    private fun sessionOf(n: Int, irreversible: Boolean = false): TaskSession {
        val steps = (0 until n).map { i ->
            TaskStep(
                nodeId = i.toString(),
                goal = com.wae.agent.core.LocalGoal(
                    stepIdx = i,
                    goalId = "g$i",
                    goalText = "步驟$i",
                    canonicalIntentTag = "TAP",
                    actionKind = com.wae.agent.core.ActionKind.TAP
                ),
                irreversible = irreversible && i == 0
            )
        }
        return TaskSession(
            TaskPlan(
                planId = "tp-run",
                command = "cmd",
                templateId = "t",
                steps = steps,
                requiredVars = emptySet(),
                createdAtMs = 1L
            )
        )
    }
}
