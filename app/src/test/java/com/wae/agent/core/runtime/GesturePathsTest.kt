package com.wae.agent.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手勢幾何測試：確認六個基礎動作的座標方向正確、且跨解析度一致（用比例計算）。
 */
class GesturePathsTest {

    private val w = 1080
    private val h = 2340

    @Test
    fun `scroll down moves finger upward`() {
        val s = GesturePaths.scrollDown(w, h)
        assertTrue("下滾：起點在下、終點在上", s.startY > s.endY)
        assertEquals(w / 2f, s.startX, 0.01f)
        assertEquals(w / 2f, s.endX, 0.01f)
        assertTrue("應為垂直手勢", !s.isHorizontal())
    }

    @Test
    fun `scroll up moves finger downward`() {
        val s = GesturePaths.scrollUp(w, h)
        assertTrue("上滾：起點在上、終點在下", s.startY < s.endY)
        assertTrue(!s.isHorizontal())
    }

    @Test
    fun `horizontal swipes go opposite directions`() {
        val right = GesturePaths.swipeHorizontal(w, h, leftToRight = true)
        val left = GesturePaths.swipeHorizontal(w, h, leftToRight = false)
        assertTrue("右滑：x 增加", right.endX > right.startX)
        assertTrue("左滑：x 減少", left.endX < left.startX)
        assertTrue("兩者都應為水平手勢", right.isHorizontal() && left.isHorizontal())
        assertEquals(right.startY, right.endY, 0.01f)
    }

    @Test
    fun `long press is stationary and long enough`() {
        val s = GesturePaths.longPress(540, 1200)
        assertTrue("長按不得位移", GesturePaths.isStationary(s))
        assertTrue("長按時間應 ≥ 500ms", s.durationMs >= 500)
        assertEquals(540f, s.startX, 0.01f)
        assertEquals(1200f, s.startY, 0.01f)
    }

    @Test
    fun `scroll strokes are not stationary`() {
        assertFalse(GesturePaths.isStationary(GesturePaths.scrollDown(w, h)))
        assertFalse(GesturePaths.isStationary(GesturePaths.scrollUp(w, h)))
        assertFalse(GesturePaths.isStationary(GesturePaths.swipeHorizontal(w, h, true)))
    }

    @Test
    fun `geometry scales with screen size`() {
        val small = GesturePaths.scrollDown(720, 1280)
        val large = GesturePaths.scrollDown(1440, 2560)
        assertEquals("應以比例計算（兩倍解析度兩倍座標）", small.startY * 2f, large.startY, 1f)
        assertEquals(small.durationMs, large.durationMs)
    }
}
