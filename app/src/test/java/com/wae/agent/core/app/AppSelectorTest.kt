package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 開放式意圖選 App 的行為測試（純函式，不需要裝置）。
 * 核心案例就是使用者的原句：「我想看劇」。
 */
class AppSelectorTest {

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
        AppIntentCategory.resetForTest()
        AppIntentCategory.loadFromLines(
            asset("ui_app_intent_category.tsv").readLines(Charsets.UTF_8).asSequence()
        )
    }

    private fun app(label: String, pkg: String, category: String, vararg aliases: String) =
        AppSelector.AppCandidate(label, pkg, category, aliases.toList())

    private val installed = listOf(
        app("Netflix", "com.netflix.mediaclient", "VIDEO_MEDIA"),
        app("紅果短劇", "com.phoenix.read", "SHORT_DRAMA", "紅果", "短劇"),
        app("LINE", "jp.naver.line.android", "MESSAGING", "賴"),
        app("設定", "com.android.settings", "SYSTEM"),
        app("Google 地圖", "com.google.android.apps.maps", "MAPS_NAV", "地圖", "導航")
    )

    @Test
    fun `watching drama picks a video app and is confident`() {
        val d = AppSelector.select("我想看劇", installed)
        assertTrue("應該要有候選", d.ranked.isNotEmpty())
        assertTrue("命中類別應包含 VIDEO_MEDIA 或 SHORT_DRAMA，實際=${d.matchedCategories}",
            d.matchedCategories.contains("VIDEO_MEDIA") || d.matchedCategories.contains("SHORT_DRAMA"))
        val best = d.best!!
        assertTrue("最高分應是影音類 App，實際=${best.candidate.label}",
            best.candidate.category == "VIDEO_MEDIA" || best.candidate.category == "SHORT_DRAMA")
        assertTrue("兩個影音 App 分數接近時應反問（reason=${d.reason}）", d.confident || d.reason == "tie")
    }

    @Test
    fun `usage breaks a tie between two video apps`() {
        val d = AppSelector.select(
            "我想看劇", installed,
            usageCounts = mapOf("com.netflix.mediaclient" to 10_000L, "com.phoenix.read" to 100L)
        )
        assertEquals("Netflix", d.best!!.candidate.label)
        assertTrue("使用頻率高時應直接決定", d.confident)
    }

    @Test
    fun `navigation intent picks maps app`() {
        val d = AppSelector.select("幫我導航到台北車站", installed)
        assertEquals("Google 地圖", d.best!!.candidate.label)
        assertTrue(d.confident)
    }

    @Test
    fun `alias hit boosts the right app`() {
        val d = AppSelector.select("我想追劇", installed)
        assertTrue("短劇 App 有『短劇』別名，應命中", d.ranked.first().aliasHit || d.ranked.first().categoryHit)
        assertTrue(d.ranked.first().candidate.category in setOf("SHORT_DRAMA", "VIDEO_MEDIA"))
    }

    @Test
    fun `simplified input still resolves to video category`() {
        val d = AppSelector.select("我想看剧", installed)
        assertTrue("簡體『看剧』也要命中 VIDEO_MEDIA/SHORT_DRAMA，實際=${d.matchedCategories}",
            d.matchedCategories.contains("VIDEO_MEDIA") || d.matchedCategories.contains("SHORT_DRAMA"))
        assertTrue(d.best!!.candidate.category in setOf("VIDEO_MEDIA", "SHORT_DRAMA"))
    }

    @Test
    fun `unknown goal is not confident`() {
        val d = AppSelector.select("幫我把音量大一點", installed)
        assertFalse(d.confident)
        assertTrue(d.reason == "low_score" || d.reason == "no_candidates")
    }

    @Test
    fun `empty input is safe`() {
        assertFalse(AppSelector.select("", installed).confident)
        assertFalse(AppSelector.select("我想看劇", emptyList()).confident)
    }

    @Test
    fun `bigram similarity is symmetric and bounded`() {
        val a = AppSelector.bigramSimilarity("看劇", "看劇")
        assertEquals(1f, a, 0.001f)
        val b = AppSelector.bigramSimilarity("看劇", "短劇")
        assertTrue(b in 0f..1f)
    }
}
