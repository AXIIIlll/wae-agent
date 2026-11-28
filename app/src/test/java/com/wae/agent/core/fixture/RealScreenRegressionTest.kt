package com.wae.agent.core.fixture

import com.wae.core.screen.ConceptMatcher
import com.wae.core.screen.OptionExtractor
import com.wae.core.screen.ScreenModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用**真機 dump** 做的回歸測試。
 *
 * 為什麼要有：019（時鐘頁籤）與 025（電話通話紀錄）都修過不只一次，
 * 每次都靠人工在手機上確認——這代表同樣的 bug 可以再回來。
 * 這裡把當時的畫面凍結成 fixture，把「修好之後必須成立的事實」寫成斷言。
 */
class RealScreenRegressionTest {

    // ── 025：電話 App 的通話紀錄 ──────────────────────────────────────

    /**
     * 真實畫面（`dialer_call_log.xml`，133 個節點）的重點：
     *  - 頁籤「最近記錄」是 **clickable=false / focusable=true** 的 LinearLayout
     *  - 同一頁還有「鍵盤」「聯絡人」
     *  - 沒有任何節點叫「通話紀錄」
     */
    private val dialer by lazy { UiDumpFixture.load("dialer_call_log.xml") }

    @Test
    fun `dialer dump is parsed into a realistic number of controls`() {
        assertTrue("fixture 應該有幾十個可互動元素，實際 ${dialer.size}", dialer.size > 20)
        assertTrue("一定要有『最近記錄』", dialer.any { it.label.contains("最近記錄") })
    }

    @Test
    fun `recent tab is kept as an option even though clickable is false`() {
        val model = OptionExtractor.from("com.samsung.android.dialer", "fp", dialer, 2340)
        val tab = model.options.firstOrNull { it.label.contains("最近記錄") }
        assertTrue("最近記錄 必須進選項（否則 025 會找不到目標）", tab != null)
        assertTrue("頁籤是靠 focusable 進來的，不可被當成純文字", tab!!.enabled)
    }

    @Test
    fun `concept table maps call history onto the recent tab`() {
        val table = loadConceptTable()
        assertTrue("概念表應該有載入", table.concepts.isNotEmpty())

        // 機制是「概念的直接說法」：目標「通話紀錄」與畫面「最近記錄」都屬於 CALL_HISTORY。
        // 同族推論只是備援（畫面只寫「最近」這種更短的情況）。
        assertEquals("CALL_HISTORY", table.conceptOf("通話紀錄")?.id)
        assertEquals("CALL_HISTORY", table.conceptOf("最近記錄")?.id)

        val present = OptionExtractor.from("com.samsung.android.dialer", "fp", dialer, 2340)
            .options.map { it.label }
        assertTrue("畫面上一定要有『最近記錄』可選", present.any { it.contains("最近記錄") })
    }

    @Test
    fun `screen model has no duplicate option ids`() {
        val model = OptionExtractor.from("com.samsung.android.dialer", "fp", dialer, 2340)
        val ids = model.options.map { it.id }
        assertEquals("選項 id 必須唯一，否則反向定位會點錯", ids.size, ids.distinct().size)
    }

    @Test
    fun `options never carry raw text of the screen into ids`() {
        val model = OptionExtractor.from("com.samsung.android.dialer", "fp", dialer, 2340)
        // id 是雜湊（opt<hash>），不該出現任何中文字
        for (o in model.options) {
            assertFalse("選項 id 不該含原文：${o.id}", o.id.any { it.code > 0x2E80 })
        }
    }
    // 019（時鐘頁籤）的 fixture 待手機回來再補：之前的 dump 抓到的是 WAE 自己的結果對話框，不是時鐘頁。

    // ── 共用：從 assets 讀概念表（單元測試走工作目錄） ────────────────

    private fun loadConceptTable(): ConceptMatcher.Table {
        val file = listOf(
            File("src/main/assets/open_source_ai_databases/ui_concepts.tsv"),
            File("app/src/main/assets/open_source_ai_databases/ui_concepts.tsv")
        ).firstOrNull { it.isFile } ?: error("找不到 ui_concepts.tsv")

        val concepts = LinkedHashMap<String, ConceptMatcher.Concept>()
        val families = LinkedHashMap<String, Set<String>>()
        val members = LinkedHashMap<String, MutableMap<String, String>>()

        for (raw in file.readLines(Charsets.UTF_8)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split('\t').map { it.trim() }
            if (cells.size < 2) continue
            when (cells[0].uppercase()) {
                "CONCEPT" -> {
                    val id = cells.getOrNull(1) ?: continue
                    val domains = cells.getOrNull(2).orEmpty()
                        .split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val labels = cells.getOrNull(3).orEmpty()
                        .split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    if (id.isNotEmpty() && labels.isNotEmpty()) {
                        concepts[id] = ConceptMatcher.Concept(id, domains, labels)
                    }
                }
                "FAMILY" -> {
                    val id = cells.getOrNull(1) ?: continue
                    val list = cells.getOrNull(2).orEmpty()
                        .split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    if (id.isNotEmpty() && list.isNotEmpty()) families[id] = list
                }
                "MEMBER" -> {
                    val conceptId = cells.getOrNull(1) ?: continue
                    val familyId = cells.getOrNull(2) ?: continue
                    val member = cells.getOrNull(3) ?: continue
                    if (conceptId.isEmpty() || familyId.isEmpty() || member.isEmpty()) continue
                    members.getOrPut(familyId) { LinkedHashMap() }[member] = conceptId
                }
            }
        }
        return ConceptMatcher.Table(concepts, families, members.mapValues { it.value.toMap() })
    }
}
