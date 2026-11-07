package com.example.newandroidapp.vision

import android.graphics.Point
import kotlin.math.abs

/**
 * 第六階段進階優化 (Part C)：
 * 動態跑馬燈與動畫幀間去抖動器（TemporalDebouncer）
 *
 * 解決痛點：
 * 畫面中的跑馬燈（Marquee）、直播彈幕、或微幅呼吸動畫按鈕會導致每幀座標微幅抖動（±4~12px）或文字頻繁滾動，
 * 造成本地 AI 誤以為畫面發生重大跳轉而重複點擊。
 *
 * 演算法：
 * 以「視窗 ID + 類別 + 空間格網槽位」建立幀間追蹤槽，若連續幀間位置抖動 < 24px 但內容或中心微幅漂移，
 * 自動平滑鎖定點擊座標中心，並標記動態穩定狀態。
 */
object TemporalDebouncer {

    private data class SlotHistory(
        val slotKey: String,
        var lastLabel: String,
        var stableTapPx: Point,
        var textChangeCount: Int,
        var jitterCount: Int
    )

    private const val MAX_SLOT_HISTORY_ENTRIES = 256
    private val slotMap = LinkedHashMap<String, SlotHistory>(128, 0.75f, true)

    data class DebounceOutput(
        val nodes: List<Stage2ConvergedNode>,
        val stabilizedCount: Int
    )

    @Synchronized
    fun stabilizeFrame(
        nodes: List<Stage2ConvergedNode>,
        screenWidth: Int,
        screenHeight: Int
    ): DebounceOutput {
        if (slotMap.size > MAX_SLOT_HISTORY_ENTRIES) {
            val iter = slotMap.entries.iterator()
            var toRemove = slotMap.size - (MAX_SLOT_HISTORY_ENTRIES / 2)
            while (iter.hasNext() && toRemove > 0) {
                iter.next()
                iter.remove()
                toRemove--
            }
        }
        var stabilizedCount = 0
        val out = ArrayList<Stage2ConvergedNode>(nodes.size)

        for (node in nodes) {
            // 以 12x24 粗粒度空間槽位 + 類型標籤作為跨幀追蹤 Key
            val slotX = (node.finalTapPx.x * 12) / screenWidth.coerceAtLeast(1)
            val slotY = (node.finalTapPx.y * 24) / screenHeight.coerceAtLeast(1)
            val key = "${node.shortTypeTag}_${slotX}_${slotY}"

            val prev = slotMap[key]
            if (prev != null) {
                val dx = abs(node.finalTapPx.x - prev.stableTapPx.x)
                val dy = abs(node.finalTapPx.y - prev.stableTapPx.y)
                val isMicroJitter = (dx in 1..20 || dy in 1..20)
                val isMarqueeTextChange = (node.label != prev.lastLabel && dx <= 16 && dy <= 16)

                if (isMicroJitter || isMarqueeTextChange) {
                    if (isMicroJitter) prev.jitterCount++
                    if (isMarqueeTextChange) prev.textChangeCount++
                    // 平滑鎖定中心點，消除動畫微抖動
                    val smoothX = (prev.stableTapPx.x + node.finalTapPx.x) / 2
                    val smoothY = (prev.stableTapPx.y + node.finalTapPx.y) / 2
                    val smoothPoint = Point(smoothX, smoothY).apply { x = smoothX; y = smoothY }
                    prev.stableTapPx = smoothPoint
                    prev.lastLabel = node.label
                    stabilizedCount++

                    val newPrefix = if (isMarqueeTextChange && !node.inlineStatePrefix.contains("[~動態]")) {
                        "${node.inlineStatePrefix}[~動態]"
                    } else {
                        node.inlineStatePrefix
                    }
                    out.add(node.copy(finalTapPx = smoothPoint, inlineStatePrefix = newPrefix))
                    continue
                }
            }

            slotMap[key] = SlotHistory(
                slotKey = key,
                lastLabel = node.label,
                stableTapPx = node.finalTapPx,
                textChangeCount = 0,
                jitterCount = 0
            )
            out.add(node)
        }

        return DebounceOutput(nodes = out, stabilizedCount = stabilizedCount)
    }
}
