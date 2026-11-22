package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 別名衍生：同一個 App 的簡繁、大小寫、去後綴寫法都要能對上。
 */
class AppAliasHeuristicsTest {

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
    fun `simplified variant is derived`() {
        val d = AppAliasHeuristics.derive("紅果短劇")
        assertTrue("應包含簡體寫法，實際=$d", d.contains("红果短剧"))
    }

    @Test
    fun `case and space variants are derived`() {
        val d = AppAliasHeuristics.derive("YouTube Music")
        assertTrue(d.any { it == "youtube music" })
        assertTrue(d.any { it == "YouTubemusic" || it == "youtubemusic" })
    }

    @Test
    fun `suffix is stripped`() {
        val d = AppAliasHeuristics.derive("某某購物 App")
        assertTrue("應去掉 App 後綴，實際=$d", d.any { it.contains("某某購物") && !it.endsWith("app") })
    }

    @Test
    fun `original label is not repeated`() {
        val d = AppAliasHeuristics.derive("設定")
        assertFalse(d.contains("設定"))
    }

    @Test
    fun `merge keeps base aliases and adds derived`() {
        val merged = AppAliasHeuristics.withDerived("紅果短劇", listOf("短劇", "紅果"))
        assertTrue(merged.contains("短劇"))
        assertTrue(merged.contains("红果短剧"))
    }
}
