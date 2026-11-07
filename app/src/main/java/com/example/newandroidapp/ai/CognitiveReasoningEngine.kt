package com.example.newandroidapp.ai

import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import kotlin.math.abs

/**
 * 四層 AI 認知推理與深度理解大腦（CognitiveReasoningEngine）
 *
 * 專責提升本機 AI 的「思考邏輯能力」與「人類口語理解力」：
 * 1. 【升級 #1：口語常識轉譯與代名詞／比較級／空間消解】
 *    - 將隱喻口語（「太亮了」、「太吵了」、「我想聽周杰倫」、「幫我設明天7點鬧鐘」、「連家裡網路」）轉譯為明確的目標 App 與操作意圖。
 *    - 解析多輪代名詞（「點它」、「把剛剛那個關掉」）與畫面比較級（「最便宜的」、「最貴的」、「最上面那個」、「最下面那個」、「右邊那個」）。
 * 2. 【升級 #3：ReAct「先診斷頁面狀態與前置條件、再行動」三問邏輯鏈】
 *    - 第一問：當前是什麼頁面？（規格選擇框 / 搜尋無結果頁 / 設定開關頁 / 搜尋輸入頁 / 結果列表頁）
 *    - 第二問：距離目標是否缺少前置條件？（例如要點「結帳/確定」前，若出現「請選擇規格/尺寸/顏色」且尚未選中，自動先選規格！）
 *    - 第三問：點選此元件是否有跳頁副作用？（執行任務中途自動屏障底部無關導覽列分頁）
 * 3. 【升級 #4：失敗自我反思與動態改道 (Self-Reflection & Adaptive Re-Planning)】
 *    - 偵測「找不到相關結果 / 無結果」時，自動精簡過長關鍵字並觸發重搜，不再死卡無限下滑。
 *    - 當點擊某項目無反應時，自動尋找同行（Same Row）尚未點擊的關聯開關或按鈕進行替代點擊。
 */
object CognitiveReasoningEngine {

    enum class ScreenSemanticState(val label: String) {
        SPEC_OR_OPTION_MODAL("規格/選項前置彈窗"),
        EMPTY_SEARCH_RESULT("搜尋無結果頁"),
        SETTINGS_CONTROL_PAGE("系統設定/開關列表頁"),
        SEARCH_INPUT_PAGE("搜尋輸入頁"),
        RESULT_OR_CONTENT_LIST("內容與結果列表頁"),
        GENERAL_PAGE("一般互動頁面")
    }

    data class ColloquialResolution(
        val originalGoal: String,
        val normalizedGoal: String,
        val inferredAppHint: String? = null,
        val cognitiveNote: String = ""
    )

    data class CognitiveDiagnosis(
        val screenState: ScreenSemanticState,
        val overrideTargetNode: Stage2ConvergedNode? = null,
        val overrideActionType: AiActionType? = null,
        val overrideInputText: String? = null,
        val overrideConfidence: Float = 0.96f,
        val reasoningTraceNote: String = "",
        val candidateScoreDeltas: Map<String, Float> = emptyMap()
    )

    private val PRICE_REGEX = Regex("(?:NT\\$|US\\$|HK\\$|\\$|¥|￥|元|塊)\\s*(\\d[\\d,]*(?:\\.\\d+)?)|(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:元|塊)")
    private val EMPTY_RESULT_HINTS = listOf(
        "找不到相關", "沒有找到", "無搜尋結果", "查無資料", "查無結果", "沒有符合", "暫無內容", "no results", "nothing found"
    )
    private val SPEC_MODAL_HINTS = listOf(
        "請選擇規格", "選擇規格", "選擇尺寸", "選擇顏色", "規格", "尺寸", "顏色分類", "容量", "款式"
    )
    private val CHECKOUT_FINAL_WORDS = listOf(
        "立即購買", "加入購物車", "確定", "確認", "結帳", "去買單", "立即下單"
    )

    /**
     * 【升級 #1】口語常識轉譯與多輪代名詞指代消解
     */
    fun resolveColloquialAndContextualGoal(rawGoal: String): ColloquialResolution {
        val clean = rawGoal.trim()
        if (clean.isEmpty()) return ColloquialResolution(rawGoal, rawGoal)

        val lower = clean.lowercase()
        val turnCtx = ConversationManager.getTurnContext()

        // 1. 多輪代名詞指代消解（「點它」、「打開它」、「把剛剛那個關掉」、「點剛剛那個」）
        if (clean.contains("剛剛那個") || clean.contains("剛才那個") || clean == "點它" || clean == "打開它" || clean == "關掉它" || clean == "切換它") {
            val prevLabel = turnCtx.lastCandidates.firstOrNull()?.label?.takeIf { it.isNotBlank() }
                ?: turnCtx.lastSearchQuery?.takeIf { it.isNotBlank() }
            if (prevLabel != null) {
                val resolved = when {
                    clean.contains("關") -> "關閉 $prevLabel"
                    clean.contains("開") -> "開啟 $prevLabel"
                    else -> "點擊 $prevLabel"
                }
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = resolved,
                    inferredAppHint = null,
                    cognitiveNote = "[上下文指代消解] 將「$clean」綁定至上一輪目標「$prevLabel」"
                )
            }
        }

        // 2. 人類日常口語與常識狀態轉譯（Commonsense State-to-Action Mapping）
        when {
            lower.contains("太亮") || lower.contains("刺眼") || lower.contains("調暗") || lower.contains("暗一點") -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 搜尋 顯示 亮度 深色模式",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > 顯示與亮度 / 深色模式」"
                )
            }
            lower.contains("太暗") || lower.contains("看不清楚") || lower.contains("調亮") || lower.contains("亮一點") -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 搜尋 顯示 亮度",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > 顯示與亮度」調高亮度"
                )
            }
            lower.contains("太吵") || lower.contains("太大聲") || lower.contains("小聲一點") || lower.contains("關小聲") || lower.contains("靜音") -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 搜尋 聲音與震動 音量 靜音",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > 聲音與震動 / 音量」"
                )
            }
            lower.contains("太小聲") || lower.contains("聽不見") || lower.contains("大聲一點") -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 搜尋 聲音與震動 音量",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > 聲音與震動」調高音量"
                )
            }
            (lower.contains("連") && (lower.contains("網") || lower.contains("wifi") || lower.contains("wi-fi"))) || lower == "沒有網路" || lower == "連家裡網路" -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 點擊 網路和網際網路 Wi-Fi",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > Wi-Fi 網路連線」"
                )
            }
            lower.contains("開熱點") || lower.contains("分享網路") || lower.contains("基地台") -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 搜尋 無線基地台 熱點",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > 無線基地台與熱點」"
                )
            }
            lower.contains("沒電") || lower.contains("快沒電") || lower.contains("省電") -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開設定 搜尋 電池 省電模式",
                    inferredAppHint = "設定",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 前往「設定 > 電池與省電模式」"
                )
            }
            lower.contains("叫我起床") || (lower.contains("鬧鐘") && (lower.contains("訂") || lower.contains("設"))) -> {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開時鐘 點擊 鬧鐘 新增",
                    inferredAppHint = "時鐘",
                    cognitiveNote = "[常識意圖轉譯]「$clean」-> 開啟「時鐘 > 鬧鐘 > 新增」"
                )
            }
            lower.startsWith("我想聽") || lower.startsWith("我要聽") || lower.startsWith("幫我放") ||
                lower.startsWith("幫我播") || lower.startsWith("放一首") || lower.startsWith("來一首") ||
                lower.startsWith("來首") || lower.startsWith("我想看") || lower.startsWith("我要看") -> {
                val rawMediaTopic = clean.replace(
                    Regex("^(我想聽|我要聽|幫我放|幫我播|放一首歌叫|放一首歌|放一首|來一首歌|來一首|來首|我想看|我要看)"),
                    ""
                ).trim()
                val mediaTopic = if (rawMediaTopic.isEmpty() && (lower.contains("歌") || lower.contains("音樂"))) {
                    "音樂"
                } else {
                    rawMediaTopic
                }
                if (mediaTopic.isNotEmpty() && !lower.contains("youtube") && !lower.contains("yt") && !lower.contains("spotify")) {
                    return ColloquialResolution(
                        originalGoal = rawGoal,
                        normalizedGoal = "打開 YouTube 搜尋 $mediaTopic 並播放第一個",
                        inferredAppHint = "YouTube",
                        cognitiveNote = "[常識意圖轉譯]「$clean」-> 轉譯為開啟 YouTube 影音搜尋「$mediaTopic」並播放"
                    )
                }
            }
            Regex("^(去|上|開|打開|用)\\s*yt\\s*(查|找|搜|搜尋|搜索|看|聽|放|播)?\\s*(.+)$", RegexOption.IGNORE_CASE).matches(clean) -> {
                val m = Regex("^(去|上|開|打開|用)\\s*yt\\s*(查|找|搜|搜尋|搜索|看|聽|放|播)?\\s*(.+)$", RegexOption.IGNORE_CASE).find(clean)!!
                val topic = m.groupValues[3].trim()
                if (topic.isNotEmpty()) {
                    return ColloquialResolution(
                        originalGoal = rawGoal,
                        normalizedGoal = "打開 YouTube 搜尋 $topic",
                        inferredAppHint = "YouTube",
                        cognitiveNote = "[口語縮寫轉譯]「$clean」-> 轉譯為「打開 YouTube 搜尋 $topic」"
                    )
                }
            }
            lower.startsWith("帶我去") || lower.startsWith("怎麼去") || lower.startsWith("怎麼走去") || lower.startsWith("導航去") -> {
                val place = clean.replace(Regex("^(帶我去|怎麼去|怎麼走去|導航去)"), "").trim()
                if (place.isNotEmpty() && !lower.contains("地圖") && !lower.contains("maps")) {
                    return ColloquialResolution(
                        originalGoal = rawGoal,
                        normalizedGoal = "打開 Google 地圖 搜尋 $place",
                        inferredAppHint = "Google 地圖",
                        cognitiveNote = "[口語導航轉譯]「$clean」-> 轉譯為「打開 Google 地圖 搜尋 $place」"
                    )
                }
            }
            lower.startsWith("上網查") || lower.startsWith("幫我上網查") || lower.startsWith("幫我問google") -> {
                val topic = clean.replace(Regex("^(幫我上網查|上網查|幫我問google)", RegexOption.IGNORE_CASE), "").trim()
                if (topic.isNotEmpty()) {
                    return ColloquialResolution(
                        originalGoal = rawGoal,
                        normalizedGoal = "打開 Chrome 搜尋 $topic",
                        inferredAppHint = "Chrome",
                        cognitiveNote = "[口語檢索轉譯]「$clean」-> 轉譯為「打開 Chrome 搜尋 $topic」"
                    )
                }
            }
        }

        // 3. 疑問句與知識檢索自動規劃（例如：「誰是蔣介石」->「打開 Chrome 搜尋 蔣介石」）
        val hasExplicitUiVerb = Regex(
            "(打開|開啟|啟動|進入|去|點選|點擊|點一下|按一下|按下|搜尋|搜索|查找|輸入|播放|觀看|往下滑|向下滑|往上滑|向上滑|下滑|上滑|往左滑|往右滑|返回|上一頁|關閉|開關|設定|設定頁|讀畫面|朗讀|youtube|line|chrome|maps|地圖|gmail|計算機|相機|電話|簡訊|音樂|spotify|netflix|instagram|facebook|threads|蝦皮|shopee|momo)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(clean) && !clean.startsWith("幫我查") && !clean.startsWith("查一下")

        if (!hasExplicitUiVerb) {
            val qExt = LocalKnowledgeAndReasoningEngine.extractQuestionTopic(clean)
            if (qExt.isLocationLookup && qExt.cleanTopic.isNotEmpty()) {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開 Google 地圖 搜尋 ${qExt.cleanTopic}",
                    inferredAppHint = "Google 地圖",
                    cognitiveNote = "[地點疑問自動規劃] 將「$clean」規劃為「打開 Google 地圖 搜尋 ${qExt.cleanTopic}」"
                )
            }
            if (qExt.isQuestionOrLookup && qExt.cleanTopic.isNotEmpty()) {
                return ColloquialResolution(
                    originalGoal = rawGoal,
                    normalizedGoal = "打開 Chrome 搜尋 ${qExt.cleanTopic}",
                    inferredAppHint = "Chrome",
                    cognitiveNote = "[知識疑問自動規劃] 將「$clean」規劃為「打開 Chrome 搜尋 ${qExt.cleanTopic}」"
                )
            }
        }

        return ColloquialResolution(rawGoal, clean)
    }

    /**
     * 【升級 #1 & #3 & #4】整合執行：
     * - 比較級與空間方位定位（「最便宜的」、「最貴的」、「最上面那個」、「最下面那個」、「左邊」、「右邊」）
     * - ReAct 頁面狀態診斷與前置條件檢查（如：結帳前先選商品規格）
     * - 搜尋無結果自動縮短關鍵字反思改道
     * - 點擊無反應自動改點同行（Same Row）關聯控制項
     */
    fun diagnoseAndReason(
        report: VisionPipelineReport,
        userGoal: String,
        activeSubGoalPhrase: String,
        screenHash: String
    ): CognitiveDiagnosis {
        val nodes = report.finalNodes
        val screenState = classifyScreenState(nodes)
        if (nodes.isEmpty()) {
            return CognitiveDiagnosis(screenState = screenState)
        }

        val combinedGoal = "$userGoal $activeSubGoalPhrase".lowercase()
        val scoreDeltas = HashMap<String, Float>()

        // =========================================================================
        // 1. 【比較級數值邏輯推理】：「最便宜 / 最低價」 vs 「最貴 / 最高價」
        // =========================================================================
        val wantsCheapest = combinedGoal.contains("最便宜") || combinedGoal.contains("最低價") || combinedGoal.contains("價格最低")
        val wantsMostExpensive = combinedGoal.contains("最貴") || combinedGoal.contains("最高價") || combinedGoal.contains("價格最高")

        if (wantsCheapest || wantsMostExpensive) {
            val pricedNodes = nodes.mapNotNull { node ->
                val price = extractPriceValue(node.label)
                if (price != null && price > 0.0 && !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) {
                    Pair(node, price)
                } else null
            }
            if (pricedNodes.isNotEmpty()) {
                val chosenPair = if (wantsCheapest) {
                    pricedNodes.minByOrNull { it.second }!!
                } else {
                    pricedNodes.maxByOrNull { it.second }!!
                }
                val compTag = if (wantsCheapest) "最低價" else "最高價"
                return CognitiveDiagnosis(
                    screenState = screenState,
                    overrideTargetNode = chosenPair.first,
                    overrideActionType = AiActionType.TAP,
                    overrideConfidence = 0.96f,
                    reasoningTraceNote = "[比較級數值邏輯推理] 比對畫面 ${pricedNodes.size} 個標價項目，鎖定${compTag}（$${chosenPair.second}）-> #${chosenPair.first.shortMarkId}「${chosenPair.first.label}」"
                )
            }
        }

        // =========================================================================
        // 2. 【決策層：隨機挑選 (RANDOM_PICK) 與 序數方位挑選 (ORDINAL_PICK / SPATIAL)】
        // 針對「進入gmail隨機選一個mail來點」、「點第一封信」、「選第二個」等列表指令，
        // 直接在主內容列表區 (y in 230..830) 排除頂部搜尋列、底部導覽列與右下角撰寫鈕，精準鎖定列表項目下發 TAP 指令！
        // =========================================================================
        val isLaunchStep = activeSubGoalPhrase.startsWith("啟動目標 App") || activeSubGoalPhrase.startsWith("打開")
        val rawListCandidates = nodes.filter { node ->
            val lbl = node.label.trim()
            val lblLower = lbl.lowercase()
            node.shortTypeTag != "INP" &&
                node.shortTypeTag != "SCR" &&
                node.gridTapPoint.y in 230..830 &&
                !(node.gridTapPoint.x >= 680 && node.gridTapPoint.y >= 700) &&
                lbl.length >= 2 &&
                !lblLower.contains("在郵件中搜尋") &&
                !lblLower.contains("搜尋郵件") &&
                !lblLower.contains("撰寫") &&
                !lblLower.contains("星號") &&
                !com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node) &&
                !com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenBrowserToolbarNode(node, userGoal) &&
                !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)
        }.sortedBy { it.gridTapPoint.y }

        // 依列表列高 (Row Y) 去重，每一列保留資訊最完整的主項目
        val distinctRowCandidates = ArrayList<Stage2ConvergedNode>()
        for (cand in rawListCandidates) {
            val existingIdx = distinctRowCandidates.indexOfFirst { abs(it.gridTapPoint.y - cand.gridTapPoint.y) <= 42 }
            if (existingIdx < 0) {
                distinctRowCandidates.add(cand)
            } else if (cand.label.length > distinctRowCandidates[existingIdx].label.length) {
                distinctRowCandidates[existingIdx] = cand
            }
        }

        val hasUnsubmittedSearchIntent = (
            com.example.newandroidapp.stability.InAppOperationPlanner.hasExplicitSearchOrInputIntent(userGoal) ||
                com.example.newandroidapp.stability.InAppOperationPlanner.hasExplicitSearchOrInputIntent(activeSubGoalPhrase)
            ) && !InAppWorkingMemory.hasAnySubmittedQuery()

        val wantsRandomPick = !isLaunchStep && !hasUnsubmittedSearchIntent && (
            combinedGoal.contains("隨機") ||
                combinedGoal.contains("隨便") ||
                combinedGoal.contains("任選") ||
                combinedGoal.contains("任一") ||
                combinedGoal.contains("任意") ||
                combinedGoal.contains("挑一個") ||
                combinedGoal.contains("選一個") ||
                combinedGoal.contains("點一個") ||
                combinedGoal.contains("挑一封") ||
                combinedGoal.contains("選一封") ||
                combinedGoal.contains("點一封") ||
                combinedGoal.contains("random")
            )

        if (wantsRandomPick && distinctRowCandidates.isNotEmpty()) {
            val pickIdx = kotlin.random.Random.nextInt(distinctRowCandidates.size)
            val chosen = distinctRowCandidates[pickIdx]
            return CognitiveDiagnosis(
                screenState = screenState,
                overrideTargetNode = chosen,
                overrideActionType = AiActionType.TAP,
                overrideConfidence = 0.96f,
                reasoningTraceNote = "[決策層:隨機列表挑選] 已排除頂部搜尋列與導覽列，從 ${distinctRowCandidates.size} 個列表項目中隨機鎖定第 ${pickIdx + 1} 項 -> #${chosen.shortMarkId}「${chosen.label.take(24)}」"
            )
        }

        val ordinalPick: Pair<Int, String>? = if (!isLaunchStep && !hasUnsubmittedSearchIntent) {
            when {
                combinedGoal.contains("第一個") || combinedGoal.contains("第一封") || combinedGoal.contains("第一則") ||
                    combinedGoal.contains("第一部") || combinedGoal.contains("第一篇") || combinedGoal.contains("第一條") ||
                    combinedGoal.contains("最上面那個") || combinedGoal.contains("最新一封") || combinedGoal.contains("最新一個") -> 0 to "第一項"
                combinedGoal.contains("第二個") || combinedGoal.contains("第二封") || combinedGoal.contains("第二則") ||
                    combinedGoal.contains("第二部") -> 1 to "第二項"
                combinedGoal.contains("第三個") || combinedGoal.contains("第三封") || combinedGoal.contains("第三則") ||
                    combinedGoal.contains("第三部") -> 2 to "第三項"
                else -> null
            }
        } else null

        if (ordinalPick != null && distinctRowCandidates.isNotEmpty()) {
            val targetIdx = ordinalPick.first.coerceAtMost(distinctRowCandidates.size - 1)
            val chosen = distinctRowCandidates[targetIdx]
            return CognitiveDiagnosis(
                screenState = screenState,
                overrideTargetNode = chosen,
                overrideActionType = AiActionType.TAP,
                overrideConfidence = 0.96f,
                reasoningTraceNote = "[決策層:序數列表挑選] 依據「${ordinalPick.second}」鎖定主列表 #${chosen.shortMarkId}「${chosen.label.take(24)}」"
            )
        }

        val contentCandidates = nodes.filter {
            it.shortTypeTag != "SCR" &&
                it.shortTypeTag != "INP" &&
                it.gridTapPoint.y in 180..860 &&
                !InAppWorkingMemory.wasCandidateClickedOnScreen(it, screenHash)
        }
        if (contentCandidates.isNotEmpty()) {
            val spatialPick: Pair<Stage2ConvergedNode, String>? = when {
                userGoal.contains("最下面那個") || userGoal.contains("最後一個") || userGoal.contains("最後一封") || userGoal.contains("最底下那個") -> {
                    contentCandidates.maxByOrNull { it.gridTapPoint.y }?.let { it to "最下方項目" }
                }
                userGoal.contains("最左邊那個") || userGoal.contains("左邊那個") -> {
                    contentCandidates.minByOrNull { it.gridTapPoint.x }?.let { it to "最左側項目" }
                }
                userGoal.contains("最右邊那個") || userGoal.contains("右邊那個") -> {
                    contentCandidates.maxByOrNull { it.gridTapPoint.x }?.let { it to "最右側項目" }
                }
                else -> null
            }
            if (spatialPick != null) {
                return CognitiveDiagnosis(
                    screenState = screenState,
                    overrideTargetNode = spatialPick.first,
                    overrideActionType = AiActionType.TAP,
                    overrideConfidence = 0.95f,
                    reasoningTraceNote = "[空間方位邏輯消解] 依據方位「${spatialPick.second}」鎖定 #${spatialPick.first.shortMarkId}「${spatialPick.first.label}」"
                )
            }
        }

        // =========================================================================
        // 3. 【ReAct 前置條件邏輯推理】：要點「結帳/購買/確定」前，若處於「請選擇規格/尺寸/顏色」彈窗，先選規格！
        // =========================================================================
        val wantsCheckoutOrConfirm = CHECKOUT_FINAL_WORDS.any { combinedGoal.contains(it) }
        if (screenState == ScreenSemanticState.SPEC_OR_OPTION_MODAL && wantsCheckoutOrConfirm) {
            val hasAlreadySelectedOption = nodes.any {
                it.inlineStatePrefix.contains("[已選]") || it.inlineStatePrefix.contains("[勾選]")
            }
            if (!hasAlreadySelectedOption) {
                // 尋找規格選項按鈕（排除關閉鈕、數量加減與底部確定鈕）
                val specOptionNode = nodes.firstOrNull { node ->
                    val l = node.label.trim().lowercase()
                    node.shortTypeTag == "BTN" &&
                        node.gridTapPoint.y in 250..780 &&
                        l.isNotEmpty() &&
                        l !in SPEC_MODAL_HINTS &&
                        CHECKOUT_FINAL_WORDS.none { l.contains(it) } &&
                        l != "關閉" && l != "x" &&
                        !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)
                }
                if (specOptionNode != null) {
                    return CognitiveDiagnosis(
                        screenState = screenState,
                        overrideTargetNode = specOptionNode,
                        overrideActionType = AiActionType.TAP,
                        overrideConfidence = 0.94f,
                        reasoningTraceNote = "[ReAct 前置條件推理] 偵測到規格選擇彈窗且尚未選取規格，先點選規格項 #${specOptionNode.shortMarkId}「${specOptionNode.label}」再執行結帳確認"
                    )
                }
            }
        }

        // =========================================================================
        // 4. 【自我反思 #1：搜尋無結果自動縮短關鍵字重搜】
        // =========================================================================
        if (screenState == ScreenSemanticState.EMPTY_SEARCH_RESULT) {
            val (rawQuery, _) = com.example.newandroidapp.stability.InAppOperationPlanner.extractSearchQueryAndFollowUp(userGoal)
            val fallbackQuery = simplifyQueryForRetry(rawQuery)
            val topInp = nodes.firstOrNull { it.shortTypeTag == "INP" || it.gridTapPoint.y <= 220 }
            if (fallbackQuery != null && topInp != null && !InAppWorkingMemory.hasExactSubmittedQuery(fallbackQuery)) {
                return CognitiveDiagnosis(
                    screenState = screenState,
                    overrideTargetNode = topInp,
                    overrideActionType = AiActionType.INPUT_TEXT,
                    overrideInputText = fallbackQuery,
                    overrideConfidence = 0.93f,
                    reasoningTraceNote = "[自我反思與動態改道] 偵測到「搜尋無結果」，自動將過長關鍵字「$rawQuery」精簡為核心詞「$fallbackQuery」重新搜尋"
                )
            }
        }

        // =========================================================================
        // 5. 【自我反思 #2：同行關聯控制項替代點擊 (Sibling Row Fallback) + 底部導覽防誤觸】
        // =========================================================================
        val isExplicitTabSwitch = combinedGoal.contains("首頁") || combinedGoal.contains("個人") ||
            combinedGoal.contains("我的") || combinedGoal.contains("購物車") || combinedGoal.contains("分頁")

        for (node in nodes) {
            // (a) 若為畫面最底部的導覽分頁 (y >= 885) 且使用者並非要切換分頁，給予副作用防護扣分
            if (!isExplicitTabSwitch && node.gridTapPoint.y >= 885 && node.shortTypeTag != "INP") {
                scoreDeltas[node.nodeId] = (scoreDeltas[node.nodeId] ?: 0f) - 25f
            }

            // (b) 若同行左側文字已被點過但畫面沒變（僅限中段列表區 y > 260，嚴禁套用於頂部搜尋列/瀏覽器工具列），自動提升同行右側尚未點擊的 CHK/BTN 控制項分數！
            if ((node.shortTypeTag == "CHK" || node.shortTypeTag == "BTN") &&
                node.gridTapPoint.y > 260 &&
                !com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node) &&
                !com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenBrowserToolbarNode(node, userGoal) &&
                !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)
            ) {
                val clickedSameRowSibling = nodes.any { other ->
                    other.nodeId != node.nodeId &&
                        other.shortTypeTag != "INP" &&
                        other.gridTapPoint.y > 260 &&
                        abs(other.gridTapPoint.y - node.gridTapPoint.y) <= 38 &&
                        InAppWorkingMemory.wasCandidateClickedOnScreen(other, screenHash)
                }
                if (clickedSameRowSibling) {
                    scoreDeltas[node.nodeId] = (scoreDeltas[node.nodeId] ?: 0f) + 32f
                }
            }
        }

        return CognitiveDiagnosis(
            screenState = screenState,
            reasoningTraceNote = "[狀態診斷:${screenState.label}]",
            candidateScoreDeltas = scoreDeltas
        )
    }

    fun classifyScreenState(nodes: List<Stage2ConvergedNode>): ScreenSemanticState {
        if (nodes.isEmpty()) return ScreenSemanticState.GENERAL_PAGE
        val allTextLower = nodes.joinToString(" ") { "${it.inlineStatePrefix}${it.label}" }.lowercase()

        if (EMPTY_RESULT_HINTS.any { allTextLower.contains(it) }) {
            return ScreenSemanticState.EMPTY_SEARCH_RESULT
        }
        if (SPEC_MODAL_HINTS.any { allTextLower.contains(it) } && CHECKOUT_FINAL_WORDS.any { allTextLower.contains(it) }) {
            return ScreenSemanticState.SPEC_OR_OPTION_MODAL
        }
        val chkCount = nodes.count { it.shortTypeTag == "CHK" }
        if (chkCount >= 2) {
            return ScreenSemanticState.SETTINGS_CONTROL_PAGE
        }
        val hasInput = nodes.any { it.shortTypeTag == "INP" }
        if (hasInput && nodes.size <= 8) {
            return ScreenSemanticState.SEARCH_INPUT_PAGE
        }
        if (nodes.size >= 5) {
            return ScreenSemanticState.RESULT_OR_CONTENT_LIST
        }
        return ScreenSemanticState.GENERAL_PAGE
    }

    fun extractPriceValue(text: String): Double? {
        val m = PRICE_REGEX.find(text) ?: return null
        val rawNum = (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(",", "").trim()
        return rawNum.toDoubleOrNull()
    }

    private fun simplifyQueryForRetry(rawQuery: String): String? {
        val clean = rawQuery.trim()
        if (clean.length < 4) return null
        // 移除常見修飾形容詞，保留核心名詞（或取最後 2~3 個核心字元）
        val stripped = clean
            .replace(Regex("(官方|正品|旗艦店|新款|2025|2026|特價|免運|推薦|高音質|超好聽|的)"), "")
            .trim()
        if (stripped.isNotEmpty() && stripped != clean && stripped.length >= 2) {
            return stripped
        }
        return clean.takeLast(2)
    }
}
