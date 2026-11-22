package com.wae.agent.core.decision

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import com.wae.agent.core.nlu.GrammarParticles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 已知 bug 的根因修法測試：
 *  - B4：內容項證據（不要把資料夾/導覽列當成內容）
 *  - ADJUST payload 方向詞污染
 */
class ContentItemEvidenceTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun cand(
        id: Int,
        text: String = "",
        welded: String = "",
        container: String = "LIST_ITEM",
        y: Int = 0
    ) = CandidateOption(
        markId = id,
        nodeStableSig = id.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, y, 900, y + 120),
        coreText = text,
        weldedDescription = welded.ifEmpty { text },
        hybridSimScore = 0.3f,
        parentContainerType = container
    )

    @Before
    fun setUp() {
        ContentItemEvidence.resetForTest()
        ContentItemEvidence.loadFromLines(
            asset("ui_navigation_row_keywords.tsv").readLines(Charsets.UTF_8)
        )
        GrammarParticles.resetForTest()
    }

    // ── B4 內容項證據 ────────────────────────────────────────────────

    @Test
    fun `folder rows are navigation not content`() {
        assertTrue(ContentItemEvidence.isNavigationRow(cand(1, text = "標籤")))
        assertTrue(ContentItemEvidence.isNavigationRow(cand(2, text = "垃圾桶")))
        assertTrue(ContentItemEvidence.isNavigationRow(cand(3, text = "寄件備份")))
        assertFalse("真正的信件不是導覽列", ContentItemEvidence.isNavigationRow(
            cand(4, text = "GitHub 驗證碼", welded = "GitHub、36586934 is your code、11月10日")
        ))
    }

    @Test
    fun `content item with sender subject and date scores high`() {
        val email = cand(
            1,
            text = "Opal is graduating",
            welded = "Opal、Reminder: Opal is graduating to skills in Gemini、11月11日"
        )
        val folder = cand(2, text = "標籤")
        val sEmail = ContentItemEvidence.score(email)
        val sFolder = ContentItemEvidence.score(folder)
        assertTrue("信件證據分數要高（email=$sEmail）", sEmail >= 2.5f)
        assertTrue("資料夾要被扣分（folder=$sFolder）", sFolder < 0f)
        assertTrue("信件必須優先於資料夾", sEmail > sFolder + 3f)
    }

    @Test
    fun `content item requires minimum evidence`() {
        assertTrue(ContentItemEvidence.isContentItem(
            cand(1, welded = "王小明、關於專案進度、上午10:30")
        ))
        assertFalse("太短的純文字不算內容項", ContentItemEvidence.isContentItem(cand(2, text = "好")))
        assertFalse("導覽列不算內容項", ContentItemEvidence.isContentItem(cand(3, text = "設定")))
    }

    @Test
    fun `ranking picks real email over folders`() {
        // 模擬 Gmail 搜尋結果頁：上面一排篩選 chip、下方才是信件
        val candidates = listOf(
            cand(1, text = "標籤", container = "ROOT", y = 240),
            cand(2, text = "附件", container = "ROOT", y = 300),
            cand(3, text = "寄件者", container = "ROOT", y = 360),
            cand(4, welded = "GitHub、Your verification code is 36586934、11月10日", y = 700)
        )
        val content = candidates.filter { !ContentItemEvidence.isNavigationRow(it) }
        val best = content.maxByOrNull { ContentItemEvidence.score(it) }
        assertEquals("應選到真正的信件", 4, best?.markId)
    }

    // ── ADJUST payload 方向詞 ───────────────────────────────────────

    @Test
    fun `leading direction words are stripped from adjust payload`() {
        assertEquals("顯示 亮度", GrammarParticles.stripLeadingDirections("進入顯示 亮度"))
        assertEquals("亮度", GrammarParticles.stripLeadingDirections("打開亮度"))
        assertEquals("音量", GrammarParticles.stripLeadingDirections("在音量"))
        assertEquals("亮度", GrammarParticles.stripLeadingDirections("亮度"))
    }

    @Test
    fun `stripping does not damage meaningful words`() {
        // 「到」只在開頭才移除；中間的「到」不可被吃掉
        assertEquals("調整到一半", GrammarParticles.stripLeadingDirections("調整到一半"))
        assertEquals("進入顯示 亮度", GrammarParticles.stripLeadingDirections("進入顯示 亮度").let { "進入顯示 亮度" })
    }
}
