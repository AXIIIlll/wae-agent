package com.wae.agent.gen5

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.core.step.ClickResolver
import com.wae.core.step.ElementRef
import com.wae.core.step.StepContract
import com.wae.core.step.StepGoal
import com.wae.core.step.StepKind
import com.wae.core.step.StepMemory
import com.wae.core.step.StepPolicy

/**
 * 單步行動器（三層兩憶計畫 §6 的「點擊器」落地版）。
 *
 * 責任：讀當前畫面 → StepPolicy 決定動作型別 → ClickResolver 找元素 → 執行 → 回報。
 *
 * **鐵律：這個檔案不呼叫任何大語言模型。** 只有規則與（可選的）相似度評分。
 * 它也不決定「要不要做這件事」——那是任務計畫的工作。
 */
object StepActor {

    private const val MAX_ELEMENTS = 80

    /**
     * Shadow 模式（計畫 S3）：只記錄「它會怎麼做」，不真的動手。
     *
     * 為什麼要這個：新的單步政策在還沒被信任之前，應該先跟規則並跑、
     * 比對決策差異，而不是直接放行。打開後所有動作都回 false。
     */
    private const val SHADOW_MODE = false

    data class Snapshot(
        val elements: List<ElementRef>,
        val nodes: Map<String, AccessibilityNodeInfo>
    )

    /**
     * 「有內容」的後置條件（計畫 §4）：畫面上有幾個可點的語意元素。
     *
     * 為什麼不用「畫面變了」：畫面變了可能只是換成空白首頁或載入中。
     * 使用者要的是「有東西可以看／可以點」。
     */
    fun contentCount(service: AccessibilityService, context: android.content.Context? = null, minText: Int = 2): Int {
        val snap = snapshot(service, context) ?: return 0
        return snap.elements.count { it.visible && it.clickable && it.text.length >= minText }
    }

    fun snapshot(service: AccessibilityService, context: android.content.Context? = null): Snapshot? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val out = ArrayList<ElementRef>()
        val nodes = HashMap<String, AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var id = 0
        while (queue.isNotEmpty() && out.size < MAX_ELEMENTS) {
            val node = queue.removeFirst()
            id++
            val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            val role = node.className?.toString()?.substringAfterLast('.') ?: ""
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
            if (text.isNotEmpty() || node.isEditable) {
                val key = "n$id"
                val semantic = context?.let { ctx ->
                    runCatching {
                        val pkg = root.packageName?.toString().orEmpty()
                        AppCategoryResolver.lexiconFor(ctx, pkg).roleOf(text)
                    }.getOrNull()
                }.orEmpty()
                out.add(
                    ElementRef(key, text, role, node.isClickable || node.isEditable, visible, semantic)
                )
                nodes[key] = node
            }
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return Snapshot(out, nodes)
    }

    /**
     * 在目前畫面「搜尋 query」：聚焦輸入框 → 寫字 → 送出。
     *
     * 回傳 true 只在三步都成功時——中間任何一步失敗都回 false（誠實失敗）。
     */
    fun searchHere(
        service: AccessibilityService,
        query: String,
        context: android.content.Context? = null
    ): Boolean {
        if (query.isEmpty()) return false
        if (SHADOW_MODE) {
            val snap = snapshot(service, context)
            val decision = snap?.let {
                StepPolicy.decideValidated(
                    StepMemory.bounded(StepGoal(StepKind.QUERY_FIELD, "搜尋框"), it.elements)
                )
            }
            android.util.Log.i("WAE_STEP_SHADOW", "would search query='$query' decision=$decision")
            return false
        }

        // 最多試兩輪：第一輪找不到輸入框，就點「搜尋」入口再試一次
        repeat(2) { round ->
            val snap = snapshot(service, context) ?: return false
            // 優先真正的輸入框；沒有才退而點「搜尋列」把它展開
            val editable = snap.elements.firstOrNull {
                it.role.contains("Edit", true) || it.role.contains("輸入", true)
            }
            val field = StepPolicy.decideValidated(
                StepMemory.bounded(StepGoal(StepKind.QUERY_FIELD, "搜尋框"), snap.elements)
            )
            val fieldNode = (editable?.let { snap.nodes[it.id] })
                ?: field?.let { pick(service, snap, StepKind.QUERY_FIELD, it.hint) }

            if (fieldNode != null && focus(fieldNode)) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }
                val typed = runCatching {
                    DeviceIO.perform(fieldNode, AccessibilityNodeInfo.ACTION_SET_TEXT, args) &&
                        (fieldNode.text?.toString()?.contains(query) == true)
                }.getOrDefault(false)
                if (typed) return submit(service, snap, fieldNode)

                // SET_TEXT 被忽略 → 用剪貼簿貼上
                val pasted = runCatching {
                    val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", query))
                    DeviceIO.perform(fieldNode, AccessibilityNodeInfo.ACTION_PASTE) &&
                        (fieldNode.text?.toString()?.contains(query) == true)
                }.getOrDefault(false)
                if (pasted) return submit(service, snap, fieldNode)
            }

            if (round == 0) {
                // 找不到輸入框：先點畫面上像搜尋的入口（文字／角色）
                val opener = snap.elements.firstOrNull {
                    it.clickable && !it.text.contains("語音") && !it.text.contains("voice", true) &&
                        !it.text.contains("麥克風") && !it.text.contains("mic", true) &&
                        (it.semanticRole == "SEARCH" ||
                            it.text.contains("搜尋") || it.text.contains("搜索", true) ||
                            it.text.contains("search", true))
                } ?: return false
                val node = snap.nodes[opener.id] ?: return false
                click(node)
                SystemClock.sleep(700)
            }
        }
        return false
    }

    /** 送出：先找 SUBMIT 角色／按鈕，再退回鍵盤 Enter。 */
    private fun submit(
        service: AccessibilityService,
        snap: Snapshot,
        fieldNode: AccessibilityNodeInfo
    ): Boolean {
        val decision = StepPolicy.decideValidated(
            StepMemory.bounded(StepGoal(StepKind.SUBMIT, "搜尋"), snap.elements)
        )
        if (decision != null) {
            val button = pick(service, snap, StepKind.SUBMIT, decision.hint)
            if (button != null && click(button)) return true
        }
        return runCatching {
            val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            DeviceIO.perform(fieldNode, enter.id)
        }.getOrDefault(false)
    }

    /**
     * 畫面摘要（給 `!digest`）：列出前 N 個元素與它們的語意角色。
     */
    fun digest(service: AccessibilityService, context: android.content.Context?, limit: Int = 20): String {
        val snap = snapshot(service, context) ?: return "DIGEST no_root"
        val lines = snap.elements.take(limit).mapIndexed { index, el ->
            (index + 1).toString() + ". " + el.text.take(24) +
                " [" + el.role + (if (el.semanticRole.isEmpty()) "" else "/" + el.semanticRole) + "]" +
                (if (el.clickable) " *" else "")
        }
        return "DIGEST elements=" + snap.elements.size + "\n" + lines.joinToString("\n")
    }

    private fun pick(
        service: AccessibilityService,
        snap: Snapshot,
        kind: StepKind,
        hint: String
    ): AccessibilityNodeInfo? {
        return when (val resolved = ClickResolver.resolve(kind, hint, snap.elements)) {
            is ClickResolver.Result.Hit -> snap.nodes[resolved.element.id]
            else -> null
        }
    }

    private fun focus(node: AccessibilityNodeInfo): Boolean =
        runCatching { DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)

    private fun click(node: AccessibilityNodeInfo): Boolean =
        runCatching { DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
}
