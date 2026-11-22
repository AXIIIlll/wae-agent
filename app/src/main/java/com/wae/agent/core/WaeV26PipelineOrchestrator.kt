package com.wae.agent.core

import android.content.Context
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * WAE V2.6 核心管線編排器（零硬編碼預設、100% 依賴開源資料庫與 8 顆端側模型）：
 * - 指令拆解與槽位提取完全查詢 `command_lexicon.tsv` (7,508 條動作/連接詞/條件標籤) 與 `global_tw_cn_app_metadata.tsv`
 * - 語意召回完全查詢 `hit_cilin_synonyms_expanded.tsv` (16,505 組同義詞詞林) 與 C++ `all_minilm_l6_v2_int8.bin`
 */
object WaeV26PipelineOrchestrator {

    @Volatile
    var currentRunMode: RunMode = RunMode.PRODUCTION

    @Volatile
    var calibrationConfig: CuaCalibrationConfig = CuaCalibrationConfig()

    val globalTaskCounter = GlobalTaskCounter()
    val localRecoveryCounter = LocalRecoveryCounter()
    val stepCheckpoints = CopyOnWriteArrayList<StepCheckpoint>()

    // LRU-200 跨滾動穩定死點黑名單：Key = "$activeWindowId|$nodeStableSig"
    private var lastBlacklistWindowId: Int = -1
    private val deadClickBlacklistLru = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 200
        }
    }

    // ========================================================================
    // 1. L6 Coordinator：完全基於 command_lexicon.tsv 與系統已安裝 App 資料庫拆解指令與提取槽位
    // ========================================================================
    private fun trimClausePunctuation(text: String): String {
        return text.trim {
            it.isWhitespace() ||
                it == '，' || it == ',' ||
                it == '。' || it == '、' ||
                it == '；' || it == ';' ||
                it == '：' || it == ':' ||
                it == '！' || it == '!' ||
                it == '？' || it == '?'
        }
    }

    fun decomposeCommandIntoLocalGoals(rawCommand: String, context: Context? = null): List<LocalGoal> {
        WaeOpenSourceDatabaseRegistry.ensureLoaded(context)
        // 元件能力圖譜 / 用途分類 / 數值調整語彙庫（全部資料驅動）
        com.wae.agent.core.component.WidgetOntology.ensureLoaded(context)
        com.wae.agent.core.component.ValueAdjustLexicon.ensureLoaded(context)
        com.wae.agent.core.component.ValueVerbLexicon.ensureLoaded(context)
        com.wae.agent.core.nlu.GrammarParticles.ensureLoaded(context)
        com.wae.agent.core.nlu.ActionSelectorTable.ensureLoaded(context)
        com.wae.agent.core.decision.TargetPreference.ensureLoaded(context)
        com.wae.agent.core.decision.DecisionHead.ensureLoaded(context)
        com.wae.agent.core.goal.GoalCompiler.ensureLoaded(context)
        com.wae.agent.core.decision.ContentItemEvidence.ensureLoaded(context)
        com.wae.agent.core.nlu.ToggleIntent.ensureLoaded(context)
        com.wae.agent.core.nlu.GoalConnectors.ensureLoaded(context)
        com.wae.agent.core.nlu.ToggleSectionMap.ensureLoaded(context)
        val clean = trimClausePunctuation(rawCommand)
        if (clean.isEmpty()) return emptyList()

        // 由手機 PackageManager 與 global_tw_cn_app_metadata.tsv 動態解析目標 App。
        // 精確／別名／模糊（含簡繁與 ASR 同音正規化）都試過才放棄——
        // 能解析就讓目標變完整，路由自然走快速管線；真的找不到才輪到 Agent。
        val resolvedApp: WaeOpenSourceDatabaseRegistry.ResolvedAppTarget? =
            WaeOpenSourceDatabaseRegistry.resolveTargetAppFromDatabaseOrDevice(context, clean)
                ?: WaeOpenSourceDatabaseRegistry.resolvePureLaunchTarget(context, clean)
        val targetPkgHint = resolvedApp?.packageName.orEmpty()

        val segments = splitByDatabaseConnectorsAndActionBoundaries(clean)
        val goals = mutableListOf<LocalGoal>()
        var stepCounter = 0

        for (rawSeg in segments) {
            var seg = trimClausePunctuation(rawSeg)
            if (seg.isEmpty()) continue

            // 若子句中包含目標 App 別名（例如「打開YouTube」或「進youtube搜尋周杰倫」）
            if (resolvedApp != null && seg.lowercase().contains(resolvedApp.matchedAlias)) {
                val aliasIdx = seg.lowercase().indexOf(resolvedApp.matchedAlias)
                val afterAliasIdx = aliasIdx + resolvedApp.matchedAlias.length
                // B1 根因修法：不是只切別名，而是把整個「App 片語」吃掉
                //   [在|到|進|從|打開|進入] + 別名 + [中|裡|內|上|的|之後]
                val remainingAfterApp = trimClausePunctuation(
                    com.wae.agent.core.nlu.GrammarParticles.stripAppPhrase(seg, resolvedApp.matchedAlias)
                )
                if (remainingAfterApp.isEmpty()) {
                    // 純啟動 App 指令（如：「打開YouTube」）
                    goals.add(
                        LocalGoal(
                            stepIdx = stepCounter++,
                            goalId = "goal_${stepCounter}",
                            goalText = seg,
                            canonicalIntentTag = "launch_app",
                            actionKind = ActionKind.LAUNCH_APP,
                            targetPackageHint = targetPkgHint
                        )
                    )
                    continue
                } else {
                    // 複合前缀（如：「進youtube搜尋周杰倫」-> 先確保啟動 YouTube，並將子句收斂為「搜尋周杰倫」）
                    // 但若前綴本身就是導覽意圖（「進入顯示設定…」），這半句必須留下來，
                    // 否則「進入X」會被整段丟掉，任務直接從下一個動作開始（真實踩過的坑）。
                    val prefix = trimClausePunctuation(seg.substring(0, aliasIdx))
                    if (prefix.isNotEmpty() &&
                        OPEN_INTENT_PREFIXES.any { prefix.contains(it) }
                    ) {
                        goals.add(
                            LocalGoal(
                                stepIdx = stepCounter++,
                                goalId = "goal_${stepCounter}",
                                goalText = trimClausePunctuation(seg.substring(0, afterAliasIdx)),
                                canonicalIntentTag = "launch_app",
                                actionKind = ActionKind.LAUNCH_APP,
                                targetPackageHint = targetPkgHint
                            )
                        )
                    }
                    seg = remainingAfterApp
                }
            }

            val matchedLex = findLongestLeadingActionLexicon(seg)
            if (matchedLex != null && matchedLex.expectsQueryPayload) {
                val payloadStart = seg.lowercase().indexOf(matchedLex.term) + matchedLex.term.length
                val payload = trimClausePunctuation(seg.substring(payloadStart.coerceIn(0, seg.length)))
                goals.add(
                    LocalGoal(
                        stepIdx = stepCounter++,
                        goalId = "goal_${stepCounter}",
                        goalText = seg,
                        canonicalIntentTag = matchedLex.canonicalRole.lowercase().ifEmpty { "search_input" },
                        actionKind = ActionKind.TYPE,
                        inputPayload = payload,
                        isSensitivePayload = false,
                        ambiguityPolicy = AmbiguityPolicy.AUTO_RESOLVE,
                        targetPackageHint = targetPkgHint
                    )
                )
            } else {
                // 受詞抽取要考慮語序：把字句的受詞在動詞之前
                //   「搜尋 invoice」→ invoice　；「把深色模式打開」→ 深色模式
                val cleanTargetNoun = stripTrailingActionTerm(
                    com.wae.agent.core.nlu.GrammarParticles
                        .extractObject(seg, matchedLex?.term.orEmpty())
                        .let { trimClausePunctuation(it) }
                )

                val canonical = matchedLex?.canonicalRole?.lowercase()?.ifEmpty { "general_tap" } ?: "general_tap"
                // B2 根因修法：動作 = f(動詞類別, 受詞型別)，而不是只看動詞。
                // 「打開設定」→ 受詞是 App → 啟動；「把深色模式打開」→ 受詞是功能項 → 點擊。
                val verbClass = com.wae.agent.core.nlu.ActionSelectorTable
                    .verbClassOf(matchedLex?.primaryActionCode)
                val objectType = com.wae.agent.core.nlu.ActionSelectorTable.objectTypeOf(
                    residue = cleanTargetNoun,
                    appAlias = resolvedApp?.matchedAlias.orEmpty()
                )
                val legacyKind = when (matchedLex?.primaryActionCode) {
                    'o' -> ActionKind.LAUNCH_APP
                    'r' -> ActionKind.SCROLL_DOWN
                    'b' -> ActionKind.BACK
                    't', 's' -> ActionKind.TYPE
                    else -> ActionKind.TAP
                }
                var actionKind = com.wae.agent.core.nlu.ActionSelectorTable.resolve(
                    verbClass = verbClass,
                    objectType = objectType,
                    default = legacyKind
                )
                // 六個補齊的基礎動作：用語言層觸發詞覆寫動作類型
                val newAction = ActionKeywords.detect(seg)
                if (newAction != null) actionKind = newAction.kind

                // 防護：TYPE 的 payload 不應包含另一個動作詞（例如「點開第一封信」整句）
                // 動作詞判定完全查資料庫，不在此列任何字串。
                if (actionKind == ActionKind.TYPE) {
                    val payloadForCheck = cleanTargetNoun.lowercase()
                    val hitOtherAction = WaeOpenSourceDatabaseRegistry.lexiconMap.entries.any { (term, e) ->
                        term.length >= 1 && term.length <= 3 &&
                            !e.isConnector &&
                            e.primaryActionCode in charArrayOf('c', 'o') &&
                            payloadForCheck.contains(term)
                    }
                    if (hitOtherAction) {
                        actionKind = ActionKind.TAP
                    }
                }
                goals.add(
                    LocalGoal(
                        stepIdx = stepCounter++,
                        goalId = "goal_${stepCounter}",
                        goalText = seg,
                        canonicalIntentTag = canonical,
                        actionKind = actionKind,
                        inputPayload = when {
                            newAction?.kind == ActionKind.REPLACE_TEXT && newAction.payload.isNotEmpty() ->
                                newAction.payload
                            // 數值調整：「把亮度調高一點」的參數是「亮度」，不是整句。
                            // 另外要清掉導覽方向詞（「進入顯示 亮度」→「顯示 亮度」）。
                            newAction?.kind == ActionKind.ADJUST_VALUE && newAction.payload.isNotEmpty() ->
                                com.wae.agent.core.nlu.GrammarParticles
                                    .stripLeadingDirections(newAction.payload)
                                    .ifBlank { newAction.payload }
                            else -> cleanTargetNoun
                        },
                        ambiguityPolicy = AmbiguityPolicy.AUTO_RESOLVE,
                        targetPackageHint = targetPkgHint,
                        // 類別級語意只對「點擊」類目標有意義（鍵入/搜尋仍需具體內容）
                        targetSpecificity = if (actionKind == ActionKind.TAP) {
                            detectTargetSpecificity(seg, cleanTargetNoun)
                        } else {
                            TargetSpecificity.INSTANCE
                        }
                    )
                )
            }
        }

        // S28：依「原始指令中的連接詞」標記步驟依賴（序列 vs 獨立）

        runCatching {

            var searchFrom = 0

            val marked = ArrayList<com.wae.agent.core.LocalGoal>(goals.size)

            for ((i, g) in goals.withIndex()) {

                if (i == 0) { marked.add(g); continue }

                val idx = rawCommand.indexOf(g.goalText, searchFrom)

                if (idx < 0) { marked.add(g); continue }

                val prefix = rawCommand.substring(searchFrom, idx)

                searchFrom = idx + g.goalText.length

                val sequential = com.wae.agent.core.nlu.GoalConnectors.endsWithSequentialConnector(prefix)

                marked.add(if (sequential) g.copy(dependsOnPrevious = true) else g)

            }

            return marked

        }

        return goals

    }

    /**
     * 判斷目標是「類別級」還是「實例級」——**只依賴語言層的量詞/代詞資料庫，不含任何 App 資料**。
     *
     * 資料來源：`zh_classifier_lexicon.tsv`
     *   - MEASURE / MEASURE_MULTI：由 CC-CEDICT 的 `CL:` 量詞標注 + jieba 詞性彙整
     *   - PRONOUN / NUMERAL：jieba 的 r / m 詞性
     *
     * 例：
     *  - 「點一封信」「隨機打開一個影片」「任何一則通知」「第一個商品」→ CLASS
     *  - 「點開晴天」「搜尋台北101」                                      → INSTANCE
     */
    fun detectTargetSpecificity(goalText: String, targetNoun: String = ""): TargetSpecificity {
        WaeOpenSourceDatabaseRegistry.ensureLoaded()
        val text = goalText.lowercase()
        val payload = targetNoun.lowercase()

        // 1) 多字量詞（如「平方米」）：直接命中
        if (WaeOpenSourceDatabaseRegistry.multiCharMeasureWords.any { text.contains(it) }) {
            return TargetSpecificity.CLASS
        }

        // 2) 不定代詞（長度 ≥2，避免單字代詞在詞內誤命中，如「本地」「其他」）
        val indefiniteHints = listOf("任何", "任一", "某個", "某个", "每一", "每個", "每个", "其中", "有些", "某个")
        if (indefiniteHints.any { text.contains(it) }) return TargetSpecificity.CLASS
        if (WaeOpenSourceDatabaseRegistry.pronouns.any { it.length >= 2 && text.contains(it) }) {
            return TargetSpecificity.CLASS
        }

        // 3) 「數詞 + 量詞」結構（如「一封」「三個」「第一則」「兩篇」）
        if (hasNumeralMeasurePattern(text)) return TargetSpecificity.CLASS

        // 4) 完全沒給標的（只說動作）→ 視為類別級
        if (payload.isEmpty() && text.isNotEmpty()) return TargetSpecificity.CLASS

        return TargetSpecificity.INSTANCE
    }

    /** 掃描「數詞（或阿拉伯數字）+ 單字量詞」的組合 */
    private fun hasNumeralMeasurePattern(text: String): Boolean {
        val reg = WaeOpenSourceDatabaseRegistry
        for (i in 1 until text.length) {
            val ch = text[i].toString()
            if (ch !in reg.singleCharMeasureWords) continue
            val prevChar = text[i - 1]
            // 阿拉伯數字
            if (prevChar.isDigit()) return true
            // 任何 CJK 數詞（含繁體：兩/壹/貳…），用 Unicode 數值判定，不需另建字幕
            if (Character.getNumericValue(prevChar) >= 0) return true
            // 詞庫裡的數詞（處理「十」「百」等複合）
            if (reg.numerals.contains(prevChar.toString())) return true
            if (i >= 2 && reg.numerals.contains(text.substring(i - 2, i))) return true
        }
        return false
    }

    /**
     * 根據 `command_lexicon.tsv` 中的連接詞標籤 (tagExpr 以 1 或 2 開頭) 與動作能量躍遷切分子句
     */
    /**
     * 把受詞結尾殘留的動作詞去掉（資料驅動：查 command_lexicon）。
     *
     * 例：「飛航模式關閉」→「飛航模式」；「亮度調高」→「亮度」
     * 這是 S27 開關狀態推理與 ADJUST_VALUE 定位的共同前置清理。
     */
    private fun stripTrailingActionTerm(text: String): String {
        var t = text.trim()
        var guard = 0
        while (guard++ < 3 && t.isNotEmpty()) {
            var stripped = false
            for (len in minOf(4, t.length) downTo 1) {
                val sub = t.takeLast(len)
                val entry = WaeOpenSourceDatabaseRegistry.lexiconMap[sub]
                if (entry != null && !entry.isConnector) {
                    val rest = t.dropLast(len).trim()
                    if (rest.isNotEmpty()) {
                        t = rest
                        stripped = true
                    }
                    break
                }
            }
            if (!stripped) break
        }
        return t
    }

    private fun splitByDatabaseConnectorsAndActionBoundaries(sentence: String): List<String> {
        val lower = sentence.lowercase()
        val n = sentence.length
        val cutRanges = mutableListOf<IntRange>()

        // 1. 先從 command_lexicon.tsv 找出標記為 isConnector (1/2) 的詞彙區間
        var i = 0
        while (i < n) {
            var matchedLen = 0
            for (len in min(6, n - i) downTo 1) {
                val sub = lower.substring(i, i + len)
                val entry = WaeOpenSourceDatabaseRegistry.lexiconMap[sub]
                if (entry != null && entry.isConnector) {
                    matchedLen = len
                    break
                }
            }
            if (matchedLen > 0) {
                cutRanges.add(i until (i + matchedLen))
                i += matchedLen
            } else {
                i++
            }
        }

        val initialClauses = mutableListOf<String>()
        var lastEnd = 0
        for (r in cutRanges) {
            if (r.first > lastEnd) {
                val part = sentence.substring(lastEnd, r.first).trim()
                if (part.isNotEmpty()) initialClauses.add(part)
            }
            lastEnd = r.last + 1
        }
        if (lastEnd < n) {
            val tail = sentence.substring(lastEnd).trim()
            if (tail.isNotEmpty()) initialClauses.add(tail)
        }

        // 2. 對每個子句，若前段為 +Q (搜尋/輸入)，而後段再次出現高置信度點擊動作詞 (c, conf >= 0.90)，在第二個動作詞處切分
        val finalSegments = mutableListOf<String>()
        for (clause in initialClauses.ifEmpty { listOf(sentence) }) {
            val firstLex = findLongestLeadingActionLexicon(clause)
            if (firstLex != null && firstLex.expectsQueryPayload) {
                val afterFirstIdx = clause.lowercase().indexOf(firstLex.term) + firstLex.term.length
                var secondActionSplitIdx = -1
                var pos = afterFirstIdx + 1
                val cLower = clause.lowercase()
                while (pos < clause.length) {
                    for (len in min(6, clause.length - pos) downTo 2) {
                        val sub = cLower.substring(pos, pos + len)
                        val entry = WaeOpenSourceDatabaseRegistry.lexiconMap[sub]
                        if (entry != null && entry.primaryActionCode == 'c' && entry.confidence >= 0.90f) {
                            secondActionSplitIdx = pos
                            break
                        }
                    }
                    if (secondActionSplitIdx >= 0) break
                    pos++
                }
                if (secondActionSplitIdx > afterFirstIdx) {
                    finalSegments.add(clause.substring(0, secondActionSplitIdx).trim())
                    finalSegments.add(clause.substring(secondActionSplitIdx).trim())
                    continue
                }
            }
            finalSegments.add(clause)
        }

        return finalSegments.filter { it.isNotEmpty() }
    }

    private fun findLongestLeadingActionLexicon(segment: String): WaeOpenSourceDatabaseRegistry.LexiconEntry? {
        val lower = segment.lowercase()
        var bestEntry: WaeOpenSourceDatabaseRegistry.LexiconEntry? = null
        var bestPos = Int.MAX_VALUE
        var bestLen = 0

        for (i in lower.indices) {
            for (len in min(8, lower.length - i) downTo 1) {
                val sub = lower.substring(i, i + len)
                val entry = WaeOpenSourceDatabaseRegistry.lexiconMap[sub]
                if (entry != null && !entry.isConnector &&
                    entry.primaryActionCode in charArrayOf('o', 's', 't', 'c', 'b', 'r', 'p')
                ) {
                    if (entry.expectsQueryPayload && len >= 2) {
                        return entry
                    }
                    if (len >= 2 && (i < bestPos || (i == bestPos && len > bestLen))) {
                        bestPos = i
                        bestLen = len
                        bestEntry = entry
                    } else if (bestEntry == null && len == 1 && i == 0) {
                        bestPos = 0
                        bestLen = 1
                        bestEntry = entry
                    }
                }
            }
        }
        return bestEntry
    }

    // ========================================================================
    // 2. 六維度確定性硬閘門 (6-Dimension Actionability Gate)
    // ========================================================================
    @Synchronized
    fun recordDeadClickNode(activeWindowId: Int, nodeStableSig: Long) {
        if (activeWindowId != lastBlacklistWindowId) {
            deadClickBlacklistLru.clear()
            lastBlacklistWindowId = activeWindowId
        }
        deadClickBlacklistLru["$activeWindowId|$nodeStableSig"] = true
    }

    @Synchronized
    fun isNodeBlacklisted(activeWindowId: Int, nodeStableSig: Long): Boolean {
        if (activeWindowId != lastBlacklistWindowId) {
            deadClickBlacklistLru.clear()
            lastBlacklistWindowId = activeWindowId
            return false
        }
        return deadClickBlacklistLru.containsKey("$activeWindowId|$nodeStableSig")
    }

    @Synchronized
    fun clearDeadClickBlacklistOnWindowChange(newWindowId: Int) {
        if (newWindowId != lastBlacklistWindowId) {
            deadClickBlacklistLru.clear()
            lastBlacklistWindowId = newWindowId
        }
    }

    fun passesSixDimensionActionabilityGate(
        cand: CandidateOption,
        activeWindowId: Int,
        screenW: Int,
        screenH: Int,
        statusBarBottom: Int,
        navBarTop: Int,
        imeTop: Int,
        isBehindModalWindow: Boolean,
        density: Float = 2.75f
    ): Boolean {
        if (isBehindModalWindow) return false
        if (!cand.isEnabled) return false

        val minPx = (8f * density).toInt().coerceAtLeast(8)
        if (cand.width() < minPx || cand.height() < minPx) return false
        val screenArea = (screenW * screenH).coerceAtLeast(1)
        if (!cand.isScrollable && cand.area() > screenArea * 0.85f) return false

        val vpTop = 0
        val vpBottom = screenH
        val interLeft = max(0, cand.grid[0])
        val interTop = max(vpTop, cand.grid[1])
        val interRight = min(screenW, cand.grid[2])
        val interBottom = min(vpBottom, cand.grid[3])
        val interArea = (interRight - interLeft).coerceAtLeast(0) * (interBottom - interTop).coerceAtLeast(0)
        if (cand.area() <= 0 || interArea.toFloat() / cand.area().toFloat() < 0.50f) return false

        if (imeTop in 1 until screenH && cand.grid[3] > imeTop) return false
        if (isNodeBlacklisted(activeWindowId, cand.nodeStableSig)) return false

        return true
    }

    // ========================================================================
    // 3. L1.5 中英混合召回：查詢哈工大同義詞詞林 (16,505 組) + C++ MiniLM 384-D INT8 模型
    // ========================================================================
    fun computeDatabaseAndBigramScore(goal: LocalGoal, cand: CandidateOption): Float {
        WaeOpenSourceDatabaseRegistry.ensureLoaded()
        val candText = cand.coreText.trim()
        val goalText = goal.goalText.trim()
        val targetNoun = goal.inputPayload.trim().ifEmpty { goalText }
        val actionVerb = if (goal.inputPayload.isNotEmpty()) {
            goalText.replaceFirst(goal.inputPayload, "").trim()
        } else ""
        var score = 0f

        // 1. 查詢哈工大同義詞詞林與 command_lexicon 角色一致性
        if (cand.roleTag.isNotEmpty() && cand.roleTag == goal.canonicalIntentTag) {
            score = max(score, 0.84f)
        }
        if (candText.isNotEmpty() &&
            (WaeOpenSourceDatabaseRegistry.areWordsSynonymousInDatabase(goalText, candText) ||
                WaeOpenSourceDatabaseRegistry.areWordsSynonymousInDatabase(targetNoun, candText))
        ) {
            score = max(score, 0.86f)
        }
        if (candText.isNotEmpty() && actionVerb.isNotEmpty() &&
            (candText.equals(actionVerb, ignoreCase = true) ||
                WaeOpenSourceDatabaseRegistry.areWordsSynonymousInDatabase(actionVerb, candText))
        ) {
            score = max(score, 0.88f)
        }

        // 2. 控件結構屬性匹配 (TYPE 動作對應 isEditable 輸入框)
        if (goal.actionKind == ActionKind.TYPE && cand.isEditable) {
            score = max(score, 0.92f)
        }

        // 3. 目標名詞雙向子字串與字元召回率 (避免長標題因分母過大而被誤殺)
        if (candText.isNotEmpty() && goalText.isNotEmpty()) {
            val cLower = candText.lowercase()
            val gLower = goalText.lowercase()
            val tLower = targetNoun.lowercase()

            if (tLower.isNotEmpty() && (cLower.contains(tLower) || tLower.contains(cLower))) {
                score = max(score, 0.90f)
            }

            var hitsGoal = 0
            for (ch in cLower) {
                if (!ch.isWhitespace() && gLower.contains(ch)) hitsGoal++
            }
            val ratioCand = hitsGoal.toFloat() / cLower.length.coerceAtLeast(1).toFloat()

            var hitsTarget = 0
            if (tLower.isNotEmpty()) {
                for (ch in tLower) {
                    if (!ch.isWhitespace() && cLower.contains(ch)) hitsTarget++
                }
            }
            val ratioTarget = if (tLower.isNotEmpty()) {
                hitsTarget.toFloat() / tLower.length.coerceAtLeast(1).toFloat()
            } else 0f

            score = max(score, max(ratioCand, ratioTarget) * 0.84f)
        }

        // 4. TYPE (輸入/搜尋) 目標專屬約束：
        //    - 底部導航頁籤（如 Google Maps 的「搜索」Tab）與輸入框同名時必須排除，否則會反覆點頁籤
        //    - 真正的搜尋/輸入入口（含 isEditable 回報 false 的 omnibox 偽輸入框）必須強制排到最前
        if (goal.actionKind == ActionKind.TYPE) {
            if (cand.isBottomNavigationTab) return 0.40f
            if (cand.isSearchAffordance) {
                // 四檔排序（避免把所有 EditText / 同名圖標拍平成同分而誤判歧義）：
                //   0.97 控件 id 直接就是搜尋框（search_* / omnibox_* / *_query）
                //   0.92 輸入框 + 文字帶搜尋語意
                //   0.90 一般輸入框（如留言框 —— 可輸入但不是搜尋目標）
                //   0.86 只有名字帶「搜尋」的純圖標（語音搜尋麥克風、放大鏡按鈕）
                score = when {
                    cand.hasSearchIdHint -> max(score, 0.97f)
                    cand.isTextInputLike && cand.hasSearchHint -> max(score, 0.92f)
                    cand.isTextInputLike -> max(score, 0.90f)
                    else -> max(score, 0.86f)
                }
            }
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * 「導覽意圖」前綴（語言層，跨 App 通用）。
     * 複合指令若以這些詞開頭（「進入顯示設定…」「打開設定…」），
     * 導覽那半句必須保留成獨立目標，不能被 App 別名解析吃掉。
     */
    private val OPEN_INTENT_PREFIXES = listOf("進入", "进入", "打開", "打开", "開啟", "开启", "前往")

    /** 這些動作若落在唯讀元件上，一定是選錯了（點進度條 / 點純顯示文字） */
    private val READ_ONLY_SENSITIVE_ACTIONS = setOf(
        ActionKind.TAP,
        ActionKind.SUBMIT,
        ActionKind.LONG_PRESS,
        ActionKind.ADJUST_VALUE
    )

    /** 分類頁相似度（bigram 重疊；資料驅動的 section hints 當目標） */
    private fun sectionScore(c: CandidateOption, hints: List<String>): Float {
        val text = (c.coreText + " " + c.weldedDescription).lowercase()
        if (text.isBlank()) return 0f
        var best = 0f
        for (h in hints) {
            if (text.contains(h)) return 1f
            val hb = h.windowed(2).toSet()
            if (hb.isEmpty()) continue
            val cb = text.windowed(2).toSet()
            best = maxOf(best, hb.count { it in cb }.toFloat() / hb.size)
        }
        return best
    }

    /**
     * S27：開關／勾選項的狀態推理。
     *
     * - 從指令解析 desired（開啟/關閉）
     * - 只考慮「真的可切換」的候選（isCheckable / TOGGLE 能力）
     * - 已經在目標狀態 → 回傳 `alreadySatisfied`（不需要動作）
     * - 否則把該候選當成明確目標（不再走一般語意競賽）
     *
     * 找不到可切換元件 → 回 null（交回一般決策，不影響其他 TAP 任務）。
     */
    private fun evaluateToggleDecision(
        goal: LocalGoal,
        optionSet: ImmutableOptionSet
    ): CuaDecisionResult? {
        val intent = com.wae.agent.core.nlu.ToggleIntent.parseForToggle(goal.goalText, goal.actionKind)
            ?: com.wae.agent.core.nlu.ToggleIntent.parseForToggle(goal.inputPayload, goal.actionKind)
            ?: return null
        val toggles = optionSet.selectableOptions.filter {
            com.wae.agent.core.nlu.ToggleIntent.isToggleCandidate(it)
        }
        val target = intent.target

        fun matchScoreOf(c: CandidateOption, t: String): Float {
            val text = (c.coreText + " " + c.weldedDescription).trim()
            if (t.isEmpty() || text.isEmpty()) return 0f
            val tBigrams = t.windowed(2).toSet()
            if (tBigrams.isEmpty()) return 0f
            val cBigrams = text.lowercase().windowed(2).toSet()
            val overlap = tBigrams.count { it in cBigrams }.toFloat() / tBigrams.size
            val direct = if (text.contains(t) || t.contains(text.take(8))) 1f else 0f
            return maxOf(overlap, direct)
        }

        fun drillToSection(reason: String): CuaDecisionResult? {
            // 目標開關不在這一頁 → 導覽到它所在的分類頁（例：飛航模式在三星「連接」、小米「連接與共享」）
            val hints = com.wae.agent.core.nlu.ToggleSectionMap.sectionHints(
                target,
                android.os.Build.MANUFACTURER
            )
            if (hints.isEmpty()) return null
            val section = optionSet.selectableOptions
                .map { c -> c to sectionScore(c, hints) }
                .filter { it.second >= 0.5f }
                // 不要把「目前所在頁」再點一次（例如已在「連接」頁又點「連接」）
                .filter { it.first.coreText.length in 2..16 }
                .maxByOrNull { it.second }?.first
                ?: return null
            runCatching {
                Log.i(
                    "WAE_TOGGLE",
                    "$reason target='$target' → drill section='${section.coreText.take(16)}' " +
                        "(score=%.2f)".format(sectionScore(section, hints))
                )
            }
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.CLEAR_WIN,
                selectedOption = section,
                top1Prob = 1f,
                top2Prob = 0f,
                margin = 1f,
                reason = "TOGGLE_DRILL_SECTION(target='$target', section='${section.coreText.take(16)}')",
                continueAfterDispatch = true
            )
        }

        val bestToggle = toggles.maxByOrNull { matchScoreOf(it, target) }
        val bestScore = bestToggle?.let { matchScoreOf(it, target) } ?: 0f
        runCatching {
            Log.i(
                "WAE_TOGGLE",
                "evaluate target='$target' desired=${intent.desired} toggles=${toggles.size} " +
                    "best='${bestToggle?.coreText?.take(20)}' score=%.2f".format(bestScore)
            )
        }
        if (bestToggle == null || bestScore < 0.34f) {
            return drillToSection("target not found on this page")
        }


        val best = bestToggle
        val score = bestScore

        if (com.wae.agent.core.nlu.ToggleIntent.alreadySatisfied(best, intent.desired)) {
            runCatching {
                Log.i(
                    "WAE_TOGGLE",
                    "already satisfied target='${intent.target}' " +
                        "desired=${if (intent.desired) "ON" else "OFF"} " +
                        "current=${if (best.isChecked) "ON" else "OFF"} → 不派發動作"
                )
            }
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.CLEAR_WIN,
                selectedOption = best,
                top1Prob = 1f,
                top2Prob = 0f,
                margin = 1f,
                reason = "TOGGLE_ALREADY_SATISFIED(target='${intent.target}', " +
                    "desired=${if (intent.desired) "ON" else "OFF"})",
                alreadySatisfied = true
            )
        }

        runCatching {
            Log.i(
                "WAE_TOGGLE",
                "state reasoning target='${intent.target}' " +
                    "desired=${if (intent.desired) "ON" else "OFF"} " +
                    "current=${if (best.isChecked) "ON" else "OFF"} match=%.2f " +
                    "chosen='${best.coreText.take(20)}'".format(score)
            )
        }
        return CuaDecisionResult(
            frameId = optionSet.frameId,
            windowIdx = optionSet.windowIdx,
            outcomeType = DecisionOutcomeType.CLEAR_WIN,
            selectedOption = best,
            top1Prob = 1f,
            top2Prob = 0f,
            margin = 1f,
            reason = "TOGGLE_STATE_REASONING(target='${intent.target}', " +
                "desired=${if (intent.desired) "ON" else "OFF"}, " +
                "current=${if (best.isChecked) "ON" else "OFF"})"
        )
    }

    /**
     * 數值調整的目標提升（元件能力圖譜的實際用途）。
     *
     * 使用者的「把亮度調高一點」在語意上指向**文字標籤**（設定列），
     * 但物理上要操作的是它旁邊那根**滑桿**。滑桿本身沒有文字，語意分永遠很低，
     * 若不做這一步就永遠選不到它。
     *
     * 三步：
     *  1. 找出語意最像的「有文字候選」（標籤）
     *  2. 用幾何關係把標籤配到可調元件（正下方 / 同列右側 / 包含）
     *  3. 把該可調元件提升到標籤之上（幅度 0.05，足以定序，不會變成亂蓋）
     *
     * 例外：整頁只有唯一一個可調元件、且沒有任何標籤夠像時，唯一性本身就是證據。
     */
    private fun boostAdjustableValueTarget(candidates: List<CandidateOption>): List<CandidateOption> {
        if (candidates.isEmpty()) return candidates
        val adjustable = candidates.filter { it.canAdjustValue && it.isEnabled }
        if (adjustable.isEmpty()) return candidates

        val label = candidates
            .filter { it.coreText.isNotBlank() }
            .maxByOrNull { it.hybridSimScore }

        if (label == null || label.hybridSimScore < 0.30f) {
            if (adjustable.size == 1) {
                val only = adjustable[0]
                val boosted = maxOf(only.hybridSimScore, 0.55f)
                return candidates.map { if (it === only) it.copy(hybridSimScore = boosted) else it }
            }
            return candidates
        }

        val match = com.wae.agent.core.component.ValueTargetResolver.associate(label, candidates)
            ?: return candidates
        val boosted = maxOf(match.target.hybridSimScore, label.hybridSimScore + 0.05f)
        return candidates.map {
            if (it === match.target) it.copy(hybridSimScore = boosted.coerceIn(0f, 1f)) else it
        }
    }

    private fun deduplicateOverlappingOrNestedCandidates(sorted: List<CandidateOption>): List<CandidateOption> {
        if (sorted.size <= 1) return sorted
        val result = ArrayList<CandidateOption>(sorted.size)
        for (cand in sorted) {
            val isDuplicateOfExisting = result.any { kept ->
                val sameLabel = kept.coreText.isNotEmpty() &&
                    kept.coreText.equals(cand.coreText, ignoreCase = true)
                val interLeft = max(kept.grid[0], cand.grid[0])
                val interTop = max(kept.grid[1], cand.grid[1])
                val interRight = min(kept.grid[2], cand.grid[2])
                val interBottom = min(kept.grid[3], cand.grid[3])
                val interArea = (interRight - interLeft).coerceAtLeast(0) *
                    (interBottom - interTop).coerceAtLeast(0)
                val minArea = min(kept.area(), cand.area()).coerceAtLeast(1)
                val highSpatialOverlap = interArea.toFloat() / minArea.toFloat() >= 0.70f
                // 同標籤且高度重疊 → 重複
                val sameLabelDuplicate = sameLabel && highSpatialOverlap
                // 「分數相同且高度重疊」原本一律視為重複，但快路徑（collectOnly）下所有候選
                // 分數都是 0，這條規則會把「有文字的節點」全砍掉、只留可點外框，
                // 導致整頁候選都沒有文字（實測：設定首頁 9 個無文字候選、點下去毫無反應）。
                // 因此只有「兩者都沒有語意證據」時才允許用分數相同去重。
                val scoreTieDuplicate = highSpatialOverlap &&
                    kotlin.math.abs(kept.hybridSimScore - cand.hybridSimScore) < 0.01f &&
                    kept.coreText.isEmpty() && cand.coreText.isEmpty()
                sameLabelDuplicate || scoreTieDuplicate
            }
            if (!isDuplicateOfExisting) {
                result.add(cand)
            }
        }
        return result
    }

    /** 與服務層一致的選擇器鍵（遥測用） */
    private fun descriptionKeyOf(c: CandidateOption): String =
        com.wae.agent.core.action.SelectorKeys.of(c)

    /** 對外統一的選擇器鍵（打分、遙測、負向邊必須用同一個鍵，否則永遠對不上） */
    fun candidateSelectorKey(c: CandidateOption): String = descriptionKeyOf(c)

    fun buildScoredCandidatesAndOptionSet(
        frameId: Long,
        windowIdx: Int,
        activeWindowId: Int,
        packageName: String,
        postureState: DualPostureState,
        pageSpineSigs: LongArray,
        pageSpineHash: Long,
        goal: LocalGoal,
        rawCandidates: List<CandidateOption>,
        window1Top2RejectedPrompts: List<String> = emptyList(),
        /**
         * M3b：只做「便宜打分」（詞典/雙字），跳過 MiniLM 前向。
         * 供 Twin 快路徑先判斷是否命中；未命中時再由呼叫端補算。
         */
        skipSemanticScoring: Boolean = false
    ): Pair<List<CandidateOption>, ImmutableOptionSet> {
        if (rawCandidates.isEmpty()) {
            return Pair(
                emptyList(),
                ImmutableOptionSet(
                    frameId = frameId,
                    windowIdx = windowIdx,
                    activeWindowId = activeWindowId,
                    packageName = packageName,
                    postureState = postureState,
                    pageSpineSigs = pageSpineSigs,
                    pageSpineHash = pageSpineHash,
                    rejectedContextPrompts = window1Top2RejectedPrompts,
                    selectableOptions = emptyList()
                )
            )
        }

        val descriptions = Array(rawCandidates.size) { i ->
            rawCandidates[i].coreText.ifEmpty { rawCandidates[i].weldedDescription }
        }

        // 先算便宜的詞典/雙字分數（微秒級），只把最可能的前 N 名送進 MiniLM 前向。
        // 目的：把每輪 embedding 成本硬性封頂（A55 實測 ~16ms/條），
        // 避免繁忙頁面 100+ 候選時每輪多花 1.5s+。
        val cheapScores = FloatArray(rawCandidates.size) { i ->
            computeDatabaseAndBigramScore(goal, rawCandidates[i])
        }
        val embedBudget = 24
        val embeddingIndices = rawCandidates.indices
            .sortedByDescending { cheapScores[it] }
            .take(embedBudget)
        val miniLmTopScores = if (skipSemanticScoring) {
            // M3b：快路徑候選階段不做 embedding（省 60–500ms），僅用便宜分數排序
            FloatArray(0)
        } else {
            com.wae.agent.core.nlp.WaeTextSemantics.batchCosine(
                goalText = goal.goalText,
                canonicalTag = goal.canonicalIntentTag,
                candidates = embeddingIndices.map { descriptions[it] }
            )
        }
        val miniLmScores = FloatArray(rawCandidates.size)
        embeddingIndices.forEachIndexed { k, candIdx ->
            miniLmScores[candIdx] = miniLmTopScores.getOrElse(k) { 0f }
        }

        val scoredRaw = rawCandidates.mapIndexed { idx, cand ->
            val sDb = cheapScores[idx]
            val sMini = miniLmScores[idx]
            var hybrid = max(cand.hybridSimScore, max(sDb, sMini)).coerceIn(0f, 1f)
            // TYPE（輸入/搜尋）目標只可能落在可操作控件上：
            // 真 embedding 會讓「含關鍵詞的說明文字」拿到 0.9x 高分（實測抖音影片說明 sim=0.90），
            // 但它既不可點也不可輸入，選中只會白跑一輪。因此對不可操作候選做降權。
            if (goal.actionKind == ActionKind.TYPE) {
                val actionable = cand.isClickable || cand.isEditable || cand.isSearchAffordance
                if (!actionable) hybrid *= 0.75f
            }
            // P6：遥測降權——歷史成功率低的選擇器扣分（不封鎖，讓次佳候選有機會）
            if (com.wae.agent.core.action.ActionTelemetry.isDemoted(packageName, descriptionKeyOf(cand))) {
                hybrid = (hybrid - com.wae.agent.core.action.ActionTelemetry.DEMOTE_PENALTY).coerceAtLeast(0f)
            }
            // 元件能力圖譜：唯讀元件（進度條 / 純顯示節點）不該被當成點擊目標
            if (cand.isReadOnlyOnly && goal.actionKind in READ_ONLY_SENSITIVE_ACTIONS) {
                hybrid *= 0.75f
            }
            // 負向邊（S2）：失敗過的路扣分；已判定為禁區的路強力壓低（避免重複犯錯）
            run {
                val negKey = descriptionKeyOf(cand)
                val opName = goal.actionKind.name
                if (com.wae.agent.core.action.NegativeEdgeStore.isBlocked(packageName, negKey, opName)) {
                    hybrid *= com.wae.agent.core.action.NegativeEdgeStore.BLOCKED_FACTOR
                } else {
                    val p = com.wae.agent.core.action.NegativeEdgeStore.penalty(packageName, negKey, opName)
                    if (p > 0f) hybrid = (hybrid - p).coerceAtLeast(0f)
                }
            }
            cand.copy(hybridSimScore = hybrid)
        }.sortedWith(
            compareByDescending<CandidateOption> { it.hybridSimScore }
                // 同分時「自己說得出自己是什麼」的候選優先：
                // 否則可點的空容器會靠 isClickable 壓過真正帶文字的節點，
                // 造成整頁 top10 全是無文字外框（實測：設定首頁完全無法導覽）。
                .thenByDescending { it.hasOwnSemanticEvidence }
                .thenByDescending { it.isEditable }
                .thenByDescending { it.isClickable }
                .thenBy { it.grid[1] }
                .thenBy { it.grid[0] }
        )

        val scoredAll = if (skipSemanticScoring) {
            // 快路徑只採集、不打分：此時所有分數相同，去重沒有判別依據，
            // 硬去重反而會丟掉有文字的節點（見 deduplicateOverlappingOrNestedCandidates 的註解）。
            if (goal.actionKind == ActionKind.ADJUST_VALUE) boostAdjustableValueTarget(scoredRaw) else scoredRaw
        } else {
            deduplicateOverlappingOrNestedCandidates(
                if (goal.actionKind == ActionKind.ADJUST_VALUE) boostAdjustableValueTarget(scoredRaw) else scoredRaw
            )
        }

        val selectable = if (windowIdx == 0) {
            // 數值調整要看「整頁所有可調元件與分類入口」：只取前 10 名時，
            // 真正要操作的滑桿/分類列常常排在第 10 名之後（實測：顯示螢幕根本沒進候選）
            if (goal.actionKind == ActionKind.ADJUST_VALUE) scoredAll.take(40) else scoredAll.take(10)
        } else {
            if (scoredAll.size > 10) {
                scoredAll.drop(10).take(8)
            } else {
                scoredAll.take(10)
            }
        }

        val optionSet = ImmutableOptionSet(
            frameId = frameId,
            windowIdx = windowIdx,
            activeWindowId = activeWindowId,
            packageName = packageName,
            postureState = postureState,
            pageSpineSigs = pageSpineSigs,
            pageSpineHash = pageSpineHash,
            rejectedContextPrompts = if (windowIdx == 1) window1Top2RejectedPrompts else emptyList(),
            selectableOptions = selectable
        )

        return Pair(scoredAll, optionSet)
    }

    // ========================================================================
    // 4. L3 CUA-S1 決策 + 自適應三條件門檻 + 顯式歧義檢測 (CLEAR_WIN / AMBIGUOUS / REJECT)
    // ========================================================================
    /**
     * 數值調整的專用決策（不看一般語意競賽）。
     *
     * 一般 L3 是「哪段文字最像指令」，但調整數值要操作的是**沒有文字**的滑桿，
     * 而畫面上最像指令的文字往往是毫不相關的東西（實測在主題頁把「亮度」判給「黑色」0.86）。
     * 因此這裡完全依「元件能力」決策，並把語意只用在「挑哪一列鑽下去」。
     */
    private fun evaluateAdjustValueDecision(
        goal: LocalGoal,
        optionSet: ImmutableOptionSet
    ): CuaDecisionResult {
        val options = optionSet.selectableOptions
        fun build(
            type: DecisionOutcomeType,
            selected: CandidateOption?,
            reason: String
        ) = CuaDecisionResult(
            frameId = optionSet.frameId,
            windowIdx = optionSet.windowIdx,
            outcomeType = type,
            selectedOption = selected,
            top1Prob = 0f,
            top2Prob = 0f,
            margin = 0f,
            reason = reason
        )

        // 1) 頁面上有可調元件 → 由「標籤 ↔ 可調元件」配對決定
        val adjustable = options.filter { it.canAdjustValue && it.isEnabled }
        if (adjustable.isNotEmpty()) {
            val label = options.filter { it.hasOwnSemanticEvidence }.maxByOrNull { it.hybridSimScore }
            val match = if (label != null) {
                com.wae.agent.core.component.ValueTargetResolver.associate(label, options)
            } else {
                null
            }
            val pick = match?.target ?: adjustable.maxByOrNull { it.hybridSimScore } ?: adjustable.first()
            return build(
                DecisionOutcomeType.CLEAR_WIN,
                pick,
                "ADJUST_VALUE_TARGET(target='${goal.inputPayload}', " +
                    "label='${label?.coreText?.take(16).orEmpty()}', " +
                    "relation=${match?.relation ?: "only-adjustable"}, " +
                    "range=${pick.rangeMin}~${pick.rangeMax}@${pick.rangeCurrent})"
            )
        }

        // 2) 沒有可調元件 → 只往「自己說得出自己是什麼」且分數夠高的列鑽一層
        //
        // 「亮度」→「顯示螢幕」這一步不能只靠語意相似度：實測「已連接的裝置」拿到 0.59、
        // 「顯示螢幕」0.58，於是鑽錯分支，一路跑到不相關頁面（甚至系統權限彈窗）。
        // 這裡用元件能力圖譜補語境：可以調「亮度」的元件家族，它的其他用途詞
        // （顯示大小、字體大小…）就是「亮度」所在的語意群，跟這些詞重疊的列優先。
        val targetNoun = goal.inputPayload.ifBlank {
            com.wae.agent.core.component.ValueIntent.parse(goal.goalText).targetNoun
        }
        val contextTerms = com.wae.agent.core.component.WidgetOntology
            .familiesForPurpose(targetNoun)
            .flatMap { it.purposes }
            .filter { it.length >= 2 && it != targetNoun }
            .distinct()

        fun contextBonus(c: CandidateOption): Float {
            if (contextTerms.isEmpty()) return 0f
            val text = c.coreText
            if (text.length < 2) return 0f
            val cBigrams = text.windowed(2).toSet()
            for (term in contextTerms) {
                val tBigrams = term.windowed(2).toSet()
                if (tBigrams.isEmpty()) continue
                val overlap = tBigrams.count { it in cBigrams }.toFloat() / tBigrams.size
                if (overlap >= 0.30f) return 0.12f
            }
            return 0f
        }

        // 用途 → 所屬分類（資料驅動）：「亮度」屬於「顯示」，所以只鑽「顯示」那一列。
        // 這一步取代了「用 embedding 猜分支」——實測 0.59 vs 0.59 的分數根本無法區分。
        val category = com.wae.agent.core.component.WidgetOntology.categoryFor(targetNoun)

        val base = options
            .filter { it.hasOwnSemanticEvidence || it.hybridSimScore >= 0.60f }
            .filter { !it.isReadOnlyOnly && !it.isEditable }
            .filter { !it.isBottomNavigationTab }
            .filter { it.parentContainerType != "BOTTOM_NAV" && it.parentContainerType != "TOOLBAR" }
        val categoryMatched = if (category != null) {
            base.filter { category.matches(it.coreText) }
        } else {
            emptyList()
        }
        val pool = if (categoryMatched.isNotEmpty()) categoryMatched else base

        val ranked = pool
            .map { it to (it.hybridSimScore + contextBonus(it)) }
            .filter { it.second >= 0.55f }
            .sortedWith(
                compareByDescending<Pair<CandidateOption, Float>> {
                    it.first.parentContainerType == "LIST_ITEM" || it.first.parentContainerType == "CARD"
                }.thenByDescending { it.second }
                    .thenBy { it.first.grid[1] }
            )
        val drill = ranked.firstOrNull()
        if (drill != null) {
            val second = ranked.getOrNull(1)
            val margin = drill.second - (second?.second ?: 0f)
            // 兩個候選幾乎同分時不要硬選：鑽錯分支的代價是整串錯誤操作
            if (second != null && margin < 0.05f) {
                return build(
                    DecisionOutcomeType.REJECT,
                    null,
                    "AMBIGUOUS_NAVIGATION(target='$targetNoun', " +
                        "top1='${drill.first.coreText.take(16)}' ${"%.2f".format(drill.second)}, " +
                        "top2='${second.first.coreText.take(16)}' ${"%.2f".format(second.second)})"
                )
            }
            return build(
                DecisionOutcomeType.CLEAR_WIN,
                drill.first,
                "ADJUST_VALUE_DRILLDOWN(target='$targetNoun', " +
                    "chosen='${drill.first.coreText.take(24)}', " +
                    "score=${"%.2f".format(drill.second)}, margin=${"%.2f".format(margin)})"
            )
        }

        // 3) 兩者都無 → 誠實拒絕（不再亂點）
        return build(
            DecisionOutcomeType.REJECT,
            null,
            "NO_ADJUSTABLE_COMPONENT(target='${goal.inputPayload}', candidates=${options.size})"
        )
    }

    fun evaluateCuaS1AndAmbiguity(
        goal: LocalGoal,
        optionSet: ImmutableOptionSet
    ): CuaDecisionResult {
        val options = optionSet.selectableOptions
        val n = options.size
        if (n == 0) {
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.REJECT,
                selectedOption = null,
                top1Prob = 0f,
                top2Prob = 0f,
                margin = 0f,
                reason = "EMPTY_OPTION_SET"
            )
        }

        // ── 數值調整專用決策（不走一般語意競賽）────────────────────────────
        // 一般 L3 會讓「最像的一段文字」勝出；對「把亮度調高」而言這是錯的，
        // 因為要操作的是滑桿（沒有文字），而畫面上像「黑色」「桌布」這類字反而可能高分。
        // 所以這裡直接依元件能力決策：
        //   1. 頁面上有可調元件 → 由「標籤↔可調元件」配對決定目標
        //   2. 沒有可調元件 → 只往「自己說得出自己是什麼、且真的可點」的列鑽一層
        //   3. 兩者都無 → 誠實拒絕
        if (goal.actionKind == ActionKind.ADJUST_VALUE) {
            return evaluateAdjustValueDecision(goal, optionSet)
        }

        // ── S27：開關狀態推理（能力優先）─────────────────────────────────
        // 「關閉藍牙」如果它本來就是關的 → 不該點（否則會打開）；點完要驗證狀態，
        // 而不是只看「畫面有沒有變」。這一段只在「畫面上真的有可切換元件」時生效。
        if (goal.actionKind == ActionKind.TAP || goal.actionKind == ActionKind.SUBMIT) {
            val toggleDecision = evaluateToggleDecision(goal, optionSet)
            if (toggleDecision != null) return toggleDecision
        }

        // ── S13：能力蒸餾查表（命中 → O(1) 回傳，不必跑 MiniLM 打分）──
        if (com.wae.agent.core.runtime.ExtremeRuntimeHooks.MEMO_ENABLED) {
            runCatching {
                val sig = com.wae.agent.core.automaton.MinHash
                    .fingerprint(optionSet.pageSpineSigs.toList()).firstOrNull() ?: 0L
                val memoHit = com.wae.agent.core.decision.DecisionMemo
                    .recall(optionSet.packageName, sig, goal.canonicalIntentTag)
                if (memoHit != null) {
                    val hit = options.firstOrNull {
                        com.wae.agent.core.action.SelectorKeys.of(it) == memoHit
                    }
                    if (hit != null) {
                        Log.i("WAE_MEMO", "hit pkg=${optionSet.packageName} selector=$memoHit → 跳過打分")
                        return CuaDecisionResult(
                            frameId = optionSet.frameId,
                            windowIdx = optionSet.windowIdx,
                            outcomeType = DecisionOutcomeType.CLEAR_WIN,
                            selectedOption = hit,
                            top1Prob = 1f,
                            top2Prob = 0f,
                            margin = 1f,
                            reason = "MEMO_HIT(selector=$memoHit)"
                        )
                    }
                }
            }
        }

        val candTexts = Array(n) { i -> options[i].coreText.ifEmpty { options[i].weldedDescription } }
        val baseSims = FloatArray(n) { i -> options[i].hybridSimScore }
        val logits = WaeNativeBridge.inferCuaS1LogitsSafe(
            goalText = goal.goalText,
            canonicalTag = goal.canonicalIntentTag,
            candidateTexts = candTexts,
            baseSimScores = baseSims
        )
        // B3 根因修法：不再是「扁平加成」，而是「與動作相關的偏好序列」。
        // TYPE 時：可編輯 > id 像輸入框 > 搜尋入口 > 可點；底部導覽列大幅扣分。
        for (i in 0 until n) {
            logits[i] += com.wae.agent.core.decision.TargetPreference.score(goal.actionKind, options[i])
        }
        // S29 線性決策頭：用既有符號特徵做候選重排序（旗標預設關閉 → 不改變任何行為）。
        // 只看「動作/元素」層的特徵，不引入新的感知成本；權重由離線訓練器產生。
        if (com.wae.agent.core.runtime.ExtremeRuntimeHooks.DECISION_HEAD_ENABLED) {
            for (i in 0 until n) {
                logits[i] += com.wae.agent.core.decision.DecisionHead.candidateBonus(goal, options[i])
            }
        }
        // S32 Grounding 2.0：四通道融合重排序（旗標預設關閉；關閉時完全走舊路徑）。
        // 1) A11Y 候選 → GroundingCandidate，融合 bonus 加進 logits
        // 2) 元素圖（同 App/同頁面狀態/同 selector 的歷史成功率）加成
        // 3) 信心校準 → ACT / ASK / RE_PERCEIVE / SCROLL；非 ACT 就回 REJECT 讓外層重感知
        var groundingBlock: String? = null
        if (com.wae.agent.core.runtime.ExtremeRuntimeHooks.GROUNDING_2_ENABLED && n > 0) {
            runCatching {
                com.wae.agent.core.grounding.GroundingFusion.ensureLoaded(null)
                val gCands = ArrayList<com.wae.agent.core.grounding.GroundingCandidate>(n)
                for (i in 0 until n) {
                    val o = options[i]
                    gCands.add(
                        com.wae.agent.core.grounding.GroundingCandidate(
                            id = i.toString(),
                            channel = com.wae.agent.core.grounding.GroundingChannel.A11Y,
                            text = o.coreText.ifEmpty { o.weldedDescription },
                            className = o.className,
                            viewId = o.viewId,
                            bounds = o.grid,
                            clickable = o.isClickable,
                            editable = o.isEditable,
                            checkable = o.isCheckable,
                            checked = o.isChecked,
                            enabled = o.isEnabled,
                            score = o.hybridSimScore.coerceIn(0f, 1f)
                        )
                    )
                }
                val fusion = com.wae.agent.core.grounding.GroundingFusion.fuse(gCands)
                for (fc in fusion.ranked) {
                    val idx = fc.candidate.id.toIntOrNull() ?: continue
                    if (idx in 0 until n) logits[idx] += fc.bonus
                }

                // 元素圖：pkg + 頁面狀態（pageSpineHash）+ selector 的 Beta 後驗加成
                val stateKey = optionSet.pageSpineHash.toString()
                for (i in 0 until n) {
                    val sel = com.wae.agent.core.action.SelectorKeys.of(options[i])
                    logits[i] += com.wae.agent.core.grounding.ElementGraph
                        .bonus(optionSet.packageName, stateKey, sel)
                }

                // 信心校準 + 不動作決策
                val p1raw = fusion.ranked.getOrNull(0)?.probability ?: 0f
                val p2raw = fusion.ranked.getOrNull(1)?.probability ?: 0f
                val calibrated = com.wae.agent.core.grounding.ConfidenceCalibrator.pSuccess(p1raw)
                val action = com.wae.agent.core.grounding.ConfidenceCalibrator.decide(
                    top1Prob = calibrated,
                    margin = p1raw - p2raw,
                    scrollable = options.any { it.isScrollable }
                )
                android.util.Log.i(
                    "WAE_GROUNDING",
                    "fusion n=${gCands.size} p1=${"%.2f".format(p1raw)} " +
                        "calibrated=${"%.2f".format(calibrated)} action=$action " +
                        "abstain=${fusion.abstain} reason='${fusion.abstainReason}' " +
                        "top1='${fusion.best?.candidate?.text?.take(16)}'"
                )
                if (action != com.wae.agent.core.grounding.ConfidenceCalibrator.GroundingAction.ACT) {
                    groundingBlock = "GROUNDING_${action.name}:p1=${"%.2f".format(calibrated)}"
                }
            }
        }
        if (groundingBlock != null) {
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.REJECT,
                selectedOption = null,
                top1Prob = 0f,
                top2Prob = 0f,
                margin = 0f,
                reason = groundingBlock!!
            )
        }

        val maxLogit = logits.maxOrNull() ?: 0f
        var sumExp = 0f
        val probs = FloatArray(n + 1)
        for (i in logits.indices) {
            val e = exp((logits[i] - maxLogit).toDouble()).toFloat()
            probs[i] = e
            sumExp += e
        }
        for (i in probs.indices) {
            probs[i] = probs[i] / sumExp.coerceAtLeast(1e-6f)
        }

        val rankedIndices = (0 until n).sortedByDescending { probs[it] }
        val top1Idx = rankedIndices[0]
        val top2Idx = rankedIndices.getOrElse(1) { -1 }

        // S29：開發版決策樣本（旗標開啟 + debuggable 才寫，供 tools/decision_head 離線訓練）
        if (com.wae.agent.core.runtime.ExtremeRuntimeHooks.DECISION_HEAD_ENABLED) {
            com.wae.agent.core.decision.DecisionHead.recordCandidateSamples(goal, options, top1Idx)
        }

        val top1Opt = options[top1Idx]
        val p1 = probs[top1Idx]
        val p2 = if (top2Idx >= 0) probs[top2Idx] else 0f
        val probR = probs[n]
        val margin = p1 - p2

        // ── 類別級目標回退（CLASS）──────────────────────────────────────────
        // 使用者只說「隨機點一封信」「任何一個影片」時，畫面元素的文字（信件主旨/影片標題）
        // 本來就不會跟指令同名；若照常走 REJECT 就會出現「打開 App 後什麼都不做」。
        // 這裡改為：在「可操作且非導航頁籤」的候選中擇一（取語意分最高者，結果可復現）。
        if (goal.targetSpecificity == TargetSpecificity.CLASS) {
            // 選「內容項」而不是「動作按鈕」：
            //  - 排除工具列 / 底部導航 / 導航頁籤（那不是內容）
            //  - 優先 LIST_ITEM / CARD 容器（列表行、卡片）
            //  - 其次語意分、再其次面積（列表行通常比 FAB 大）、最後取最上方者（可復現）
            val contentLike = setOf("LIST_ITEM", "CARD")
            val classCandidates = options.filter { it.isClickable || it.isEditable }
                .filter { !it.isBottomNavigationTab }
                .filter { it.parentContainerType != "TOOLBAR" && it.parentContainerType != "BOTTOM_NAV" }

            // A：搜尋框/輸入框不是「內容元素」——空結果頁上若只點得到搜尋框，
            //    會變成反覆把文字打進同一個框。這裡一律排除，讓它誠實地失敗。
            val contentOnly = classCandidates.filter { !it.isSearchAffordance && !it.isEditable }
            // B：完全沒有內容元素 → 視為「查無結果」，不再盲目重試
            if (contentOnly.isEmpty()) {
                return CuaDecisionResult(
                    frameId = optionSet.frameId,
                    windowIdx = optionSet.windowIdx,
                    outcomeType = DecisionOutcomeType.REJECT,
                    selectedOption = null,
                    top1Prob = p1,
                    top2Prob = p2,
                    margin = margin,
                    reason = "EMPTY_CONTENT_SET(target='${goal.inputPayload}')"
                )
            }
            // B4 根因修法：先剔除導覽型列（資料夾/分類/設定入口），
            // 再用「內容項證據分數」排序，而不是只看容器型別與面積。
            val contentItems = contentOnly.filter {
                !com.wae.agent.core.decision.ContentItemEvidence.isNavigationRow(it)
            }
            val classCandidatesFinal = if (contentItems.isNotEmpty()) contentItems else contentOnly
            // 通用動作按鈕（確定/取消/下一頁…）優先度低於內容元素；
            // 詞彙來自 AOSP 多語系資源，跨 App 通用。
            val hasContent = classCandidatesFinal.any { it.parentContainerType in contentLike }
            val fallback = classCandidatesFinal.sortedWith(
                compareByDescending<CandidateOption> { it.parentContainerType in contentLike }
                    .thenBy { if (hasContent &&
                        WaeOpenSourceDatabaseRegistry.isGenericActionLabel(it.coreText)) 1 else 0 }
                    .thenByDescending { com.wae.agent.core.decision.ContentItemEvidence.score(it) }
                    .thenByDescending { it.hybridSimScore }
                    .thenByDescending { (it.grid[2] - it.grid[0]) * (it.grid[3] - it.grid[1]) }
                    .thenBy { it.grid[1] }
            ).firstOrNull()
            // 目標語意與所有內容元素都不相似 → 不要硬點（例如「把亮度調高一點」沒有任何元素符合）
            if (fallback == null || fallback.hybridSimScore < 0.20f) {
                return CuaDecisionResult(
                    frameId = optionSet.frameId,
                    windowIdx = optionSet.windowIdx,
                    outcomeType = DecisionOutcomeType.REJECT,
                    selectedOption = null,
                    top1Prob = p1,
                    top2Prob = p2,
                    margin = margin,
                    reason = "NO_PLAUSIBLE_TARGET(target='${goal.inputPayload}', best=${"%.2f".format(fallback?.hybridSimScore ?: 0f)})"
                )
            }
            if (fallback != null) {
                // 本函式同時跑在 JVM 測試中（android.util.Log 未實作），包起來避免污染決策邏輯
                runCatching {
                    Log.i(
                        "WAE_CLASS",
                        "fallback candidates=" + classCandidatesFinal.take(6).joinToString(" | ") {
                            "'${it.coreText.take(12)}'/${it.parentContainerType}/sim=${"%.2f".format(it.hybridSimScore)}"
                        }
                    )
                }
                return CuaDecisionResult(
                    frameId = optionSet.frameId,
                    windowIdx = optionSet.windowIdx,
                    outcomeType = DecisionOutcomeType.CLEAR_WIN,
                    selectedOption = fallback,
                    top1Prob = p1,
                    top2Prob = p2,
                    margin = margin,
                    reason = "CLASS_LEVEL_FALLBACK(target='${goal.inputPayload}', " +
                        "chosen='${fallback.coreText.take(24)}', " +
                        "container=${fallback.parentContainerType}, sim=${"%.2f".format(fallback.hybridSimScore)}, " +
                        "evidence=${"%.1f".format(com.wae.agent.core.decision.ContentItemEvidence.score(fallback))})"
                )
            }
        }


        val thetaAbs = calibrationConfig.thetaAbs(n)
        val thetaMargin = calibrationConfig.thetaMargin(n)
        val thetaSimFloor = calibrationConfig.simFloor

        val top2Opt = if (top2Idx >= 0) options[top2Idx] else null
        val isTrueSemanticAmbiguity = top2Opt != null &&
            margin < thetaMargin &&
            p1 > probR &&
            (p1 >= thetaAbs || (p1 + p2) >= thetaAbs) &&
            p2 >= calibrationConfig.ambiguitySecondProbFloor &&
            top1Opt.hybridSimScore >= thetaSimFloor &&
            top2Opt.hybridSimScore >= thetaSimFloor

        // ── 近同分硬選防護（NEAR_TIE）──────────────────────────────────────────
        // 既有 isTrueSemanticAmbiguity 要求 p2 >= 0.25，10 路均分時 p2 只有約 0.1~0.2，
        // 於是「兩個都很有可能是對的」這種情況反而會被直接硬選一個（實測 Maps 曾出現 p1=p2=0.321 硬選）。
        // 規則：Top-1 / Top-2 語意分都 >= 0.80 且差距 < 0.03，且 Top-2 也是真的可操作控件、
        //       且兩者不是同一標籤的不同框 → 一律走澄清，不再自動猜。
        // PREFER_FIRST 例外：該策略本身語意就是「列表按視覺順序取第一個」。
        val simGap = kotlin.math.abs(top1Opt.hybridSimScore - (top2Opt?.hybridSimScore ?: 0f))
        val top2IsActionable = top2Opt != null &&
            (top2Opt.isClickable || top2Opt.isEditable || top2Opt.isSearchAffordance)
        // B3 根因修法：相似度相同 ≠ 等價。必須「在該動作的偏好上也等價」才值得打斷使用者。
        val prefEquivalent = top2Opt != null &&
            com.wae.agent.core.decision.TargetPreference.isEquivalent(goal.actionKind, top1Opt, top2Opt)
        val isNearTieAmbiguity = top2Opt != null &&
            goal.ambiguityPolicy != AmbiguityPolicy.PREFER_FIRST &&
            top1Opt.hybridSimScore >= 0.80f &&
            top2Opt.hybridSimScore >= 0.80f &&
            simGap < 0.03f &&
            prefEquivalent &&
            top2IsActionable &&
            !top1Opt.coreText.equals(top2Opt.coreText, ignoreCase = true)

        if (isNearTieAmbiguity) {
            val top3 = rankedIndices.take(3).map { options[it] }
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.AMBIGUOUS,
                selectedOption = top1Opt,
                top1Prob = p1,
                top2Prob = p2,
                margin = margin,
                top3AmbiguousCandidates = top3,
                reason = "NEAR_TIE_AMBIGUITY (Top1='${top1Opt.coreText}' ${"%.2f".format(top1Opt.hybridSimScore)}" +
                    " vs Top2='${top2Opt?.coreText.orEmpty()}' ${"%.2f".format(top2Opt?.hybridSimScore ?: 0f)}" +
                    ", gap=${"%.3f".format(simGap)})"
            )
        }

        if (isTrueSemanticAmbiguity) {
            return when (goal.ambiguityPolicy) {
                AmbiguityPolicy.PREFER_FIRST,
                AmbiguityPolicy.AUTO_RESOLVE -> {
                    val firstByPos = rankedIndices.take(2)
                        .sortedWith(
                            compareByDescending<Int> { options[it].hybridSimScore }
                                .thenByDescending { options[it].isEditable }
                                .thenByDescending { options[it].isClickable }
                                .thenBy { options[it].grid[1] }
                        )
                        .firstOrNull() ?: top1Idx
                    CuaDecisionResult(
                        frameId = optionSet.frameId,
                        windowIdx = optionSet.windowIdx,
                        outcomeType = DecisionOutcomeType.CLEAR_WIN,
                        selectedOption = options[firstByPos],
                        top1Prob = p1,
                        top2Prob = p2,
                        margin = margin,
                        reason = "AMBIGUOUS_RESOLVED_BY_${goal.ambiguityPolicy.name}"
                    )
                }
                AmbiguityPolicy.ASK_USER -> {
                    val top3 = rankedIndices.take(3).map { options[it] }
                    CuaDecisionResult(
                        frameId = optionSet.frameId,
                        windowIdx = optionSet.windowIdx,
                        outcomeType = DecisionOutcomeType.AMBIGUOUS,
                        selectedOption = top1Opt,
                        top1Prob = p1,
                        top2Prob = p2,
                        margin = margin,
                        top3AmbiguousCandidates = top3,
                        reason = "SEMANTIC_AMBIGUITY_DETECTED (Top1=${top1Opt.coreText}, Top2=${top2Opt?.coreText.orEmpty()})"
                    )
                }
            }
        }

        // ── TYPE 意圖的輸入埠守門（通用）──────────────────────────────────
        // 要「打字/搜尋」時，目標必須是可輸入或搜尋入口；影片卡片/內容項就算像也不該 TYPE。
        //  - 有輸入類候選但 Top-1 不是 → 改選輸入類（入口則先 TAP，下一輪再輸入）
        //  - 完全沒有輸入類候選 → 明確拒絕，讓外層用 BACK 退出上一層後重試
        if (goal.actionKind == ActionKind.TYPE && goal.ambiguityPolicy != AmbiguityPolicy.ASK_USER) {
            val inputCandidates = options.filter {
                (it.isEditable || it.isSearchAffordance || it.hasSearchIdHint) && !it.isBottomNavigationTab
            }
            if (inputCandidates.isEmpty()) {
                Log.i("WAE_EXEC", "TYPE gate: no input entry on screen → REJECT (pkg=${optionSet.packageName})")
                return CuaDecisionResult(
                    frameId = optionSet.frameId,
                    windowIdx = optionSet.windowIdx,
                    outcomeType = DecisionOutcomeType.REJECT,
                    selectedOption = null,
                    top1Prob = p1,
                    top2Prob = p2,
                    margin = margin,
                    reason = "NO_INPUT_ENTRY(pkg=${optionSet.packageName})"
                )
            }
            val top1IsInput = top1Opt.isEditable || top1Opt.isSearchAffordance || top1Opt.hasSearchIdHint
            if (!top1IsInput) {
                val best = inputCandidates.maxByOrNull {
                    it.hybridSimScore + (if (it.hasSearchIdHint) 0.05f else 0f)
                }!!
                Log.i(
                    "WAE_EXEC",
                    "TYPE gate: prefer input entry '${best.coreText.take(16)}' editable=${best.isEditable}"
                )
                return CuaDecisionResult(
                    frameId = optionSet.frameId,
                    windowIdx = optionSet.windowIdx,
                    outcomeType = DecisionOutcomeType.CLEAR_WIN,
                    selectedOption = best,
                    top1Prob = p1,
                    top2Prob = p2,
                    margin = margin,
                    reason = "TYPE_PREFER_INPUT_ENTRY(entry='${best.coreText.take(16)}')",
                    overrideActionKind = if (!best.isEditable) ActionKind.TAP else null
                )
            }
        }

        // ── 目標獲取階梯（B4）：要打字，但畫面上只有「入口」──
        // 例：設定頁的搜尋列，必須先點開才會出現真正的輸入框。
        // 這一步先用 TAP 開啟入口，下一輪再對真正的輸入框 TYPE。
        if (com.wae.agent.core.decision.TargetPreference.needsEntryLadder(goal.actionKind, top1Opt) &&
            goal.ambiguityPolicy != AmbiguityPolicy.ASK_USER
        ) {
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.CLEAR_WIN,
                selectedOption = top1Opt,
                top1Prob = p1,
                top2Prob = p2,
                margin = margin,
                reason = "TYPE_ENTRY_LADDER(entry='${top1Opt.coreText.take(16)}', " +
                    "pref=${"%.1f".format(com.wae.agent.core.decision.TargetPreference.score(goal.actionKind, top1Opt))})",
                overrideActionKind = ActionKind.TAP
            )
        }

        // 若畫面上有多個高分符合項目（如搜尋結果有多支同歌手影片）導致 Softmax 機率被均分，
        // 只要 Top-1 語意分數 >= 0.72 且顯著高於拒絕項 probR，在非強制詢問策略下直接判定 CLEAR_WIN
        // 但底部導航頁籤一律不得走此捷徑（否則會把 Google Maps 的「搜索」Tab 當成搜尋框），
        // 且 TYPE 目標必須落在可輸入/搜尋入口上。
        val top1EligibleForHighSimFastPath = top1Opt.hybridSimScore >= 0.72f &&
            !top1Opt.isBottomNavigationTab &&
            (goal.actionKind != ActionKind.TYPE || top1Opt.isSearchAffordance || top1Opt.isEditable)
        if (top1EligibleForHighSimFastPath && p1 > probR &&
            goal.ambiguityPolicy != AmbiguityPolicy.ASK_USER
        ) {
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.CLEAR_WIN,
                selectedOption = top1Opt,
                top1Prob = p1,
                top2Prob = p2,
                margin = margin,
                reason = "CLEAR_WIN_HIGH_SIM_MULTI_MATCH"
            )
        }

        if (probR > p1 || p1 < thetaAbs || top1Opt.hybridSimScore < thetaSimFloor) {
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.REJECT,
                selectedOption = null,
                top1Prob = p1,
                top2Prob = p2,
                margin = margin,
                reason = "LOW_CONFIDENCE_OR_SIM (P1=${"%.2f".format(p1)} < ${"%.2f".format(thetaAbs)}, Sim=${"%.2f".format(top1Opt.hybridSimScore)})"
            )
        }

        if (margin < thetaMargin) {
            return CuaDecisionResult(
                frameId = optionSet.frameId,
                windowIdx = optionSet.windowIdx,
                outcomeType = DecisionOutcomeType.REJECT,
                selectedOption = null,
                top1Prob = p1,
                top2Prob = p2,
                margin = margin,
                reason = "LOW_MARGIN_FUZZY (Margin=${"%.2f".format(margin)} < ${"%.2f".format(thetaMargin)})"
            )
        }

        return CuaDecisionResult(
            frameId = optionSet.frameId,
            windowIdx = optionSet.windowIdx,
            outcomeType = DecisionOutcomeType.CLEAR_WIN,
            selectedOption = top1Opt,
            top1Prob = p1,
            top2Prob = p2,
            margin = margin,
            reason = "CLEAR_WIN (P1=${"%.2f".format(p1)}, Margin=${"%.2f".format(margin)})"
        )
    }

    // ========================================================================
    // 5. L5 動作後 600ms 三態空間因果歸因 (Tri-State Causal Attribution)
    // ========================================================================
    fun evaluateTriStateCausalAttribution(
        prevWindowId: Int,
        currWindowId: Int,
        prevSpineSigs: LongArray,
        currSpineSigs: LongArray,
        prevTargetSubtreeHash: Long,
        currTargetSubtreeHash: Long,
        imeRoseForTarget: Boolean,
        otherNonCarouselTextChanged: Boolean
    ): Pair<CausalAttributionState, Float> {
        val replacementRate = WaeNativeBridge.computeJaccardReplacementRateSafe(prevSpineSigs, currSpineSigs)

        if (prevTargetSubtreeHash != currTargetSubtreeHash ||
            imeRoseForTarget ||
            prevWindowId != currWindowId ||
            replacementRate > 0.60f
        ) {
            return Pair(CausalAttributionState.VALID_CAUSAL, replacementRate)
        }

        if (otherNonCarouselTextChanged) {
            return Pair(CausalAttributionState.UNCERTAIN_CROSS_CONTAINER, replacementRate)
        }

        return Pair(CausalAttributionState.BACKGROUND_NOISE, replacementRate)
    }

    // ========================================================================
    // 6. 敏感資訊與安全硬護欄
    // ========================================================================
    data class SecurityCheckResult(
        val allowed: Boolean,
        val requiresHumanTakeover: Boolean,
        val requiresUserConfirmation: Boolean,
        val reason: String
    )

    fun checkSecurityGuardrails(
        packageName: String,
        hasPasswordNodeOnScreen: Boolean,
        visibleScreenTexts: List<String>,
        selectedOption: CandidateOption?,
        goal: LocalGoal
    ): SecurityCheckResult {
        if (com.wae.agent.core.safety.SystemSafetyPackages.isBlocked(packageName) || hasPasswordNodeOnScreen) {
            return SecurityCheckResult(
                allowed = false,
                requiresHumanTakeover = true,
                requiresUserConfirmation = false,
                reason = "安全護欄攔截：偵測到系統權限或密碼頁面 ($packageName)，強制轉交人工接管"
            )
        }
        return SecurityCheckResult(
            allowed = true,
            requiresHumanTakeover = false,
            requiresUserConfirmation = false,
            reason = "SAFE"
        )
    }

    // ========================================================================
    // 7. Level-1 面積序 Top-5 無文字大框篩選 ([1%, 50%] 螢幕面積，由大到小排序)
    // ========================================================================
    fun selectTop5UnlabeledBoxesByAreaForOcr(
        screenW: Int,
        screenH: Int,
        unlabeledRects: List<Rect>
    ): List<Rect> {
        val screenArea = (screenW * screenH).coerceAtLeast(1)
        val minArea = (screenArea * 0.01f).toInt()
        val maxArea = (screenArea * 0.50f).toInt()

        return unlabeledRects
            .filter { r ->
                val a = r.width() * r.height()
                a in minArea..maxArea
            }
            .sortedByDescending { r -> r.width() * r.height() }
            .take(5)
    }

    // ========================================================================
    // 8. RunMode.TEST 校準快照落盤器 ("遇到不知道就停")
    // ========================================================================
    fun dumpCalibrationSnapshot(
        context: Context?,
        goal: LocalGoal,
        optionSet: ImmutableOptionSet,
        decision: CuaDecisionResult
    ) {
        if (context == null) return
        try {
            val obj = JSONObject().apply {
                put("timestampMs", System.currentTimeMillis())
                put("runMode", currentRunMode.name)
                put("frameId", optionSet.frameId)
                put("windowIdx", optionSet.windowIdx)
                put("packageName", optionSet.packageName)
                put("posture", optionSet.postureState.effectivePosture.name)
                put("dynamicScore", optionSet.postureState.dynamicScore.toDouble())
                put("goalText", goal.goalText)
                put("inputPayload", goal.redactedPayload())
                put("outcome", decision.outcomeType.name)
                put("top1Prob", decision.top1Prob.toDouble())
                put("top2Prob", decision.top2Prob.toDouble())
                put("margin", decision.margin.toDouble())
                put("reason", decision.reason)
                val optsArr = JSONArray()
                for (opt in optionSet.selectableOptions) {
                    optsArr.put(
                        JSONObject().apply {
                            put("markId", opt.markId)
                            put("source", opt.source.name)
                            put("confidence", opt.detectorConfidence.toDouble())
                            put("coreText", opt.coreText)
                            put("welded", opt.weldedDescription)
                            put("hybridSim", opt.hybridSimScore.toDouble())
                        }
                    )
                }
                put("options", optsArr)
            }
            val file = File(context.filesDir, "calibration_dumps.jsonl")
            file.appendText(obj.toString() + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }
}
