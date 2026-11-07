package com.example.newandroidapp.chat

import android.content.Context
import android.graphics.Rect
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 主動回報與候選清單提煉器 (ProactiveReportingHelper)
 * 任務完成或階段性操作後提煉畫面候選項目，生成結構化繁體中文回報與對話上下文。
 */
object ProactiveReportingHelper {

    /**
     * 從四階段視覺感知報告中提煉候選項目與任務完成回報文字
     */
    fun generateProactiveReport(
        report: VisionPipelineReport,
        @Suppress("UNUSED_PARAMETER") actionSummary: String,
        isCompleted: Boolean
    ): Pair<String, List<ScreenCandidateItem>> {
        val meaningfulCandidates = extractTopCandidates(report, maxCount = 5)

        val sb = StringBuilder()
        if (isCompleted) {
            sb.append("已經幫你弄好了。")
        } else {
            sb.append("先幫你弄到這一步。")
        }

        return Pair(sb.toString(), meaningfulCandidates)
    }

    fun extractTopCandidates(
        report: VisionPipelineReport,
        maxCount: Int = 5
    ): List<ScreenCandidateItem> {
        val candidates = ArrayList<ScreenCandidateItem>()
        val seen = HashSet<String>()
        var idx = 1

        val sortedNodes = report.finalNodes
            .filter { node ->
                node.label.isNotBlank() &&
                    node.label.length >= 2 &&
                    !node.label.startsWith("圖示:") &&
                    node.shortTypeTag != "SCR" &&
                    (node.visibleRatio >= 0.20f || node.visibleRatio == 0.0f)
            }
            .sortedWith(compareBy<Stage2ConvergedNode> { it.gridTapPoint.y / 60 }.thenBy { it.gridTapPoint.x })

        for (node in sortedNodes) {
            val cleanLabel = node.label.replace(Regex("^\\[.*?\\]"), "").trim()
            if (cleanLabel.length < 2) continue
            val lower = cleanLabel.lowercase()
            if (seen.contains(lower)) continue
            seen.add(lower)

            candidates.add(
                ScreenCandidateItem(
                    index = idx++,
                    label = cleanLabel,
                    boundsInScreen = Rect(node.convergedBounds),
                    targetNodeId = node.nodeId
                )
            )
            if (candidates.size >= maxCount) break
        }
        return candidates
    }

    fun postProactiveReport(
        context: Context?,
        report: VisionPipelineReport,
        actionSummary: String,
        isCompleted: Boolean
    ): ChatMessage {
        val (text, candidates) = generateProactiveReport(report, actionSummary, isCompleted)
        val msg = ConversationManager.appendAiMessage(
            text = text,
            metadataHeader = if (isCompleted) "剛剛 · 已完成" else "剛剛 · 執行中",
            isProactive = true,
            candidates = candidates
        )
        val curCtx = ConversationManager.getTurnContext()
        ConversationManager.updateTurnContext(
            curCtx.copy(
                lastSearchQuery = actionSummary,
                lastCandidates = candidates,
                lastAiPrompt = text,
                timestamp = System.currentTimeMillis()
            )
        )
        if (context != null) {
            com.example.newandroidapp.stability.VoiceCommandAndTtsManager.speak(context, text)
        }
        return msg
    }
}
