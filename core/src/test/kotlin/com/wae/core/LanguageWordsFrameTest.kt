package com.wae.agent.core

import com.wae.core.predicate.LanguageWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 文法句型判定（第 2 個修法）。
 *
 * 背景：Qwen 之外的任務分類器（tiny BERT）把「在設定裡搜尋藍牙」判成 CREATE（信心 0.83），
 * 於是計畫出「新增」步驟，整題失敗。句首是「在」，動詞優先規則接不到。
 *
 * 這個判定的資料全部來自 TSV（搜尋詞、介詞、方位助詞），這裡只驗證「規則本身」。
 */
class LanguageWordsFrameTest {

    @Before
    fun setUp() {
        LanguageWords.resetForTest()
        LanguageWords.install(
            separators = emptyList(),
            leads = emptyList(),
            searchWords = setOf("搜尋", "找", "查"),
            labels = emptyMap(),
            prefixes = listOf("在", "到", "進"),
            suffixes = listOf("裡", "中", "内")
        )
    }

    @Test
    fun `search inside a place is a FIND not a CREATE`() {
        assertEquals("FIND", LanguageWords.typeOfFrame("在設定裡搜尋藍牙"))
        assertEquals("FIND", LanguageWords.typeOfFrame("在 Chrome 中找貓咪"))
        assertEquals("FIND", LanguageWords.typeOfFrame("到聯絡人裡查王小明"))
    }

    @Test
    fun `plain search without a place marker is left to the head verb rule`() {
        // 「搜尋貓咪」句首就是動詞，交給 typeOfHead；這裡不該搶
        assertNull(LanguageWords.typeOfFrame("搜尋貓咪"))
        assertNull(LanguageWords.typeOfFrame("打開設定"))
    }

    @Test
    fun `place marker without a search word is not a FIND`() {
        assertNull(LanguageWords.typeOfFrame("在設定裡開啟藍牙"))
        assertNull(LanguageWords.typeOfFrame("在設定裡關閉自動旋轉"))
    }

    @Test
    fun `empty and blank input are safe`() {
        assertNull(LanguageWords.typeOfFrame(""))
        assertNull(LanguageWords.typeOfFrame("   "))
    }

    @Test
    fun `without data nothing is classified which is the data driven contract`() {
        LanguageWords.resetForTest()
        assertNull(LanguageWords.typeOfFrame("在設定裡搜尋藍牙"))
    }
}
