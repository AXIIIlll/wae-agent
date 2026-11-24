package com.wae.agent.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.wae.agent.agent.AgentAction
import com.wae.agent.agent.AgentDecision
import com.wae.agent.agent.AgentExecutor
import com.wae.agent.agent.AgentLoop
import com.wae.agent.agent.AgentRunOutcome
import com.wae.agent.agent.AgentStep
import com.wae.agent.agent.DecisionResolver
import com.wae.agent.agent.MiniLmScorer
import com.wae.agent.agent.Observation
import com.wae.agent.agent.ObservationBuilder
import com.wae.agent.agent.Observer
import com.wae.agent.agent.SafetyCheck
import com.wae.agent.agent.UserAsker
import com.wae.agent.agent.VerifyRules
import com.wae.agent.voice.VoiceRiskPolicy

/**
 * 開放式目標在真實裝置上的執行器（M2，**不含 LLM**）：
 *   AppSelector（意圖→類別→已安裝 App）→ OpenEndedPlanner（決定性模板）
 *   → 或 ElementPlanner（逐元素打分）→ Observation（無障礙樹）
 *   → DecisionResolver（MiniLM）→ Safety → 既有無障礙動作 → VerifyRules → 迴圈。
 *
 * 不含任何 App 專屬流程；動作只有通用種類。
 */
class AgentRunner(private val service: WaeVisionAccessibilityService) {

    private var lastSnapshot: ObservationBuilder.ScreenSnapshot =
        ObservationBuilder.ScreenSnapshot(Observation(), emptyList())

    /** OCR 行的螢幕座標（index → rect）；a11y 沒節點時唯一的點擊方式。 */
    private val ocrBounds = HashMap<Int, android.graphics.Rect>()

    fun run(
        goal: String,
        onPlan: (com.wae.agent.agent.AgentPlan) -> Unit = {},
        // I：每一步的進度（"第 2/3 步：按下播放"），由 UI 顯示，不再只有一行「規劃中…」
        onStep: (String) -> Unit = {}
    ): AgentRunOutcome {
        val ctx: Context = service
        val t0 = SystemClock.elapsedRealtime()
        // 通用前置：先回首頁，避免上一個測試殘留畫面干擾
        runCatching { service.agentHome() }
        Thread.sleep(600)
        // 已安裝 App 目錄（含 metadata 類別/別名）＋ 最近使用量；不再只取前 60 個名稱。
        val catalog = com.wae.agent.core.app.InstalledAppCatalog.load(ctx)
        val usage = com.wae.agent.core.app.InstalledAppCatalog.usageCounts(ctx)
        // 觀測／決策／驗證／執行器先建好：後面不管是「純開 App」「模板」或「LLM 計畫」都共用同一組。
        val observer = object : Observer {
            override fun observe(): Observation {
                lastSnapshot = ObservationBuilder.snapshot(service.agentRoot(), 60)
                var obs = lastSnapshot.observation
                // 覆蓋率關鍵：無障礙樹太空（Canvas/WebView/Flutter/遊戲）就補 OCR。
                // OCR 行標成可點並帶座標，執行層用座標點；沒有這條，這些 App 等於全瞎。
                val ocrA11yThreshold = com.wae.agent.core.runtime.Tunables.getInt(ctx, "perception.ocr_a11y_threshold", 6)
                val ocrMaxLines = com.wae.agent.core.runtime.Tunables.getInt(ctx, "perception.ocr_max_lines", 30)
                if (obs.elements.size < ocrA11yThreshold) {
                    runCatching {
                        val bmp = service.captureScreenForPerception()
                        if (bmp != null) {
                            val lines = com.wae.agent.vision.PpOcrEngine.detectAndRecognize(bmp, ocrMaxLines)
                            if (lines.isNotEmpty()) {
                                val sx = bmp.width.toFloat() / 1080f
                                obs = ObservationBuilder.mergeOcr(obs, lines, scale = sx.coerceIn(0.5f, 3f))
                                ocrBounds.clear()
                                obs.elements.filter { it.type == "OCR" && it.hasBounds() }.forEach { e ->
                                    ocrBounds[e.index] = android.graphics.Rect(e.left, e.top, e.right, e.bottom)
                                }
                                Log.i("WAE_AGENT", "ocr merged: ${lines.size} lines (a11y=${lastSnapshot.observation.elements.size})")
                            }
                        }
                    }
                }
                return obs
            }
        }
        val resolver = DecisionResolver(
            MiniLmScorer(ctx),
            // 決策門檻一律走 Tunables（可調、可真機覆寫，不在程式碼寫魔術數字）
            com.wae.agent.agent.ResolverConfig(
                autoActThreshold = com.wae.agent.core.runtime.Tunables.getFloat(ctx, "decision.auto_act_threshold", 0.55f),
                askOrReplanThreshold = com.wae.agent.core.runtime.Tunables.getFloat(ctx, "decision.scroll_when_below", 0.30f)
            )
        )
        val verifier = VerifyRules { isAudioPlaying() }
        val executor = object : AgentExecutor {
            override fun execute(decision: AgentDecision): Boolean = act(decision)
        }
        val asker = object : UserAsker {
            // 自治測試模式：有選項選第一個；沒有選項就回固定值讓流程繼續（真機版會接助理 UI）
            override fun ask(question: String, options: List<String>): String? =
                options.firstOrNull() ?: "好"
        }
        val safety = object : SafetyCheck {
            override fun allows(decision: AgentDecision, observation: Observation): Boolean {
                if (decision.action != AgentAction.TYPE) return true
                return VoiceRiskPolicy.classify(ctx, decision.text.orEmpty()) != VoiceRiskPolicy.Risk.BLOCK
            }
        }
        // 第一段：先走「意圖 → 類別 → 已安裝 App」的決定性路徑（看劇／聽歌／導航這類），
        // 沒有把握時退回模板或逐元素決策；本專案不出貨 LLM，所以沒有「生成計畫」這條路。
        val appDecision = com.wae.agent.core.app.AppSelector.select(
            goal, catalog, usage,
            bias = { cat, label -> com.wae.agent.core.app.AppChoiceMemo.bias(ctx, cat, label) }
        )
        Log.i(
            "WAE_AGENT",
            "phase=select_app ms=" + (SystemClock.elapsedRealtime() - t0) +
                " reason=" + appDecision.reason + " cats=" + appDecision.matchedCategories
        )
        // 這一類意圖有決定性模板 → **永遠不呼叫 LLM**。
        // 之前「看劇」在 AppSelector 沒把握時會掉進 LLM（選 App + 生成計畫 ≈ 2 分鐘），
        // 但模板 3 步就能做完；沒把握時直接反問，毫秒級。
        val templateKind = com.wae.agent.core.app.OpenEndedPlanner.kindFor(appDecision.matchedCategories)
        var selectedApp: String? = null
        if (appDecision.confident) {
            selectedApp = appDecision.best!!.candidate.label
            com.wae.agent.core.app.AppChoiceMemo.recordPick(
                appDecision.matchedCategories.firstOrNull().orEmpty(), selectedApp
            )
            Log.i(
                "WAE_AGENT",
                "app selected by intent/category: $selectedApp (${appDecision.best!!.candidate.pkg}) " +
                    "reason=${appDecision.reason} cats=${appDecision.matchedCategories}"
            )
        } else if (templateKind != null) {
            // 已知類別但選不出唯一 App：**不再反問**（App 內沒有作答 UI，問了也沒用），
            // 直接採用分數最高的候選，並在 log/畫面上說明選了誰。
            val best = appDecision.best
            if (best == null) {
                return AgentRunOutcome(
                    com.wae.agent.agent.AgentResult.ASKING,
                    "找不到可用的 App，請先安裝或直接說「打開 XX」",
                    llmCalls = 0
                )
            }
            selectedApp = best.candidate.label
            com.wae.agent.core.app.AppChoiceMemo.recordPick(
                appDecision.matchedCategories.firstOrNull().orEmpty(), selectedApp
            )
            Log.i(
                "WAE_AGENT",
                "app auto-picked: $selectedApp (${best.candidate.pkg}) reason=${appDecision.reason}"
            )
        } else {
            // 沒有計畫模型（本專案不出貨 LLM）→ 直接走「逐元素決策」迴圈：
            // 不生成任何文字，只在目前畫面上挑最該動的元素。能做完就做完；
            // 不知道要做什麼就明確回報需要反問，不要硬跑一個註定失敗的計畫。
            val element = ElementPlanner(observer, resolver, executor, maxSteps = 8)
            val r = element.run(goal)
            Log.i("WAE_AGENT", "element planner: success=${r.success} ask=${r.needsAsk} steps=${r.steps} reason=${r.reason}")
            return AgentRunOutcome(
                when {
                    r.success -> com.wae.agent.agent.AgentResult.SUCCESS
                    r.needsAsk -> com.wae.agent.agent.AgentResult.ASKING
                    else -> com.wae.agent.agent.AgentResult.FAIL
                },
                "element planner: " + r.reason + " steps=" + r.steps,
                llmCalls = 0
            )
        }
        if (selectedApp != null) {
            Log.i("WAE_AGENT", "selected app=" + selectedApp)
        }

        // 「打開 X」這種**純啟動**指令：pipeline 有這條路，Agent 路徑原本沒有，
        // 所以會掉進逐元素決策 → 畫面上找不到可點的目標 → 直接失敗。
        // 實測（2026-11-23）：題庫第 2 題「打開時鐘」就是這樣失敗的，
        // 但 App 解析其實是對的（com.sec.android.app.clockpackage）。
        //
        // 這裡用 pipeline 自己的拆解結果當判斷依據：整句只拆出一個 LAUNCH_APP 才短路，
        // 所以「打開 YouTube 搜尋周杰倫」不會被誤判成純啟動。
        val launchOnly = com.wae.core.util.Degrade.orNull("agent:decompose") {
            com.wae.agent.core.WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(goal, ctx)
        }
        if (launchOnly != null && launchOnly.size == 1 &&
            launchOnly[0].actionKind == com.wae.agent.core.ActionKind.LAUNCH_APP &&
            templateKind == null
        ) {
            val g = launchOnly[0]
            val pkg = g.targetPackageHint.ifEmpty {
                com.wae.agent.core.WaeOpenSourceDatabaseRegistry
                    .resolvePureLaunchTarget(ctx, goal)?.packageName.orEmpty()
            }
            val launched = if (pkg.isNotEmpty()) launchApp(pkg, g.goalText) else launchAppByName(g.goalText)
            Log.i("WAE_AGENT", "pure launch: pkg=$pkg launched=$launched goal='${g.goalText}'")
            return AgentRunOutcome(
                if (launched) com.wae.agent.agent.AgentResult.SUCCESS
                else com.wae.agent.agent.AgentResult.FAIL,
                if (launched) "已打開 ${g.goalText}" else "無法啟動 ${g.goalText}",
                llmCalls = 0,
                stepsExecuted = 1
            )
        }
        // 開放式意圖（看劇／聽歌／導航／外送／購物）走決定性模板：
        // 模板可預測、步驟少，比「生成式計畫」在這些句子上可靠得多。
        // 先用 FuzzyIntent 抽槽位；缺必要槽位就反問，不要硬猜。
        val fuzzy = com.wae.agent.core.app.FuzzyIntent.resolve(goal)
        val slots: Map<String, String> = when (fuzzy) {
            is com.wae.agent.core.app.FuzzyIntent.Result.Resolved -> fuzzy.slots
            is com.wae.agent.core.app.FuzzyIntent.Result.Ask -> {
                if (appDecision.confident) {
                    return AgentRunOutcome(
                        com.wae.agent.agent.AgentResult.ASKING,
                        fuzzy.question,
                        llmCalls = 0
                    )
                }
                fuzzy.slots
            }
            null -> emptyMap()
        }
        val templatePlan = com.wae.agent.core.app.OpenEndedPlanner.plan(
            goal, selectedApp ?: "", appDecision.matchedCategories, slots,
            appDecision.best?.candidate?.pkg.orEmpty()
        )
        val boundPlanner: com.wae.agent.agent.Planner = if (templatePlan != null) {
            Log.i("WAE_AGENT_TEMPLATE", "deterministic plan: " + templatePlan.summary)
            object : com.wae.agent.agent.Planner {
                override fun plan(goal: String): com.wae.agent.agent.AgentPlan? = templatePlan
                // 模板計畫失敗時只重試「失敗的那一步」一次（模板沒有推理能力，
                // 重寫整份計畫只會亂；重試一次對開屏慢、動畫延遲這類暫時性失敗有用）。
                override fun replan(
                    goal: String,
                    failedStep: com.wae.agent.agent.AgentStep?,
                    reason: String
                ): com.wae.agent.agent.AgentPlan? {
                    val step = failedStep ?: return null
                    val t = step.target.orEmpty()
                    // 播放失敗：很可能停在錯的頁面（廣告、頻道、Shorts）。
                    // 先返回上一層、重新挑一部內容，再按播放——而不是原地重試同一步。
                    if (t == "\$role:play") {
                        return com.wae.agent.agent.AgentPlan(
                            version = 1,
                            goalKind = "open_ended",
                            summary = "back and retry play",
                            steps = listOf(
                                com.wae.agent.agent.AgentStep(1, "返回上一層", "", "上一頁"),
                                com.wae.agent.agent.AgentStep(2, "重新挑一部內容", "\$role:first_result", ""),
                                com.wae.agent.agent.AgentStep(3, step.doText, t, step.verify)
                            )
                        )
                    }
                    return com.wae.agent.agent.AgentPlan(
                        version = 1,
                        goalKind = "open_ended",
                        summary = "retry: " + (step.verify ?: step.doText),
                        steps = listOf(step.copy(id = 1))
                    )
                }
            }
        } else {
            // 沒有模板可套、也沒有 LLM 可以生成計畫 → 逐元素決策，並誠實回報結果。
            val element = ElementPlanner(observer, resolver, executor, maxSteps = 8)
            val r = element.run(goal)
            Log.i("WAE_AGENT", "element planner (no template): success=${r.success} ask=${r.needsAsk} steps=${r.steps} reason=${r.reason}")
            return AgentRunOutcome(
                when {
                    r.success -> com.wae.agent.agent.AgentResult.SUCCESS
                    r.needsAsk -> com.wae.agent.agent.AgentResult.ASKING
                    else -> com.wae.agent.agent.AgentResult.FAIL
                },
                "element planner: " + r.reason + " steps=" + r.steps,
                llmCalls = 0
            )
        }
        val loop = AgentLoop(
            planner = boundPlanner,
            observer = observer,
            resolver = resolver,
            verifier = verifier,
            executor = executor,
            asker = asker,
            safety = safety,
            // 步數/時限只認 budget.*（與 Gen5 同一組數字；單一出口見 BudgetSource）
            limits = com.wae.agent.core.runtime.BudgetSource.limits(ctx),
            onProgress = { msg ->
                Log.i("WAE_AGENT_STEP", msg)
                runCatching { onStep(msg) }
            }
        )
        Log.i("WAE_AGENT", "phase=plan_ready ms=" + (SystemClock.elapsedRealtime() - t0))
        val out = loop.run(goal, onPlan)
        Log.i(
            "WAE_AGENT",
            "result=" + out.result + " steps=" + out.stepsExecuted + " llmCalls=" + out.llmCalls +
                " ms=" + (SystemClock.elapsedRealtime() - t0) + " msg=" + out.message
        )
        return out
    }

    /** 播放驗證的第二訊號：系統是否正在輸出音訊（影片/音樂都會拿到 audio focus）。 */
    private fun isAudioPlaying(): Boolean = runCatching {
        val am = service.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isMusicActive
    }.getOrDefault(false)

    // ── 通用動作 ────────────────────────────────────────────
    private fun act(d: AgentDecision): Boolean = try {
        when (d.action) {
            AgentAction.TAP -> tapIndex(d.index)
            AgentAction.TYPE -> typeIndex(d.index, d.text.orEmpty())
            AgentAction.SCROLL -> scrollForward()
            AgentAction.BACK -> service.agentBack()
            AgentAction.OPEN_APP -> launchApp(d.appPackage, d.appName.orEmpty())
            AgentAction.WAIT -> { Thread.sleep(800); true }
            else -> true
        }
    } catch (t: Throwable) {
        Log.w("WAE_AGENT", "act failed: " + t.message)
        false
    }

    private fun tapIndex(index: Int): Boolean {
        val node = lastSnapshot.nodes.getOrNull(index)
        if (node == null) {
            // OCR 行沒有 node → 只有座標可以點
            val rect = ocrBounds[index] ?: return false
            return tapRect(rect)
        }
        if (NodeOps.perform(node, AccessibilityNodeInfo.ACTION_CLICK)) return true
        return NodeOps.tap(service, node)
    }

    private fun tapRect(rect: Rect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return NodeOps.gesture(service, rect.exactCenterX(), rect.exactCenterY())
    }

    private fun typeIndex(index: Int, text: String): Boolean {
        if (text.isBlank()) return false
        val node = lastSnapshot.nodes.getOrNull(index) ?: return false
        NodeOps.perform(node, AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return NodeOps.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun scrollForward(): Boolean {
        val scrollable = lastSnapshot.nodes.firstOrNull { it.isScrollable }
            ?: return service.agentBack()
        return NodeOps.perform(scrollable, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** 開 App 後等畫面真的有內容（首頁常常先給骨架/空畫面，太早觀察會什麼都抓不到）。 */
    private fun waitForContent(maxMs: Long = 4000L): Boolean {
        val ctx = service as android.content.Context
        val timeout = com.wae.agent.core.runtime.Tunables.getLong(ctx, "agent.wait_content_timeout_ms", maxMs)
        val minElements = com.wae.agent.core.runtime.Tunables.getInt(ctx, "agent.wait_content_min_elements", 6)
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = service.agentRoot() ?: return false
            val snap = ObservationBuilder.snapshot(root, 16)
            if (snap.observation.elements.size >= minElements) return true
            Thread.sleep(250)
        }
        return false
    }

    /** 開 App：有 package 就直接用 package（最可靠），否則退回 label 解析。 */
    private fun launchApp(pkg: String?, name: String): Boolean {
        if (!pkg.isNullOrBlank()) {
            val intent = service.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                Log.i("WAE_AGENT", "launched pkg=$pkg (direct)")
                Thread.sleep(com.wae.agent.core.runtime.Tunables.getLong(service, "agent.wait_after_launch_ms", 1200L))
                waitForContent()
                return true
            }
            Log.w("WAE_AGENT", "no launch intent for pkg=$pkg; falling back to name")
        }
        return launchAppByName(name)
    }

    private fun launchAppByName(name: String): Boolean {
        val pm = service.packageManager
        val pkg = resolveAppPackage(name)
        if (pkg == null) {
            // 通用規則：解析不到就不動作，讓上層重規劃或反問；不再落到任何固定 App。
            Log.w("WAE_AGENT", "app not resolved: '" + name + "' (no fallback)")
            return false
        }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        Log.i("WAE_AGENT", "launched pkg=" + pkg + " for name=" + name)
        Thread.sleep(com.wae.agent.core.runtime.Tunables.getLong(service, "agent.wait_after_launch_ms", 1200L))
        waitForContent()
        return true
    }

    /**
     * 用中文語意 embedding（bge-small-zh）把已安裝 App 的 label 與目標做排序。
     * 加分：系統 App 分類（VIDEO/AUDIO/NEWS/MAPS…）跟目標的語意相近時 +0.06。
     * 純本機、不需任何預設清單。
     */
    private fun rankAppsByGoal(goal: String): List<Pair<String, Float>> {
        return runCatching {
            val ctx = service.applicationContext
            if (!com.wae.agent.core.nlp.ZhEmbedder.ensureLoaded(ctx)) return emptyList()
            val simpGoal = tradToSimp(goal)
            val gv = com.wae.agent.core.nlp.ZhEmbedder.encode(listOf(simpGoal)).firstOrNull()
                ?: return emptyList()
            val apps = installedAppsInfo()
            if (apps.isEmpty()) return emptyList()
            val labels = apps.map { it.first }
            val vecs = com.wae.agent.core.nlp.ZhEmbedder.encode(labels)
            val scored = apps.mapIndexedNotNull { idx, app ->
                val ev = vecs.getOrNull(idx) ?: return@mapIndexedNotNull null
                var score = com.wae.agent.core.nlp.ZhEmbedder.cosine(gv, ev)
                score += categoryBonus(simpGoal, gv, app.third)
                app.first to score
            }
            scored.sortedByDescending { it.second }.take(5)
        }.getOrDefault(emptyList())
    }

    /** 系統分類的通用加成（不寫死任何 App；分類由 Android 提供）。 */
    private fun categoryBonus(goalSimp: String, goalVec: FloatArray, category: Int): Float {
        if (category == 0) return 0f
        val terms = when (category) {
            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "影片 影音 追劇 播放"
            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "音樂 聲音 播放"
            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "照片 相簿"
            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "社群 聊天"
            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "新聞 資訊 查資料"
            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "地圖 導航 查地點"
            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "工具 效率"
            android.content.pm.ApplicationInfo.CATEGORY_GAME -> "遊戲"
            else -> return 0f
        }
        return runCatching {
            val cv = com.wae.agent.core.nlp.ZhEmbedder.encode(listOf(terms)).firstOrNull()
                ?: return 0f
            val sim = com.wae.agent.core.nlp.ZhEmbedder.cosine(goalVec, cv)
            if (sim <= 0.25f) 0f else sim * 0.06f
        }.getOrDefault(0f)
    }

    /** 已安裝 App：label、package、系統分類。 */
    private fun installedAppsInfo(): List<Triple<String, String, Int>> {
        val pm = service.packageManager
        return runCatching {
            pm.getInstalledApplications(0).mapNotNull { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()
                if (label.isNullOrBlank()) null else Triple(label, app.packageName, app.category)
            }
        }.getOrDefault(emptyList())
    }

    /** 繁體 → 簡體（把目標轉成中文 embedding 較熟悉的簡體；使用現有繁簡對照資料）。 */
    private fun tradToSimp(text: String): String {
        if (tradToSimpMap == null) {
            tradToSimpMap = runCatching {
                val map = HashMap<Char, Char>()
                service.assets.open("open_source_ai_databases/ui_asr_trad_map.tsv")
                    .bufferedReader().useLines { seq ->
                        seq.forEach { line ->
                            val p = line.split('\t')
                            if (p.size >= 2 && p[0].length == 1 && p[1].length == 1) {
                                map[p[1][0]] = p[0][0]
                            }
                        }
                    }
                map
            }.getOrDefault(emptyMap())
        }
        val m = tradToSimpMap ?: return text
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(m[ch] ?: ch)
        return sb.toString()
    }

    @Volatile
    private var tradToSimpMap: Map<Char, Char>? = null

    /** 通用 App 解析：資料庫/別名 → 已安裝 App 的 label（完全相符或包含）→ null。 */
    private fun resolveAppPackage(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        AppNameResolver.resolve(service, n)?.let { return it }
        if (n.contains("瀏覽器") || n.contains("浏览器") || n.equals("browser", true)) {
            resolveBrowserPackage()?.let { return it }
        }
        val pm = service.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        var contains: String? = null
        for (app in apps) {
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue
            if (label.equals(n, ignoreCase = true)) return app.packageName
            if (contains == null && (label.contains(n, ignoreCase = true) ||
                    (n.contains(label, ignoreCase = true) && label.length >= 2))
            ) {
                contains = app.packageName
            }
        }
        return contains
    }

    /** 用系統 ACTION_VIEW 解析出可開網頁的 App（瀏覽器），不寫死任何品牌。 */
    private fun resolveBrowserPackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = service.packageManager
        val resolved = pm.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        resolved?.activityInfo?.packageName
    }.getOrNull()

    /** 讓 AgentLoop 的 plan 步驟可用（保留給後續 prompt 調校）。 */
    @Suppress("unused")
    private fun stepText(s: AgentStep): String = s.doText + " -> " + (s.target ?: "")
}
