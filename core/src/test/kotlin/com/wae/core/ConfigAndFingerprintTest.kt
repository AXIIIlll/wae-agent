package com.wae.core

import com.wae.core.config.Config
import com.wae.core.config.ConfigParser
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.config.MapConfig
import com.wae.core.model.FrameFingerprint
import com.wae.core.util.TextHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigAndFingerprintTest {

    @Test
    fun `parser strips BOM and comments`() {
        val lines = sequenceOf(
            "\uFEFF# key\tvalue\t說明",
            "sample.limit\t12\t數值上限",
            "",
            "broken_line_without_tab",
            "empty_value\t"
        )
        val map = ConfigParser.parse(lines)
        assertEquals("12", map["sample.limit"])
        assertEquals(1, map.size)
    }

    @Test
    fun `map config falls back to defaults`() {
        val config: Config = MapConfig(mapOf(Key.PLAN_MAX_REPLANS to "5"))
        assertEquals(5, config.int(Key.PLAN_MAX_REPLANS, Default.MAX_REPLANS))
        assertEquals(Default.MAX_REPLANS, config.int(Key.STEP_MAX_ATTEMPTS, Default.MAX_REPLANS))
        assertEquals(Default.CONFIRM_HIGH_RISK, config.bool(Key.SAFETY_CONFIRM_HIGH_RISK, Default.CONFIRM_HIGH_RISK))
    }

    @Test
    fun `missing required keys are reported`() {
        val parsed = ConfigParser.parse(sequenceOf("a\t1\tdesc"))
        val missing = ConfigParser.missing(parsed, listOf("a", "b"))
        assertEquals(listOf("b"), missing)
    }

    @Test
    fun `fingerprint is order independent and content sensitive`() {
        val a = TestFrames.toggleControl(key = "sw1")
        val b = TestFrames.fieldControl(key = "et1")

        val fp1 = FrameFingerprint.of("pkg", 1L, listOf(a, b))
        val fp2 = FrameFingerprint.of("pkg", 1L, listOf(b, a))
        assertEquals(fp1, fp2)

        val fp3 = FrameFingerprint.of("pkg", 2L, listOf(a, b))
        assertNotEquals(fp1, fp3)

        val fp4 = FrameFingerprint.of("pkg", 1L, listOf(b))
        assertNotEquals(fp1, fp4)
    }

    @Test
    fun `fingerprint is stable across calls`() {
        val controls = listOf(TestFrames.toggleControl())
        assertEquals(
            FrameFingerprint.of("pkg", 1L, controls),
            FrameFingerprint.of("pkg", 1L, controls)
        )
    }

    @Test
    fun `text hash normalizes case and whitespace with root locale`() {
        assertEquals(TextHash.of("  藍牙  "), TextHash.of("藍牙"))
        assertEquals(TextHash.of("WiFi"), TextHash.of("wifi"))
        assertTrue(TextHash.of("a b") != TextHash.of("ab"))
    }
}
