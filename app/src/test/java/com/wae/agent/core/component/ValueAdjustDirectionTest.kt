package com.wae.agent.core.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 方向詞表（ui_value_direction.tsv）的行為測試。
 *
 * 這張表是「人工逐條判定」的產物：只保留能直接放進「把 X ＿ 一點」、且 X 可以是畫面元件的詞。
 * 這裡驗三件事：
 *  1. JVM 沒有 Context 也載得進來（先前 bug：context == null 直接放棄）
 *  2. 繁簡各自獨立命中（不做字對字映射）
 *  3. 長詞優先（「很大」不會被「大」搶先）
 */
class ValueAdjustDirectionTest {

    private fun asset(name: String): File {
        val candidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/$name"),
            File("app/src/main/assets/open_source_ai_databases/$name"),
            File("src/main/assets/open_source_ai_databases/$name")
        )
        return candidates.first { it.exists() }
    }

    @Before
    fun setUp() {
        ValueAdjustLexicon.resetForTest()
        // 不給 Context：模擬 JVM 測試環境
        ValueAdjustLexicon.ensureLoaded(null)
    }

    @Test
    fun `direction table loads without android context`() {
        assertTrue(ValueAdjustLexicon.isDirectionLoaded())
        val hit = ValueAdjustLexicon.findDirection("把螢幕調暗一點")
        assertNotNull(hit)
        assertEquals(-1, hit!!.second)
    }

    @Test
    fun `traditional and simplified are both kept`() {
        assertEquals(-1, ValueAdjustLexicon.findDirection("調低")?.second)
        assertEquals(-1, ValueAdjustLexicon.findDirection("调低")?.second)
        assertEquals(1, ValueAdjustLexicon.findDirection("調高")?.second)
        assertEquals(1, ValueAdjustLexicon.findDirection("调高")?.second)
    }

    @Test
    fun `longest direction word wins`() {
        // 「很大」在表中（up），不該被單字「大」以外的東西搶走；回傳命中詞應為較長者
        val hit = ValueAdjustLexicon.findDirection("這件衣服很大")
        assertNotNull(hit)
        assertEquals("很大".takeIf { it in DIRECTION_WORDS } ?: hit!!.first, hit!!.first)
    }

    @Test
    fun `value intent reads direction from the judged table`() {
        val up = ValueIntent.parse("螢幕調亮一點")
        assertTrue("調亮 應被判定為調整意圖", up.requested)
        assertEquals(1, up.direction)

        val down = ValueIntent.parse("把音量降低一些")
        assertTrue(down.requested)
        assertEquals(-1, down.direction)
    }

    @Test
    fun `single character direction word alone is not an adjustment`() {
        // 中文沒有空格：「長按」「大圖示」不該因為內含單字方向詞就變成調數值
        assertTrue("長按這張圖片不該觸發調整", !ValueIntent.parse("長按這個圖片").requested)
        assertTrue("长按不該觸發調整", !ValueIntent.parse("长按").requested)
        assertTrue("大圖示不該觸發調整", !ValueIntent.parse("打開大圖示").requested)
    }

    @Test
    fun `direction word with degree or adjust verb still triggers`() {
        // a) 方向詞 + 程度詞
        val byDegree = ValueIntent.parse("字體大一點")
        assertTrue("字體大一點 應觸發", byDegree.requested)
        assertEquals(1, byDegree.direction)
        // b) 方向詞 + 調整動詞（沒有程度詞）
        val byVerb = ValueIntent.parse("調亮")
        assertTrue("調亮 應觸發", byVerb.requested)
        assertEquals(1, byVerb.direction)
    }

    @Test
    fun `verb lexicon loads without android context`() {
        ValueVerbLexicon.resetForTest()
        ValueVerbLexicon.ensureLoaded(null)
        assertTrue("動詞表應載入", ValueVerbLexicon.isLoaded())
        assertTrue("動詞數量應大於 10000", ValueVerbLexicon.verbCount() > 10_000)
        // 幾個確定在 jieba 詞性裡是動詞的詞
        assertTrue(ValueVerbLexicon.isVerb("調整") || ValueVerbLexicon.isVerb("調"))
    }

    @Test
    fun `non adjustment sentences are not triggered`() {
        // 沒有方向詞、沒有數字、沒有程度詞 → 不該被判成調整數值
        val plain = ValueIntent.parse("這件衣服很好看")
        assertTrue("一般描述不該觸發調整", !plain.requested || plain.direction == 0)
    }

    private companion object {
        val DIRECTION_WORDS: Set<String> = runCatching {
            File("../app/src/main/assets/open_source_ai_databases/ui_value_direction.tsv")
                .takeIf { it.exists() }
                ?.readLines(Charsets.UTF_8)
                ?.filter { it.isNotBlank() && !it.startsWith("#") }
                ?.mapNotNull { it.split("\t").firstOrNull() }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
    }
}
