package com.wae.agent.core

import com.wae.agent.core.action.ActionTelemetry
import com.wae.agent.core.twin.TwinFastPath
import com.wae.agent.core.twin.TwinStep
import com.wae.agent.core.twin.TwinTaskKey
import com.wae.agent.core.twin.parseTwinStep
import com.wae.agent.core.twin.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0 架構閉環測試：Twin 任務簽名（P0-1）與遙測持久化（P0-2）。
 *
 * 這兩項都不改變決策邏輯，但決定了「學習」與「重放」是否真的成立，
 * 因此用測試把契約固定下來。
 */
class P0ClosureTest {

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        ActionTelemetry.resetForTest()
    }

    // ── P0-1：任務簽名 ────────────────────────────────────────────────

    @Test
    fun `task key normalizes punctuation and case`() {
        assertEquals("在gmail搜尋invoice", TwinTaskKey.of("在 Gmail 搜尋 invoice！"))
        assertEquals(TwinTaskKey.of("搜尋invoice"), TwinTaskKey.of("搜尋 　invoice。"))
        assertTrue("正規化後長度需有上限", TwinTaskKey.of("測".repeat(200)).length <= 48)
    }

    @Test
    fun `different tasks produce different keys`() {
        val a = TwinTaskKey.of("在gmail搜尋invoice")
        val b = TwinTaskKey.of("在gmail搜尋發票")
        assertFalse("不同任務不得共用簽名（避免跨任務重放）", TwinTaskKey.compatible(a, b))
        assertTrue(TwinTaskKey.compatible(a, a))
    }

    @Test
    fun `legacy records without key stay compatible`() {
        // 舊資料（沒有任務簽名）視為通用，否則升級後所有歷史軌跡都會失效
        assertTrue(TwinTaskKey.compatible("", "在gmail搜尋invoice"))
        assertTrue(TwinTaskKey.compatible("在gmail搜尋invoice", ""))
    }

    @Test
    fun `step json round trip keeps command key`() {
        val step = TwinStep(
            pkg = "com.android.settings",
            spineBefore = 1L,
            spineAfter = 2L,
            action = "ADJUST_VALUE",
            target = "亮度",
            posture = "LIST_FEED",
            ok = true,
            ms = 320,
            ts = now,
            viewId = "com.android.settings:id/seekbar",
            className = "android.widget.SeekBar",
            commandKey = TwinTaskKey.of("把亮度調高一點")
        )
        val restored = parseTwinStep(step.toJson().toString())
        assertNotNull(restored)
        assertEquals(step.commandKey, restored!!.commandKey)
        assertEquals("亮度", restored.target)
    }

    @Test
    fun `legacy json without command key parses to empty`() {
        val legacy = """{"pkg":"a","before":1,"after":2,"action":"TAP","target":"x","posture":"","ok":true,"ms":1,"ts":1}"""
        val step = parseTwinStep(legacy)
        assertNotNull(step)
        assertEquals("", step!!.commandKey)
    }

    @Test
    fun `fast path does not cross tasks`() {
        val id = "com.example:id/search"
        val recorded = TwinStep(
            pkg = "com.example",
            spineBefore = 1L,
            spineAfter = 2L,
            action = "TAP",
            target = "搜尋",
            posture = "",
            ok = true,
            ms = 10,
            ts = now - 1000,
            viewId = id,
            sigsBefore = (1L..10L).toList(),
            commandKey = TwinTaskKey.of("搜尋第一封")
        )
        val options = listOf(
            CandidateOption(
                markId = 1,
                nodeStableSig = 11L,
                source = CandidateSource.A11Y_TREE,
                detectorConfidence = 1f,
                grid = intArrayOf(0, 0, 100, 100),
                coreText = "搜尋",
                weldedDescription = "搜尋",
                hybridSimScore = 0.5f,
                viewId = id,
                className = "android.widget.Button",
                roleTag = "search_submit"
            )
        )

        val hitSame = TwinFastPath.match(
            pkg = "com.example", action = "TAP", posture = "", sigsBefore = (1L..10L).toList(),
            options = options, history = listOf(recorded),
            commandKey = TwinTaskKey.of("搜尋第一封"), nowMs = now, minPriorSuccess = 1
        )
        assertNotNull("同一任務應命中快路徑", hitSame)

        val hitOther = TwinFastPath.match(
            pkg = "com.example", action = "TAP", posture = "", sigsBefore = (1L..10L).toList(),
            options = options, history = listOf(recorded),
            commandKey = TwinTaskKey.of("搜尋第二封"), nowMs = now, minPriorSuccess = 1
        )
        assertNull("不同任務不得命中（跨任務重放防護）", hitOther)
    }

    // ── P0-2：遙測持久化 ──────────────────────────────────────────────

    @Test
    fun `demotion survives a simulated restart`() {
        repeat(5) { ActionTelemetry.record("pkg", "id:btn", success = false) }
        assertTrue("5 次失敗應被降權", ActionTelemetry.isDemoted("pkg", "id:btn"))

        val saved = ActionTelemetry.toJson()
        ActionTelemetry.resetForTest()
        assertFalse("重啟後（尚未載入）應沒有資料", ActionTelemetry.isDemoted("pkg", "id:btn"))

        ActionTelemetry.loadFromJson(saved)
        assertTrue("載入後降權狀態應保留", ActionTelemetry.isDemoted("pkg", "id:btn"))
        assertEquals(0f, ActionTelemetry.successRate("pkg", "id:btn")!!, 0.001f)
    }

    @Test
    fun `bad json fails safe`() {
        ActionTelemetry.record("pkg", "id:x", success = true)
        ActionTelemetry.loadFromJson("{ not json")
        assertEquals(-1f, ActionTelemetry.successRate("pkg", "id:x")!!, 0.001f)
        assertEquals(0, ActionTelemetry.snapshot().size)

        // schema 版本不符 → 不載入（避免舊格式汙染）
        ActionTelemetry.loadFromJson("""{"schema":999,"stats":[{"key":"p|s","ok":1,"fail":9}]}""")
        assertEquals(0, ActionTelemetry.snapshot().size)
    }

    @Test
    fun `mixed outcomes keep the ratio`() {
        repeat(3) { ActionTelemetry.record("pkg", "id:y", success = true) }
        repeat(3) { ActionTelemetry.record("pkg", "id:y", success = false) }
        val saved = ActionTelemetry.toJson()
        ActionTelemetry.resetForTest()
        ActionTelemetry.loadFromJson(saved)
        assertEquals(0.5f, ActionTelemetry.successRate("pkg", "id:y")!!, 0.001f)
        // 6 筆樣本、成功率 50% < 60% → 降權
        assertTrue(ActionTelemetry.isDemoted("pkg", "id:y"))
    }
}
