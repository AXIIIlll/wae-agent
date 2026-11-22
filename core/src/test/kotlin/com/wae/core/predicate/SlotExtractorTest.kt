package com.wae.core.predicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 槽位抽取：時間／對象／內容，用「減法」而不是名詞表。 */
class SlotExtractorTest {

    @Before
    fun setup() {
        LanguageWords.install(
            separators = listOf("然後", "再"),
            leads = listOf("幫我", "請", "把", "將"),
            searchWords = setOf("搜尋"),
            labels = mapOf("share" to "分享", "send" to "送出", "new" to "新增", "save" to "儲存"),
            taskVerbs = listOf("搜尋", "點擊", "傳給", "分享給", "導航到"),
            openWords = listOf("打開", "開")
        )
    }

    @Test
    fun `extracts time person and content from a transfer sentence`() {
        val s = SlotExtractor.extract("把上週的照片傳給媽媽")
        assertEquals("媽媽", s.person)
        assertEquals("上週的照片", s.content)
        assertNull(s.time)
    }

    @Test
    fun `extracts time slot`() {
        val s = SlotExtractor.extract("明天九點提醒我開會")
        assertEquals("明天九點", s.time)
        assertEquals("提醒我開會", s.content)
    }

    @Test
    fun `extracts navigate target as content`() {
        val s = SlotExtractor.extract("導航到台北車站")
        assertEquals("台北車站", s.content)
        assertNull(s.person)
    }

    @Test
    fun `known app names are removed from content`() {
        val s = SlotExtractor.extract("打開地圖搜尋臺北101", knownNames = listOf("地圖"))
        assertEquals("臺北101", s.content)
    }

    @Test
    fun `empty input yields empty slots`() {
        val s = SlotExtractor.extract("   ")
        assertNull(s.time)
        assertNull(s.person)
        assertNull(s.content)
    }
}
