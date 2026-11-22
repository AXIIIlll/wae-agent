package com.wae.agent.gen5

import android.content.Context
import android.util.Log
import com.wae.agent.core.WaeVisionAccessibilityService
import com.wae.core.config.BundledData
import com.wae.core.config.Config
import com.wae.core.config.ConfigParser
import com.wae.core.config.FileName
import com.wae.core.config.MapConfig
import com.wae.core.config.Reason
import com.wae.core.contract.ContractDeriver
import com.wae.core.effect.IntentCompiler
import com.wae.core.effect.Planner
import com.wae.core.exec.Executor
import com.wae.core.frame.FrameLedger
import com.wae.core.intent.EffectCompiler
import com.wae.core.intent.FrameParser
import com.wae.core.intent.RoleLexicon
import com.wae.core.learn.Attributor
import com.wae.core.memory.FileMemory
import com.wae.core.means.MeansSolver
import com.wae.core.model.Outcome
import com.wae.core.pipeline.Agent
import com.wae.core.pipeline.EffectSource
import com.wae.core.pipeline.FrameSource
import com.wae.core.receipt.ReceiptRecorder
import com.wae.core.safety.Safety
import com.wae.core.time.Clock
import java.io.File

/**
 * Gen-5 的組裝點（唯一 DI 點）。
 *
 * 這一版把「意圖框架 → 效果」、「畫面帳本」、「任務收據」、「OCR 通道」都接上，
 * 但仍**不改變**使用者看到的行為：沒有被明確呼叫時，App 走的是 Gen-4 舊路徑。
 */
object Gen5Runtime {

    /** 「再做一次」用的檔（只存一行指令，不存任何畫面內容）。 */
    private fun lastCommandFile(context: android.content.Context) =
        java.io.File(context.filesDir, "last_command.txt")

    private fun rememberLastCommand(context: android.content.Context, command: String) {
        runCatching { lastCommandFile(context).writeText(command, Charsets.UTF_8) }
    }

    private fun readLastCommand(context: android.content.Context): String? =
        runCatching {
            val f = lastCommandFile(context)
            if (!f.exists()) null else f.readText(Charsets.UTF_8).trim().ifEmpty { null }
        }.getOrNull()

    /** 「不是這個」「錯了」：把上一次那一步禁掉。 */
    private fun banLastAction(
        context: android.content.Context,
        store: com.wae.core.memory.MemoryBundle
    ): Boolean {
        return runCatching {
            val file = java.io.File(context.filesDir, "mem/receipts.tsv")
            if (!file.exists()) return@runCatching false
            val last = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.lastOrNull()
            } ?: return@runCatching false
            val cells = last.split('\t')
            if (cells.size < 6) return@runCatching false
            val frameFp = cells[1]
            val steps = cells[5]
            // 取「最後一個後置條件成立的步驟」——那才是使用者看到的那一下
            val step = steps.split(';').lastOrNull { it.split('|').getOrNull(3) == "1" }
                ?: return@runCatching false
            val parts = step.split('|')
            if (parts.size < 2) return@runCatching false
            store.memory.ban(effectKey = parts[0], means = parts[1], frameFp = frameFp)
        }.getOrDefault(false)
    }

    /** 讀上一次的結果文字（`gen5_result.txt` 的最後一行，去掉指令前綴）。 */
    private fun readLastResultText(context: android.content.Context): String? {
        return runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching null
            val file = java.io.File(dir, "gen5_result.txt")
            if (!file.exists()) return@runCatching null
            val line = file.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() }
                ?: return@runCatching null
            val text = line.substringAfter('\t', "").trim()
            text.ifEmpty { null }
        }.getOrNull()
    }

    private fun copyToClipboard(context: android.content.Context, text: String): Boolean {
        return runCatching {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WAE", text))
            true
        }.getOrDefault(false)
    }

    /** 首頁至少要有的可點元素數（太少就當空白／載入中）。 */
    private const val CONTENT_MIN = 3

    private const val TAG = "WAE_GEN5"

    /** 述詞快速路徑開關（可用 Tunables 覆寫） */
    private const val PREDICATE_ENABLED_KEY = "predicate.enabled"
    private const val ASSET_TUNABLES = "open_source_ai_databases/ui_tunables.tsv"
    private const val ASSET_LEXICON = "open_source_ai_databases/role_lexicon.tsv"
    private const val CAPABILITY_FILE = "capabilities.tsv"
    private const val TRANSITIONS_FILE = "transitions.tsv"
    private const val GEN5_KEY = "agent.gen5.enabled"

    /**
     * 取消／否定的句型。
     *
     * 刻意「寧可太寬」：這裡的誤判是把「不要傳訊息」當取消（正確），
     * 反向誤判則是「使用者說不要，系統照做」——後果嚴重得多。
     */
    /** 複製上一次的結果：「複製結果」「複製上一次的結果」。 */
    private val COPY_RESULT_PATTERN = Regex("^(複製結果|複製上一次的結果|複製上次結果)$")

    /** 使用者糾正：「不是這個」「不是它」「錯了」「換一個方式」。 */
    private val CORRECTION_PATTERN = Regex("^(不是這個|不是它|不是這家|錯了|換一個方式|換一種方式)$")

    /** 「再做一次」的句型：完全比對，避免吃掉一般指令。 */
    private val REPEAT_PATTERN = Regex("^(再做一次|再來一次|重複|重做|再一次)$")

    /** 查詢通知的句型（不是動作，只是把最近的內容講出來）。 */
    private val NOTIFICATION_QUERY =
        Regex(".*(有什麼通知|有甚麼通知|有哪些通知|讀通知|最新通知|通知內容).*")

    private val CANCEL_PATTERN = Regex("^(取消|不要|不用|不必|算了|停止|停下來|別|先不要).*")

    /** 系統權限對話框的套件名片段（各版本/廠牌略有不同，用包含比對）。 */
    private const val PERMISSION_CONTROLLER = "permissioncontroller"

    /** 一次 Gen-5 執行的結果（給 UI 用）。 */
    data class RunResult(
        val outcome: Outcome,
        val summary: String,
        val stepsUsed: Int
    )

    fun isEnabled(context: Context): Boolean =
        com.wae.agent.core.runtime.Tunables.getBool(context, GEN5_KEY, true)

    fun run(context: Context, command: String): String = runDetailed(context, command).summary

    fun runDetailed(context: Context, command: String): RunResult {
        // **語料收集**：把每一次真實指令寫進 commands.jsonl（一行一筆）。
        // 為什麼：模型路線需要「真實標註句」，而我們現在是 0 筆。
        // 先累積原文，之後再用規則粗標 + 人工抽驗，才有真資料可訓練。
        runCatching {
            java.io.File(context.filesDir, "commands.jsonl").appendText(
                "{\"t\":${System.currentTimeMillis()},\"c\":\"" +
                    command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}\n"
            )
        }

        val service = WaeVisionAccessibilityService.activeInstance
            ?: return RunResult(Outcome.Unknown("no_service"), "NO_SERVICE", 0)

        // ── 述詞快速路徑（通用代理）──────────────────────────────
        // 這條路徑不認識任何技能：它把指令編譯成「目標述詞」，再由 PredicateAgent 算出動作。
        // 只有「編譯得出述詞」的指令才會被它接手；其餘照舊往下走既有管線。
        if (com.wae.agent.core.runtime.Tunables.getBool(context, PREDICATE_ENABLED_KEY, true)) {
            val fast = com.wae.agent.core.predicate.PredicateRuntime.tryRun(context, command)
            when (fast.status) {
                com.wae.agent.core.predicate.PredicateRuntime.Status.HANDLED_OK -> {
                    Log.i(TAG, "predicate ok '${fast.message}'")
                    return RunResult(Outcome.Success, fast.message, fast.steps)
                }
                com.wae.agent.core.predicate.PredicateRuntime.Status.HANDLED_ASK -> {
                    // 語意模糊：**不要動手**，把問題原樣交給使用者
                    Log.i(TAG, "predicate ask '${fast.message}'")
                    return RunResult(
                        Outcome.Abstain(
                            Reason.MISSING_SLOT,
                            listOf(com.wae.core.model.Option("ok", "好"))
                        ),
                        // 給使用者看的是「問句」，不是內部前綴（PΦ:ask(...)）
                        fast.message.substringAfter(") ", fast.message),
                        fast.steps
                    )
                }
                com.wae.agent.core.predicate.PredicateRuntime.Status.HANDLED_FAIL -> {
                    // **不吞掉指令**：述詞路徑失敗時要讓既有管線接手。
                    // 之前這裡直接回 Abstain，結果「打開設定」這類本來會成功的話被吃掉，
                    // 使用者看到的現象就是「什麼都不做」。
                    Log.i(TAG, "predicate fail (fallback to legacy) '${fast.message}'")
                    Unit
                }
                com.wae.agent.core.predicate.PredicateRuntime.Status.NOT_HANDLED -> Unit
            }
        }

        // ── 再做一次（H6 的最小版）──────────────────────────────────
        // 記住「上一次成功的指令」，讓「再做一次」真的有意義。
        if (REPEAT_PATTERN.matches(command.trim())) {
            val last = readLastCommand(context)
            if (last == null) {
                val text = "ABSTAIN reason=no_candidate steps=0 repeat=none"
                Log.i(TAG, "result=$text command=$command")
                return RunResult(
                    Outcome.Abstain(Reason.NO_CANDIDATE, listOf(com.wae.core.model.Option("ok", "知道了"))),
                    text,
                    0
                )
            }
            Log.i(TAG, "repeat last='$last'")
            return runDetailed(context, last)
        }

        // ── 通知查詢（D12 的半自動版）────────────────────────────────
        // 直接回覆通知需要 RemoteInput（第三方受限），所以先做「讀出來給你看」；
        // 要真的回，請用「打開訊息」進 App 回——那是完整可行的路徑。
        if (NOTIFICATION_QUERY.containsMatchIn(command)) {
            val items = NotificationInbox.recent(5)
            val body = if (items.isEmpty()) {
                "目前沒有收到通知（我只記得到最近的幾則）。"
            } else {
                items.joinToString("\n")
            }
            val text = "ABSTAIN reason=info notifications=" + items.size
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.INFO, listOf(com.wae.core.model.Option("ok", "知道了"))),
                text,
                0
            )
        }

        // ── 取消與否定（B10）──────────────────────────────────────────
        // 「取消」「不要」「算了」= 什麼都不做。這比任何能力都重要：
        // 講錯話能收回，才敢用。
        if (CANCEL_PATTERN.containsMatchIn(command)) {
            FollowUp.clear(context)
            val text = "ABSTAIN reason=cancelled steps=0"
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.CANCELLED, listOf(com.wae.core.model.Option("ok", "好"))),
                text,
                0
            )
        }

        // ── 多輪補槽（B11）────────────────────────────────────────────
        // 先看有沒有「上一輪反問、等答案」的缺口；有的話把這句話接回去。
        val pending = FollowUp.take(context)
        if (pending != null && FollowUp.looksLikeAnswer(command)) {
            val composed = pending.template.replace("{slot}", FollowUp.normalizeAnswer(command))
            Log.i(TAG, "followup answer='$command' -> '$composed'")
            return runDetailed(context, composed)
        }

        // 「傳訊息」沒說對象：反問，並記住要接哪一句
        val missingRecipient = Regex("^傳(?:送)?(?:訊息|簡訊)$").matches(command.trim())
        if (missingRecipient) {
            FollowUp.remember(context, template = "傳訊息給{slot}", question = "要傳給誰？")
            val text = "ABSTAIN reason=missing_slot steps=0 question=要傳給誰"
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.MISSING_SLOT, listOf(com.wae.core.model.Option("answer", "直接回名字"))),
                text,
                0
            )
        }

        // ── 複製上一次的結果（D14 的使用者版）────────────────────────
        // 為什麼是「上一次的結果」而不是「目前畫面」：代理人執行時，前景是我們自己，
        // 讀「目前畫面」只會複製到自己的 UI。複製結果才是使用者真的想要的東西。
        if (COPY_RESULT_PATTERN.matches(command.trim())) {
            val text = readLastResultText(context)
            if (text == null) {
                val out = "ABSTAIN reason=no_candidate steps=0 copy=none"
                Log.i(TAG, "result=$out command=$command")
                return RunResult(
                    Outcome.Abstain(Reason.NO_CANDIDATE, listOf(com.wae.core.model.Option("ok", "知道了"))),
                    out,
                    0
                )
            }
            val copied = copyToClipboard(context, text)
            val out = "ABSTAIN reason=info copy=" + (if (copied) "ok" else "failed") + " chars=" + text.length
            Log.i(TAG, "result=$out command=$command")
            return RunResult(
                Outcome.Abstain(Reason.INFO, listOf(com.wae.core.model.Option("ok", "知道了"))),
                out,
                0
            )
        }

        // ── 簡體 → 繁體（打字的簡體使用者）──────────────────────────
        val normalized = SimplifiedToTraditional.normalize(context, command)
        if (normalized != command) {
            Log.i(TAG, "simplified '$command' -> '$normalized'")
            return runDetailed(context, normalized)
        }

        // ── 條件句（C8 最小版）──────────────────────────────────────
        // 「如果開著就關掉 Wi-Fi」→ 剝成「關掉 Wi-Fi」；狀態判斷交給執行期的畫面。
        if (com.wae.core.intent.Conditional.isConditional(command)) {
            val stripped = com.wae.core.intent.Conditional.strip(command)
            Log.i(TAG, "conditional '$command' -> '$stripped'")
            return runDetailed(context, stripped)
        }

        val config = loadConfig(context)
        val clock = object : Clock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }
        val memDir = File(context.filesDir, FileName.MEMORY_DIR)
        // D2：所有記憶體從同一個組裝點來（不再各模組自己 new）
        val store = com.wae.core.memory.MemoryBundle(memDir, config, clock)

        // ── 規則引擎（v1）────────────────────────────────────────────
        // 三條路：① 操作層（ops）② 既有指令（cmd）③ 退回舊分支
        // 規則說「模糊／沒命中／做不了」時，一律往下走舊分支（永遠有退路）。
        var ruleResult: RunResult? = null
        if (RuleEngineHost.enabled && !RuleEngineHost.bypass) {
            val facts = RuleEngineHost.installedAppsFacts(context)
            var decision = RuleEngineHost.decide(context, command, facts)
            // 模糊但只缺 app → 用類別補槽後重試（剛好一個候選才補）
            if (decision is com.wae.core.rule.RuleEngine.Decision.NeedsModel) {
                val filled = RuleEngineHost.fillSlots(context, command, decision)
                if (filled.isNotEmpty()) {
                    decision = RuleEngineHost.decide(context, command, facts, filled)
                }
            }
            RuleEngineHost.noteDecision(decision)
            Log.i(TAG, "rules: ${RuleEngineHost.describe(decision)} command=$command")

            when (decision) {
                is com.wae.core.rule.RuleEngine.Decision.Plan -> {
                    val steps = decision.steps
                    val hasOp = steps.any { it is com.wae.core.rule.MethodExpander.Step.Op }
                    val pureOps = hasOp && steps.none { it is com.wae.core.rule.MethodExpander.Step.Cmd }
                    if (pureOps && !RuleEngineHost.opsEnabled) {
                        // 純操作層計畫：開關沒開就只記 shadow，行為與上一版一樣
                        Log.i(TAG, "shadow ops=" + decision.ops.joinToString(";") { it.id + it.args.toString() } +
                            " purpose=" + decision.purpose.id)
                    } else if (steps.isEmpty()) {
                        // 舊式：只有 commands 的清單
                        RuleEngineHost.bypass = true
                        try {
                            var lastSummary = ""
                            for (cmd in decision.commands) {
                                val r = runDetailed(context, cmd)
                                lastSummary = r.summary
                                if (!lastSummary.contains("SUCCESS")) {
                                    ruleResult = r
                                    break
                                }
                            }
                            if (ruleResult == null) {
                                ruleResult = RunResult(
                                    Outcome.Success,
                                    lastSummary + " + rule=" + decision.purpose.id + " llm=0",
                                    0
                                )
                            }
                        } finally {
                            RuleEngineHost.bypass = false
                        }
                    } else {
                        // 前置檢查：要開的 App 根本沒裝 → 立刻誠實失敗（不要進舊管線慢慢重試）
                        val missingApp = firstMissingApp(context, steps)
                        if (missingApp != null) {
                            val text = "ABSTAIN reason=no_candidate app_not_installed=" + missingApp
                            Log.i(TAG, "result=$text command=$command")
                            return RunResult(
                                Outcome.Abstain(
                                    Reason.NO_CANDIDATE,
                                    listOf(com.wae.core.model.Option("ok", "知道了"))
                                ),
                                text,
                                0
                            )
                        }
                        // 混合計畫：照順序執行（cmd 交舊管線、wait 等、op 交操作層）
                        val service = runCatching {
                            com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
                        }.getOrNull()
                        var ok = true
                        var detail = ""
                        RuleEngineHost.bypass = true
                        try {
                            for ((index, step) in steps.withIndex()) {
                                when (step) {
                                    is com.wae.core.rule.MethodExpander.Step.Cmd -> {
                                        val r = runDetailed(context, step.text)
                                        detail = r.summary
                                        if (!detail.contains("SUCCESS")) {
                                            ok = false
                                            break
                                        }
                                    }
                                    is com.wae.core.rule.MethodExpander.Step.Wait -> {
                                        android.os.SystemClock.sleep(step.ms.toLong())
                                    }
                                    is com.wae.core.rule.MethodExpander.Step.Op -> {
                                        val stepOk = OpExecutor.runSingle(context, service, step.call)
                                        detail = "step" + (index + 1) + "=" + step.call.id + " ok=" + stepOk
                                        if (!stepOk) {
                                            ok = false
                                            break
                                        }
                                    }
                                }
                            }
                        } finally {
                            RuleEngineHost.bypass = false
                        }
                        // 開 App 後前景切換需要一點時間；先等再驗，避免把成功判成失敗
                        if (ok && steps.any { it is com.wae.core.rule.MethodExpander.Step.Cmd }) {
                            android.os.SystemClock.sleep(700L)
                        }
                        val verified = if (ok) OpExecutor.verifyPurpose(context, service, decision.purpose, decision.slots) else null
                        RuleEngineHost.recordOutcome(context, decision.purpose.id, ok && verified != false)
                        if (ok && verified != false) {
                            ruleResult = RunResult(
                                Outcome.Success,
                                "steps=" + steps.size + " verified=" + verified +
                                    " + rule=" + decision.purpose.id + " llm=0",
                                0
                            )
                        } else {
                            Log.i(TAG, "plan failed/verification: ok=$ok verified=$verified detail=$detail")
                            ruleResult = RunResult(
                                Outcome.Abstain(
                                    Reason.NO_CANDIDATE,
                                    listOf(com.wae.core.model.Option("retry", "再試一次"))
                                ),
                                "ABSTAIN reason=plan_incomplete steps=" + steps.size + " detail=" + detail,
                                0
                            )
                        }
                    }
                }
                is com.wae.core.rule.RuleEngine.Decision.NeedsConfirmation -> {
                    ruleResult = RunResult(
                        Outcome.Abstain(
                            Reason.SENSITIVE_OPERATION,
                            listOf(com.wae.core.model.Option("confirm", "我自己來"))
                        ),
                        "ABSTAIN reason=sensitive_operation rule=" + decision.purpose.id +
                            " risk=" + decision.purpose.risk + " steps=" + decision.commands.size,
                        0
                    )
                }
                else -> Unit
            }
        }
        ruleResult?.let { return it }

        // ── 目的型意圖（模型 A：三層兩憶計畫 §2）────────────────────
        // 「我想看影片」「導航到台北車站」→ 類別 → App；一個就開、多個就問。
        when (val vague = com.wae.agent.gen5.VagueRunner.route(context, command)) {
            null -> Unit
            is com.wae.agent.gen5.VagueRunner.Result.Answer -> {
                Log.i(TAG, "result=${vague.summary} command=$command")
                return RunResult(
                    Outcome.Abstain(vague.reason, vague.options),
                    vague.summary,
                    0
                )
            }
            is com.wae.agent.gen5.VagueRunner.Result.Run -> {
                val first = runDetailed(context, vague.command)
                val after = vague.afterCommand
                // HOME 策略：開起來不算完成，要確認「有內容可以點」
                if (after == null && vague.strategy == com.wae.core.intent.IntentRouter.Strategy.HOME) {
                    val count = runCatching {
                        val svc = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
                        if (svc == null) 0 else StepActor.contentCount(svc, context)
                    }.getOrDefault(0)
                    if (vague.purpose.isNotEmpty() && vague.pkg.isNotEmpty()) {
                        AppCapabilityStore.record(context, vague.pkg, vague.purpose, count >= CONTENT_MIN)
                    }
                    if (count < CONTENT_MIN) {
                        val detail = first.summary + " + content=" + count + " (太少，可能空白或載入中)"
                        Log.i(TAG, "result=ABSTAIN reason=no_candidate $detail command=$command")
                        return RunResult(
                            Outcome.Abstain(Reason.NO_CANDIDATE, listOf(com.wae.core.model.Option("retry", "再試一次"))),
                            detail,
                            0
                        )
                    }
                    return RunResult(Outcome.Success, first.summary + " + content=" + count, 0)
                }
                if (after == null || !first.summary.contains("SUCCESS")) return first
                val second = runDetailed(context, after)
                if (vague.purpose.isNotEmpty() && vague.pkg.isNotEmpty()) {
                    AppCapabilityStore.record(
                        context, vague.pkg, vague.purpose,
                        second.summary.contains("SUCCESS")
                    )
                }
                if (second.summary.contains("SUCCESS")) return second
                // 後援：App 沒有搜尋 intent 時，直接在畫面上做「聚焦→輸入→送出」
                val query = after.substringAfter("搜尋", "").trim()
                val ok = runCatching {
                    val svc = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance
                    svc != null && query.isNotEmpty() && StepActor.searchHere(svc, query, context)
                }.getOrDefault(false)
                return if (ok) {
                    RunResult(Outcome.Success, first.summary + " + inAppSearch=ok", 0)
                } else {
                    second
                }
            }
        }

        // 名詞 fallback：句型與路由都認不得時，用「名詞→型別→App 類別」
        // 例：「我想找 AI 資料」→ INFO → 瀏覽器搜尋；「我想找醫院」→ PLACE → 地圖
        runCatching { nounFallback(context, command) }.getOrNull()?.let { return it }

        // ── 使用者糾正（G7）──────────────────────────────────────────
        // 「不是這個」= 把上一次成功的那一步禁掉（同一畫面 × 效果 × 手段）。
        if (CORRECTION_PATTERN.matches(command.trim())) {
            val banned = banLastAction(context, store)
            val text = if (banned) {
                "ABSTAIN reason=info correction=applied"
            } else {
                "ABSTAIN reason=info correction=nothing_to_ban"
            }
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.INFO, listOf(com.wae.core.model.Option("ok", "知道了"))),
                text,
                0
            )
        }

        val memory = store.memory
        val ledger = store.ledger
        val receipts = store.receipts
        val transitions = store.transitions

        val ocr = OcrChannel(service)
        val icons = IconChannel(service)
        val rawSource = A11yFrameSource(service, context, ocr, icons)
        val source = FrameSource {
            val frame = rawSource.capture()
            ledger.remember(frame)
            frame
        }

        val parser = FrameParser(loadLexicon(context))
        val fallback = IntentCompiler(BundledData.verbRules())
        val effects: EffectSource = EffectSource { raw ->
            // 口語清理：「幫我」「請」「可以…嗎」不影響要做什麼，但會讓句型比對失敗
            val text = com.wae.core.intent.Politeness.strip(raw)
            // D11：分享（跨 App 傳遞）
            val share = com.wae.core.intent.ShareCommandParser.parse(text)
            if (share != null) {
                listOf(share.effect)
            } else {
                // B7：先試「多效果」（一句話兩個動作，且有先後）
                val multi = com.wae.core.intent.MultiEffectParser.parse(text)
                if (multi != null && multi.isNotEmpty()) {
                    multi
                } else {
                    val parsed = parser.parse(text)
                    val fromRoles = parsed?.let { EffectCompiler.compile(it) }.orEmpty()
                    if (fromRoles.isNotEmpty()) fromRoles else fallback.compile(text)
                }
            }
        }

        val solver = MeansSolver(config)
        // 系統權限對話框（例如相機第一次要位置權限）：蓋在目標 App 上面，
        // 這時候點任何圖示都只是亂點。直接說「需要你去按允許」比亂點誠實。
        val frontPkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()
        if (frontPkg.contains(PERMISSION_CONTROLLER)) {
            val text = "ABSTAIN reason=permission_missing steps=0 dialog=$frontPkg"
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.PERMISSION_MISSING, listOf(com.wae.core.model.Option("retry", "已允許，再試"))),
                text,
                0
            )
        }
        // H5：長任務看得到進度（語音與按鈕共用同一條 overlay）
        val overlay = runCatching { com.wae.agent.core.VoiceProgressOverlay(service) }.getOrNull()
        val progress: (String) -> Unit = { text ->
            if (text.isNotEmpty()) Log.i(TAG, "progress: $text")
            runCatching {
                if (text.isEmpty()) overlay?.hide() else overlay?.show(text)
            }
        }

        // 事前檢查：亮度需要「修改系統設定」權限。沒有的話直接帶使用者去授權，
        // 不要讓他看到「試了幾次都不行」卻不知道為什麼。
        val requested = effects.compile(command)

        // 分享相簿照片需要讀取媒體權限：沒有就帶使用者去授權，不要讓它「試了才知道」
        if (requested.any { it.target.startsWith("share:photo:") } && !PhotoLibrary.hasPermission(context)) {
            runCatching {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                )
                intent.data = android.net.Uri.parse("package:" + context.packageName)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            val text = "ABSTAIN reason=permission_missing steps=0 destination=app_permissions"
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.PERMISSION_MISSING, listOf(com.wae.core.model.Option("retry", "已授權，再試"))),
                text,
                0
            )
        }

        if (requested.any { it.target.startsWith("brightness:") } && !android.provider.Settings.System.canWrite(context)) {
            runCatching {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = android.net.Uri.parse("package:" + context.packageName)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            val text = "ABSTAIN reason=permission_missing steps=0 destination=write_settings"
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.PERMISSION_MISSING, listOf(com.wae.core.model.Option("retry", "已授權，再試"))),
                text,
                0
            )
        }
        // 預算三個數字唯一定義在 ui_tunables.tsv 的 budget.*；舊路徑同樣從這組讀（BudgetSource）。
        val budget = com.wae.core.safety.BudgetTracker(
            com.wae.core.safety.ContractBudget.fromConfig(config),
            clock
        )
        val transactions = com.wae.core.exec.TransactionLog()

        // 能力索引（G）：Router 與「這支 App 做這類事要幾步」都靠它
        val capabilities = store.capabilities

        // 效果路由（B12）：能力索引由 MemoryBundle 還原，跑完統一寫回
        val router = com.wae.core.routing.CapabilityRouteSelector(
            capabilities,
            com.wae.core.routing.Router(config)
        )

        // 目標解析的語意階（B8）：MiniLM 沒載入時自動退回前兩階
        val resolver = com.wae.core.match.TargetResolver(similarity = MiniLmSimilarity(context))
        // App 名稱 → 套件名（B20）：讓「開 App」有誠實的後置條件
        val appResolver = object : com.wae.core.pipeline.AppResolver {
            override fun resolve(label: String): String? = AppCatalog.resolveLabel(context, label)
            override fun matches(pkg: String, label: String): Boolean = AppCatalog.matches(context, pkg, label)
        }

        // 能力索引（G）：Router 與「這支 App 做這類事要幾步」都靠它

        val agent = Agent(
            config = config,
            clock = clock,
            memory = memory,
            frameSource = source,
            compiler = effects,
            deriver = ContractDeriver(BundledData.contractRules()),
            planner = Planner(solver, resolver, appResolver),
            executor = Executor(AndroidActuator(service, context), clock, config),
            safety = Safety(config),
            attributor = Attributor(),
            receipts = receipts,
            budget = budget,
            transactions = transactions,
            capabilities = capabilities,
            routes = router,
            verifier = com.wae.core.verify.CompositeVerifier(listOf(NotificationChannel())),
            transitions = transitions,
            selfPkg = context.packageName,
            appResolver = appResolver,
            rollback = com.wae.core.exec.Rollback(Executor(AndroidActuator(service, context), clock, config)),
            programs = store.programs,
            preconditions = BundledData.preconditions(),
            onProgress = progress,
            sleeper = { ms -> runCatching { Thread.sleep(ms) } }
        )

        val outcome = runCatching { agent.run(command) }.getOrElse { t ->
            Log.e(TAG, "run failed", t)
            Outcome.Unknown("exception:" + t.message)
        }
        // 排錯用：把「這句話被編譯成什麼效果」寫出來（不含使用者原文）
        Log.i(TAG, "effects=" + effects.compile(command).joinToString(",") { it.key() })
        // 排錯用：同一幀上「計畫有沒有東西」（不影響執行）
        runCatching {
            val probeFrame = rawSource.capture()
            val plan = Planner(solver, resolver, appResolver)
                .plan(effects.compile(command), probeFrame, ContractDeriver(BundledData.contractRules()).derive(probeFrame), com.wae.core.model.Constraints.NONE)
            Log.i(TAG, "dryrun steps=${plan.steps.size} unsat=${plan.unsatisfied.size} pkg=${probeFrame.pkg} " +
                "controls=${probeFrame.controls.size} icons=${probeFrame.controls.count { it.source == com.wae.core.model.Channel.ICON }} " +
                "ocr=${probeFrame.controls.count { it.source == com.wae.core.model.Channel.OCR }}")
        }
        runCatching { memory.flush() }
        runCatching { store.save() }
        if (outcome is Outcome.Success) rememberLastCommand(context, command)

        // 拍照無法從畫面確認（沒有讀取媒體權限）→ 不假裝成功
        if (requested.any { it.target.startsWith("capture:") } && outcome is Outcome.Success) {
            val text = "ABSTAIN reason=capture_unverified steps=" + budget.stepsUsed()
            Log.i(TAG, "result=$text command=$command")
            return RunResult(
                Outcome.Abstain(Reason.CAPTURE_UNVERIFIED, listOf(com.wae.core.model.Option("ok", "我去相簿確認"))),
                text,
                budget.stepsUsed()
            )
        }

        val text = describe(outcome) +
            " frames=" + ledger.size() +
            " silent=" + memory.silentFailures() +
            " steps=" + budget.stepsUsed() +
            " tx=" + transactions.entries().size
        Log.i(TAG, "result=$text command=$command")
        return RunResult(outcome, text, budget.stepsUsed())
    }

    private fun describe(outcome: Outcome): String = when (outcome) {
        is Outcome.Success -> "SUCCESS"
        is Outcome.NoEffect -> "NO_EFFECT"
        is Outcome.WrongEffect -> "WRONG_EFFECT"
        is Outcome.Blocked -> "BLOCKED"
        is Outcome.Abstain -> "ABSTAIN reason=${outcome.reason} options=${outcome.options.joinToString(",") { it.id }}"
        is Outcome.Unknown -> "UNKNOWN reason=${outcome.reason}"
    }

    /** 內建資料為底，可用 assets 的同一份 TSV 覆蓋（真機調參不用重編）。 */
    /** 計畫的第一步若是「打開 X」，先確認 X 真的存在；回傳找不到的 App 名稱。 */
    private fun firstMissingApp(
        context: android.content.Context,
        steps: List<com.wae.core.rule.MethodExpander.Step>
    ): String? {
        val first = steps.firstOrNull() as? com.wae.core.rule.MethodExpander.Step.Cmd ?: return null
        val label = first.text.removePrefix("打開").removePrefix("開啟").removePrefix("用").trim()
        if (label.isEmpty() || label == first.text) return null
        val apps = runCatching {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
        }.getOrDefault(emptyList())
        val found = apps.any {
            it.label.equals(label, true) || it.label.contains(label, true) ||
                it.pkg.endsWith("." + label.lowercase())
        }
        return if (found) null else label
    }

    /** 名詞 fallback（只在前面的句型／路由都沒接手時執行）。 */
    private fun nounFallback(context: android.content.Context, command: String): RunResult? {
        val resolver = NounHost.resolver(context)
        val resolved = resolver.resolve(command) ?: return null
        val service = com.wae.agent.core.WaeVisionAccessibilityService.activeInstance ?: return null
        val apps = runCatching {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
        }.getOrDefault(emptyList())
        // 名詞本身就是 App（日曆、Chrome、YouTube…）→ 直接開它，不要按型別亂挑
        val namedApp = apps.firstOrNull {
            it.label.equals(resolved.noun, true) || it.label.contains(resolved.noun, true) ||
                resolved.noun.contains(it.label, true) && it.label.length >= 2
        }
        if (namedApp != null) {
            if (OpExecutor.launchPkg(context, namedApp.pkg)) {
                if (resolved.action == "search") {
                    awaitForeground(service, namedApp.pkg, 2500L)
                    OpExecutor.uriSearchFor(context, namedApp.pkg, resolved.noun)
                }
                AppCapabilityStore.record(context, namedApp.pkg, "noun:" + resolved.type, true)
                return nounSuccess(resolved, namedApp.label, "direct", command)
            }
        }
        val candidates = apps.filter { resolved.categories.contains(it.category) }
        if (candidates.isEmpty()) {
            return nounAbstain(command, "noun_type=" + resolved.type + " noun=" + resolved.noun + " no_app_for_type")
        }

        // 選 App 的順序：能直接搜尋的（有 URI 模板）優先，其次已知做得到，
        // 最後才照清單順序——不要先開一個名稱有 AI 但根本沒有搜尋的 App。
        // 使用者在設定裡排的順序優先；沒排過的再依「可搜尋 → 已知可行」
        val userOrdered = com.wae.agent.settings.AppTypeOrderStore.apply(
            context, resolved.type, candidates
        ) { it.pkg }
        val uriApps = userOrdered.filter { uriCapable(it.pkg) }
        val pool = if (resolved.action == "search" && uriApps.isNotEmpty()) uriApps else userOrdered
        val ordered = pool.sortedByDescending { uriCapable(it.pkg) }
            .sortedByDescending { AppCapabilityStore.supports(context, it.pkg, "noun:" + resolved.type) == true }

        var lastDetail = ""
        val tried = ArrayList<String>()
        for (app in ordered.take(3)) {
            tried.add(app.label)
            // 直接啟動（不經舊管線）：舊管線會重試、會開 overlay，慢又容易卡住
            val launched = OpExecutor.launchPkg(context, app.pkg)
            if (!launched) {
                lastDetail = "launch_failed app=" + app.label + " pkg=" + app.pkg
                continue
            }
            if (resolved.action != "search") {
                AppCapabilityStore.record(context, app.pkg, "noun:" + resolved.type, true)
                return nounSuccess(resolved, app.label, "open", command)
            }
            // 等 App 真的到前景再做 URI 搜尋（太早送會落到舊畫面）
            awaitForeground(service, app.pkg, 2500L)
            val byUri = runCatching { OpExecutor.uriSearchFor(context, app.pkg, resolved.noun) }.getOrDefault(false)
            if (byUri) {
                AppCapabilityStore.record(context, app.pkg, "noun:" + resolved.type, true)
                return nounSuccess(resolved, app.label, "uri", command)
            }
            val byA11y = runCatching { StepActor.searchHere(service, resolved.noun, context) }.getOrDefault(false)
            if (byA11y) {
                AppCapabilityStore.record(context, app.pkg, "noun:" + resolved.type, true)
                return nounSuccess(resolved, app.label, "a11y", command)
            }
            AppCapabilityStore.record(context, app.pkg, "noun:" + resolved.type, false)
            lastDetail = "search_failed app=" + app.label
        }
        return nounAbstain(
            command,
            "noun_type=" + resolved.type + " noun=" + resolved.noun +
                " tried=" + tried.joinToString(",") + " " + lastDetail
        )
    }

    private fun uriCapable(pkg: String): Boolean = runCatching {
        BundledData.uriSearchLines().any { line ->
            !line.startsWith("#") && line.split('\t').firstOrNull()?.trim() == pkg
        }
    }.getOrDefault(false)

    private fun awaitForeground(
        service: com.wae.agent.core.WaeVisionAccessibilityService,
        pkg: String,
        timeoutMs: Long
    ) {
        var waited = 0L
        while (waited < timeoutMs) {
            val current = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (current == pkg) return
            android.os.SystemClock.sleep(150L)
            waited += 150L
        }
    }

    private fun nounSuccess(
        resolved: com.wae.core.rule.NounResolver.Resolution,
        appLabel: String,
        via: String,
        command: String
    ): RunResult {
        val text = "SUCCESS noun_type=" + resolved.type + " noun=" + resolved.noun +
            " app=" + appLabel + " via=" + via + " matched_by=" + resolved.matchedBy
        Log.i(TAG, "result=$text command=$command")
        return RunResult(Outcome.Success, text, 0)
    }

    private fun nounAbstain(command: String, detail: String): RunResult {
        val text = "ABSTAIN reason=no_candidate " + detail
        Log.i(TAG, "result=$text command=$command")
        return RunResult(
            Outcome.Abstain(Reason.NO_CANDIDATE, listOf(com.wae.core.model.Option("retry", "再試一次"))),
            text,
            0
        )
    }

    private fun loadConfig(context: Context): Config {
        val merged = LinkedHashMap(BundledData.tunables())
        runCatching {
            context.assets.open(ASSET_TUNABLES).bufferedReader(Charsets.UTF_8).use { reader ->
                merged.putAll(ConfigParser.parse(reader.readLines().asSequence()))
            }
        }
        return MapConfig(merged)
    }

    /** 角色詞庫：assets 沒有就用內建預設（並記一筆 log，方便發現漏打包）。 */
    private fun loadLexicon(context: Context): RoleLexicon {
        val fromAssets = runCatching {
            context.assets.open(ASSET_LEXICON).bufferedReader(Charsets.UTF_8).use { reader ->
                RoleLexicon.parse(reader.readLines().asSequence())
            }
        }.getOrNull()
        if (fromAssets == null) {
            Log.w(TAG, "role_lexicon.tsv 不在 assets，改用內建預設")
            return RoleLexicon.DEFAULT
        }
        return fromAssets
    }
}
