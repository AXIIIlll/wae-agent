package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 資料檔守門：`ui_app_intent_category.tsv` 的類別必須都存在於 App metadata，
 * 且常見開放式意圖要能命中正確類別（「我想看劇」是使用者最常舉的例子）。
 */
class AppIntentCategoryTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun knownCategories(): Set<String> =
        asset("global_tw_cn_app_metadata.tsv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            }
            .toSet()

    @Before
    fun setUp() {
        AppIntentCategory.resetForTest()
        AppIntentCategory.loadFromLines(
            asset("ui_app_intent_category.tsv").readLines(Charsets.UTF_8).asSequence()
        )
    }

    @Test
    fun `table parses into rules`() {
        assertTrue("至少要解析出 30 條意圖規則，實際=${AppIntentCategory.ruleCount()}", AppIntentCategory.ruleCount() >= 30)
    }

    @Test
    fun `every category exists in app metadata`() {
        val known = knownCategories()
        val missing = AppIntentCategory.allCategories().filter { it !in known }
        assertTrue("意圖表用了不存在的類別：$missing", missing.isEmpty())
    }

    @Test
    fun `watching drama maps to video categories`() {
        val cats = AppIntentCategory.match("我想看劇").map { it.first }
        assertTrue("看劇應命中 VIDEO_MEDIA 或 SHORT_DRAMA，實際=$cats",
            cats.contains("VIDEO_MEDIA") || cats.contains("SHORT_DRAMA"))
    }

    @Test
    fun `short drama prefers short drama category`() {
        val cats = AppIntentCategory.match("我想看短劇").map { it.first }
        assertEquals("長詞優先：看短劇 應先命中 SHORT_DRAMA，實際=$cats", "SHORT_DRAMA", cats.firstOrNull())
    }

    @Test
    fun `unknown goal returns empty`() {
        assertTrue(AppIntentCategory.match("幫我把音量大一點").isEmpty())
    }
}
