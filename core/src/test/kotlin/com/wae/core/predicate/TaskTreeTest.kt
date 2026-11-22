package com.wae.core.predicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任務樹（HTN 最小版）的契約測試。
 *
 * 驗的是使用者最在意的三件事：
 *  1. **混合指令**能不能被表示（不新增任何句型）
 *  2. 展開後是不是**可執行的葉子序列**
 *  3. 上限（深度／葉子）有沒有生效
 */
class TaskTreeTest {

    @org.junit.Before
    fun installWords() {
        LanguageWords.install(
            separators = emptyList(),
            leads = emptyList(),
            searchWords = emptySet(),
            labels = mapOf("share" to "分享", "send" to "送出", "new" to "新增", "save" to "儲存")
        )
    }

    @Test
    fun `transfer expands to six executable leaves`() {
        val tree = TaskLibrary.transfer("相簿", "上週的照片", "媽媽")
        val e = TaskExpander.expand(tree)
        assertEquals(6, e.leaves.size)
        assertEquals(TaskNode.Op.LAUNCH, e.leaves[0].op)
        assertEquals(TaskNode.Op.FIND, e.leaves[1].op)
        assertEquals(TaskNode.Op.SHARE, e.leaves[3].op)
        assertEquals(TaskNode.Op.SEND, e.leaves.last().op)
        assertFalse(e.truncated)
    }

    @Test
    fun `create expands with one leaf per field`() {
        val tree = TaskLibrary.create(
            app = "提醒",
            fields = listOf("標題" to "開會", "時間" to "明天 09:00")
        )
        val e = TaskExpander.expand(tree)
        // LAUNCH + NEW + 2×FILL + SAVE
        assertEquals(5, e.leaves.size)
        assertEquals(2, e.leaves.count { it.op == TaskNode.Op.FILL })
        assertEquals("明天 09:00", e.leaves.first { it.op == TaskNode.Op.FILL && it.target == "時間" }.arg)
    }

    @Test
    fun `mixed instruction is just a Seq of two task types`() {
        // 「把上週的照片傳給媽媽，然後提醒她明天九點開會」
        val tree = TaskNode.Seq(
            listOf(
                TaskLibrary.transfer("相簿", "上週的照片", "媽媽"),
                TaskLibrary.create("提醒", listOf("標題" to "開會", "時間" to "明天 09:00"))
            )
        )
        val e = TaskExpander.expand(tree)
        assertEquals(11, e.leaves.size)          // 6 + 5
        assertEquals(TaskNode.Op.LAUNCH, e.leaves[0].op)
        assertEquals(TaskNode.Op.LAUNCH, e.leaves[6].op)
        assertTrue("混合指令不需要任何新句型", e.leaves.any { it.op == TaskNode.Op.SHARE })
        assertTrue(e.leaves.any { it.op == TaskNode.Op.SAVE })
    }

    @Test
    fun `leaf budget truncates and marks it`() {
        val tree = TaskNode.Seq(
            listOf(
                TaskLibrary.transfer("相簿", "照片", "媽媽"),
                TaskLibrary.create("提醒", listOf("a" to "1", "b" to "2", "c" to "3")),
                TaskLibrary.transfer("檔案", "報告", "老王")
            )
        )
        val e = TaskExpander.expand(tree, TaskExpander.Budget(maxLeaves = 8))
        assertTrue(e.truncated)
        assertEquals(8, e.leaves.size)
    }

    @Test
    fun `unknown task falls back to ask generic`() {
        val e = TaskExpander.expand(TaskLibrary.generic("幫我訂機票"))
        assertEquals(1, e.leaves.size)
        assertEquals(TaskNode.Op.ASK, e.leaves[0].op)
        assertEquals("幫我訂機票", e.leaves[0].target)
    }

    @Test
    fun `groundable uses the semantic score against the screen`() {
        val leaf = TaskNode.Leaf(TaskNode.Op.PICK, "媽媽")
        val screen = listOf("ROW 媽媽", "ROW 老王", "BUTTON 送出")
        // 只有「媽媽」那行分數夠高
        val score: (String, String) -> Float = { a, b -> if (b.contains(a)) 0.9f else 0.1f }
        assertTrue(TaskExpander.groundable(leaf, score, screen))
        assertFalse(TaskExpander.groundable(TaskNode.Leaf(TaskNode.Op.PICK, "阿嬤"), score, screen))
    }

    @Test
    fun `cond becomes guard leaf plus branch`() {
        val guard = Phi.Exists(listOf(Atom.Observable("wifi")))
        val tree = TaskNode.Cond(
            guard,
            TaskLibrary.state("熱點", true),
            TaskLibrary.generic("先開 WiFi")
        )
        val e = TaskExpander.expand(tree)
        assertEquals(TaskNode.Op.GUARD, e.leaves[0].op)
        assertTrue(e.leaves.size >= 2)
    }
}
