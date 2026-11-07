package com.example.newandroidapp.vision

import android.graphics.Point
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SmartLoadingDetector verifying detection of ProgressBar,
 * spinners, skeleton screens, loading keywords, and exclusion of SeekBars.
 */
class SmartLoadingDetectorTest {

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

    private fun createRawNode(
        id: String = "node_1",
        className: String = "android.view.View",
        bounds: Rect = testRect(100, 200, 300, 400),
        text: String = "",
        contentDescription: String = "",
        viewIdResourceName: String = "",
        isClickable: Boolean = false
    ): RawVisionNode {
        return RawVisionNode(
            id = id,
            windowId = 1,
            windowLayer = 0,
            zOrderRank = 0,
            className = className,
            bounds = bounds,
            text = text,
            contentDescription = contentDescription,
            viewIdResourceName = viewIdResourceName,
            isClickable = isClickable
        )
    }

    private fun createConvergedNode(
        label: String,
        bounds: Rect = testRect(100, 200, 300, 400),
        shortTypeTag: String = "TXT"
    ): Stage2ConvergedNode {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        return Stage2ConvergedNode(
            nodeId = "conv_1",
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

    @Test
    fun testProgressBarClass_detectedAsLoading() {
        val rawNodes = listOf(
            createRawNode(
                className = "android.widget.ProgressBar",
                bounds = testRect(100, 100, 200, 200)
            )
        )
        val result = SmartLoadingDetector.detectLoading(rawNodes, emptyList(), 2400)
        assertTrue("ProgressBar class must trigger isLoading = true", result.isLoading)
        assertEquals("PROGRESS_BAR", result.indicatorType)
    }

    @Test
    fun testMaterialProgressIndicators_detectedAsLoading() {
        val circular = listOf(
            createRawNode(
                className = "com.google.android.material.progressindicator.CircularProgressIndicator",
                bounds = testRect(200, 300, 350, 450)
            )
        )
        val circularRes = SmartLoadingDetector.detectLoading(circular, emptyList(), 2400)
        assertTrue("CircularProgressIndicator must trigger isLoading", circularRes.isLoading)

        val linear = listOf(
            createRawNode(
                className = "com.google.android.material.progressindicator.LinearProgressIndicator",
                bounds = testRect(50, 100, 600, 150)
            )
        )
        val linearRes = SmartLoadingDetector.detectLoading(linear, emptyList(), 2400)
        assertTrue("LinearProgressIndicator must trigger isLoading", linearRes.isLoading)
    }

    @Test
    fun testSpinKitAndSpinnerClasses_detectedAsSpinner() {
        val spinnerNodes = listOf(
            createRawNode(
                className = "com.github.ybq.android.spinkit.SpinKitView",
                bounds = testRect(150, 250, 250, 350)
            )
        )
        val result = SmartLoadingDetector.detectLoading(spinnerNodes, emptyList(), 2400)
        assertTrue("SpinKitView must be detected", result.isLoading)
        assertEquals("SPINNER", result.indicatorType)
    }

    @Test
    fun testShimmerClass_detectedAsShimmer() {
        val shimmerNodes = listOf(
            createRawNode(
                className = "com.facebook.shimmer.ShimmerFrameLayout",
                bounds = testRect(0, 100, 800, 600)
            )
        )
        val result = SmartLoadingDetector.detectLoading(shimmerNodes, emptyList(), 2400)
        assertTrue("ShimmerFrameLayout must be detected as loading", result.isLoading)
        assertEquals("SHIMMER", result.indicatorType)
    }

    @Test
    fun testResourceIdKeywords_detectedAsLoading() {
        val idNodes = listOf(
            createRawNode(
                className = "android.view.View",
                viewIdResourceName = "com.example.app:id/loading_spinner_view",
                bounds = testRect(100, 100, 200, 200)
            )
        )
        val result = SmartLoadingDetector.detectLoading(idNodes, emptyList(), 2400)
        assertTrue("Resource id containing loading/spinner must be detected", result.isLoading)
    }

    @Test
    fun testSeekBarAndRatingBarExclusion_notFlagged() {
        val seekBarNode = listOf(
            createRawNode(
                className = "android.widget.SeekBar",
                viewIdResourceName = "com.example.app:id/volume_seekbar",
                bounds = testRect(50, 200, 700, 280)
            )
        )
        val seekResult = SmartLoadingDetector.detectLoading(seekBarNode, emptyList(), 2400)
        assertFalse("SeekBar must never be classified as loading indicator", seekResult.isLoading)

        val ratingBarNode = listOf(
            createRawNode(
                className = "android.widget.RatingBar",
                viewIdResourceName = "com.example.app:id/star_rating",
                bounds = testRect(100, 300, 400, 380)
            )
        )
        val ratingResult = SmartLoadingDetector.detectLoading(ratingBarNode, emptyList(), 2400)
        assertFalse("RatingBar must never be classified as loading indicator", ratingResult.isLoading)
    }

    @Test
    fun testLoadingTextInRawNode_detectedAsTextLoading() {
        val loadingTextPhrases = listOf(
            "載入中",
            "正在載入...",
            "正在加載",
            "加載中",
            "請稍候...",
            "請稍後",
            "轉圈圈",
            "Loading please wait",
            "Refreshing feed"
        )
        for (phrase in loadingTextPhrases) {
            val nodes = listOf(createRawNode(text = phrase))
            val result = SmartLoadingDetector.detectLoading(nodes, emptyList(), 2400)
            assertTrue("Phrase '$phrase' should trigger isLoading", result.isLoading)
        }
    }

    @Test
    fun testLoadingTextInConvergedNode_detected() {
        val converged = listOf(createConvergedNode(label = "載入中，請稍候"))
        val result = SmartLoadingDetector.detectLoading(emptyList(), converged, 2400)
        assertTrue("Loading text in converged node should be detected", result.isLoading)
        assertEquals("TEXT_LOADING", result.indicatorType)
    }

    @Test
    fun testStructuralSkeletonDetection_uniformEmptyContainers() {
        val skeletonContainers = listOf(
            createRawNode(
                id = "skel_1",
                className = "android.widget.FrameLayout",
                bounds = testRect(40, 200, 760, 340), // height = 140
                isClickable = false
            ),
            createRawNode(
                id = "skel_2",
                className = "android.widget.FrameLayout",
                bounds = testRect(40, 360, 760, 500), // height = 140
                isClickable = false
            ),
            createRawNode(
                id = "skel_3",
                className = "android.widget.FrameLayout",
                bounds = testRect(40, 520, 760, 660), // height = 140
                isClickable = false
            )
        )
        val result = SmartLoadingDetector.detectLoading(skeletonContainers, emptyList(), 2400)
        assertTrue("Uniform empty containers should trigger skeleton shimmer", result.isLoading)
        assertEquals("SHIMMER", result.indicatorType)
    }

    @Test
    fun testNormalContent_notDetectedAsLoading() {
        val normalRaw = listOf(
            createRawNode(
                className = "android.widget.Button",
                text = "確認下單",
                isClickable = true
            ),
            createRawNode(
                className = "android.widget.TextView",
                text = "商品總計 NT$ 1,200"
            )
        )
        val normalConverged = listOf(
            createConvergedNode(label = "確認下單", shortTypeTag = "BTN"),
            createConvergedNode(label = "商品總計 NT$ 1,200", shortTypeTag = "TXT")
        )
        val result = SmartLoadingDetector.detectLoading(normalRaw, normalConverged, 2400)
        assertFalse("Normal content must not be detected as loading", result.isLoading)
        assertEquals("NONE", result.indicatorType)
    }
}
