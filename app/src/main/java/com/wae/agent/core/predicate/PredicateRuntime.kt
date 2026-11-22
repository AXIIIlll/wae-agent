package com.wae.agent.core.predicate

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.agent.core.WaeOpenSourceDatabaseRegistry
import com.wae.agent.core.WaeVisionAccessibilityService
import com.wae.agent.gen5.A11yFrameSource
import com.wae.agent.gen5.DeviceIO
import com.wae.agent.gen5.NodeRegistry
import com.wae.core.model.Frame
import com.wae.core.util.Degrade
import com.wae.core.predicate.AgentAction
import com.wae.core.predicate.NlPredicateCompiler
import com.wae.core.predicate.PredicateAgent
import com.wae.core.predicate.PredicateEval
import com.wae.core.predicate.PredicateWorld
import com.wae.core.predicate.Phi
import com.wae.core.predicate.asWorld

/**
 * 述詞導向的執行器：**目標是述詞，步驟是算出來的**。
 *
 * 這條路徑與既有管線的關係：
 *  - 它不認識任何「技能」「句型」「目的」，只認識 `Φ` 與 [AgentAction]。
 *  - 它只用既有的裝置出口（[DeviceIO]）與既有的 App 啟動器。
 *  - 成功與失敗都由 `eval(Φ, frame)` 判定——與驗證層同一套標準。
 */
object PredicateRuntime {

    enum class Status { HANDLED_OK, HANDLED_FAIL, HANDLED_ASK, NOT_HANDLED }

    data class Result(
        val status: Status,
        val message: String,
        val steps: Int = 0
    )

    private const val SETTLE_MS = 350L

    /** 一次嘗試的入口；[allowModel] 為 false 時不會叫模型。 */
    fun tryRun(
        context: Context,
        command: String,
        allowModel: Boolean = true,
        maxSteps: Int = 12,
        maxScrolls: Int = 4
    ): Result {
        Degrade.orNull("warmup:TinyBert") { TinyBert.ensureLoaded(context) }
        Degrade.orNull("warmup:DecisionRanker") { DecisionRanker.warmUp(context) }
        // ── 缺口 1：序列指令（「開 WiFi，然後開藍牙」）──
        val clauses = com.wae.core.predicate.ClauseSplitter.split(command)
        if (clauses.size > 1) {
            var last: Result = Result(Status.NOT_HANDLED, "PΦ:empty")
            for (c in clauses) {
                val r = tryRun(context, c, allowModel = false, maxSteps = maxSteps, maxScrolls = maxScrolls)
                if (r.status == Status.HANDLED_FAIL || r.status == Status.HANDLED_ASK) return r
                last = r
            }
            return Result(last.status, last.message + " (+${clauses.size} 步)", last.steps)
        }

        val (lexicon, sections) = PredicateLexiconLoader.of(context)
        val obs = AndroidObservables.lookup(context)
        val compiler = NlPredicateCompiler(lexicon)

        val compiled = compiler.compile(command)
        val source = "rules"
        val service = WaeVisionAccessibilityService.activeInstance
            ?: return Result(Status.HANDLED_FAIL, "PΦ:no_service")

        // ── ⑤ 分類器 + 槽位 → 任務樹（失敗則回退動作詞切分）──
        run {
            Degrade.orNull("classifier:load") { TaskTypeClassifier.ensureLoaded(context) }
            // **句首動詞優先**：確定性高於模型（「導航到…」一定是 NAVIGATE）
            val headType = com.wae.core.predicate.LanguageWords.typeOfHead(command)
            val pred: TaskTypeClassifier.Prediction? = if (headType != null) {
                TaskTypeClassifier.Prediction(headType, 1.0f)
            } else {
                Degrade.orNull("classifier:classify") { TaskTypeClassifier.classify(command) }
            }
            if (pred != null && pred.confidence >= TaskTypeClassifier.MIN_CONFIDENCE) {
                val app = runCatching {
                    WaeOpenSourceDatabaseRegistry
                        .resolveTargetAppFromDatabaseOrDevice(context, command)?.displayName
                }.getOrNull()
                var slots = com.wae.core.predicate.SlotExtractor.extract(command)
                // 對象若是人名 → 先查通訊錄；0 個或 ≥2 個就反問，不要猜
                if (pred.label == "TRANSFER" && !slots.person.isNullOrBlank()) {
                    val found = ContactResolver.find(context, slots.person!!)
                    when {
                        found.isEmpty() -> return Result(
                            Status.HANDLED_ASK,
                            "PΦ:ask(TASK) 我找不到「${slots.person}」這個聯絡人，要傳給誰？",
                            0
                        )
                        found.size > 1 -> return Result(
                            Status.HANDLED_ASK,
                            "PΦ:ask(TASK) 有 ${found.size} 個「${slots.person}」，是哪一個？" +
                                found.take(3).joinToString("、") { it.displayName },
                            0
                        )
                        else -> slots = slots.copy(person = found[0].displayName)
                    }
                }
                val task = com.wae.core.predicate.TaskPlanner.fromType(pred.label, slots, app)
                android.util.Log.i(
                    "WAE_TASK",
                    "classify='${pred.label}' conf=${pred.confidence} content='${slots.content}' task=${task != null}"
                )
                if (task != null) {
                    val expanded = com.wae.core.predicate.TaskExpander.expand(task)
                    val gw = A11yScreenGateway(service, context)
                    val r = com.wae.core.predicate.TaskExecutor.run(expanded, gw)
                    android.util.Log.i("WAE_TASK", "status=${r.status} steps=${r.steps.size} q='${r.question}'")
                    return when (r.status) {
                        com.wae.core.predicate.TaskExecutor.Status.DONE ->
                            Result(Status.HANDLED_OK, "PΦ:task_done(${r.steps.size})", r.steps.size)
                        com.wae.core.predicate.TaskExecutor.Status.ASK ->
                            Result(Status.HANDLED_ASK, "PΦ:ask(TASK) ${r.question}", r.steps.size)
                        com.wae.core.predicate.TaskExecutor.Status.FAILED ->
                            Result(Status.HANDLED_FAIL, "PΦ:task_failed(${r.failedAt}) ${r.question}", r.steps.size)
                    }
                }
            }
        }

        // ── 開 App + 後續動作（例如「打開地圖搜尋臺北101並點擊路線」）──
        // 以前這種句子會被編譯成 NAVIGATE 交回舊管線，結果只開 App 就結束。
        com.wae.core.predicate.TaskBuilder.fromCommand(command)?.let { task ->
            val expanded = com.wae.core.predicate.TaskExpander.expand(task)
            val gateway = A11yScreenGateway(service, context)
            val r = com.wae.core.predicate.TaskExecutor.run(expanded, gateway)
            android.util.Log.i("WAE_TASK", "status=${r.status} steps=${r.steps.size} q='${r.question}'")
            return when (r.status) {
                com.wae.core.predicate.TaskExecutor.Status.DONE ->
                    Result(Status.HANDLED_OK, "PΦ:task_done(${r.steps.size})", r.steps.size)
                com.wae.core.predicate.TaskExecutor.Status.ASK ->
                    Result(Status.HANDLED_ASK, "PΦ:ask(TASK) ${r.question}", r.steps.size)
                com.wae.core.predicate.TaskExecutor.Status.FAILED ->
                    Result(Status.HANDLED_FAIL, "PΦ:task_failed(${r.failedAt}) ${r.question}", r.steps.size)
            }
        }

        if (!compiled.operable) {
            return Result(Status.NOT_HANDLED, "PΦ:not_compiled(" + compiled.issues.joinToString(";") + ")")
        }


        // 「打開某個 App」交回既有啟動路徑：那條路徑已經處理了別名、多 process、
        // 以及各家 OEM 的套件差異。述詞路徑只負責「狀態改變」類的目標。
        if (compiled.kind == NlPredicateCompiler.GoalKind.NAVIGATE) {
            return Result(Status.NOT_HANDLED, "PΦ:navigate_delegated")
        }


        var goal: Phi? = compiled.goal
        var steps = 0
        var scrolls = 0
        var visited = HashSet<String>()
        // 已經操作過、但沒有讓目標成立（或距離沒下降）的元素；避免原地打轉
        val tried = HashSet<String>()
        var searchUsed = false

        // 相對目標（調亮一點）需要先讀當前值
        if (goal == null && compiled.relative != null) {
            val frame = capture(service, context)
            val label = compiled.relative!!.label
            val control = frame.controls.firstOrNull {
                it.range != null && (label == null || it.label.contains(label, ignoreCase = true))
            } ?: return Result(Status.HANDLED_FAIL, "PΦ:no_slider")
            val current = control.valueFraction ?: return Result(Status.HANDLED_FAIL, "PΦ:no_value")
            goal = compiler.absoluteOf(compiled.relative!!, current)
        }
        val target = goal ?: return Result(Status.HANDLED_FAIL, "PΦ:no_goal")
        val goalLabel = PredicateEval.atoms(target)
            .filterIsInstance<com.wae.core.predicate.Atom.LabelContains>()
            .firstOrNull()?.text.orEmpty()
        // 這次走過的分類入口（成功後存成記憶，下次一步到位）
        val descentPath = ArrayList<String>()

        // 換 App 類：先開，再重新評估
        var launchedOnce = false
        var launchCount = 0
        compiled.targetPkg?.let { pkg ->
            val frame = capture(service, context)
            if (!PredicateEval.eval(target, frame.asWorld(obs)) && !launchedOnce) {
                launchedOnce = true
                launchCount += 1
                val launched = runCatching {
                    if (isInstalled(context, pkg)) {
                        A11yScreenGateway(service, context).launchAndNormalize(pkg)
                    } else {
                        // 資料表寫的是 AOSP 的 `com.android.settings`，三星／小米的實際套件不同。
                        // 這裡用平台的「開啟設定」意圖，不寫死任何 OEM 名稱。
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    }
                }.getOrDefault(false)
                steps += 1
                if (launched) Thread.sleep(SETTLE_MS * 3)
            }
        }

        // ── 語意模糊守門：不夠具體就先問，不要安靜地做錯 ──
        runCatching {
            val f0 = capture(service, context)
            val verdict = com.wae.core.predicate.AmbiguityDetector.ofCompiled(compiled, f0.asWorld(obs))
            if (verdict.blocking) {
                android.util.Log.i(
                    "WAE_AMBIG",
                    "kind=${verdict.kind} score=${verdict.score} q='${verdict.question}'"
                )
                return Result(
                    Status.HANDLED_ASK,
                    "PΦ:ask(${verdict.kind}) ${verdict.question}",
                    0
                )
            }
        }

        // ── ④ 路徑記憶：以前成功走過的路，直接照走（一步到位）──
        if (goalLabel.isNotEmpty()) {
            val pkgNow = capture(service, context).pkg
            val remembered = PredicatePathMemory.recall(context, pkgNow, goalLabel)
            if (remembered.isNotEmpty()) {
                var replayOk = true
                android.util.Log.i("WAE_PHI", "memory hit '$goalLabel' -> ${remembered.joinToString(">")}")
                for (lbl in remembered) {
                    val f = capture(service, context)
                    if (PredicateEval.eval(target, f.asWorld(obs))) break
                    val c = f.controls.firstOrNull {
                        it.label.isNotBlank() && com.wae.core.predicate.TextFold.containsFold(it.label, lbl)
                    }
                    if (c == null || !tapByKey(service, c.key, f, lbl)) {
                        replayOk = false
                        break
                    }
                    steps += 1
                    Thread.sleep(SETTLE_MS)
                }
                val f = capture(service, context)
                if (PredicateEval.eval(target, f.asWorld(obs))) {
                    return Result(Status.HANDLED_OK, "PΦ:ok(memory,steps=$steps)", steps)
                }
                if (!replayOk) PredicatePathMemory.forget(context, pkgNow, goalLabel)
            }
        }

        // ── 巡覽狀態機：知道自己在哪、從哪來、去過哪 ──
        var depth = 0
        // 本次執行「看到過的畫面 → 事實」與「按下什麼 → 到哪一頁」，用來餵給規劃器
        val sessionFacts = HashMap<String, PredicateWorld>()
        val sessionEdges = ArrayList<com.wae.agent.core.automaton.AutomatonEdge>()
        var lastActionLabel = ""
        var lastFromFp = ""
        var stall = 0
        var prevDist = Int.MAX_VALUE
        var lastFp = ""
        val visitedFrames = HashSet<String>()
        val frameStack = ArrayList<String>()

        while (steps < maxSteps) {
            val frame = capture(service, context)
            if (PredicateEval.eval(target, frame.asWorld(obs))) {
                if (descentPath.isNotEmpty() && goalLabel.isNotEmpty()) {
                    PredicatePathMemory.remember(context, frame.pkg, goalLabel, descentPath)
                }
                return Result(Status.HANDLED_OK, "PΦ:ok(source=$source,steps=$steps)", steps)
            }
            // 換頁了 → 捲動額度與「試過的元素」都重新開始（新頁面是全新的）
            if (frame.fp != lastFp) {
                lastFp = frame.fp
                scrolls = 0
                tried.clear()
                visitedFrames.add(frame.fp)
            }
            // 把「這一頁長什麼樣」納入地圖
            sessionFacts[frame.fp] = frame.asWorld(obs)
            if (lastActionLabel.isNotBlank()) {
                sessionEdges.add(
                    com.wae.agent.core.automaton.AutomatonEdge(
                        from = lastFromFp.ifEmpty { frame.fp },
                        to = frame.fp,
                        op = "TAP",
                        selector = lastActionLabel,
                        ok = 1
                    )
                )
                lastActionLabel = ""
            }
            val dist = PredicateEval.distance(target, frame.asWorld(obs))
            // 缺口 3：連續兩步沒有更靠近 → 用地圖規劃一次，照規劃的第一步走
            if (dist >= prevDist) stall++ else stall = 0
            prevDist = dist
            if (stall >= 2) {
                stall = 0
                val selector = SessionPlanner.nextSelector(
                    SessionPlanner.Graph(sessionFacts, sessionEdges),
                    frame.fp,
                    target
                )
                if (selector != null) {
                    android.util.Log.i("WAE_PLAN", "planToPhi -> '$selector'")
                    tapByKey(service, "", frame, selector)
                    steps += 1
                    Thread.sleep(SETTLE_MS)
                    continue
                }
            }
            val action = PredicateAgent.next(
                PredicateAgent.Context(
                    world = frame.asWorld(obs),
                    goal = target,
                    step = steps,
                    maxSteps = maxSteps,
                    visitedSections = visited,
                    scrolls = scrolls,
                    maxScrolls = maxScrolls,
                    depth = depth,
                    maxDepth = 3,
                    frameFp = frame.fp,
                    visitedFrames = visitedFrames,
                    canGoBack = depth > 0,
                    expectedPkg = compiled.targetPkg ?: "",
                    similarity = PredicateAgent.LabelSimilarity { goal, cand ->
                        DecisionRanker.textScore(goal, cand, context)
                    },
                    // 多定義比對（P1）：能看到整個控制項，所以圖示按鈕也有機會命中；
                    // 開關預設關閉，退回上面的純文字相似度。
                    controlScorer = com.wae.agent.core.match.MatchSource.scorer(context),
                    similarityFloor = com.wae.agent.core.runtime.Tunables.getFloat(
                        context, "match.similarity_floor", 0.62f
                    ),
                    tried = tried,
                    searchUsed = searchUsed
                ),
                sections
            )
            steps += 1
            android.util.Log.i(
                "WAE_PHI",
                "step=$steps dist=$dist pkg='${frame.pkg}' n=${frame.controls.size} action=$action"
            )
            // 軌跡（只寫不讀）：之後用執行結果當標籤，量「解析對不對」不必人工標註
            ResolutionTrace.record(
                context = context,
                pkg = frame.pkg,
                frameFp = frame.fp,
                query = goalLabel,
                step = steps,
                action = action.toString(),
                candidates = frame.controls.size,
                note = "phi"
            )
            // 把「它看到的畫面」印出來：模型只讀文字，除錯也只看同一份文字
            if (steps == 1 || steps % 3 == 0) {
                android.util.Log.i(
                    "WAE_SCREEN",
                    com.wae.core.predicate.ScreenText.render(frame, maxLines = 40)
                )
            }
            when (action) {
                is AgentAction.Done -> {
                    if (descentPath.isNotEmpty() && goalLabel.isNotEmpty()) {
                        val f = capture(service, context)
                        PredicatePathMemory.remember(context, f.pkg, goalLabel, descentPath)
                    }
                    return Result(Status.HANDLED_OK, "PΦ:ok(source=$source,steps=$steps)", steps)
                }

                is AgentAction.GiveUp ->
                    return Result(Status.HANDLED_FAIL, "PΦ:give_up(${action.reason})", steps)

                is AgentAction.Tap -> {
                    lastFromFp = frame.fp
                    lastActionLabel = action.label
                    if (!tapByKey(service, action.key, frame, action.label)) {
                        tried.add(action.key)
                    } else {
                        tried.add(action.key)
                    }
                    Thread.sleep(SETTLE_MS)
                }

                is AgentAction.OpenSection -> {
                    lastFromFp = frame.fp
                    lastActionLabel = action.sectionLabel
                    visited.add(action.sectionKey)
                    tried.add(action.sectionKey)
                    if (action.sectionLabel.isNotBlank()) descentPath.add(action.sectionLabel)
                    if (tapByKey(service, action.sectionKey, frame, action.sectionLabel)) {
                        // 鑽進去了：記下層數與來源頁
                        frameStack.add(frame.fp)
                        depth += 1
                        scrolls = 0
                    } else {
                        // 找不到入口就往下捲，下一輪再試
                        scroll(service, 1)
                        scrolls += 1
                    }
                    Thread.sleep(SETTLE_MS)
                }

                is AgentAction.SetValue -> {
                    if (!setProgressByKey(service, action.key, action.fraction)) {
                        return Result(Status.HANDLED_FAIL, "PΦ:set_value_failed(${action.label})", steps)
                    }
                    Thread.sleep(SETTLE_MS)
                }

                is AgentAction.TypeSearch -> {
                    searchUsed = true
                    if (!typeIntoField(service, action.fieldKey, action.query)) {
                        tried.add(action.fieldKey)
                    }
                    Thread.sleep(SETTLE_MS * 2)
                }

                is AgentAction.Scroll -> {
                    scroll(service, action.direction)
                    scrolls += 1
                    Thread.sleep(SETTLE_MS)
                }

                is AgentAction.Back -> {
                    runCatching {
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    // 退回上一層：層數 −1，捲動與「試過的元素」重新開始
                    depth = (depth - 1).coerceAtLeast(0)
                    if (frameStack.isNotEmpty()) frameStack.removeAt(frameStack.size - 1)
                    tried.clear()
                    scrolls = 0
                    Thread.sleep(SETTLE_MS)
                }

                is AgentAction.Launch -> {
                    // 最多啟動兩次：避免「開了又被導到別的 App」造成無限重開
                    if (launchCount < 2) {
                        launchCount += 1
                        Degrade.orNull("launch:${action.pkg}") { A11yScreenGateway(service, context).launchAndNormalize(action.pkg) }
                        Thread.sleep(SETTLE_MS * 3)
                    } else {
                        tried.add(action.pkg)
                        Thread.sleep(SETTLE_MS)
                    }
                }
            }
        }
        return Result(Status.HANDLED_FAIL, "PΦ:steps_exhausted($steps)", steps)
    }

    // ── 裝置動作（全部走唯一出口）──────────────────────────────

    /**
     * 把文字打進搜尋框並送出。
     *
     * 用無障礙的 `ACTION_SET_TEXT`（不用模擬鍵盤），中文也能直接寫入；
     * 送出用 `ACTION_IME_ENTER`（API 30+，minSdk 30 剛好）。
     */
    private fun typeIntoField(service: WaeVisionAccessibilityService, key: String, text: String): Boolean {
        val node = NodeRegistry.get(key) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = DeviceIO.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return false
        runCatching {
            DeviceIO.perform(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, android.os.Bundle())
        }
        return true
    }

    private fun isInstalled(context: Context, pkg: String): Boolean =
        Degrade.orElse("isInstalled($pkg)", false) {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }

    private fun capture(service: WaeVisionAccessibilityService, context: Context): Frame =
        Degrade.orElse("capture", Frame.EMPTY) { A11yFrameSource(service, context).capture() }

    /**
     * 點一個元素。**三層備援**：
     *  1. 用 key 取節點（同一幀內有效）
     *  2. 節點本身不可點 → 往上找最近的可點祖先（圖示在列裡就是這種情況）
     *  3. key 失效（捲動／延遲載入換了座標）→ 用**標籤在這一幀重新找**
     */
    private fun tapByKey(
        service: WaeVisionAccessibilityService,
        key: String,
        frame: Frame = Frame.EMPTY,
        label: String = ""
    ): Boolean {
        NodeRegistry.get(key)?.let { node ->
            if (clickNode(service, node)) return true
        }
        // 備援：用標籤重新定位（key 含座標，捲動後就對不上了）
        if (label.isNotBlank() && frame.controls.isNotEmpty()) {
            val again = frame.controls.firstOrNull {
                it.label.isNotBlank() && com.wae.core.predicate.TextFold.containsFold(it.label, label)
            }
            if (again != null) {
                NodeRegistry.get(again.key)?.let { node ->
                    if (clickNode(service, node)) return true
                }
            }
        }
        return false
    }

    /** 點節點；不可點就往上找可點的祖先（最多 4 層）。 */
    private fun clickNode(service: WaeVisionAccessibilityService, node: AccessibilityNodeInfo): Boolean {
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

    private fun setProgressByKey(service: WaeVisionAccessibilityService, key: String, fraction: Float): Boolean {
        val node = NodeRegistry.get(key) ?: return false
        val range = node.rangeInfo ?: return false
        val target = range.min + (range.max - range.min) * fraction.coerceIn(0f, 1f)
        val args = android.os.Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target)
        }
        val setProgress = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        return DeviceIO.perform(node, setProgress, args)
    }

    private fun scroll(service: WaeVisionAccessibilityService, direction: Int) {
        runCatching {
            if (direction >= 0) {
                com.wae.agent.input.ScreenGestures.scrollDownHalf(service)
            } else {
                com.wae.agent.input.ScreenGestures.scrollUp(service)
            }
        }
    }
}
