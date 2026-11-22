package com.wae.agent.core.predicate

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.agent.core.WaeOpenSourceDatabaseRegistry
import com.wae.agent.core.WaeVisionAccessibilityService
import com.wae.agent.gen5.A11yFrameSource
import com.wae.agent.gen5.DeviceIO
import com.wae.agent.gen5.NodeRegistry
import com.wae.core.model.Control
import com.wae.core.model.Frame
import com.wae.core.predicate.TaskExecutor
import com.wae.core.predicate.TaskNode
import com.wae.core.predicate.TextFold

/**
 * **真機 gateway**：把任務樹的葉子接到無障礙畫面上。
 *
 * 分工（與 TaskExecutor 的約定）：
 *  - [screenLines]：畫面 → 文字（模型只看文字）
 *  - [find]：用決策模型在畫面上找最像的那一個（回索引 + 分數）
 *  - [act]：把原語送進唯一出口（DeviceIO）
 */
class A11yScreenGateway(
    private val service: WaeVisionAccessibilityService,
    private val context: Context
) : TaskExecutor.ScreenGateway {

    private var frame: Frame = Frame.EMPTY
    private var lastMatches: List<Pair<Control, Float>> = emptyList()
    private var lastFindTarget: String = ""
    private var lastWinFp: String = ""

    private fun capture(): Frame {
        frame = runCatching { A11yFrameSource(service, context).capture() }.getOrDefault(Frame.EMPTY)
        // **視窗診斷**（先量測再設計彈窗政策）：畫面變了才印一次，避免洗版
        if (frame.fp.isNotEmpty() && frame.fp != lastWinFp) {
            lastWinFp = frame.fp
            runCatching { dumpWindows() }
        }
        return frame
    }

    /** 印出所有可見視窗的層級資訊：id / type / layer / 套件。 */
    private fun dumpWindows() {
        val ws = runCatching { service.windows }.getOrNull() ?: return
        val sb = StringBuilder("windows=${ws.size}")
        for (w in ws) {
            sb.append(" | id=").append(w.id)
                .append(" type=").append(w.type)
                .append(" layer=").append(w.layer)
                .append(" active=").append(w.isActive)
                .append(" pkg=").append(w.root?.packageName ?: "?")
            runCatching { w.root?.recycle() }
        }
        android.util.Log.i("WAE_WIN", sb.toString())
    }

    override fun screenLines(): List<String> {
        val f = capture()
        return f.controls.map { c ->
            buildString {
                append(c.kind.name).append(' ').append(c.label)
                val checked = c.state.checked
                if (checked != null) append(if (checked) " 已開啟" else " 未開啟")
            }.trim()
        }
    }

    override fun find(target: String): Pair<Int, Float> {
        val f = capture()
        val scored = f.controls.mapIndexed { i, c ->
            Triple(i, c, DecisionRanker.textScore(target, c.label, context))
        }
        lastMatches = scored.map { it.second to it.third }
        val best = scored.maxByOrNull { it.third } ?: return -1 to 0f
        return best.first to best.third
    }

    override fun alreadySatisfied(leaf: TaskNode.Leaf): Boolean {
        val f = capture()
        val on = when (leaf.arg.lowercase()) {
            "on", "開", "開啟", "打開" -> true
            "off", "關", "關閉", "關掉" -> false
            else -> return false
        }
        val hit = f.controls.firstOrNull {
            it.label.isNotBlank() && TextFold.containsFold(it.label, leaf.target)
        } ?: return false
        return hit.state.checked == on
    }

    override fun act(op: TaskNode.Op, target: String, arg: String): Boolean {
        // LAUNCH：target 是「App 名稱」不是套件名 → 先用資料庫/裝置解析成 package
        if (op == TaskNode.Op.LAUNCH) {
            val resolved = runCatching {
                WaeOpenSourceDatabaseRegistry.resolveTargetAppFromDatabaseOrDevice(context, target)
            }.getOrNull()
            val pkg = resolved?.packageName ?: target
            android.util.Log.i("WAE_TASK", "LAUNCH '$target' -> '$pkg'")
            return launchAndNormalize(pkg)
        }

        // **彈窗閘門**（動作前的前置檢查，不參與「要做什麼」的決策）
        // 只擷取一次畫面，供後續步驟共用——多餘的 capture 會打亂節點時序（實測踩過）
        var popupFrame = capture()
        run {
            val target = com.wae.core.predicate.PopupGate.findDismissTarget(popupFrame.controls)
            if (target != null) {
                val n = NodeRegistry.get(target.key)
                if (n != null) {
                    val ok = performOn(n, TaskNode.Op.PICK, "")
                    android.util.Log.i("WAE_POPUP", "dismissed '${target.label}' ok=$ok")
                    if (ok) {
                        Thread.sleep(400)
                        popupFrame = capture()
                    }
                }
            }
        }

        // FIND：target 是「要搜尋的字」，不是搜尋框本身 → 找搜尋框、點它、打進去
        if (op == TaskNode.Op.FIND) {
            // ① 先找「進入輸入模式」的入口：搜尋框，或任何可編輯欄位
            val f0 = popupFrame
            val entry = f0.controls.firstOrNull { c ->
                val l = c.label
                l.isNotBlank() && com.wae.core.predicate.LanguageWords.searchWords()
                    .any { com.wae.core.predicate.TextFold.containsFold(l, it) }
            } ?: f0.controls.firstOrNull { it.kind == com.wae.core.model.ControlKind.FIELD }
            val entryNode = entry?.let { NodeRegistry.get(it.key) }
            if (entryNode == null) {
                android.util.Log.i("WAE_TASK", "FIND 找不到搜尋入口")
                return false
            }
            DeviceIO.tap(service, entryNode) || DeviceIO.perform(entryNode, AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(600)

            // ② **重新擷取**：點下去之後才會出現真正的輸入框（舊節點已失效）
            val f1 = capture()
            val editable = f1.controls.firstOrNull { it.kind == com.wae.core.model.ControlKind.FIELD }
                ?: f1.controls.firstOrNull { c ->
                    val l = c.label
                    l.isNotBlank() && com.wae.core.predicate.LanguageWords.searchWords()
                        .any { com.wae.core.predicate.TextFold.containsFold(l, it) }
                }
            val editNode = editable?.let { NodeRegistry.get(it.key) } ?: entryNode

            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
            }
            // ③ 先聚焦再寫入（有些 App 必須先 focus 才接受 SET_TEXT）
            runCatching { DeviceIO.perform(editNode, AccessibilityNodeInfo.ACTION_FOCUS) }
            var typed = DeviceIO.perform(editNode, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            runCatching {
                DeviceIO.perform(editNode, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())
            }
            Thread.sleep(700)

            // ④ **用搜尋結果驗證查詢字串**（不列任何連接詞／錯字表）
            //    切分可能留下尾端連接字（並），ASR 也可能把「並」聽成「病」。
            //    做法：若結果太少，去掉尾端 1–2 個字重試，取「結果較多」的那個。
            var bestQuery = target
            var bestCount = resultCount()
            var attempt = 0
            while (bestCount < 2 && attempt < 2 && bestQuery.length > 3) {
                attempt += 1
                bestQuery = bestQuery.dropLast(1)
                val retryArgs = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, bestQuery)
                }
                DeviceIO.perform(editNode, AccessibilityNodeInfo.ACTION_SET_TEXT, retryArgs)
                runCatching {
                    DeviceIO.perform(editNode, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())
                }
                Thread.sleep(600)
                val c = resultCount()
                if (c > bestCount) bestCount = c
            }

            android.util.Log.i(
                "WAE_TASK",
                "FIND 輸入 '$bestQuery' typed=$typed 結果=$bestCount（入口='${entry.label}'）"
            )
            return typed && bestCount > 0
        }

        val f = capture()
        // ④ 搜尋結果要「選第一項」，不是選文字最像的那一項。
        //    判準：這一步是 PICK，且 target 就是剛剛搜尋的字（lastFindTarget）。
        if (op == TaskNode.Op.PICK && target == lastFindTarget && target.isNotBlank()) {
            val firstRow = f.controls
                .filter { it.kind == com.wae.core.model.ControlKind.ROW && it.label.isNotBlank() }
                .minByOrNull { it.bounds.top }
            val n = NodeRegistry.get(firstRow?.key ?: "")
            if (n != null) {
                val beforeFp = f.fp
                val ok = performOn(n, op, arg)
                if (!ok) return false
                Thread.sleep(500)
                val changed = capture().fp != beforeFp
                android.util.Log.i("WAE_TASK", "verify PICK(first-row '${firstRow?.label}') changed=$changed")
                return changed
            }
        }
        // 找不到就再等一次：很多 App 的按鈕是「進頁之後才慢慢長出來」
        var control = bestControl(f, target)
        if (control == null) {
            Thread.sleep(900)
            control = bestControl(capture(), target)
        }
        if (control == null) return false
        val beforeFp = f.fp
        val node = NodeRegistry.get(control.key)
        val ok = if (node == null) {
            // key 失效（捲動／延遲載入）→ 用標籤在這一幀重新找
            val again = bestControl(capture(), target) ?: return false
            performOn(NodeRegistry.get(again.key), op, arg)
        } else {
            performOn(node, op, arg)
        }
        if (!ok) return false

        // ⑥ 點擊後的結果驗證：PICK/SHARE/SEND 之後畫面必須有變化
        //    （否則就是「點了但沒進去」——例如搜尋結果沒點開）
        if (op == TaskNode.Op.PICK || op == TaskNode.Op.SHARE || op == TaskNode.Op.SEND) {
            Thread.sleep(500)
            val after = capture()
            val changed = after.fp != beforeFp
            android.util.Log.i(
                "WAE_TASK",
                "verify ${op.name} '$target' screenChanged=$changed (n=${after.controls.size})"
            )
            return changed || after.controls.any { it.label.isNotBlank() && it.label.contains(target) }
        }
        return true
    }

    /** 目前畫面上「像搜尋結果」的元素數量（用來驗證查詢字串）。 */
    private fun resultCount(): Int {
        val f = capture()
        return f.controls.count { c ->
            c.kind == com.wae.core.model.ControlKind.ROW ||
                c.kind == com.wae.core.model.ControlKind.BUTTON
        }
    }

    /**
     * **起始狀態正規化**：把 App 帶回「根畫面」。
     *
     * 方法（通用，不看 App 名稱）：
     *  - 只有畫面上**存在返回元素**時才按 Back（避免直接跳出 App）
     *  - 返回元素的判定來自 `LanguageWords` 的 back 詞（資料驅動）
     *  - 最多按 [maxBacks] 次
     */
    /**
     * **唯一的啟動出口**：啟動 + 起始狀態正規化。
     *
     * 為什麼要收斂：先前 LAUNCH 有三份實作（骨架、動作詞切分、NAVIGATE 委派），
     * 正規化只加在其中一份 → 走別條路就不生效（實測踩過）。
     */
    fun launchAndNormalize(pkg: String): Boolean {
        val launched = runCatching {
            WaeOpenSourceDatabaseRegistry.launchAppByPackageName(context, pkg)
        }.getOrDefault(false)
        Thread.sleep(if (launched) 900 else 300)
        normalizeToRoot(maxBacks = 3, pkg = pkg)
        return true
    }

    private fun normalizeToRoot(maxBacks: Int, pkg: String) {
        val backWords = com.wae.core.predicate.LanguageWords.backWords()
        var sawBack = false
        if (backWords.isNotEmpty()) {
            repeat(maxBacks) {
                val f = capture()
                val hasBack = f.controls.any { c ->
                    c.label.isNotBlank() && backWords.any { w ->
                        com.wae.core.predicate.TextFold.containsFold(c.label, w)
                    }
                }
                if (!hasBack) return
                sawBack = true
                runCatching {
                    service.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    )
                }
                Thread.sleep(450)
            }
            if (sawBack) {
                android.util.Log.i("WAE_TASK", "normalizeToRoot via back")
                return
            }
        }

        // 沒有可辨識的返回元素（很多 App 是純圖示箭頭）→ **重建任務棧**
        // CLEAR_TASK 會清空該 App 的 Activity 堆疊並回到入口 Activity，
        // 不依賴任何文字或座標，也不需要猜。
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
        }.getOrNull()
        if (intent == null) {
            android.util.Log.w("WAE_TASK", "normalizeToRoot: no launch intent for $pkg")
            return
        }
        val relaunched = runCatching {
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (relaunched) {
            Thread.sleep(1200)
            android.util.Log.i("WAE_TASK", "normalizeToRoot via relaunch($pkg)")
        } else {
            android.util.Log.w("WAE_TASK", "normalizeToRoot: startActivity failed for $pkg")
        }
    }

    private fun bestControl(f: Frame, target: String): Control? {
        if (f.controls.isEmpty()) return null
        return f.controls
            .map { it to DecisionRanker.textScore(target, it.label, context) }
            .filter { it.second >= 0.45f }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun performOn(node: AccessibilityNodeInfo?, op: TaskNode.Op, arg: String): Boolean {
        if (node == null) return false
        return when (op) {
            TaskNode.Op.FILL -> {
                DeviceIO.tap(service, node) || DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK)
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arg)
                }
                DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            TaskNode.Op.FIND -> {
                DeviceIO.tap(service, node) || DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK)
            }
            else -> clickNode(node)
        }
    }

    /** 點節點；不可點就往上找可點祖先（圖示在列裡就是這種情況）。 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (DeviceIO.tap(service, node)) return true
        if (DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_CLICK)) return true
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up < 4) {
            val parent = runCatching { cur!!.parent }.getOrNull() ?: break
            if (parent.isClickable) {
                if (DeviceIO.tap(service, parent) || DeviceIO.perform(parent, AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            cur = parent
            up += 1
        }
        return false
    }
}
