package com.wae.agent.core.component

import com.wae.agent.core.ActionKind
import com.wae.agent.core.ActionKeywords
import com.wae.agent.core.ActionOps
import com.wae.agent.core.FineOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 「調整數值」意圖解析 + 幾何規劃 + 動作映射測試。
 *
 * 這一層是純函式，所以可以把真實使用者講法（含容易誤判的說法）全部固定下來，
 * 避免之後調整詞表時悄悄回歸。
 */
class ValueAdjustTest {

    /** 數值調整詞表全部來自在資料庫，測試也從同一個檔案載入（改了 TSV 測試會立刻反映） */
    @Before
    fun loadLexicon() {
        ValueAdjustLexicon.resetForTest()
        val candidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/ui_value_adjust_lexicon.tsv"),
            File("app/src/main/assets/open_source_ai_databases/ui_value_adjust_lexicon.tsv"),
            File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/ui_value_adjust_lexicon.tsv")
        )
        val file = candidates.first { it.exists() }
        ValueAdjustLexicon.loadFromLines(file.readLines(Charsets.UTF_8))
        assertTrue("數值調整語彙庫應載入成功", ValueAdjustLexicon.isLoaded())
    }

    // ── 意圖解析 ────────────────────────────────────────────────────────

    @Test
    fun `relative direction with small magnitude`() {
        val i = ValueIntent.parse("把亮度調高一點")
        assertTrue(i.requested)
        assertEquals(1, i.direction)
        assertEquals(0.10f, i.deltaFraction)
        assertTrue("目標名詞應為亮度，實際='${i.targetNoun}'", i.targetNoun.contains("亮度"))
    }

    @Test
    fun `relative direction downwards`() {
        val i = ValueIntent.parse("音量降低一些")
        assertEquals(-1, i.direction)
        assertEquals(0.10f, i.deltaFraction)
        assertTrue(i.targetNoun.contains("音量"))
    }

    @Test
    fun `verb plus percent is strong evidence`() {
        val i = ValueIntent.parse("把亮度調到 70%")
        assertEquals(0.70f, i.targetFraction!!, 0.001f)
        assertEquals("percent+verb", i.fractionSource)
        assertEquals(0, i.direction)
    }

    @Test
    fun `bare percent is weak evidence`() {
        val i = ValueIntent.parse("點開 50% 折扣信件")
        assertEquals("percent", i.fractionSource)
        // 弱證據不應被當成「調整數值」指令
        assertNull(ActionKeywords.detect("點開 50% 折扣信件"))
    }

    @Test
    fun `verb plus raw number`() {
        val i = ValueIntent.parse("把字體大小設為 30")
        assertEquals(30f, i.targetRaw!!, 0.001f)
        assertTrue(i.targetNoun.contains("字體"))
    }

    @Test
    fun `max min half words`() {
        assertEquals(1f, ValueIntent.parse("螢幕亮度開到最大").targetFraction!!, 0.001f)
        assertEquals(0f, ValueIntent.parse("音量調到最小").targetFraction!!, 0.001f)
        assertEquals(0.5f, ValueIntent.parse("設成一半").targetFraction!!, 0.001f)
    }

    @Test
    fun `english word boundary is respected`() {
        // download 內含 down、upload 內含 up，不能誤判成調高/調低
        val i = ValueIntent.parse("download the file and upload it")
        assertFalse("不應判定為調整數值，matched='${i.matched}'", i.requested)
        assertNull(ActionKeywords.detect("download the file and upload it"))
    }

    @Test
    fun `english relative works`() {
        val i = ValueIntent.parse("turn up the brightness a bit")
        assertEquals(1, i.direction)
    }

    @Test
    fun `navigate to number is not a value adjustment`() {
        val i = ValueIntent.parse("導航到101大樓")
        assertFalse("「到＋數字」不該被當成調數值，matched='${i.matched}'", i.requested)
    }

    @Test
    fun `action keywords route to adjust value`() {
        val hit = ActionKeywords.detect("把亮度調高一點")
        assertNotNull(hit)
        assertEquals(ActionKind.ADJUST_VALUE, hit!!.kind)
        assertTrue(hit.payload.contains("亮度"))

        // 既有動作不能被搶走
        assertEquals(ActionKind.LONG_PRESS, ActionKeywords.detect("長按這個")?.kind)
        assertEquals(ActionKind.SCROLL_UP, ActionKeywords.detect("往上滑")?.kind)
        assertEquals(ActionKind.REPLACE_TEXT, ActionKeywords.detect("替換成咖啡")?.kind)
    }

    @Test
    fun `adjust value maps to compact fine op`() {
        val op = ActionOps.of(ActionKind.ADJUST_VALUE)
        assertEquals(FineOp.I_SET_VALUE, op.fine)
        assertEquals("I:VAL", op.compact())
        assertTrue("短碼應維持精簡", op.compact().length <= 14)
    }

    // ── 幾何與目標值 ────────────────────────────────────────────────────

    @Test
    fun `desired value handles absolute and relative`() {
        val abs = ValueIntent.parse("調到 70%")
        assertEquals(70f, ValueAdjustPlanner.desiredValue(abs, 0f, 100f, 20f), 0.001f)

        val up = ValueIntent.parse("調高一點") // 預設幅度 0.15（無「一點」的上下文時）
        assertTrue(ValueAdjustPlanner.desiredValue(up, 0f, 100f, 50f) > 50f)

        val upSmall = ValueIntent.parse("調高一點")
        assertEquals(0.10f, upSmall.deltaFraction)
        assertEquals(60f, ValueAdjustPlanner.desiredValue(upSmall, 0f, 100f, 50f), 0.001f)

        val downSmall = ValueIntent.parse("調低一點")
        assertEquals(40f, ValueAdjustPlanner.desiredValue(downSmall, 0f, 100f, 50f), 0.001f)
    }

    @Test
    fun `desired value clamps to range`() {
        val up = ValueIntent.parse("調高很多")
        assertEquals(100f, ValueAdjustPlanner.desiredValue(up, 0f, 100f, 90f), 0.001f)
        val down = ValueIntent.parse("調低很多")
        assertEquals(0f, ValueAdjustPlanner.desiredValue(down, 0f, 100f, 5f), 0.001f)
    }

    @Test
    fun `fraction of value`() {
        assertEquals(0.5f, ValueAdjustPlanner.fractionOf(50f, 0f, 100f), 0.001f)
        assertEquals(0f, ValueAdjustPlanner.fractionOf(-5f, 0f, 100f), 0.001f)
        assertEquals(1f, ValueAdjustPlanner.fractionOf(500f, 0f, 100f), 0.001f)
        assertEquals(0f, ValueAdjustPlanner.fractionOf(5f, 10f, 10f), 0.001f)
    }

    @Test
    fun `drag stroke maps values to track coordinates`() {
        val bounds = intArrayOf(100, 500, 900, 560)
        // 無內縮：0 在軌道最左、50 在中點
        val left = ValueAdjustPlanner.dragStroke(bounds, 0f, 50f, 0f, 100f, insetPx = 0f)
        assertEquals(100f, left.startX, 0.01f)
        assertEquals(500f, left.endX, 0.01f)

        // 內縮 40px：可用軌道 140..860，中點 500
        val mid = ValueAdjustPlanner.dragStroke(bounds, 0f, 50f, 0f, 100f, insetPx = 40f)
        assertEquals(140f, mid.startX, 0.01f)
        assertEquals(500f, mid.endX, 0.01f)
        assertEquals(530f, mid.startY, 0.01f)

        val toMax = ValueAdjustPlanner.dragStroke(bounds, 0f, 100f, 0f, 100f, insetPx = 40f)
        assertEquals(860f, toMax.endX, 0.01f)
    }

    @Test
    fun `drag stroke never inverts`() {
        val bounds = intArrayOf(100, 500, 900, 560)
        val s = ValueAdjustPlanner.dragStroke(bounds, 90f, 10f, 0f, 100f, insetPx = 0f)
        assertTrue(s.startX > s.endX)
    }

    @Test
    fun `tolerance follows range span`() {
        assertTrue(ValueAdjustPlanner.withinTolerance(50f, 52f, 0f, 100f))
        assertFalse(ValueAdjustPlanner.withinTolerance(50f, 70f, 0f, 100f))
        // 量程很大時容忍值放大（255 級亮度）
        assertTrue(ValueAdjustPlanner.withinTolerance(120f, 125f, 0f, 255f))
    }

    @Test
    fun `degenerate range never counts as success`() {
        // 真實踩過的坑：載入中的進度條量程 0~0，拖曳 0→0 竟被當成成功 → 任務誤報 SUCCESS
        assertFalse(ValueAdjustPlanner.withinTolerance(0f, 0f, 0f, 0f))
        assertFalse(ValueAdjustPlanner.withinTolerance(5f, 5f, 5f, 5f))
    }

    @Test
    fun `describe is human readable`() {
        val d = ValueAdjustPlanner.describe(ValueIntent.parse("調高一點"), 0f, 100f, 40f)
        assertTrue("實際=$d", d.contains("調高"))
        assertTrue(d.contains("40.00"))
    }

    // ── 指令拆解接線 ────────────────────────────────────────────────────

    @Test
    fun `decomposed brightness command becomes adjust value`() {
        val goals = com.wae.agent.core.WaeV26PipelineOrchestrator
            .decomposeCommandIntoLocalGoals("把亮度調高一點")
        val g = goals.firstOrNull { it.actionKind == ActionKind.ADJUST_VALUE }
        assertNotNull("應拆出 ADJUST_VALUE 目標，實際=${goals.map { it.actionKind }}", g)
        assertTrue("參數應是「亮度」而非整句，實際='${g!!.inputPayload}'", g.inputPayload.contains("亮度"))
    }

    @Test
    fun `plain tap and search are untouched`() {
        val goals = com.wae.agent.core.WaeV26PipelineOrchestrator
            .decomposeCommandIntoLocalGoals("點開第一封信")
        assertTrue("一般點擊不應變成數值調整", goals.none { it.actionKind == ActionKind.ADJUST_VALUE })
    }
}
