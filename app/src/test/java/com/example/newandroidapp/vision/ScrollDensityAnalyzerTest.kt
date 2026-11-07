package com.example.newandroidapp.vision

import android.graphics.Point
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ScrollDensityAnalyzer verifying adaptive scroll stride calculation,
 * density detection (dense switches/settings vs sparse lists), intent overrides, and gesture parameters.
 */
class ScrollDensityAnalyzerTest {

    private fun testRect(l: Int, t: Int, r: Int, b: Int): Rect = Rect(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun testPoint(x: Int, y: Int): Point = Point(x, y).apply {
        this.x = x
        this.y = y
    }

    private fun createConvergedNode(
        id: String,
        label: String,
        bounds: Rect,
        shortTypeTag: String = "TXT"
    ): Stage2ConvergedNode {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        return Stage2ConvergedNode(
            nodeId = id,
            shortMarkId = 1,
            className = "android.widget.TextView",
            shortTypeTag = shortTypeTag,
            label = label,
            inlineStatePrefix = "",
            rawOriginalBounds = bounds,
            convergedBounds = bounds,
            finalTapPx = testPoint(cx, cy),
            gridTapPoint = testPoint(500, 500),
            visibleRatio = 1.0f,
            wasHotspotConverged = false,
            absorbedTextIds = emptyList(),
            sourceType = NodeSourceType.UI_TREE
        )
    }

    private fun createRawScrollContainer(bounds: Rect): RawVisionNode {
        return RawVisionNode(
            id = "scroll_container",
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = bounds,
            isScrollable = true,
            scrollDirections = "V"
        )
    }

    @Test
    fun testDenseSwitchList_yieldsFineDenseMode() {
        val screenHeight = 2400
        val switchNodes = listOf(
            createConvergedNode("s1", "深色模式", testRect(40, 300, 1000, 420), shortTypeTag = "CHK"),
            createConvergedNode("s2", "自動更新", testRect(40, 450, 1000, 570), shortTypeTag = "CHK"),
            createConvergedNode("s3", "推送通知", testRect(40, 600, 1000, 720), shortTypeTag = "CHK")
        )

        val (mode, stride) = ScrollDensityAnalyzer.determineScrollStride(switchNodes, screenHeight)
        assertEquals(ScrollStrideMode.FINE_DENSE, mode)
        assertEquals(0.28f, stride, 0.01f)
    }

    @Test
    fun testDenseSmallItemHeights_yieldsFineDenseMode() {
        val screenHeight = 2400
        val denseRows = listOf(
            createConvergedNode("r1", "一般設定", testRect(40, 200, 1000, 320)), // height = 120
            createConvergedNode("r2", "聲音與震動", testRect(40, 330, 1000, 450)), // height = 120
            createConvergedNode("r3", "顯示與亮度", testRect(40, 460, 1000, 580)), // height = 120
            createConvergedNode("r4", "電池電量", testRect(40, 590, 1000, 710)) // height = 120
        )

        val (mode, stride) = ScrollDensityAnalyzer.determineScrollStride(denseRows, screenHeight)
        assertEquals(ScrollStrideMode.FINE_DENSE, mode)
        assertEquals(0.28f, stride, 0.01f)
    }

    @Test
    fun testSparseLongList_yieldsLargeFastMode() {
        val screenHeight = 2400
        val feedCards = listOf(
            createConvergedNode("f1", "iPhone 16 Pro 鈦金屬", testRect(40, 200, 1000, 700)), // height = 500
            createConvergedNode("f2", "Galaxy S24 Ultra", testRect(40, 750, 1000, 1250)), // height = 500
            createConvergedNode("f3", "Pixel 9 Pro XL", testRect(40, 1300, 1000, 1800)) // height = 500
        )

        val (mode, stride) = ScrollDensityAnalyzer.determineScrollStride(feedCards, screenHeight)
        assertEquals(ScrollStrideMode.LARGE_FAST, mode)
        assertEquals(0.68f, stride, 0.01f)
    }

    @Test
    fun testIntentCategoryOverride_forcesMode() {
        val screenHeight = 2400
        val feedCards = listOf(
            createConvergedNode("f1", "Card 1", testRect(40, 200, 1000, 700)),
            createConvergedNode("f2", "Card 2", testRect(40, 750, 1000, 1250))
        )

        // Even though cards are large, TOGGLE_SWITCH intent forces FINE_DENSE
        val switchParams = ScrollDensityAnalyzer.calculateScrollParameters(
            rawNodes = emptyList(),
            convergedNodes = feedCards,
            down = true,
            screenWidth = 1080,
            screenHeight = screenHeight,
            intentCategory = "TOGGLE_SWITCH"
        )
        assertEquals(ScrollStrideMode.FINE_DENSE, switchParams.mode)
        assertEquals(320L, switchParams.durationMs)

        // Dense list forced to LARGE_FAST by SCROLL_SEARCH intent
        val denseRows = listOf(
            createConvergedNode("s1", "Switch 1", testRect(40, 300, 1000, 420), shortTypeTag = "CHK"),
            createConvergedNode("s2", "Switch 2", testRect(40, 450, 1000, 570), shortTypeTag = "CHK")
        )
        val searchParams = ScrollDensityAnalyzer.calculateScrollParameters(
            rawNodes = emptyList(),
            convergedNodes = denseRows,
            down = true,
            screenWidth = 1080,
            screenHeight = screenHeight,
            intentCategory = "SCROLL_SEARCH"
        )
        assertEquals(ScrollStrideMode.LARGE_FAST, searchParams.mode)
        assertEquals(180L, searchParams.durationMs)
    }

    @Test
    fun testCalculateScrollParameters_downVsUpDirection() {
        val screenHeight = 2400
        val screenWidth = 1080

        // Down = true (swipe finger from bottom up to see content below)
        val downParams = ScrollDensityAnalyzer.calculateScrollParameters(
            rawNodes = emptyList(),
            convergedNodes = emptyList(),
            down = true,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertTrue("Down scroll startY should be greater than endY", downParams.startYFraction > downParams.endYFraction)
        assertTrue("startYFraction must be within safe screen bounds", downParams.startYFraction in 0.18f..0.82f)
        assertTrue("endYFraction must be within safe screen bounds", downParams.endYFraction in 0.18f..0.82f)

        // Down = false (swipe finger from top down to see content above)
        val upParams = ScrollDensityAnalyzer.calculateScrollParameters(
            rawNodes = emptyList(),
            convergedNodes = emptyList(),
            down = false,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertTrue("Up scroll startY should be less than endY", upParams.startYFraction < upParams.endYFraction)
        assertTrue("startYFraction must be within safe screen bounds", upParams.startYFraction in 0.18f..0.82f)
        assertTrue("endYFraction must be within safe screen bounds", upParams.endYFraction in 0.18f..0.82f)
    }

    @Test
    fun testContainerBoundsRespected() {
        val containerRect = testRect(50, 150, 1030, 2200)
        val rawNodes = listOf(createRawScrollContainer(containerRect))

        val params = ScrollDensityAnalyzer.calculateScrollParameters(
            rawNodes = rawNodes,
            convergedNodes = emptyList(),
            down = true,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertSame(containerRect, params.containerBounds)
        assertEquals(containerRect.left, params.containerBounds?.left)
        assertEquals(containerRect.top, params.containerBounds?.top)
        assertEquals(containerRect.right, params.containerBounds?.right)
        assertEquals(containerRect.bottom, params.containerBounds?.bottom)
    }
}
