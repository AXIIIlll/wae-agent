package com.example.newandroidapp.vision

import android.graphics.Point
import com.example.newandroidapp.ai.DeterminismHardeningEngine

/**
 * 第四階段核心：語義去重、重複結構折疊、狀態內嵌與 0~999 網格量化壓縮器
 *
 * 1. 重複結構折疊（List/Grid Folding）：將 RecyclerView / ListView 中結構相同的項目合併為單一群組宣告，去除重複類別描述。
 * 2. 狀態內嵌（Inline State）：將勾選 [勾選]/[未選]、選中 [已選]、輸入框提示 [輸入:...]、可滑動方向 [可滑動] 直接內嵌於標籤。
 * 3. 座標網格化（0~999 Grid Quantization）：將原始螢幕像素正規化為 0~999 整數網格，節省 20%~30% 數字 Token 開銷。
 * 4. 幀間差分輸出（Delta Frame Compression）：額外提供與上一幀的差異摘要，供本地端小模型極速推理。
 */
object SemanticCompressor {

    private var previousFrameSignatures: Map<String, String> = emptyMap()

    data class CompressionResult(
        val compressedText: String,
        val deltaText: String,
        val foldedGroupCount: Int,
        val rawCharEstimate: Int,
        val compressedCharCount: Int,
        val savingPercent: Int
    )

    fun compress(
        rawNodes: List<RawVisionNode>,
        finalNodes: List<Stage2ConvergedNode>,
        useNormalizedGrid: Boolean = true,
        screenPostureTag: String = ""
    ): CompressionResult {
        // 估算傳統未壓縮無障礙樹 JSON/XML 輸出的字元開銷
        val rawCharEstimate = rawNodes.sumOf { r ->
            145 + r.className.length + r.text.length + r.contentDescription.length + r.viewIdResourceName.length
        }.coerceAtLeast(1)

        // 依 10px 確定性空間網格排序（先依 Y 座標由上至下、再依 X 座標由左至右）
        val sorted = DeterminismHardeningEngine.sortUnscoredNodesCanonically(finalNodes)

        // 統計具有相同 listGroupSignature 的重複結構群組（>= 2 個同構項目即觸發折疊）
        val sigCounts = HashMap<String, Int>()
        for (n in sorted) {
            val sig = n.listGroupSignature
            if (sig != null) {
                sigCounts[sig] = (sigCounts[sig] ?: 0) + 1
            }
        }

        val foldableSignatures = sigCounts.filterValues { it >= 2 }.keys
        val groupedBySig = LinkedHashMap<String, MutableList<Stage2ConvergedNode>>()
        for (n in sorted) {
            val sig = n.listGroupSignature
            if (sig != null && sig in foldableSignatures) {
                groupedBySig.getOrPut(sig) { ArrayList() }.add(n)
            }
        }

        val emittedGroupSigs = HashSet<String>()
        val sb = StringBuilder()
        val coordModeTag = if (useNormalizedGrid) "GRID_0_999" else "RAW_PX"
        val postureSuffix = if (screenPostureTag.isNotBlank()) ", posture=$screenPostureTag" else ""
        sb.appendLine("#VISION_FRAME[mode=$coordModeTag, nodes=${sorted.size}$postureSuffix]")

        var foldedGroupCount = 0

        for (node in sorted) {
            val sig = node.listGroupSignature
            if (sig != null && sig in foldableSignatures) {
                if (sig in emittedGroupSigs) continue
                emittedGroupSigs.add(sig)
                val groupItems = groupedBySig[sig] ?: continue
                foldedGroupCount++

                val commonType = groupItems.first().shortTypeTag
                sb.append("@FOLD_LIST[$commonType x${groupItems.size}]{")
                groupItems.joinTo(sb, separator = "; ") { item ->
                    val cx = if (useNormalizedGrid) item.gridTapPoint.x else item.finalTapPx.x
                    val cy = if (useNormalizedGrid) item.gridTapPoint.y else item.finalTapPx.y
                    val stateAndLabel = "${item.inlineStatePrefix}${item.label}".trim()
                    "#${item.shortMarkId}:\"$stateAndLabel\"@$cx,$cy"
                }
                sb.appendLine("}")
            } else {
                val cx = if (useNormalizedGrid) node.gridTapPoint.x else node.finalTapPx.x
                val cy = if (useNormalizedGrid) node.gridTapPoint.y else node.finalTapPx.y
                val stateAndLabel = "${node.inlineStatePrefix}${node.label}".trim()
                val visSuffix = if (node.visibleRatio < 0.95f) "(露${(node.visibleRatio * 100).toInt()}%)" else ""
                sb.appendLine("#${node.shortMarkId}[${node.shortTypeTag}] \"$stateAndLabel\" @$cx,$cy$visSuffix")
            }
        }

        val compressedText = sb.toString().trim()
        val compressedLen = compressedText.length
        val savingPercent = (((rawCharEstimate - compressedLen).toFloat() / rawCharEstimate.toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 99)

        // 計算幀間差分（Delta Frame）
        val currentSigs = LinkedHashMap<String, String>()
        for (n in sorted) {
            val cx = if (useNormalizedGrid) n.gridTapPoint.x else n.finalTapPx.x
            val cy = if (useNormalizedGrid) n.gridTapPoint.y else n.finalTapPx.y
            val key = "${n.shortTypeTag}:${n.label}"
            val valStr = "#${n.shortMarkId} ${n.inlineStatePrefix}\"${n.label}\" @$cx,$cy"
            currentSigs[key] = valStr
        }

        val deltaSb = StringBuilder()
        if (previousFrameSignatures.isEmpty()) {
            deltaSb.append("DELTA_FRAME[首幀完整基準]: 共 ${sorted.size} 個有效互動/語義元件")
        } else {
            val added = currentSigs.filter { (k, _) -> k !in previousFrameSignatures }.values
            val changed = currentSigs.filter { (k, v) ->
                val prev = previousFrameSignatures[k]
                prev != null && prev != v
            }.values
            val removedCount = previousFrameSignatures.keys.count { it !in currentSigs }

            if (added.isEmpty() && changed.isEmpty() && removedCount == 0) {
                deltaSb.append("DELTA_FRAME[NO_CHANGE]: 畫面無語義或座標變動 (0 Token 增量)")
            } else {
                deltaSb.appendLine("DELTA_FRAME[+${added.size} 新增, ~${changed.size} 變動, -$removedCount 移除]:")
                added.forEach { deltaSb.appendLine("  + $it") }
                changed.forEach { deltaSb.appendLine("  ~ $it") }
            }
        }
        previousFrameSignatures = currentSigs

        return CompressionResult(
            compressedText = compressedText,
            deltaText = deltaSb.toString().trim(),
            foldedGroupCount = foldedGroupCount,
            rawCharEstimate = rawCharEstimate,
            compressedCharCount = compressedLen,
            savingPercent = savingPercent
        )
    }

    /**
     * 將 0~999 網格正規化座標無損還原回真實螢幕像素座標
     */
    fun gridToRawPixel(gx: Int, gy: Int, screenWidth: Int, screenHeight: Int): Point {
        val px = ((gx.coerceIn(0, 999) / 999f) * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val py = ((gy.coerceIn(0, 999) / 999f) * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        return Point(px, py)
    }
}
