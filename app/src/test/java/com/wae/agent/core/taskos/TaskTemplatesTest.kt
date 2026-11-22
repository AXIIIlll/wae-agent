package com.wae.agent.core.taskos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S33：Task OS 的模板與槽位（資料驅動層）。
 */
class TaskTemplatesTest {

    @Before
    fun setUp() {
        TaskTemplates.resetForTest()
        SlotFiller.resetForTest()
    }

    @Test
    fun `loads templates and verbs and skips bad rows`() {
        TaskTemplates.loadFromLines(
            listOf(
                "# 註解",
                "template\tv1\t(?i)版本\tLAUNCH_APP:設定|TAP:關於",
                "template\tv2\t壞正則([\tLAUNCH_APP:設定", // 壞 regex → 跳過
                "template\tonly-two-columns\tx",
                "verb\ttap\tTAP",
                "verb\topen\tLAUNCH_APP",
                "verb\tbad\tNOT_A_KIND"
            )
        )
        assertEquals(1, TaskTemplates.count())
        assertEquals(listOf("v1"), TaskTemplates.ids())
        assertEquals(2, TaskTemplates.verbCount())
    }

    @Test
    fun `match returns first matching template in order`() {
        TaskTemplates.loadFromLines(
            listOf(
                "template\tfirst\t(?i)版本\tLAUNCH_APP:設定",
                "template\tsecond\t(?i)版本|關於\tLAUNCH_APP:設定"
            )
        )
        val t = TaskTemplates.match("查一下版本")
        assertNotNull(t)
        assertEquals("first", t!!.id)
        assertNull(TaskTemplates.match("完全不相關"))
    }

    @Test
    fun `parse steps supports collect irreversible and guard`() {
        TaskTemplates.loadFromLines(
            listOf("verb\ttap\tTAP", "verb\tback\tBACK", "verb\ttype\tTYPE")
        )
        val steps = TaskTemplates.parseSteps(
            "tp1",
            "TAP:關於|COLLECT:LONGEST_NUMBER:version|IRREVERSIBLE|BACK|" +
                "GUARD:TEXT:同意|TYPE:\$version",
            stepIdxStart = 3
        )
        assertEquals(3, steps.size)
        // 第一步是 TAP，且掛了 collect
        assertEquals(com.wae.agent.core.ActionKind.TAP, steps[0].goal.actionKind)
        assertEquals("version", steps[0].collect?.name)
        assertEquals(CollectSpec.Mode.LONGEST_NUMBER, steps[0].collect?.mode)
        assertTrue(steps[0].irreversible)
        assertEquals(3, steps[0].goal.stepIdx)
        // 第二步 BACK
        assertEquals(com.wae.agent.core.ActionKind.BACK, steps[1].goal.actionKind)
        // 第三步 TYPE，帶守衛與 $version payload
        assertEquals(com.wae.agent.core.ActionKind.TYPE, steps[2].goal.actionKind)
        assertEquals("\$version", steps[2].goal.inputPayload)
        assertEquals(TaskGuard.TextContains("同意"), steps[2].guard)
    }

    @Test
    fun `parse steps skips unknown verb instead of failing`() {
        TaskTemplates.loadFromLines(listOf("verb\ttap\tTAP"))
        val steps = TaskTemplates.parseSteps("tp", "TAP:首頁|UNKNOWN_VERB:x|TAP:設定")
        assertEquals(2, steps.size)
        assertEquals("點選首頁", steps[0].goal.goalText)
        assertEquals("點選設定", steps[1].goal.goalText)
    }

    @Test
    fun `parse steps builds payload for type and launch`() {
        TaskTemplates.loadFromLines(
            listOf("verb\ttype\tTYPE", "verb\tlaunch_app\tLAUNCH_APP", "verb\twait\tWAIT")
        )
        val steps = TaskTemplates.parseSteps("tp", "LAUNCH_APP:設定|TYPE:台北101|WAIT:")
        assertEquals("打開設定", steps[0].goal.goalText)
        assertEquals("設定", steps[0].goal.inputPayload)
        assertEquals("輸入台北101", steps[1].goal.goalText)
        assertEquals("台北101", steps[1].goal.inputPayload)
        assertEquals("800", steps[2].goal.inputPayload) // 預設等待時間
    }

    @Test
    fun `slot filler extracts slots`() {
        SlotFiller.loadFromLines(
            listOf(
                "slot\tapp\t(?:打開|開啟)([^，,。\\s]{1,12})",
                "slot\tquery\t(?:搜尋)([^，,。\\s]{1,20})",
                "slot\tbad\t("
            )
        )
        assertEquals(2, SlotFiller.count())
        val slots = SlotFiller.extract("打開設定，搜尋台北101")
        assertEquals("設定", slots["app"])
        assertEquals("台北101", slots["query"])
        assertTrue(SlotFiller.extract("").isEmpty())
    }
}
