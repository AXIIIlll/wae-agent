package com.wae.core

import com.wae.core.config.Key
import com.wae.core.config.MapConfig
import com.wae.core.model.Constraints
import com.wae.core.routing.CapabilityIndex
import com.wae.core.routing.Goal
import com.wae.core.routing.GoalKind
import com.wae.core.routing.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 效果路由：驗證「在結果層面比較多條路」這件事的邏輯。
 *
 * 所有測試都**不含任何 App 名稱的硬編碼語意**：App 只是 id，選它靠數據。
 */
class EffectRoutingTest {

    private val config = MapConfig(emptyMap())
    private val router = Router(config)
    private val goal = Goal(GoalKind.SEND_MEDIA, subject = "photo", recipient = "媽媽")

    @Test
    fun `fewer steps wins when reliability is equal`() {
        val index = CapabilityIndex()
        index.recordSuccess("app.slow", GoalKind.SEND_MEDIA, steps = 7)
        index.recordSuccess("app.fast", GoalKind.SEND_MEDIA, steps = 3)

        val chosen = router.choose(goal, index)!!

        assertEquals("app.fast", chosen.appId)
        assertTrue(chosen.score > 0f)
    }

    @Test
    fun `higher reliability wins when steps are equal`() {
        val index = CapabilityIndex()
        repeat(5) { index.recordSuccess("app.reliable", GoalKind.SEND_MEDIA, steps = 4) }
        repeat(4) { index.recordFailure("app.flaky", GoalKind.SEND_MEDIA, steps = 4) }
        index.recordSuccess("app.flaky", GoalKind.SEND_MEDIA, steps = 4)

        val chosen = router.choose(goal, index)!!

        assertEquals("app.reliable", chosen.appId)
        assertTrue(chosen.reliability > 0.7f)
    }

    @Test
    fun `unknown app gets a conservative prior and loses to a proven route`() {
        val index = CapabilityIndex()
        repeat(3) { index.recordSuccess("app.known", GoalKind.SEND_MEDIA, steps = 5) }
        // app.new 沒有任何資料：它仍要出現在候選裡（不能被判死），但不會贏
        index.recordFailure("app.new", GoalKind.SEND_MEDIA, steps = 5)
        index.recordSuccess("app.new", GoalKind.SEND_MEDIA, steps = 5)

        val routes = router.rank(goal, index)

        assertEquals("app.known", routes.first().appId)
        val newRoute = routes.first { it.appId == "app.new" }
        assertTrue(newRoute.reliability < routes.first().reliability)
    }

    @Test
    fun `banned route is excluded`() {
        val index = CapabilityIndex()
        index.recordSuccess("app.a", GoalKind.SEND_MEDIA, steps = 3)
        index.recordSuccess("app.b", GoalKind.SEND_MEDIA, steps = 9)

        val (effectKey, meansKey) = Router.banKey(goal, "app.a")
        val constraints = Constraints(banned = setOf(Constraints.pair(effectKey, meansKey)))

        val chosen = router.choose(goal, index, constraints)!!

        assertEquals("app.b", chosen.appId)
    }

    @Test
    fun `lowered route keeps its place but is penalised`() {
        val index = CapabilityIndex()
        index.recordSuccess("app.a", GoalKind.SEND_MEDIA, steps = 3)
        index.recordSuccess("app.b", GoalKind.SEND_MEDIA, steps = 3)

        val (effectKey, meansKey) = Router.banKey(goal, "app.a")
        val constraints = Constraints(confirm = setOf(Constraints.pair(effectKey, meansKey)))

        val ranked = router.rank(goal, index, constraints)
        val a = ranked.first { it.appId == "app.a" }

        assertTrue(a.lowered)
        assertEquals("app.b", ranked.first().appId)
    }

    @Test
    fun `pinned app overrides the ranking`() {
        val index = CapabilityIndex()
        index.recordSuccess("app.fast", GoalKind.SEND_MEDIA, steps = 3)
        index.recordSuccess("app.slow", GoalKind.SEND_MEDIA, steps = 9)

        val chosen = router.choose(goal.copy(pinnedApp = "app.slow"), index)!!

        assertEquals("app.slow", chosen.appId)
    }

    @Test
    fun `no candidate returns null instead of guessing`() {
        assertNull(router.choose(goal, CapabilityIndex()))
    }

    @Test
    fun `explanation is human readable and contains the numbers`() {
        val index = CapabilityIndex()
        index.recordSuccess("app.fast", GoalKind.SEND_MEDIA, steps = 3)

        val text = router.choose(goal, index)!!.explain()

        assertTrue(text.contains("app=app.fast"))
        assertTrue(text.contains("steps=3"))
        assertTrue(text.contains("p="))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `capability stats smooth the average steps`() {
        val index = CapabilityIndex()
        index.recordSuccess("x", GoalKind.SEARCH, steps = 10)
        index.recordSuccess("x", GoalKind.SEARCH, steps = 1)

        val cap = index.candidates(GoalKind.SEARCH).first()

        assertEquals(2, cap.attempts)
        // (10*2 + 1) / 3 = 7 → 新觀察不會讓平均值瞬間翻盤
        assertEquals(7, cap.avgSteps)
    }

    @Test
    fun `route penalty is configurable`() {
        val index = CapabilityIndex()
        index.recordSuccess("app.a", GoalKind.SEND_MEDIA, steps = 3)
        index.recordSuccess("app.b", GoalKind.SEND_MEDIA, steps = 3)
        val (effectKey, meansKey) = Router.banKey(goal, "app.a")
        val constraints = Constraints(confirm = setOf(Constraints.pair(effectKey, meansKey)))

        val harsh = Router(MapConfig(mapOf(Key.ROUTE_LOWER_PENALTY to "0.1")))
        val ranked = harsh.rank(goal, index, constraints)

        assertEquals("app.b", ranked.first().appId)
        assertTrue(ranked.first { it.appId == "app.a" }.score < ranked.first().score)
    }
}
