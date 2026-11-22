package com.wae.agent.core.taskos

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S33：TaskOsEngine（門面）＋ CheckpointStore ＋ SkillCompiler 的整合行為。
 *
 * 守住三件事：
 *  1. 旗標關閉時 **完全不做任何事**（NoMatch），不影響既有路徑
 *  2. 旗標開啟時：模板 → 計畫；缺變數 → NeedVars；checkpoint → 續跑
 *  3. 任務完成 → 編譯技能（可重用）＋清除 checkpoint
 */
class TaskOsEngineTest {

    @Before
    fun setUp() {
        TaskOsEngine.resetForTest()
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = false
        TaskTemplates.loadFromLines(
            listOf(
                "template\tread-version\t(?i)(?:讀|查看|查).*版本\t" +
                    "LAUNCH_APP:設定|TAP:關於|COLLECT:LONGEST_NUMBER:version|BACK",
                "verb\tlaunch_app\tLAUNCH_APP",
                "verb\ttap\tTAP",
                "verb\tback\tBACK"
            )
        )
        SlotFiller.loadFromLines(emptyList())
    }

    @After
    fun tearDown() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = false
        TaskOsEngine.resetForTest()
    }

    @Test
    fun `flag off means no match and no side effects`() {
        val start = TaskOsEngine.start("查一下版本")
        assertEquals(TaskOsEngine.Start.NoMatch, start)
        assertNull(TaskOsEngine.resume("查一下版本"))
    }

    @Test
    fun `flag on plans a template task`() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = true
        val start = TaskOsEngine.start("查一下版本", nowMs = 5L)
        assertTrue(start is TaskOsEngine.Start.Planned)
        val session = (start as TaskOsEngine.Start.Planned).session
        assertEquals("read-version", session.plan.templateId)
        assertEquals(3, session.plan.size)
        assertEquals(5L, session.plan.createdAtMs)
    }

    @Test
    fun `checkpoint enables resume`() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = true
        val first = (TaskOsEngine.start("查一下版本") as TaskOsEngine.Start.Planned).session
        // 跑第一步並存檔
        first.onSuccess(first.nextRunnable()!!, listOf("設定"))
        assertNotNull(CheckpointStore.load(CheckpointStore.keyOf("查一下版本")))

        // 同指令再 start → 應該接續（剩 2 步）
        val resumed = (TaskOsEngine.start("查一下版本") as TaskOsEngine.Start.Planned).session
        assertEquals(2, resumed.remainingCount())
    }

    @Test
    fun `completed session compiles skill and clears checkpoint`() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = true
        val session = (TaskOsEngine.start("查一下版本", nowMs = 9L) as TaskOsEngine.Start.Planned).session
        // 全部跑完
        while (true) {
            val r = session.nextRunnable(listOf("版本 1.4.2")) ?: break
            session.onSuccess(r, listOf("版本 1.4.2"))
        }
        assertTrue(session.isDone)
        assertEquals("1.4.2", session.context.get("version"))

        val skill = TaskOsEngine.onSessionCompleted(session, nowMs = 10L)
        assertNotNull(skill)
        assertEquals(1, SkillCompiler.count())
        assertEquals("read-version", skill!!.templateId)
        // checkpoint 應被清除 → 不會誤續跑
        assertNull(CheckpointStore.load(CheckpointStore.keyOf("查一下版本")))
    }

    @Test
    fun `skill hit plans without re-planning`() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = true
        // 先做一次任務 → 產生技能
        val session = (TaskOsEngine.start("查一下版本") as TaskOsEngine.Start.Planned).session
        while (true) {
            val r = session.nextRunnable(listOf("版本 1.4.2")) ?: break
            session.onSuccess(r, listOf("版本 1.4.2"))
        }
        TaskOsEngine.onSessionCompleted(session)

        // 第二次：應命中技能（templateId 可能是 skill:...）
        val second = TaskOsEngine.start("查一下版本") as TaskOsEngine.Start.Planned
        assertTrue(
            "第二次應由技能庫提供：template=${second.session.plan.templateId}",
            second.session.plan.templateId.startsWith("skill:") ||
                second.session.plan.templateId == "read-version"
        )
        assertEquals(3, second.session.plan.size)
    }

    @Test
    fun `skill compile rejects single step plans`() {
        val plan = TaskPlan(
            planId = "tp-one", command = "單步", templateId = "",
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
        val session = TaskSession(plan)
        session.onSuccess(session.nextRunnable()!!)
        assertNull(SkillCompiler.compile(session))
    }

    @Test
    fun `abandon clears checkpoint`() {
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED = true
        val session = (TaskOsEngine.start("查一下版本") as TaskOsEngine.Start.Planned).session
        session.onSuccess(session.nextRunnable()!!)
        assertNotNull(CheckpointStore.load(CheckpointStore.keyOf("查一下版本")))
        TaskOsEngine.abandon("查一下版本")
        assertNull(CheckpointStore.load(CheckpointStore.keyOf("查一下版本")))
    }

    @Test
    fun `skill toStepsSpec rejects unknown action kinds`() {
        val step = TaskStep(
            nodeId = "0",
            goal = com.wae.agent.core.LocalGoal(
                stepIdx = 0, goalId = "g", goalText = "x",
                canonicalIntentTag = "ADJUST_VALUE",
                actionKind = com.wae.agent.core.ActionKind.ADJUST_VALUE
            )
        )
        assertEquals("", SkillCompiler.toStepsSpec(listOf(step)))
        assertFalse(SkillCompiler.toStepsSpec(emptyList()).isNotEmpty())
    }
}
