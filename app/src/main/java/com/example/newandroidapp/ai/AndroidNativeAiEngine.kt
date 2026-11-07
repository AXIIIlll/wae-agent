package com.example.newandroidapp.ai

import android.content.Context
import android.graphics.Point
import android.os.SystemClock
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLinks
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.stability.HumanColloquialPersonaEngine
import com.example.newandroidapp.stability.SafetyGateManager
import com.example.newandroidapp.vision.NodeSourceType
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport
import kotlin.math.max

/**
 * 本地 AI 引擎一：安卓原生的 AI（Android Native AI - 零預設詞庫，100% 通用結構與語義核）
 *
 * 結合：
 * 1. Android 系統內建 `TextClassificationManager`（Android System Intelligence / ASI 實體提取）
 * 2. 原生確定性元件結構（INP 輸入框 / CHK 開關 / ICO 幾何圖示）與字元 N-Gram 交集評分
 * 3. 原生高風險安全閘門（`C!`）
 */
object AndroidNativeAiEngine {

    fun checkZeroTapQuery(goal: String, report: VisionPipelineReport): String? {
        val q = goal.trim()
        val lower = q.lowercase()
        val ctx = ConversationManager.getTurnContext()

        // 0. 抱怨點錯 / 罵她 / 嫌慢（嗆辣傲嬌短句回嘴，最多 <= 20 字）
        val complaint = HumanColloquialPersonaEngine.detectComplaintOrEmotionalChat(q, ctx)
        if (complaint != null) {
            return complaint.spokenAndDisplayText
        }

        // 1. Math calculation (Zero-Tap)
        val mathMatch = Regex(".*?(\\d+(?:\\.\\d+)?)\\s*([+\\-*/xX]|加|減|乘以?|除以?)\\s*(\\d+(?:\\.\\d+)?).*").find(q)
        if (mathMatch != null) {
            val num1 = mathMatch.groupValues[1].toDoubleOrNull()
            val op = mathMatch.groupValues[2]
            val num2 = mathMatch.groupValues[3].toDoubleOrNull()
            if (num1 != null && num2 != null) {
                val res = when (op) {
                    "+", "加" -> num1 + num2
                    "-", "減" -> num1 - num2
                    "*", "x", "X", "乘", "乘以" -> num1 * num2
                    "/", "除", "除以" -> if (num2 != 0.0) num1 / num2 else null
                    else -> null
                }
                if (res != null) {
                    val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
                    return HumanColloquialPersonaEngine.enforceConciseLength("這也要問？答案是 $formatted。")
                } else if ((op == "/" || op == "除" || op == "除以") && num2 == 0.0) {
                    return "除數不能是零。"
                }
            }
        }

        // 2. Task status inquiry (Zero-Tap)
        if (q.contains("剛剛做到") || q.contains("哪一步") || q.contains("做到了哪") ||
            q.contains("目前進度") || q.contains("現在進度") || q.contains("進度如何") || q.contains("進度")
        ) {
            val lastTask = (ctx.lastSearchQuery ?: ctx.lastAiPrompt).orEmpty().take(6)
            return if (lastTask.isNotBlank()) {
                HumanColloquialPersonaEngine.enforceConciseLength("剛幫你弄完「$lastTask」。")
            } else {
                "現在閒著等你下令。"
            }
        }

        // 3. Screen focus inquiry (Zero-Tap) - 排除「把現在畫面上的重點存到備忘錄/丟給LINE」等跨 App 操作指令
        val hasActionOrTransferVerb = listOf(
            "存到", "記到", "記下來", "寫進", "傳給", "傳到", "丟給", "丟到", "發給", "分享", "貼到", "貼給",
            "打開", "開啟", "去", "到", "然後", "接著"
        ).any { lower.contains(it) }
        if (!hasActionOrTransferVerb && (
                q.contains("畫面") || q.contains("螢幕") || q.contains("這頁") ||
                    q.contains("重點") || q.contains("有哪些選項") || q.contains("畫面上是什麼") || q.contains("這是什麼畫面")
                )
        ) {
            val firstLabel = report.finalNodes.map { it.label.trim() }
                .firstOrNull { it.isNotBlank() && it.length >= 2 && !it.startsWith("圖示:") }
                ?.take(8)
            return if (!firstLabel.isNullOrBlank()) {
                HumanColloquialPersonaEngine.enforceConciseLength("這頁上面有「$firstLabel」。")
            } else {
                "這頁沒什麼特別的東西。"
            }
        }

        // 4. Chit-chat (Zero-Tap)
        if (lower == "你好" || lower == "您好" || lower == "嗨" || lower == "哈囉" || lower == "hello" || lower == "hi" ||
            q.contains("你是誰") || q.contains("自我介紹")
        ) {
            return "有事快說，別浪費時間。"
        }

        // 5. 時間與日期（Zero-Tap）
        if (q.contains("幾點") || q.contains("現在時間") || q.contains("今天幾號") ||
            q.contains("幾月幾號") || q.contains("今天日期") || q.contains("星期幾") || q.contains("禮拜幾")
        ) {
            val timeRes = LocalKnowledgeAndReasoningEngine.evaluateConversationalThinking(q, null)
            if (timeRes != null) return timeRes.replyText
        }

        return null
    }

    /**
     * 極短句（正常 ~10 字、最多 <= 20 字）、零語助詞直率真人口語對話回覆生成器：
     * 當使用者按下「發送」（對話模式）時呼叫，保證 100% 像真人回話、零專業詞彙，
     * 且遇到知識問答（如「誰是蔣介石」）會直接回答，並預先規劃好「打開 Chrome 搜尋 蔣介石」作為待執行指令。
     *
     * @return Pair<AI 回覆文字, 可選的待執行指令 (若非 null 則在 AI 氣泡下方顯示「執行」按鈕)>
     */
    fun generateConversationalReply(
        userText: String,
        report: VisionPipelineReport?,
        context: Context? = null
    ): Pair<String, String?> {
        val q = userText.trim()
        if (q.isEmpty()) {
            return Pair("你不講話，我怎麼知道要幹嘛。", null)
        }
        val lower = q.lowercase()
        val turnCtx = ConversationManager.getTurnContext()

        // 0. 抱怨做錯 / 罵她 / 嫌慢（直率嗆辣回嘴，正常 ~12 字、上限 20 字、零語助詞）
        val complaint = HumanColloquialPersonaEngine.detectComplaintOrEmotionalChat(q, turnCtx)
        if (complaint != null) {
            val plannedRemedial = complaint.suggestedRemedialCommand?.let {
                LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal(it, context, report)
            }
            return Pair(complaint.spokenAndDisplayText, plannedRemedial)
        }

        // 1. 安全閘門確認/取消指令
        val safetyCheck = SafetyGateManager.checkSafetyConfirmation(q, turnCtx)
        when (safetyCheck) {
            is SafetyGateManager.SafetyConfirmationResult.Confirmed -> {
                return Pair("知道了，現在幫你按下去。", "確認執行")
            }
            is SafetyGateManager.SafetyConfirmationResult.Cancelled -> {
                SafetyGateManager.clearPendingAction()
                return Pair("知道了，已經幫你取消。", null)
            }
            is SafetyGateManager.SafetyConfirmationResult.NoPendingAction -> {
                return Pair(HumanColloquialPersonaEngine.enforceConciseLength(safetyCheck.message), null)
            }
            SafetyGateManager.SafetyConfirmationResult.NotApplicable -> {}
        }

        // 2. 算術計算
        val mathMatch = Regex(".*?(\\d+(?:\\.\\d+)?)\\s*([+\\-*/xX]|加|減|乘以?|除以?)\\s*(\\d+(?:\\.\\d+)?).*").find(q)
        if (mathMatch != null) {
            val num1 = mathMatch.groupValues[1].toDoubleOrNull()
            val op = mathMatch.groupValues[2]
            val num2 = mathMatch.groupValues[3].toDoubleOrNull()
            if (num1 != null && num2 != null) {
                val res = when (op) {
                    "+", "加" -> num1 + num2
                    "-", "減" -> num1 - num2
                    "*", "x", "X", "乘", "乘以" -> num1 * num2
                    "/", "除", "除以" -> if (num2 != 0.0) num1 / num2 else null
                    else -> null
                }
                if (res != null) {
                    val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
                    return Pair(HumanColloquialPersonaEngine.enforceConciseLength("這也要問？答案是 $formatted。"), null)
                } else if ((op == "/" || op == "除" || op == "除以") && num2 == 0.0) {
                    return Pair("除數不能是零。", null)
                }
            }
        }

        // 3. 進度與狀態詢問
        if (q.contains("剛剛做到") || q.contains("哪一步") || q.contains("做到了哪") ||
            q.contains("目前進度") || q.contains("現在進度") || q.contains("進度如何") || q.contains("進度")
        ) {
            val lastTask = (turnCtx.lastSearchQuery ?: turnCtx.lastAiPrompt).orEmpty().take(6)
            val reply = if (lastTask.isNotBlank()) "剛幫你弄完「$lastTask」。" else "現在閒著等你下令。"
            return Pair(HumanColloquialPersonaEngine.enforceConciseLength(reply), null)
        }

        // 4. 畫面重點與螢幕內容詢問
        if (q.contains("畫面") || q.contains("螢幕") || q.contains("這頁") ||
            q.contains("重點") || q.contains("有哪些選項") || q.contains("畫面上是什麼") ||
            q.contains("這是什麼畫面") || q.contains("朗讀")
        ) {
            if (report == null || report.finalNodes.isEmpty()) {
                return Pair("先開懸浮球我才看得到畫面。", "朗讀當前畫面")
            }
            val ans = checkZeroTapQuery(q, report) ?: "這頁看起來沒什麼東西。"
            return Pair(HumanColloquialPersonaEngine.enforceConciseLength(ans), null)
        }

        // 5. 問候、自我介紹、感謝與日常對話
        if (lower == "你好" || lower == "您好" || lower == "嗨" || lower == "哈囉" || lower == "hello" || lower == "hi" ||
            q.contains("你是誰") || q.contains("自我介紹") || q.contains("你能做什麼") || q.contains("可以幫我做什麼") ||
            q.contains("怎麼用") || q.contains("功能")
        ) {
            return Pair("我是你手機幫手，有事快說。", null)
        }

        if (q.contains("謝謝") || q.contains("感謝") || lower == "thanks" || lower == "thank you") {
            return Pair("不用謝，小事而已。", null)
        }

        // 6. 本機知識問答、時間日期推理、日常情緒思考與疑問句自動規劃（如：「誰是蔣介石」）
        val knowledgeRes = LocalKnowledgeAndReasoningEngine.evaluateConversationalThinking(q, context)
        if (knowledgeRes != null) {
            return Pair(knowledgeRes.replyText, knowledgeRes.plannedExecutableCommand)
        }

        // 6.5 若屬於 Android 原生系統指令（如設定鬧鐘、計時器、音量調整、手電筒、系統設定子頁直達）
        if (com.example.newandroidapp.action.AndroidNativeSystemActionExecutor.parseCommand(q) != null) {
            return Pair(
                HumanColloquialPersonaEngine.enforceConciseLength("知道了，按執行我就幫你用。"),
                q
            )
        }

        // 7. 判斷是否為單一手機操作指令（包含「太亮了」、「我想聽晴天」、「點第一個」等口語指令）
        val colloquialPlanned = CognitiveReasoningEngine.resolveColloquialAndContextualGoal(q)
        val isActionCommand = colloquialPlanned.normalizedGoal != q || Regex(
            "(打開|開啟|啟動|進入|去|點選|點擊|點一下|按一下|按下|搜尋|搜索|查找|輸入|播放|觀看|往下滑|向下滑|往上滑|向上滑|下滑|上滑|往左滑|向左滑|往右滑|向右滑|返回|上一頁|關閉|開關|設定|設定頁|第[1-5一二三四五]個|youtube|line|chrome|maps|地圖|gmail|計算機|相機|電話|簡訊|音樂|spotify|netflix|instagram|facebook|threads|蝦皮|shopee|momo)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(q)

        val fullyPlannedCmd = LocalKnowledgeAndReasoningEngine.ensurePlannedExecutableGoal(q, context, report)

        if (isActionCommand) {
            return Pair(
                HumanColloquialPersonaEngine.enforceConciseLength("知道了，按執行我就幫你用。"),
                fullyPlannedCmd
            )
        }

        // 9. 其他開放式名詞或未知查詢：預先規劃好「打開 Chrome 搜尋 <關鍵字>」
        return Pair(
            HumanColloquialPersonaEngine.enforceConciseLength("這要上網查，按執行幫你搜。"),
            fullyPlannedCmd
        )
    }

    fun checkCandidateFollowUp(goal: String): Int? {
        val q = goal.trim()
        if (q.isEmpty() || q.length > 10) return null
        val lower = q.lowercase()
        if (q == "好" || q == "好的" || q == "好啊" || q == "好喔" || q == "行" || q == "可以" ||
            q == "就這個" || q == "就這一個" || lower == "ok" || lower == "yes"
        ) {
            return 1
        }
        // 排除包含跨 App、查詢或一般語意詞彙的句子（避免「查一下」、「一個人」、「一起」誤觸發候選編號）
        val nonCandidateKeywords = listOf(
            "去", "到", "打開", "開啟", "查", "搜", "找", "然後", "接著", "把",
            "傳", "丟", "發", "寫", "記", "個人", "一下", "一天", "一起", "一邊", "一般", "一直"
        )
        if (nonCandidateKeywords.any { lower.contains(it) }) {
            return null
        }

        val strictMatch = Regex(
            "^(?:請幫我|幫我|我要|就)?(?:選|點擊|點選|點|選擇|看|聽|開|要|按)?\\s*(?:第\\s*([1-5一二三四五])\\s*(?:個|項|條|首|種|支|部|筆|名)?|([1-5一二三四五])\\s*(?:個|項|條|首|種|支|部|筆))$|^(?:選|點擊|點選|點|選擇|要|按)?\\s*([1-5一二三四五])$"
        ).matchEntire(q) ?: return null

        val raw = strictMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return when (raw) {
            "1", "一" -> 1
            "2", "二" -> 2
            "3", "三" -> 3
            "4", "四" -> 4
            "5", "五" -> 5
            else -> null
        }
    }

    private fun safeElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    fun decide(
        userGoal: String,
        report: VisionPipelineReport,
        context: Context? = null
    ): AiActionDecision {
        val startMs = safeElapsedRealtime()
        val cleanGoal = userGoal.trim()
        val lowerGoal = cleanGoal.lowercase()

        // 檢查安全閘門二次確認（「確認執行」或「取消」）
        val turnCtx = ConversationManager.getTurnContext()
        val safetyCheck = SafetyGateManager.checkSafetyConfirmation(cleanGoal, turnCtx)
        when (safetyCheck) {
            is SafetyGateManager.SafetyConfirmationResult.Confirmed -> {
                SafetyGateManager.clearPendingAction()
                val pending = safetyCheck.pendingAction
                val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                return AiActionDecision(
                    engineName = "Android 原生 AI (安全閘門二次確認)",
                    actionType = AiActionType.TAP,
                    targetNode = null,
                    targetMarkId = pending.targetMarkId,
                    tapPixel = Point(pending.tapPixelX, pending.tapPixelY).apply { x = pending.tapPixelX; y = pending.tapPixelY },
                    tapGrid0to999 = Point(pending.gridX, pending.gridY).apply { x = pending.gridX; y = pending.gridY },
                    compactOpcode = "TAP(CONFIRMED_#${pending.targetMarkId ?: 0})",
                    confidence = 0.99f,
                    latencyMs = elapsed,
                    reasoningTrace = "安全閘門二次確認通過：確認執行「${pending.label}」@${pending.tapPixelX},${pending.tapPixelY}"
                )
            }
            is SafetyGateManager.SafetyConfirmationResult.Cancelled -> {
                SafetyGateManager.clearPendingAction()
                val pending = safetyCheck.pendingAction
                val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                val cancelTrace = "已取消高風險操作「${pending.label}」，安全閘門待確認動作已清除。"
                return AiActionDecision(
                    engineName = "Android 原生 AI (安全閘門取消)",
                    actionType = AiActionType.ANSWER_QUESTION,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    inputTextValue = cancelTrace,
                    compactOpcode = "CANCEL_SAFE",
                    confidence = 0.99f,
                    latencyMs = elapsed,
                    reasoningTrace = cancelTrace
                )
            }
            is SafetyGateManager.SafetyConfirmationResult.NoPendingAction -> {
                val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                return AiActionDecision(
                    engineName = "Android 原生 AI (安全閘門狀態)",
                    actionType = AiActionType.ANSWER_QUESTION,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    inputTextValue = safetyCheck.message,
                    compactOpcode = "ANS",
                    confidence = 0.99f,
                    latencyMs = elapsed,
                    reasoningTrace = safetyCheck.message
                )
            }
            SafetyGateManager.SafetyConfirmationResult.NotApplicable -> {
                // 繼續後續正常管線
            }
        }

        val smpIntentResult = OperationalIntentIndex.classify(cleanGoal, context)
        if (smpIntentResult != null && smpIntentResult.isChitchat && smpIntentResult.score >= 0.80f) {
            val hasActionVerb = CommandLexiconEngine.analyzeClauseActionAndTarget(cleanGoal, null, null, context) != null
            if (!hasActionVerb) {
                val chitchatAns = checkZeroTapQuery(cleanGoal, report)
                    ?: "收到您的訊息「$cleanGoal」。請問需要我幫您操作哪個 App 或執行什麼動作嗎？"
                val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                return AiActionDecision(
                    engineName = "Android 原生 AI (SMP-Chitchat)",
                    actionType = AiActionType.ANSWER_QUESTION,
                    targetNode = null,
                    targetMarkId = null,
                    tapPixel = null,
                    tapGrid0to999 = null,
                    inputTextValue = chitchatAns,
                    compactOpcode = "ANS",
                    confidence = smpIntentResult.score,
                    latencyMs = elapsed,
                    reasoningTrace = "[SMP 閒聊意圖識別 (${smpIntentResult.domain}/${smpIntentResult.actionCategory}, score=${"%.2f".format(smpIntentResult.score)})] $chitchatAns"
                )
            }
        }
        val smpDomainHintPrefix = if (smpIntentResult != null && !smpIntentResult.isChitchat) {
            "[SMP領域提示:${smpIntentResult.domain}/${smpIntentResult.actionCategory}] "
        } else ""

        val zeroTapAns = checkZeroTapQuery(cleanGoal, report)
        if (zeroTapAns != null) {
            val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "Android 原生 AI (Zero-Tap)",
                actionType = AiActionType.ANSWER_QUESTION,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                inputTextValue = zeroTapAns,
                compactOpcode = "ANS",
                confidence = 0.99f,
                latencyMs = elapsed,
                reasoningTrace = "${smpDomainHintPrefix}零手勢即時問答回覆：$zeroTapAns"
            )
        }

        val followUpCandidateIdx = checkCandidateFollowUp(cleanGoal)
        if (followUpCandidateIdx != null) {
            val candidateItem = turnCtx.lastCandidates.find { it.index == followUpCandidateIdx }
            if (candidateItem != null) {
                val matchedNode = report.finalNodes.find { node ->
                    candidateItem.targetNodeId?.let { it == node.nodeId } == true ||
                        (candidateItem.label.isNotBlank() && node.label.contains(candidateItem.label)) ||
                        (candidateItem.boundsInScreen != null && node.convergedBounds.contains(candidateItem.boundsInScreen.centerX(), candidateItem.boundsInScreen.centerY()))
                }
                if (matchedNode != null) {
                    val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
                    return AiActionDecision(
                        engineName = "Android 原生 AI (多輪追問快篩)",
                        actionType = AiActionType.TAP,
                        targetNode = matchedNode,
                        targetMarkId = matchedNode.shortMarkId,
                        tapPixel = matchedNode.finalTapPx,
                        tapGrid0to999 = matchedNode.gridTapPoint,
                        compactOpcode = "TAP(#${matchedNode.shortMarkId}) @${matchedNode.gridTapPoint.x},${matchedNode.gridTapPoint.y}",
                        confidence = 0.95f,
                        latencyMs = elapsed,
                        reasoningTrace = "依據多輪對話候選清單第 $followUpCandidateIdx 項「${candidateItem.label}」精準定位點擊",
                        topCandidates = listOf(matchedNode to 0.95f)
                    )
                }
            }
        }

        val asiEntities = extractEntitiesViaAndroidTextClassifier(cleanGoal, context)

        if (lowerGoal == "返回" || lowerGoal == "上一頁" || lowerGoal == "back") {
            val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.NAVIGATE_BACK,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "KB",
                confidence = 0.98f,
                latencyMs = elapsed,
                reasoningTrace = "原生指令解析為系統返回鍵 (KB)"
            )
        }

        if (lowerGoal.contains("往下滑") || lowerGoal.contains("向下滑") || lowerGoal == "下滑") {
            val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.SCROLL_DOWN,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SD",
                confidence = 0.96f,
                latencyMs = elapsed,
                reasoningTrace = "原生指令解析為向下捲動列表 (SD)"
            )
        }

        if (lowerGoal.contains("往上滑") || lowerGoal.contains("向上滑") || lowerGoal == "上滑") {
            val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.SCROLL_UP,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SU",
                confidence = 0.96f,
                latencyMs = elapsed,
                reasoningTrace = "原生指令解析為向上捲動列表 (SU)"
            )
        }

        if (lowerGoal.contains("往左滑") || lowerGoal.contains("向左滑") || lowerGoal.contains("左滑") || lowerGoal.contains("左翻") || lowerGoal == "swipe left") {
            val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.SWIPE_LEFT,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SL",
                confidence = 0.96f,
                latencyMs = elapsed,
                reasoningTrace = "原生指令解析為向左滑動手勢 (SL)"
            )
        }

        if (lowerGoal.contains("往右滑") || lowerGoal.contains("向右滑") || lowerGoal.contains("右滑") || lowerGoal.contains("右翻") || lowerGoal == "swipe right") {
            val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.SWIPE_RIGHT,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SR",
                confidence = 0.96f,
                latencyMs = elapsed,
                reasoningTrace = "原生指令解析為向右滑動手勢 (SR)"
            )
        }

        val extractedInputText = extractInputPayload(cleanGoal)
        val isInputIntent = extractedInputText != null ||
            lowerGoal.startsWith("輸入") ||
            lowerGoal.startsWith("搜尋") ||
            lowerGoal.startsWith("搜索") ||
            lowerGoal.contains("輸入框")

        val isLongPressIntent = lowerGoal.contains("長按") || lowerGoal.contains("按住") || lowerGoal.contains("long press")
        val isDoubleTapIntent = lowerGoal.contains("雙擊") || lowerGoal.contains("連按兩下") || lowerGoal.contains("點兩下") || lowerGoal.contains("連點兩下") || lowerGoal.contains("按兩次") || lowerGoal.contains("double tap")

        val cleanTargetKeyword = cleanGoal
            .replace(Regex("^(請幫我|幫我|請|長按|按住|雙擊|連按兩下|點兩下|連點兩下|按兩次|點擊|點選|點一下|按一下|按下|打開|開啟|進入|選擇|找到|去|在)"), "")
            .trim()
            .ifEmpty { cleanGoal }

        val scored = ArrayList<Pair<Stage2ConvergedNode, Float>>(report.finalNodes.size)
        for (node in report.finalNodes) {
            val score = scoreNodeStructureAndLexicon(
                node = node,
                rawGoal = cleanGoal,
                targetKeyword = cleanTargetKeyword,
                isInputIntent = isInputIntent,
                asiEntities = asiEntities
            )
            scored.add(node to score)
        }

        val canonicalScored = DeterminismHardeningEngine.sortScoredCandidatesCanonically(scored)
        val topCandidates = canonicalScored.take(6)
        val bestPair = topCandidates.firstOrNull()

        val elapsed = (safeElapsedRealtime() - startMs).coerceAtLeast(1L)

        if (bestPair == null || bestPair.second <= 0.10f) {
            if (isLongPressIntent) {
                val candidate = report.finalNodes.firstOrNull()
                return AiActionDecision(
                    engineName = "Android 原生 AI (ASI)",
                    actionType = AiActionType.LONG_PRESS,
                    targetNode = candidate,
                    targetMarkId = candidate?.shortMarkId,
                    tapPixel = candidate?.finalTapPx,
                    tapGrid0to999 = candidate?.gridTapPoint,
                    compactOpcode = if (candidate != null) "LP(#${candidate.shortMarkId}) @${candidate.gridTapPoint.x},${candidate.gridTapPoint.y}" else "LP",
                    confidence = 0.75f,
                    latencyMs = elapsed,
                    reasoningTrace = "原生指令解析為長按操作 (LP)",
                    topCandidates = topCandidates
                )
            }
            if (isDoubleTapIntent) {
                val candidate = report.finalNodes.firstOrNull()
                return AiActionDecision(
                    engineName = "Android 原生 AI (ASI)",
                    actionType = AiActionType.DOUBLE_TAP,
                    targetNode = candidate,
                    targetMarkId = candidate?.shortMarkId,
                    tapPixel = candidate?.finalTapPx,
                    tapGrid0to999 = candidate?.gridTapPoint,
                    compactOpcode = if (candidate != null) "DT(#${candidate.shortMarkId}) @${candidate.gridTapPoint.x},${candidate.gridTapPoint.y}" else "DT",
                    confidence = 0.75f,
                    latencyMs = elapsed,
                    reasoningTrace = "原生指令解析為雙擊操作 (DT)",
                    topCandidates = topCandidates
                )
            }
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.SCROLL_DOWN,
                targetNode = null,
                targetMarkId = null,
                tapPixel = null,
                tapGrid0to999 = null,
                compactOpcode = "SD",
                confidence = 0.40f,
                latencyMs = elapsed,
                reasoningTrace = "當前畫面無直接字面匹配元件，建議交由 Needle 3 向量檢索或向下捲動 (SD)",
                topCandidates = topCandidates
            )
        }

        val bestNode = bestPair.first
        val bestConf = bestPair.second.coerceIn(0.10f, 0.99f)

        val isSensitive = SafetyGateManager.isHighRiskAction(bestNode.label, lowerGoal)
        if (isSensitive) {
            SafetyGateManager.recordPendingAction(
                node = bestNode,
                targetMarkId = bestNode.shortMarkId,
                tapPx = bestNode.finalTapPx,
                gridPt = bestNode.gridTapPoint,
                opcode = "C! -> #${bestNode.shortMarkId} @${bestNode.gridTapPoint.x},${bestNode.gridTapPoint.y}",
                packageName = null
            )
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.CONFIRM_HIGH_RISK,
                targetNode = bestNode,
                targetMarkId = bestNode.shortMarkId,
                tapPixel = bestNode.finalTapPx,
                tapGrid0to999 = bestNode.gridTapPoint,
                compactOpcode = "C! -> #${bestNode.shortMarkId}",
                confidence = bestConf,
                latencyMs = elapsed,
                reasoningTrace = "觸發原生高風險安全閘門 (C!)：目標「${bestNode.label}」涉及敏感操作，需確認後執行",
                topCandidates = topCandidates
            )
        }

        if ((bestNode.shortTypeTag == "INP" || isInputIntent) && !extractedInputText.isNullOrBlank()) {
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.INPUT_TEXT,
                targetNode = bestNode,
                targetMarkId = bestNode.shortMarkId,
                tapPixel = bestNode.finalTapPx,
                tapGrid0to999 = bestNode.gridTapPoint,
                inputTextValue = extractedInputText,
                compactOpcode = "INPUT(#${bestNode.shortMarkId}, \"$extractedInputText\") @${bestNode.gridTapPoint.x},${bestNode.gridTapPoint.y}",
                confidence = bestConf,
                latencyMs = elapsed,
                reasoningTrace = "命中輸入目標 #${bestNode.shortMarkId}「${bestNode.inlineStatePrefix}${bestNode.label}」，自動填入「$extractedInputText」",
                topCandidates = topCandidates
            )
        }

        if (isLongPressIntent) {
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.LONG_PRESS,
                targetNode = bestNode,
                targetMarkId = bestNode.shortMarkId,
                tapPixel = bestNode.finalTapPx,
                tapGrid0to999 = bestNode.gridTapPoint,
                compactOpcode = "LP(#${bestNode.shortMarkId}) @${bestNode.gridTapPoint.x},${bestNode.gridTapPoint.y}",
                confidence = bestConf,
                latencyMs = elapsed,
                reasoningTrace = "原生結構語義長按命中 #${bestNode.shortMarkId}「${bestNode.label}」",
                topCandidates = topCandidates
            )
        }

        if (isDoubleTapIntent) {
            return AiActionDecision(
                engineName = "Android 原生 AI (ASI)",
                actionType = AiActionType.DOUBLE_TAP,
                targetNode = bestNode,
                targetMarkId = bestNode.shortMarkId,
                tapPixel = bestNode.finalTapPx,
                tapGrid0to999 = bestNode.gridTapPoint,
                compactOpcode = "DT(#${bestNode.shortMarkId}) @${bestNode.gridTapPoint.x},${bestNode.gridTapPoint.y}",
                confidence = bestConf,
                latencyMs = elapsed,
                reasoningTrace = "原生結構語義雙擊命中 #${bestNode.shortMarkId}「${bestNode.label}」",
                topCandidates = topCandidates
            )
        }

        return AiActionDecision(
            engineName = "Android 原生 AI (ASI)",
            actionType = AiActionType.TAP,
            targetNode = bestNode,
            targetMarkId = bestNode.shortMarkId,
            tapPixel = bestNode.finalTapPx,
            tapGrid0to999 = bestNode.gridTapPoint,
            compactOpcode = "TAP(#${bestNode.shortMarkId}) @${bestNode.gridTapPoint.x},${bestNode.gridTapPoint.y}",
            confidence = bestConf,
            latencyMs = elapsed,
            reasoningTrace = "${smpDomainHintPrefix}原生結構語義命中 #${bestNode.shortMarkId}「${bestNode.label}」(來源:${bestNode.sourceType.tag}, 露出率:${(bestNode.visibleRatio * 100).toInt()}%)",
            topCandidates = topCandidates
        )
    }

    private fun scoreNodeStructureAndLexicon(
        node: Stage2ConvergedNode,
        rawGoal: String,
        targetKeyword: String,
        isInputIntent: Boolean,
        asiEntities: List<String>
    ): Float {
        // 【最高優先級鐵律禁令：嚴禁 AI 點擊「語音輸入／語音搜尋／麥克風／智慧鏡頭」】
        if (com.example.newandroidapp.stability.InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) {
            return -1.0f
        }

        val fullLabel = "${node.inlineStatePrefix}${node.label}".lowercase()
        val cleanLabel = node.label.lowercase()
        val kw = targetKeyword.lowercase()
        val goalLower = rawGoal.lowercase()

        var score = 0f

        val idMatch = Regex("#(\\d+)").find(goalLower)
        if (idMatch != null) {
            val wantedId = idMatch.groupValues[1].toIntOrNull()
            if (wantedId == node.shortMarkId) return 0.99f
        }

        if (cleanLabel.isNotEmpty() && (cleanLabel == kw || fullLabel == kw)) {
            score += 0.92f
        } else if (cleanLabel.length >= 2 && (cleanLabel.contains(kw) || kw.contains(cleanLabel))) {
            score += 0.78f
        } else if (cleanLabel.length >= 2 && kw.length >= 2) {
            val syns = LookaheadPlannerEngine.expandTargetSynonyms(kw)
            if (syns.any { syn -> syn.length >= 2 && (cleanLabel == syn || cleanLabel.contains(syn)) }) {
                score += 0.74f
            }
        }

        val charOverlap = computeCharOverlapScore(kw, cleanLabel)
        score += charOverlap * 0.45f

        // 當非輸入意圖 (!isInputIntent) 時，嚴禁給予 INP 輸入框高分，避免點擊目標時誤點搜尋欄
        if (!isInputIntent && node.shortTypeTag == "INP") {
            return 0.02f
        }

        // 通用 UI 結構屬性加權（輸入框、搜尋入口、開關）
        if (isInputIntent) {
            if (node.shortTypeTag == "INP") {
                score += 0.72f
            } else if (fullLabel.contains("搜尋") || fullLabel.contains("search") || fullLabel.contains("查找")) {
                score += 0.58f
            }
        }

        if ((goalLower.contains("開關") || goalLower.contains("開啟") || goalLower.contains("關閉")) &&
            node.shortTypeTag == "CHK"
        ) {
            score += 0.35f
        }

        for (ent in asiEntities) {
            if (ent.isNotBlank() && cleanLabel.contains(ent.lowercase())) {
                score += 0.25f
            }
        }

        if (node.sourceType == NodeSourceType.ROI_OCR_TEXT || node.sourceType == NodeSourceType.GEOMETRIC_ICON_CLASSIFIED) {
            score += 0.03f
        }

        return score.coerceAtMost(0.99f)
    }

    private fun computeCharOverlapScore(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val setA = a.filter { !it.isWhitespace() }.toSet()
        val setB = b.filter { !it.isWhitespace() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val interCount = setA.intersect(setB).size
        // 當目標詞長度 >= 2 時，若僅有 1 個單字巧合重疊（例如「行車路線」與「信義路」或「叫車」僅重疊 1 字），不計重疊分以防止亂點無關按鈕
        if (setA.size >= 2 && interCount < 2) return 0f
        return interCount.toFloat() / max(1, maxOf(setA.size, minOf(setB.size, 4))).toFloat()
    }

    fun extractInputPayload(goal: String): String? {
        val m1 = Regex("(?:輸入|搜尋|搜索|查找|打字|傳送|發送)[:：\\s\"「『]?([^\"」』，,]+)[」』\"]?").find(goal.trim())
        if (m1 != null) {
            val v = m1.groupValues[1].trim()
            if (v.isNotEmpty() && v != "框" && v != "輸入框") return v
        }
        return null
    }

    private val entityClassifierCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
            return size > 64
        }
    }

    private fun extractEntitiesViaAndroidTextClassifier(text: String, context: Context?): List<String> {
        if (context == null || text.isBlank()) return emptyList()
        val key = text.trim()
        synchronized(entityClassifierCache) {
            entityClassifierCache[key]?.let { return it }
        }
        // TextClassifier.generateLinks 為跨行程 Binder IPC 呼叫，嚴禁在主執行緒 (Main Looper) 同步阻塞以免引發 ANR 卡退
        val isOnMainThread = runCatching {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        }.getOrDefault(false)
        if (isOnMainThread) return emptyList()

        val extracted = runCatching {
            val tcm = context.getSystemService(TextClassificationManager::class.java) ?: return emptyList()
            val classifier = tcm.textClassifier
            val request = TextLinks.Request.Builder(key).build()
            val links = classifier.generateLinks(request)
            links.links.map { link -> key.substring(link.start, link.end) }
        }.getOrDefault(emptyList())
        synchronized(entityClassifierCache) {
            entityClassifierCache[key] = extracted
        }
        return extracted
    }
}
