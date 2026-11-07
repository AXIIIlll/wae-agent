package com.example.newandroidapp.vision

import android.graphics.Bitmap
import android.graphics.Point
import android.os.SystemClock
import com.example.newandroidapp.ai.DeterminismHardeningEngine
import com.example.newandroidapp.stability.HeadsUpNotificationDefenseGuard
import com.example.newandroidapp.stability.SystemBarBlockerGuard
import com.example.newandroidapp.vision.dataset.Phase1TreeImmunityConfig
import com.example.newandroidapp.vision.dataset.Phase2ButtonFunctionalityClassifier
import com.example.newandroidapp.vision.dataset.Phase3ScreenPostureClassifier

/**
 * 四階段 AI 視覺感知總管線 ＋ 第 6 點深度優化（AiVisionPipeline）
 *
 * 串聯：
 * Stage 1: Z-Order 逆向光柵化 ＋ 2D 遮罩佔用格網 ＋ 全螢幕透明層 vs. 半透明黑底像素鑑別（ScrimAndAlphaInspector）
 * Stage 2: 最小視覺外框收斂 ＋ 無字圖示空間拓樸吸附 ＋ 孤立無字純圖示 16x16 幾何拓樸分類（TinyIconClassifier）
 * Stage 3: 結構空洞偵測（>15% 螢幕面積黑盒）＋ 局部 ROI 視覺雙軌補盲（ML Kit OCR + Sobel 邊緣連通域 + dHash 快取）
 * Stage 4: 重複列表結構折疊 ＋ 狀態符號內嵌 ＋ 動態跑馬燈幀間去抖動（TemporalDebouncer）＋ 0~999 座標網格量化
 */
object AiVisionPipeline {

    private fun safeElapsedRealtime(): Long {
        return try {
            val t = SystemClock.elapsedRealtime()
            if (t > 0L) t else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    fun execute(
        rawNodes: List<RawVisionNode>,
        screenWidth: Int,
        screenHeight: Int,
        screenBitmap: Bitmap? = null,
        useNormalizedGrid: Boolean = true
    ): VisionPipelineReport {
        val startMs = safeElapsedRealtime()
        val grid = OccupancyGrid2D(screenWidth = screenWidth, screenHeight = screenHeight, cols = 120, rows = 240)

        // 【系統通知橫幅與突發彈窗防禦 (HeadsUpNotificationDefenseGuard) ＋ 確定性背景雜訊隔離】：
        // 在進入 Stage 1 遮罩光柵化前，先剔除位於螢幕頂部的 com.android.systemui、非目標 App 懸浮橫幅、
        // 狀態列/導覽列以及動態變化的時間/電量/網速文字節點，避免干擾遮罩與後續特徵建立。
        val headsUpFiltered = HeadsUpNotificationDefenseGuard.filterRawNodesAndRecordHeadsUpBanner(
            rawNodes = rawNodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        val topStatusH = SystemBarBlockerGuard.getStatusBarHeightPx(null, screenHeight)
        val botNavH = SystemBarBlockerGuard.getNavigationBarHeightPx(null, screenHeight)
        val navFloorPx = screenHeight - botNavH
        val foregroundPkg = DeterminismHardeningEngine.selectPrimaryForegroundPackage(headsUpFiltered)

        val cleanRawNodes = headsUpFiltered.filterNot { n ->
            val isFullScreenContainer = (n.bounds.bottom - n.bounds.top) > screenHeight * 0.4f
            !isFullScreenContainer && DeterminismHardeningEngine.shouldStripRawVisionNode(
                node = n,
                screenHeightPx = screenHeight,
                statusBarCeilingPx = topStatusH,
                navBarFloorPx = navFloorPx,
                foregroundAppPackage = foregroundPkg
            )
        }

        val nodeById = cleanRawNodes.associateBy { it.id }

        fun isAncestorOf(potentialAncestorId: String, nodeId: String): Boolean {
            if (potentialAncestorId == nodeId) return false
            var cur = nodeById[nodeId]?.parentId
            var depth = 0
            val visited = HashSet<String>(8)
            visited.add(nodeId)
            while (cur != null && depth < 50) {
                if (cur == potentialAncestorId) return true
                if (!visited.add(cur)) break
                cur = nodeById[cur]?.parentId
                depth++
            }
            return false
        }

        val screenArea = (screenWidth * screenHeight).toFloat().coerceAtLeast(1f)
        val maxUnitAreaRatio = Phase1TreeImmunityConfig.maxInteractiveUnitAreaRatio

        fun findInteractiveUnitRootId(nodeId: String): String? {
            var curId: String? = nodeId
            var bestInteractiveRootId: String? = null
            var depth = 0
            val visited = HashSet<String>(8)
            while (curId != null && depth < 50) {
                if (!visited.add(curId)) break
                val n = nodeById[curId] ?: break
                val w = (n.bounds.right - n.bounds.left).coerceAtLeast(0)
                val h = (n.bounds.bottom - n.bounds.top).coerceAtLeast(0)
                val areaRatio = (w.toFloat() * h.toFloat()) / screenArea
                if ((n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable) && areaRatio < maxUnitAreaRatio) {
                    bestInteractiveRootId = n.id
                }
                curId = n.parentId
                depth++
            }
            return bestInteractiveRootId
        }

        val unitRootByNodeId = HashMap<String, String?>(cleanRawNodes.size)
        for (n in cleanRawNodes) {
            unitRootByNodeId[n.id] = findInteractiveUnitRootId(n.id)
        }

        val sortedByZ = cleanRawNodes.sortedBy { it.zOrderRank }

        val stage1Results = ArrayList<Stage1OccludedNode>(sortedByZ.size)
        val pendingSubtreeMasks = ArrayList<Pair<RawVisionNode, Boolean>>()
        var transparentBypassCount = 0

        for (raw in sortedByZ) {
            val rawUnitRootId = unitRootByNodeId[raw.id]
            // 將與當前節點「不具備父子同源關係，且不屬於同一個互動單元群組 (Interactive Unit Root)」的已掃描高層節點正式寫入 2D Occupancy Grid
            val iter = pendingSubtreeMasks.iterator()
            while (iter.hasNext()) {
                val (higherNode, shouldMask) = iter.next()
                val higherUnitRootId = unitRootByNodeId[higherNode.id]
                val sameInteractiveUnit = higherUnitRootId != null &&
                    higherNode.windowId == raw.windowId &&
                    (raw.id == higherUnitRootId ||
                        higherUnitRootId == rawUnitRootId ||
                        isAncestorOf(potentialAncestorId = higherUnitRootId, nodeId = raw.id))
                if (!isAncestorOf(potentialAncestorId = raw.id, nodeId = higherNode.id) && !sameInteractiveUnit) {
                    if (shouldMask) {
                        grid.markRectOccupied(higherNode.bounds, layerMarker = (higherNode.windowLayer + 1).coerceAtLeast(1))
                    }
                    iter.remove()
                }
            }

            // 第 6 點優化 (Part A)：對大面積覆蓋層執行 16 點像素亮度與對比度抽樣，鑑別「透明穿透層」vs「模態半透明黑底」
            val alphaCheck = ScrimAndAlphaInspector.inspectLargeOverlay(
                raw = raw,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                screenBitmap = screenBitmap
            )
            if (alphaCheck.type == ScrimAndAlphaInspector.OverlayAlphaType.TRANSPARENT_PASSTHROUGH) {
                transparentBypassCount++
            }

            val evaluated = grid.evaluateNodeVisibilityAndSafePoint(raw, occlusionCullThreshold = 0.20f)
                .copy(overlayAlphaDesc = alphaCheck.type.desc)
            stage1Results.add(evaluated)

            if (alphaCheck.shouldWriteToOccupancyGrid) {
                pendingSubtreeMasks.add(Pair(raw, true))
            }
        }

        for ((rem, shouldMask) in pendingSubtreeMasks) {
            if (shouldMask) {
                grid.markRectOccupied(rem.bounds, layerMarker = (rem.windowLayer + 1).coerceAtLeast(1))
            }
        }

        val culledCount = stage1Results.count { it.isCulled }
        val shiftedCount = stage1Results.count { !it.isCulled && it.wasPartiallyOccluded }

        // 第二階段：最小視覺外框收斂 + 無字圖示空間拓樸關聯 + 第 6 點孤立純圖示 16x16 幾何拓樸分類
        val stage2Res = HotspotAndSpatialBinder.process(
            stage1Nodes = stage1Results,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenBitmap = screenBitmap
        )

        // 第三階段：偵測面積 > 15% 的黑盒空洞（Canvas / 遊戲 / WebView），並執行局部 ROI 雙軌視覺補盲
        val nextMarkId = (stage2Res.convergedNodes.maxOfOrNull { it.shortMarkId } ?: 0) + 1
        val roiVoids = RoiVisionFallbackEngine.detectAndFillBlackBoxVoids(
            stage1Nodes = stage1Results,
            existingStage2Nodes = stage2Res.convergedNodes,
            screenBitmap = screenBitmap,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            startingMarkId = nextMarkId
        )

        val roiRecoveredNodes = ArrayList(roiVoids.flatMap { it.recoveredNodes })
        if (screenBitmap != null && !screenBitmap.isRecycled && stage2Res.convergedNodes.size < 3 && roiRecoveredNodes.isEmpty()) {
            val fullScreenMarkStart = nextMarkId + roiRecoveredNodes.size
            val fullScreenVisualNodes = RoiVisionFallbackEngine.detectFullScreenVisualFallback(
                existingNodes = stage2Res.convergedNodes,
                screenBitmap = screenBitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                startingMarkId = fullScreenMarkStart
            )
            roiRecoveredNodes.addAll(fullScreenVisualNodes)
        }
        val combinedNodes = ArrayList<Stage2ConvergedNode>(stage2Res.convergedNodes.size + roiRecoveredNodes.size)
        combinedNodes.addAll(stage2Res.convergedNodes)
        combinedNodes.addAll(roiRecoveredNodes)

        // 【第 3 層防護：剔除任何落入頂部狀態列、底部導覽列、非目標包名或動態時間/電量文字之節點（含截圖 OCR / Sobel 邊緣偵測節點）】
        val safeFilteredNodes = combinedNodes.filterNot { node ->
            SystemBarBlockerGuard.isCoordinateInForbiddenSystemBarZone(null, node.finalTapPx.y, screenHeight) ||
                SystemBarBlockerGuard.isNodeInSystemBarZone(
                    bounds = node.convergedBounds,
                    packageName = node.packageName,
                    screenHeight = screenHeight,
                    statusBarHeightPx = topStatusH,
                    navBarHeightPx = botNavH
                ) ||
                DeterminismHardeningEngine.shouldStripConvergedNode(
                    node = node,
                    screenHeightPx = screenHeight,
                    statusBarCeilingPx = topStatusH,
                    navBarFloorPx = navFloorPx,
                    foregroundAppPackage = foregroundPkg
                )
        }

        // 第 6 點優化 (Part C)：動態跑馬燈與動畫幀間去抖動（Temporal Debouncing）
        val debounced = TemporalDebouncer.stabilizeFrame(
            nodes = safeFilteredNodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        // Phase 2: 離線全畫面按鈕功用分類與無標籤控制項語義補全，並強制執行 10px 空間網格固定排序與確定性編號
        val annotatedNodes = Phase2ButtonFunctionalityClassifier.annotateNodes(
            nodes = debounced.nodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        val allFinalNodes = DeterminismHardeningEngine.assignCanonicalMarkIds(annotatedNodes)
        // Phase 3: 離線全域畫面姿勢分類與預期狀態轉移推論
        val postureResult = Phase3ScreenPostureClassifier.classifyScreenPosture(
            nodes = allFinalNodes,
            rawNodes = cleanRawNodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        val markMap = LinkedHashMap<Int, Point>()
        for (n in allFinalNodes) {
            markMap[n.shortMarkId] = n.finalTapPx
        }

        // 第四階段：語義去重、重複結構折疊、狀態符號內嵌與 0~999 座標網格量化
        val compression = SemanticCompressor.compress(
            rawNodes = rawNodes,
            finalNodes = allFinalNodes,
            useNormalizedGrid = useNormalizedGrid,
            screenPostureTag = "${postureResult.posture.code}(${postureResult.posture.displayNameZh})"
        )

        val elapsedMs = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)

        return VisionPipelineReport(
            timestampMs = System.currentTimeMillis(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            gridCols = grid.cols,
            gridRows = grid.rows,
            occupancyGridSnapshot = grid.snapshot(),
            totalRawNodes = rawNodes.size,
            culledByOcclusionCount = culledCount,
            partiallyOccludedShiftedCount = shiftedCount,
            transparentOverlayBypassedCount = transparentBypassCount,
            hotspotConvergedCount = stage2Res.hotspotConvergedCount,
            spatialBoundIconCount = stage2Res.spatialBoundIconCount,
            geometricIconClassifiedCount = stage2Res.geometricIconClassifiedCount,
            roiRecoveredCount = roiRecoveredNodes.size,
            nanoDetDetectedCount = allFinalNodes.count { it.sourceType == NodeSourceType.NANODET_UI_DETECT },
            foldedListGroupCount = compression.foldedGroupCount,
            temporalStabilizedCount = debounced.stabilizedCount,
            stage1AllNodes = stage1Results,
            detectedRoiVoids = roiVoids,
            finalNodes = allFinalNodes,
            markIdToPixelMap = markMap,
            compressedPromptText = compression.compressedText,
            deltaPromptText = compression.deltaText,
            rawCharCount = compression.rawCharEstimate,
            compressedCharCount = compression.compressedCharCount,
            compressionRatioPercent = compression.savingPercent,
            pipelineLatencyMs = elapsedMs,
            screenPosture = postureResult.posture.code,
            screenPostureZh = postureResult.posture.displayNameZh,
            screenPostureConfidence = postureResult.confidence,
            postureRecommendedRoles = postureResult.recommendedRoles
        )
    }
}
