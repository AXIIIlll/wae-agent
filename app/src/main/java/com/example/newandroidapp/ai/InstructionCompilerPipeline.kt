package com.example.newandroidapp.ai

import android.content.Context
import com.example.newandroidapp.stability.ClosedLoopVerifier
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.stability.InAppWorkingMemory
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 100% 原創自研：三階段指令編譯管線（Instruction Compiler Pipeline）
 *
 * 位於自然語言指令輸入與底層視覺執行引擎之間，徹底解決本地端小模型「聽不懂複雜長句子」與「跨步搶跑/關鍵字汙染」問題：
 *
 * 1. 【第一階段：中文複合長句切分器（Deterministic Clause Splitter）】：
 *    - 在送入模型前，透過高優先級語法與連接詞規則（識別「然後、接著、再、之後、並、最後、以及」與標點符號，
 *      並識別無標點跨動詞結構如「打開...搜尋...記下來...」、「去...找到...把...抓出來直接打開...」），
 *      將複雜單一長句強制切分為具嚴格先後順序的「單一意圖短句佇列（Atomic Clauses）」，禁止模型一次性硬解整段長句。
 *
 * 2. 【第二階段：受限的「6 大標準動作原語」映射（Canonical Action Primitives Mapping）】：
 *    - 嚴格定義系統僅支援 6 種標準化動作積木（`CanonicalActionPrimitiveType`）：
 *      1. `LAUNCH_APP(targetApp)`：啟動或切換至指定應用程式。
 *      2. `SEARCH_IN_APP(keyword)`：封裝好的複合搜尋巨集動作（自動包含：定位頂部/導覽列搜尋框 -> 注入關鍵字並送出 -> 驗證非輸入框之結果區出現該關鍵字）。
 *      3. `TAP_ELEMENT(targetTextOrIcon)`：尋找並點擊特定畫面元件。
 *      4. `INPUT_TEXT(contentOrMemoryRef)`：將指定文字或跨 App 暫存區（`$MEMORY`）內容填入輸入框。
 *      5. `EXTRACT_TO_MEMORY(extractRule)`：純內部資料擷取動作（不點擊 UI，直接從當前畫面樹提取目標文字清單寫入跨 App 暫存區與系統剪貼簿，若為最後一步則自動返回主畫面輸出至對話框）。
 *      6. `SYSTEM_NAV(backOrScroll)`：返回或滑動探索。
 *
 * 3. 【第三階段：執行期的「單步視野隔離（Single-Step Tunnel Vision）」與硬性驗收條件（Exit Condition）】：
 *    - 底層視覺與節點評分引擎嚴禁讀取使用者的原始長句子，每次僅能接收當前正在執行的那一個標準動作積木（如 `SEARCH_IN_APP("6767")`）。
 *    - 必須在當前動作積木的硬性驗收條件（Exit Condition）100% 達成後，狀態機才可將下一個動作積木推進至執行層。
 */
object InstructionCompilerPipeline {

    const val MEMORY_REF_TOKEN = CrossAppWorkflowEngine.MEMORY_REF_TOKEN

    /**
     * 第一階段產出之單一意圖短句（Atomic Clause）
     */
    data class AtomicClause(
        val clauseIndex: Int,
        val rawFragment: String,
        val normalizedClause: String,
        val inferredAppHint: String? = null
    )

    /**
     * 第二階段編譯產出之完整指令執行計畫
     */
    data class CompiledInstructionPlan(
        val rawInstruction: String,
        val atomicClauses: List<AtomicClause>,
        val primitives: List<CompiledActionPrimitive>,
        val subGoals: List<CooperativeSubGoal>
    ) {
        fun formatCompiledSummary(): String {
            if (primitives.isEmpty()) return "EMPTY_PLAN"
            return primitives.joinToString(" -> ") { it.formalCall }
        }
    }

    /**
     * 第三階段：單步視野隔離封包（Single-Step Tunnel Vision Packet）
     * 保證底層視覺與評分引擎僅能看到當前這一個動作原語積木，徹底隔絕原始長句與前後步驟的關鍵字汙染。
     */
    data class SingleStepTunnelVision(
        val activeStepIndex: Int,
        val totalSteps: Int,
        val primitive: CompiledActionPrimitive,
        val formalPrimitiveCall: String,
        val isolatedMotorGoal: String,
        val isolatedSemanticTarget: String,
        val resolvedInputPayload: String?,
        val isolatedSubGoal: CooperativeSubGoal
    )

    /**
     * 第三階段：硬性驗收條件裁決結果（Exit Condition Verdict）
     */
    data class ExitConditionVerdict(
        val satisfied: Boolean,
        val primitiveCall: String,
        val diagnosticReason: String
    )

    private val FORBIDDEN_CONJUNCTION_TOKENS = listOf(
        "然後再", "然后再", "然後", "然后",
        "接著再", "接着再", "接著", "接着",
        "之後再", "之后再", "之後", "之后",
        "最後再", "最后再", "最後", "最后",
        "以及再", "以及", "並且", "并且", "順便", "顺便"
    )

    // =========================================================================
    // 【第一階段：中文複合長句切分器（Deterministic Clause Splitter）】
    // =========================================================================

    /**
     * 將複雜的中文單一長句強制切分為具有嚴格先後順序的「單一意圖短句佇列（Atomic Clauses）」。
     * 同時處理：
     * 1. 顯式轉折連接詞（「然後、接著、再、之後、並、最後、以及」）與標點符號（，、；、。、->）
     * 2. 無標點跨動詞結構（如「打開Chrome搜尋6767記下來」、「打開YouTube搜尋周杰倫點第一個影片」、「去gmail找到...把地鐵抓出來直接打開google地圖搜尋并規劃路線」）
     */
    fun splitIntoAtomicClauses(
        rawInstruction: String,
        context: Context? = null
    ): List<AtomicClause> {
        val clean = OpenSourceAiDatabaseManager.correctAsrHomophonesInCommand(rawInstruction.trim(), context)
        if (clean.isEmpty()) return emptyList()

        val baseSegments = HierarchicalCommandChunker.tokenizeChronologicalClauses(clean)
        val expandedClauses = ArrayList<AtomicClause>()
        var currentAppCtx: String? = null

        for (seg in baseSegments) {
            val crossVerbParts = splitCrossVerbStructureWithinSegment(seg, context)
            for (part in crossVerbParts) {
                val trimmed = stripDanglingConjunctions(part)
                if (trimmed.isEmpty()) continue
                val explicitApp = HierarchicalCommandChunker.detectExplicitAppInClause(trimmed, context)
                if (explicitApp != null) {
                    currentAppCtx = explicitApp
                }
                expandedClauses.add(
                    AtomicClause(
                        clauseIndex = expandedClauses.size + 1,
                        rawFragment = part.trim(),
                        normalizedClause = trimmed,
                        inferredAppHint = explicitApp ?: currentAppCtx
                    )
                )
            }
        }

        return expandedClauses
    }

    /**
     * 識別並拆解單一片段內部的跨動詞結構（例如「打開YouTube搜尋周杰倫點第一個影片」-> `["打開 YouTube", "搜尋周杰倫", "點第一個影片"]`）
     */
    private fun splitCrossVerbStructureWithinSegment(
        segment: String,
        context: Context?
    ): List<String> {
        val clean = segment.trim()
        if (clean.isEmpty()) return emptyList()

        val result = ArrayList<String>()
        val detectedApp = HierarchicalCommandChunker.detectExplicitAppInClause(clean, context)

        // 1. 若開頭包含「打開/去/開啟/進入 <App>」且後面緊接著 App 內動作動詞（如「打開Chrome搜尋6767」或「去設定把Wi-Fi打開」）
        val remainderAfterApp: String = if (!detectedApp.isNullOrBlank()) {
            val stripped = HierarchicalCommandChunker.stripLeadingAppOpenPrefix(clean, detectedApp)
            if (stripped.isNotBlank() &&
                !stripped.equals(clean, ignoreCase = true) &&
                !stripped.equals(detectedApp, ignoreCase = true)
            ) {
                result.add("打開 $detectedApp")
                stripped
            } else {
                clean
            }
        } else {
            clean
        }

        // 2. 檢查剩餘子句是否同時包含「搜尋/查找 <關鍵字>」與後置「點選/播放第N個」跨動詞結構（如「搜尋周杰倫點第一個影片」）
        val searchPlusSelectRegex = Regex(
            "^((?:搜尋|搜索|查詢|查找|查一下|查|找一下|找|聽|看)\\s*.+?)(?:\\s*)((?:點擊|點選|點開|點一下|點|選擇|選|播放)\\s*(?:最上面|最上方|頂部)?(?:第[一二三四五12345][個首部支篇條則項名]|第一個結果|第一筆|第一個|那首歌|那部影片).*)$"
        )
        val matchSearchSelect = searchPlusSelectRegex.find(remainderAfterApp)
        if (matchSearchSelect != null) {
            val searchPart = matchSearchSelect.groupValues[1].trim()
            val selectPart = matchSearchSelect.groupValues[2].trim()
            if (searchPart.isNotEmpty() && selectPart.isNotEmpty()) {
                result.add(searchPart)
                result.add(selectPart)
                return result
            }
        }

        result.add(remainderAfterApp)
        return result
    }

    private fun stripDanglingConjunctions(text: String): String {
        var s = text.trim()
        for (conj in FORBIDDEN_CONJUNCTION_TOKENS) {
            if (s.startsWith(conj)) {
                s = s.removePrefix(conj).trim()
            }
            if (s.endsWith(conj)) {
                s = s.removeSuffix(conj).trim()
            }
        }
        s = s.replace(Regex("^(?:，|,|；|;|。|->)+|(?:，|,|；|;|。|->)+$"), "").trim()
        return s
    }

    // =========================================================================
    // 【第二階段：受限的「6 大標準動作原語」映射（Canonical Action Primitives Mapping）】
    // =========================================================================

    /**
     * 將使用者原始指令編譯為三階段計畫：
     * Stage 1 (`atomicClauses`) -> Stage 2 (`primitives` 6 大標準動作原語佇列 & `subGoals`)
     */
    fun compile(
        rawInstruction: String,
        context: Context? = null,
        lockToCurrentApp: Boolean = false
    ): CompiledInstructionPlan {
        val clean = rawInstruction.trim()
        val clauses = splitIntoAtomicClauses(clean, context)
        val chunkedPlan = HierarchicalCommandChunker.chunkInstruction(
            rawGoal = clean,
            context = context,
            lockToCurrentApp = lockToCurrentApp
        )

        val totalSteps = chunkedPlan.flattenedSubGoals.size.coerceAtLeast(1)
        val compiledSubGoals = chunkedPlan.flattenedSubGoals.mapIndexed { idx, sub ->
            val stepNum = idx + 1
            val primitive = mapSubGoalToCanonicalPrimitive(
                subGoal = sub.copy(stepIndex = stepNum, totalSteps = totalSteps)
            )
            sub.copy(
                stepIndex = stepNum,
                totalSteps = totalSteps,
                canonicalPrimitive = primitive
            )
        }
        val primitives = compiledSubGoals.mapNotNull { it.canonicalPrimitive }

        return CompiledInstructionPlan(
            rawInstruction = clean,
            atomicClauses = clauses,
            primitives = primitives,
            subGoals = compiledSubGoals
        )
    }

    /**
     * 將任意 `CooperativeSubGoal` 確定性映射為 6 大標準動作原語積木（`CompiledActionPrimitive`）。
     * 確保系統中所有子目標皆 100% 落入 6 種原語枚舉之一，無任何模糊自然語言殘留。
     */
    fun mapSubGoalToCanonicalPrimitive(subGoal: CooperativeSubGoal): CompiledActionPrimitive {
        val existing = subGoal.canonicalPrimitive
        if (existing != null && existing.stepIndex == subGoal.stepIndex && existing.totalSteps == subGoal.totalSteps) {
            return existing
        }

        val cat = subGoal.intentCategory.uppercase()
        val type: CanonicalActionPrimitiveType = when (cat) {
            "LAUNCH_APP" -> CanonicalActionPrimitiveType.LAUNCH_APP
            "EXTRACT_SCREEN_INFO", "EXTRACT_TO_MEMORY" -> CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY
            "SYSTEM_BACK", "SCROLL", "GESTURE_SWIPE", "SYSTEM_NAV" -> CanonicalActionPrimitiveType.SYSTEM_NAV
            "INPUT_TEXT" -> CanonicalActionPrimitiveType.INPUT_TEXT
            "INPUT_SEARCH" -> {
                val appHint = subGoal.targetAppHint.orEmpty()
                val isNoteOrChatDirectFill = appHint in setOf("備忘錄", "Keep 筆記", "Samsung Notes", "LINE", "訊息") &&
                    (CrossAppWorkflowEngine.isMemoryPlaceholder(subGoal.inputPayload) ||
                        subGoal.simplifiedMotorGoal.startsWith("輸入") ||
                        subGoal.targetSemanticPhrase.startsWith("輸入"))
                if (isNoteOrChatDirectFill) {
                    CanonicalActionPrimitiveType.INPUT_TEXT
                } else {
                    CanonicalActionPrimitiveType.SEARCH_IN_APP
                }
            }
            else -> CanonicalActionPrimitiveType.TAP_ELEMENT
        }

        val rawArg = when (type) {
            CanonicalActionPrimitiveType.LAUNCH_APP ->
                subGoal.targetAppHint?.takeIf { it.isNotBlank() } ?: subGoal.targetSemanticPhrase
            CanonicalActionPrimitiveType.SEARCH_IN_APP,
            CanonicalActionPrimitiveType.INPUT_TEXT -> {
                if (CrossAppWorkflowEngine.isMemoryPlaceholder(subGoal.inputPayload) ||
                    CrossAppWorkflowEngine.isMemoryPlaceholder(subGoal.targetSemanticPhrase)
                ) {
                    MEMORY_REF_TOKEN
                } else {
                    subGoal.inputPayload?.takeIf { it.isNotBlank() }
                        ?: subGoal.targetSemanticPhrase.replace(Regex("^(?:搜尋|搜索|輸入|填入)\\s*"), "").trim()
                }
            }
            CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY ->
                subGoal.targetSemanticPhrase.replace(Regex("^(?:擷取|提取|抽取)\\s*"), "").trim()
            CanonicalActionPrimitiveType.TAP_ELEMENT ->
                subGoal.targetSemanticPhrase
                    .replace(Regex("^(?:點擊|點選|點開|點一下|按一下|按下|開啟|關閉|點|按)\\s*"), "")
                    .replace(Regex("(?:那個|這個|那顆|這顆|該)?(?:按鈕|按鍵|圖示|圖標|鍵|一下)+$"), "")
                    .trim()
                    .ifBlank { subGoal.targetSemanticPhrase }
            CanonicalActionPrimitiveType.SYSTEM_NAV -> {
                val motor = subGoal.simplifiedMotorGoal.lowercase()
                when {
                    cat == "SYSTEM_BACK" || motor.contains("返回") || motor.contains("上一頁") -> "BACK"
                    motor.contains("上滑") || motor.contains("向上") -> "SCROLL_UP"
                    motor.contains("左滑") || motor.contains("向左") -> "SWIPE_LEFT"
                    motor.contains("右滑") || motor.contains("向右") -> "SWIPE_RIGHT"
                    else -> "SCROLL_DOWN"
                }
            }
        }

        return CompiledActionPrimitive(
            stepIndex = subGoal.stepIndex,
            totalSteps = subGoal.totalSteps,
            type = type,
            argument = rawArg.trim(),
            appPhaseIndex = subGoal.appPhaseIndex,
            totalAppBlocks = subGoal.totalAppBlocks,
            targetAppHint = subGoal.targetAppHint,
            desiredToggleState = subGoal.desiredToggleState,
            isHighRiskHint = subGoal.isHighRiskHint,
            sourceClause = subGoal.simplifiedMotorGoal
        )
    }

    // =========================================================================
    // 【第三階段：執行期的「單步視野隔離（Single-Step Tunnel Vision）」與硬性驗收條件（Exit Condition）】
    // =========================================================================

    /**
     * 為當前正在執行的子目標建立「單步視野隔離封包（Single-Step Tunnel Vision）」。
     * 底層視覺與節點評分引擎僅能接收此封包產出的單步極簡指令，100% 禁止讀取使用者的原始複合長句。
     */
    fun buildSingleStepTunnelVision(subGoal: CooperativeSubGoal): SingleStepTunnelVision {
        val primitive = mapSubGoalToCanonicalPrimitive(subGoal)
        val rawPayload = when {
            !subGoal.inputPayload.isNullOrBlank() -> subGoal.inputPayload
            primitive.type == CanonicalActionPrimitiveType.SEARCH_IN_APP ||
                primitive.type == CanonicalActionPrimitiveType.INPUT_TEXT -> primitive.argument
            else -> null
        }
        val resolvedPayload = CrossAppWorkflowEngine.resolveDynamicPayload(rawPayload)
        val effectiveArg = if (CrossAppWorkflowEngine.isMemoryPlaceholder(primitive.argument) && !resolvedPayload.isNullOrBlank()) {
            resolvedPayload
        } else {
            primitive.argument
        }

        val cleanIsolatedMotorGoal: String
        val cleanIsolatedSemanticTarget: String

        when (primitive.type) {
            CanonicalActionPrimitiveType.LAUNCH_APP -> {
                cleanIsolatedMotorGoal = "打開 $effectiveArg"
                cleanIsolatedSemanticTarget = effectiveArg
            }
            CanonicalActionPrimitiveType.SEARCH_IN_APP -> {
                // 若原 subGoal 是帶有複合篩選詞的單步，只保留當前步驟所需的檢索詞
                cleanIsolatedMotorGoal = "搜尋 $effectiveArg"
                cleanIsolatedSemanticTarget = "搜尋 $effectiveArg"
            }
            CanonicalActionPrimitiveType.INPUT_TEXT -> {
                cleanIsolatedMotorGoal = "輸入 $effectiveArg"
                cleanIsolatedSemanticTarget = "輸入 $effectiveArg"
            }
            CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY -> {
                cleanIsolatedMotorGoal = "擷取 $effectiveArg"
                cleanIsolatedSemanticTarget = effectiveArg
            }
            CanonicalActionPrimitiveType.SYSTEM_NAV -> {
                cleanIsolatedMotorGoal = when (effectiveArg.uppercase()) {
                    "BACK" -> "返回上一頁"
                    "SCROLL_UP" -> "向上滑動"
                    "SWIPE_LEFT" -> "向左滑動"
                    "SWIPE_RIGHT" -> "向右滑動"
                    else -> "向下滑動"
                }
                cleanIsolatedSemanticTarget = cleanIsolatedMotorGoal
            }
            CanonicalActionPrimitiveType.TAP_ELEMENT -> {
                val baseMotor = subGoal.simplifiedMotorGoal.trim()
                cleanIsolatedMotorGoal = stripDanglingConjunctions(
                    if (baseMotor.isNotBlank()) baseMotor else "點擊 $effectiveArg"
                )
                cleanIsolatedSemanticTarget = stripDanglingConjunctions(
                    subGoal.targetSemanticPhrase.ifBlank { effectiveArg }
                )
            }
        }

        val isolatedSubGoal = subGoal.copy(
            targetSemanticPhrase = cleanIsolatedSemanticTarget,
            inputPayload = resolvedPayload ?: subGoal.inputPayload,
            simplifiedMotorGoal = cleanIsolatedMotorGoal,
            canonicalPrimitive = primitive
        )

        return SingleStepTunnelVision(
            activeStepIndex = subGoal.stepIndex,
            totalSteps = subGoal.totalSteps,
            primitive = primitive,
            formalPrimitiveCall = primitive.formalCall,
            isolatedMotorGoal = cleanIsolatedMotorGoal,
            isolatedSemanticTarget = cleanIsolatedSemanticTarget,
            resolvedInputPayload = resolvedPayload,
            isolatedSubGoal = isolatedSubGoal
        )
    }

    /**
     * 驗證當前動作原語積木的「硬性驗收條件（Exit Condition）」是否已 100% 達成。
     * 未達成前嚴禁狀態機將下一個動作積木推進至執行層。
     */
    fun evaluateExitCondition(
        subGoal: CooperativeSubGoal,
        report: VisionPipelineReport,
        currentForegroundPackage: String? = null,
        alreadyLaunchedApp: Boolean = false
    ): ExitConditionVerdict {
        val primitive = mapSubGoalToCanonicalPrimitive(subGoal)
        return when (primitive.type) {
            CanonicalActionPrimitiveType.LAUNCH_APP -> {
                val expectedPkg = InAppWorkingMemory.expectedTargetPackage
                val pkgMatched = !currentForegroundPackage.isNullOrBlank() &&
                    expectedPkg.isNotBlank() &&
                    currentForegroundPackage == expectedPkg
                val satisfied = alreadyLaunchedApp || pkgMatched
                ExitConditionVerdict(
                    satisfied = satisfied,
                    primitiveCall = primitive.formalCall,
                    diagnosticReason = if (satisfied) {
                        "目標 App 已啟動至前景"
                    } else {
                        "目標 App 尚未進入前景"
                    }
                )
            }

            CanonicalActionPrimitiveType.SEARCH_IN_APP -> {
                val rawQuery = subGoal.inputPayload?.takeIf { it.isNotBlank() } ?: primitive.argument
                val resolvedQuery = (CrossAppWorkflowEngine.resolveDynamicPayload(rawQuery) ?: rawQuery).trim()
                if (resolvedQuery.isBlank() || CrossAppWorkflowEngine.isMemoryPlaceholder(resolvedQuery)) {
                    return ExitConditionVerdict(
                        satisfied = false,
                        primitiveCall = primitive.formalCall,
                        diagnosticReason = "搜尋關鍵字尚未解析完成"
                    )
                }

                // 條件 0：結合載入狀態偵測器 (SmartLoadingDetector)，畫面載入動畫結束前嚴禁解鎖下一子任務
                val loadingVerdict = com.example.newandroidapp.stability.SmartLoadingDetector.evaluateLoadingState(report)
                if (loadingVerdict.isLoading) {
                    return ExitConditionVerdict(
                        satisfied = false,
                        primitiveCall = primitive.formalCall,
                        diagnosticReason = "載入狀態偵測鎖定中：${loadingVerdict.reason}，禁止提前搶跑切換 App"
                    )
                }

                // 條件 1：關鍵字必須已注入並送出
                val hasSubmitted = InAppWorkingMemory.hasSubmittedEquivalentQuery(resolvedQuery)
                if (!hasSubmitted) {
                    return ExitConditionVerdict(
                        satisfied = false,
                        primitiveCall = primitive.formalCall,
                        diagnosticReason = "關鍵字「$resolvedQuery」尚未注入並送出"
                    )
                }

                // 條件 2：驗證非輸入框之結果區出現該關鍵字（或已載入搜尋結果頁／實體詳情卡，且頂部無殘留未送出之可編輯輸入框）
                val hasFocusedInputBox = report.finalNodes.any { node ->
                    node.shortTypeTag == "INP" &&
                        (node.inlineStatePrefix.contains("FOC") || node.inlineStatePrefix.contains("焦點"))
                }
                val hasTopEditableInput = report.finalNodes.any { node ->
                    node.shortTypeTag == "INP" && node.gridTapPoint.y <= 250
                }
                val nonInputResultShowsKeyword = isNonInputResultRegionShowingKeyword(report, resolvedQuery)
                val fullResultPageLoaded = ClosedLoopVerifier.isSearchResultsPageLoadedInReport(report, resolvedQuery)
                val detailOrPlaceSheetLoaded = isEntityDetailOrPlaceSheetLoaded(report)

                // 通用自動補全護欄：當頂部仍顯示可編輯輸入框 (INP) 且畫面尚未載入正式搜尋結果頁或實體詳情卡時，
                // 非輸入框區域出現的文字僅為自動補全建議列 (Autocomplete Dropdown)，必須留在當前步驟點選建議項或送出，禁止提前搶跑！
                val isStillInAutocompleteDropdown = hasTopEditableInput && !fullResultPageLoaded && !detailOrPlaceSheetLoaded

                val satisfied = if (isStillInAutocompleteDropdown) {
                    false
                } else {
                    (nonInputResultShowsKeyword || fullResultPageLoaded || detailOrPlaceSheetLoaded) &&
                        (!hasFocusedInputBox || fullResultPageLoaded || detailOrPlaceSheetLoaded)
                }

                ExitConditionVerdict(
                    satisfied = satisfied,
                    primitiveCall = primitive.formalCall,
                    diagnosticReason = if (satisfied) {
                        "搜尋關鍵字「$resolvedQuery」已送出且結果頁或地點/實體詳情卡已載入完成"
                    } else {
                        "非輸入框結果區尚未出現「$resolvedQuery」結果，禁止提前搶跑至下一步"
                    }
                )
            }

            CanonicalActionPrimitiveType.INPUT_TEXT -> {
                val rawText = subGoal.inputPayload?.takeIf { it.isNotBlank() } ?: primitive.argument
                val resolvedText = (CrossAppWorkflowEngine.resolveDynamicPayload(rawText) ?: rawText).trim()
                val satisfied = resolvedText.isNotBlank() &&
                    !CrossAppWorkflowEngine.isMemoryPlaceholder(resolvedText) &&
                    InAppWorkingMemory.hasSubmittedEquivalentQuery(resolvedText)
                ExitConditionVerdict(
                    satisfied = satisfied,
                    primitiveCall = primitive.formalCall,
                    diagnosticReason = if (satisfied) "已完成文字填入「$resolvedText」" else "尚未完成文字填入"
                )
            }

            CanonicalActionPrimitiveType.EXTRACT_TO_MEMORY -> {
                val entry = CrossAppWorkflowEngine.latestClipboardEntry
                val satisfied = entry != null && entry.extractedText.isNotBlank()
                ExitConditionVerdict(
                    satisfied = satisfied,
                    primitiveCall = primitive.formalCall,
                    diagnosticReason = if (satisfied) {
                        "已提取「${entry?.extractedText}」至 \$MEMORY 暫存區與系統剪貼簿"
                    } else {
                        "尚未提取資料至 \$MEMORY 暫存區"
                    }
                )
            }

            CanonicalActionPrimitiveType.TAP_ELEMENT -> {
                val screenHash = InAppOperationPlanner.computeQuickScreenHash(report)
                val argClean = primitive.argument.trim()
                val argSynonyms = LookaheadPlannerEngine.expandTargetSynonyms(argClean)
                    .map { it.lowercase().replace('臺', '台') }
                    .toSet()
                val argRole = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(argClean)
                val clickedOnThisScreen = report.finalNodes.any { node ->
                    if (!InAppWorkingMemory.wasCandidateClickedOnScreen(node, screenHash)) {
                        false
                    } else {
                        val lblClean = node.label.trim()
                        val lblNorm = lblClean.lowercase().replace('臺', '台')
                        val directMatch = argClean.isNotEmpty() && lblClean.contains(argClean, ignoreCase = true)
                        val synMatch = argSynonyms.any { syn ->
                            syn.length >= 2 && (lblNorm == syn || lblNorm.contains(syn))
                        }
                        val roleMatch = argRole != null &&
                            OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(lblClean) == argRole
                        directMatch || synMatch || roleMatch
                    }
                }
                ExitConditionVerdict(
                    satisfied = clickedOnThisScreen,
                    primitiveCall = primitive.formalCall,
                    diagnosticReason = if (clickedOnThisScreen) "目標元件已點擊" else "目標元件尚未完成點擊驗證"
                )
            }

            CanonicalActionPrimitiveType.SYSTEM_NAV -> {
                val satisfied = InAppWorkingMemory.currentSubPageScrollCount > 0
                ExitConditionVerdict(
                    satisfied = satisfied,
                    primitiveCall = primitive.formalCall,
                    diagnosticReason = if (satisfied) "已完成系統導航/滑動" else "尚未執行系統導航/滑動"
                )
            }
        }
    }

    /**
     * 檢查畫面中「非輸入框 (`shortTypeTag != "INP"`)」之結果區域是否已出現目標搜尋關鍵字。
     */
    fun isNonInputResultRegionShowingKeyword(
        report: VisionPipelineReport,
        keyword: String
    ): Boolean {
        val cleanKw = keyword.trim().lowercase().replace('臺', '台')
        if (cleanKw.isEmpty()) return false

        val nonInputNodes = report.finalNodes.filter { node ->
            node.shortTypeTag != "INP" &&
                node.shortTypeTag != "SCR" &&
                node.label.trim().isNotEmpty() &&
                !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(node)
        }
        if (nonInputNodes.isEmpty()) return false

        // 直接包含完整關鍵字，或包含關鍵字的核心詞片段（>= 2 字）
        if (nonInputNodes.any { it.label.lowercase().replace('臺', '台').contains(cleanKw) }) {
            return true
        }
        if (cleanKw.length >= 4) {
            val prefix2 = cleanKw.take(2)
            val suffix2 = cleanKw.takeLast(2)
            if (nonInputNodes.any { lbl ->
                    val l = lbl.label.lowercase().replace('臺', '台')
                    l.contains(prefix2) && l.contains(suffix2)
                }
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 檢測當前畫面是否已展開「地圖地點詳情卡 (Place Sheet)」或「商品/實體詳情頁 (Entity Detail Sheet)」。
     * 當搜尋送出後（如 Google Maps 查台北101），畫面可能只有頂部搜尋框顯示「台北101」，
     * 而下方主視窗全部都是可點擊的詳情動作膠囊（如「路線」、「開始」、「通話」、「儲存」、「分享」）。
     * 一旦偵測到 >= 2 個獨立的詳情動作角色同時出現，代表已成功進入實體詳情卡，應立即推進至下一步 TAP_ELEMENT！
     */
    fun isEntityDetailOrPlaceSheetLoaded(report: VisionPipelineReport): Boolean {
        val detailActionRoles = setOf(
            "MAP_DIRECTIONS_ROUTE",
            "MAP_START_NAVIGATION",
            "LIKE_FAVORITE_BOOKMARK",
            "SHARE_FORWARD",
            "SHARE_EXPORT",
            "CALL_PHONE_CONTACT",
            "OPEN_EXTERNAL_WEBSITE",
            "ADD_TO_CART",
            "ADD_TO_CART_BUY",
            "CHECKOUT_BUY_NOW"
        )
        val matchedRoles = HashSet<String>()
        for (node in report.finalNodes) {
            if (node.shortTypeTag == "INP" || node.shortTypeTag == "SCR") continue
            val role = OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(node.label)
                ?: node.predictedFunctionRole.takeIf { it in detailActionRoles }
            if (role != null && role in detailActionRoles) {
                matchedRoles.add(role)
            }
        }
        // 地圖詳情卡特徵：出現 MAP_DIRECTIONS_ROUTE 或 MAP_START_NAVIGATION 且伴隨至少 1 個其他詳情動作，或出現 >= 3 個詳情動作按鈕
        if ((matchedRoles.contains("MAP_DIRECTIONS_ROUTE") || matchedRoles.contains("MAP_START_NAVIGATION")) && matchedRoles.size >= 2) {
            return true
        }
        return matchedRoles.size >= 3
    }
}
