package com.example.newandroidapp.stability

import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 100% 原創自研：智慧載入狀態與骨架屏偵測器 (SmartLoadingDetector)
 *
 * 供「跨 App 連續任務嚴格驗收閘門 (Strict Sub-task Gate)」與「閉環驗證 (ClosedLoopVerifier)」呼叫，
 * 負責偵測當前畫面是否仍處於載入動畫、轉圈圈 (ProgressBar / CircularProgressIndicator)、
 * 下拉重新整理 (SwipeRefreshLayout) 或 Shimmer 骨架屏狀態。
 *
 * 硬性規則：
 * - 當 `isScreenCurrentlyLoading(report) == true` 時，下一個跨 App 子任務或純擷取動作嚴禁搶跑解鎖！
 */
object SmartLoadingDetector {

    private val LOADING_CLASS_KEYWORDS = listOf(
        "progressbar",
        "progressindicator",
        "circularprogress",
        "linearprogress",
        "swiperefresh",
        "shimmer",
        "skeleton",
        "loadingview",
        "lottieanimation"
    )

    private val LOADING_RESOURCE_ID_KEYWORDS = listOf(
        "progress_bar",
        "progressbar",
        "loading_spinner",
        "loading_view",
        "swipe_refresh",
        "shimmer_layout",
        "skeleton_container"
    )

    private val LOADING_TEXT_PATTERNS = listOf(
        "載入中",
        "載入中...",
        "正在載入",
        "讀取中",
        "搜尋中",
        "搜索中",
        "查詢中",
        "處理中",
        "請稍候",
        "loading",
        "loading...",
        "searching...",
        "please wait",
        "buffering"
    )

    data class LoadingCheckVerdict(
        val isLoading: Boolean,
        val reason: String,
        val matchedLoadingNodeLabel: String? = null
    )

    fun evaluateLoadingState(report: VisionPipelineReport): LoadingCheckVerdict {
        if (report.finalNodes.isEmpty()) {
            return LoadingCheckVerdict(
                isLoading = true,
                reason = "畫面無可用節點（疑似空白過渡幀或載入中）"
            )
        }

        val nonChromeContentNodes = report.finalNodes.filter { node ->
            node.shortTypeTag != "INP" &&
                node.gridTapPoint.y in 180..860 &&
                !isLoadingIndicatorNode(node)
        }

        for (node in report.finalNodes) {
            if (isLoadingIndicatorNode(node)) {
                // 若畫面上已有充足的實質內容結果卡片 (>= 4 個)，局部小進度條不視為全頁未載入完畢
                if (nonChromeContentNodes.size >= 4 && !isExplicitBlockingLoadingText(node.label)) {
                    continue
                }
                return LoadingCheckVerdict(
                    isLoading = true,
                    reason = "偵測到載入動畫或進度元件 #${node.shortMarkId}「${node.label}」(${node.className.substringAfterLast('.')})",
                    matchedLoadingNodeLabel = node.label
                )
            }
        }

        // 檢查 Stage1 原始節點是否含有未收斂之全屏 ProgressBar（且內容區節點極少）
        if (nonChromeContentNodes.size <= 1) {
            val rawSpinner = report.stage1AllNodes.firstOrNull { s1 ->
                val cls = s1.raw.className.lowercase()
                val resId = s1.raw.viewIdResourceName.lowercase()
                LOADING_CLASS_KEYWORDS.any { cls.contains(it) } ||
                    LOADING_RESOURCE_ID_KEYWORDS.any { resId.contains(it) }
            }
            if (rawSpinner != null) {
                return LoadingCheckVerdict(
                    isLoading = true,
                    reason = "偵測到原始視圖樹進度條元件 (${rawSpinner.raw.className.substringAfterLast('.')}) 且結果區尚未渲染"
                )
            }
        }

        return LoadingCheckVerdict(
            isLoading = false,
            reason = "畫面載入動畫已結束，內容區穩定"
        )
    }

    fun isScreenCurrentlyLoading(report: VisionPipelineReport): Boolean {
        return evaluateLoadingState(report).isLoading
    }

    fun isLoadingIndicatorNode(node: Stage2ConvergedNode): Boolean {
        val cls = node.className.lowercase()
        val resId = node.nodeId.lowercase()
        if (LOADING_CLASS_KEYWORDS.any { cls.contains(it) }) return true
        if (LOADING_RESOURCE_ID_KEYWORDS.any { resId.contains(it) }) return true
        return isExplicitBlockingLoadingText(node.label)
    }

    private fun isExplicitBlockingLoadingText(rawLabel: String): Boolean {
        val clean = rawLabel.trim().lowercase()
        if (clean.isEmpty() || clean.length > 24) return false
        return LOADING_TEXT_PATTERNS.any { pat ->
            clean == pat || clean.startsWith("$pat ") || clean.endsWith(" $pat")
        }
    }
}
