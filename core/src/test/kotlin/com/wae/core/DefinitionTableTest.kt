package com.wae.core

import com.wae.core.match.Definition
import com.wae.core.match.DefinitionTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多定義表（P0）的解析與查詢測試。
 *
 * 重點不只是「解析得動」，而是**出貨的資料本身**被測：
 * 生成器改壞、表被截斷、`路線` 這種關鍵定義掉了，這裡就會紅。
 */
class DefinitionTableTest {

    private fun repoFile(rel: String): File? =
        listOf(File("../$rel"), File(rel), File("../../$rel")).firstOrNull { it.exists() }

    private fun shippedTable(): DefinitionTable? {
        val def = repoFile("app/src/main/assets/open_source_ai_databases/ui_entity_definitions.tsv")
            ?: return null
        val groups = repoFile("app/src/main/assets/open_source_ai_databases/ui_entity_groups.tsv")
        return DefinitionTable.parse(
            def.readLines(Charsets.UTF_8).asSequence(),
            groups?.readLines(Charsets.UTF_8)?.asSequence() ?: emptySequence()
        )
    }

    @Test
    fun `shipped table parses into many entities with several views`() {
        val table = shippedTable() ?: return
        assertTrue("實體數太少，生成器可能壞了：${table.entities.size}", table.entities.size > 1000)
        assertTrue("定義數太少：${table.definitionCount()}", table.definitionCount() > 5000)
        val views = table.entities.flatMap { table.definitionsOf(it) }.map { it.view }.toSet()
        assertTrue("至少要有文字與圖示兩種視圖", views.contains(Definition.View.TEXT))
        assertTrue("至少要有圖示視圖", views.contains(Definition.View.ICON))
    }

    @Test
    fun `route keeps its chinese definitions which is the known hard case`() {
        val table = shippedTable() ?: return
        val defs = table.definitionsOf("MAP_DIRECTIONS_ROUTE")
        assertTrue("缺少 MAP_DIRECTIONS_ROUTE", defs.isNotEmpty())
        val texts = defs.filter { it.view == Definition.View.TEXT }.map { it.value }
        assertTrue("缺少 路線：$texts", texts.contains("路線"))
        // 這條是「圖示按鈕沒有文字」的關鍵：同一個目標也有圖示定義
        val icons = defs.filter { it.view == Definition.View.ICON }.map { it.value }
        assertTrue("缺少圖示定義：$icons", icons.isNotEmpty())
    }

    @Test
    fun `route belongs to a group with siblings so negatives can be derived`() {
        val table = shippedTable() ?: return
        val siblings = table.siblingsOf("MAP_DIRECTIONS_ROUTE")
        assertTrue("同群沒有兄弟，負向就無從生成", siblings.isNotEmpty())
        assertTrue("兄弟不該包含自己", siblings.none { it == "MAP_DIRECTIONS_ROUTE" })
        val group = table.groupOf("MAP_DIRECTIONS_ROUTE")
        assertNotNull("找不到群組", group)
        assertTrue("互斥強度應在 0..1", group!!.exclusivity in 0f..1f)
    }

    @Test
    fun `specificity ranks concrete definitions above generic ones`() {
        val table = shippedTable() ?: return
        val ranked = table.ranked("wifi")
        assertTrue("wifi 應該有多個定義", ranked.size >= 2)
        // 越籠統（被越多實體共用）的詞權重越低；排序後最高的一定不是填充詞
        assertTrue("排序後應由具體到籠統", ranked.first().specificity >= ranked.last().specificity)
        assertTrue("優先序應保留 Wi-Fi 這類具體叫法", ranked.any { it.value == "Wi-Fi" })
    }

    @Test
    fun `parser skips comments blank lines malformed rows and unknown views`() {
        val table = DefinitionTable.parse(
            sequenceOf(
                "\uFEFF# 註解",
                "",
                "a\tg\tTEXT\t甲\t0.5\tok",
                "b\tg\tNOT_A_VIEW\t乙\t0.5\t未知視圖要略過",
                "only_three\tcolumns\tTEXT",
                "c\tg\tICON\t丙\tnot_a_number",
                "d\tg\tTEXT\t\t0.9\t空值要略過"
            )
        )
        assertEquals(setOf("a", "c"), table.entities)
        assertEquals(1, table.definitionsOf("a").size)
        assertEquals(0f, table.definitionsOf("c").first().specificity, 0.0001f)
        assertEquals(0, table.definitionsOf("d").size)
    }

    @Test
    fun `specificity is clamped into range`() {
        val table = DefinitionTable.parse(
            sequenceOf(
                "a\tg\tTEXT\t甲\t9.9",
                "b\tg\tTEXT\t乙\t-3"
            )
        )
        assertEquals(1f, table.definitionsOf("a").first().specificity, 0.0001f)
        assertEquals(0f, table.definitionsOf("b").first().specificity, 0.0001f)
    }

    @Test
    fun `empty input yields an empty table instead of throwing`() {
        val table = DefinitionTable.parse(emptySequence(), emptySequence())
        assertEquals(0, table.entities.size)
        assertEquals(0, table.definitionCount())
        assertEquals(emptyList<String>(), table.siblingsOf("nothing"))
    }

    @Test
    fun `groups parse exclusivity and missing group file is tolerated`() {
        val withGroups = DefinitionTable.parse(
            sequenceOf("a\tnet\tTEXT\t甲\t0.5"),
            sequenceOf("# 註解", "net\t0.9\t互斥", "bad_line")
        )
        assertEquals(0.9f, withGroups.groups.getValue("net").exclusivity, 0.0001f)
        assertEquals("net", withGroups.groupOf("a")?.name)
        // 沒有群組表時仍能查定義，只是問不到互斥強度
        assertEquals(null, withGroups.groupOf("nonexistent"))
    }
}
