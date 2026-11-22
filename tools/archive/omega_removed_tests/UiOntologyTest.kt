package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 第 1 層：通用 UI 本體論。
 *
 * 守住：規則載入（含壞行）、class/id/container 命中、結構性先驗、
 * 可讀/可寫/可動作的分類，以及未知元素不亂猜（UNKNOWN）。
 */
class UiOntologyTest {

    @Before
    fun setUp() {
        UiOntology.resetForTest()
        UiOntology.loadFromLines(
            listOf(
                "utype\tTEXT_FIELD\tclass\tEditText",
                "utype\tSEARCH_BOX\tid\tsearch",
                "utype\tSEARCH_BOX\tid\tomnibox",
                "utype\tSWITCH_ROW\tclass\tSwitch",
                "utype\tSLIDER\tclass\tSeekBar",
                "utype\tLIST_ROW\tcontainer\tLIST_ITEM",
                "utype\tBUTTON\tclass\tButton",
                "utype\t壞行沒有四欄\tclass\tx",
                "utype\tNOT_A_TYPE\tclass\tY"
            )
        )
    }

    @Test
    fun `edit text class maps to text field`() {
        val m = UiOntology.classify(
            UiOntology.Input(className = "android.widget.EditText", editable = true)
        )
        assertEquals(UiOntology.UiType.TEXT_FIELD, m.type)
        assertTrue(m.confidence > 0f)
        assertTrue(m.evidence.isNotEmpty())
    }

    @Test
    fun `search box id wins over generic text field`() {
        val m = UiOntology.classify(
            UiOntology.Input(
                className = "android.widget.EditText",
                viewId = "com.app:id/search_omnibox_edit_text",
                editable = true
            )
        )
        assertEquals(UiOntology.UiType.SEARCH_BOX, m.type)
    }

    @Test
    fun `switch class maps to switch row`() {
        val m = UiOntology.classify(
            UiOntology.Input(className = "android.widget.Switch", checkable = true)
        )
        assertEquals(UiOntology.UiType.SWITCH_ROW, m.type)
    }

    @Test
    fun `structural prior works without rules`() {
        val m = UiOntology.classify(
            UiOntology.Input(className = "com.unknown.Custom", hasRange = true)
        )
        assertEquals(UiOntology.UiType.SLIDER, m.type)
    }

    @Test
    fun `list item container gives list row`() {
        val m = UiOntology.classify(
            UiOntology.Input(
                className = "android.widget.TextView",
                clickable = true,
                parentContainerType = "LIST_ITEM"
            )
        )
        assertEquals(UiOntology.UiType.LIST_ROW, m.type)
    }

    @Test
    fun `empty input is unknown`() {
        val m = UiOntology.classify(UiOntology.Input())
        assertEquals(UiOntology.UiType.UNKNOWN, m.type)
        assertEquals(0f, m.confidence, 1e-6f)
    }

    @Test
    fun `readable writable actionable sets`() {
        assertTrue(UiOntology.isReadable(UiOntology.UiType.TEXT_FIELD))
        assertTrue(UiOntology.isReadable(UiOntology.UiType.SWITCH_ROW))
        assertTrue(UiOntology.isWritable(UiOntology.UiType.TEXT_FIELD))
        assertTrue(UiOntology.isWritable(UiOntology.UiType.SLIDER))
        assertFalse(UiOntology.isWritable(UiOntology.UiType.TEXT_LABEL))
        assertTrue(UiOntology.isActionable(UiOntology.UiType.BUTTON))
        assertFalse(UiOntology.isActionable(UiOntology.UiType.TEXT_LABEL))
        assertFalse(UiOntology.isActionable(UiOntology.UiType.UNKNOWN))
    }

    @Test
    fun `rule count ignores bad rows`() {
        assertTrue(UiOntology.ruleCount() >= 5)
    }
}
