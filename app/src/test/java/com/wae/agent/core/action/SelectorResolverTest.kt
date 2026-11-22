package com.wae.agent.core.action

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import com.wae.agent.core.twin.TwinStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P3 選擇器自癒：六級降級鏈，命中層級可回報（供遥測）。 */
class SelectorResolverTest {

    private fun step(
        viewId: String = "com.demo.mail:id/row",
        role: String = "LIST_ITEM",
        text: String = "信件A",
        x: Int = 500,
        y: Int = 1200
    ) = TwinStep(
        pkg = "com.demo.mail", spineBefore = 1, spineAfter = 2, action = "TAP",
        target = text, posture = "", ok = true, ms = 300, ts = 0,
        viewId = viewId, roleTag = role, gridX = x, gridY = y
    )

    private fun cand(
        markId: Int = 1,
        viewId: String = "",
        role: String = "LIST_ITEM",
        text: String = "信件A",
        x: Int = 500,
        y: Int = 1200,
        clickable: Boolean = true
    ) = CandidateOption(
        markId = markId,
        nodeStableSig = markId.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(x - 10, y - 10, x + 10, y + 10),
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.5f,
        isClickable = clickable,
        viewId = viewId,
        roleTag = role
    )

    @Test
    fun `level 0 exact view id`() {
        val r = SelectorResolver.resolve(
            step(), listOf(cand(viewId = "com.demo.mail:id/row"))
        )
        assertEquals(0, r?.level)
        assertEquals("viewId", r?.levelName)
    }

    @Test
    fun `level 1 view id tail when package prefix changed`() {
        val r = SelectorResolver.resolve(
            step(), listOf(cand(viewId = "com.demo.mail.v2:id/row"))
        )
        assertEquals(1, r?.level)
        assertEquals("viewIdTail", r?.levelName)
    }

    @Test
    fun `level 2 role and text`() {
        val r = SelectorResolver.resolve(
            step(viewId = ""), listOf(cand(viewId = "com.x:id/y", role = "LIST_ITEM", text = "信件A"))
        )
        assertEquals(2, r?.level)
    }

    @Test
    fun `level 3 text containment after copy change`() {
        val r = SelectorResolver.resolve(
            step(viewId = "", text = "信件A"),
            listOf(cand(viewId = "com.x:id/y", role = "CARD", text = "信件A（已讀）"))
        )
        assertEquals(3, r?.level)
    }

    @Test
    fun `level 4 same role nearest position`() {
        val r = SelectorResolver.resolve(
            step(viewId = "", text = "完全不同", x = 500, y = 1200),
            listOf(cand(markId = 7, viewId = "", role = "LIST_ITEM", text = "其他", x = 520, y = 1250))
        )
        assertEquals(4, r?.level)
    }

    @Test
    fun `returns null when nothing plausible`() {
        assertNull(
            SelectorResolver.resolve(
                step(viewId = "", role = "", text = "X"),
                listOf(cand(viewId = "", role = "BUTTON", text = "abc", x = 10, y = 10))
            )
        )
    }

    @Test
    fun `level 5 nearest actionable as last resort`() {
        val r = SelectorResolver.resolve(
            step(viewId = "", role = "", text = "X", x = 500, y = 1200),
            listOf(cand(viewId = "", role = "BUTTON", text = "abc", x = 510, y = 1210))
        )
        assertEquals(5, r?.level)
    }
}
