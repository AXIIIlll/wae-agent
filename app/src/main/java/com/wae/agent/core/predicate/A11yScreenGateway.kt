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

    /** 畫面穩定判定（P0）：連續兩次以上指紋相同、且停留超過門檻，才當作「可以動手」。 */
    private val stability = com.wae.core.plan.Stability(
        requiredStableFrames = com.wae.agent.core.runtime.Tunables.getInt(
            context, "screen.stable_frames", 2
        ),
        minStableMs = com.wae.agent.core.runtime.Tunables.getLong(
            context, "screen.stable_ms", 300L
        )
    )

    /**
     * 擷取一幀「穩定」的畫面。
     *
     * 為什麼要等：同一題時好時壞的根因幾乎都是太早動手（列表還在載入、轉場動畫）。
     * 最多重試 `screen.stable_polls` 次；真的等不到就用最後一幀（不要卡死整個任務）。
     */
    private fun capture(): Frame {
        val maxPolls = com.wae.agent.core.runtime.Tunables.getInt(context, "screen.stable_polls", 6)
        var last = Frame.EMPTY
        for (poll in 0 until maxPolls.coerceAtLeast(1)) {
            last = com.wae.core.util.Degrade.orElse("gateway:capture", Frame.EMPTY) {
                A11yFrameSource(service, context).capture()
            }
            if (stability.observe(last.fp, android.os.SystemClock.uptimeMillis())) break
            if (poll < maxPolls - 1) Thread.sleep(120)
        }
        frame = last
        // **視窗診斷**（先量測再設計彈窗政策）：畫面變了才印一次，避免洗版
        if (frame.fp.isNotEmpty() && frame.fp != lastWinFp) {
            lastWinFp = frame.fp
            runCatching { dumpWindows() }
        }
        return frame
    }

    /**
     * 搜尋入口的關鍵字（選擇器家族 SEARCH_ENTRY），來自 `ui_selector_families.tsv`。
     *
     * 為什麼要身份字串：設定頁的搜尋入口常常只有放大鏡圖示——label 是空的，
     * 或只有「搜」一個字，純文字比對永遠找不到（實測 14 題卡在這裡）。
     */
    @Volatile
    private var entryKeywords: Set<String>? = null

    private fun searchEntryKeywords(): Set<String> {
        entryKeywords?.let { return it }
        val loaded = com.wae.core.util.Degrade.orElse("gateway:searchEntryKeywords", emptySet<String>()) {
            context.assets.open("open_source_ai_databases/ui_selector_families.tsv")
                .bufferedReader(Charsets.UTF_8).useLines { lines ->
                    val acc = HashSet<String>()
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val cells = line.split('\t')
                        if (cells.size < 2) continue
                        if (cells[1].trim().equals("SEARCH_ENTRY", true)) acc.add(cells[0].trim().lowercase())
                    }
                    acc
                }
        }
        entryKeywords = loaded
        return loaded
    }

    /**
     * 挑「最像搜尋入口」的控制項。順序很重要：
     * 可編輯且像搜尋 → 像搜尋（含圖示身份）→ 任何輸入框。
     */
    private fun pickSearchEntry(controls: List<Control>, keywords: Set<String>): Control? {
        if (controls.isEmpty()) return null
        val searchWords = com.wae.core.predicate.LanguageWords.searchWords()

        fun byLabel(c: Control): Boolean {
            val l = c.label
            if (l.isBlank()) return false
            if (searchWords.any { TextFold.containsFold(l, it) }) return true
            // 「搜」這種被截斷的單字提示：搜尋詞以它開頭就算
            return l.length <= 2 && searchWords.any { it.startsWith(l) }
        }

        fun byId(c: Control): Boolean {
            val id = c.key.lowercase()
            return keywords.any { k -> k.isNotBlank() && com.wae.core.match.DefinitionMatcher.containsToken(id, k) }
        }

        val field = com.wae.core.model.ControlKind.FIELD
        return controls.firstOrNull { it.kind == field && (byLabel(it) || byId(it)) }
            ?: controls.firstOrNull { byLabel(it) || byId(it) }
            ?: controls.firstOrNull { it.kind == field }
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
        // 決策層只看「選項」：先把畫面簡化成可互動元素，再在這個小集合上打分。
        // 這樣純說明文字根本不會進候選，也不必再靠分數補救。
        val pairs = optionsOf(f)
        if (pairs.isEmpty()) {
            lastMatches = emptyList()
            return -1 to 0f
        }
        val boost = conceptBoost(target, pairs.map { it.second })
        val scored = pairs.mapIndexed { i, pair ->
            val option = pair.second
            val direct = labelScore(target, option.label, boost)
            val groupScore = if (option.group.isNotEmpty()) {
                DecisionRanker.textScore(target, option.group, context)
            } else 0f
            Triple(i, option, maxOf(direct, groupScore * 0.8f))
        }
        lastMatches = pairs.map { it.first to DecisionRanker.textScore(target, it.second.label, context) }

        val goalFold = com.wae.core.predicate.TextFold.fold(target)
        val best = scored.maxWithOrNull { a, b ->
            val byScore = a.third.compareTo(b.third)
            if (byScore != 0) return@maxWithOrNull byScore
            val aExact = if (com.wae.core.predicate.TextFold.fold(a.second.label) == goalFold) 1 else 0
            val bExact = if (com.wae.core.predicate.TextFold.fold(b.second.label) == goalFold) 1 else 0
            if (aExact != bExact) return@maxWithOrNull (aExact - bExact)
            b.second.label.length.compareTo(a.second.label.length)
        } ?: return -1 to 0f

        run {
            val top = scored.sortedByDescending { it.third }.take(3).joinToString(" | ") { s ->
                "#" + s.first + ":" + s.second.role + ":" + s.second.label.take(16) + ":%.2f".format(s.third)
            }
            android.util.Log.i("WAE_PICK", "find(option) top3=" + top + " of " + pairs.size)
        }
        return best.first to best.third
    }

    override fun pickByOcr(target: String): Boolean {
        val bmp = com.wae.core.util.Degrade.orNull("ocr:tap.shot") { service.captureScreenForPerception() }
            ?: return false
        val lines = com.wae.core.util.Degrade.orEmpty("ocr:tap.detect") {
            com.wae.agent.vision.PpOcrEngine.detectAndRecognize(bmp, 60)
        }
        if (lines.isEmpty()) {
            android.util.Log.i("WAE_OCR", "pickByOcr '$target' → OCR 沒有結果")
            return false
        }
        val hit = lines.firstOrNull { l ->
            val fold = com.wae.core.predicate.TextFold.fold(l.text)
            val want = com.wae.core.predicate.TextFold.fold(target)
            want.isNotEmpty() && fold.contains(want)
        }
        if (hit == null) {
            android.util.Log.i("WAE_OCR", "pickByOcr '$target' → 前幾行=" +
                lines.take(5).joinToString("/") { it.text.take(8) })
            return false
        }
        // OCR 座標是 bitmap 座標；換算回螢幕座標再點
        val dm = context.resources.displayMetrics
        val sx = if (bmp.width > 0) dm.widthPixels.toFloat() / bmp.width else 1f
        val sy = if (bmp.height > 0) dm.heightPixels.toFloat() / bmp.height else 1f
        val cx = ((hit.rect.left + hit.rect.right) / 2f * sx).toInt()
        val cy = ((hit.rect.top + hit.rect.bottom) / 2f * sy).toInt()
        android.util.Log.i("WAE_OCR", "pickByOcr '$target' → '" + hit.text + "' @($cx,$cy)")
        return com.wae.core.util.Degrade.orElse("ocr:tap.click", false) {
            val path = android.graphics.Path()
            path.moveTo(cx.toFloat(), cy.toFloat())
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            service.dispatchGesture(gesture, null, null)
            true
        }
    }

    override fun openOverflowMenu(): Boolean {
        val labels = com.wae.core.predicate.LanguageWords.menuLabels()
        if (labels.isEmpty()) return false
        val attempts = com.wae.agent.core.runtime.Tunables.getInt(context, "menu.find_attempts", 3)
        val delayMs = com.wae.agent.core.runtime.Tunables.getLong(context, "menu.find_delay_ms", 400L)

        // 候選：標籤含任一個 MENU 詞（三點選單的無障礙標籤五花八門）
        var candidates: List<Control> = emptyList()
        var lastFrame: Frame = Frame.EMPTY
        for (i in 0 until attempts) {
            val f = capture()
            lastFrame = f
            candidates = f.controls.filter { c ->
                val fold = com.wae.core.predicate.TextFold.fold(c.label)
                fold.isNotEmpty() && labels.any { l ->
                    val t = com.wae.core.predicate.TextFold.fold(l)
                    t.isNotEmpty() && fold.contains(t)
                }
            }
            if (candidates.isNotEmpty()) break
            if (i < attempts - 1) Thread.sleep(delayMs)
        }
        if (candidates.isEmpty()) {
            android.util.Log.i("WAE_MENU", "no overflow menu after $attempts tries")
            return false
        }

        // 排序（通用）：用「不在網頁內容 / 在上方 / 在右側 / 有資源名」排除頁面內嵌那顆三點。
        val dm = context.resources.displayMetrics
        val pairs = optionsOf(lastFrame)
        val byKey = pairs.associate { it.first.key to it.second }
        val ranked = com.wae.core.screen.MenuRanker.rank(
            options = candidates.mapNotNull { byKey[it.key] },
            screenHeightPx = dm.heightPixels,
            screenWidthPx = dm.widthPixels
        )
        val ordered = ranked.mapNotNull { r -> candidates.firstOrNull { c -> byKey[c.key]?.id == r.option.id } }
        val pool = if (ordered.isNotEmpty()) ordered else candidates

        // 依序嘗試；點下去之後如果沒有出現新的彈出層，就換下一個候選。
        val maxTries = com.wae.agent.core.runtime.Tunables.getInt(context, "menu.candidate_tries", 2)
        for ((index, cand) in pool.withIndex()) {
            if (index >= maxTries) break
            val why = ranked.firstOrNull { it.option.label == cand.label }?.reason ?: "plain"
            android.util.Log.i("WAE_MENU", "try overflow[$index] = '" + cand.label + "' (" + why + ")")
            val before = capture()
            val tapped = act(TaskNode.Op.PICK, cand.label, "")
            if (!tapped) continue
            Thread.sleep(com.wae.agent.core.runtime.Tunables.getLong(context, "menu.open_wait_ms", 350L))
            val after = capture()
            // 成功判準：出現新的彈出層選項，或可選項數量明顯變化
            val popupAppeared = optionsOf(after).any { it.second.layer == 0 } &&
                !optionsOf(before).any { it.second.layer == 0 }
            val grew = after.controls.size > before.controls.size
            if (popupAppeared || grew) {
                android.util.Log.i("WAE_MENU", "overflow opened by '" + cand.label + "' popup=" + popupAppeared + " grew=" + grew)
                return true
            }
            // 點錯了：按返回復原，再試下一個候選
            com.wae.core.util.Degrade.orNull("menu:backOut") { service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
            Thread.sleep(200)
        }
        android.util.Log.i("WAE_MENU", "all overflow candidates failed (" + pool.size + " candidates)")
        return false
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
            // ① 先找「進入輸入模式」的入口。實測設定頁的搜尋入口常常只有圖示
            //    （label 是空的、或只有「搜」一個字），所以不能只比文字：
            //    依序看「可編輯 + 像搜尋」→「像搜尋」→「身份字串含 SEARCH_ENTRY 關鍵字」→「任何輸入框」。
            val keywords = searchEntryKeywords()
            var entry: Control? = null
            var entryNode: AccessibilityNodeInfo? = null
            var lastCount = -1
            // 最多試 10 次、每次等 500ms：剛啟動的 App 常常還沒把無障礙樹長好，
            // 太早抓會拿到幾乎空的畫面（實測 FIND 失敗都是 controls=0，不是找不到元素）。
            for (attempt in 0 until 10) {
                val frameNow = if (attempt == 0) popupFrame else capture()
                lastCount = frameNow.controls.size
                val picked = pickSearchEntry(frameNow.controls, keywords)
                if (picked != null) {
                    val node = NodeRegistry.get(picked.key)
                    if (node != null) {
                        entry = picked
                        entryNode = node
                        break
                    }
                }
                if (attempt < 9) Thread.sleep(500)
            }

            // 退路：畫面抓不到、但游標已經在某個可編輯欄位（例如搜尋框早就開著）→ 直接輸入。
            // 使用者實測就是這個情況：「它進了搜尋之後壓根沒打字」。
            if (entryNode == null) {
                val focused = com.wae.core.util.Degrade.orNull("find:focus") {
                    service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }
                if (focused != null && focused.isEditable) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
                    }
                    val ok = DeviceIO.perform(focused, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    DeviceIO.perform(
                        focused,
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
                        android.os.Bundle()
                    )
                    android.util.Log.i("WAE_TASK", "FIND 用焦點欄位直接輸入 target='$target' ok=$ok")
                    return ok
                }
            }

            if (entryNode == null) {
                android.util.Log.i("WAE_TASK", "FIND 找不到搜尋入口 controls=$lastCount")
                return false
            }
            android.util.Log.i("WAE_TASK", "FIND 入口='" + entry!!.label + "' id=" + entry.key.substringAfterLast('/'))
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

    /**
     * 決策層：在「可選項」上做選擇（PlanMatcher），並考慮負面知識與後置條件。
     *
     * 為什麼不再用「對整棵樹打一個分數」：
     *  - 純說明文字只要字面相符就會贏過真正的按鈕（實測第 19 題）。
     *  - 動作相容性沒被檢查：TYPE 可能選到按鈕、TOGGLE 可能選到不可切的列。
     *  - 失敗過的組合會一直被重試（負面知識只在舊路徑生效）。
     */
    private fun bestControl(f: Frame, target: String): Control? {
        if (f.controls.isEmpty()) return null
        val pairs = optionsOf(f)
        if (pairs.isEmpty()) return null
        val screen = com.wae.core.screen.ScreenModel(f.pkg, f.fp, pairs.map { it.second }, emptyList())

        // 負面知識：這個 (pkg, 標籤, PICK) 最近失敗過兩次以上 → 這次跳過
        val banned = HashSet<String>()
        for (pair in pairs) {
            val label = pair.second.label
            val blocked = com.wae.core.util.Degrade.orElse("gateway:banned", false) {
                com.wae.agent.core.action.NegativeEdgeStore.isBlocked(f.pkg, label, "PICK")
            }
            if (blocked) banned.add(com.wae.core.plan.PlanMatcher.banKey(f.pkg, label, "PICK"))
        }

        val boost = conceptBoost(target, pairs.map { it.second })
        val step = com.wae.core.plan.PlanStep(action = "PICK", target = target)
        val started = android.os.SystemClock.uptimeMillis()
        val candidate = com.wae.core.plan.PlanMatcher.best(
            step = step,
            screen = screen,
            scoreOf = { goal, label -> labelScore(goal, label, boost) },
            banned = banned,
            packageName = f.pkg
        )
        val elapsed = android.os.SystemClock.uptimeMillis() - started

        if (candidate == null || candidate.reason == "banned") {
            // 最近似退路：原本會直接失敗，這裡再多試一次（要求明顯領先，不亂猜）
            val nearest = com.wae.core.util.Degrade.orNull("gateway:nearest") {
                com.wae.core.screen.ConceptMatcher.nearest(
                    target,
                    pairs.map { it.second.label },
                    floor = com.wae.agent.core.runtime.Tunables.getFloat(
                        context, "match.nearest_floor", 0.45f
                    ),
                    margin = com.wae.agent.core.runtime.Tunables.getFloat(
                        context, "match.nearest_margin", 0.10f
                    )
                )
            }
            if (nearest != null) {
                val nearIdx = pairs.indexOfFirst { it.second.label == nearest.first }
                if (nearIdx >= 0) {
                    android.util.Log.i(
                        "WAE_OPTION",
                        "bestControl '$target' → nearest '${nearest.first}' dice=%.2f".format(nearest.second)
                    )
                    recordPlanTrace(f, target, pairs.size, nearest.first, nearest.second, false, elapsed)
                    return pairs[nearIdx].first
                }
            }
            android.util.Log.i(
                "WAE_OPTION",
                "bestControl '$target' → 沒得選（options=${pairs.size} banned=${banned.size}）"
            )
            recordPlanTrace(f, target, pairs.size, "", 0f, true, elapsed)
            return null
        }

        val chosen = pairs.firstOrNull { it.second.id == candidate.option.id }?.first
        android.util.Log.i(
            "WAE_OPTION",
            "bestControl '$target' → '${candidate.option.label}' score=%.2f via=${candidate.reason} of ${pairs.size}"
                .format(candidate.score)
        )
        recordPlanTrace(
            f, target, pairs.size, candidate.option.label, candidate.score, false, elapsed
        )
        return chosen
    }

    /** 計畫軌跡（P0 可觀測性）：每一步選了什麼、分數多少、花多久。 */
    private fun recordPlanTrace(
        f: Frame,
        target: String,
        optionCount: Int,
        chosen: String,
        score: Float,
        skippedByBan: Boolean,
        elapsedMs: Long
    ) {
        if (!com.wae.agent.core.runtime.Tunables.getBool(context, "plan.trace_enabled", true)) return
        com.wae.core.util.Degrade.orNull("gateway:planTrace") {
            val trace = com.wae.core.plan.PlanTrace(
                planOrigin = "PREDICATE",
                planRevision = 1,
                stepIndex = 0,
                action = "PICK",
                target = target,
                preconditionOk = f.fp.isNotEmpty(),
                optionCount = optionCount,
                chosenOption = chosen,
                score = score,
                skippedByBan = skippedByBan,
                elapsedMs = elapsedMs
            )
            val dir = java.io.File(context.filesDir, "eval").apply { mkdirs() }
            java.io.File(dir, "plan_trace.tsv").appendText(trace.toTsv() + "\n", Charsets.UTF_8)
        }
    }

    /**
     * 概念同族加分（通用）：
     * 「通話記錄」在撥號 App 只顯示「最近」時，字面永遠對不上；
     * 但同一頁還有「鍵盤／聯絡人」時，就能判斷「最近」是通話記錄。
     */
    private fun conceptBoost(target: String, options: List<com.wae.core.screen.ScreenOption>): Set<String> {
        val table = com.wae.agent.core.plan.ConceptLexicon.table(context)
        if (table.concepts.isEmpty()) return emptySet()
        val concept = com.wae.core.util.Degrade.orNull("gateway:conceptOf") { table.conceptOf(target) }?.id
        return com.wae.core.util.Degrade.orEmpty("gateway:familyLabels") {
            com.wae.core.screen.ConceptMatcher
                .familyLabelsFor(concept, options.map { it.label }, table)
                .toList()
        }.toSet()
    }

    /** 標籤分數：字面優先，其次才是同族推論（同族分數低於包含，避免壓過真正相符的標籤）。 */
    private fun labelScore(target: String, label: String, boost: Set<String>): Float {
        val direct = DecisionRanker.textScore(target, label, context)
        if (direct >= 0.85f) return direct
        val isFamily = boost.any { com.wae.core.predicate.TextFold.fold(it) == com.wae.core.predicate.TextFold.fold(label) }
        return if (isFamily) maxOf(direct, com.wae.core.screen.ConceptMatcher.FAMILY_SCORE) else direct
    }

    /** 上一幀指紋（changed 條件用）。 */
    private var previousFp: String = ""

    /**
     * 目前畫面的「事實」：套件、指紋、可選標籤、開關狀態、動作前指紋。
     * 計畫的前置／後置條件就是靠這份資料判斷。
     */
    override fun facts(): com.wae.core.plan.PlanCondition.Facts {
        val f = frame
        val pairs = com.wae.core.util.Degrade.orEmpty("gateway:facts") { optionsOf(f) }
        val labels = pairs.map { it.second.label }
        val checked = pairs.mapNotNull { p ->
            p.second.checked?.let { p.second.label to it }
        }.toMap()
        return com.wae.core.plan.PlanCondition.Facts(
            packageName = f.pkg,
            fingerprint = f.fp,
            labels = labels,
            checked = checked,
            previousFingerprint = previousFp
        )
    }

    /** 這一幀的「可選項」清單（與 OptionResolver 共用同一套抽取規則）。 */
    private fun optionsOf(f: Frame): List<Pair<Control, com.wae.core.screen.ScreenOption>> {
        val height = com.wae.core.util.Degrade.orElse("gateway:screenHeight", 0) {
            context.resources.displayMetrics.heightPixels
        }
        return com.wae.core.screen.OptionExtractor.scan(f.pkg, f.fp, f.controls, height).pairs
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
