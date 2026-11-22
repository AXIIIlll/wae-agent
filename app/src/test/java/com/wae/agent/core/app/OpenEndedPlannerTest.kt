package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 開放式意圖的決定性計畫模板測試（純函式）。
 * 這裡固定住「看劇」的形狀：開 App → 點內容 → 按播放，而且用「暫停」當播放中的證據。
 */
class OpenEndedPlannerTest {

    @Test
    fun `video plan opens app then plays`() {
        val p = OpenEndedPlanner.videoPlan("Netflix")
        assertEquals(3, p.steps.size)
        assertEquals("\$app:Netflix", p.steps[0].target)
        assertEquals("\$role:first_result", p.steps[1].target)
        // 內容頁不一定有「播放」字樣，第 2 步只要畫面有變化就算過
        assertEquals("", p.steps[1].verify)
        assertEquals("\$role:play", p.steps[2].target)
        assertEquals("暫停", p.steps[2].verify)
        assertEquals("open_ended", p.goalKind)
    }

    @Test
    fun `music plan is two steps`() {
        val p = OpenEndedPlanner.musicPlan("Spotify")
        assertEquals(2, p.steps.size)
        assertEquals("\$role:play", p.steps[1].target)
        assertEquals("暫停", p.steps[1].verify)
    }

    @Test
    fun `maps and food use search then verify`() {
        val maps = OpenEndedPlanner.searchPlan("Google 地圖", "目的地", "導航")
        assertEquals(4, maps.steps.size)
        assertEquals("\$role:input", maps.steps[1].target)
        assertEquals("目的地", maps.steps[2].target)
        assertEquals("\$role:first_result", maps.steps[3].target)

        val food = OpenEndedPlanner.searchPlan("foodpanda", "餐點", "菜單", "牛肉麵")
        assertEquals(4, food.steps.size)
        assertEquals("牛肉麵", food.steps[2].target)
    }

    @Test
    fun `package target is used when available`() {
        val p = OpenEndedPlanner.videoPlan("Netflix", "com.netflix.mediaclient")
        assertEquals("\$pkg:com.netflix.mediaclient", p.steps[0].target)
    }

    @Test
    fun `slot value becomes the typed payload`() {
        val p = OpenEndedPlanner.plan(
            "導航到士林夜市", "Google 地圖", listOf("MAPS_NAV"),
            mapOf("destination" to "士林夜市")
        )
        assertNotNull(p)
        assertEquals("士林夜市", p!!.steps[2].target)
        assertTrue(p.summary.contains("士林夜市"))
    }

    @Test
    fun `kind mapping covers the main categories`() {
        assertEquals(OpenEndedPlanner.Kind.VIDEO, OpenEndedPlanner.kindFor(listOf("VIDEO_MEDIA")))
        assertEquals(OpenEndedPlanner.Kind.VIDEO, OpenEndedPlanner.kindFor(listOf("SHORT_DRAMA")))
        assertEquals(OpenEndedPlanner.Kind.MUSIC, OpenEndedPlanner.kindFor(listOf("MUSIC_MEDIA")))
        assertEquals(OpenEndedPlanner.Kind.MAPS, OpenEndedPlanner.kindFor(listOf("MAPS_NAV")))
        assertEquals(OpenEndedPlanner.Kind.FOOD, OpenEndedPlanner.kindFor(listOf("FOOD_DELIVERY")))
        assertNull(OpenEndedPlanner.kindFor(listOf("MESSAGING")))
    }

    @Test
    fun `plan returns null when no template applies`() {
        assertNull(OpenEndedPlanner.plan("幫我傳訊息", "LINE", listOf("MESSAGING")))
        assertNull(OpenEndedPlanner.plan("我想看劇", "", listOf("VIDEO_MEDIA")))
    }

    @Test
    fun `plan picks video template for watching drama`() {
        val p = OpenEndedPlanner.plan("我想看劇", "紅果短劇", listOf("SHORT_DRAMA", "VIDEO_MEDIA"))
        assertNotNull(p)
        assertTrue(p!!.summary.contains("紅果短劇"))
        assertEquals("\$app:紅果短劇", p.steps[0].target)
    }
}
