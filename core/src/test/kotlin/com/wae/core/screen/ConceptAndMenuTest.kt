package com.wae.core.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 概念／同族／最近似／選單排序的契約測試。
 *
 * 這些規則決定「同名或不同名的元件怎麼選」，一旦鬆掉就會亂點，
 * 所以每一條都在這裡釘死。
 */
class ConceptAndMenuTest {

    private val table = ConceptMatcher.Table(
        concepts = mapOf(
            "CALL_HISTORY" to ConceptMatcher.Concept(
                "CALL_HISTORY", setOf("PHONE"),
                listOf("通話記錄", "通話紀錄", "來電記錄")
            ),
            "BROWSER_HISTORY" to ConceptMatcher.Concept(
                "BROWSER_HISTORY", setOf("BROWSER"),
                listOf("歷史記錄", "歷史紀錄")
            )
        ),
        families = mapOf(
            "PHONE_TABS" to setOf("鍵盤", "撥號", "聯絡人", "最近", "語音信箱"),
            "BROWSER_MENU" to setOf("新分頁", "書籤", "歷史記錄", "下載", "設定")
        ),
        members = mapOf(
            "PHONE_TABS" to mapOf("最近" to "CALL_HISTORY"),
            "BROWSER_MENU" to mapOf("歷史記錄" to "BROWSER_HISTORY")
        )
    )

    // ── 同族推論 ──────────────────────────────────────────────

    @Test
    fun `recent counts as call history when the dialer tabs are present`() {
        val labels = listOf("鍵盤", "聯絡人", "最近")
        val allowed = ConceptMatcher.familyLabelsFor("CALL_HISTORY", labels, table)
        assertTrue(allowed.any { it == "最近" })
    }

    @Test
    fun `recent alone is not enough without family co-occurrence`() {
        val allowed = ConceptMatcher.familyLabelsFor("CALL_HISTORY", listOf("最近"), table)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `family inference is concept specific`() {
        // 撥號分頁在，但問的是瀏覽器歷史 → 不可以借「最近」來當答案
        val labels = listOf("鍵盤", "聯絡人", "最近")
        val allowed = ConceptMatcher.familyLabelsFor("BROWSER_HISTORY", labels, table)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `unknown concept yields nothing`() {
        assertTrue(ConceptMatcher.familyLabelsFor(null, listOf("鍵盤", "最近"), table).isEmpty())
        assertTrue(ConceptMatcher.familyLabelsFor("NOPE", listOf("鍵盤", "最近"), table).isEmpty())
    }

    // ── 最近似 ────────────────────────────────────────────────

    @Test
    fun `nearest finds a close label when exact matching fails`() {
        // 「下載項目」對上「下載項目清單」：共用兩個 bigram，Dice 0.75 → 可接受
        val hit = ConceptMatcher.nearest("下載項目", listOf("下載項目清單", "設定", "藍牙"))
        assertNotNull(hit)
        assertEquals("下載項目清單", hit!!.first)
    }

    @Test
    fun `nearest deliberately refuses call history versus recent`() {
        // 「通話記錄」與「最近記錄」只共用「記錄」一個 bigram（Dice 0.33）
        // → 演算法不會亂點；這正是必須靠同族推論（CONCEPT/FAMILY）的原因
        assertNull(ConceptMatcher.nearest("通話記錄", listOf("最近記錄", "設定")))
    }

    @Test
    fun `nearest refuses when the best two are too close`() {
        // 兩個候選分數接近 → 不猜
        val hit = ConceptMatcher.nearest("歷史記錄", listOf("歷史紀錄", "歷史紀錄本"), margin = 0.5f)
        assertNull(hit)
    }

    @Test
    fun `dice is one for identical strings and zero for unrelated`() {
        assertEquals(1f, ConceptMatcher.dice("歷史記錄", "歷史記錄"), 0.001f)
        assertEquals(0f, ConceptMatcher.dice("藍牙", "下載"), 0.001f)
    }

    // ── 選單排序（兩個三點）────────────────────────────────────

    private fun option(
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        viewId: String = "",
        layer: Int = 1,
        inWeb: Boolean = false
    ) = ScreenOption(
        id = "opt-$label-$left-$top",
        label = label,
        role = "BUTTON",
        group = "",
        source = "A11Y",
        enabled = true,
        checked = null,
        left = left, top = top, right = right, bottom = bottom,
        viewId = viewId,
        layer = layer,
        inWeb = inWeb,
        ordinal = 1
    )

    @Test
    fun `toolbar overflow beats the in-page three dots`() {
        val inPage = option("更多選項", 950, 400, 1040, 480, viewId = "", inWeb = true)
        val toolbar = option("更多選項", 950, 40, 1040, 130, viewId = "com.android.chrome:id/menu_button")
        val ranked = MenuRanker.rank(listOf(inPage, toolbar), screenHeightPx = 2400, screenWidthPx = 1080)
        assertEquals(toolbar.id, ranked.first().option.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `bottom-left menu is demoted even without web content flag`() {
        val bottomLeft = option("更多選項", 20, 2200, 110, 2300)
        val topRight = option("更多選項", 950, 40, 1040, 130)
        val ranked = MenuRanker.rank(listOf(bottomLeft, topRight), screenHeightPx = 2400, screenWidthPx = 1080)
        assertEquals(topRight.id, ranked.first().option.id)
    }
}
