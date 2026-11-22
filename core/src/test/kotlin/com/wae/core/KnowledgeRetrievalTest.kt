package com.wae.core

import com.wae.core.chat.Citation
import com.wae.core.chat.CorrectionStore
import com.wae.core.chat.KnowledgeBase
import com.wae.core.chat.KnowledgeDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型自己查」的最小驗證：
 *  - 檢索要能把換句話說對到同一個目的
 *  - 輸出必須引用檢索片段（查了不用 → 直接判錯）
 *  - 使用者修正要變成最高權重的語料
 */
class KnowledgeRetrievalTest {

    private val docs = listOf(
        KnowledgeDoc("P-G2.2", "purpose", "目的 G2.2：可以這樣說——找餐廳、找加油站、導航到"),
        KnowledgeDoc("P-E2.1", "purpose", "目的 E2.1：可以這樣說——調亮、螢幕變亮"),
        KnowledgeDoc("P-D1.1", "purpose", "目的 D1.1：可以這樣說——看影片、播放影片"),
        KnowledgeDoc("O-URI_SEARCH", "op", "操作 URI_SEARCH：前提 前景 App 有 URI 模板"),
        KnowledgeDoc("A-com.google.android.apps.maps", "app", "App「地圖」分類 MAPS_NAV 套件 com.google.android.apps.maps")
    )

    private val base = KnowledgeBase(docs)

    @Test
    fun `paraphrase finds the right purpose`() {
        val results = base.retrieve("我想找個吃飯的地方", 3)
        assertTrue("應該檢索到 G2.2：$results", results.any { it.id == "P-G2.2" })
    }

    @Test
    fun `direct phrase finds the right purpose`() {
        val top = base.retrieve("幫我找餐廳", 1).firstOrNull()
        assertEquals("P-G2.2", top?.id)
    }

    @Test
    fun `unrelated query returns nothing useful`() {
        val results = base.retrieve("qwertyzzz", 3)
        assertTrue(results.isEmpty() || results.all { it.score < 0.05f })
    }

    @Test
    fun `citation requires a valid ref`() {
        val ok = Citation.parse("OP URI_SEARCH(query=餐廳) #ref=P1", setOf("P1"))
        assertTrue(ok.ok)
        assertEquals("OP", ok.steps.first().action)

        val missing = Citation.parse("OP URI_SEARCH(query=餐廳)", setOf("P1"))
        assertTrue("沒引用要判錯", !missing.ok)

        val badRef = Citation.parse("OP URI_SEARCH(query=餐廳) #ref=P9", setOf("P1"))
        assertTrue("引用不存在要判錯", !badRef.ok)
    }

    @Test
    fun `ask and done need no ref`() {
        assertTrue(Citation.parse("ASK 要訂哪一家？", emptySet()).ok)
        assertTrue(Citation.parse("DONE", emptySet()).ok)
    }

    @Test
    fun `corrections become high weight retrieval docs`() {
        val store = CorrectionStore()
        store.add("我想找個吃飯的地方", "OP URI_SEARCH(query=餐廳)")
        val docs = store.toDocs()
        assertEquals(1, docs.size)
        assertTrue(docs.first().weight >= 10f)

        val withCorrection = KnowledgeBase(docs + this.docs)
        val top = withCorrection.retrieve("我想找個吃飯的地方", 2).first()
        assertEquals("correction", top.kind)
        assertNotNull(CorrectionStore.normalize("我想找個吃飯的地方。"))
    }

    @Test
    fun `correction store round trip`() {
        val store = CorrectionStore()
        store.add("調亮一點", "OP SYSTEM_VALUE(brightness=up)")
        val parsed = CorrectionStore.parse(store.toLines().asSequence())
        assertEquals("OP SYSTEM_VALUE(brightness=up)", parsed.lookup("調亮一點")?.action)
    }
}
