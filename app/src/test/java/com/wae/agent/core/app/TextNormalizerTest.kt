package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 文字正規化：簡繁、ASR 同音、標點。這是「同一個 App 的各種寫法都能對上」的基礎。
 */
class TextNormalizerTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    @Before
    fun setUp() {
        TextNormalizer.resetForTest()
        TextNormalizer.loadFromLines(
            asset("opencc_zh_tw_cn_lexicon.tsv").readLines(Charsets.UTF_8).asSequence(),
            asset("stopwords_and_asr_homophones.tsv").readLines(Charsets.UTF_8).asSequence()
        )
    }

    @Test
    fun `simplified becomes traditional`() {
        assertEquals("紅果短劇", TextNormalizer.normalize("红果短剧"))
        assertEquals("設定", TextNormalizer.normalize("设定"))
    }

    @Test
    fun `punctuation and spaces are removed`() {
        assertEquals("打開紅果短劇", TextNormalizer.normalize("打開 紅果短剧！"))
    }

    @Test
    fun `asr homophone pairs are corrected`() {
        // 資料檔裡有 高帖 → 高鐵
        assertEquals("高鐵", TextNormalizer.normalize("高帖"))
    }

    @Test
    fun `similarity is high for homophone near-miss`() {
        // 「短句」vs「短劇」：同音不同字，正規化後只差一個字
        assertTrue(TextNormalizer.similarity("紅果短句", "紅果短劇") >= 0.5f)
    }

    @Test
    fun `similarity is low for unrelated text`() {
        assertTrue(TextNormalizer.similarity("紅果短劇", "計算機") < 0.2f)
    }
}
