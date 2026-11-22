package com.wae.agent.core.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 設定分類的 OEM 差異：同一句「把飛航模式關掉」，三星、小米、華為要進不同頁。
 * 資料驅動（`ui_toggle_section_map.tsv` 第 4 欄），找不到就要回空集合讓上層改走搜尋。
 */
class ToggleSectionMapOemTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    @Before
    fun setUp() {
        ToggleSectionMap.resetForTest()
        ToggleSectionMap.loadFromLines(asset("ui_toggle_section_map.tsv").readLines(Charsets.UTF_8))
    }

    @Test
    fun `samsung uses generic airplane section`() {
        val hints = ToggleSectionMap.sectionHints("飛航模式", "samsung")
        assertTrue("三星應走通用規則，實際=$hints", hints.contains("連接"))
    }

    @Test
    fun `xiaomi uses its own section name`() {
        val hints = ToggleSectionMap.sectionHints("飛航模式", "Xiaomi")
        assertTrue("小米應走專屬規則，實際=$hints", hints.any { it.contains("連接與共享") })
    }

    @Test
    fun `huawei uses wireless section`() {
        val hints = ToggleSectionMap.sectionHints("飛航模式", "HUAWEI")
        assertTrue("華為應走專屬規則，實際=$hints", hints.any { it.contains("無線與網路") })
    }

    @Test
    fun `google pixel uses network section`() {
        val hints = ToggleSectionMap.sectionHints("飛航模式", "Google")
        assertTrue("Pixel 應走專屬規則，實際=$hints", hints.any { it.contains("網際網路") })
    }

    @Test
    fun `unknown oem falls back to generic`() {
        val hints = ToggleSectionMap.sectionHints("飛航模式", "SomeBrand")
        assertTrue(hints.isNotEmpty())
        assertTrue(hints.contains("連接"))
    }

    @Test
    fun `unknown target returns empty so caller can search`() {
        assertEquals(emptyList<String>(), ToggleSectionMap.sectionHints("量子引擎", "samsung"))
    }

    @Test
    fun `without manufacturer only generic rules apply`() {
        val hints = ToggleSectionMap.sectionHints("藍牙")
        assertTrue(hints.contains("連接"))
        assertTrue("不該套用小米專屬規則，實際=$hints", hints.none { it.contains("連接與共享") })
    }
}
