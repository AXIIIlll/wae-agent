package com.wae.agent.core.twin

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3a 編譯快路徑測試。
 *
 * 匹配主訊號是「元素選擇器 + 頁面姿態」，簽名相似度為輔
 * （實測：Maps 首頁每次簽名數量都不同，單靠簽名會漏命中）。
 */
class TwinFastPathTest {

    private val now = 1_700_000_000_000L
    private val searchBoxId = "com.google.android.apps.maps:id/search_omnibox_text_box"

    private fun step(
        pkg: String = "com.google.android.apps.maps",
        action: String = "TYPE",
        target: String = "在這裡搜尋",
        viewId: String = searchBoxId,
        role: String = "search_submit",
        posture: String = "SEARCH_RESULTS",
        ok: Boolean = true,
        ts: Long = now - 1000,
        sigsBefore: List<Long> = (1L..10L).toList()
    ) = TwinStep(
        pkg = pkg,
        spineBefore = 111,
        spineAfter = 222,
        action = action,
        target = target,
        posture = posture,
        ok = ok,
        ms = 1200,
        ts = ts,
        sigsBefore = sigsBefore,
        sigsAfter = (20L..30L).toList(),
        viewId = viewId,
        className = "android.widget.EditText",
        roleTag = role,
        gridX = 500,
        gridY = 300
    )

    private fun cand(
        markId: Int = 1,
        text: String = "在這裡搜尋",
        viewId: String = searchBoxId,
        role: String = "search_submit",
        clickable: Boolean = true,
        editable: Boolean = true
    ) = CandidateOption(
        markId = markId,
        nodeStableSig = markId.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = intArrayOf(0, 0, 100, 100),
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.5f,
        isClickable = clickable,
        isEditable = editable,
        viewId = viewId,
        className = "android.widget.EditText",
        roleTag = role,
        parentContainerType = "CONTAINER"
    )

    private fun match(
        history: List<TwinStep>,
        options: List<CandidateOption> = listOf(cand()),
        sigs: List<Long> = (1L..10L).toList(),
        pkg: String = "com.google.android.apps.maps",
        action: String = "TYPE",
        posture: String = "SEARCH_RESULTS",
        minPrior: Int = 2
    ) = TwinFastPath.match(
        pkg = pkg,
        action = action,
        posture = posture,
        sigsBefore = sigs,
        options = options,
        history = history,
        nowMs = now,
        minPriorSuccess = minPrior
    )

    @Test
    fun `hits when same selector has enough prior successes`() {
        val hit = match(history = listOf(step(), step()))
        assertNotNull("應命中", hit)
        assertTrue("應為選擇器一致", hit!!.selectorMatch)
        assertEquals(2, hit.priorSuccess)
        assertEquals(1, hit.candidate.markId)
    }

    @Test
    fun `rejects when prior successes below threshold`() {
        assertNull("只有一次成功紀錄，不該使用快路徑", match(history = listOf(step())))
    }

    @Test
    fun `rejects different package`() {
        assertNull(match(history = listOf(step(pkg = "com.other"), step(pkg = "com.other"))))
    }

    @Test
    fun `rejects different action`() {
        assertNull(match(history = listOf(step(action = "TAP"), step(action = "TAP"))))
    }

    @Test
    fun `rejects stale records`() {
        val old = now - 8L * 24 * 60 * 60 * 1000
        assertNull("超過 7 天應失效", match(history = listOf(step(ts = old), step(ts = old))))
    }

    @Test
    fun `rejects failed historical steps`() {
        assertNull(match(history = listOf(step(ok = false), step(ok = false))))
    }

    @Test
    fun `hits by signature similarity when view id is absent`() {
        // 沒有 viewId 的情況（例如 Flutter/Canvas），改用簽名相似度 + 文字
        val history = listOf(
            step(viewId = "", role = "", target = "搜尋", sigsBefore = (1L..10L).toList()),
            step(viewId = "", role = "", target = "搜尋", sigsBefore = (1L..10L).toList())
        )
        val hit = match(
            history = history,
            options = listOf(cand(text = "搜尋", viewId = "", role = ""))
        )
        assertNotNull("簽名相似 + 文字相同應可命中", hit)
        assertTrue(!hit!!.selectorMatch)
    }

    @Test
    fun `rejects when selector differs and signature dissimilar`() {
        val history = listOf(
            step(sigsBefore = (1L..10L).toList()),
            step(sigsBefore = (1L..10L).toList())
        )
        val hit = match(
            history = history,
            options = listOf(cand(text = "完全不同", viewId = "com.other:id/x", role = "other")),
            sigs = (100L..110L).toList()
        )
        assertNull("選擇器不同且簽名不相似 → 不命中", hit)
    }

    @Test
    fun `requires actionable candidate`() {
        val hit = match(
            history = listOf(step(), step()),
            options = listOf(cand(clickable = false, editable = false))
        )
        assertNull("不可操作的候選不能作為快路徑目標", hit)
    }

    @Test
    fun `locate prefers view id`() {
        val hit = match(history = listOf(step(), step()))!!
        val options = listOf(
            cand(markId = 7, text = "其他地方", viewId = "com.demo:id/other"),
            cand(markId = 8, text = "在這裡搜尋", viewId = searchBoxId)
        )
        assertEquals(8, TwinFastPath.locate(hit, options)?.markId)
    }

    @Test
    fun `selector summary prefers view id then role then text`() {
        assertTrue(TwinFastPath.selectorOf(step()).startsWith("id:"))
        assertTrue(TwinFastPath.selectorOf(step(viewId = "", role = "LIST_ITEM")).startsWith("role:"))
        assertTrue(
            TwinFastPath.selectorOf(step(viewId = "", role = "", target = "信件A")).startsWith("text:")
        )
    }
}
