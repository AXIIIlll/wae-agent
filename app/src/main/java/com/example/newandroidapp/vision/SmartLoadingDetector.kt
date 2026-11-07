package com.example.newandroidapp.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 智慧載入狀態檢測器 (Smart Loading Detector)
 *
 * 檢測畫面是否處於載入或過渡狀態：
 * 1. 原生/Material 元件類別：ProgressBar, CircularProgressIndicator, LinearProgressIndicator, Shimmer, Skeleton
 * 2. 視圖 ID 關鍵字：progress, loading, spinner, skeleton, shimmer, load_more
 * 3. 載入文字與描述：載入中, loading, 請稍候, 轉圈圈, 正在載入, 正在加載, 加載中, 請稍後, please wait, refreshing, buffering
 * 4. 骨架屏結構偵測：多個同高度無文字空容器
 * 5. 安全排除：SeekBar 與 RatingBar 嚴格排除，不誤判為載入
 */
data class LoadingDetectionResult(
    val isLoading: Boolean,
    val indicatorType: String = "NONE", // "PROGRESS_BAR", "SPINNER", "SHIMMER", "TEXT_LOADING", "NONE"
    val confidence: Float = 0f,
    val recommendedWaitMs: Long = 600L,
    val indicatorDetails: String = "",
    val indicatorBounds: Rect? = null
)

object SmartLoadingDetector {

    const val MIN_WAIT_MS = 400L
    const val MAX_WAIT_TIMEOUT_MS = 1500L
    const val POLL_INTERVAL_MS = 120L

    private val FORBIDDEN_CLASSES = listOf(
        "seekbar",
        "ratingbar"
    )

    private val FORBIDDEN_IDS = listOf(
        "seekbar",
        "ratingbar",
        "player_progress",
        "music_progress",
        "video_progress"
    )

    private val LOADING_CLASS_KEYWORDS = listOf(
        "progressbar",
        "progressindicator",
        "circularprogress",
        "linearprogress",
        "loadingview",
        "loadingindicator",
        "spinkit",
        "spinview",
        "refreshview",
        "shimmer",
        "skeleton",
        "contentloadingprogressbar"
    )

    private val LOADING_ID_KEYWORDS = listOf(
        "progress",
        "loading",
        "spinner",
        "skeleton",
        "shimmer",
        "load_more",
        "circular_progress",
        "refresh_progress",
        "refreshing",
        "wait_view"
    )

    private val LOADING_TEXT_REGEX = Regex(
        "(載入中|正在載入|正在加載|加載中|請稍候|請稍後|轉圈圈|loading|please wait|refreshing|buffering)",
        RegexOption.IGNORE_CASE
    )

    /**
     * 針對 RawVisionNode 與 Stage2ConvergedNode 進行載入檢測
     */
    fun detectLoading(
        rawNodes: List<RawVisionNode>,
        convergedNodes: List<Stage2ConvergedNode>,
        screenHeight: Int
    ): LoadingDetectionResult {
        // 1. 檢查 RawVisionNode 之 className 與 viewIdResourceName
        for (raw in rawNodes) {
            val cls = raw.className.lowercase()
            if (FORBIDDEN_CLASSES.any { cls.contains(it) }) continue

            val resId = raw.viewIdResourceName.lowercase()
            if (FORBIDDEN_IDS.any { resId.contains(it) }) continue

            val isProgressClass = LOADING_CLASS_KEYWORDS.any { cls.contains(it) }
            val isProgressId = LOADING_ID_KEYWORDS.any { resId.contains(it) }

            if (isProgressClass || isProgressId) {
                val w = raw.bounds.right - raw.bounds.left
                val h = raw.bounds.bottom - raw.bounds.top
                if (w >= 16 && h >= 16) {
                    val type = when {
                        cls.contains("shimmer") || cls.contains("skeleton") || resId.contains("skeleton") || resId.contains("shimmer") -> "SHIMMER"
                        cls.contains("spin") || resId.contains("spin") -> "SPINNER"
                        else -> "PROGRESS_BAR"
                    }
                    return LoadingDetectionResult(
                        isLoading = true,
                        indicatorType = type,
                        confidence = 0.95f,
                        recommendedWaitMs = 600L,
                        indicatorDetails = "Class: ${raw.className}, Id: ${raw.viewIdResourceName}",
                        indicatorBounds = raw.bounds
                    )
                }
            }

            val desc = raw.contentDescription.trim()
            if (desc.isNotEmpty() && LOADING_TEXT_REGEX.containsMatchIn(desc)) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = if (desc.contains("轉圈圈") || desc.contains("spinner")) "SPINNER" else "TEXT_LOADING",
                    confidence = 0.92f,
                    recommendedWaitMs = 600L,
                    indicatorDetails = "Description: $desc",
                    indicatorBounds = raw.bounds
                )
            }

            val text = raw.text.trim()
            if (text.isNotEmpty() && LOADING_TEXT_REGEX.containsMatchIn(text)) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = if (text.contains("轉圈圈")) "SPINNER" else "TEXT_LOADING",
                    confidence = 0.92f,
                    recommendedWaitMs = 600L,
                    indicatorDetails = "Text: $text",
                    indicatorBounds = raw.bounds
                )
            }
        }

        // 2. 檢查 Stage2ConvergedNode 標籤
        for (node in convergedNodes) {
            if (LOADING_TEXT_REGEX.containsMatchIn(node.label)) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = if (node.label.contains("轉圈圈")) "SPINNER" else "TEXT_LOADING",
                    confidence = 0.90f,
                    recommendedWaitMs = 600L,
                    indicatorDetails = "Label: ${node.label}",
                    indicatorBounds = node.convergedBounds
                )
            }
        }

        // 3. 骨架屏結構偵測：3 個或以上同高度無文字空容器
        val skeleton = detectStructuralSkeleton(rawNodes, screenHeight)
        if (skeleton != null) {
            return skeleton
        }

        return LoadingDetectionResult(
            isLoading = false,
            indicatorType = "NONE",
            confidence = 0f,
            recommendedWaitMs = 0L,
            indicatorDetails = "No loading indicators detected"
        )
    }

    /**
     * 支援 VisionPipelineReport、rawWindows 與 rootNode 的多來源檢測介面
     */
    fun detectLoading(
        report: VisionPipelineReport?,
        rawWindows: List<AccessibilityWindowInfo>?,
        rootNode: AccessibilityNodeInfo?
    ): LoadingDetectionResult {
        if (report != null) {
            val rawList = report.stage1AllNodes.map { it.raw }
            val res = detectLoading(rawList, report.finalNodes, report.screenHeight)
            if (res.isLoading) return res
        }

        // 若 report 未檢測到或為 null，直接掃描 rootNode 或 rawWindows 根節點
        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        rootNode?.let { candidateRoots.add(it) }
        rawWindows?.forEach { win ->
            runCatching { win.root }.getOrNull()?.let { candidateRoots.add(it) }
        }

        for (root in candidateRoots) {
            val nodeRes = detectLoadingFromNode(root)
            if (nodeRes.isLoading) return nodeRes
        }

        return LoadingDetectionResult(
            isLoading = false,
            indicatorType = "NONE",
            confidence = 0f,
            recommendedWaitMs = 0L,
            indicatorDetails = "No loading indicators detected in tree"
        )
    }

    /**
     * 直接深度遍歷 AccessibilityNodeInfo 節點樹檢查載入指示
     */
    fun detectLoadingFromNode(root: AccessibilityNodeInfo?): LoadingDetectionResult {
        if (root == null) {
            return LoadingDetectionResult(isLoading = false, indicatorType = "NONE")
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()
        var visited = 0

        while (queue.isNotEmpty() && visited < 180) {
            val node = queue.removeFirst()
            visited++
            val cls = node.className?.toString()?.lowercase().orEmpty()
            val resId = node.viewIdResourceName?.lowercase().orEmpty()

            if (!FORBIDDEN_CLASSES.any { cls.contains(it) } && !FORBIDDEN_IDS.any { resId.contains(it) }) {
                val isProgressClass = LOADING_CLASS_KEYWORDS.any { cls.contains(it) }
                val isProgressId = LOADING_ID_KEYWORDS.any { resId.contains(it) }

                if (isProgressClass || isProgressId) {
                    runCatching { node.getBoundsInScreen(rect) }
                    val nw = rect.right - rect.left
                    val nh = rect.bottom - rect.top
                    if (nw >= 16 && nh >= 16) {
                        val type = when {
                            cls.contains("shimmer") || cls.contains("skeleton") || resId.contains("skeleton") -> "SHIMMER"
                            cls.contains("spin") || resId.contains("spin") -> "SPINNER"
                            else -> "PROGRESS_BAR"
                        }
                        return LoadingDetectionResult(
                            isLoading = true,
                            indicatorType = type,
                            confidence = 0.95f,
                            recommendedWaitMs = 600L,
                            indicatorDetails = "NodeClass: $cls, Id: $resId",
                            indicatorBounds = Rect(rect)
                        )
                    }
                }

                val text = node.text?.toString().orEmpty().trim()
                if (text.isNotEmpty() && LOADING_TEXT_REGEX.containsMatchIn(text)) {
                    runCatching { node.getBoundsInScreen(rect) }
                    return LoadingDetectionResult(
                        isLoading = true,
                        indicatorType = if (text.contains("轉圈圈")) "SPINNER" else "TEXT_LOADING",
                        confidence = 0.92f,
                        recommendedWaitMs = 600L,
                        indicatorDetails = "NodeText: $text",
                        indicatorBounds = Rect(rect)
                    )
                }

                val desc = node.contentDescription?.toString().orEmpty().trim()
                if (desc.isNotEmpty() && LOADING_TEXT_REGEX.containsMatchIn(desc)) {
                    runCatching { node.getBoundsInScreen(rect) }
                    return LoadingDetectionResult(
                        isLoading = true,
                        indicatorType = if (desc.contains("轉圈圈") || desc.contains("spinner")) "SPINNER" else "TEXT_LOADING",
                        confidence = 0.92f,
                        recommendedWaitMs = 600L,
                        indicatorDetails = "NodeDesc: $desc",
                        indicatorBounds = Rect(rect)
                    )
                }
            }

            val count = runCatching { node.childCount }.getOrDefault(0).coerceIn(0, 80)
            for (i in 0 until count) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }

        return LoadingDetectionResult(isLoading = false, indicatorType = "NONE")
    }

    private fun detectStructuralSkeleton(
        rawNodes: List<RawVisionNode>,
        screenHeight: Int
    ): LoadingDetectionResult? {
        val emptyContainers = rawNodes.filter { raw ->
            val w = raw.bounds.right - raw.bounds.left
            val h = raw.bounds.bottom - raw.bounds.top
            raw.text.isBlank() &&
                raw.contentDescription.isBlank() &&
                h in 28..(screenHeight / 3) &&
                w >= 150 &&
                !raw.isClickable &&
                (raw.className.contains("Layout") || raw.className.contains("View") || raw.className.contains("Card"))
        }

        if (emptyContainers.size >= 3) {
            val heights = emptyContainers.map { it.bounds.bottom - it.bounds.top }
            val avgH = heights.average()
            val isUniform = heights.all { Math.abs(it - avgH) <= 16 }
            if (isUniform) {
                return LoadingDetectionResult(
                    isLoading = true,
                    indicatorType = "SHIMMER",
                    confidence = 0.88f,
                    recommendedWaitMs = 600L,
                    indicatorDetails = "Detected ${emptyContainers.size} uniform empty skeleton cards (avgH: ${avgH.toInt()}px)"
                )
            }
        }
        return null
    }
}
