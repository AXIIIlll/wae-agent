package com.example.newandroidapp.action

import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.e2e.TestHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureExecutorTest {

    private fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect(left, top, right, bottom).apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    @Test
    fun testCalculateSliderTargetCoordinate_horizontal_endpoints() {
        val bounds = createRect(100, 200, 500, 260) // W=400, H=60
        val cy = (bounds.top + bounds.bottom) / 2
        val p0 = GestureExecutor.calculateSliderTargetCoordinate(bounds, 0.0f, isHorizontal = true)
        val p100 = GestureExecutor.calculateSliderTargetCoordinate(bounds, 1.0f, isHorizontal = true)
        val p50 = GestureExecutor.calculateSliderTargetCoordinate(bounds, 0.5f, isHorizontal = true)

        assertEquals(cy, p0.y)
        assertEquals(cy, p100.y)
        assertEquals(cy, p50.y)

        assertTrue("p0.x should be near bounds.left", p0.x > bounds.left && p0.x < p50.x)
        assertTrue("p100.x should be near bounds.right", p100.x < bounds.right && p100.x > p50.x)
        assertTrue("p50.x should be between p0 and p100", p50.x > p0.x && p50.x < p100.x)
    }

    @Test
    fun testCalculateSliderTargetCoordinate_clamping() {
        val bounds = createRect(50, 100, 450, 150)
        val pNegative = GestureExecutor.calculateSliderTargetCoordinate(bounds, -0.8f, isHorizontal = true)
        val pZero = GestureExecutor.calculateSliderTargetCoordinate(bounds, 0.0f, isHorizontal = true)
        val pOverflow = GestureExecutor.calculateSliderTargetCoordinate(bounds, 1.8f, isHorizontal = true)
        val pOne = GestureExecutor.calculateSliderTargetCoordinate(bounds, 1.0f, isHorizontal = true)

        assertEquals(pZero.x, pNegative.x)
        assertEquals(pOne.x, pOverflow.x)
    }

    @Test
    fun testCalculateSliderTargetCoordinate_vertical() {
        val bounds = createRect(200, 100, 260, 500) // W=60, H=400
        val cx = (bounds.left + bounds.right) / 2
        val p0 = GestureExecutor.calculateSliderTargetCoordinate(bounds, 0.0f, isHorizontal = false)
        val p100 = GestureExecutor.calculateSliderTargetCoordinate(bounds, 1.0f, isHorizontal = false)

        assertEquals(cx, p0.x)
        assertEquals(cx, p100.x)

        // On vertical sliders, 0% is at bottom, 100% is at top
        assertTrue("p0.y should be near bottom", p0.y > bounds.top && p0.y < bounds.bottom)
        assertTrue("p100.y should be near top", p100.y > bounds.top && p100.y < p0.y)
    }

    @Test
    fun testCalculatePickerFlickCoordinates_incrementFlickUp() {
        val bounds = createRect(100, 200, 300, 600) // H=400, cy=400
        val cx = (bounds.left + bounds.right) / 2
        val (start, end) = GestureExecutor.calculatePickerFlickCoordinates(bounds, increment = true)

        assertEquals(cx, start.x)
        assertEquals(cx, end.x)
        assertTrue("Nudge up must start lower than it ends", start.y > end.y)
        assertTrue("Start Y must be within picker bounds", start.y > bounds.top && start.y < bounds.bottom)
        assertTrue("End Y must be within picker bounds", end.y > bounds.top && end.y < bounds.bottom)
    }

    @Test
    fun testCalculatePickerFlickCoordinates_decrementFlickDown() {
        val bounds = createRect(100, 200, 300, 600) // H=400, cy=400
        val cx = (bounds.left + bounds.right) / 2
        val (start, end) = GestureExecutor.calculatePickerFlickCoordinates(bounds, increment = false)

        assertEquals(cx, start.x)
        assertEquals(cx, end.x)
        assertTrue("Nudge down must start higher than it ends", start.y < end.y)
        assertTrue("Start Y must be within picker bounds", start.y > bounds.top && start.y < bounds.bottom)
        assertTrue("End Y must be within picker bounds", end.y > bounds.top && end.y < bounds.bottom)
    }

    @Test
    fun testCalculateSwipeEndpoints_leftAndRight() {
        val sw = 1080
        val sh = 2400

        val (leftStart, leftEnd) = GestureExecutor.calculateSwipeEndpoints(sw, sh, isLeft = true)
        val (rightStart, rightEnd) = GestureExecutor.calculateSwipeEndpoints(sw, sh, isLeft = false)

        // Swipe left moves right to left (80% -> 20%)
        assertTrue("Swipe left startX should be > endX", leftStart.x > leftEnd.x)
        assertEquals((sw * 0.80f).toInt(), leftStart.x)
        assertEquals((sw * 0.20f).toInt(), leftEnd.x)
        assertEquals((sh * 0.50f).toInt(), leftStart.y)
        assertEquals(leftStart.y, leftEnd.y)

        // Swipe right moves left to right (20% -> 80%)
        assertTrue("Swipe right startX should be < endX", rightStart.x < rightEnd.x)
        assertEquals((sw * 0.20f).toInt(), rightStart.x)
        assertEquals((sw * 0.80f).toInt(), rightEnd.x)
        assertEquals((sh * 0.50f).toInt(), rightStart.y)
        assertEquals(rightStart.y, rightEnd.y)
    }

    @Test
    fun testCalculateSwipeEndpoints_customY() {
        val sw = 1080
        val sh = 2400
        val customY = 850f

        val (start, end) = GestureExecutor.calculateSwipeEndpoints(sw, sh, isLeft = true, customY = customY)
        assertEquals(850, start.y)
        assertEquals(850, end.y)
    }

    @Test
    fun testConstantsAndTimingConfigurations() {
        assertEquals(38L, GestureExecutor.DEFAULT_TAP_DURATION_MS)
        assertEquals(100L, GestureExecutor.DEFAULT_DOUBLE_TAP_INTERVAL_MS)
        assertEquals(600L, GestureExecutor.DEFAULT_LONG_PRESS_DURATION_MS)
        assertEquals(200L, GestureExecutor.DEFAULT_SWIPE_DURATION_MS)
        assertEquals(140L, GestureExecutor.DEFAULT_PICKER_FLICK_DURATION_MS)
        assertEquals(240L, GestureExecutor.DEFAULT_SLIDER_DRAG_DURATION_MS)
    }

    @Test
    fun testZeroEmojiCompliance() {
        TestHelpers.assertZeroEmojis("GestureExecutor", "class name")
        assertFalse(TestHelpers.containsEmoji(GestureExecutor::class.java.simpleName))
    }
}
