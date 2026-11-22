package com.wae.agent.core.component

import com.wae.agent.core.CandidateOption
import com.wae.agent.core.CandidateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 元件能力圖譜測試。
 *
 * 這裡同時扮演「資料庫守門」：直接讀真實的 TSV，確保程式依賴的
 * 元件家族、能力、用途標籤真的存在（資料被改壞時測試會先擋下來）。
 */
class WidgetOntologyTest {

    private fun dbFile(): File {
        val candidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/ui_widget_capability_ontology.tsv"),
            File("app/src/main/assets/open_source_ai_databases/ui_widget_capability_ontology.tsv"),
            File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/ui_widget_capability_ontology.tsv")
        )
        return candidates.first { it.exists() }
    }

    @Before
    fun setUp() {
        WidgetOntology.resetForTest()
        WidgetOntology.loadFromLines(dbFile().readLines(Charsets.UTF_8))
        // 用途 → 所屬分類（決定「該往哪一列鑽」）
        val catCandidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/ui_purpose_category_map.tsv"),
            File("app/src/main/assets/open_source_ai_databases/ui_purpose_category_map.tsv"),
            File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/ui_purpose_category_map.tsv")
        )
        WidgetOntology.loadCategoriesFromLines(catCandidates.first { it.exists() }.readLines(Charsets.UTF_8))
        assertTrue("元件能力圖譜應載入成功", WidgetOntology.isLoaded())
    }

    @Test
    fun `purpose maps to the right settings category`() {
        // 「亮度」住在「顯示」分類，不是「音效與震動」——這正是先前鑽錯分支的根因
        val display = WidgetOntology.categoryFor("亮度")
        assertNotNull("「亮度」應能對應到分類", display)
        assertEquals("display", display!!.id)
        assertTrue("「顯示螢幕」應被判定屬於顯示分類", display.matches("顯示螢幕"))
        assertTrue(display.matches("顯示"))
        assertFalse("「已連接的裝置」不屬於顯示分類", display.matches("已連接的裝置"))
        assertFalse("「音效與震動」不屬於顯示分類", display.matches("音效與震動"))

        val sound = WidgetOntology.categoryFor("音量")
        assertNotNull(sound)
        assertEquals("sound", sound!!.id)
        assertTrue(sound.matches("音效與震動"))
    }

    @Test
    fun `category database is non trivial`() {
        assertTrue("分類數應有十餘個，實際=${WidgetOntology.categoryCount()}", WidgetOntology.categoryCount() >= 10)
        assertNull("未知用途不應亂給分類", WidgetOntology.categoryFor("完全不存在的用途詞"))
    }

    @Test
    fun `database covers the widget families we rely on`() {
        assertTrue("家族數應具一定規模，實際=${WidgetOntology.familyCount()}", WidgetOntology.familyCount() >= 40)
        val required = listOf(
            "seek_bar", "range_slider", "switch", "spinner", "recycler_view",
            "text_view", "edit_text", "button", "seekbar_preference", "switch_preference",
            "progress_bar", "web_view", "generic_view", "compose_view"
        )
        for (id in required) {
            assertTrue(
                "缺少元件家族：$id",
                WidgetOntology.allFamilies().any { it.id == id }
            )
        }
    }

    @Test
    fun `class name resolves to family`() {
        assertEquals("seek_bar", WidgetOntology.familyOfClass("android.widget.SeekBar")?.id)
        assertEquals("seek_bar", WidgetOntology.familyOfClass("androidx.appcompat.widget.AppCompatSeekBar")?.id)
        assertEquals("switch", WidgetOntology.familyOfClass("androidx.appcompat.widget.SwitchCompat")?.id)
        assertEquals("button", WidgetOntology.familyOfClass("com.google.android.material.button.MaterialButton")?.id)
        assertEquals(
            "seekbar_preference",
            WidgetOntology.familyOfClass("androidx.preference.SeekBarPreferenceCompat")?.id
        )
    }

    @Test
    fun `longest pattern wins for subclasses`() {
        // RangeSlider 的類別名同時包含 BaseSlider 片段，必須命中「區間滑桿」而非一般滑桿
        assertEquals(
            "range_slider",
            WidgetOntology.familyOfClass("com.google.android.material.slider.RangeSlider")?.id
        )
        // 未知/自繪元件（例如 Flutter 的 FlutterView）不應被硬猜成某個家族
        assertNull(WidgetOntology.familyOfClass("io.flutter.embedding.android.FlutterView"))
    }

    @Test
    fun `capability lookup answers what a widget can do`() {
        val slider = WidgetOntology.capabilitiesOfClass("android.widget.SeekBar")
        assertTrue("滑桿應可設定數值", Capability.SET_VALUE in slider)
        assertTrue("滑桿應可拖曳", Capability.DRAG in slider)

        val sw = WidgetOntology.capabilitiesOfClass("android.widget.Switch")
        assertTrue("開關應可切換", Capability.TOGGLE in sw)

        val bar = WidgetOntology.capabilitiesOfClass("android.widget.ProgressBar")
        assertTrue("進度條應是唯讀", Capability.READ_ONLY in bar)
        assertFalse("進度條不應可設定數值", Capability.SET_VALUE in bar)
    }

    @Test
    fun `reverse lookup from purpose to widget families`() {
        val brightness = WidgetOntology.familiesForPurpose("亮度")
        assertTrue(
            "「亮度」應能反查到滑桿類元件，實際=${brightness.map { it.id }}",
            brightness.any { it.id == "seek_bar" } && brightness.any { it.id == "seekbar_preference" }
        )
        assertTrue(WidgetOntology.purposeImpliesValueAdjustment("亮度"))
        assertTrue(WidgetOntology.purposeImpliesValueAdjustment("音量"))
        // 不是數值的用途不應該被當成「要找滑桿」
        assertFalse(WidgetOntology.purposeImpliesValueAdjustment("頭像"))
    }

    @Test
    fun `capability reverse index works`() {
        val adjustable = WidgetOntology.familiesWithCapability(Capability.SET_VALUE).map { it.id }
        assertTrue("可調元件應包含滑桿", adjustable.contains("seek_bar"))
        assertTrue("可調元件應包含數字選擇器", adjustable.contains("number_picker"))
        assertFalse("進度條不應在可調清單", adjustable.contains("progress_bar"))
    }

    // ── 能力融合 ────────────────────────────────────────────────────────

    @Test
    fun `runtime range wins over unknown class`() {
        // Compose / 自繪滑桿：類別名是通用 View，但執行期有 RangeInfo
        val a = AffordanceFusion.resolve(
            RuntimeSignals(
                className = "android.view.View",
                isClickable = true,
                hasRange = true,
                rangeMin = 0f,
                rangeMax = 255f,
                rangeCurrent = 120f,
                rangeIsPercent = false
            )
        )
        assertTrue("應融合出 SET_VALUE", a.has(Capability.SET_VALUE))
        assertTrue("應判定為可調", a.adjustable)
        assertFalse("可調元件不應被視為唯讀", a.readOnlyOnly)
        assertTrue("應留下證據供審計，實際=${a.evidence}", a.evidence.contains("range"))
    }

    @Test
    fun `read only progress bar with range is not adjustable`() {
        val a = AffordanceFusion.resolve(
            RuntimeSignals(
                className = "android.widget.ProgressBar",
                isClickable = false,
                hasRange = true,
                rangeMin = 0f,
                rangeMax = 100f,
                rangeCurrent = 40f
            )
        )
        assertFalse("純進度條不該被當成可調整", a.adjustable)
        assertTrue("應保留唯讀", a.has(Capability.READ_ONLY))
    }

    @Test
    fun `set progress action alone means adjustable`() {
        val a = AffordanceFusion.resolve(
            RuntimeSignals(
                className = "android.view.View",
                hasSetProgressAction = true,
                hasRange = true,
                rangeMin = 0f,
                rangeMax = 10f,
                rangeCurrent = 5f
            )
        )
        assertTrue(a.adjustable)
        assertTrue(a.confidence >= 0.9f)
    }

    @Test
    fun `switch family yields toggle even without runtime flags`() {
        val a = AffordanceFusion.resolve(RuntimeSignals(className = "androidx.appcompat.widget.SwitchCompat"))
        assertTrue(a.has(Capability.TOGGLE))
    }

    @Test
    fun `unknown non interactive node is read only`() {
        val a = AffordanceFusion.resolve(RuntimeSignals(className = "android.view.View"))
        assertTrue(a.readOnlyOnly)
    }

    // ── 標籤與可調元件的空間配對 ─────────────────────────────────────────

    private fun cand(
        id: Int,
        grid: IntArray,
        text: String = "",
        adjustable: Boolean = false,
        className: String = "android.view.View",
        clickable: Boolean = true
    ) = CandidateOption(
        markId = id,
        nodeStableSig = id.toLong(),
        source = CandidateSource.A11Y_TREE,
        detectorConfidence = 1f,
        grid = grid,
        coreText = text,
        weldedDescription = text,
        hybridSimScore = 0.5f,
        isClickable = clickable,
        className = className,
        hasRange = adjustable,
        isAdjustable = adjustable,
        rangeMin = 0f,
        rangeMax = 100f,
        rangeCurrent = 50f
    )

    @Test
    fun `label above slider associates below`() {
        val label = cand(1, intArrayOf(40, 400, 700, 460), text = "亮度")
        val slider = cand(2, intArrayOf(40, 470, 700, 540), adjustable = true)
        val other = cand(3, intArrayOf(40, 900, 700, 960), adjustable = true)
        val m = ValueTargetResolver.associate(label, listOf(label, slider, other))
        assertNotNull("應配對到正下方的滑桿", m)
        assertEquals(2, m!!.target.markId)
        assertTrue(m.relation.startsWith("below"))
    }

    @Test
    fun `label with same row switch associates right`() {
        val label = cand(1, intArrayOf(40, 400, 300, 460), text = "深色模式")
        val toggle = cand(2, intArrayOf(700, 410, 800, 450), text = "", adjustable = true)
        val m = ValueTargetResolver.associate(label, listOf(label, toggle))
        assertNotNull(m)
        assertEquals(2, m!!.target.markId)
        assertTrue("同列右側應被接受，實際=${m.relation}", m.relation.startsWith("same-row"))
    }

    @Test
    fun `label far from any adjustable returns null`() {
        val label = cand(1, intArrayOf(40, 400, 700, 460), text = "帳號")
        val slider = cand(2, intArrayOf(40, 1800, 700, 1900), adjustable = true)
        assertNull(ValueTargetResolver.associate(label, listOf(label, slider)))
    }

    @Test
    fun `adjustable label associates to itself`() {
        val label = cand(1, intArrayOf(40, 400, 700, 460), text = "亮度", adjustable = true)
        val m = ValueTargetResolver.associate(label, listOf(label))
        assertNotNull(m)
        assertEquals("self", m!!.relation)
    }
}
