package com.wae.core

import com.wae.core.config.BundledData
import com.wae.core.rule.NounResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名詞 → 型別 → App 類別（規則／路由都認不得時的 fallback）。
 *
 * 驗的重點：使用者只改名詞、或名詞根本沒被寫進句型表，也要能推出「該用哪類 App」。
 */
class NounResolverTest {

    private val resolver: NounResolver by lazy {
        NounResolver.parse(
            BundledData.objectTypeLines(),
            BundledData.typeAppLines(),
            BundledData.verbTypeLines()
        )
    }

    @Test
    fun `unknown info noun falls back to verb type`() {
        // 實機案例：「我想找 AI 資料」→ 沒有任何句型命中 → 應該用瀏覽器搜尋
        val r = resolver.resolve("我想找 AI 資料")
        assertNotNull("應該要有解析結果", r)
        assertEquals("INFO", r!!.type)
        assertTrue("要能交給瀏覽器類別：${r.categories}", r.categories.contains("BROWSER"))
        assertEquals("search", r.action)
    }

    @Test
    fun `place noun chooses maps`() {
        val r = resolver.resolve("我想找醫院")!!
        assertEquals("PLACE", r.type)
        assertTrue(r.categories.contains("MAPS_NAV"))
        assertEquals("醫院", r.noun)
        assertEquals("noun", r.matchedBy)
    }

    @Test
    fun `verb stripped before noun matching`() {
        val r = resolver.resolve("幫我找一下餐廳")!!
        assertEquals("PLACE", r.type)
        assertEquals("餐廳", r.noun)
    }

    @Test
    fun `person verb maps to person type`() {
        val r = resolver.resolve("傳訊息給媽媽")!!
        assertEquals("PERSON", r.type)
        assertEquals("媽媽", r.noun)
    }

    @Test
    fun `setting noun wins over open verb`() {
        // 「打開藍牙」：動詞預設是 APP，但名詞藍牙是 SETTING
        val r = resolver.resolve("打開藍牙")!!
        assertEquals("SETTING", r.type)
    }

    @Test
    fun `no verb and no noun returns null instead of guessing`() {
        assertNull(resolver.resolve("嗯"))
        assertNull(resolver.resolve("隨便說一句不相關的話"))
    }

    @Test
    fun `tables are loaded and cover main types`() {
        val types = resolver.types()
        for (expected in listOf("PLACE", "INFO", "PERSON", "PRODUCT", "MEDIA", "SETTING")) {
            assertTrue("型別表缺 $expected", expected in types)
        }
    }
}
