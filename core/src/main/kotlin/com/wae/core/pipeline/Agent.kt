package com.wae.core.pipeline

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.config.Reason
import com.wae.core.contract.ContractDeriver
import com.wae.core.effect.Planner
import com.wae.core.exec.Executor
import com.wae.core.learn.Attributor
import com.wae.core.memory.Memory
import com.wae.core.model.Frame
import com.wae.core.model.Option
import com.wae.core.model.Outcome
import com.wae.core.model.Step
import com.wae.core.receipt.ReceiptRecorder
import com.wae.core.receipt.ReceiptStep
import com.wae.core.receipt.TaskReceipt
import com.wae.core.safety.Decision
import com.wae.core.safety.Safety
import com.wae.core.session.TaskSession
import com.wae.core.time.Clock
import com.wae.core.util.TextHash

/** 命令 → 效果。用介面是為了讓「動詞表」與「意圖框架」可以互換（B6/B7 的接縫）。 */
fun interface EffectSource {
    fun compile(command: String): List<com.wae.core.model.Effect>
}

/**
 * 唯一管線（Gen-5 §5）。
 *
 * ```
 * 命令 → 效果 → 每一輪：感知 → 計畫 → 安全 → 執行 → 驗證
 *                              ↑                    │
 *                              └──── 反例 → 重規劃 ──┘
 *                                  ↑        │
 *                            捲動找（Seek） 退回（Retreat）
 * ```
 *
 * 廣度不是來自「多認得幾個動詞」，而是來自這兩個結構性程序：
 *  - 目標不在這一屏 → **捲動再找**
 *  - 走到錯的畫面 → **退回再規劃**
 */
class Agent(
    private val config: Config,
    private val clock: Clock,
    private val memory: Memory,
    private val frameSource: FrameSource,
    private val compiler: EffectSource,
    private val deriver: ContractDeriver,
    private val planner: Planner,
    private val executor: Executor,
    private val safety: Safety,
    private val attributor: Attributor,
    private val receipts: ReceiptRecorder? = null,
    private val budget: com.wae.core.safety.BudgetTracker? = null,
    private val transactions: com.wae.core.exec.TransactionLog? = null,
    private val capabilities: com.wae.core.routing.CapabilityIndex? = null,
    private val routes: com.wae.core.routing.RouteSelector? = null,
    private val warmup: Boolean = false,
    private val verifier: com.wae.core.verify.CompositeVerifier = com.wae.core.verify.CompositeVerifier.NONE,
    private val transitions: com.wae.core.worldmodel.TransitionRecorder? = null,
    private val selfPkg: String? = null,
    private val appResolver: AppResolver? = null,
    private val rollback: com.wae.core.exec.Rollback? = null,
    private val programs: com.wae.core.memory.ProgramCache? = null,
    private val preconditions: Preconditions? = null,
    private val onProgress: ((String) -> Unit)? = null,
    private val sleeper: (Long) -> Unit = {}
) {

    private val settler = Settler(config, sleeper)

    private val seeker = Seeker(config)
    private val retreater = Retreater(config)

    fun run(command: String): Outcome {
        val startedAt = clock.nowMs()
        val maxReplans = config.int(Key.PLAN_MAX_REPLANS, Default.MAX_REPLANS)
        val maxScrolls = config.int(Key.SEEK_MAX_SCROLLS, Default.MAX_SCROLLS)
        val maxBacks = config.int(Key.RECOVER_MAX_BACKS, Default.MAX_BACKS)

        val session = TaskSession.create(command, maxReplans)
        val effects = compiler.compile(command)
        val receiptSteps = ArrayList<ReceiptStep>()
        var lastFrame: Frame = Frame.EMPTY
        var silent = 0
        var routeGoal: com.wae.core.routing.Goal? = null
        var routedApp: String? = null
        var executed = 0
        var executedLaunches = 0
        val commandHash = TextHash.of(command)

        fun finish(outcome: Outcome): Outcome {
            // 能力索引（G）：把「哪個 App 做哪類事、花幾步、成不成」記下來。
            // 之前這個 store 永遠只有表頭——Router 因此沒有資料可用。
            runCatching {
                val kind = goalKindOf(effects.firstOrNull())
                val appId = routedApp ?: lastFrame.pkg
                if (kind != null && appId.isNotEmpty() && appId != selfPkg) {
                    if (outcome is Outcome.Success) {
                        capabilities?.recordSuccess(appId, kind, executed)
                    } else {
                        capabilities?.recordFailure(appId, kind, executed)
                    }
                }
            }

            onProgress?.invoke("")
            // 失敗且已經動過手 → 嘗試回滾（F6），並記在收據上。
            // 例外：`panel_opened` 是「我開好面板、請你自己切」，那是預期結果，不該被回滾掉。
            val intentional = outcome is Outcome.Abstain && outcome.reason == Reason.PANEL_OPENED
            val rb = rollback
            val tx = transactions
            // 只有「開 App」的任務失敗時不要回滾——把剛打開的 App 關掉只會讓使用者困惑
            val nothingButLaunch = executed > 0 && executedLaunches == executed
            if (outcome !is Outcome.Success && !intentional && executed > 0 &&
                !nothingButLaunch && rb != null && tx != null
            ) {
                val report = runCatching { rb.run(tx) }.getOrNull()
                if (report != null && report.attempted > 0) {
                    receiptSteps.add(
                        ReceiptStep(
                            effect = "rollback:" + report.attempted,
                            means = "rolled_back=" + report.rolledBack.size,
                            outcome = if (report.rolledBack.size == report.attempted) "SUCCESS" else "PARTIAL",
                            postHeld = report.rolledBack.isNotEmpty()
                        )
                    )
                }
            }
            // 路由的回饋：成功／失敗都寫回能力索引（B13 的資料來源）
            val goal = routeGoal
            val app = routedApp
            val index = capabilities
            if (goal != null && app != null && index != null) {
                if (outcome is Outcome.Success) {
                    index.recordSuccess(app, goal.kind, receiptSteps.size)
                } else {
                    index.recordFailure(app, goal.kind, receiptSteps.size)
                }
            }
            val recorder = receipts ?: return outcome
            recorder.write(
                TaskReceipt(
                    traceId = "t:" + TextHash.of(command + "#" + startedAt),
                    commandHash = TextHash.of(command),
                    pkg = lastFrame.pkg,
                    appVersion = lastFrame.appVersion,
                    effects = effects.map { it.key() },
                    steps = receiptSteps,
                    outcome = outcomeName(outcome),
                    silentFailures = silent,
                    startedAtMs = startedAt,
                    endedAtMs = clock.nowMs()
                )
            )
            return outcome
        }

        if (effects.isEmpty()) {
            session.abstained()
            return finish(Outcome.Abstain(Reason.UNKNOWN_VERB, defaultOptions()))
        }
        session.moveTo(TaskSession.State.COMPILING)

        // 效果路由（B12）：先決定「用哪個 App 做」，再進入計畫迴圈
        val index = capabilities
        val selector = routes
        if (index != null && selector != null) {
            val goal = com.wae.core.routing.GoalExtractor.of(effects)
            // 「打開某個 App」不是結果路由的工作：使用者已經指名了 App，
            // 再讓路由去挑一個「成功率最高的 App」會打開錯的東西（實測踩過）。
            if (goal != null && goal.kind != com.wae.core.routing.GoalKind.OPEN_ITEM) {
                val probe = frameSource.capture()
                lastFrame = probe
                memory.rememberFrame(probe)
                val route = selector.select(goal, probe.pkg, memory.constraintsFor(probe))
                if (route != null && route.appId != probe.pkg) {
                    routeGoal = goal
                    routedApp = route.appId
                    session.moveTo(TaskSession.State.PLANNING)
                    session.moveTo(TaskSession.State.EXECUTING)
                    val launch = Step(
                        effect = com.wae.core.model.Effect("app:" + route.appId, com.wae.core.model.Op.NAVIGATE),
                        means = com.wae.core.model.Means.Intent(
                            action = com.wae.core.effect.Planner.ACTION_MAIN,
                            data = route.appId
                        ),
                        post = com.wae.core.exec.PostConditions.pkgChanged(probe.pkg),
                        risk = com.wae.core.model.Risk.LOW,
                        idempotencyKey = "route:" + route.appId
                    )
                    val launched = executor.run(launch)
                    session.moveTo(TaskSession.State.VERIFYING)
                    settler.awaitChange(probe) { frameSource.capture() }
                    session.backToPlanning()
                    if (!launched.isSuccess) {
                        session.abstained()
                        return finish(Outcome.Abstain(Reason.ROUTE_LAUNCH_FAILED, defaultOptions()))
                    }
                } else if (route != null) {
                    routeGoal = goal
                    routedApp = route.appId
                }
            }
        }

        if (session.state != TaskSession.State.PLANNING) {
            session.moveTo(TaskSession.State.PLANNING)
        }

        var rounds = 0
        var scrolls = 0
        var retreats = 0
        val triedPreconditions = HashSet<String>()
        var waitedForLoad = false
        var queryWaits = 0
        var timingFailures = 0
        var taskBans = com.wae.core.model.Constraints.NONE
        val maxRounds = maxReplans + maxScrolls + maxBacks + 1

        while (rounds < maxRounds) {
            rounds++

            val frame = frameSource.capture()
            lastFrame = frame
            memory.rememberFrame(frame)

            // 目標已經達成了嗎？（例：App 已在前景，只是它自己跳出登入促銷頁）
            // 達成就不該因為「姿態像登入牆」而棄權——那是把成功誤判成失敗。
            if (isGoalAlreadySatisfied(effects, frame)) {
                // 狀態機的 DONE 只能從 VERIFYING 進來；這裡是「一開始就成立」，
                // 所以照合法的路徑走一次，而不是硬跳（硬跳會丟例外 → UNKNOWN）。
                runCatching {
                    session.moveTo(TaskSession.State.EXECUTING)
                    session.moveTo(TaskSession.State.VERIFYING)
                    session.done()
                }
                return finish(Outcome.Success)
            }

            // 系統對話框（權限、安裝…）蓋在目標 App 上：不亂點，直接交還給人
            if (isSystemDialog(frame.pkg)) {
                session.abstained()
                return finish(Outcome.Abstain(Reason.PERMISSION_MISSING, defaultOptions()))
            }

            // 姿態守衛（B17）：登入／密碼頁不自動操作，直接交還給人
            when (com.wae.core.learn.PostureClassifier.classify(frame)) {
                com.wae.core.learn.Posture.LOGIN_WALL -> {
                    session.abstained()
                    return finish(Outcome.Abstain(Reason.LOGIN_WALL, defaultOptions()))
                }
                // 系統權限對話框：只有人能決定，代理人不代按
                com.wae.core.learn.Posture.PERMISSION_WALL -> {
                    session.abstained()
                    return finish(Outcome.Abstain(Reason.PERMISSION_MISSING, defaultOptions()))
                }
                else -> Unit
            }
            val contracts = deriver.derive(frame)
            val constraints = memory.constraintsFor(frame) + taskBans
            // 路徑快取（C7）：同一個指令、同一支 App、同一版本 → 上次成功的手段優先
            val preferred = programs?.hintsFor(commandHash, frame.pkg, frame.appVersion).orEmpty()
            val plan = planner.plan(effects, frame, contracts, constraints, preferred)
            if (plan.notes.isNotEmpty()) {
                onProgress?.invoke("plan notes=" + plan.notes.joinToString("|"))
            }

            if (plan.steps.isEmpty()) {
                // 一般前置條件（C2）：這個效果需要先在哪個 App，就先走過去再重規劃。
                val nav = preconditions?.navigationFor(effects, triedPreconditions)
                if (nav != null) {
                    val launchEffect = com.wae.core.model.Effect("app:" + nav.appLabel, com.wae.core.model.Op.NAVIGATE)
                    val step = planner.appLaunchStep(launchEffect, nav.appLabel, frame)
                    if (step != null) {
                        triedPreconditions.add(nav.key)
                        executeQuietly(session, step)
                        receiptSteps.add(
                            ReceiptStep(step.effect.key(), step.means.describe(), "precondition", true)
                        )
                        continue
                    }
                }

                // 給了前置條件之後，畫面常常還沒載入完；等一次再重規劃（只等一次）
                if (!waitedForLoad) {
                    waitedForLoad = true
                    sleeper(config.long(Key.SETTLE_INTERVAL_MS, Default.SETTLE_INTERVAL_MS) * 12)
                    continue
                }

                // 搜尋類效果需要「欄位出現」才輸入得到；畫面常常還在轉場，等多幾次再重規劃。
                val waitingForQueryField = effects.any { it.target.startsWith("query:") } && queryWaits < MAX_QUERY_WAITS
                if (waitingForQueryField) {
                    queryWaits++
                    sleeper(config.long(Key.SETTLE_INTERVAL_MS, Default.SETTLE_INTERVAL_MS) * 6)
                    continue
                }

                // 彈窗守衛（B16）：只偵測、不自動關；請人看一眼再決定。
                // 自己的介面不算彈窗（否則第一次執行就會被自己的首頁擋住）。
                if (frame.pkg != selfPkg && com.wae.core.learn.PopupGuard.likelyDialog(frame)) {
                    session.abstained()
                    return finish(Outcome.Abstain(Reason.POPUP_SUSPECTED, defaultOptions()))
                }
                val scroll = if (seeker.canSeek(frame, scrolls)) seeker.scrollStep(frame) else null
                if (scroll != null) {
                    scrolls++
                    executeQuietly(session, scroll)
                    receiptSteps.add(ReceiptStep(scroll.effect.key(), scroll.means.describe(), "aux_scroll", true))
                    continue
                }
                session.abstained()
                return finish(Outcome.Abstain(Reason.NO_CANDIDATE, defaultOptions()))
            }

            var failedStep: Step? = null

            for (step in plan.steps) {
                // 合約預算（C4）：步數／時間／高風險次數／禁用效果
                if (budget != null) {
                    when (budget.check(step)) {
                        com.wae.core.safety.BudgetDecision.ALLOWED -> Unit
                        com.wae.core.safety.BudgetDecision.DENIED_FORBIDDEN -> {
                            session.abstained()
                            return finish(Outcome.Abstain(Reason.FORBIDDEN_EFFECT, defaultOptions()))
                        }
                        else -> {
                            session.abstained()
                            return finish(Outcome.Abstain(Reason.BUDGET_EXCEEDED, defaultOptions()))
                        }
                    }
                }

                when (safety.decide(step, constraints, warmup)) {
                    Decision.BLOCK -> {
                        session.abstained()
                        return finish(Outcome.Abstain(Reason.SENSITIVE_OPERATION, defaultOptions()))
                    }
                    Decision.CONFIRM -> {
                        session.abstained()
                        return finish(Outcome.Abstain(Reason.SENSITIVE_OPERATION, confirmOptions()))
                    }
                    Decision.SAFE -> Unit
                }

                session.moveTo(TaskSession.State.EXECUTING)
                // H5：讓使用者看得到「它現在在做第幾步」；同時把選中的手段記進 log（除錯用）
                onProgress?.invoke(
                    "第 " + (executed + 1) + " 步：" + shortLabel(step.effect.key()) +
                        " via " + step.means.describe()
                )
                val outcome = executor.run(step)
                if (outcome is Outcome.Success && step.effect.target.startsWith("app:")) {
                    executedLaunches++
                }

                session.moveTo(TaskSession.State.VERIFYING)
                // 等畫面真的變了再驗證（否則第一幀是舊畫面，會誤判成靜默失敗）
                // 開 App 的冷啟動比換頁慢很多：給它獨立的等待上限（只等到畫面變為止）。
                // 開 App 有專屬快路徑：只要前景套件變成目標就立刻回傳，
                // 不要等「任何畫面變化」等到 timeout（那是「開了之後卡一下」的主因）。
                val isLaunch = step.effect.target.startsWith("app:")
                val after = if (isLaunch) {
                    awaitLaunch(frame, step.effect)
                } else {
                    settler.awaitChange(frame) { frameSource.capture() }
                }
                lastFrame = after
                memory.rememberFrame(after)

                // 這個手段在這台裝置上根本不存在（沒有節點／沒有權限）：
                // 不是 App 的問題，是本任務換一條路（B2：節點 → 座標手勢）
                if (outcome is Outcome.Unknown && outcome.reason.startsWith(Reason.MEANS_NOT_SUPPORTED)) {
                    taskBans = taskBans + com.wae.core.model.Constraints(
                        banned = setOf(
                            com.wae.core.model.Constraints.pair(step.effect.key(), step.means.describe())
                        )
                    )
                    receiptSteps.add(
                        ReceiptStep(step.effect.key(), step.means.describe(), "UNSUPPORTED", false)
                    )
                    // 交給迴圈尾端統一重規劃（這裡不要再自己做一次，否則狀態機會亂）
                    failedStep = step
                    break
                }

                // E1：每一輪正常的執行本來就是一條 (state, action, next state)，順手留下來
                transitions?.record(
                    com.wae.core.worldmodel.Transition(
                        pkg = after.pkg.ifEmpty { frame.pkg },
                        appVersion = after.appVersion,
                        actionKey = step.means.describe(),
                        before = com.wae.core.worldmodel.Observation(
                            fp = frame.fp, pkg = frame.pkg, controlCount = frame.controls.size
                        ),
                        after = com.wae.core.worldmodel.Observation(
                            fp = after.fp, pkg = after.pkg, controlCount = after.controls.size
                        ),
                        reportedSuccess = outcome.isSuccess
                    )
                )

                val postHeld = step.post.holds(after)
                receiptSteps.add(
                    ReceiptStep(
                        effect = step.effect.key(),
                        means = step.means.describe(),
                        outcome = outcomeName(outcome),
                        postHeld = postHeld
                    )
                )

                if (outcome.isSuccess && postHeld) {
                    timingFailures = 0
                    memory.noteStepSuccess(step, after)
                    budget?.consume(step)
                    executed++
                    programs?.record(commandHash, frame.pkg, frame.appVersion, step.effect.key(), step.means.describe())
                    transactions?.record(step, frame.fp, after.fp, com.wae.core.exec.TransactionLog.reverseOf(step, frame.fp))
                    continue
                }

                // 三通道驗證（C2）：螢幕沒看到，但別的通道說它發生了
                if (!postHeld) {
                val channel = verifier.corroborate(step, after, startedAt)
                    if (channel != null && outcome.isSuccess) {
                        receiptSteps.add(
                            ReceiptStep(step.effect.key(), step.means.describe(), "CHANNEL:$channel", false, "CHANNEL:$channel")
                        )
                        memory.noteStepSuccess(step, after)
                        budget?.consume(step)
                        continue
                    }
                }

                val attribution = attributor.attribute(step, outcome, frame, after)
                // 時序類失敗（動畫、載入）先等一下再重規劃；立刻重試只會撞到同一幀。
                // 連續失敗時指數退避：400 → 800 → 1600 → 3200 ms（上限 4 級），
                // 因為「等一次不夠」通常代表它需要更久，而不是需要重試更密集。
                if (attribution == com.wae.core.model.Attribution.TIMING) {
                    timingFailures++
                    val shift = (timingFailures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
                    val delay = config.long(Key.SETTLE_INTERVAL_MS, Default.SETTLE_INTERVAL_MS) *
                        TIMING_BACKOFF_BASE * (1L shl shift)
                    sleeper(delay)
                } else {
                    timingFailures = 0
                }
                if (outcome.isSuccess) {
                    silent++
                    memory.recordSilentFailure(step, after)
                }
                memory.recordFailure(step, outcome, attribution, after)
                // 這條路在這台機器上失敗了 → 下次別再優先走它
                programs?.forget(commandHash, frame.pkg, frame.appVersion, step.effect.key())
                executor.forget(step)
                receiptSteps.add(
                    ReceiptStep(step.effect.key(), step.means.describe(), outcomeName(outcome), postHeld, attribution.name)
                )
                failedStep = step

                if (outcome is Outcome.WrongEffect && retreater.canRetreat(retreats)) {
                    retreats++
                    executeQuietly(session, retreater.backStep(after.fp))
                }
                break
            }

            if (failedStep == null) {
                // 效果層驗證（B19）：每一步都成功 ≠ 目標達成。
                // 只有「從畫面驗得出來且不成立」才算失敗；驗不出來的效果不猜。
                val verified = effects.mapNotNull { effect ->
                    com.wae.core.effect.EffectVerifier.verify(effect, lastFrame, appResolver)?.let { effect to it }
                }
                val failedEffect = verified.firstOrNull { !it.second }
                if (failedEffect == null) {
                    // 系統面板（Wi-Fi／藍牙）：我開好了，剩下要你自己切一下
                    val panelEffect = effects.firstOrNull { it.target.startsWith("panel:") }
                    if (panelEffect != null) {
                        session.abstained()
                        return finish(
                            Outcome.Abstain(
                                Reason.PANEL_OPENED,
                                listOf(Option("done", "知道了"))
                            )
                        )
                    }
                    session.done()
                    return finish(Outcome.Success)
                }
                val effect = failedEffect.first
                val synthetic = Step(
                    effect = effect,
                    means = com.wae.core.model.Means.SystemApi("verify:" + effect.key()),
                    post = { true },
                    idempotencyKey = "verify:" + effect.key()
                )
                memory.recordFailure(synthetic, Outcome.NoEffect, com.wae.core.model.Attribution.CONTRACT, lastFrame)
                if (!session.replan()) {
                    session.abstained()
                    return finish(Outcome.Abstain(Reason.REPLANS_EXHAUSTED, defaultOptions()))
                }
                continue
            }
            if (!session.replan()) {
                session.abstained()
                return finish(Outcome.Abstain(Reason.REPLANS_EXHAUSTED, defaultOptions()))
            }
        }

        session.abstained()
        return finish(Outcome.Abstain(Reason.REPLANS_EXHAUSTED, defaultOptions()))
    }

    /** 輔助步驟（捲動／退回）不需要逐個驗證後置條件：它們的後置條件就是「畫面變了」。 */
    private fun executeQuietly(session: TaskSession, step: Step) {
        session.moveTo(TaskSession.State.EXECUTING)
        executor.run(step)
        session.moveTo(TaskSession.State.VERIFYING)
        session.backToPlanning()
    }

    private fun outcomeName(outcome: Outcome): String = when (outcome) {
        is Outcome.Success -> "SUCCESS"
        is Outcome.NoEffect -> "NO_EFFECT"
        is Outcome.WrongEffect -> "WRONG_EFFECT"
        is Outcome.Blocked -> "BLOCKED"
        is Outcome.Abstain -> "ABSTAIN:" + outcome.reason
        is Outcome.Unknown -> "UNKNOWN:" + outcome.reason
    }

    /**
     * 等「目標 App 真的到前景」。
     *
     * 為什麼不沿用 settle：settle 等的是「任何畫面變化」，開 App 時常出現
     * 同一個畫面的重繪（或 splash → 首頁），會一路等到 timeout（12 秒），
     * 使用者就會覺得「開了之後卡住」。
     */
    private fun awaitLaunch(before: Frame, effect: com.wae.core.model.Effect): Frame {
        val timeoutMs = config.long(Key.LAUNCH_SETTLE_TIMEOUT_MS, Default.LAUNCH_SETTLE_TIMEOUT_MS)
        val intervalMs = config.long(Key.SETTLE_INTERVAL_MS, Default.SETTLE_INTERVAL_MS)
            .coerceIn(80L, 300L)
        val label = effect.target.removePrefix("app:")
        var waited = 0L
        var current = frameSource.capture()
        while (waited < timeoutMs) {
            val reached = current.pkg != before.pkg ||
                (appResolver?.matches(current.pkg, label) == true)
            if (reached) return current
            sleeper(intervalMs)
            waited += intervalMs
            current = frameSource.capture()
        }
        return current
    }

    /**
     * 效果是否「已經成立」：全部都不是 false，且至少一個明確為 true。
     * 驗不出來的（回 null）不算成立，也不算失敗。
     */
    private fun isGoalAlreadySatisfied(
        effects: List<com.wae.core.model.Effect>,
        frame: com.wae.core.model.Frame
    ): Boolean {
        if (effects.isEmpty()) return false
        var anyTrue = false
        for (effect in effects) {
            when (com.wae.core.effect.EffectVerifier.verify(effect, frame, appResolver)) {
                true -> anyTrue = true
                false -> return false
                null -> Unit
            }
        }
        return anyTrue
    }

    /** 是不是系統對話框（用資料表設定的套件名片段判斷，不寫死）。 */
    private fun isSystemDialog(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val hints = config.str(Key.DIALOG_PKG_HINTS, Default.DIALOG_PKG_HINTS)
        return hints.split(',')
            .map { it.trim() }
            .any { it.isNotEmpty() && pkg.contains(it, ignoreCase = true) }
    }

    /** 效果 → 能力類別（只記 App 內可重複的操作；系統動作不算能力）。 */
    private fun goalKindOf(effect: com.wae.core.model.Effect?): com.wae.core.routing.GoalKind? =
        when {
            effect == null -> null
            effect.target.startsWith("app:") -> com.wae.core.routing.GoalKind.OPEN_ITEM
            effect.target.startsWith("query:") -> com.wae.core.routing.GoalKind.SEARCH
            effect.target.startsWith("toggle:") -> com.wae.core.routing.GoalKind.TOGGLE
            effect.target.startsWith("contact:") -> com.wae.core.routing.GoalKind.SEND_TEXT
            effect.target.startsWith("share:text:") -> com.wae.core.routing.GoalKind.SEND_TEXT
            effect.target.startsWith("share:") -> com.wae.core.routing.GoalKind.SEND_MEDIA
            else -> null
        }

    /** 給進度條用的短標籤（不顯示原始內容，只顯示「做什麼」）。 */
    private fun shortLabel(effectKey: String): String = when {
        effectKey.startsWith("NAVIGATE:app:") -> "開啟 App"
        effectKey.startsWith("NAVIGATE:panel:") -> "系統面板"
        effectKey.startsWith("NAVIGATE:global:") -> "系統動作"
        effectKey.startsWith("SELECT:query:") -> "搜尋"
        effectKey.startsWith("SET:volume:") -> "調整音量"
        effectKey.startsWith("SET:brightness:") -> "調整亮度"
        effectKey.startsWith("SUBMIT:contact:") -> "傳訊息"
        effectKey.startsWith("SUBMIT:capture:") -> "拍照"
        effectKey.startsWith("SUBMIT:share:") -> "分享"
        effectKey.startsWith("SET:toggle:") -> "切換開關"
        else -> "執行"
    }

    private fun defaultOptions(): List<Option> = listOf(
        Option(OPTION_RETRY, "換個方式再試"),
        Option(OPTION_TEACH, "教我按")
    )

    private fun confirmOptions(): List<Option> = listOf(
        Option(OPTION_CONFIRM, "我確認，繼續"),
        Option(OPTION_CANCEL, "取消")
    )

    companion object {
        const val OPTION_RETRY = "retry"
        const val OPTION_TEACH = "teach"
        const val OPTION_CONFIRM = "confirm"
        const val OPTION_CANCEL = "cancel"

        /** 時序失敗的退避基準倍數（乘以 settle interval，再乘 2 的次方）。 */
        private const val TIMING_BACKOFF_BASE = 4L

        /**
         * 指數退避最多翻幾次（400 → 800 ms）。
         *
         * 實測：翻到 1600/3200 會把「開 App 需要等一下」的任務拖過時間預算，
         * 反而讓本來會成功的任務變成棄權。退避的目的是「不要撞同一幀」，不是「等到天荒地老」。
         */
        private const val MAX_BACKOFF_SHIFT = 1

        /** 搜尋欄位最多等幾次（每次都重抓畫面，不是盲等）。 */
        private const val MAX_QUERY_WAITS = 3
    }
}
