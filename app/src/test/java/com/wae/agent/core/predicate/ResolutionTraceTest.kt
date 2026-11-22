package com.wae.agent.core.predicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析軌跡的格式化測試（純函式，不碰 Android IO）。
 *
 * 為什麼要測格式：這份 TSV 之後要當評測資料用，欄位數錯一格、
 * 或使用者輸入裡有 tab／換行，整份資料就毀了——而且毀得很安靜。
 */
class ResolutionTraceTest {

    @Test
    fun `line has exactly ten fields in the documented order`() {
        val line = ResolutionTrace.formatLine(
            tsMs = 1_700_000_000_000L,
            pkg = "com.google.android.apps.maps",
            frameFp = "abcdef1234567890",
            query = "導航到公司",
            step = 3,
            action = "TAP(index=4)",
            candidates = 27,
            score = 0.842f,
            floor = 0.62f,
            note = "phi"
        )
        val cells = line.split('\t')
        assertEquals(10, cells.size)
        assertEquals("1700000000000", cells[0])
        assertEquals("com.google.android.apps.maps", cells[1])
        assertEquals("導航到公司", cells[3])
        assertEquals("3", cells[4])
        assertEquals("27", cells[6])
        assertEquals("0.842", cells[7])
        assertEquals("0.620", cells[8])
    }

    @Test
    fun `tabs and newlines in user text cannot break the table`() {
        val line = ResolutionTrace.formatLine(
            0L, "pkg", "fp", "打開\t設定\n然後", 1, "TAP\nbad", 2, -1f, -1f, ""
        )
        assertEquals(10, line.split('\t').size)
        assertTrue("換行不該留在單行資料裡", !line.contains('\n'))
        assertTrue("使用者文字裡的 tab 應被壓成空白", line.contains("打開 設定 然後"))
    }

    @Test
    fun `unknown score renders as empty instead of a fake number`() {
        val line = ResolutionTrace.formatLine(0L, "p", "f", "q", 1, "a", 0, -1f, -1f, "")
        val cells = line.split('\t')
        assertEquals("", cells[7])
        assertEquals("", cells[8])
    }

    @Test
    fun `long text is truncated so a single row cannot explode`() {
        val longQuery = "很".repeat(500)
        val line = ResolutionTrace.formatLine(0L, "p", "f", longQuery, 1, "a", 0, -1f, -1f, "")
        val cells = line.split('\t')
        assertEquals(120, cells[3].length)
    }

    @Test
    fun `header matches the number of columns`() {
        assertEquals(10, ResolutionTrace.header().split('\t').size)
    }

    @Test
    fun `trace is enabled by default when there is no context`() {
        assertTrue(ResolutionTrace.enabled(null))
    }
}
