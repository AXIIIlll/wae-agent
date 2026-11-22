package com.wae.core

import com.wae.core.config.BundledData
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.config.MapConfig
import com.wae.core.effect.IntentCompiler
import com.wae.core.model.ControlKind
import com.wae.core.model.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 內建資料表測試：**出貨的資料本身**就是被測對象。
 *
 * 這個測試存在的理由：`ui_voice_languages.tsv` 曾經是 0 列資料卻被標成 ✅，
 * 因為沒有任何測試讀它。從 Gen-5 起，每一張出貨的表都要有一條「非空且可解析」的測試。
 */
class BundledDataTest {

    @Test
    fun `tunables table is non empty and covers every default key`() {
        val values = BundledData.tunables()
        assertFalse(values.isEmpty())

        val required = listOf(
            Key.STEP_MAX_ATTEMPTS,
            Key.STEP_TIMEOUT_MS,
            Key.PLAN_MAX_REPLANS,
            Key.PERCEPTION_OCR_A11Y_THRESHOLD,
            Key.REFUTATION_BAN_THRESHOLD,
            Key.REFUTATION_TTL_MS,
            Key.EXEC_IDEMPOTENCY_WINDOW_MS,
            Key.ABSTAIN_MIN_CONFIDENCE,
            Key.SAFETY_CONFIRM_HIGH_RISK,
            Key.GESTURE_SCROLL_DELTA_PX,
            Key.GEN5_ENABLED
        )
        assertEquals(emptyList<String>(), com.wae.core.config.ConfigParser.missing(values, required))
    }

    @Test
    fun `tunables values parse into typed config`() {
        val config = MapConfig(BundledData.tunables())
        assertEquals(Default.MAX_REPLANS, config.int(Key.PLAN_MAX_REPLANS, -1))
        assertEquals(Default.BAN_THRESHOLD, config.int(Key.REFUTATION_BAN_THRESHOLD, -1))
        assertEquals(Default.CONFIRM_HIGH_RISK, config.bool(Key.SAFETY_CONFIRM_HIGH_RISK, false))
        // 2026-11-17：使用者要求「按鈕直接走新管線」，所以預設改為啟用；
        // 舊路徑仍是最後備援（Gen-5 完全沒動作時）。
        assertTrue(config.bool(Key.GEN5_ENABLED, false))
    }

    @Test
    fun `contract rules cover the six derived kinds`() {
        val rules = BundledData.contractRules()
        val expected = listOf(
            ControlKind.TOGGLE,
            ControlKind.ROW,
            ControlKind.FIELD,
            ControlKind.SLIDER,
            ControlKind.TAB,
            ControlKind.BUTTON
        )
        for (kind in expected) {
            assertTrue("契約規則缺少 $kind", rules.containsKey(kind))
        }
        assertFalse(rules.containsKey(ControlKind.UNKNOWN))
    }

    @Test
    fun `verb table compiles a toggle command`() {
        val compiler = IntentCompiler(BundledData.verbRules())
        val effects = compiler.compile("關閉藍牙")
        assertEquals(1, effects.size)
        assertEquals("toggle:藍牙", effects[0].target)
        assertEquals(Op.SET, effects[0].op)
    }

    @Test
    fun `verb table compiles app launch and volume`() {
        val compiler = IntentCompiler(BundledData.verbRules())
        assertEquals("app:設定", compiler.compile("打開設定")[0].target)
        assertEquals("volume:master", compiler.compile("調低音量")[0].target)
        assertEquals(IntentCompiler.VALUE_DOWN, compiler.compile("調低音量")[0].value)
    }
}
