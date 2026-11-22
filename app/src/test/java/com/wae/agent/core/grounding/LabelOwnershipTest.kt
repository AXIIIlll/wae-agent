package com.wae.agent.core.grounding

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LabelOwnership] 標籤歸屬測試（G1 / I3）。
 *
 * 覆蓋三種列型的 owner 推論：
 *  1. 開關列：文字 + 右側 checkable 開關 → owner 是開關（不是文字、不是整列）
 *  2. 圖示列：文字 + 一排圖示 → owner 是同列最近的圖示
 *  3. 列表列：整列可點 + 列尾箭頭 → owner 是整列（箭頭不會搶走）
 *
 * 另外覆蓋：文字自己可點（self）、無效 rect、找不到 owner、坏檔 bootstrap fallback。
 */
class LabelOwnershipTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = groundingRect(l, t, r, b)

    private fun cand(
        id: String,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        clickable: Boolean = true,
        text: String = "",
        checkable: Boolean = false,
        checked: Boolean = false,
        enabled: Boolean = true,
        score: Float = 0.5f
    ) = GroundingCandidate(
        id = id,
        channel = GroundingChannel.A11Y,
        text = text,
        bounds = rect(l, t, r, b),
        clickable = clickable,
        checkable = checkable,
        checked = checked,
        enabled = enabled,
        score = score
    )

    @Before
    fun setUp() {
        LabelOwnership.resetForTest()
    }

    @After
    fun tearDown() {
        LabelOwnership.resetForTest()
    }

    // ── 列型分類 ──────────────────────────────────────────────────

    @Test
    fun classifyRowsByStructure() {
        val switchRow = listOf(
            cand("label", 40, 200, 500, 240, clickable = false, text = "Wi-Fi"),
            cand("switch", 900, 195, 1040, 245, checkable = true, checked = true)
        )
        assertEquals(LabelOwnership.RowType.SWITCH_ROW, LabelOwnership.classifyRow(switchRow, 1080))

        val iconRow = listOf(
            cand("label", 40, 300, 300, 340, clickable = false, text = "動作"),
            cand("icon1", 700, 300, 760, 360),
            cand("icon2", 800, 300, 860, 360)
        )
        assertEquals(LabelOwnership.RowType.ICON_ROW, LabelOwnership.classifyRow(iconRow, 1080))

        val listRow = listOf(
            cand("row", 40, 500, 1040, 580, text = ""),
            cand("label", 200, 520, 600, 560, clickable = false, text = "設定項目"),
            cand("chevron", 980, 515, 1020, 565)
        )
        assertEquals(LabelOwnership.RowType.LIST_ROW, LabelOwnership.classifyRow(listRow, 1080))

        assertEquals(LabelOwnership.RowType.LIST_ROW, LabelOwnership.classifyRow(emptyList(), 1080))
    }

    // ── 三種列型的 owner ──────────────────────────────────────────

    @Test
    fun switchRowOwnerIsTheCheckableControl() {
        val textRect = rect(40, 200, 500, 240)
        val nodes = listOf(
            cand("label", 40, 200, 500, 240, clickable = false, text = "Wi-Fi"),
            cand("switch", 900, 195, 1040, 245, checkable = true, checked = true)
        )
        val ownership = LabelOwnership.ownerOf(textRect, nodes, 1080)
        assertEquals(LabelOwnership.RowType.SWITCH_ROW, ownership.rowType)
        assertNotNull(ownership.owner)
        assertEquals("switch", ownership.owner?.id)
        assertTrue("confidence=${ownership.confidence}", ownership.confidence > 0f)
        assertTrue("evidence=${ownership.evidence}", ownership.evidence.startsWith("switch:"))
    }

    @Test
    fun iconRowOwnerIsNearestIcon() {
        val textRect = rect(40, 300, 300, 340)
        val nodes = listOf(
            cand("label", 40, 300, 300, 340, clickable = false, text = "動作"),
            cand("icon1", 800, 300, 860, 360),
            cand("icon2", 700, 300, 760, 360)
        )
        val ownership = LabelOwnership.ownerOf(textRect, nodes, 1080)
        assertEquals(LabelOwnership.RowType.ICON_ROW, ownership.rowType)
        assertEquals("icon2", ownership.owner?.id)
        assertTrue("evidence=${ownership.evidence}", ownership.evidence.startsWith("icon:"))
    }

    @Test
    fun listRowOwnerIsTheWholeClickableRow() {
        val textRect = rect(200, 520, 600, 560)
        val nodes = listOf(
            cand("row", 40, 500, 1040, 580, text = ""),
            cand("label", 200, 520, 600, 560, clickable = false, text = "設定項目"),
            cand("chevron", 980, 515, 1020, 565)
        )
        val ownership = LabelOwnership.ownerOf(textRect, nodes, 1080)
        assertEquals(LabelOwnership.RowType.LIST_ROW, ownership.rowType)
        assertEquals("row", ownership.owner?.id)
        assertTrue("evidence=${ownership.evidence}", ownership.evidence.startsWith("list:"))
    }

    // ── 邊界情境 ──────────────────────────────────────────────────

    @Test
    fun actionableTextOwnsItself() {
        val textRect = rect(100, 100, 300, 150)
        val nodes = listOf(
            cand("button", 100, 100, 300, 150, text = "搜尋"),
            cand("elsewhere", 900, 900, 1040, 960)
        )
        val ownership = LabelOwnership.ownerOf(textRect, nodes, 1080)
        assertEquals("button", ownership.owner?.id)
        assertEquals(1f, ownership.confidence, 0.0001f)
        assertEquals("self:actionable_text", ownership.evidence)
    }

    @Test
    fun invalidTextRectGivesNullOwner() {
        val nodes = listOf(cand("button", 100, 100, 300, 150, text = "搜尋"))
        val ownership = LabelOwnership.ownerOf(null, nodes, 1080)
        assertNull(ownership.owner)
        assertEquals("invalid:text_rect", ownership.evidence)
        assertTrue(ownership.evidence.startsWith("invalid:"))
    }

    @Test
    fun noSameRowClickableGivesNullOwner() {
        val textRect = rect(100, 100, 300, 150)
        val nodes = listOf(
            cand("label", 100, 100, 300, 150, clickable = false, text = "孤立文字"),
            cand("far", 900, 900, 1040, 960)
        )
        val ownership = LabelOwnership.ownerOf(textRect, nodes, 1080)
        assertNull(ownership.owner)
        assertTrue(ownership.evidence.startsWith("no:"))
    }

    // ── 資料驅動 / bootstrap ──────────────────────────────────────

    @Test
    fun bootstrapWeightsAreLoaded() {
        assertTrue("bootstrap 權重數=${LabelOwnership.weightCount()}", LabelOwnership.weightCount() > 20)
        assertTrue(
            "switch checkable 權重應為正",
            LabelOwnership.weightOf(LabelOwnership.RowType.SWITCH_ROW, "flag:checkable") > 0f
        )
    }

    @Test
    fun brokenWeightsFallBackToBootstrap() {
        LabelOwnership.loadFromLines(listOf("这不是合法的列", "只有一欄", "switch\tflag:checkable\tnot-a-number"))
        assertTrue("坏檔後權重數=${LabelOwnership.weightCount()}", LabelOwnership.weightCount() > 20)
        // fallback 後推論仍正常
        val nodes = listOf(
            cand("label", 40, 200, 500, 240, clickable = false, text = "Wi-Fi"),
            cand("switch", 900, 195, 1040, 245, checkable = true)
        )
        val ownership = LabelOwnership.ownerOf(rect(40, 200, 500, 240), nodes, 1080)
        assertEquals("switch", ownership.owner?.id)
    }

    @Test
    fun customWeightsOverrideBootstrap() {
        LabelOwnership.loadFromLines(
            listOf(
                "list\tflag:row_like\t5.0",
                "list\tflag:clickable\t5.0",
                "icon\tflag:icon_like\t5.0"
            )
        )
        assertEquals(3, LabelOwnership.weightCount())
        assertEquals(5f, LabelOwnership.weightOf(LabelOwnership.RowType.LIST_ROW, "flag:row_like"), 0.0001f)
        LabelOwnership.resetForTest()
        assertTrue(LabelOwnership.weightCount() > 20)
    }
}
