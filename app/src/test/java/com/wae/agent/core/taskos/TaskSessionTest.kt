package com.wae.agent.core.taskos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S33：TaskSession（變數綁定、守衛、收集、修復插入、checkpoint 續跑）。
 */
class TaskSessionTest {

    @Before
    fun setUp() {
        CheckpointStore.resetForTest()
    }

    @Test
    fun `next runnable binds variables from context`() {
        val session = session(
            ctxVars = mapOf("version" to "1.4.2"),
            steps = listOf(
                step("0", com.wae.agent.core.ActionKind.TYPE, payload = "\$version")
            )
        )
        val runnable = session.nextRunnable()
        assertNotNull(runnable)
        assertEquals("1.4.2", runnable!!.goal.inputPayload)
    }

    @Test
    fun `guard text not present skips step`() {
        val session = session(
            steps = listOf(
                step("0", com.wae.agent.core.ActionKind.TAP, guard = TaskGuard.TextContains("同意")),
                step("1", com.wae.agent.core.ActionKind.BACK)
            )
        )
        // 畫面沒有「同意」→ 第一步被跳過（視為完成），直接回第二步
        val runnable = session.nextRunnable(listOf("無關文字"))
        assertEquals("1", runnable!!.step.nodeId)
        assertEquals(1, session.completed)
    }

    @Test
    fun `success collects variable and advances`() {
        val session = session(
            steps = listOf(
                step("0", com.wae.agent.core.ActionKind.TAP, collect = CollectSpec("version")),
                step("1", com.wae.agent.core.ActionKind.BACK)
            )
        )
        val r = session.nextRunnable()!!
        session.onSuccess(r, listOf("版本 1.4.2"))
        assertEquals("1.4.2", session.context.get("version"))
        assertEquals(1, session.completed)
        assertEquals("1", session.nextRunnable()!!.step.nodeId)
        assertFalse(session.isDone)
    }

    @Test
    fun `failure inserts repair then retries original`() {
        val session = session(
            steps = listOf(step("0", com.wae.agent.core.ActionKind.TYPE, payload = "台北101"))
        )
        val r = session.nextRunnable()!!
        val repair = session.onFailure(r, listOf("載入中"))
        assertNotNull(repair)
        assertEquals("input_retry", repair!!.reason)
        // 佇列順序：先 CLEAR_TEXT 修復，再原本的 TYPE
        val first = session.nextRunnable()!!
        assertEquals(com.wae.agent.core.ActionKind.CLEAR_TEXT, first.goal.actionKind)
        session.onSuccess(first)
        val retried = session.nextRunnable()!!
        assertEquals(com.wae.agent.core.ActionKind.TYPE, retried.goal.actionKind)
        assertEquals("台北101", retried.goal.inputPayload)
    }

    @Test
    fun `second failure has no more repair`() {
        val session = session(
            steps = listOf(step("0", com.wae.agent.core.ActionKind.TYPE, payload = "x"))
        )
        val r1 = session.nextRunnable()!!
        assertNotNull(session.onFailure(r1, emptyList()))
        // 修復步驟本身失敗 → 不該無限修
        val repairStep = session.nextRunnable()!!
        assertNull(session.onFailure(repairStep, emptyList()))
    }

    @Test
    fun `checkpoint round trip keeps progress`() {
        val session = session(
            steps = listOf(
                step("0", com.wae.agent.core.ActionKind.TAP),
                step("1", com.wae.agent.core.ActionKind.TYPE, payload = "\$v")
            ),
            ctxVars = mapOf("v" to "hello")
        )
        session.onSuccess(session.nextRunnable()!!)
        val text = session.toCheckpoint()
        val restored = TaskSession.fromCheckpoint(text)
        assertNotNull(restored)
        assertEquals(1, restored!!.remainingCount())
        assertEquals("hello", restored.context.get("v"))
        assertEquals("tp-test", restored.plan.planId)
        assertEquals("hello", restored.nextRunnable()!!.goal.inputPayload)
    }

    @Test
    fun `bad checkpoint returns null`() {
        assertNull(TaskSession.fromCheckpoint("garbage"))
    }

    @Test
    fun `done when queue empty`() {
        val session = session(steps = listOf(step("0", com.wae.agent.core.ActionKind.BACK)))
        session.onSuccess(session.nextRunnable()!!)
        assertNull(session.nextRunnable())
        assertTrue(session.isDone)
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun session(
        steps: List<TaskStep>,
        ctxVars: Map<String, String> = emptyMap()
    ): TaskSession {
        val plan = TaskPlan(
            planId = "tp-test",
            command = "test",
            templateId = "t",
            steps = steps,
            requiredVars = emptySet(),
            createdAtMs = 1L
        )
        return TaskSession(plan, TaskContext("test", ctxVars))
    }

    private fun step(
        id: String,
        kind: com.wae.agent.core.ActionKind,
        payload: String = "",
        guard: TaskGuard = TaskGuard.Always,
        collect: CollectSpec? = null,
        irreversible: Boolean = false
    ): TaskStep = TaskStep(
        nodeId = id,
        goal = com.wae.agent.core.LocalGoal(
            stepIdx = id.toIntOrNull() ?: 0,
            goalId = "g$id",
            goalText = "步驟$id",
            canonicalIntentTag = kind.name,
            actionKind = kind,
            inputPayload = payload
        ),
        guard = guard,
        collect = collect,
        irreversible = irreversible
    )
}
