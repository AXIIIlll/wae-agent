package com.example.newandroidapp.ai

import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.Stage2ConvergedNode
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 3 步前瞻推演與跨介面深度優先回溯引擎（LookaheadPlannerEngine）
 *
 * 借鑑西洋棋 Alpha-Beta / Expectiminimax 前瞻搜尋與深度優先回溯（DFS Backtracking）精神，
 * 專為手機跨介面未知環境（Incomplete Information UI）設計的四階段決策引擎：
 *
 * 1. 【階段一：搜尋優先捷徑 (Search-First Shortcut)】
 *    - 當目標按鈕不在當前畫面，但畫面頂部有純文字「搜尋設定 / 放大鏡」入口時（嚴禁語音輸入），
 *      直接推演 `點搜尋框 -> 輸入目標詞 -> 點結果` 的期望值最高（`+88f`），免去層層盲猜子選單。
 * 2. 【階段二：新介面同義詞與概念展開 (Synonym & Concept Expansion)】
 *    - 進入新介面後，不同廠牌/App 對同一功能的命名不同（如「深色模式 <-> 暗色主題 / 夜間模式」、
 *      「熱點 <-> 無線基地台 / 網路共用」），自動展開同義詞集合比對（`+68f`）。
 * 3. 【階段三：跨層級分類期望值前瞻 + 走錯路自動退回改試第 2 名 (DFS Lookahead & Auto-Backtrack)】
 *    - 當畫面無直接目標也無搜尋框時，對畫面上的父分類按鈕計算 3 步前瞻期望值：
 *      \(EV(a) = R_1(a) + \gamma \cdot P(\text{Target} \in S' \mid a) \cdot R_2 + \gamma^2 \cdot R_3\)
 *    - 點進第 1 名父分類 A 後，若首屏 + 下滑 1 次仍無目標（含同義詞），立即觸發 `AUTO_BACKTRACK` 按返回鍵退回上一頁，
 *      將死胡同 A 重罰 `-100f`，自動改點上一頁期望值第 2 名的父分類 B！
 * 4. 【階段四：必經前置動作前瞻 (Precondition Lookahead)】
 *    - 點擊「下一步 / 註冊 / 送出」前，若偵測到未勾選的「我已閱讀並同意條款」核取方塊，前瞻推演先勾選條款以解鎖下一步按鈕。
 */
object LookaheadPlannerEngine {

    data class LookaheadCandidateEval(
        val node: Stage2ConvergedNode,
        val expectedValueBoost: Float,
        val simulatedPathDescription: String,
        val isDirectOrSynonymHit: Boolean = false
    )

    data class LookaheadPlanResult(
        val targetFeatureKeyword: String,
        val expandedSynonyms: Set<String>,
        val hasDirectOrSynonymTargetOnScreen: Boolean,
        val candidateBoosts: Map<String, Float>,
        val topLookaheadPaths: List<LookaheadCandidateEval>,
        val overrideActionType: AiActionType? = null,
        val overrideTargetNode: Stage2ConvergedNode? = null,
        val overrideInputText: String? = null,
        val overrideConfidence: Float = 0.96f,
        val lookaheadReasoningTrace: String = ""
    )

    /**
     * 跨廠牌（Samsung OneUI / Pixel / Xiaomi MIUI / OPPO ColorOS / ASUS）與跨 App 雙向同義詞展開字典
     */
    private val SYNONYM_CLUSTERS: List<Set<String>> = listOf(
        setOf("深色模式", "黑暗模式", "夜間模式", "暗色主題", "深色主題", "深色外觀", "暗黑模式", "dark mode", "dark theme"),
        setOf("護眼模式", "濾藍光", "夜覽", "舒適觀看", "藍光過濾", "自然色彩", "閱讀模式", "eye comfort"),
        setOf("字體大小", "文字大小", "顯示大小", "字型與顯示大小", "字型大小", "字體與顯示大小", "font size"),
        setOf("熱點", "個人熱點", "無線基地台", "網路共用", "行動熱點", "可攜式熱點", "行動無線基地台", "hotspot", "tethering"),
        setOf("定位", "位置資訊", "位置服務", "定位服務", "gps", "資訊位置", "location"),
        setOf("快取", "清除快取", "清除資料", "儲存空間與快取", "垃圾清理", "清除暫存", "clear cache"),
        setOf("自動旋轉", "螢幕自動旋轉", "縱向鎖定", "自動轉向", "旋轉螢幕", "auto rotate"),
        setOf("勿擾", "勿擾模式", "零打擾", "睡眠模式", "專注模式", "do not disturb"),
        setOf("省電", "省電模式", "電池最佳化", "超級省電", "低耗電模式", "battery saver"),
        setOf("指紋", "臉部解鎖", "生物辨識", "螢幕鎖定", "解鎖方式", "密碼與安全", "biometrics"),
        setOf("更新率", "螢幕更新率", "流暢度", "動態流暢度", "120hz", "高更新率", "refresh rate"),
        setOf("開發人員選項", "開發者選項", "usb偵錯", "版本號碼", "developer options"),
        setOf(
            "路線", "規劃路線", "规划路线", "路線規劃", "路线规划", "行車路線", "行车路线",
            "駕車路線", "驾车路线", "開車路線", "开车路线", "騎車路線", "步行路線", "大眾運輸路線",
            "怎麼走", "怎么走", "directions", "查看路線", "路線資訊"
        ),
        setOf("導航", "開始導航", "开始导航", "開始", "出发", "出發", "start", "navigate")
    )

    /**
     * 階層式跨介面知識樹：定義「深層目標特徵詞」與「第 1 順位 / 第 2 順位 / 第 3 順位父分類入口」的條件機率映射
     */
    private data class HierarchicalCategoryRule(
        val targetTriggerKeywords: Set<String>,
        val rank1ParentLabels: Set<String>,
        val rank2ParentLabels: Set<String>,
        val rank3ParentLabels: Set<String> = emptySet()
    )

    private val HIERARCHICAL_CATEGORY_RULES: List<HierarchicalCategoryRule> = listOf(
        // 1. 顯示 / 桌布與樣式 / 無障礙設定 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "深色模式", "黑暗模式", "夜間模式", "暗色主題", "護眼模式", "濾藍光", "夜覽",
                "字體大小", "文字大小", "顯示大小", "字型", "亮度", "自動調整亮度",
                "更新率", "螢幕更新率", "120hz", "休眠", "螢幕逾時", "自動旋轉"
            ),
            rank1ParentLabels = setOf("顯示", "顯示與亮度", "螢幕", "顯示設定", "display"),
            rank2ParentLabels = setOf("桌布與樣式", "個人化", "主題", "外觀與個人化", "桌布與主題", "顯示大小與文字"),
            rank3ParentLabels = setOf("無障礙設定", "無障礙", "進階功能", "系統")
        ),
        // 2. 網路 / 連線 / 行動網路 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "熱點", "個人熱點", "無線基地台", "網路共用", "行動熱點",
                "飛行模式", "飛航模式", "行動數據", "sim卡", "漫遊", "vpn", "私人dns", "dns", "apn"
            ),
            rank1ParentLabels = setOf("網路和網際網路", "連線", "行動網路", "雙卡與行動網路", "網路與連線", "network"),
            rank2ParentLabels = setOf("更多連線設定", "無線與網路", "連線與共用", "網路共用與行動無線基地台"),
            rank3ParentLabels = setOf("系統", "進階設定")
        ),
        // 3. 應用程式 / 儲存空間 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "快取", "清除快取", "清除資料", "儲存空間與快取", "強制停止", "解除安裝", "預設應用程式", "應用程式權限"
            ),
            rank1ParentLabels = setOf("應用程式", "應用管理", "應用程式與通知", "應用程式資訊", "apps"),
            rank2ParentLabels = setOf("儲存空間", "電池與裝置維護", "更多設定", "進階"),
            rank3ParentLabels = setOf("安全性與隱私權", "隱私權", "系統")
        ),
        // 4. 音效與震動 / 通知 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "鈴聲", "震動", "靜音", "媒體音量", "鬧鐘音量", "勿擾", "勿擾模式", "通知音效", "觸控震動"
            ),
            rank1ParentLabels = setOf("音效與震動", "聲音與震動", "音效", "聲音", "sound"),
            rank2ParentLabels = setOf("通知", "通知與狀態列", "應用程式與通知"),
            rank3ParentLabels = setOf("無障礙設定", "系統")
        ),
        // 5. 安全性與隱私 / 定位 / 密碼 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "定位", "位置資訊", "位置服務", "gps", "指紋", "臉部解鎖", "生物辨識", "螢幕鎖定", "修改密碼", "權限管理"
            ),
            rank1ParentLabels = setOf("安全性與隱私權", "生物辨識與安全性", "密碼與安全", "位置", "定位服務", "隱私權"),
            rank2ParentLabels = setOf("鎖定螢幕", "帳號與備份", "安全"),
            rank3ParentLabels = setOf("進階功能", "系統")
        ),
        // 6. 電池 / 省電 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "省電", "省電模式", "電池最佳化", "超級省電", "耗電", "電池健康度", "無線反向充電"
            ),
            rank1ParentLabels = setOf("電池", "電池與效能", "電池與裝置維護", "省電與電池", "battery"),
            rank2ParentLabels = setOf("裝置維護", "系統", "顯示"),
            rank3ParentLabels = setOf("應用程式")
        ),
        // 7. 關於手機 / 開發人員選項 子樹
        HierarchicalCategoryRule(
            targetTriggerKeywords = setOf(
                "開發人員選項", "開發者選項", "usb偵錯", "版本號碼", "系統更新", "軟體更新", "手機名稱", "imei"
            ),
            rank1ParentLabels = setOf("關於手機", "系統", "我的裝置", "軟體資訊", "about phone"),
            rank2ParentLabels = setOf("更多設定", "進階設定", "其他設定"),
            rank3ParentLabels = setOf("一般管理")
        )
    )

    private val AGREEMENT_CHECKBOX_HINTS = listOf(
        "我已閱讀", "同意", "服務條款", "隱私權政策", "使用者協議", "滿18歲", "agree", "terms"
    )

    private val NEXT_SUBMIT_CTA_WORDS = listOf(
        "下一步", "繼續", "註冊", "登入", "提交", "確認送出", "立即開通", "next", "continue", "submit"
    )

    /**
     * 從使用者指令或當前子目標提取純淨的「目標功能/按鈕名稱」
     */
    fun extractCleanTargetFeature(userGoal: String, currentSubGoal: String = ""): String {
        val raw = currentSubGoal.ifBlank { userGoal }.trim()
        if (raw.isEmpty()) return ""
        return raw
            .replace(Regex("^(請幫我|幫我|請|去|到|在|進入|打開|開啟|關閉|關掉|啟用|停用|切換|設定|調整|調成|改成|找到|找一下|查找|查看|點選|點擊|點一下|按一下|按下|點|按)\\s*"), "")
            .replace(Regex("^(設定裡的|設定中的|設定裡面的|手機的|系統的|裡面的|裡的)\\s*"), "")
            .replace(Regex("(?:那個|這個|那顆|這顆|該)?(?:按鈕|按鍵|圖示|圖標|鍵|一下)+$"), "")
            .replace(Regex("(打開|開啟|關閉|關掉|開關|功能|模式|選項)$"), { m ->
                // 保留「深色模式」、「護眼模式」、「勿擾模式」、「省電模式」、「飛行模式」中的「模式」
                val v = m.value
                if (v == "模式" || v == "選項") v else ""
            })
            .trim()
            .ifEmpty { raw }
    }

    /**
     * 【階段二：新介面同義詞展開（整合哈工大同義詞詞林 16,504 組 Synsets + AOSP 四語對齊 UI 庫 + OpenCC 兩岸慣用詞庫）】
     * 輸入目標關鍵字（例如「深色模式」、「設定」、「搜尋」），回傳包含自身、跨廠牌同義詞、繁簡體與英文對照的集合
     */
    fun expandTargetSynonyms(targetPhrase: String): Set<String> {
        val clean = targetPhrase.trim().lowercase()
        if (clean.isEmpty()) return emptySet()

        val result = LinkedHashSet<String>()
        result.add(clean)
        val normTw = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean)
        if (normTw.isNotEmpty()) result.add(normTw)

        for (cluster in SYNONYM_CLUSTERS) {
            val matchedCluster = cluster.any { syn ->
                clean.contains(syn) || normTw.contains(syn) || (syn.length >= 2 && (syn.contains(clean) || syn.contains(normTw)))
            }
            if (matchedCluster) {
                result.addAll(cluster)
            }
        }

        // 接入 GitHub 開源哈工大同義詞詞林擴展版 + AOSP Android 14 四語 UI 詞典 + OpenCC 兩岸慣用詞
        result.addAll(OpenSourceAiDatabaseManager.expandSynonymsFromOpenSourceDatabases(clean, maxSynonyms = 28))
        return result
    }

    /**
     * 檢查當前畫面是否已經存在目標按鈕（含直接字面匹配或同義詞展開匹配）
     */
    fun hasTargetOrSynonymOnScreen(
        report: VisionPipelineReport,
        targetFeature: String,
        synonyms: Set<String> = expandTargetSynonyms(targetFeature)
    ): Boolean {
        if (targetFeature.isBlank() && synonyms.isEmpty()) return false
        for (node in report.finalNodes) {
            if (InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) continue
            val labelLower = node.label.trim().lowercase()
            if (labelLower.isEmpty()) continue
            if (synonyms.any { syn -> syn.length >= 2 && (labelLower.contains(syn) || syn.contains(labelLower)) }) {
                return true
            }
        }
        return false
    }

    /**
     * 核心：執行 3 步前瞻推演（Search-First 捷徑 + 同義詞展開 + 父分類期望值樹搜尋 + 前置動作前瞻）
     */
    fun evaluateLookaheadAndCrossScreenPlan(
        report: VisionPipelineReport,
        userGoal: String,
        currentSubGoal: String = "",
        screenHash: String = "",
        allowSearchFirstShortcut: Boolean = true
    ): LookaheadPlanResult {
        val cleanFeature = extractCleanTargetFeature(userGoal, currentSubGoal)
        val expandedSynonyms = expandTargetSynonyms(cleanFeature)
        val goalLower = userGoal.trim().lowercase()

        val boosts = LinkedHashMap<String, Float>()
        val simulatedPaths = ArrayList<LookaheadCandidateEval>()

        // =========================================================================
        // 0. 【必經前置動作前瞻 (Precondition Lookahead)】
        // 當目標是點擊「下一步 / 繼續 / 註冊 / 提交」，但畫面上存在尚未勾選的「我已閱讀並同意條款」CHK，
        // 3 步前瞻推演：Step 1 勾選同意條款 -> Step 2 解鎖下一步按鈕 -> Step 3 完成提交！
        // =========================================================================
        val wantsNextOrSubmit = NEXT_SUBMIT_CTA_WORDS.any { goalLower.contains(it) || cleanFeature.lowercase().contains(it) }
        if (wantsNextOrSubmit) {
            val uncheckedAgreement = report.finalNodes.firstOrNull { node ->
                !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node) &&
                    !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash) &&
                    !node.inlineStatePrefix.contains("已勾選") &&
                    !node.inlineStatePrefix.contains("開啟") &&
                    (node.shortTypeTag == "CHK" || AGREEMENT_CHECKBOX_HINTS.any { node.label.lowercase().contains(it) }) &&
                    AGREEMENT_CHECKBOX_HINTS.any { "${node.inlineStatePrefix}${node.label}".lowercase().contains(it) }
            }
            if (uncheckedAgreement != null) {
                boosts[uncheckedAgreement.nodeId] = 78f
                val pathDesc = "Step1:勾選「${uncheckedAgreement.label}」 -> Step2:解鎖下一步按鈕 -> Step3:完成目標"
                simulatedPaths.add(
                    LookaheadCandidateEval(
                        node = uncheckedAgreement,
                        expectedValueBoost = 78f,
                        simulatedPathDescription = pathDesc
                    )
                )
                return LookaheadPlanResult(
                    targetFeatureKeyword = cleanFeature,
                    expandedSynonyms = expandedSynonyms,
                    hasDirectOrSynonymTargetOnScreen = false,
                    candidateBoosts = boosts,
                    topLookaheadPaths = simulatedPaths,
                    overrideActionType = AiActionType.TAP,
                    overrideTargetNode = uncheckedAgreement,
                    overrideConfidence = 0.96f,
                    lookaheadReasoningTrace = "[3步前瞻:前置解鎖] 偵測到「${uncheckedAgreement.label}」尚未勾選，前瞻推演 ($pathDesc) 優先點擊勾選"
                )
            }
        }

        // =========================================================================
        // 1. 【階段二：檢查當前畫面是否有「直接目標」或「同義詞展開目標」】
        // 例如使用者找「深色模式」，進入新介面後按鈕名稱叫「暗色主題」或「夜間模式」
        // =========================================================================
        var bestSynonymNode: Stage2ConvergedNode? = null
        var bestSynonymBoost = 0f

        for (node in report.finalNodes) {
            if (InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) continue
            if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) continue
            val labelLower = node.label.trim().lowercase()
            if (labelLower.isEmpty()) continue

            // 先經過 15萬×15萬 動作意圖互斥與反義引擎檢查：若該節點與目標互為同排互斥動作或反義詞，直接排除並給予負向抑制
            if (cleanFeature.isNotEmpty()) {
                val mutexEval = ActionIntentMutualExclusionEngine.evaluateCandidateActionAdjustment(
                    targetPhrase = cleanFeature,
                    candidateLabel = node.label,
                    candidatePredictedRoleCode = node.predictedFunctionRole
                )
                if (mutexEval.scoreDelta <= -45f) {
                    boosts[node.nodeId] = -95f
                    continue
                }
            }

            val cleanNodeLabel = labelLower.replace("圖示:", "").trim()
            val matchedSyn = expandedSynonyms.firstOrNull { syn ->
                syn.length >= 2 && (cleanNodeLabel == syn || cleanNodeLabel.contains(syn) || (cleanNodeLabel.length >= 2 && syn.contains(cleanNodeLabel)))
            }
            if (matchedSyn != null) {
                val isExact = labelLower == cleanFeature.lowercase() || labelLower.contains(cleanFeature.lowercase())
                val boost = if (isExact) 72f else 68f
                boosts[node.nodeId] = (boosts[node.nodeId] ?: 0f) + boost
                val pathDesc = if (isExact) {
                    "Step1:直接命中目標「${node.label}」"
                } else {
                    "Step1:同義詞展開命中「${node.label}」(對應目標:「$cleanFeature」)"
                }
                simulatedPaths.add(
                    LookaheadCandidateEval(
                        node = node,
                        expectedValueBoost = boost,
                        simulatedPathDescription = pathDesc,
                        isDirectOrSynonymHit = true
                    )
                )
                val currentBest = bestSynonymNode
                if (boost > bestSynonymBoost ||
                    (boost == bestSynonymBoost && currentBest != null &&
                        DeterminismHardeningEngine.compareNodesCanonically(node, currentBest) < 0)
                ) {
                    bestSynonymBoost = boost
                    bestSynonymNode = node
                }
            }
        }

        if (bestSynonymNode != null) {
            simulatedPaths.sortWith { a, b ->
                val cmp = b.expectedValueBoost.compareTo(a.expectedValueBoost)
                if (cmp != 0) cmp else DeterminismHardeningEngine.compareNodesCanonically(a.node, b.node)
            }
            return LookaheadPlanResult(
                targetFeatureKeyword = cleanFeature,
                expandedSynonyms = expandedSynonyms,
                hasDirectOrSynonymTargetOnScreen = true,
                candidateBoosts = boosts,
                topLookaheadPaths = simulatedPaths,
                overrideActionType = null, // 交由主決策管線依加權後最高分執行（支援同行 Switch Grouper 等機制）
                overrideTargetNode = bestSynonymNode,
                lookaheadReasoningTrace = "[前瞻同義詞展開] 於當前介面定位到「${bestSynonymNode.label}」(同義對應「$cleanFeature」，期望值 +${bestSynonymBoost.toInt()})"
            )
        }

        // =========================================================================
        // 2. 【階段一：搜尋優先捷徑 (Search-First Shortcut)】
        // 當目標按鈕（含同義詞）不在當前畫面，但畫面頂部有「搜尋設定」入口或目標屬於跨層設定特徵詞時，
        // 3 步前瞻推演：Step 1 點擊/輸入頂部搜尋框「$cleanFeature」 -> Step 2 直接跳出目標結果 -> Step 3 點擊完成！
        // （嚴格遵守鐵律：100% 排除語音輸入與麥克風；一般頁面找按鍵優先透過 SCROLL_DOWN 滑動尋找，不誤點通用搜尋鈕）
        // =========================================================================
        val matchedRules = HIERARCHICAL_CATEGORY_RULES.filter { rule ->
            rule.targetTriggerKeywords.any { kw ->
                expandedSynonyms.any { syn -> syn.contains(kw) || kw.contains(syn) } ||
                    goalLower.contains(kw)
            }
        }
        val hasExplicitSearch = InAppOperationPlanner.hasExplicitSearchOrInputIntent(userGoal) ||
            InAppOperationPlanner.hasExplicitSearchOrInputIntent(currentSubGoal)
        val isExplicitButtonTap = userGoal.trim().startsWith("點擊") ||
            userGoal.trim().startsWith("按一下") ||
            userGoal.trim().startsWith("點選") ||
            CommandLexiconEngine.isKnownUiButtonNoun(cleanFeature)

        if (allowSearchFirstShortcut &&
            (matchedRules.isNotEmpty() || !isExplicitButtonTap) &&
            cleanFeature.length in 2..16 &&
            !InAppWorkingMemory.hasSubmittedQuery(cleanFeature) &&
            !InAppWorkingMemory.hasTypedButNotSubmitted(cleanFeature)
        ) {
            val topSearchNode = report.finalNodes.firstOrNull { node ->
                !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node) &&
                    !InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash) &&
                    node.gridTapPoint.y <= 260 &&
                    node.label.lowercase().let { l ->
                        val isSettingsSearchPortal = l.contains("搜尋設定") || l.contains("search settings") || l.contains("搜尋應用程式")
                        val isGeneralSearchPortal = node.shortTypeTag == "INP" ||
                            node.nanoDetCategory == "UI_SEARCH_BAR" ||
                            l == "搜尋" || l == "search"
                        isSettingsSearchPortal || ((matchedRules.isNotEmpty() || hasExplicitSearch) && isGeneralSearchPortal)
                    }
            }

            if (topSearchNode != null) {
                val boost = 88f
                boosts[topSearchNode.nodeId] = boost
                val isDirectInput = topSearchNode.shortTypeTag == "INP"
                val actionType = if (isDirectInput) AiActionType.INPUT_TEXT else AiActionType.TAP
                val inputVal = if (isDirectInput) cleanFeature else null
                val pathDesc = "Step1:${if (isDirectInput) "於搜尋框輸入" else "展開搜尋入口"}「${topSearchNode.label}」搜尋「$cleanFeature」 -> Step2:直達跨層目標"

                simulatedPaths.add(
                    LookaheadCandidateEval(
                        node = topSearchNode,
                        expectedValueBoost = boost,
                        simulatedPathDescription = pathDesc
                    )
                )

                return LookaheadPlanResult(
                    targetFeatureKeyword = cleanFeature,
                    expandedSynonyms = expandedSynonyms,
                    hasDirectOrSynonymTargetOnScreen = false,
                    candidateBoosts = boosts,
                    topLookaheadPaths = simulatedPaths,
                    overrideActionType = actionType,
                    overrideTargetNode = topSearchNode,
                    overrideInputText = inputVal,
                    overrideConfidence = 0.96f,
                    lookaheadReasoningTrace = "[3步前瞻:搜尋優先捷徑] 當前畫面無「$cleanFeature」，前瞻推演 ($pathDesc) 期望值最高 (+88)"
                )
            }
        }

        // =========================================================================
        // 3. 【階段三：跨層級父分類 3 步前瞻推演 (Hierarchical Category Lookahead Tree)】
        // 當畫面無直接目標且無搜尋框（或已嘗試過搜尋）時，根據目標與所有同義詞計算父分類按鈕的期望值：
        // - 第 1 順位父分類：+62f（若該分類已點過並走錯路退回，則重罰 -100f！）
        // - 第 2 順位父分類：+48f（當第 1 順位走錯路退回後，自動晉升為最高分首選！）
        // - 第 3 順位父分類：+34f
        // =========================================================================

        if (matchedRules.isNotEmpty()) {
            for (node in report.finalNodes) {
                if (InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)) continue
                val labelLower = node.label.trim().lowercase()
                if (labelLower.isEmpty()) continue

                // 若此父分類按鈕在當前畫面已經被點過（代表剛剛點進去探索過但沒找到、已觸發 AUTO_BACKTRACK 退回），
                // 則給予 -100f 死胡同重罰，絕不再點第二次，讓第 2 名的父分類自動接手！
                if (InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) {
                    boosts[node.nodeId] = -100f
                    continue
                }

                for (rule in matchedRules) {
                    val isRank1 = rule.rank1ParentLabels.any { p -> labelLower == p || labelLower.contains(p) }
                    val isRank2 = !isRank1 && rule.rank2ParentLabels.any { p -> labelLower == p || labelLower.contains(p) }
                    val isRank3 = !isRank1 && !isRank2 && rule.rank3ParentLabels.any { p -> labelLower == p || labelLower.contains(p) }

                    val (evBoost, rankTag) = when {
                        isRank1 -> 62f to "第1順位父分類"
                        isRank2 -> 48f to "第2順位備選分類"
                        isRank3 -> 34f to "第3順位廣域分類"
                        else -> 0f to ""
                    }

                    if (evBoost > 0f) {
                        val current = boosts[node.nodeId] ?: 0f
                        if (evBoost > current) {
                            boosts[node.nodeId] = evBoost
                            val pathDesc = "Step1:點進$rankTag「${node.label}」 -> Step2:於新介面展開同義詞尋找「$cleanFeature」(若無則下滑1次，仍無立即按返回改試下一順位)"
                            simulatedPaths.removeAll { it.node.nodeId == node.nodeId }
                            simulatedPaths.add(
                                LookaheadCandidateEval(
                                    node = node,
                                    expectedValueBoost = evBoost,
                                    simulatedPathDescription = pathDesc
                                )
                            )
                        }
                    }
                }
            }
        }

        simulatedPaths.sortWith { a, b ->
            val cmp = b.expectedValueBoost.compareTo(a.expectedValueBoost)
            if (cmp != 0) cmp else DeterminismHardeningEngine.compareNodesCanonically(a.node, b.node)
        }
        val bestLookahead = simulatedPaths.firstOrNull()
        val trace = if (bestLookahead != null) {
            "[3步前瞻推演] 當前介面未見「$cleanFeature」，前瞻最高期望值路徑為 #${bestLookahead.node.shortMarkId}「${bestLookahead.node.label}」(+${bestLookahead.expectedValueBoost.toInt()}): ${bestLookahead.simulatedPathDescription}"
        } else {
            ""
        }

        return LookaheadPlanResult(
            targetFeatureKeyword = cleanFeature,
            expandedSynonyms = expandedSynonyms,
            hasDirectOrSynonymTargetOnScreen = false,
            candidateBoosts = boosts,
            topLookaheadPaths = simulatedPaths,
            overrideActionType = if (bestLookahead != null && bestLookahead.expectedValueBoost >= 45f) AiActionType.TAP else null,
            overrideTargetNode = if (bestLookahead != null && bestLookahead.expectedValueBoost >= 45f) bestLookahead.node else null,
            overrideConfidence = 0.93f,
            lookaheadReasoningTrace = trace
        )
    }
}
