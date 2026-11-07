package com.example.newandroidapp.ai

import android.content.Context
import com.example.newandroidapp.stability.BackwardChainingPlanner
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.TerminalVerb

/**
 * 100% 原創自研：階層式長指令思考與「大塊 App 切分 + 塊內極簡碎化」引擎 (HierarchicalCommandChunker)
 *
 * 設計理念（思考層與動手層徹底分家）：
 * 1. 【第一層：App 大塊切分 (`AppMacroBlock`)】：
 *    - 收到任何自然語言指令時，先依序判斷總共需要動用到幾個 App：
 *      - 用到 1 個 App -> 切分為 1 個大塊 (`totalAppBlocks = 1`)
 *      - 用到 2 個 App -> 切分為 2 個大塊 (`totalAppBlocks = 2`)
 *      - 用到 N 個 App -> 切分為 N 個大塊 (`totalAppBlocks = N`)
 *    - 若後續子句未切換新 App（例如「去 YouTube 查周杰倫，然後點第一個影片，再按讚」或「去設定把 Wi-Fi 打開，然後把藍牙關掉」），
 *      所有子句嚴格收攏於同一個 `AppMacroBlock` 大塊內，絕不誤拆。
 *
 * 2. 【第二層：大塊內極簡碎化 (`AtomicMotorTask`)】：
 *    - 在每個 `AppMacroBlock` 大塊內部，順著指令時間順序將複合長句打碎成最純粹、單一動作的原子任務 (`AtomicMotorTask`)。
 *    - 產出完全不含「然後 / 接著 / 最後」等連接詞、也不含其他 App 名稱雜訊的極簡短句 (`simplifiedMotorGoal`)，
 *      例如：「打開 YouTube」->「搜尋 周杰倫」->「搜尋 周杰倫 並 點擊第一個影片」->「點擊 讚」。
 *
 * 3. 【第三層：動手模型單步隔離餵食】：
 *    - 轉換為 `CooperativeSubGoal` 序列後，底層執行層每次只接收當前那一小步的 `simplifiedMotorGoal`，
 *      徹底消除長句關鍵字跨步汙染與「遇到『然後』就放棄搜尋擷取」的結構性問題。
 */
object HierarchicalCommandChunker {

    enum class AtomicActionKind {
        LAUNCH_APP,
        INPUT_SEARCH,
        SELECT_RESULT,
        TAP_TARGET,
        TOGGLE_SWITCH,
        EXTRACT_SCREEN_INFO,
        PASTE_CLIPBOARD,
        SCROLL,
        GESTURE_SWIPE,
        LONG_PRESS,
        DOUBLE_TAP,
        SYSTEM_BACK
    }

    data class AtomicMotorTask(
        val taskIndexInBlock: Int,
        val totalTasksInBlock: Int,
        val globalStepIndex: Int,
        val globalTotalSteps: Int,
        val parentBlockIndex: Int,
        val totalAppBlocks: Int,
        val actionKind: AtomicActionKind,
        val simplifiedMotorGoal: String,
        val targetSemanticPhrase: String,
        val targetAppHint: String? = null,
        val inputPayload: String? = null,
        val desiredToggleState: Boolean? = null,
        val isHighRiskHint: Boolean = false
    ) {
        fun toCooperativeSubGoal(): CooperativeSubGoal {
            val category = when (actionKind) {
                AtomicActionKind.LAUNCH_APP -> "LAUNCH_APP"
                AtomicActionKind.INPUT_SEARCH, AtomicActionKind.PASTE_CLIPBOARD -> "INPUT_SEARCH"
                AtomicActionKind.SELECT_RESULT, AtomicActionKind.TAP_TARGET -> "TAP_TARGET"
                AtomicActionKind.TOGGLE_SWITCH -> "TOGGLE_SWITCH"
                AtomicActionKind.EXTRACT_SCREEN_INFO -> "EXTRACT_SCREEN_INFO"
                AtomicActionKind.SCROLL -> "SCROLL"
                AtomicActionKind.GESTURE_SWIPE -> "GESTURE_SWIPE"
                AtomicActionKind.LONG_PRESS -> "LONG_PRESS"
                AtomicActionKind.DOUBLE_TAP -> "DOUBLE_TAP"
                AtomicActionKind.SYSTEM_BACK -> "SYSTEM_BACK"
            }
            val primitiveType = when (actionKind) {
                AtomicActionKind.LAUNCH_APP -> CanonicalActionPrimitiveType.LAUNCH_APP
                AtomicActionKind.PASTE_CLIPBOARD -> CanonicalActionPrimitiveType.INPUT_TEXT
                AtomicActionKind.INPUT_SEARCH -> {
                    if (isNoteApp(targetAppHint) ||
                        targetSemanticPhrase.startsWith("輸入 ") ||
                        simplifiedMotorGoal.startsWith("輸入")
                    ) {
                        CanonicalActionPrimitiveType.INPUT_TEXT
                    } else {
                        CanonicalActionPrimitiveType.SEARCH_IN_APP
                    }
                }
                AtomicActionKind.EXTRACT_SCREEN_INFO -> CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY
                AtomicActionKind.SELECT_RESULT,
                AtomicActionKind.TAP_TARGET,
                AtomicActionKind.TOGGLE_SWITCH,
                AtomicActionKind.LONG_PRESS,
                AtomicActionKind.DOUBLE_TAP -> CanonicalActionPrimitiveType.TAP_ELEMENT
                AtomicActionKind.SYSTEM_BACK,
                AtomicActionKind.SCROLL,
                AtomicActionKind.GESTURE_SWIPE -> CanonicalActionPrimitiveType.SYSTEM_NAV
            }
            val primitiveArg = when (primitiveType) {
                CanonicalActionPrimitiveType.LAUNCH_APP -> (targetAppHint ?: targetSemanticPhrase).trim()
                CanonicalActionPrimitiveType.SEARCH_IN_APP,
                CanonicalActionPrimitiveType.INPUT_TEXT -> {
                    if (CrossAppWorkflowEngine.isMemoryPlaceholder(inputPayload)) {
                        CrossAppWorkflowEngine.MEMORY_REF_TOKEN
                    } else {
                        (inputPayload ?: targetSemanticPhrase).trim()
                    }
                }
                CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY -> targetSemanticPhrase.trim()
                CanonicalActionPrimitiveType.TAP_ELEMENT -> targetSemanticPhrase.trim()
                CanonicalActionPrimitiveType.SYSTEM_NAV -> when (actionKind) {
                    AtomicActionKind.SYSTEM_BACK -> "BACK"
                    AtomicActionKind.SCROLL -> if (simplifiedMotorGoal.contains("上")) "SCROLL_UP" else "SCROLL_DOWN"
                    AtomicActionKind.GESTURE_SWIPE -> if (simplifiedMotorGoal.contains("左")) "SWIPE_LEFT" else "SWIPE_RIGHT"
                    else -> "BACK"
                }
            }
            val compiledPrimitive = CompiledActionPrimitive(
                stepIndex = globalStepIndex,
                totalSteps = globalTotalSteps,
                type = primitiveType,
                argument = primitiveArg,
                appPhaseIndex = parentBlockIndex,
                totalAppBlocks = totalAppBlocks,
                targetAppHint = targetAppHint,
                desiredToggleState = desiredToggleState,
                isHighRiskHint = isHighRiskHint,
                sourceClause = simplifiedMotorGoal
            )
            return CooperativeSubGoal(
                stepIndex = globalStepIndex,
                totalSteps = globalTotalSteps,
                intentCategory = category,
                targetAppHint = targetAppHint,
                targetSemanticPhrase = targetSemanticPhrase,
                inputPayload = inputPayload,
                desiredToggleState = desiredToggleState,
                isHighRiskHint = isHighRiskHint,
                appPhaseIndex = parentBlockIndex,
                totalAppBlocks = totalAppBlocks,
                simplifiedMotorGoal = simplifiedMotorGoal,
                canonicalPrimitive = compiledPrimitive
            )
        }
    }

    data class AppMacroBlock(
        val blockIndex: Int,
        val totalBlocks: Int,
        val targetAppHint: String?,
        val rawClauses: List<String>,
        val requiresCurrentScreenExtractFirst: Boolean = false,
        val exportsDataToNextBlock: Boolean = false,
        val consumesDataFromPrevBlock: Boolean = false,
        val recipientOrNoteTarget: String? = null,
        val atomicTasks: List<AtomicMotorTask> = emptyList()
    ) {
        val rawBlockSummary: String
            get() = rawClauses.joinToString(" -> ")
    }

    data class HierarchicalExecutionPlan(
        val rawInstruction: String,
        val totalAppBlocks: Int,
        val macroBlocks: List<AppMacroBlock>,
        val flattenedSubGoals: List<CooperativeSubGoal>
    ) {
        fun formatThinkingSummary(): String {
            if (macroBlocks.isEmpty()) return "無有效指令區塊"
            return buildString {
                append("[階層思考拆解: 共 $totalAppBlocks 個 App 大塊 / ${flattenedSubGoals.size} 個極簡步] ")
                macroBlocks.forEachIndexed { idx, block ->
                    if (idx > 0) append(" ==> ")
                    val appLabel = block.targetAppHint ?: "當前畫面"
                    val stepsStr = block.atomicTasks.joinToString(" -> ") { it.simplifiedMotorGoal }
                    append("大塊#${block.blockIndex}($appLabel): [$stepsStr]")
                }
            }
        }
    }

    // 顯式 App 名稱對應表（特定 App 名稱排在通用「設定 / 訊息 / 地圖」之前，避免「去時鐘設定鬧鐘」誤判為「設定」或「高德地圖」誤判為「地圖」）
    private val ORDERED_EXPLICIT_APP_ALIASES: List<Pair<String, String>> = listOf(
        "youtube" to "YouTube",
        "yt" to "YouTube",
        "bilibili" to "Bilibili 嗶哩嗶哩",
        "嗶哩嗶哩" to "Bilibili 嗶哩嗶哩",
        "哔哩哔哩" to "Bilibili 嗶哩嗶哩",
        "b站" to "Bilibili 嗶哩嗶哩",
        "小紅書" to "小紅書",
        "小红书" to "小紅書",
        "spotify" to "Spotify",
        "netflix" to "Netflix",
        "高德地圖" to "高德地圖",
        "高德地图" to "高德地圖",
        "百度地圖" to "百度地圖",
        "百度地图" to "百度地圖",
        "google地圖" to "地圖",
        "google 地圖" to "地圖",
        "google地图" to "地圖",
        "google 地图" to "地圖",
        "谷歌地圖" to "地圖",
        "谷歌地图" to "地圖",
        "google maps" to "地圖",
        "地圖" to "地圖",
        "地图" to "地圖",
        "google瀏覽器" to "Chrome",
        "google 瀏覽器" to "Chrome",
        "谷歌瀏覽器" to "Chrome",
        "chrome瀏覽器" to "Chrome",
        "chrome" to "Chrome",
        "瀏覽器" to "Chrome",
        "samsung notes" to "Samsung Notes",
        "三星筆記" to "Samsung Notes",
        "keep" to "Keep 筆記",
        "備忘錄" to "備忘錄",
        "筆記本" to "備忘錄",
        "記事本" to "備忘錄",
        "筆記" to "備忘錄",
        "wechat" to "WeChat 微信",
        "微信" to "WeChat 微信",
        "line" to "LINE",
        "賴" to "LINE",
        "gmail" to "Gmail",
        "信箱" to "Gmail",
        "台灣高鐵" to "台灣高鐵 T-EX",
        "高鐵app" to "台灣高鐵 T-EX",
        "高鐵" to "台灣高鐵 T-EX",
        "台鐵e訂通" to "台鐵e訂通",
        "台鐵" to "台鐵e訂通",
        "時鐘" to "時鐘",
        "鬧鐘" to "時鐘",
        "行事曆" to "日曆",
        "日曆" to "日曆",
        "計算機" to "計算機",
        "算盤" to "計算機",
        "相簿" to "媒體瀏覽器",
        "媒體瀏覽器" to "媒體瀏覽器",
        "蝦皮購物" to "蝦皮",
        "蝦皮" to "蝦皮",
        "shopee" to "蝦皮",
        "momo" to "momo",
        "uber eats" to "Uber Eats",
        "ubereats" to "Uber Eats",
        "foodpanda" to "foodpanda",
        "熊貓外送" to "foodpanda",
        "系統設定" to "設定",
        "settings" to "設定",
        "簡訊" to "訊息",
        "google" to "Chrome"
    )

    private val NOTE_AND_SAVE_VERBS = listOf(
        "記下來", "記到", "寫進", "寫到", "存到", "存入", "記錄到", "貼到", "貼上", "新增筆記", "寫備忘錄", "存起來"
    )

    private val SHARE_AND_SEND_VERBS = listOf(
        "傳給", "傳到", "傳送給", "發給", "發送到", "分享給", "分享到", "貼給",
        "丟給", "丟到", "寄給", "送給", "轉傳給", "轉發給", "賴給", "line給",
        "傳訊息給", "傳line給"
    )

    private val CURRENT_SCREEN_REF_KEYWORDS = listOf(
        "現在畫面", "當前畫面", "這個畫面", "這頁", "這首歌", "這篇文章", "這個結果", "畫面上的重點", "畫面重點"
    )

    private val SETTINGS_FEATURE_KEYWORDS = listOf(
        "藍牙", "wi-fi", "wifi", "熱點", "深色模式", "暗色主題", "深色主題", "夜覽", "護眼",
        "勿擾", "飛航模式", "飛行模式", "省電模式", "自動旋轉", "定位", "亮度", "音量"
    )

    /**
     * 核心入口：將任意自然語言指令先切分為 1..N 個 `AppMacroBlock` 大塊，再於各大塊內打碎為極簡 `AtomicMotorTask` 序列
     */
    fun chunkInstruction(
        rawGoal: String,
        context: Context? = null,
        highRiskKeywords: List<String> = emptyList(),
        lockToCurrentApp: Boolean = false
    ): HierarchicalExecutionPlan {
        val rawTrimmed = rawGoal.trim()
        if (rawTrimmed.isEmpty()) {
            return HierarchicalExecutionPlan(
                rawInstruction = "",
                totalAppBlocks = 0,
                macroBlocks = emptyList(),
                flattenedSubGoals = emptyList()
            )
        }
        val clean = OpenSourceAiDatabaseManager.correctAsrHomophonesInCommand(rawTrimmed, context)

        // 第一步：切分 App 大塊 (1 App = 1 大塊, 2 Apps = 2 大塊, N Apps = N 大塊)
        val rawBlocks = segmentIntoAppMacroBlocks(clean, context, lockToCurrentApp)
        val totalBlocks = rawBlocks.size.coerceAtLeast(1)

        // 第二步：對每個 App 大塊內部進行原子級碎化與極簡化
        val populatedBlocks = ArrayList<AppMacroBlock>(totalBlocks)
        val allAtomicTasks = ArrayList<AtomicMotorTask>()

        for (block in rawBlocks) {
            val blockTasks = shatterBlockIntoAtomicTasks(
                block = block,
                totalBlocks = totalBlocks,
                highRiskKeywords = highRiskKeywords,
                lockToCurrentApp = lockToCurrentApp,
                context = context
            )
            populatedBlocks.add(block.copy(totalBlocks = totalBlocks, atomicTasks = blockTasks))
            allAtomicTasks.addAll(blockTasks)
        }

        // 第三步：重新編號全域步驟與塊內步驟
        val globalTotal = allAtomicTasks.size.coerceAtLeast(1)
        var globalCursor = 1
        val finalizedBlocks = populatedBlocks.map { block ->
            val blockTotal = block.atomicTasks.size.coerceAtLeast(1)
            val reindexedTasks = block.atomicTasks.mapIndexed { idx, task ->
                val updated = task.copy(
                    taskIndexInBlock = idx + 1,
                    totalTasksInBlock = blockTotal,
                    globalStepIndex = globalCursor,
                    globalTotalSteps = globalTotal,
                    parentBlockIndex = block.blockIndex,
                    totalAppBlocks = totalBlocks
                )
                globalCursor++
                updated
            }
            block.copy(atomicTasks = reindexedTasks)
        }

        val flattenedSubGoals = finalizedBlocks.flatMap { b ->
            b.atomicTasks.map { it.toCooperativeSubGoal() }
        }

        return HierarchicalExecutionPlan(
            rawInstruction = clean,
            totalAppBlocks = finalizedBlocks.size,
            macroBlocks = finalizedBlocks,
            flattenedSubGoals = flattenedSubGoals
        )
    }

    /**
     * 第一層思考：判斷整句指令需要用到幾個 App，依序切分為 1..N 個 `AppMacroBlock`
     */
    fun segmentIntoAppMacroBlocks(
        cleanGoal: String,
        context: Context? = null,
        lockToCurrentApp: Boolean = false
    ): List<AppMacroBlock> {
        if (lockToCurrentApp) {
            val clauses = tokenizeChronologicalClauses(cleanGoal)
            val hasExtractIntent = clauses.any { extractTargetFromExtractorClause(it) != null }
            return listOf(
                AppMacroBlock(
                    blockIndex = 1,
                    totalBlocks = 1,
                    targetAppHint = null,
                    rawClauses = clauses,
                    exportsDataToNextBlock = hasExtractIntent
                )
            )
        }

        // 特殊情境：將「現在畫面上的重點 / 這首歌」直接存到備忘錄或傳到 LINE（未先指定來源查詢 App）
        val mentionsCurrentScreen = CURRENT_SCREEN_REF_KEYWORDS.any { cleanGoal.contains(it) }
        if (mentionsCurrentScreen) {
            val targetApp = detectExplicitAppInClause(cleanGoal, context) ?: inferCrossAppTransitionTarget(cleanGoal, null)
            if (targetApp != null) {
                val isSearchInSecondApp = cleanGoal.contains("搜尋") || cleanGoal.contains("搜索") || cleanGoal.contains("查找")
                val recipientOrNote = extractRecipientOrNoteEntry(cleanGoal, targetApp)
                return listOf(
                    AppMacroBlock(
                        blockIndex = 1,
                        totalBlocks = 2,
                        targetAppHint = null,
                        rawClauses = listOf("擷取當前畫面重點資訊"),
                        requiresCurrentScreenExtractFirst = true,
                        exportsDataToNextBlock = true
                    ),
                    AppMacroBlock(
                        blockIndex = 2,
                        totalBlocks = 2,
                        targetAppHint = targetApp,
                        rawClauses = listOf(cleanGoal),
                        consumesDataFromPrevBlock = true,
                        recipientOrNoteTarget = if (isSearchInSecondApp) null else recipientOrNote
                    )
                )
            }
        }

        val clauses = tokenizeChronologicalClauses(cleanGoal)
        if (clauses.isEmpty()) {
            return listOf(
                AppMacroBlock(
                    blockIndex = 1,
                    totalBlocks = 1,
                    targetAppHint = null,
                    rawClauses = listOf(cleanGoal)
                )
            )
        }

        // 掃描所有子句，依「是否切換新 App」聚合成 AppMacroBlock
        data class MutableBlockDraft(
            var targetAppHint: String?,
            val clauses: MutableList<String> = ArrayList(),
            var consumesClipboard: Boolean = false,
            var explicitExportsData: Boolean = false,
            var recipientOrNoteTarget: String? = null
        )

        val drafts = ArrayList<MutableBlockDraft>()

        for ((idx, clause) in clauses.withIndex()) {
            val explicitApp = detectExplicitAppInClause(clause, context)
            val isExplicitExtractor = extractTargetFromExtractorClause(clause) != null

            if (idx == 0) {
                // 第一個子句：若有顯式 App 則直接採用；否則僅在有後續跨 App 子句或明確系統動作時推論初始 App
                val initialApp = explicitApp ?: inferInitialClauseApp(clause, clauses.size > 1, context)
                drafts.add(
                    MutableBlockDraft(
                        targetAppHint = initialApp,
                        clauses = arrayListOf(clause),
                        explicitExportsData = isExplicitExtractor
                    )
                )
                continue
            }

            val currentDraft = drafts.last()
            val candidateNextApp = explicitApp ?: inferCrossAppTransitionTarget(clause, currentDraft.targetAppHint)

            val isSwitchingToNewApp = candidateNextApp != null &&
                !candidateNextApp.equals(currentDraft.targetAppHint, ignoreCase = true)

            if (isSwitchingToNewApp) {
                val isTransferConsumer = currentDraft.explicitExportsData ||
                    isClipboardConsumerClause(clause, candidateNextApp)
                drafts.add(
                    MutableBlockDraft(
                        targetAppHint = candidateNextApp,
                        clauses = arrayListOf(clause),
                        consumesClipboard = isTransferConsumer,
                        recipientOrNoteTarget = if (isTransferConsumer) extractRecipientOrNoteEntry(clause, candidateNextApp) else null
                    )
                )
            } else {
                // 未切換新 App：留在當前同一個 App 大塊內！
                if (currentDraft.targetAppHint == null && candidateNextApp != null) {
                    currentDraft.targetAppHint = candidateNextApp
                }
                if (isExplicitExtractor) {
                    currentDraft.explicitExportsData = true
                }
                currentDraft.clauses.add(clause)
            }
        }

        // 若總共有 >= 2 個大塊，且第 1 個大塊尚未標註 App（例如「先查今日美金匯率，然後去備忘錄記下來」），自動補齊第 1 大塊的預設 App
        if (drafts.size >= 2 && drafts[0].targetAppHint == null) {
            val firstClauseText = drafts[0].clauses.joinToString(" ")
            drafts[0].targetAppHint = inferFallbackAppForFirstBlockWhenMultiApp(firstClauseText)
        }

        val total = drafts.size
        return drafts.mapIndexed { i, draft ->
            val nextConsumesClipboard = draft.explicitExportsData || ((i + 1 < total) && drafts[i + 1].consumesClipboard)
            val thisConsumesClipboard = draft.consumesClipboard || (i > 0 && drafts[i - 1].explicitExportsData)
            AppMacroBlock(
                blockIndex = i + 1,
                totalBlocks = total,
                targetAppHint = draft.targetAppHint,
                rawClauses = draft.clauses.toList(),
                requiresCurrentScreenExtractFirst = false,
                exportsDataToNextBlock = nextConsumesClipboard,
                consumesDataFromPrevBlock = thisConsumesClipboard,
                recipientOrNoteTarget = draft.recipientOrNoteTarget
            )
        }
    }

    /**
     * 將原始長句依序切分為細粒度時間子句（保留動詞，不吞掉「打開 / 去 / 開啟」，同時支援無標點口語長句與繁簡連詞）
     */
    fun tokenizeChronologicalClauses(rawSentence: String): List<String> {
        val clean = rawSentence.trim()
        if (clean.isEmpty()) return emptyList()

        // 1. 先按明確的時間轉折連接詞與標點切分（支援繁簡體，含「然後、接著、再、之後、並、最後、以及」）
        val connectorRegex = Regex(
            "(?:，|,|；|;|->|\\s+)*(?:然後再|然后再|然後|然后|接著再|接着再|接著|接着|再來|再来|之後再|之后再|之後|之后|最後再|最后再|最後|最后|以及再|以及|查完後|查完后|複製結果後|复制结果后|複製後|复制后|並且|并且|\\band\\s+then\\b|\\bthen\\b)(?:，|,|\\s)*|(?:，|,|；|;|->)+",
            RegexOption.IGNORE_CASE
        )
        val stage1 = clean.split(connectorRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 2. 針對每個片段檢查是否含無標點抽取子句（「把X抓出來」）、無標點顯式跨 App 轉移（「直接打開google地圖...」）或「並/并/再 + 明確動作動詞」
        val stage2 = ArrayList<String>()
        val inlineVerbSplitRegex = Regex(
            "\\s*(?:並|并|再|並且|并且|順便|顺便|變)(?=(?:打開|開啟|打开|开启|關閉|關掉|关闭|关掉|點擊|點選|點開|點進|點入|點第|點最|點一下|點極|點及|點行車|點路線|進入|進網站|進官網|進網頁|按一下|按讚|按下|搜尋|搜索|查找|查詢|輸入|播放|傳給|傳到|傳送給|發給|丟給|丟到|寄給|送給|轉傳給|轉發給|記下來|記到|寫進|存到|分享|設鬧鐘|設定鬧鐘|規劃路線|规划路线|路線規劃|路线规划|行車路線|駕車路線|開車路線|步行路線|大眾運輸|查看路線|導航|导航|開始導航|开始导航))"
        )

        for (seg in stage1) {
            val implicitTransferred = splitImplicitTwoStageDataTransfer(seg)
            for (part in implicitTransferred) {
                val subParts = part.split(inlineVerbSplitRegex)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                stage2.addAll(subParts)
            }
        }

        return stage2.ifEmpty { listOf(clean) }
    }

    private fun splitImplicitTwoStageDataTransfer(sentence: String): List<String> {
        val clean = sentence.trim()
        if (clean.isEmpty()) return emptyList()

        // A. 先切分無標點「把/將 <實體> 抓出來/找出來/提取出來/複製出來」抽取子句與其前後子句
        val extractorClauseRegex = Regex(
            "^(.+?)(?:，|,|\\s)*((?:把|將|将)[^，,。；;]{1,12}?(?:抓出來|抓出来|找出來|找出来|提取出來|提取出来|擷取出來|複製出來|复制出来|抄下來|抄下来))(?:，|,|\\s)*(.*)$"
        )
        val extMatch = extractorClauseRegex.find(clean)
        if (extMatch != null && !clean.startsWith("把") && !clean.startsWith("將") && !clean.startsWith("将")) {
            val beforeExt = extMatch.groupValues[1].trim()
            val extClause = extMatch.groupValues[2].trim()
            val afterExt = extMatch.groupValues[3].trim()
            val out = ArrayList<String>(3)
            if (beforeExt.isNotEmpty()) out.addAll(splitImplicitTwoStageDataTransfer(beforeExt))
            if (extClause.isNotEmpty()) out.add(extClause)
            if (afterExt.isNotEmpty()) out.addAll(splitImplicitTwoStageDataTransfer(afterExt))
            if (out.size >= 2) return out
        }

        // B. 若開頭本身就是「把<實體>抓出來直接打開<App>...」
        val leadingExtractorThenAppRegex = Regex(
            "^((?:把|將|将)[^，,。；;]{1,12}?(?:抓出來|抓出来|找出來|找出来|提取出來|提取出来|擷取出來|複製出來|复制出来|記下來|记下来|抄下來))(?:，|,|\\s)*((?:直接|馬上|立刻|順便|顺便|再)?(?:打開|開啟|打开|开启|切換到|切换到|去|到|進入|进入).+)$"
        )
        val leadExtMatch = leadingExtractorThenAppRegex.find(clean)
        if (leadExtMatch != null) {
            val extPart = leadExtMatch.groupValues[1].trim()
            val nextPart = leadExtMatch.groupValues[2].trim()
            val out = ArrayList<String>(2)
            out.add(extPart)
            out.addAll(splitImplicitTwoStageDataTransfer(nextPart))
            return out
        }

        // C. 切分無標點顯式第二階段 App 切換（如「...直接打開google地圖搜尋...」）
        val unpunctuatedAppSwitchRegex = Regex(
            "^(.{4,}?)(?:\\s*)((?:直接|馬上|立刻|順便|顺便)(?:打開|開啟|打开|开启|切換到|切换到|去|到|進入|进入)\\s*(?:google\\s*地圖|google\\s*地图|google\\s*maps|谷歌地圖|地圖|地图|chrome|google\\s*瀏覽器|瀏覽器|youtube|yt|spotify|line|賴|備忘錄|筆記|keep|gmail|信箱|時鐘|鬧鐘|日曆|行事曆|蝦皮|momo|計算機).*)$",
            RegexOption.IGNORE_CASE
        )
        val appSwitchMatch = unpunctuatedAppSwitchRegex.find(clean)
        if (appSwitchMatch != null) {
            val part1 = appSwitchMatch.groupValues[1].trim()
            val part2 = appSwitchMatch.groupValues[2].trim()
            if (part1.length >= 3 && part2.length >= 3) {
                return listOf(part1, part2)
            }
        }

        if (clean.startsWith("把")) return listOf(clean)
        val m = Regex(
            "^(.+?)(?:，|,|\\s+)?((?:把(?:查詢結果|搜尋結果|結果|重點|文字|內容|資訊|資料|網址|連結|股價|匯率|天氣|時間|開會時間|地點|地址|地鐵|單號|驗證碼|它)\\s*)(?:(?:記下來|记下来|存起來|存起来|抄下來|抄下来).*|(?:記到|寫進|寫到|存到|存入|記錄到|貼到|貼給|傳到|傳給|傳送給|發給|發送到|分享到|分享給|丟給|丟到|寄給|送給|轉傳給|轉發給|賴給|line給|記在).+)|(?:記下來|记下来|存起來|存起来|抄下來|抄下来).*|(?:記到|寫進|寫到|存到|存入|記錄到|貼到|貼給|傳到|傳給|傳送給|發給|發送到|分享到|分享給|丟給|丟到|寄給|送給|轉傳給|轉發給|賴給|line給|記在).+)$",
            RegexOption.IGNORE_CASE
        ).find(clean)
        if (m != null) {
            val part1 = m.groupValues[1].trim()
            val part2 = m.groupValues[2].trim()
            val part1App = detectExplicitAppInClause(part1)
            if (part1.length >= 3 && part2.length >= 2 &&
                !part1.startsWith("把") &&
                !isNoteApp(part1App) &&
                !Regex("把(?:查詢結果|搜尋結果|結果|重點|文字|內容|資訊|資料|網址|連結|股價|匯率|天氣|它)$").containsMatchIn(part1)
            ) {
                return listOf(part1, part2)
            }
        }
        return listOf(clean)
    }

    /**
     * 第二層思考：將單一 `AppMacroBlock` 大塊內部的子句打碎為一連串極簡單步 `AtomicMotorTask`
     */
    @Suppress("UNUSED_PARAMETER")
    private fun shatterBlockIntoAtomicTasks(
        block: AppMacroBlock,
        totalBlocks: Int,
        highRiskKeywords: List<String>,
        lockToCurrentApp: Boolean,
        context: Context?
    ): List<AtomicMotorTask> {
        val tasks = ArrayList<AtomicMotorTask>()
        val appHint = block.targetAppHint

        // 情況 A：直接從當前畫面擷取重點（如「把現在畫面上的重點存到備忘錄」的大塊 #1）
        if (block.requiresCurrentScreenExtractFirst && appHint == null) {
            tasks.add(
                AtomicMotorTask(
                    taskIndexInBlock = 1,
                    totalTasksInBlock = 1,
                    globalStepIndex = 1,
                    globalTotalSteps = 1,
                    parentBlockIndex = block.blockIndex,
                    totalAppBlocks = totalBlocks,
                    actionKind = AtomicActionKind.EXTRACT_SCREEN_INFO,
                    simplifiedMotorGoal = "擷取當前畫面重點資訊",
                    targetSemanticPhrase = "擷取當前畫面重點資訊",
                    targetAppHint = null
                )
            )
            return tasks
        }

        // 1. 若該大塊有指定目標 App（且非鎖定當前 App），第一步永遠是極簡「打開 <App>」
        if (!lockToCurrentApp && !appHint.isNullOrBlank()) {
            tasks.add(
                AtomicMotorTask(
                    taskIndexInBlock = tasks.size + 1,
                    totalTasksInBlock = 0,
                    globalStepIndex = 0,
                    globalTotalSteps = 0,
                    parentBlockIndex = block.blockIndex,
                    totalAppBlocks = totalBlocks,
                    actionKind = AtomicActionKind.LAUNCH_APP,
                    simplifiedMotorGoal = "打開 $appHint",
                    targetSemanticPhrase = if (totalBlocks >= 2) "啟動目標 App：$appHint" else "啟動目標 App：$appHint",
                    targetAppHint = appHint,
                    isHighRiskHint = false
                )
            )
        }

        // 2. 整理大塊內的子句（將第 1 個子句開頭的「先去 YouTube / 直接打開google地圖」前綴剝離，留下純 App 內動作）
        val cleanedInAppClauses = ArrayList<String>()
        for ((idx, rawClause) in block.rawClauses.withIndex()) {
            val stripped = if (idx == 0 && !appHint.isNullOrBlank()) {
                stripLeadingAppOpenPrefix(rawClause, appHint)
            } else {
                stripLeadingConnectorNoise(rawClause, appHint)
            }
            if (stripped.isNotBlank() && !stripped.equals(appHint, ignoreCase = true)) {
                cleanedInAppClauses.add(stripped)
            }
        }

        var clauseIdx = 0
        var lastSearchQueryInBlock = ""
        var explicitExtractEntityHint = ""

        // 3. 若此大塊是跨 App 剪貼簿消費者（如「打開備忘錄記下來」、「去 LINE 傳給媽媽」或「直接打開 google 地圖搜尋并規劃路線」）
        if (block.consumesDataFromPrevBlock) {
            val isRisk = highRiskKeywords.any { kw -> block.rawClauses.any { it.lowercase().contains(kw) } }
            val appLower = appHint?.lowercase().orEmpty()
            val isNoteOrMessaging = isNoteApp(appHint) ||
                appHint.equals("LINE", ignoreCase = true) ||
                appHint.equals("訊息", ignoreCase = true) ||
                appLower.contains("wechat") ||
                appLower.contains("微信") ||
                appLower.contains("telegram") ||
                appLower.contains("whatsapp") ||
                appLower.contains("messenger")

            if (isNoteOrMessaging) {
                val entryTarget = block.recipientOrNoteTarget
                if (!entryTarget.isNullOrBlank()) {
                    tasks.add(
                        AtomicMotorTask(
                            taskIndexInBlock = tasks.size + 1,
                            totalTasksInBlock = 0,
                            globalStepIndex = 0,
                            globalTotalSteps = 0,
                            parentBlockIndex = block.blockIndex,
                            totalAppBlocks = totalBlocks,
                            actionKind = AtomicActionKind.TAP_TARGET,
                            simplifiedMotorGoal = "點擊 $entryTarget",
                            targetSemanticPhrase = entryTarget,
                            targetAppHint = appHint,
                            isHighRiskHint = isRisk
                        )
                    )
                }
                tasks.add(
                    AtomicMotorTask(
                        taskIndexInBlock = tasks.size + 1,
                        totalTasksInBlock = 0,
                        globalStepIndex = 0,
                        globalTotalSteps = 0,
                        parentBlockIndex = block.blockIndex,
                        totalAppBlocks = totalBlocks,
                        actionKind = AtomicActionKind.PASTE_CLIPBOARD,
                        simplifiedMotorGoal = "輸入跨App擷取內容",
                        targetSemanticPhrase = "輸入跨App擷取內容",
                        targetAppHint = appHint,
                        inputPayload = CrossAppWorkflowEngine.CLIPBOARD_PLACEHOLDER,
                        isHighRiskHint = isRisk
                    )
                )
                return tasks
            } else {
                // 在地圖 / 瀏覽器 / 影音等第二階段 App 將上一階段抓出的實體填入搜尋框送出，並繼續執行同大塊後續動作（如「規劃路線」）
                tasks.add(
                    AtomicMotorTask(
                        taskIndexInBlock = tasks.size + 1,
                        totalTasksInBlock = 0,
                        globalStepIndex = 0,
                        globalTotalSteps = 0,
                        parentBlockIndex = block.blockIndex,
                        totalAppBlocks = totalBlocks,
                        actionKind = AtomicActionKind.INPUT_SEARCH,
                        simplifiedMotorGoal = "搜尋 跨App擷取內容",
                        targetSemanticPhrase = "搜尋 跨App擷取內容",
                        targetAppHint = appHint,
                        inputPayload = CrossAppWorkflowEngine.CLIPBOARD_PLACEHOLDER,
                        isHighRiskHint = isRisk
                    )
                )
                val firstCleaned = cleanedInAppClauses.firstOrNull()?.trim().orEmpty()
                if (firstCleaned in setOf("搜尋", "搜索", "查詢", "查找", "找", "搜")) {
                    clauseIdx = 1
                }
            }
        }

        // 若大塊只是純「打開某 App」（沒有後續塊內動作），直接回傳 LAUNCH_APP 即可
        if (cleanedInAppClauses.isEmpty() && tasks.isNotEmpty()) {
            return tasks
        }
        if (cleanedInAppClauses.isEmpty() && tasks.isEmpty()) {
            tasks.add(
                AtomicMotorTask(
                    taskIndexInBlock = 1,
                    totalTasksInBlock = 1,
                    globalStepIndex = 1,
                    globalTotalSteps = 1,
                    parentBlockIndex = block.blockIndex,
                    totalAppBlocks = totalBlocks,
                    actionKind = AtomicActionKind.TAP_TARGET,
                    simplifiedMotorGoal = block.rawClauses.firstOrNull().orEmpty(),
                    targetSemanticPhrase = block.rawClauses.firstOrNull().orEmpty(),
                    targetAppHint = appHint
                )
            )
            return tasks
        }

        while (clauseIdx < cleanedInAppClauses.size) {
            val clause = cleanedInAppClauses[clauseIdx]
            val lower = clause.lowercase()
            val isRisk = highRiskKeywords.any { lower.contains(it) }

            // 檢查是否為純資訊抽取子句（如「把地鐵抓出來 / 把地點抓出來 / 把單號複製出來」）
            val extractorTarget = extractTargetFromExtractorClause(clause)
            if (extractorTarget != null) {
                explicitExtractEntityHint = extractorTarget
                clauseIdx++
                continue
            }

            // 先使用三層標註詞庫 (CommandLexiconEngine) 進行多義詞條件消歧 (如 o+A>w+W>c+S) 與按鈕名詞 (B) 識別
            val lexiconClauseEval = CommandLexiconEngine.analyzeClauseActionAndTarget(
                clause = clause,
                appHint = appHint,
                report = null,
                context = context
            )
            val isLexiconExplicitTapOrButton = lexiconClauseEval?.resolvedPrimaryCode == 'c' ||
                lexiconClauseEval?.resolvedPrimaryCode == 'w' ||
                lexiconClauseEval?.isTargetUiButton == true ||
                CommandLexiconEngine.isKnownUiButtonNoun(simplifyAtomicTargetPhrase(clause), context)

            // 先嘗試通用「描述性條件蒸餾器」（例如「找到一封最近提到聚餐地點的信」-> 核心檢索詞="聚餐"、需點開內文=true）
            val distilled = if (isLexiconExplicitTapOrButton) null else distillDescriptiveLookupClause(clause)
            if (distilled != null && distilled.inlineExtractHint.isNotBlank() && explicitExtractEntityHint.isBlank()) {
                explicitExtractEntityHint = distilled.inlineExtractHint
            }

            // 檢查此子句是否為「搜尋 / 查詢 / 聽歌 / 買東西 / 輸入」動作（若詞庫已判定為 c 點擊按鈕或 w 開關，嚴禁當成搜尋詞！）
            val (extractedQuery, inlineFollowUp) = when {
                isLexiconExplicitTapOrButton -> "" to ""
                distilled != null -> distilled.coreQuery to ""
                else -> InAppOperationPlanner.extractSearchQueryAndFollowUp(clause)
            }
            val implicitLookup = when {
                isLexiconExplicitTapOrButton -> ""
                distilled != null -> distilled.coreQuery
                extractedQuery.isNotBlank() -> extractedQuery
                else -> extractImplicitLookupTopic(clause)
            }

            // 若在備忘錄/筆記 App 內直接「寫XXX / 記下XXX / 輸入XXX」（非跨 App 剪貼簿）
            val directNoteWritePayload = extractDirectNoteWritePayload(clause, appHint)

            if (directNoteWritePayload.isNotBlank() && isNoteApp(appHint)) {
                tasks.add(
                    AtomicMotorTask(
                        taskIndexInBlock = tasks.size + 1,
                        totalTasksInBlock = 0,
                        globalStepIndex = 0,
                        globalTotalSteps = 0,
                        parentBlockIndex = block.blockIndex,
                        totalAppBlocks = totalBlocks,
                        actionKind = AtomicActionKind.TAP_TARGET,
                        simplifiedMotorGoal = "點擊 新增",
                        targetSemanticPhrase = "新增",
                        targetAppHint = appHint,
                        isHighRiskHint = false
                    )
                )
                tasks.add(
                    AtomicMotorTask(
                        taskIndexInBlock = tasks.size + 1,
                        totalTasksInBlock = 0,
                        globalStepIndex = 0,
                        globalTotalSteps = 0,
                        parentBlockIndex = block.blockIndex,
                        totalAppBlocks = totalBlocks,
                        actionKind = AtomicActionKind.INPUT_SEARCH,
                        simplifiedMotorGoal = "搜尋 $directNoteWritePayload",
                        targetSemanticPhrase = "輸入 $directNoteWritePayload",
                        targetAppHint = appHint,
                        inputPayload = directNoteWritePayload,
                        isHighRiskHint = isRisk
                    )
                )
                clauseIdx++
                continue
            }

            if (implicitLookup.isNotBlank()) {
                lastSearchQueryInBlock = implicitLookup

                // 檢查下一個同 App 子句是否正好是「點第一個影片 / 播放第一首 / 點最上面那個」這類搜尋結果挑選子句
                val nextClause = cleanedInAppClauses.getOrNull(clauseIdx + 1)
                val mergedNextFollowUp = if (nextClause != null && isResultSelectionFollowUpClause(nextClause)) {
                    clauseIdx++ // 吸收下一個子句作為本次搜尋的 Stage C 挑選條件，避免重複點擊兩次
                    cleanResultSelectionPhrase(nextClause)
                } else {
                    ""
                }

                val combinedFollowUp = when {
                    inlineFollowUp.isNotBlank() && mergedNextFollowUp.isNotBlank() -> "$inlineFollowUp $mergedNextFollowUp"
                    mergedNextFollowUp.isNotBlank() -> mergedNextFollowUp
                    else -> inlineFollowUp
                }

                // 原子步驟 A：輸入搜尋詞
                tasks.add(
                    AtomicMotorTask(
                        taskIndexInBlock = tasks.size + 1,
                        totalTasksInBlock = 0,
                        globalStepIndex = 0,
                        globalTotalSteps = 0,
                        parentBlockIndex = block.blockIndex,
                        totalAppBlocks = totalBlocks,
                        actionKind = AtomicActionKind.INPUT_SEARCH,
                        simplifiedMotorGoal = "搜尋 $implicitLookup",
                        targetSemanticPhrase = "搜尋 $implicitLookup",
                        targetAppHint = appHint,
                        inputPayload = implicitLookup,
                        isHighRiskHint = isRisk
                    )
                )

                // 判斷是否需要在搜尋後執行「點選結果 (SELECT_RESULT)」
                // 若為信件/訊息/文章內文查找 (`distilled?.requiresOpeningContainerItem == true`)，必須先點開該筆結果進入內文頁才抓得到實體！
                val needsResultClick = (distilled?.requiresOpeningContainerItem == true) ||
                    combinedFollowUp.isNotBlank() ||
                    (!block.exportsDataToNextBlock && InAppOperationPlanner.requiresResultSelectionAfterSearch(clause, appHint, combinedFollowUp))

                if (needsResultClick) {
                    val semanticPhrase = if (combinedFollowUp.isNotBlank()) {
                        "$implicitLookup $combinedFollowUp"
                    } else {
                        implicitLookup
                    }
                    val motorGoal = if (combinedFollowUp.isNotBlank()) {
                        "搜尋 $implicitLookup 並 $combinedFollowUp"
                    } else {
                        "點選 $implicitLookup 結果"
                    }
                    tasks.add(
                        AtomicMotorTask(
                            taskIndexInBlock = tasks.size + 1,
                            totalTasksInBlock = 0,
                            globalStepIndex = 0,
                            globalTotalSteps = 0,
                            parentBlockIndex = block.blockIndex,
                            totalAppBlocks = totalBlocks,
                            actionKind = AtomicActionKind.SELECT_RESULT,
                            simplifiedMotorGoal = motorGoal,
                            targetSemanticPhrase = semanticPhrase,
                            targetAppHint = appHint,
                            inputPayload = null,
                            isHighRiskHint = isRisk
                        )
                    )
                }

                clauseIdx++
                continue
            }

            // 非搜尋子句：轉換為單一極簡控制動作 (TOGGLE_SWITCH / SCROLL / SYSTEM_BACK / TAP_TARGET ...)
            val toggleHint = when {
                clause.contains("開啟") || clause.contains("打開") || clause.contains("啟用") || clause.endsWith("開") -> true
                clause.contains("關閉") || clause.contains("關掉") || clause.contains("停用") || clause.endsWith("關") -> false
                else -> null
            }

            val cleanTarget = simplifyAtomicTargetPhrase(clause)
            val actionKind = when {
                lower == "返回" || lower == "上一頁" || lower == "回上一頁" -> AtomicActionKind.SYSTEM_BACK
                lower.contains("下滑") || lower.contains("往下滑") || lower.contains("向下滑") ||
                    lower.contains("上滑") || lower.contains("往上滑") || lower.contains("向上滑") -> AtomicActionKind.SCROLL
                lower.contains("左滑") || lower.contains("向左滑") || lower.contains("右滑") || lower.contains("向右滑") -> AtomicActionKind.GESTURE_SWIPE
                lower.contains("長按") || lower.contains("按住") -> AtomicActionKind.LONG_PRESS
                lower.contains("雙擊") || lower.contains("連按兩下") || lower.contains("點兩下") || lower.contains("連點兩下") -> AtomicActionKind.DOUBLE_TAP
                toggleHint != null && isSettingToggleTarget(cleanTarget, appHint) -> AtomicActionKind.TOGGLE_SWITCH
                else -> AtomicActionKind.TAP_TARGET
            }

            val simplifiedMotor = when (actionKind) {
                AtomicActionKind.SYSTEM_BACK -> "返回上一頁"
                AtomicActionKind.SCROLL -> if (lower.contains("上滑")) "向上滑動" else "向下滑動"
                AtomicActionKind.GESTURE_SWIPE -> if (lower.contains("左滑")) "向左滑動" else "向右滑動"
                AtomicActionKind.LONG_PRESS -> "長按 $cleanTarget"
                AtomicActionKind.DOUBLE_TAP -> "雙擊 $cleanTarget"
                AtomicActionKind.TOGGLE_SWITCH -> if (toggleHint == false) "關閉 $cleanTarget" else "開啟 $cleanTarget"
                else -> "點擊 $cleanTarget"
            }

            tasks.add(
                AtomicMotorTask(
                    taskIndexInBlock = tasks.size + 1,
                    totalTasksInBlock = 0,
                    globalStepIndex = 0,
                    globalTotalSteps = 0,
                    parentBlockIndex = block.blockIndex,
                    totalAppBlocks = totalBlocks,
                    actionKind = actionKind,
                    simplifiedMotorGoal = simplifiedMotor,
                    targetSemanticPhrase = cleanTarget,
                    targetAppHint = appHint,
                    inputPayload = null,
                    desiredToggleState = toggleHint,
                    isHighRiskHint = isRisk
                )
            )
            clauseIdx++
        }

        // 4. 若此大塊結束時需要將畫面結果匯出給下一個 App 大塊 (`exportsDataToNextBlock = true`) 或明確要求擷取至暫存區 (`explicitExtractEntityHint`)
        if (block.exportsDataToNextBlock || explicitExtractEntityHint.isNotBlank()) {
            val extractTopic = explicitExtractEntityHint.ifBlank {
                lastSearchQueryInBlock.ifBlank {
                    tasks.lastOrNull()?.targetSemanticPhrase ?: block.rawClauses.lastOrNull().orEmpty()
                }
            }
            tasks.add(
                AtomicMotorTask(
                    taskIndexInBlock = tasks.size + 1,
                    totalTasksInBlock = 0,
                    globalStepIndex = 0,
                    globalTotalSteps = 0,
                    parentBlockIndex = block.blockIndex,
                    totalAppBlocks = totalBlocks,
                    actionKind = AtomicActionKind.EXTRACT_SCREEN_INFO,
                    simplifiedMotorGoal = "擷取 $extractTopic",
                    targetSemanticPhrase = extractTopic,
                    targetAppHint = appHint,
                    inputPayload = null,
                    isHighRiskHint = false
                )
            )
        }

        return tasks
    }

    /**
     * 偵測子句中明確提及的 App 名稱
     */
    fun detectExplicitAppInClause(clause: String, context: Context? = null): String? {
        val lower = clause.lowercase().trim()
        if (lower.isEmpty()) return null

        // 優先比對特定 App 別名（時鐘/鬧鐘、YouTube、Spotify、備忘錄、Chrome、LINE 等）
        for ((keyword, canonicalApp) in ORDERED_EXPLICIT_APP_ALIASES) {
            if (lower.contains(keyword)) {
                return canonicalApp
            }
        }

        // 「設定」需排除「設定鬧鐘 / 設定提醒 / 設定行程」等動詞用法
        if (lower.contains("設定") &&
            !lower.contains("設定鬧鐘") &&
            !lower.contains("設定提醒") &&
            !lower.contains("設定日曆") &&
            !lower.contains("設定行事曆")
        ) {
            return "設定"
        }

        // 「訊息」需排除「傳訊息給...」（若有指明 LINE 則已在上方命中，若僅說「打開訊息」則命中訊息 App）
        if (lower.contains("訊息") && !lower.contains("line") && !lower.contains("微信")) {
            return "訊息"
        }

        // 查詢 7 大開源數據庫中的 87+ 台灣/大陸/國際主流 App 別名庫
        val openSourceApp = OpenSourceAiDatabaseManager.resolveAppByColloquialAlias(clause, context)
        if (openSourceApp != null) {
            return openSourceApp.canonicalLabel
        }

        val backwardSpec = BackwardChainingPlanner.extractTerminalGoalSpec(clause)
        if (!backwardSpec.appHint.isNullOrBlank()) {
            return backwardSpec.appHint
        }

        if (context != null) {
            val matched = InstalledAppLauncher.resolveTargetAppForGoal(context, clause, null)
            if (matched != null && matched.second >= 0.85f) {
                return matched.first.label
            }
        }
        return null
    }

    /**
     * 當第一個子句未直接寫出 App 名稱時，判斷是否可安全推論其初始目標 App
     */
    private fun inferInitialClauseApp(
        firstClause: String,
        hasMultipleClauses: Boolean,
        context: Context?
    ): String? {
        val backwardSpec = BackwardChainingPlanner.extractTerminalGoalSpec(firstClause)
        if (!backwardSpec.appHint.isNullOrBlank()) {
            return backwardSpec.appHint
        }

        if (context != null) {
            val matched = InstalledAppLauncher.resolveTargetAppForGoal(context, firstClause, null)
            if (matched != null && matched.second >= 0.85f) {
                return matched.first.label
            }
        }

        val lower = firstClause.lowercase()
        if (lower.contains("上網查") || lower.contains("上網搜") || lower.contains("google查") ||
            lower.contains("時刻表") || lower.contains("官網") || lower.contains("網站")
        ) {
            return "Chrome"
        }
        // 若為多子句長指令的第一句（例如「先把藍牙打開，然後去YouTube查周杰倫」或「先查今日美金匯率，然後記到備忘錄」）
        if (hasMultipleClauses) {
            if (SETTINGS_FEATURE_KEYWORDS.any { lower.contains(it) } &&
                (lower.contains("打開") || lower.contains("開啟") || lower.contains("關閉") || lower.contains("關掉") || lower.contains("調"))
            ) {
                return "設定"
            }
            if (lower.contains("匯率") || lower.contains("天氣") || lower.contains("股價") || lower.contains("新聞") ||
                lower.contains("時刻表") || lower.contains("票價") || lower.contains("官網") || lower.contains("網站") ||
                lower.startsWith("去查") || lower.startsWith("查一下") || lower.startsWith("搜尋") || lower.startsWith("查詢")
            ) {
                return "Chrome"
            }
        }
        return null
    }

    /**
     * 判斷第 2 個以後的子句（在未寫出顯式 App 名稱時）是否隱含「切換至另一個新 App」的跨 App 轉移意圖。
     * 若僅為「點第一個影片」、「按讚」、「打開藍牙（當前已在設定內）」等塊內後續動作，嚴格回傳 `null` 留在原大塊！
     */
    data class DistilledLookup(
        val coreQuery: String,
        val requiresOpeningContainerItem: Boolean,
        val inlineExtractHint: String = ""
    )

    /**
     * 解析「把地鐵抓出來 / 把開會時間找出來 / 將地址提取出來」這類純抽取子句，回傳目標實體類型名詞（如「地鐵」）
     */
    fun extractTargetFromExtractorClause(clause: String): String? {
        val clean = clause.trim()
        val m = Regex(
            "^(?:請幫我|幫我|請)?\\s*(?:先|再|然後|接著)?\\s*(?:把|將|将)\\s*([^，,。；;]{1,12}?)\\s*(?:給|给)?\\s*(?:抓出來|抓出来|抓出|找出來|找出来|找出|提取出來|提取出来|提取|擷取出來|複製出來|复制出来|複製下來|复制下来|抄下來|抄下来|整理成清單|整理清單)$"
        ).find(clean)
        if (m != null) {
            val target = m.groupValues[1]
                .replace(Regex("^(?:裡面的|里面的|信裡的|信里的|信件裡的|訊息裡的|上的|的)\\s*"), "")
                .trim()
            return target.takeIf { it.isNotEmpty() }
        }
        val mBare = Regex(
            "^(?:請幫我|幫我|請)?\\s*(?:先|再|然後|接著)?\\s*(?:整理成清單|整理清單|複製結果|复制结果|複製查詢結果|擷取結果|提取結果)$"
        ).find(clean)
        if (mBare != null) {
            return "結果"
        }
        return null
    }

    /**
     * 通用「描述性條件蒸餾器 (Descriptive Lookup Distiller)」：
     * 將「找到一封最近提到聚餐地點的信」、「找一下昨天老闆傳的開會時間訊息」蒸餾為：
     * - `coreQuery = "聚餐"`（純檢索核心詞，供搜尋框輸入）
     * - `requiresOpeningContainerItem = true`（搜尋後必須點開該封信件/訊息進入內文頁才看得到細節）
     * - `inlineExtractHint = "地點"`（內嵌的抽取屬性提示）
     */
    fun distillDescriptiveLookupClause(clause: String): DistilledLookup? {
        val clean = clause.trim()
        val containerRegex = Regex(
            "(?:找到|找一下|找出|找|查看|查一下|搜尋|搜索|看)?\\s*(?:一封|一則|一條|一篇|那個|那封|那則)?\\s*(?:最近|剛剛|昨天|今天|前天|上週|最新)?\\s*(?:提到|關於|有關|寫著|寫有|包含|有提到|說到|討論)?\\s*(.+?)\\s*的\\s*(信件|信|郵件|email|mail|訊息|對話|聊天紀錄|貼文|文章|通知|訂單)$",
            RegexOption.IGNORE_CASE
        )
        val match = containerRegex.find(clean) ?: return null
        var rawTopic = match.groupValues[1].trim()
            .replace(Regex("^(?:一封|一則|一條|一篇|最近|剛剛|昨天|今天|提到|關於|有關|寫著|包含)\\s*"), "")
            .trim()
        if (rawTopic.isEmpty()) return null

        val trailingAttrRegex = Regex("^(.{2,}?)(地點|地址|地鐵站|地鐵|捷運站|捷運|餐廳|位置|時間|日期|幾點|驗證碼|單號|電話|網址|連結|金額|價格|密碼)$")
        val attrMatch = trailingAttrRegex.find(rawTopic)
        val coreQuery: String
        val inlineAttr: String
        if (attrMatch != null && attrMatch.groupValues[1].trim().isNotEmpty()) {
            coreQuery = attrMatch.groupValues[1].trim()
            inlineAttr = attrMatch.groupValues[2].trim()
        } else {
            coreQuery = rawTopic
            inlineAttr = ""
        }

        return DistilledLookup(
            coreQuery = coreQuery,
            requiresOpeningContainerItem = true,
            inlineExtractHint = inlineAttr
        )
    }

    private fun inferCrossAppTransitionTarget(clause: String, currentAppHint: String?): String? {
        val lower = clause.lowercase()
        val curAppLower = currentAppHint?.lowercase().orEmpty()

        // 若為當前畫面的純抽取子句（如「把地鐵抓出來」），留在原 App 大塊內執行抽取，不誤判為切換備忘錄
        if (extractTargetFromExtractorClause(clause) != null) {
            return null
        }

        // 1. 筆記 / 備忘錄寫入動詞（若當前不在備忘錄內 -> 切換至「備忘錄」大塊）
        if (NOTE_AND_SAVE_VERBS.any { lower.contains(it) }) {
            if (!isNoteApp(currentAppHint)) {
                return "備忘錄"
            }
        }

        // 2. 通訊傳送動詞（若當前不在 LINE / 訊息內 -> 切換至「LINE」大塊）
        if (SHARE_AND_SEND_VERBS.any { lower.contains(it) }) {
            if (!curAppLower.contains("line") && !curAppLower.contains("訊息")) {
                return "LINE"
            }
        }

        // 3. 鬧鐘設定（若當前不在時鐘內 -> 切換至「時鐘」大塊）
        if (lower.contains("鬧鐘") || lower.contains("定時器") || lower.contains("碼錶")) {
            if (!curAppLower.contains("時鐘")) {
                return "時鐘"
            }
        }

        // 4. 明確的系統設定開關（僅當目前處於非設定 App 如 YouTube/Chrome 時，才視為切換到「設定」大塊）
        if (curAppLower.isNotEmpty() && curAppLower != "設定" &&
            SETTINGS_FEATURE_KEYWORDS.any { lower.contains(it) } &&
            (lower.contains("打開") || lower.contains("開啟") || lower.contains("關閉") || lower.contains("關掉"))
        ) {
            return "設定"
        }

        return null
    }

    private fun inferFallbackAppForFirstBlockWhenMultiApp(firstBlockText: String): String? {
        val lower = firstBlockText.lowercase()
        return when {
            SETTINGS_FEATURE_KEYWORDS.any { lower.contains(it) } -> "設定"
            lower.contains("聽") || lower.contains("mv") || lower.contains("影片") -> "YouTube"
            lower.contains("信") || lower.contains("郵件") || lower.contains("email") -> "Gmail"
            lower.contains("查") || lower.contains("搜尋") || lower.contains("搜索") ||
                lower.contains("天氣") || lower.contains("匯率") || lower.contains("新聞") -> "Chrome"
            else -> null
        }
    }

    private fun isClipboardConsumerClause(clause: String, targetAppHint: String?): Boolean {
        val lower = clause.lowercase()
        // 若子句是明確自己指定要寫的字面內容（例如「去備忘錄寫帶雨傘」），則不是消費上一個 App 的剪貼簿
        val directPayload = extractDirectNoteWritePayload(clause, targetAppHint)
        if (directPayload.isNotBlank()) return false

        val strippedAction = stripLeadingAppOpenPrefix(clause, targetAppHint).trim()
        val isBareSearchOrRouteConsumer = strippedAction in setOf(
            "搜尋", "搜索", "查詢", "查找", "找", "搜",
            "規劃路線", "规划路线", "路線規劃", "路线规划", "導航", "导航", "查路線", "搜尋路線"
        ) || Regex("^(?:搜尋|搜索|查詢|查找)(?:它|這個地點|那個地點|該地點)?(?:並|并|再|後|后).+$").matches(strippedAction)

        val hasDataCarrierPrefix = lower.contains("把文字") ||
            lower.contains("把結果") ||
            lower.contains("把重點") ||
            lower.contains("把內容") ||
            lower.contains("把資料") ||
            lower.contains("把網址") ||
            lower.contains("把連結") ||
            lower.contains("把股價") ||
            lower.contains("把匯率") ||
            lower.contains("把天氣") ||
            lower.contains("把地點") ||
            lower.contains("把地址") ||
            lower.contains("把地鐵") ||
            lower.contains("把時間")

        return NOTE_AND_SAVE_VERBS.any { lower.contains(it) } ||
            SHARE_AND_SEND_VERBS.any { lower.contains(it) } ||
            hasDataCarrierPrefix ||
            isBareSearchOrRouteConsumer ||
            lower.contains("記下來") ||
            lower.contains("貼上") ||
            lower.contains("存起來")
    }

    private fun isNoteApp(appHint: String?): Boolean {
        val lower = appHint?.lowercase().orEmpty()
        return lower.contains("備忘") || lower.contains("keep") || lower.contains("note") || lower.contains("筆記") || lower.contains("記事")
    }

    private fun extractDirectNoteWritePayload(clause: String, appHint: String?): String {
        if (!isNoteApp(appHint)) return ""
        val stripped = stripLeadingAppOpenPrefix(clause, appHint)
        if (stripped.isEmpty()) return ""
        // 若屬於「記下來 / 存起來 / 貼上 / 把結果記下來」則是跨 App 剪貼簿，非直接指定文字
        val genericClipboardPhrases = setOf("記下來", "存起來", "貼上", "記錄下來", "把結果記下來", "把重點記下來", "記下歌名", "記下標題", "記下結果")
        if (stripped in genericClipboardPhrases || genericClipboardPhrases.any { stripped.endsWith(it) }) {
            return ""
        }
        val m = Regex("^(?:寫下|寫上|寫|記下|記錄|輸入|新增筆記寫|新增)[:：\\s\"「『]*([^\"」』，,。！!？?]+)[」』\"]?$").find(stripped)
        if (m != null) {
            val content = m.groupValues[1].trim()
            if (content.isNotEmpty() && content !in genericClipboardPhrases) {
                return content
            }
        }
        return ""
    }

    /**
     * 判斷子句是否為緊接在「搜尋」之後的「結果點選動作」（例如：「點第一個影片」、「播放第一首」、「點最上面那個」、「點開第一個」）
     */
    private fun isResultSelectionFollowUpClause(clause: String): Boolean {
        val clean = clause.trim()
        if (clean.isEmpty()) return false
        if (BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(clean)) {
            return true
        }
        val selectionRegex = Regex(
            "^(?:請幫我|幫我|請)?(?:點擊|點選|點開|點進|點入|點一下|點|選擇|選|播放|打開|開啟|進入|進|看|聽)?\\s*(?:最上面|最上方|頂部)?(?:第[一二三四五12345][個首部支篇條則項名]|那[個首部支]影片|那首歌|第一個結果|第一筆|第一個|網站|官網|官方網站|網頁|連結).*$"
        )
        return selectionRegex.matches(clean) ||
            clean == "播放" || clean == "點開" || clean == "播放第一首" || clean == "點第一個" ||
            clean == "點進網站" || clean == "點進官網" || clean == "進入網站" || clean == "進網站" || clean == "點開網站"
    }

    private fun cleanResultSelectionPhrase(clause: String): String {
        return clause.trim()
            .replace(Regex("^(?:請幫我|幫我|請|然後|接著|再|並且|並|并)\\s*"), "")
            .replace(Regex("^(?:點擊|點選|點一下|按一下|按下|選擇|進入)\\s*"), "")
            .trim()
            .ifBlank { clause.trim() }
    }

    private fun isSettingToggleTarget(cleanTarget: String, appHint: String?): Boolean {
        val lower = cleanTarget.lowercase()
        return appHint == "設定" ||
            SETTINGS_FEATURE_KEYWORDS.any { lower.contains(it) } ||
            lower.contains("開關") ||
            lower.contains("模式")
    }

    private fun extractRecipientOrNoteEntry(clause: String, targetAppHint: String?): String? {
        val recipientMatch = Regex(
            "(?:傳訊息給|傳LINE給|傳line給|轉傳給|轉發給|傳送給|發送到|分享給|分享到|傳給|傳到|發給|貼給|丟給|丟到|寄給|送給|賴給|line給|告訴)\\s*([^，,。\\s]{1,16})",
            RegexOption.IGNORE_CASE
        ).find(clause)
        if (recipientMatch != null) {
            val rawTarget = recipientMatch.groupValues[1].trim()
            val person = rawTarget
                .replace(Regex("^(?:在|去|到)?(?:line|賴|微信|メッセージ|訊息)(?:裡面|裡|上|內|中)?(?:的|裡)?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("(?:說.*|並說.*)$"), "")
                .trim()
            if (person.isNotEmpty() && !person.equals("line", ignoreCase = true) && person != "賴") {
                return person
            }
        }
        if (isNoteApp(targetAppHint)) {
            return "新增"
        }
        return null
    }

    fun stripLeadingAppOpenPrefix(clause: String, appHint: String?): String {
        var s = clause.trim()
        s = s.replace(Regex("^(?:請幫我|幫我|我要|我想|請)?\\s*(?:先|再|然後|接著|最後|直接|馬上|立刻|順便|顺便)?\\s*"), "").trim()
        if (appHint.isNullOrBlank()) {
            return s
        }

        // 收集此 App 的所有別名與標準名稱（整合 7 大開源數據庫 App 別名表）
        val candidateAliases = ArrayList<String>()
        for ((alias, canonical) in ORDERED_EXPLICIT_APP_ALIASES) {
            if (canonical.equals(appHint, ignoreCase = true) || alias.equals(appHint, ignoreCase = true)) {
                candidateAliases.add(alias)
            }
        }
        candidateAliases.addAll(OpenSourceAiDatabaseManager.getAllAliasesForAppLabel(appHint))
        candidateAliases.add(appHint)
        val sortedAliases = candidateAliases.distinct().sortedByDescending { it.length }

        // 僅當開頭是「(直接/去/到/打開/開啟/進入/在/用)? + App名稱」時才剝離該 App 啟動前綴，絕不誤剝「打開深色模式」的「打開」！
        val afterOpenVerb = s.replace(Regex("^(?:直接|馬上|立刻|順便|顺便)?\\s*(?:去|到|打開|開啟|打开|开启|進入|进入|切換到|切换到|在|用)\\s*"), "").trim()
        for (alias in sortedAliases) {
            if (afterOpenVerb.lowercase().startsWith(alias.lowercase())) {
                val remainder = afterOpenVerb.substring(alias.length).trim()
                    .replace(Regex("^(?:瀏覽器|應用程式|軟體|程式|app)\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                return remainder.replace(Regex("^(?:裡面|裡|內|中|上|去|並|并|然後|接著)\\s*"), "").trim()
            }
        }
        return s
    }

    private fun stripLeadingConnectorNoise(clause: String, appHint: String?): String {
        val s = clause.trim()
            .replace(Regex("^(?:請幫我|幫我|我要|我想|請)?\\s*(?:先|再|然後|接著|最後|並且|并且|並|并|直接|順便|顺便)?\\s*"), "")
            .trim()
        if (!appHint.isNullOrBlank()) {
            return stripLeadingAppOpenPrefix(s, appHint)
        }
        return s
    }

    private fun simplifyAtomicTargetPhrase(clause: String): String {
        var s = clause.trim()
        // 支援「把 Wi-Fi 打開 / 將藍牙關掉」語法還原為純目標名詞「Wi-Fi / 藍牙」
        val baPattern = Regex("^(?:請幫我|幫我|請)?\\s*(?:先|再)?\\s*(?:把|將)\\s*(.+?)\\s*(?:給)?(?:打開|開啟|啟用|關閉|關掉|停用|點開|按一下|點一下)$")
        val mBa = baPattern.find(s)
        if (mBa != null && mBa.groupValues[1].isNotBlank()) {
            return mBa.groupValues[1].trim()
        }

        s = s.replace(
            Regex("^(?:請幫我|幫我|我想|我要|請)?\\s*(?:先|再|並|并)?\\s*(?:點擊|點選|點開|點一下|點極|點及|點|按一下|按下|打開|開啟|關閉|關掉|啟用|停用|進入|選擇|選|切換到|切換|去|把|按個|按)\\s*"),
            ""
        ).trim()
        s = s.replace(Regex("(?:那個|這個|那顆|這顆|該)?(?:按鈕|按鍵|圖示|圖標|鍵|打開|開啟|關閉|關掉|一下)+$"), "").trim()
        if (s in setOf("規劃路線", "规划路线", "路線規劃", "路线规划", "怎麼走", "怎么走", "路線", "路线")) {
            return "路線"
        }
        if (s in setOf("開始導航", "开始导航", "導航", "导航")) {
            return "導航"
        }
        return s.ifBlank { clause.trim() }
    }

    private fun extractImplicitLookupTopic(actionPart: String): String {
        val cleanAction = actionPart.trim()
        if (CommandLexiconEngine.isKnownUiButtonNoun(cleanAction) ||
            CommandLexiconEngine.isKnownUiButtonNoun(simplifyAtomicTargetPhrase(cleanAction))
        ) {
            return ""
        }
        val spec = BackwardChainingPlanner.extractTerminalGoalSpec(cleanAction)
        if (spec.terminalVerb == TerminalVerb.TAP_BUTTON) {
            return ""
        }
        if (spec.targetEntity.isNotBlank() &&
            (spec.terminalVerb == TerminalVerb.SEARCH_FIND ||
                spec.terminalVerb == TerminalVerb.PLAY ||
                spec.terminalVerb == TerminalVerb.BUY)
        ) {
            val nonSearchWords = setOf("設定", "藍牙", "wifi", "wi-fi", "網路", "返回", "上一頁", "首頁", "深色模式", "相簿", "行車路線", "規劃路線", "路線", "開始導航", "導航")
            if (spec.targetEntity.lowercase() !in nonSearchWords &&
                !CommandLexiconEngine.isKnownUiButtonNoun(spec.targetEntity) &&
                !BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(spec.targetEntity)
            ) {
                return spec.targetEntity.trim()
            }
        }

        val m = Regex("(?:查詢|查一下|查看|查|找一下|找|搜尋|搜索|看看|聽一下|聽)\\s*(.+)$").find(actionPart.trim())
        if (m != null) {
            val candidate = m.groupValues[1]
                .replace(Regex("(?:並複製|然後複製|的結果)$"), "")
                .trim()
            if (!BackwardChainingPlanner.isGenericOrPositionalSelectionTarget(candidate)) {
                return candidate
            }
        }
        return ""
    }
}
