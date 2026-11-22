package com.wae.agent.core.predicate

import com.wae.core.model.ControlKind
import com.wae.core.predicate.PredicateControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 決策模型的回歸測試（`DecisionRanker`）。
 *
 * 存在理由：這是「點哪裡」的唯一判斷點，先前**一個測試都沒有**——
 * 3859 行的無障礙服務與 536 行的述詞執行器都靠它，改一行只能靠真機猜。
 *
 * 這裡刻意用**確定性**的手法：`context = null` 時不會載入任何模型，
 * 語意分只走「字面包含」這一階（1.0 或 0.0），所以分數可以手算驗證。
 *
 * 已知邊界（P3／P4 會改）：純圖示按鈕（沒有 label）目前語意分為 0，
 * 一定低於 `match.similarity_floor`，也就是**永遠不會被選中**。
 * 最後一條測試把這個現況寫成可執行的記錄。
 */
class DecisionRankerTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/WAE/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    private fun loadShippedWeights() {
        TunablesBridge.load(asset("ui_tunables.tsv").readLines(Charsets.UTF_8).asSequence())
    }

    /** 只為測試存在的薄橋接：避免測試直接依賴 runtime 套件的載入順序。 */
    private object TunablesBridge {
        fun load(lines: Sequence<String>) {
            com.wae.agent.core.runtime.Tunables.resetForTest()
            com.wae.agent.core.runtime.Tunables.loadFromLines(lines)
        }
    }

    private fun floatOf(key: String, fallback: Float): Float =
        com.wae.agent.core.runtime.Tunables.getFloat(null, key, fallback)

    @Before
    fun setUp() {
        com.wae.agent.core.runtime.Tunables.resetForTest()
    }

    private fun control(
        label: String,
        kind: ControlKind,
        checked: Boolean? = null,
        enabled: Boolean = true,
        key: String = "k"
    ): PredicateControl {
        val labelV = label
        val kindV = kind
        val checkedV = checked
        val enabledV = enabled
        val keyV = key
        return object : PredicateControl {
            override val key: String = keyV
            override val label: String = labelV
            override val kind: ControlKind = kindV
            override val checked: Boolean? = checkedV
            override val editable: Boolean = false
            override val enabled: Boolean = enabledV
            override val valueFraction: Float? = null
            override val textHash: String = ""
        }
    }

    @Test
    fun `literal label match plus kind match scores highest`() {
        loadShippedWeights()
        val c = control("藍牙", ControlKind.TOGGLE)
        val s = DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, c)
        // 0.55 語意 + 0.22 角色 + 0.15×0.5（沒指定狀態） + 0.08 可用
        assertEquals(0.925f, s, 0.002f)
    }

    @Test
    fun `kind mismatch is punished even when the text matches`() {
        loadShippedWeights()
        // 真實踩過的坑：「行動熱點與網路共享」這列文字很像，但它是 ROW 不是開關，
        // 點下去會進到不相干的頁面。
        val toggle = control("藍牙", ControlKind.TOGGLE)
        val row = control("藍牙", ControlKind.ROW)
        val sToggle = DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, toggle)
        val sRow = DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, row)
        assertTrue("角色不符必須扣分：toggle=$sToggle row=$sRow", sToggle > sRow)
        assertEquals(floatOf("match.weight.kind", 0.22f), sToggle - sRow, 0.002f)
    }

    @Test
    fun `state mismatch is weaker than state match but not fatal`() {
        loadShippedWeights()
        val on = control("Wi-Fi", ControlKind.TOGGLE, checked = true)
        val off = control("Wi-Fi", ControlKind.TOGGLE, checked = false)
        val sOn = DecisionRanker.score("Wi-Fi", ControlKind.TOGGLE, true, on)
        val sOff = DecisionRanker.score("Wi-Fi", ControlKind.TOGGLE, true, off)
        assertTrue("狀態相符要比相反高", sOn > sOff)
        // 狀態相反只是「可能要翻轉它」，仍保留分數（不是 0）
        assertTrue("狀態相反不該歸零", sOff > 0.5f)
    }

    @Test
    fun `unknown kind gets partial credit`() {
        loadShippedWeights()
        val unknown = control("藍牙", ControlKind.UNKNOWN)
        val wrong = control("藍牙", ControlKind.TOGGLE)
        val sUnknown = DecisionRanker.score("藍牙", ControlKind.FIELD, null, unknown)
        val sWrong = DecisionRanker.score("藍牙", ControlKind.FIELD, null, wrong)
        assertTrue("UNKNOWN 要比明確不符寬容", sUnknown > sWrong)
    }

    @Test
    fun `disabled control loses only the usability weight`() {
        loadShippedWeights()
        val enabled = control("藍牙", ControlKind.TOGGLE, enabled = true)
        val disabled = control("藍牙", ControlKind.TOGGLE, enabled = false)
        val sEnabled = DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, enabled)
        val sDisabled = DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, disabled)
        assertEquals(floatOf("match.weight.usable", 0.08f), sEnabled - sDisabled, 0.002f)
    }

    @Test
    fun `blank label scores zero semantic`() {
        loadShippedWeights()
        assertEquals(0f, DecisionRanker.textScore("藍牙", "", null), 0.0001f)
        assertEquals(0f, DecisionRanker.textScore("", "藍牙", null), 0.0001f)
    }

    @Test
    fun `literal containment is direction sensitive`() {
        loadShippedWeights()
        // 標籤比目標長 → 命中（「藍牙與其他裝置」之於「藍牙」）
        assertEquals(1f, DecisionRanker.textScore("藍牙", "藍牙與其他裝置", null), 0.0001f)
        // 目標比標籤長 → 對不上（「藍牙耳機」對上標籤「藍牙」只得 0）
        // 這是現況弱點：查詢常帶修飾詞，字面階一對不上就掉到語意階。
        assertEquals(0f, DecisionRanker.textScore("藍牙耳機", "藍牙", null), 0.0001f)
    }

    @Test
    fun `weights really come from the tunables table`() {
        // 把語意權重壓成 0、角色權重拉成 1：分數應該只由角色決定。
        TunablesBridge.load(
            sequenceOf(
                "match.weight.semantic\t0",
                "match.weight.kind\t1",
                "match.weight.state\t0",
                "match.weight.usable\t0"
            )
        )
        val matching = control("藍牙", ControlKind.TOGGLE)
        val wrongKind = control("藍牙", ControlKind.ROW)
        assertEquals(1f, DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, matching), 0.001f)
        assertEquals(0f, DecisionRanker.score("藍牙", ControlKind.TOGGLE, null, wrongKind), 0.001f)
    }

    @Test
    fun `score is clamped to one`() {
        TunablesBridge.load(
            sequenceOf(
                "match.weight.semantic\t1",
                "match.weight.kind\t1",
                "match.weight.state\t1",
                "match.weight.usable\t1"
            )
        )
        val c = control("藍牙", ControlKind.TOGGLE, checked = true)
        val s = DecisionRanker.score("藍牙", ControlKind.TOGGLE, true, c)
        assertEquals(1f, s, 0.0001f)
    }

    @Test
    fun `shipped table defines every match key`() {
        val lines = asset("ui_tunables.tsv").readLines(Charsets.UTF_8)
        val keys = lines
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 2 }
            .associate { it[0].trim() to it[1].trim() }
        for (k in listOf(
            "match.weight.semantic", "match.weight.kind", "match.weight.state",
            "match.weight.usable", "match.similarity_floor"
        )) {
            assertTrue("ui_tunables.tsv 缺少 $k", keys.containsKey(k))
            assertTrue("$k 必須是數字", keys.getValue(k).toFloatOrNull() != null)
        }
        // 四項權重相加應為 1（分數才落在 0..1，門檻才有意義）
        val sum = listOf("match.weight.semantic", "match.weight.kind", "match.weight.state", "match.weight.usable")
            .sumOf { keys.getValue(it).toDouble() }
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `icon only control stays below the act floor which is the known gap`() {
        loadShippedWeights()
        // 純圖示按鈕：沒有 label，只有角色與可用性。
        val iconOnly = control("", ControlKind.BUTTON)
        val s = DecisionRanker.score("路線", ControlKind.BUTTON, null, iconOnly)
        val floor = floatOf("match.similarity_floor", 0.62f)
        assertTrue(
            "現況：純圖示按鈕（地圖的「路線」）分數 $s 低於門檻 $floor，" +
                "所以永遠不會被選中。P3／P4 的多視圖（圖示語意）要把這條翻過來。",
            s < floor
        )
    }
}
