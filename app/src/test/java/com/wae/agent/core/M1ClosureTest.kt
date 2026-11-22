package com.wae.agent.core

import com.wae.agent.core.action.NegativeEdgeStore
import com.wae.agent.core.action.NegativeReason
import com.wae.agent.core.safety.SystemSafetyPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M1 測試：通用性修補（系統安全套件資料化）與負向邊（S2 地基）。
 */
class M1ClosureTest {

    private fun tsv(name: String): File {
        val candidates = listOf(
            File("../app/src/main/assets/open_source_ai_databases/$name"),
            File("app/src/main/assets/open_source_ai_databases/$name"),
            File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
        )
        return candidates.first { it.exists() }
    }

    @Before
    fun setUp() {
        SystemSafetyPackages.resetForTest()
        NegativeEdgeStore.resetForTest()
    }

    // ── 系統安全套件（資料驅動）────────────────────────────────────────

    @Test
    fun `safety packages come from the database`() {
        SystemSafetyPackages.loadFromLines(tsv("system_safety_packages.tsv").readLines(Charsets.UTF_8))
        assertTrue("資料庫應載入成功", SystemSafetyPackages.isLoaded())
        assertTrue("AOSP 權限對話框", SystemSafetyPackages.isBlocked("com.android.permissioncontroller"))
        assertTrue("Google 權限對話框", SystemSafetyPackages.isBlocked("com.google.android.permissioncontroller"))
        assertTrue("MIUI 權限管理", SystemSafetyPackages.isBlocked("com.lbe.security.miui"))
        assertTrue("ColorOS 權限管理", SystemSafetyPackages.isBlocked("com.coloros.securitypermission"))
        assertFalse("一般 App 不該被擋", SystemSafetyPackages.isBlocked("com.example.app"))
        assertFalse("空字串不該被擋", SystemSafetyPackages.isBlocked(""))
    }

    @Test
    fun `safety fails safe even before loading`() {
        // 資料檔沒載入時，仍以語意規則擋住權限控制器（失敗要往保守方向倒）
        assertTrue(SystemSafetyPackages.isBlocked("com.some.vendor.permissioncontroller"))
        assertTrue(SystemSafetyPackages.isBlocked("com.vendor.packageinstaller"))
        assertFalse(SystemSafetyPackages.isBlocked("com.example.shop"))
    }

    // ── 負向邊 ────────────────────────────────────────────────────────

    @Test
    fun `no effect needs two strikes before blocking`() {
        val pkg = "com.example"
        val sel = "id:search"
        val op = "TAP"
        NegativeEdgeStore.record(pkg, sel, op, NegativeReason.NO_EFFECT)
        assertFalse("一次失敗可能只是偶然，不封鎖", NegativeEdgeStore.isBlocked(pkg, sel, op))
        assertTrue("但已有懲罰", NegativeEdgeStore.penalty(pkg, sel, op) > 0f)

        NegativeEdgeStore.record(pkg, sel, op, NegativeReason.NO_EFFECT)
        assertTrue("兩次失敗即視為不可行", NegativeEdgeStore.isBlocked(pkg, sel, op))
    }

    @Test
    fun `wrong effect blocks immediately`() {
        NegativeEdgeStore.record("pkg", "id:x", "TAP", NegativeReason.WRONG_EFFECT)
        assertTrue("跑到非預期頁面 → 立刻封鎖", NegativeEdgeStore.isBlocked("pkg", "id:x", "TAP"))
        assertEquals(NegativeReason.WRONG_EFFECT, NegativeEdgeStore.reasonOf("pkg", "id:x", "TAP"))
    }

    @Test
    fun `success decays the negative memory`() {
        val pkg = "p"
        val sel = "s"
        val op = "TAP"
        repeat(2) { NegativeEdgeStore.record(pkg, sel, op, NegativeReason.NO_EFFECT) }
        assertTrue(NegativeEdgeStore.isBlocked(pkg, sel, op))

        NegativeEdgeStore.recordSuccess(pkg, sel, op)
        assertFalse("成功一次後解除封鎖（避免永久誤殺）", NegativeEdgeStore.isBlocked(pkg, sel, op))
        NegativeEdgeStore.recordSuccess(pkg, sel, op)
        assertEquals("計數歸零即移除", 0, NegativeEdgeStore.size())
    }

    @Test
    fun `penalty is capped`() {
        repeat(10) { NegativeEdgeStore.record("p", "s", "TAP", NegativeReason.TIMEOUT) }
        val p = NegativeEdgeStore.penalty("p", "s", "TAP")
        assertTrue("懲罰需有上限，實際=$p", p <= 0.30f)
        assertTrue(p > 0f)
    }

    @Test
    fun `expired edges stop blocking`() {
        val now = 1_700_000_000_000L
        repeat(2) { NegativeEdgeStore.record("p", "s", "TAP", NegativeReason.NO_EFFECT, now) }
        assertTrue(NegativeEdgeStore.isBlocked("p", "s", "TAP", now))
        // 31 天後（NO_EFFECT 的 TTL 為 30 天）
        assertFalse(NegativeEdgeStore.isBlocked("p", "s", "TAP", now + 31L * 24 * 3600 * 1000))
    }

    @Test
    fun `json round trip keeps edges`() {
        NegativeEdgeStore.record("pkg", "id:a", "TAP", NegativeReason.WRONG_EFFECT)
        val json = NegativeEdgeStore.toJson()
        NegativeEdgeStore.resetForTest()
        assertEquals(0, NegativeEdgeStore.size())
        NegativeEdgeStore.loadFromJson(json)
        assertEquals(1, NegativeEdgeStore.size())
        assertTrue(NegativeEdgeStore.isBlocked("pkg", "id:a", "TAP"))
    }

    @Test
    fun `bad json fails safe`() {
        NegativeEdgeStore.record("pkg", "id:a", "TAP", NegativeReason.WRONG_EFFECT)
        NegativeEdgeStore.loadFromJson("{ broken")
        assertEquals(0, NegativeEdgeStore.size())
        NegativeEdgeStore.loadFromJson("""{"schema":999,"edges":[]}""")
        assertEquals(0, NegativeEdgeStore.size())
    }

    @Test
    fun `keys are scoped by package selector and op`() {
        NegativeEdgeStore.record("p1", "id:x", "TAP", NegativeReason.NO_EFFECT)
        NegativeEdgeStore.record("p1", "id:x", "TAP", NegativeReason.NO_EFFECT)
        assertTrue(NegativeEdgeStore.isBlocked("p1", "id:x", "TAP"))
        assertFalse("不同套件不共用負邊", NegativeEdgeStore.isBlocked("p2", "id:x", "TAP"))
        assertFalse("不同動作不共用負邊", NegativeEdgeStore.isBlocked("p1", "id:x", "TYPE"))
    }
}
