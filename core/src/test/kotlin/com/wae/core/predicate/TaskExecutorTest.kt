package com.wae.core.predicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 葉子執行器：用假的 gateway 驗證「匹配決定指哪、執行器決定何時問」。 */
class TaskExecutorTest {

    private class FakeGateway(
        private val lines: List<String>,
        private val failOn: String? = null
    ) : TaskExecutor.ScreenGateway {
        val acted = ArrayList<String>()
        override fun screenLines(): List<String> = lines
        override fun find(target: String): Pair<Int, Float> {
            val i = lines.indexOfFirst { it.contains(target) }
            return if (i >= 0) i to 0.9f else -1 to 0.1f
        }
        override fun act(op: TaskNode.Op, target: String, arg: String): Boolean {
            if (target == failOn) return false
            acted.add("${op.name}:$target")
            return true
        }
    }

    @Test
    fun `runs all leaves in order`() {
        val tree = TaskLibrary.create("提醒", listOf("標題" to "開會", "時間" to "明天09:00"))
        val e = TaskExpander.expand(tree)
        val gw = FakeGateway(listOf("BUTTON 新增", "FIELD 標題", "FIELD 時間", "BUTTON 儲存"))
        val r = TaskExecutor.run(e, gw)
        assertEquals(TaskExecutor.Status.DONE, r.status)
        assertEquals(e.leaves.size, r.steps.size)
        assertTrue(gw.acted.first().startsWith("LAUNCH"))
    }

    @Test
    fun `missing target asks instead of clicking something else`() {
        val tree = TaskLibrary.transfer("相簿", "上週的照片", "媽媽")
        val e = TaskExpander.expand(tree)
        // 畫面上只有相簿，沒有照片也沒有媽媽
        val gw = FakeGateway(listOf("ROW 相簿"))
        val r = TaskExecutor.run(e, gw)
        assertEquals(TaskExecutor.Status.ASK, r.status)
        assertTrue("要指出卡在哪一個目標", r.question.contains("上週的照片"))
        assertTrue("不能對找不到的元素做 PICK", gw.acted.none { it.startsWith("PICK") && it.contains("上週的照片") })
    }

    @Test
    fun `failing leaf reports the exact step`() {
        val tree = TaskLibrary.create("提醒", listOf("標題" to "開會"))
        val e = TaskExpander.expand(tree)
        val gw = FakeGateway(listOf("BUTTON 新增", "FIELD 標題", "BUTTON 儲存"), failOn = "標題")
        val r = TaskExecutor.run(e, gw)
        assertEquals(TaskExecutor.Status.FAILED, r.status)
        assertTrue(r.failedAt >= 0)
        assertTrue(r.question.contains("標題"))
    }

    @Test
    fun `unknown leaf asks`() {
        val e = TaskExpander.expand(TaskLibrary.generic("幫我訂機票"))
        val r = TaskExecutor.run(e, FakeGateway(emptyList()))
        assertEquals(TaskExecutor.Status.ASK, r.status)
        assertTrue(r.question.contains("訂機票"))
    }
}
