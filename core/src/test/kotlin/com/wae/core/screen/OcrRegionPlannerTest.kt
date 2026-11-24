package com.wae.core.screen

import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 區域式 OCR 的規劃器：只決定「看哪裡」，不負責辨識。
 *
 * 之所以先寫測試：這一步最容易偷偷變成「整頁 OCR」，
 * 一旦如此就會拖慢每一步、還會被背景文字干擾。
 */
class OcrRegionPlannerTest {

    private fun control(
        label: String,
        left: Int,
        top: Int,
        width: Int = 100,
        height: Int = 100,
        clickable: Boolean = true,
        channel: Channel = Channel.A11Y
    ) = Control(
        key = "k-$label-$left-$top",
        kind = com.wae.core.model.ControlKind.BUTTON,
        label = label,
        textHash = "",
        bounds = RectI(left, top, left + width, top + height),
        enabled = true,
        clickable = clickable,
        classHint = "",
        state = com.wae.core.model.ControlState(),
        source = channel
    )

    @Test
    fun `labelled controls are not OCRed`() {
        val regions = OcrRegionPlanner.plan(
            listOf(control("Wi-Fi", 100, 100)),
            screenWidth = 1080, screenHeight = 2000
        )
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `unlabelled clickable control produces one region around it`() {
        val regions = OcrRegionPlanner.plan(
            listOf(control("", 500, 1000, width = 80, height = 80)),
            screenWidth = 1080, screenHeight = 2000,
            expandPx = 48, minArea = 100
        )
        assertEquals(1, regions.size)
        // 擴張後應覆蓋原控制項
        val r = regions[0]
        assertTrue(r.left <= 500 && r.top <= 1000 && r.right >= 580 && r.bottom >= 1080)
    }

    @Test
    fun `non clickable control is ignored`() {
        val regions = OcrRegionPlanner.plan(
            listOf(control("", 500, 1000, clickable = false)),
            screenWidth = 1080, screenHeight = 2000, minArea = 100
        )
        assertTrue(regions.isEmpty())
    }

    @Test
    fun `overlapping regions are merged so the same area is not OCRed twice`() {
        val a = control("", 500, 1000, width = 60, height = 60)
        val b = control("", 520, 1020, width = 60, height = 60)
        val regions = OcrRegionPlanner.plan(
            listOf(a, b), screenWidth = 1080, screenHeight = 2000, expandPx = 20, minArea = 100
        )
        assertEquals(1, regions.size)
    }

    @Test
    fun `popup bounds are always included`() {
        val popup = OcrRegionPlanner.Region(100, 200, 900, 1200)
        val regions = OcrRegionPlanner.plan(
            emptyList(), popupBounds = listOf(popup), screenWidth = 1080, screenHeight = 2000
        )
        assertEquals(1, regions.size)
        assertEquals(popup, regions[0])
    }

    @Test
    fun `regions are clamped to the screen`() {
        val regions = OcrRegionPlanner.plan(
            listOf(control("", 1040, 1960, width = 100, height = 100)),
            screenWidth = 1080, screenHeight = 2000, expandPx = 50, minArea = 100
        )
        assertEquals(1, regions.size)
        val r = regions[0]
        assertTrue(r.right <= 1080 && r.bottom <= 2000)
    }

    @Test
    fun `tiny regions are dropped and count is capped`() {
        val many = (0 until 10).map { i -> control("", 100 + i * 200, 100, width = 10, height = 10) }
        val regions = OcrRegionPlanner.plan(
            many, screenWidth = 3000, screenHeight = 2000, expandPx = 5, minArea = 5000, maxRegions = 3
        )
        assertTrue(regions.size <= 3)
        assertTrue(regions.all { it.area >= 5000 })
    }
}
