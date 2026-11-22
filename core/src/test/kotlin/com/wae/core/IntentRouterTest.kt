package com.wae.core

import com.wae.core.intent.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目的型意圖的路由（「我想看影片」→ VIDEO_MEDIA + HOME）。
 *
 * 這裡刻意只測路由本身：**選哪個 App 是 App 層的事**（要看裝置裝了什麼），
 * 但「這句話屬於哪個類別、進去之後該做什麼」是純邏輯，必須在 JVM 釘住。
 */
class IntentRouterTest {

    private val lines = sequenceOf(
        "# 註解",
        "看影片\tVIDEO_MEDIA\thome",
        "看劇\tVIDEO_MEDIA|SHORT_DRAMA\thome",
        "聽音樂\tMUSIC_MEDIA\thome",
        "導航到\tMAPS_NAV\tsearch\t剩下的字當地點",
        "找餐廳\tMAPS_NAV\tsearch"
    )

    private fun router(): IntentRouter {
        IntentRouter.resetForTest()
        IntentRouter.loadFromLines(lines)
        return IntentRouter
    }

    @Test
    fun `plain goal maps to category and home strategy`() {
        val route = router().route("我想看影片")
        // 「我想看影片」不以 term 開頭，所以不算路由——這是刻意的：
        // 口語前綴先由 Politeness 剝掉，路由只看「動作句」。
        assertNull(route)
        val stripped = router().route("看影片")
        assertEquals(listOf("VIDEO_MEDIA"), stripped!!.categories)
        assertEquals(IntentRouter.Strategy.HOME, stripped.strategy)
    }

    @Test
    fun `longest term wins`() {
        val route = router().route("看劇")
        assertEquals("看劇", route!!.term)
        assertEquals(listOf("VIDEO_MEDIA", "SHORT_DRAMA"), route.categories)
    }

    @Test
    fun `search strategy carries the remaining text`() {
        val route = router().route("導航到台北車站")
        assertEquals(IntentRouter.Strategy.SEARCH, route!!.strategy)
        assertEquals("台北車站", route.query)
    }

    @Test
    fun `search without a query is not a route`() {
        // 「導航到」後面沒東西 → 不算路由（交給反問，不要亂搜）
        assertNull(router().route("導航到"))
    }

    @Test
    fun `route carries purpose for capability tracking`() {
        // 目的 id 讓「這個 App 做不做得到這件事」可以累積
        IntentRouter.resetForTest()
        IntentRouter.loadFromLines(
            sequenceOf("導航到\tMAPS_NAV\tsearch\tG2.1", "看影片\tVIDEO_MEDIA\thome\tD1.1")
        )
        assertEquals("G2.1", IntentRouter.route("導航到台北車站")!!.purpose)
        assertEquals("D1.1", IntentRouter.route("看影片")!!.purpose)
    }

    @Test
    fun `unrelated command is not routed`() {
        assertNull(router().route("打開設定"))
        assertTrue(router().route("看影片") != null)
    }
}
