package com.wae.core.predicate

import com.wae.core.model.ControlKind
import org.junit.Test

/**
 * **模糊語義能力基準**（診斷用，刻意不斷言、只印表）。
 *
 * 為什麼要印表而不是斷言：這份表的目的不是「證明它會」，
 * 而是**量出它現在到底能做什麼**，作為補強前的基線。
 *
 * 八類模糊：
 *  1. 詞義（開 = 開啟 App / 打開開關）
 *  2. 指涉（那個、剛才的）
 *  3. 目標缺失（調一下、弄好）
 *  4. 量化（調大一點 vs 調到最大）
 *  5. 條件省略（如果沒開就開）
 *  6. 多目標（開 WiFi 和藍牙）
 *  7. 否定（不要開 WiFi）
 *  8. 時間（等一下再開）
 */
class AmbiguityBenchmarkTest {

    private val lex = PredicateLexicon(
        toggleWords = setOf("打開", "開啟", "開", "關閉", "關掉", "關"),
        onWords = setOf("打開", "開啟", "開", "on", "enable"),
        offWords = setOf("關閉", "關掉", "關", "off", "disable"),
        fillers = setOf("幫我", "請", "把", "將", "一下"),
        adjustVerbs = setOf("調", "設", "改"),
        directions = mapOf("高" to 1, "低" to -1, "亮" to 1, "暗" to -1, "大" to 1, "小" to -1),
        degreeSmall = setOf("一點", "一些"),
        degreeLarge = setOf("很多", "最大", "最小"),
        valueLabels = mapOf("亮度" to "亮度", "音量" to "音量"),
        appAliases = mapOf("相機" to "com.android.camera", "設定" to "com.android.settings"),
        settingsPkg = "com.android.settings"
    )

    private val compiler = NlPredicateCompiler(lex)

    private val world = FactsWorld(
        pkg = "com.android.settings",
        controls = listOf(
            FactsControl("w1", "Wi-Fi", ControlKind.TOGGLE, checked = false),
            FactsControl("w2", "Wi-Fi 通話", ControlKind.TOGGLE, checked = false),
            FactsControl("b1", "藍牙", ControlKind.TOGGLE, checked = false),
            FactsControl("s1", "亮度", ControlKind.SLIDER, valueFraction = 0.4f),
            FactsControl("r1", "儲存空間", ControlKind.ROW)
        )
    )

    private val cases = listOf(
        "開 WiFi" to "詞義：App 還是開關",
        "打開那個" to "指涉：哪個",
        "調一下" to "目標缺失",
        "弄好它" to "目標缺失",
        "調大一點" to "量化：哪個元件",
        "把亮度調到最大" to "量化：絕對值",
        "如果沒開就開 WiFi" to "條件省略",
        "開 WiFi 和藍牙" to "多目標：一個動作兩個目標",
        "不要開 WiFi" to "否定",
        "等一下再開 WiFi" to "時間",
        "幫我訂機票" to "完全未知"
    )

    @Test
    fun `print ambiguity baseline`() {
        println("═".repeat(96))
        println("模糊語義基準（現況）")
        println("═".repeat(96))
        var operable = 0
        var asked = 0
        for ((cmd, kind) in cases) {
            val c = compiler.compile(cmd)
            val v = AmbiguityDetector.ofCompiled(c, world)
            if (c.operable) operable += 1
            if (v.blocking) asked += 1
            val verdict = when {
                !c.operable -> "無法編譯"
                v.kind == AmbiguityDetector.Kind.MULTIPLE_CANDIDATES -> "問：多候選"
                v.kind == AmbiguityDetector.Kind.UNDEFINED_TARGET -> "問：無目標"
                v.kind == AmbiguityDetector.Kind.UNKNOWN_INTENT -> "問：聽不懂"
                else -> "直接執行"
            }
            val goal = c.goal?.toString()?.take(58) ?: c.relative?.let { "RELATIVE(δ=${it.delta})" } ?: "—"
            println("$cmd")
            println("    類別=$kind")
            println("    結果=$verdict   goal=$goal")
        }
        println("═".repeat(96))
        println("可編譯 $operable / ${cases.size}　　會反問 $asked / ${cases.size}")
        println("═".repeat(96))
    }
}
