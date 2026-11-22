package com.wae.agent.agent

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把無障礙樹轉成 Observation（餵給決策層）。
 * 只取可見、有文字或可互動的節點，最多 maxNodes 個，避免吃爆 context。
 */
object ObservationBuilder {

    data class ScreenSnapshot(val observation: Observation, val nodes: List<AccessibilityNodeInfo>)

    /**
     * 把 OCR 辨識到的文字行併進觀察結果（給**沒有無障礙樹**的 App 用）。
     *
     * OCR 行沒有 node，所以標成 `clickable=true` 並帶螢幕座標；執行層用座標點擊。
     * 這是「所有 App 都能操作」的關鍵：Canvas／WebView／Flutter／遊戲只有畫面，沒有節點。
     */
    fun mergeOcr(
        obs: Observation,
        lines: List<com.wae.agent.vision.PpOcrEngine.OcrLine>,
        scale: Float = 1f,
        maxLines: Int = 30
    ): Observation {
        if (lines.isEmpty()) return obs
        val out = ArrayList<ObservedElement>(obs.elements.size + lines.size)
        out.addAll(obs.elements)
        for (l in lines.take(maxLines)) {
            val t = l.text.trim()
            if (t.isEmpty()) continue
            val r = l.rect
            out.add(
                ObservedElement(
                    index = out.size,
                    text = t,
                    type = "OCR",
                    clickable = true,
                    visible = true,
                    left = (r.left * scale).toInt(),
                    top = (r.top * scale).toInt(),
                    right = (r.right * scale).toInt(),
                    bottom = (r.bottom * scale).toInt()
                )
            )
        }
        return obs.copy(elements = out, ocrTexts = (obs.ocrTexts + lines.map { it.text }).distinct())
    }

    fun fromNode(root: AccessibilityNodeInfo?, maxNodes: Int = 80): Observation =
        snapshot(root, maxNodes).observation

    fun snapshot(root: AccessibilityNodeInfo?, maxNodes: Int = 80): ScreenSnapshot {
        if (root == null) return ScreenSnapshot(Observation(), emptyList())
        val elements = ArrayList<ObservedElement>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && elements.size < maxNodes && guard < 400) {
            guard++
            val node = queue.removeFirst()
            val text = buildText(node)
            if (text.isNotBlank() && node.isVisibleToUser) {
                val editable = node.isEditable || isEditText(node)
                elements.add(
                    ObservedElement(
                        index = elements.size,
                        text = text,
                        type = node.className?.toString()?.substringAfterLast('.') ?: "",
                        clickable = node.isClickable,
                        editable = editable,
                        visible = node.isVisibleToUser,
                        checked = node.isChecked,
                        checkable = node.isCheckable
                    )
                )
                nodes.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        val pkg = root.packageName?.toString().orEmpty()
        val observation = Observation(
            appPackage = pkg,
            title = elements.firstOrNull()?.text.orEmpty(),
            elements = elements
        )
        return ScreenSnapshot(observation, nodes)
    }

    private fun buildText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return node.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun isEditText(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText") || cls.contains("AutoComplete")
    }
}
