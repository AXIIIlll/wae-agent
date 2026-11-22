package com.wae.core

import com.wae.core.predicate.LanguageWords
import com.wae.core.predicate.SlotExtractor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 斷句與查詢抽取（使用者指出的第二個大問題）。
 *
 * 實測：「在 Chrome 搜尋今天天氣並打開第一個結果」曾把整串丟去搜尋
 * （content='在 Chrome 天氣並 第一個結果'），於是搜出來的東西完全不相關。
 *
 * 這裡釘住三件事：
 *  1. 連接詞之後是「下一個動作」，不是查詢字。
 *  2. App 名稱要被剪掉（由呼叫端傳 knownNames）。
 *  3. 介詞／方位助詞只是位置標記，不是內容；但地名（中壢）不能被切壞。
 */
class SlotExtractorClauseTest {

    @Before
    fun setUp() {
        LanguageWords.resetForTest()
        LanguageWords.install(
            separators = listOf("然後", "接著"),
            leads = emptyList(),
            searchWords = setOf("搜尋", "找"),
            labels = emptyMap(),
            taskVerbs = listOf("搜尋", "打開", "查看"),
            openWords = emptyList(),
            prefixes = listOf("在", "到"),
            suffixes = listOf("裡", "中"),
            conjunctions = listOf("並")
        )
    }

    @Test
    fun `conjunction starts a new action so it must not enter the query`() {
        val s = SlotExtractor.extract(
            "在 Chrome 搜尋今天天氣並打開第一個結果",
            knownNames = listOf("Chrome")
        )
        assertEquals("天氣", s.content)
    }

    @Test
    fun `app name and place particles are not part of the query`() {
        val s = SlotExtractor.extract("在設定裡搜尋藍牙", knownNames = listOf("設定"))
        assertEquals("藍牙", s.content)
    }

    @Test
    fun `separator such as then also cuts the query`() {
        val s = SlotExtractor.extract("在 Chrome 搜尋台北車站然後看第一個", knownNames = listOf("Chrome"))
        assertEquals("台北車站", s.content)
    }

    @Test
    fun `place names that merely contain a particle are preserved`() {
        val s = SlotExtractor.extract("搜尋中壢", knownNames = emptyList())
        assertEquals("中壢", s.content)
    }

    @Test
    fun `plain query is untouched`() {
        val s = SlotExtractor.extract("搜尋貓咪", knownNames = emptyList())
        assertEquals("貓咪", s.content)
    }

    @Test
    fun `without particle data nothing is stripped which is the data driven contract`() {
        LanguageWords.resetForTest()
        LanguageWords.install(
            separators = emptyList(),
            leads = emptyList(),
            searchWords = setOf("搜尋"),
            labels = emptyMap(),
            taskVerbs = listOf("搜尋")
        )
        val s = SlotExtractor.extract("在設定裡搜尋藍牙")
        // 沒有資料就只做「剪掉動詞」，其餘原樣保留（不偷偷內建詞表）
        assertEquals("在設定裡 藍牙", s.content)
    }
}
