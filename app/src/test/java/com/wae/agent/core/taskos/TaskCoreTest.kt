package com.wae.agent.core.taskos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S33 Task OS 核心型別：黑板變數解析、收集規格、任務圖平坦化、守衛合併。
 */
class TaskCoreTest {

    // ── TaskContext ────────────────────────────────────────────────

    @Test
    fun `resolve substitutes known vars and keeps unknown`() {
        val ctx = TaskContext("test", mapOf("version" to "1.2.3"))
        assertEquals("版本 1.2.3", ctx.resolve("版本 \$version"))
        assertEquals("缺 \$missing", ctx.resolve("缺 \$missing"))
        // 沒有 $ 時原樣返回（避免昂貴的正則）
        assertEquals("no vars", ctx.resolve("no vars"))
    }

    @Test
    fun `missing vars reports only absent ones`() {
        val ctx = TaskContext("t", mapOf("a" to "1"))
        assertEquals(setOf("b", "c"), ctx.missingVars(setOf("a", "b", "c")))
        assertEquals(emptySet<String>(), ctx.missingVars(setOf("a")))
    }

    @Test
    fun `line format round trip preserves command and vars`() {
        val ctx = TaskContext("查版本然後貼上", mapOf("version" to "1.2.3", "note" to "a\tb\nc"))
        val restored = TaskContext.fromLineFormat(ctx.toLineFormat())
        assertEquals("查版本然後貼上", restored!!.command)
        assertEquals("1.2.3", restored.get("version"))
        assertEquals("a\tb\nc", restored.get("note"))
    }

    @Test
    fun `bad line format returns null`() {
        assertNull(TaskContext.fromLineFormat("not a context"))
    }

    @Test
    fun `history records only what happened`() {
        val ctx = TaskContext("c")
        val step = step("s1")
        ctx.record(step, ok = true, collected = emptyMap())
        ctx.record(step, ok = false, collected = emptyMap())
        assertEquals(2, ctx.historySnapshot().size)
        assertEquals(1, ctx.recentSuccesses().size)
    }

    // ── CollectSpec ────────────────────────────────────────────────

    @Test
    fun `collect longest number picks the longest`() {
        val spec = CollectSpec("version", CollectSpec.Mode.LONGEST_NUMBER)
        assertEquals("1.4.2", spec.extract(listOf("版本", "1.4.2", "123")))
        assertNull(spec.extract(listOf("沒有數字")))
    }

    @Test
    fun `collect text after label`() {
        val spec = CollectSpec("v", CollectSpec.Mode.TEXT_AFTER, "版本")
        assertEquals("1.4.2", spec.extract(listOf("版本 1.4.2 其他")))
        assertNull(spec.extract(listOf("沒有標籤")))
    }

    @Test
    fun `collect regex modes`() {
        val one = CollectSpec("b", CollectSpec.Mode.REGEX, """\d+%""")
        assertEquals("85%", one.extract(listOf("電量 85% 剩餘")))
        val all = CollectSpec("n", CollectSpec.Mode.REGEX_ALL, """\d""")
        assertEquals("123", all.extract(listOf("1 2 3")))
        // 壞正則不得崩潰
        assertNull(CollectSpec("x", CollectSpec.Mode.REGEX, "(").extract(listOf("abc")))
    }

    // ── TaskGraph ──────────────────────────────────────────────────

    @Test
    fun `graph flattens actions and conditions`() {
        val graph = TaskGraph(
            listOf(
                TaskNode.Action(step("a")),
                TaskNode.Condition(
                    guard = TaskGuard.TextContains("同意"),
                    thenBranch = listOf(TaskNode.Action(step("b"))),
                    elseBranch = listOf(TaskNode.Action(step("c")))
                ),
                TaskNode.Commit(step("d", irreversible = true))
            )
        )
        val flat = graph.flatten()
        assertEquals(4, flat.size)
        assertEquals(listOf("a", "b", "c", "d"), flat.map { it.nodeId })
        // 條件分支帶著守衛
        assertEquals(TaskGuard.TextContains("同意"), flat[1].guard)
        // 不可逆標記保留
        assertTrue(flat[3].irreversible)
    }

    @Test
    fun `graph loop body expands once with depth guard`() {
        val loop = TaskNode.Loop(
            body = listOf(TaskNode.Action(step("body"))),
            maxIterations = 5,
            until = TaskGuard.TextContains("完成")
        )
        val flat = TaskGraph(listOf(loop)).flatten()
        assertEquals(1, flat.size) // v1 平坦化只展開一次（迴圈由 runner 的守衛重跑）
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun step(id: String, irreversible: Boolean = false): TaskStep {
        val kind = com.wae.agent.core.ActionKind.TAP
        return TaskStep(
            nodeId = id,
            goal = com.wae.agent.core.LocalGoal(
                stepIdx = 0,
                goalId = id,
                goalText = "目標$id",
                canonicalIntentTag = kind.name,
                actionKind = kind
            ),
            irreversible = irreversible
        )
    }
}
