package com.wae.agent.core.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Twin 最小閉環（M2）核心邏輯測試。
 *
 * 驗證四件事：
 *  1. 由執行軌跡建出的圖是正確的（狀態數 / 邊數 / 成功率）
 *  2. 重放能命中每一條已記錄的轉移（= 可編譯）
 *  3. 未入圖的步驟會被判定為 miss（= 仍需感知，誠實暴露覆蓋率）
 *  4. held-out 切分可用（避免用同一批資料自我驗證）
 */
class TwinStoreTest {

    private fun step(
        before: Long,
        after: Long,
        action: String = "TAP",
        target: String = "搜尋",
        ok: Boolean = true,
        ms: Long = 1200,
        ts: Long = 1000
    ) = TwinStep(
        pkg = "com.demo.app",
        spineBefore = before,
        spineAfter = after,
        action = action,
        target = target,
        posture = "LIST_FEED",
        ok = ok,
        ms = ms,
        ts = ts
    )

    @Test
    fun `graph stats count states edges and success rate`() {
        val steps = listOf(
            step(1, 2),
            step(2, 3),
            step(3, 4, ok = false)
        )
        val stats = TwinStore.stats(steps)
        assertEquals(4, stats.states)
        assertEquals(2, stats.edges) // 失敗那步不入圖
        assertEquals(3, stats.steps)
        assertEquals(2f / 3f, stats.successRate, 1e-6f)
        assertEquals(1200L, stats.avgStepMs)
        assertTrue(stats.summary().contains("Twin:"))
    }

    @Test
    fun `replay matches all recorded transitions`() {
        val steps = listOf(step(1, 2), step(2, 3), step(3, 4))
        val r = TwinStore.replay(steps)
        assertEquals(3, r.totalSteps)
        assertEquals(3, r.matchedSteps)
        assertTrue(r.success)
        // 重放成本是查表級（每步 1ms），遠低於現場感知
        assertEquals(3L, r.simulatedMs)
        assertEquals(3600L, r.actualMs)
        assertTrue(r.summary().contains("省"))
    }

    @Test
    fun `replay misses steps absent from graph`() {
        val train = listOf(step(1, 2))
        val test = listOf(step(1, 2), step(2, 9))
        val r = TwinStore.replay(test, graphSource = train)
        assertEquals(2, r.totalSteps)
        assertEquals(1, r.matchedSteps)
        assertFalse("未入圖的轉移必須被判為 miss", r.success)
    }

    @Test
    fun `held out split separates train and test`() {
        val steps = listOf(step(1, 2, ts = 100), step(2, 3, ts = 200), step(3, 4, ts = 300))
        val (train, test) = TwinStore.heldOut(steps, excludeAfterTs = 200)
        assertEquals(1, train.size)
        assertEquals(2, test.size)
    }

    @Test
    fun `json round trip preserves fields`() {
        val s = step(11, 22, action = "TYPE", target = "台北101", ok = true, ms = 850, ts = 999)
        val parsed = parseTwinStep(s.toJson().toString())
        assertNotNull(parsed)
        assertEquals(s, parsed)
    }

    @Test
    fun `duplicate transitions collapse into one edge`() {
        val steps = listOf(step(1, 2), step(1, 2), step(1, 2))
        assertEquals(1, TwinStore.buildEdges(steps).size)
        assertEquals(3, TwinStore.stats(steps).steps)
    }

    @Test
    fun `state abstraction matches same page type with different content`() {
        // 兩個不同的搜尋結果頁：頁面型態相同（結構簽名高度重疊），但 spineHash 不同
        val train = listOf(
            step(100, 200, action = "TYPE", target = "台北101").copy(
                sigsBefore = listOf(1, 2, 3, 4, 5, 6, 7, 8),
                sigsAfter = listOf(1, 2, 3, 4, 5, 9, 10, 11)
            )
        )
        val test = listOf(
            step(300, 400, action = "TYPE", target = "士林夜市").copy(
                sigsBefore = listOf(1, 2, 3, 4, 5, 6, 7, 8),
                sigsAfter = listOf(1, 2, 3, 4, 5, 12, 13, 14)
            )
        )
        val exactOnly = TwinStore.replay(test, graphSource = train, fuzzyThreshold = 1.01f) // 關閉模糊匹配
        assertEquals("精確匹配不該命中不同內容", 0, exactOnly.matchedSteps)

        val abstracted = TwinStore.replay(test, graphSource = train, fuzzyThreshold = 0.4f)
        assertEquals("狀態抽象後應可複用", 1, abstracted.matchedSteps)
        assertTrue(abstracted.success)
    }

    @Test
    fun `precondition match does not require identical postcondition`() {
        // 真實觀測：搜「台北101」vs「士林夜市」，前置狀態 Jaccard=1.0，後置只有 0.18
        val train = listOf(
            step(500, 600).copy(
                sigsBefore = (1L..25L).toList(),
                sigsAfter = (26L..57L).toList()
            )
        )
        val test = listOf(
            step(700, 800).copy(
                sigsBefore = (1L..25L).toList(),
                // 模擬「不同查詢 → 結果頁結構差異大」（實測 Jaccard 0.18）
                sigsAfter = (100L..126L).toList()
            )
        )
        val strict = TwinStore.replay(
            test, graphSource = train, fuzzyThreshold = 0.4f, requireAfterSimilarity = true
        )
        assertEquals("嚴格模式要求後置一致 → 不應命中", 0, strict.matchedSteps)

        val contract = TwinStore.replay(test, graphSource = train, fuzzyThreshold = 0.4f)
        assertEquals("前置條件相同即可重放（後置由執行期驗證）", 1, contract.matchedSteps)
        assertTrue(contract.success)
    }

    @Test
    fun `different precondition does not match`() {
        val train = listOf(
            step(1, 2).copy(sigsBefore = (1L..10L).toList(), sigsAfter = (11L..20L).toList())
        )
        val test = listOf(
            step(3, 4).copy(sigsBefore = (100L..120L).toList(), sigsAfter = (11L..20L).toList())
        )
        val r = TwinStore.replay(test, graphSource = train, fuzzyThreshold = 0.4f)
        assertEquals("前置狀態不同 → 不可重放", 0, r.matchedSteps)
        assertFalse(r.success)
    }

    @Test
    fun `cross app history never matches`() {
        val train = listOf(
            step(1, 2, action = "TAP").copy(
                pkg = "com.other.app",
                sigsBefore = (1L..10L).toList(),
                sigsAfter = (11L..20L).toList()
            )
        )
        val test = listOf(
            step(1, 2, action = "TAP").copy(
                pkg = "com.demo.app",
                sigsBefore = (1L..10L).toList(),
                sigsAfter = (11L..20L).toList()
            )
        )
        // 若不做套件過濾，精確鍵會誤命中（狀態哈希相同）——必須由 pkg 邊界擋住
        val r = TwinStore.replay(test, graphSource = train, fuzzyThreshold = 0.4f)
        assertEquals("跨 App 的歷史不得用於重放", 0, r.matchedSteps)
    }

    @Test
    fun `state similarity is jaccard of signature sets`() {        assertEquals(1f, TwinStore.stateSimilarity(listOf(1, 2), listOf(1, 2)), 1e-6f)
        assertEquals(0f, TwinStore.stateSimilarity(listOf(1, 2), listOf(3, 4)), 1e-6f)
        // 交集 2 / 聯集 5 = 0.4
        assertEquals(0.4f, TwinStore.stateSimilarity(listOf(1, 2, 3), listOf(2, 3, 4, 5)), 1e-6f)
        // 空簽名不可視為完全相似（資料缺失 ≠ 相同）
        assertEquals(0f, TwinStore.stateSimilarity(emptyList(), emptyList()), 1e-6f)
    }

    @Test
    fun `json round trip preserves spine signatures`() {
        val s = step(1, 2).copy(sigsBefore = listOf(7, 8, 9), sigsAfter = listOf(10, 11))
        val parsed = parseTwinStep(s.toJson().toString())
        assertNotNull(parsed)
        assertEquals(listOf(7L, 8L, 9L), parsed!!.sigsBefore)
        assertEquals(listOf(10L, 11L), parsed.sigsAfter)
    }

    @Test
    fun `chain coverage counts linked transitions`() {
        // 1→2→3 完整鏈
        val full = listOf(step(1, 2), step(2, 3), step(3, 4))
        assertEquals(1f, TwinStore.chainCoverage(full), 1e-6f)
        assertEquals(3, TwinStore.longestChain(full))

        // 1→2 然後 9→10（斷點）
        val broken = listOf(step(1, 2), step(9, 10))
        assertEquals(0f, TwinStore.chainCoverage(broken), 1e-6f)
        assertEquals(1, TwinStore.longestChain(broken))
    }

    @Test
    fun `single step chain is fully covered`() {
        assertEquals(1f, TwinStore.chainCoverage(listOf(step(1, 2))), 1e-6f)
        assertEquals(1, TwinStore.longestChain(listOf(step(1, 2))))
    }

    @Test
    fun `empty input is safe`() {
        val stats = TwinStore.stats(emptyList())
        assertEquals(0, stats.states)
        assertEquals(0, stats.edges)
        val r = TwinStore.replay(emptyList())
        assertTrue(r.success)
    }
}
