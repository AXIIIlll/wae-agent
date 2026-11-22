package com.wae.core

import com.wae.core.match.DefinitionMatcher
import com.wae.core.match.DefinitionTable
import com.wae.core.match.Resolution
import com.wae.core.match.TargetResolver
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TargetResolver` 接上多定義比對器後的行為（P1 的單一出口驗證）。
 *
 * 這個測試存在的理由：解析階梯先前只看標籤文字，所以「沒有文字的圖示按鈕」
 * 在第三階（語意相似）永遠被判成 Missing。注入 scorer 之後，
 * 同一個階梯就能用身份字串裡的圖示名命中——不必為地圖寫任何專屬規則。
 */
class TargetResolverScorerTest {

    private fun button(label: String, viewId: String, kind: ControlKind = ControlKind.BUTTON): Control =
        Control(key = "android.widget.ImageButton|$viewId", kind = kind, label = label, bounds = RectI(0, 0, 10, 10))

    private fun matcher(): DefinitionMatcher = DefinitionMatcher(
        DefinitionTable.parse(
            sequenceOf(
                "MAP_DIRECTIONS_ROUTE\tmaps\tTEXT\t路線\t0.900",
                "MAP_DIRECTIONS_ROUTE\tmaps\tICON\tdirections\t0.900",
                "VOLUME\tmedia\tTEXT\t音量\t0.300"
            )
        )
    )

    @Test
    fun `icon only button resolves through its view id`() {
        val controls = listOf(
            button("", "com.google.android.apps.maps:id/directions_button"),
            button("音量", "com.x:id/volume")
        )
        val resolver = TargetResolver(scorer = matcher())
        val r = resolver.resolve("路線", controls)
        assertTrue("應該解析到圖示按鈕，實際=$r", r is Resolution.Found)
        val found = r as Resolution.Found
        assertTrue(found.control.key.contains("directions"))
    }

    @Test
    fun `no candidate stays missing instead of guessing`() {
        val controls = listOf(button("音量", "com.x:id/volume"))
        val resolver = TargetResolver(scorer = matcher())
        val r = resolver.resolve("路線", controls)
        assertTrue("找不到就要回 Missing，不要硬猜，實際=$r", r is Resolution.Missing)
    }

    @Test
    fun `two equally good candidates become ambiguous so the caller can ask`() {
        // 兩個標籤都不是精確相等（所以不會在第一階就被選走），但都含「路線」→ 同分
        val controls = listOf(
            button("路線資訊", "com.x:id/route_a"),
            button("查看路線", "com.x:id/route_b")
        )
        val resolver = TargetResolver(scorer = matcher())
        val r = resolver.resolve("路線", controls)
        assertTrue("同分應該回 Ambiguous（上層反問），實際=$r", r is Resolution.Ambiguous)
    }

    @Test
    fun `resolver without scorer keeps the old label only behaviour`() {
        val controls = listOf(button("", "com.google.android.apps.maps:id/directions_button"))
        // 沒有 scorer、也沒有相似度 → 第三階不存在，圖示按鈕仍然解析不到
        val r = TargetResolver().resolve("路線", controls)
        assertTrue("沒有 scorer 時應維持舊行為，實際=$r", r is Resolution.Missing)
    }

    @Test
    fun `exact label still wins at the first stage without touching the scorer`() {
        val controls = listOf(
            button("路線", "com.x:id/a", ControlKind.BUTTON),
            button("", "com.google.android.apps.maps:id/directions_button")
        )
        val resolver = TargetResolver(scorer = matcher())
        val r = resolver.resolve("路線", controls)
        assertTrue(r is Resolution.Found)
        assertEquals("com.x:id/a", (r as Resolution.Found).control.key.substringAfter('|'))
    }
}
