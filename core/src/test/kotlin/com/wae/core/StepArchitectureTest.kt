package com.wae.core

import com.wae.core.step.ClickResolver
import com.wae.core.step.ElementRef
import com.wae.core.step.Escalation
import com.wae.core.step.RuleTaskPlanner
import com.wae.core.step.StepContract
import com.wae.core.step.StepDecision
import com.wae.core.step.StepGoal
import com.wae.core.step.StepKind
import com.wae.core.step.StepMemory
import com.wae.core.step.StepPolicy
import com.wae.core.step.TaskMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三層兩憶：界線的測試（schema／契約／點擊器／記憶／階梯）。
 *
 * 「不需要測」指的是不用真機跑；這些是純函式，成本近乎零，
 * 而且它們正是**最容易在重構時被悄悄改壞**的地方。
 */
class StepArchitectureTest {

    // ── schema 與仲裁 ──────────────────────────────────────────────

    @Test
    fun `query field step may not turn into tapping a list item`() {
        val goal = StepGoal(StepKind.QUERY_FIELD, "搜尋框")
        val bad = StepDecision(StepKind.LIST_ITEM, "第一支影片", 0.9f)
        assertNull(StepContract.validate(goal, bad))
        val good = StepDecision(StepKind.QUERY_FIELD, "搜尋框", 0.9f)
        assertTrue(StepContract.validate(goal, good) != null)
    }

    @Test
    fun `done only allows done`() {
        assertTrue(StepContract.isAllowed(StepKind.DONE, StepKind.DONE))
        assertFalse(StepContract.isAllowed(StepKind.DONE, StepKind.LIST_ITEM))
    }

    // ── 點擊器：確定性、不含模型 ────────────────────────────────────

    private val elements = listOf(
        ElementRef("1", "搜尋", "EditText", clickable = true),
        ElementRef("2", "設定", "TextView", clickable = false),
        ElementRef("3", "確定", "Button", clickable = true),
        ElementRef("4", "隱藏", "Button", clickable = true, visible = false)
    )

    @Test
    fun `exact text wins and invisible elements are ignored`() {
        val hit = ClickResolver.resolve(StepKind.QUERY_FIELD, "確定", elements)
        assertTrue(hit is ClickResolver.Result.Hit)
        assertEquals("3", (hit as ClickResolver.Result.Hit).element.id)
    }

    @Test
    fun `ambiguous text asks for disambiguation instead of guessing`() {
        val dup = listOf(
            ElementRef("a", "開啟", "Button", clickable = true),
            ElementRef("b", "開啟", "Button", clickable = true)
        )
        val result = ClickResolver.resolve(StepKind.SUBMIT, "開啟", dup)
        assertTrue(result is ClickResolver.Result.Ambiguous)
    }

    @Test
    fun `semantic role wins for submit`() {
        val els = listOf(
            ElementRef("a", "確定", "Button", clickable = true),
            ElementRef("b", "送出", "Button", clickable = true, semanticRole = "SUBMIT")
        )
        val hit = ClickResolver.resolve(StepKind.SUBMIT, "送出", els)
        assertEquals("b", (hit as ClickResolver.Result.Hit).element.id)
    }

    @Test
    fun `policy prefers search semantic role`() {
        val els = listOf(
            ElementRef("a", "放大鏡", "ImageButton", clickable = true, semanticRole = "SEARCH"),
            ElementRef("b", "首頁", "TextView", clickable = false)
        )
        val memory = StepMemory.bounded(StepGoal(StepKind.QUERY_FIELD, "搜尋"), els)
        val decision = StepPolicy.decideValidated(memory)
        assertEquals(StepKind.QUERY_FIELD, decision!!.kind)
    }

    @Test
    fun `missing target returns miss not a random click`() {
        assertTrue(ClickResolver.resolve(StepKind.LIST_ITEM, "不存在的東西", emptyList()) is ClickResolver.Result.Miss)
    }

    // ── 單步決策 ───────────────────────────────────────────────────

    @Test
    fun `policy finds the edit field for query step`() {
        val memory = StepMemory.bounded(StepGoal(StepKind.QUERY_FIELD, "搜尋框"), elements)
        val decision = StepPolicy.decideValidated(memory)
        assertEquals(StepKind.QUERY_FIELD, decision!!.kind)
    }

    @Test
    fun `policy stays inside the contract`() {
        val memory = StepMemory.bounded(StepGoal(StepKind.SET_TEXT, "藍牙"), elements)
        val decision = StepPolicy.decideValidated(memory)
        assertEquals(StepKind.SET_TEXT, decision!!.kind)
    }

    // ── 記憶：兩份不能互相越界 ─────────────────────────────────────

    @Test
    fun `task memory does not carry raw elements and compresses outcomes`() {
        var memory = TaskMemory(command = "看影片", goalText = "看影片")
        repeat(25) { memory = memory.withOutcome("step $it ok") }
        assertTrue("要壓縮到上限內", memory.outcomes.size <= 21)
        assertTrue("第一條應該是摘要", memory.outcomes.first().contains("已摘要"))
    }

    @Test
    fun `step memory bounds the element list and drops invisible ones`() {
        val many = (1..200).map { ElementRef("$it", "item$it", "TextView", clickable = true) } +
            ElementRef("x", "hidden", "TextView", clickable = true, visible = false)
        val memory = StepMemory.bounded(StepGoal(StepKind.LIST_ITEM, "第一"), many)
        assertEquals(StepMemory.MAX_ELEMENTS, memory.elements.size)
        assertFalse(memory.elements.any { it.id == "x" })
    }

    // ── 任務計畫與階梯 ─────────────────────────────────────────────

    @Test
    fun `rule planner turns a search goal into four steps`() {
        val steps = RuleTaskPlanner().plan(TaskMemory(command = "在設定裡搜尋藍牙", goalText = "在設定裡搜尋藍牙"))
        assertEquals(4, steps!!.size)
        assertEquals(StepKind.QUERY_FIELD, steps[0].kind)
        assertEquals("藍牙", steps[1].hint)
        assertEquals(StepKind.DONE, steps.last().kind)
    }

    @Test
    fun `planner falls back to single done step when no search term`() {
        val steps = RuleTaskPlanner().plan(TaskMemory(command = "看影片", goalText = "看影片"))
        assertEquals(listOf(StepKind.DONE), steps!!.map { it.kind })
    }

    @Test
    fun `escalation ladder ends with asking a human`() {
        val ladder = Escalation()
        val actions = (1..6).map { ladder.onStepFailed() }
        assertEquals(Escalation.Action.RETRY_MEANS, actions[0])
        assertEquals(Escalation.Action.RETRY_MEANS, actions[1])
        assertEquals(Escalation.Action.ALT_TARGET, actions[2])
        assertEquals(Escalation.Action.REPLAN, actions[3])
        assertEquals(Escalation.Action.REPLAN, actions[4])
        assertEquals(Escalation.Action.ASK_HUMAN, actions[5])
    }
}
