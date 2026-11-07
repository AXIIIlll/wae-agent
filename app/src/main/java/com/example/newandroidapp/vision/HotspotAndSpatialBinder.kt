package com.example.newandroidapp.vision

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.example.newandroidapp.ai.DeterminismHardeningEngine
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 第二階段核心（含第 6 點孤立純圖示 16x16 幾何拓樸分類）：
 * 觸控熱區收斂（Union Bounding Box）、空間拓樸語義就近綁定（Spatial Association）與微型幾何圖示分類
 */
object HotspotAndSpatialBinder {

    data class Stage2Result(
        val convergedNodes: List<Stage2ConvergedNode>,
        val hotspotConvergedCount: Int,
        val spatialBoundIconCount: Int,
        val geometricIconClassifiedCount: Int
    )

    fun process(
        stage1Nodes: List<Stage1OccludedNode>,
        screenWidth: Int,
        screenHeight: Int,
        screenBitmap: Bitmap? = null
    ): Stage2Result {
        val visibleStage1 = stage1Nodes.filter { !it.isCulled }

        // 建立父節點 -> 可見子孫節點映射表（強制過濾自迴圈 pid == node.raw.id）
        val childrenMap = HashMap<String, MutableList<Stage1OccludedNode>>()
        for (node in visibleStage1) {
            val pid = node.raw.parentId
            if (pid != null && pid != node.raw.id) {
                childrenMap.getOrPut(pid) { ArrayList() }.add(node)
            }
        }

        fun isInteractiveNode(node: Stage1OccludedNode): Boolean {
            val r = node.raw
            return r.isClickable || r.isLongClickable || r.isCheckable || r.isEditable
        }

        fun collectDescendants(
            parentId: String,
            out: MutableList<Stage1OccludedNode>,
            visitedIds: MutableSet<String> = HashSet(),
            depth: Int = 0
        ) {
            if (depth > 48 || out.size >= 256) return
            if (!visitedIds.add(parentId)) return
            val direct = childrenMap[parentId] ?: return
            for (ch in direct) {
                if (out.size >= 256) break
                val childId = ch.raw.id
                if (childId in visitedIds) continue
                out.add(ch)
                collectDescendants(childId, out, visitedIds, depth + 1)
            }
        }

        /**
         * 收集歸屬於當前父節點的「自有非互動子孫節點 (Owned Non-Interactive Subtree)」：
         * 一旦遇到自身為可互動的子節點（`isInteractiveNode(ch) == true`），立即停止向下穿透該子節點的內部子樹！
         * 嚴禁父容器穿透獨立子按鈕（如「行車路線」、「儲存」、「分享」）去偷取子按鈕內部的非點擊 TextView 與點擊座標。
         */
        fun collectOwnedNonInteractiveSubtree(
            parentId: String,
            outNonInteractive: MutableList<Stage1OccludedNode>,
            outNestedInteractive: MutableList<Stage1OccludedNode>,
            visitedIds: MutableSet<String> = HashSet(),
            depth: Int = 0
        ) {
            if (depth > 48 || (outNonInteractive.size + outNestedInteractive.size) >= 256) return
            if (!visitedIds.add(parentId)) return
            val direct = childrenMap[parentId] ?: return
            for (ch in direct) {
                if ((outNonInteractive.size + outNestedInteractive.size) >= 256) break
                val childId = ch.raw.id
                if (childId in visitedIds) continue
                if (isInteractiveNode(ch)) {
                    outNestedInteractive.add(ch)
                    // 遇到可互動子元件即停止穿透其內部子樹，但繼續收集其內部可能嵌套的更深層可互動子元件供座標防撞使用
                    val deeperDescendants = ArrayList<Stage1OccludedNode>()
                    collectDescendants(childId, deeperDescendants)
                    for (deep in deeperDescendants) {
                        if (isInteractiveNode(deep)) {
                            outNestedInteractive.add(deep)
                        }
                    }
                } else {
                    outNonInteractive.add(ch)
                    collectOwnedNonInteractiveSubtree(
                        parentId = childId,
                        outNonInteractive = outNonInteractive,
                        outNestedInteractive = outNestedInteractive,
                        visitedIds = visitedIds,
                        depth = depth + 1
                    )
                }
            }
        }

        /**
         * 取得一顆可互動子按鈕的完整顯示標籤（包含該子按鈕自身的文字或其內部非互動 TextView 的文字），
         * 用於從外層父卡片或橫向捲動容器的合成 contentDescription 中扣除子按鈕文字。
         */
        fun extractInteractiveChildDisplayTexts(interactiveNode: Stage1OccludedNode): List<String> {
            val result = ArrayList<String>(4)
            val own = firstNonBlank(
                interactiveNode.raw.text,
                interactiveNode.raw.contentDescription,
                interactiveNode.raw.hintText
            )
            if (own.isNotEmpty()) result.add(own)
            val innerNonInteractive = ArrayList<Stage1OccludedNode>()
            val innerNestedInteractive = ArrayList<Stage1OccludedNode>()
            collectOwnedNonInteractiveSubtree(
                parentId = interactiveNode.raw.id,
                outNonInteractive = innerNonInteractive,
                outNestedInteractive = innerNestedInteractive
            )
            for (inner in innerNonInteractive) {
                val t = firstNonBlank(inner.raw.text, inner.raw.contentDescription, inner.raw.hintText)
                if (t.isNotEmpty() && t !in result) {
                    result.add(t)
                }
            }
            return result
        }

        /**
         * 從父容器的原始標籤中扣除所有獨立可互動子按鈕的文字（例如從卡片「儲存 分享 購票 行車路線 台北101」扣除「儲存/分享/購票/行車路線」），
         * 確保父卡片只保留純粹的實體標題與摘要，不與子按鈕搶奪動作語義。
         */
        fun subtractChildButtonTextsFromParentLabel(
            rawParentLabel: String,
            nestedInteractiveNodes: List<Stage1OccludedNode>
        ): String {
            if (rawParentLabel.isBlank() || nestedInteractiveNodes.isEmpty()) return rawParentLabel.trim()
            val childLabels = LinkedHashSet<String>()
            for (nested in nestedInteractiveNodes) {
                for (txt in extractInteractiveChildDisplayTexts(nested)) {
                    val clean = txt.trim()
                    if (clean.isNotEmpty() && !clean.equals(rawParentLabel.trim(), ignoreCase = true)) {
                        childLabels.add(clean)
                    }
                }
            }
            if (childLabels.isEmpty()) return rawParentLabel.trim()
            var cleaned = rawParentLabel
            // 依長度由長到短扣除，避免短詞先切碎長詞
            for (childLbl in childLabels.sortedByDescending { it.length }) {
                cleaned = cleaned.replace(childLbl, " ")
            }
            val normalized = cleaned.replace(Regex("\\s+"), " ").trim()
            return normalized
        }

        /**
         * 計算父容器排除所有可互動子按鈕包圍盒（含安全邊距）後的非重疊安全點擊座標。
         */
        fun computeSafeParentTapAvoidingNestedButtons(
            parentBounds: Rect,
            fallbackTap: Point,
            ownedTextAnchor: Stage1OccludedNode?,
            nestedInteractiveNodes: List<Stage1OccludedNode>
        ): Point {
            if (nestedInteractiveNodes.isEmpty()) return fallbackTap
            val margin = 18
            fun hitsAnyNested(x: Int, y: Int): Boolean {
                return nestedInteractiveNodes.any { nested ->
                    val nb = nested.visibleBoundingBox
                    x in (nb.left - margin)..(nb.right + margin) &&
                        y in (nb.top - margin)..(nb.bottom + margin)
                }
            }
            if (ownedTextAnchor != null) {
                val anchorPt = ownedTextAnchor.safeTapPointPx
                if (!hitsAnyNested(anchorPt.x, anchorPt.y)) {
                    return anchorPt
                }
            }
            if (!hitsAnyNested(fallbackTap.x, fallbackTap.y)) {
                return fallbackTap
            }
            val cx = (parentBounds.left + parentBounds.right) / 2
            val cy = (parentBounds.top + parentBounds.bottom) / 2
            val candidatePoints = listOf(
                Point(cx, parentBounds.top + ((parentBounds.bottom - parentBounds.top) * 0.25f).toInt()).apply {
                    x = cx
                    y = parentBounds.top + ((parentBounds.bottom - parentBounds.top) * 0.25f).toInt()
                },
                Point(parentBounds.left + ((parentBounds.right - parentBounds.left) * 0.28f).toInt(), cy).apply {
                    x = parentBounds.left + ((parentBounds.right - parentBounds.left) * 0.28f).toInt()
                    y = cy
                },
                Point(cx, cy).apply { x = cx; y = cy },
                Point(
                    parentBounds.left + ((parentBounds.right - parentBounds.left) * 0.28f).toInt(),
                    parentBounds.top + ((parentBounds.bottom - parentBounds.top) * 0.22f).toInt()
                ).apply {
                    x = parentBounds.left + ((parentBounds.right - parentBounds.left) * 0.28f).toInt()
                    y = parentBounds.top + ((parentBounds.bottom - parentBounds.top) * 0.22f).toInt()
                }
            )
            for (pt in candidatePoints) {
                if (pt.x in parentBounds.left..parentBounds.right &&
                    pt.y in parentBounds.top..parentBounds.bottom &&
                    !hitsAnyNested(pt.x, pt.y)
                ) {
                    return pt
                }
            }
            return fallbackTap
        }

        val absorbedChildIds = HashSet<String>()
        var hotspotConvergedCount = 0

        data class MutableCandidate(
            val stage1: Stage1OccludedNode,
            var label: String,
            var convergedBounds: Rect,
            var safeTapPx: Point,
            var wasHotspotConverged: Boolean,
            val absorbedIds: MutableList<String> = ArrayList()
        )

        val candidates = ArrayList<MutableCandidate>()

        for (s1 in visibleStage1) {
            val raw = s1.raw
            if (raw.id in absorbedChildIds) continue

            // 略過全螢幕透明穿透特效層（不應作為獨立互動節點輸出）
            if (s1.overlayAlphaDesc == ScrimAndAlphaInspector.OverlayAlphaType.TRANSPARENT_PASSTHROUGH.desc &&
                !raw.isClickable && raw.text.isBlank() && raw.contentDescription.isBlank()
            ) {
                continue
            }

            val isInteractive = raw.isClickable || raw.isLongClickable || raw.isCheckable || raw.isEditable
            val ownedNonInteractive = ArrayList<Stage1OccludedNode>()
            val nestedInteractiveDescendants = ArrayList<Stage1OccludedNode>()
            collectOwnedNonInteractiveSubtree(
                parentId = raw.id,
                outNonInteractive = ownedNonInteractive,
                outNestedInteractive = nestedInteractiveDescendants
            )
            val hasAnyDescendants = ownedNonInteractive.isNotEmpty() || nestedInteractiveDescendants.isNotEmpty()

            if (isInteractive && hasAnyDescendants) {
                val renderedDescendants = ownedNonInteractive.filter {
                    it.raw.hasRenderedContent ||
                        it.raw.text.isNotEmpty() ||
                        it.raw.contentDescription.isNotEmpty() ||
                        it.raw.hintText.isNotEmpty()
                }
                var primaryTextDescendant: Stage1OccludedNode? = null

                var unionRect: Rect? = null
                val mergedTexts = ArrayList<String>()
                val rawOwnText = firstNonBlank(raw.text, raw.contentDescription, raw.hintText)
                val ownText = subtractChildButtonTextsFromParentLabel(rawOwnText, nestedInteractiveDescendants)
                if (ownText.isNotEmpty()) mergedTexts.add(ownText)

                for (desc in renderedDescendants) {
                    // 【鐵律禁令：嚴禁將「語音搜尋／麥克風／智慧鏡頭」子元件併入搜尋框熱區或文字標籤】
                    if (isVoiceOrCameraInputNode(desc.raw)) {
                        continue
                    }
                    val dRect = desc.visibleBoundingBox
                    val dW = dRect.right - dRect.left
                    val dH = dRect.bottom - dRect.top
                    if (dW > 0 && dH > 0) {
                        val curUnion = unionRect
                        if (curUnion == null) {
                            unionRect = Rect().apply {
                                left = dRect.left
                                top = dRect.top
                                right = dRect.right
                                bottom = dRect.bottom
                            }
                        } else {
                            curUnion.left = min(curUnion.left, dRect.left)
                            curUnion.top = min(curUnion.top, dRect.top)
                            curUnion.right = max(curUnion.right, dRect.right)
                            curUnion.bottom = max(curUnion.bottom, dRect.bottom)
                        }
                    }
                    val rawDt = firstNonBlank(desc.raw.text, desc.raw.contentDescription, desc.raw.hintText)
                    val dt = subtractChildButtonTextsFromParentLabel(rawDt, nestedInteractiveDescendants)
                    if (dt.isNotEmpty()) {
                        if (primaryTextDescendant == null && dW > 0 && dH > 0) {
                            primaryTextDescendant = desc
                        }
                        if (dt !in mergedTexts) {
                            mergedTexts.add(dt)
                        }
                    }
                    absorbedChildIds.add(desc.raw.id)
                }

                for (desc in ownedNonInteractive) {
                    absorbedChildIds.add(desc.raw.id)
                }

                // 若某個可點擊捲動容器（例如包裹整排按鈕的 HorizontalScrollView #26）本身沒有任何獨立文字，
                // 且內部已經有 >= 2 顆獨立可互動子按鈕，則該捲動外殼只是純按鈕列包裝容器，不應再偽裝成一顆獨立按鈕搶奪點擊權！
                if (mergedTexts.isEmpty() && raw.isScrollable && nestedInteractiveDescendants.size >= 2 && !raw.isEditable && !raw.isCheckable) {
                    continue
                }

                val finalBounds: Rect
                val finalTap: Point
                val converged: Boolean
                val uRect = unionRect
                val uW = if (uRect != null) uRect.right - uRect.left else 0
                val uH = if (uRect != null) uRect.bottom - uRect.top else 0
                if (uRect != null && uW > 0 && uH > 0) {
                    finalBounds = uRect
                    val candidateTap = if (renderedDescendants.size == 1) {
                        renderedDescendants[0].safeTapPointPx
                    } else {
                        val cx = (uRect.left + uRect.right) / 2
                        val cy = (uRect.top + uRect.bottom) / 2
                        Point(cx, cy).apply { x = cx; y = cy }
                    }
                    finalTap = computeSafeParentTapAvoidingNestedButtons(
                        parentBounds = finalBounds,
                        fallbackTap = candidateTap,
                        ownedTextAnchor = primaryTextDescendant,
                        nestedInteractiveNodes = nestedInteractiveDescendants
                    )
                    val s1W = s1.visibleBoundingBox.right - s1.visibleBoundingBox.left
                    val s1H = s1.visibleBoundingBox.bottom - s1.visibleBoundingBox.top
                    val origArea = max(1, s1W * s1H)
                    val newArea = uW * uH
                    val rawCx = (s1.raw.bounds.left + s1.raw.bounds.right) / 2
                    val rawCy = (s1.raw.bounds.top + s1.raw.bounds.bottom) / 2
                    val centerShift = hypot(
                        (finalTap.x - rawCx).toDouble(),
                        (finalTap.y - rawCy).toDouble()
                    )
                    converged = (newArea < origArea * 0.85f) || (centerShift > 12.0)
                    if (converged) hotspotConvergedCount++
                } else {
                    finalBounds = s1.visibleBoundingBox
                    finalTap = computeSafeParentTapAvoidingNestedButtons(
                        parentBounds = finalBounds,
                        fallbackTap = s1.safeTapPointPx,
                        ownedTextAnchor = primaryTextDescendant,
                        nestedInteractiveNodes = nestedInteractiveDescendants
                    )
                    converged = false
                }

                candidates.add(
                    MutableCandidate(
                        stage1 = s1,
                        label = mergedTexts.joinToString(" "),
                        convergedBounds = finalBounds,
                        safeTapPx = finalTap,
                        wasHotspotConverged = converged
                    )
                )
            } else if (isInteractive || raw.isScrollable || raw.text.isNotEmpty() || raw.contentDescription.isNotEmpty() || raw.hintText.isNotEmpty()) {
                val isPureLayoutShell = !isInteractive && !raw.isScrollable &&
                    raw.text.isEmpty() && raw.contentDescription.isEmpty() && raw.hintText.isEmpty() &&
                    hasAnyDescendants
                if (!isPureLayoutShell) {
                    val rawLbl = firstNonBlank(raw.text, raw.contentDescription, raw.hintText)
                    val lbl = subtractChildButtonTextsFromParentLabel(rawLbl, nestedInteractiveDescendants)
                    // 若非互動捲動容器（如 HorizontalScrollView #20/#26）扣除子按鈕文字後無自有標籤且內含 >= 2 顆子按鈕，略過避免干擾
                    if (!isInteractive && raw.isScrollable && lbl.isBlank() && nestedInteractiveDescendants.size >= 2) {
                        continue
                    }
                    candidates.add(
                        MutableCandidate(
                            stage1 = s1,
                            label = lbl,
                            convergedBounds = s1.visibleBoundingBox,
                            safeTapPx = computeSafeParentTapAvoidingNestedButtons(
                                parentBounds = s1.visibleBoundingBox,
                                fallbackTap = s1.safeTapPointPx,
                                ownedTextAnchor = null,
                                nestedInteractiveNodes = nestedInteractiveDescendants
                            ),
                            wasHotspotConverged = false
                        )
                    )
                }
            }
        }

        val activeCandidates = candidates.filter { it.stage1.raw.id !in absorbedChildIds }.toMutableList()

        // 2. 空間拓樸關聯（Spatial Association）
        val consumedTextNodeIds = HashSet<String>()
        var spatialBoundCount = 0
        val maxSearchRadiusPx = min(screenWidth, screenHeight) * 0.16f

        val unlabeledIcons = activeCandidates.filter { cand ->
            val r = cand.stage1.raw
            (r.isClickable || r.isLongClickable || r.isCheckable) && cand.label.isBlank()
        }

        val pureTextNodes = activeCandidates.filter { cand ->
            val r = cand.stage1.raw
            !r.isClickable && !r.isLongClickable && !r.isCheckable && !r.isEditable &&
                cand.label.isNotBlank()
        }

        val clickableContainerIds = activeCandidates
            .filter { it.stage1.raw.isClickable || it.stage1.raw.isCheckable }
            .map { it.stage1.raw.id }
            .toHashSet()

        for (iconCand in unlabeledIcons) {
            val iconRect = iconCand.convergedBounds
            val iconCx = (iconRect.left + iconRect.right) * 0.5f
            val iconCy = (iconRect.top + iconRect.bottom) * 0.5f
            val iconW = iconRect.right - iconRect.left
            val iconH = iconRect.bottom - iconRect.top
            val iconRawId = iconCand.stage1.raw.id
            val iconParentId = iconCand.stage1.raw.parentId

            var bestTextCand: MutableCandidate? = null
            var bestScore = Float.MAX_VALUE

            for (txtCand in pureTextNodes) {
                val txtId = txtCand.stage1.raw.id
                if (txtId in consumedTextNodeIds) continue
                if (txtCand.stage1.raw.windowId != iconCand.stage1.raw.windowId) continue

                // 【水平膠囊列防跨容器偷取文字保護】若文字節點隸屬於另一個獨立可點擊父容器，禁止跨容器吸附
                val txtParentId = txtCand.stage1.raw.parentId
                if (!txtParentId.isNullOrEmpty() &&
                    txtParentId != iconRawId &&
                    txtParentId != iconParentId &&
                    txtParentId in clickableContainerIds
                ) {
                    continue
                }

                val txtRect = txtCand.convergedBounds
                val txtCx = (txtRect.left + txtRect.right) * 0.5f
                val txtCy = (txtRect.top + txtRect.bottom) * 0.5f
                val txtW = txtRect.right - txtRect.left
                val txtH = txtRect.bottom - txtRect.top

                val intersects = iconRect.left < txtRect.right && txtRect.left < iconRect.right &&
                    iconRect.top < txtRect.bottom && txtRect.top < iconRect.bottom
                if (intersects) {
                    val dist = hypot((txtCx - iconCx).toDouble(), (txtCy - iconCy).toDouble()).toFloat() * 0.2f
                    if (dist < bestScore) {
                        bestScore = dist
                        bestTextCand = txtCand
                    }
                    continue
                }

                val dx = abs(txtCx - iconCx)
                val dyBelow = txtRect.top - iconRect.bottom
                if (dyBelow in -12..(maxSearchRadiusPx.toInt()) && dx <= max(iconW, txtW) * 0.85f) {
                    val score = dyBelow.coerceAtLeast(0) + dx * 1.4f
                    if (score < bestScore) {
                        bestScore = score
                        bestTextCand = txtCand
                    }
                }

                val dy = abs(txtCy - iconCy)
                val dxRight = txtRect.left - iconRect.right
                if (dxRight in -12..(maxSearchRadiusPx.toInt()) && dy <= max(iconH, txtH) * 0.8f) {
                    val score = dxRight.coerceAtLeast(0) * 1.1f + dy * 1.5f
                    if (score < bestScore) {
                        bestScore = score
                        bestTextCand = txtCand
                    }
                }

                val dxLeft = iconRect.left - txtRect.right
                if (dxLeft in -12..(maxSearchRadiusPx * 0.8f).toInt() && dy <= max(iconH, txtH) * 0.7f) {
                    val score = dxLeft.coerceAtLeast(0) * 1.3f + dy * 1.6f
                    if (score < bestScore) {
                        bestScore = score
                        bestTextCand = txtCand
                    }
                }
            }

            if (bestTextCand != null && bestScore <= maxSearchRadiusPx) {
                iconCand.label = bestTextCand.label
                iconCand.absorbedIds.add(bestTextCand.stage1.raw.id)
                consumedTextNodeIds.add(bestTextCand.stage1.raw.id)
                spatialBoundCount++
            }
        }

        // 2.5 【思考理解力升級 #2：同行／同卡片「主詞—按鈕」關係理解器 (Row & Card Relational Grouper)】
        // 當畫面上有一整排「開關 (CHK)」或通用動作鈕（如「購買、加入購物車、下載、安裝、刪除、開啟、關閉、管理、領取」），
        // 自動將其左側同行（Same Row）或卡片正上方的主詞標題焊接進來（例如變成「藍牙 開關」、「降噪耳機 加入購物車」），
        // 徹底解決 AI 分不清哪個開關或購買鈕屬於哪個項目的問題！
        val genericActionLabels = setOf(
            "開關", "開啟", "關閉", "on", "off", "switch", "checkbox",
            "購買", "立即購買", "加入購物車", "下單", "下載", "安裝", "更新",
            "刪除", "移除", "管理", "設定", "領取", "播放", "訂閱", "關注", "查看", "編輯"
        )
        val rowTolerancePx = (screenHeight * 0.042f).coerceAtLeast(36f)
        val cardVerticalRadiusPx = (screenHeight * 0.09f).coerceAtLeast(80f)

        for (ctrlCand in activeCandidates) {
            val r = ctrlCand.stage1.raw
            val isActionableCtrl = r.isCheckable || r.isClickable || r.isLongClickable
            if (!isActionableCtrl) continue

            val cleanCtrlLabel = ctrlCand.label.trim()
            val needsSubjectBinding = r.isCheckable && cleanCtrlLabel.length <= 4 ||
                cleanCtrlLabel.lowercase() in genericActionLabels
            if (!needsSubjectBinding) continue

            val ctrlRect = ctrlCand.convergedBounds
            val ctrlCx = (ctrlRect.left + ctrlRect.right) * 0.5f
            val ctrlCy = (ctrlRect.top + ctrlRect.bottom) * 0.5f

            var bestSubjectCand: MutableCandidate? = null
            var bestSubjectScore = Float.MAX_VALUE

            for (txtCand in pureTextNodes) {
                if (txtCand === ctrlCand) continue
                val subjText = txtCand.label.trim()
                if (subjText.length < 2 || subjText.lowercase() in genericActionLabels) continue
                if (txtCand.stage1.raw.windowId != r.windowId) continue

                val txtRect = txtCand.convergedBounds
                val txtCx = (txtRect.left + txtRect.right) * 0.5f
                val txtCy = (txtRect.top + txtRect.bottom) * 0.5f

                // A. 水平同行左側主詞（例如左邊「藍牙 / Wi-Fi / 商品名」，右邊「開關 / 購買」）
                val dyRow = abs(txtCy - ctrlCy)
                val dxLeftGap = ctrlRect.left - txtRect.right
                if (dyRow <= rowTolerancePx && txtCx < ctrlCx && dxLeftGap >= -24 && dxLeftGap <= screenWidth * 0.78f) {
                    val rowScore = dyRow * 2.0f + dxLeftGap.coerceAtLeast(0) * 0.35f
                    if (rowScore < bestSubjectScore) {
                        bestSubjectScore = rowScore
                        bestSubjectCand = txtCand
                    }
                }

                // B. 卡片正上方主詞（例如上方「商品標題」，下方「加入購物車 / 購買」）
                val dxCol = abs(txtCx - ctrlCx)
                val dyAboveGap = ctrlRect.top - txtRect.bottom
                val maxW = max(ctrlRect.right - ctrlRect.left, txtRect.right - txtRect.left)
                if (dyAboveGap in -10..(cardVerticalRadiusPx.toInt()) && dxCol <= maxW * 0.90f) {
                    val cardScore = dyAboveGap.coerceAtLeast(0) * 1.2f + dxCol * 0.8f + 40f
                    if (cardScore < bestSubjectScore) {
                        bestSubjectScore = cardScore
                        bestSubjectCand = txtCand
                    }
                }
            }

            if (bestSubjectCand != null) {
                val subj = bestSubjectCand.label.trim()
                if (!cleanCtrlLabel.contains(subj)) {
                    ctrlCand.label = if (cleanCtrlLabel.isEmpty()) subj else "$subj $cleanCtrlLabel"
                    if (bestSubjectCand.stage1.raw.id !in ctrlCand.absorbedIds) {
                        ctrlCand.absorbedIds.add(bestSubjectCand.stage1.raw.id)
                    }
                    spatialBoundCount++
                }
            }
        }

        // 3. 第 6 點優化：針對周圍完全無鄰近文字可吸附的「孤立無字純圖示」，啟動 16x16 像素幾何拓樸分類器！
        val finalOut = ArrayList<Stage2ConvergedNode>()
        var markCounter = 1
        var geometricIconCount = 0

        for (cand in activeCandidates) {
            val raw = cand.stage1.raw
            if (raw.id in consumedTextNodeIds) continue

            // 【最高優先級鐵律禁令：嚴禁 AI 點擊語音輸入／語音搜尋／麥克風／鏡頭搜尋按鈕，直接從候選清單剔除】
            if (!raw.isEditable && isVoiceOrCameraInputNode(raw, cand.label)) {
                continue
            }

            // 【確定性加固：動態時間/電量/網速文字隔離】
            if (!raw.isEditable && cand.label.isNotBlank() &&
                DeterminismHardeningEngine.isDynamicClockOrBatteryNoise(cand.label)
            ) {
                continue
            }

            val isInteractive = raw.isClickable || raw.isLongClickable || raw.isCheckable || raw.isEditable || raw.isScrollable
            if (!isInteractive && cand.label.isBlank()) continue

            var isGeometricIconClassified = false
            val resolvedLabel = if (cand.label.isNotBlank()) {
                if (raw.isEditable) cand.label else DeterminismHardeningEngine.sanitizeDynamicNoiseFromLabel(cand.label)
            } else {
                isGeometricIconClassified = true
                geometricIconCount++
                TinyIconClassifier.classifyIsolatedIcon(
                    raw = raw,
                    iconBounds = cand.convergedBounds,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    screenBitmap = screenBitmap
                )
            }
            if (!isInteractive && resolvedLabel.isBlank()) continue

            val inlineState = buildInlineStatePrefix(raw)
            val shortTag = if (isGeometricIconClassified) "ICO" else classifyShortTag(raw)

            // 【防誤觸右側語音麥克風】：若為寬長條搜尋框或輸入框，強制將點擊落點鎖定在左側 35% 純文字區，遠離右側語音輸入與相機按鈕
            val boxW = cand.convergedBounds.right - cand.convergedBounds.left
            val lowerResolved = resolvedLabel.lowercase()
            val isWideSearchOrInputBar = boxW >= (screenWidth * 0.42f).toInt() &&
                (raw.isEditable || lowerResolved.contains("搜尋") || lowerResolved.contains("搜索") || lowerResolved.contains("search") || lowerResolved.contains("輸入")) &&
                !isVoiceOrCameraInputNode(raw, resolvedLabel)
            val rawTapPoint = if (isWideSearchOrInputBar) {
                val safeTextZoneX = (cand.convergedBounds.left + (boxW * 0.34f).toInt())
                    .coerceIn(cand.convergedBounds.left + 12, cand.convergedBounds.right - 12)
                Point(safeTextZoneX, cand.safeTapPx.y).apply {
                    x = safeTextZoneX
                    y = cand.safeTapPx.y
                }
            } else {
                cand.safeTapPx
            }
            val safeTapPoint = DeterminismHardeningEngine.roundPointTo10PxGrid(rawTapPoint)

            val gx = ((safeTapPoint.x.toFloat() / screenWidth.coerceAtLeast(1)) * 999f).toInt().coerceIn(0, 999)
            val gy = ((safeTapPoint.y.toFloat() / screenHeight.coerceAtLeast(1)) * 999f).toInt().coerceIn(0, 999)

            val srcType = when {
                cand.absorbedIds.isNotEmpty() -> NodeSourceType.SPATIAL_MERGED_ICON
                isGeometricIconClassified -> NodeSourceType.GEOMETRIC_ICON_CLASSIFIED
                else -> NodeSourceType.UI_TREE
            }

            finalOut.add(
                Stage2ConvergedNode(
                    nodeId = raw.id,
                    shortMarkId = markCounter++,
                    className = raw.className,
                    shortTypeTag = shortTag,
                    label = resolvedLabel,
                    inlineStatePrefix = inlineState,
                    rawOriginalBounds = DeterminismHardeningEngine.roundRectTo10PxGrid(raw.bounds),
                    convergedBounds = DeterminismHardeningEngine.roundRectTo10PxGrid(cand.convergedBounds),
                    finalTapPx = safeTapPoint,
                    gridTapPoint = Point(gx, gy).apply { x = gx; y = gy },
                    visibleRatio = cand.stage1.visibleRatio,
                    wasHotspotConverged = cand.wasHotspotConverged,
                    absorbedTextIds = cand.absorbedIds,
                    sourceType = srcType,
                    listGroupSignature = raw.listStructuralSignature,
                    packageName = raw.packageName
                )
            )
        }

        val canonicalOut = DeterminismHardeningEngine.assignCanonicalMarkIds(finalOut)

        return Stage2Result(
            convergedNodes = canonicalOut,
            hotspotConvergedCount = hotspotConvergedCount,
            spatialBoundIconCount = spatialBoundCount,
            geometricIconClassifiedCount = geometricIconCount
        )
    }

    fun buildInlineStatePrefix(raw: RawVisionNode): String {
        val sb = StringBuilder()
        if (!raw.isEnabled) sb.append("[停用]")
        if (raw.isCheckable) {
            sb.append(if (raw.isChecked) "[勾選]" else "[未選]")
        }
        if (raw.isSelected) sb.append("[已選]")
        if (raw.isFocused) sb.append("[焦點]")
        if (raw.isEditable) {
            val hint = raw.hintText.ifBlank { "輸入框" }
            sb.append("[輸入:$hint]")
        }
        if (raw.isScrollable && raw.scrollDirections.isNotEmpty()) {
            sb.append("[${raw.scrollDirections}]")
        }
        return sb.toString()
    }

    private fun classifyShortTag(raw: RawVisionNode): String = when {
        raw.isEditable -> "INP"
        raw.isCheckable -> "CHK"
        raw.isScrollable && !raw.isClickable -> "SCR"
        raw.isClickable || raw.isLongClickable -> "BTN"
        else -> "TXT"
    }

    private fun firstNonBlank(vararg values: String): String {
        for (v in values) {
            if (v.isNotBlank()) return v.trim()
        }
        return ""
    }

    private val VOICE_OR_CAMERA_KEYWORDS = listOf(
        "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音", "麥克風", "話筒", "按住說話", "點擊說話", "聽寫",
        "voice search", "voice input", "voice", "microphone", "speak", "dictate",
        "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "相機搜尋", "google lens"
    )

    private val VOICE_OR_CAMERA_ID_TOKENS = listOf(
        "voice", "mic", "microphone", "speech", "asr", "audio_input", "lens", "camera_search"
    )

    fun isVoiceOrCameraInputNode(raw: RawVisionNode, extraLabel: String = ""): Boolean {
        val combinedText = "${raw.text} ${raw.contentDescription} ${raw.hintText} $extraLabel".lowercase()
        if (VOICE_OR_CAMERA_KEYWORDS.any { combinedText.contains(it) }) return true
        val idLower = raw.viewIdResourceName.substringAfterLast('/').lowercase()
        if (idLower.isNotEmpty() && VOICE_OR_CAMERA_ID_TOKENS.any { idLower.contains(it) }) return true
        return false
    }
}
