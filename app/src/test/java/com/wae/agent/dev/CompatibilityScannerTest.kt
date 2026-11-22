package com.wae.agent.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 相容性掃描的純邏輯測試：判定規則與資料檔解析。
 * （實際掃描需要真機，不在單元測試範圍。）
 */
class CompatibilityScannerTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/dev/$name"),
        File("app/src/main/assets/dev/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/dev/$name")
    ).first { it.exists() }

    private val signals = listOf(
        CompatibilityScanner.Signal("無障礙", false),
        CompatibilityScanner.Signal("請關閉無障礙", true),
        CompatibilityScanner.Signal("遠端控制", true),
        CompatibilityScanner.Signal("風險", false)
    )

    @Test
    fun `block signal wins over warn`() {
        val (verdict, evidence) = CompatibilityScanner.classify(
            listOf("本服務已偵測到無障礙服務，請關閉無障礙後再使用"), signals
        )
        assertEquals(CompatibilityScanner.Verdict.BLOCKED, verdict)
        assertTrue(evidence.contains("請關閉無障礙"))
    }

    @Test
    fun `warn only is not blocked`() {
        val (verdict, evidence) = CompatibilityScanner.classify(
            listOf("提醒您注意帳號風險"), signals
        )
        assertEquals(CompatibilityScanner.Verdict.WARN, verdict)
        assertTrue(evidence.contains("風險"))
    }

    @Test
    fun `clean screen is ok`() {
        val (verdict, _) = CompatibilityScanner.classify(
            listOf("首頁", "搜尋", "設定"), signals
        )
        assertEquals(CompatibilityScanner.Verdict.OK, verdict)
    }

    @Test
    fun `empty screen is ok`() {
        assertEquals(
            CompatibilityScanner.Verdict.OK,
            CompatibilityScanner.classify(emptyList(), signals).first
        )
    }

    @Test
    fun `cases are matched ignoring case`() {
        val (verdict, _) = CompatibilityScanner.classify(
            listOf("Accessibility service detected"),
            listOf(CompatibilityScanner.Signal("accessibility", false))
        )
        assertEquals(CompatibilityScanner.Verdict.WARN, verdict)
    }

    @Test
    fun `target list loads and covers major categories`() {
        val targets = asset("compat_scan_targets.tsv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .filter { it.size >= 2 }
            .map { CompatibilityScanner.Target(it[0], it[1], it.getOrElse(2) { "" }) }
        assertTrue("目標清單不能是空的", targets.isNotEmpty())
        assertTrue("必須包含中文標籤", targets.any { t -> t.label.any { ch -> ch.code > 127 } })
        val categories = targets.map { it.category }.toSet()
        assertTrue(
            "應涵蓋銀行或支付",
            categories.any { it.contains("銀行") } || categories.any { it.contains("支付") }
        )
        assertTrue(targets.all { it.pkg.trim() == it.pkg && it.pkg.contains(".") })
    }

    @Test
    fun `block signals load and include hard blockers`() {
        val parsed = asset("compat_block_signals.tsv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .filter { it.size >= 2 }
            .map { CompatibilityScanner.Signal(it[0], it[1].trim().uppercase() == "BLOCK") }
        assertTrue(parsed.isNotEmpty())
        assertTrue("至少要有一條硬阻擋", parsed.any { it.blocked })
        assertTrue("至少要有一條警告", parsed.any { !it.blocked })
    }
}
