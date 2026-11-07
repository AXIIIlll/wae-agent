package com.example.newandroidapp.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.newandroidapp.stability.InAppOperationPlanner
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 100% 原創自研：跨 App 多階段流程編排與深度跨 App 指令理解引擎 (CrossAppWorkflowEngine)
 *
 * 專責解決「一條自然語言指令橫跨 2 個以上不同 App 串聯執行、或需把 App A 畫面上的查詢結果/重點帶去 App B 貼上/搜尋/傳送」的核心難題：
 *
 * 1. 【多階段跨 App 自然語言分段與隱式意圖解析 (`parseCrossAppPhases`)】：
 *    - 識別顯式跨 App 轉折語法：「先去 A ... 然後打開 B ...」、「在 A 查完後去 B ...」、「複製結果後打開 B ...」。
 *    - 識別隱式跨 App 意圖：
 *      - 「幫我上網查今日美金匯率，然後記到備忘錄」 -> 自動推論 Phase 1 = `Chrome`、Phase 2 = `備忘錄`
 *      - 「把現在畫面上的重點存到備忘錄 / 傳到 LINE 給媽媽」 -> 自動推論 Phase 1 = `當前畫面擷取 (EXTRACT_SCREEN_INFO)`、Phase 2 = `備忘錄 / LINE`
 *
 * 2. 【跨 App 語義剪貼簿 (`CrossAppSemanticClipboard`)】：
 *    - 當第一階段在 App A 查詢完成（或要求擷取當前畫面重點）時，透過 `extractAndStoreFromReport` 從 `VisionPipelineReport`
 *      過濾無關導覽列與按鈕，精準提煉與主題相關的核心文字/數值（如氣溫、匯率、股價、歌名、標題），
 *      存入記憶體語義槽位 `{{CROSS_APP_CLIPBOARD}}` 並同步寫入 Android 系統 `ClipboardManager`。
 *    - 當第二階段在 App B 執行 `INPUT_TEXT` 時，透過 `resolveDynamicPayload` 自動將 `{{CROSS_APP_CLIPBOARD}}` 替換為剛擷取出的真實內容！
 *
 * 3. 【跨 App 階段隔離與防劫持白名單協同】：
 *    - 每個 `CooperativeSubGoal` 皆標註 `appPhaseIndex`（1, 2, 3...），當進入下一個 App 階段時，
 *      允許 `DualLocalAiCoordinator` 再次執行 `LAUNCH_APP` 並呼叫 `InAppWorkingMemory.transitionToNextAppPhase`。
 */
object CrossAppWorkflowEngine {

    const val CLIPBOARD_PLACEHOLDER = "{{CROSS_APP_CLIPBOARD}}"
    const val MEMORY_REF_TOKEN = "\$MEMORY"

    fun isMemoryPlaceholder(payload: String?): Boolean {
        if (payload.isNullOrBlank()) return false
        return payload.contains(CLIPBOARD_PLACEHOLDER) || payload.contains(MEMORY_REF_TOKEN)
    }

    enum class TargetEntityCategory {
        LOCATION_TRANSIT,
        TIME_DATE,
        CODE_IDENTIFIER,
        PRICE_METRIC,
        GENERAL
    }

    data class CrossAppClipboardEntry(
        val sourcePackageOrApp: String,
        val queryTopic: String,
        val extractedText: String,
        val timestampMs: Long,
        val entityCategory: TargetEntityCategory = TargetEntityCategory.GENERAL,
        val isPreciseEntitySpan: Boolean = false
    )

    data class ParsedAppPhase(
        val phaseIndex: Int,
        val rawClause: String,
        val targetAppHint: String?,
        val requiresCurrentScreenExtractFirst: Boolean = false,
        val shouldExtractResultAtEnd: Boolean = false,
        val consumesClipboardPayload: Boolean = false,
        val recipientOrNoteTarget: String? = null
    )

    @Volatile
    var latestClipboardEntry: CrossAppClipboardEntry? = null
        private set

    private val EXPLICIT_APP_ALIASES = linkedMapOf(
        "設定" to "設定",
        "系統設定" to "設定",
        "settings" to "設定",
        "youtube" to "YouTube",
        "yt" to "YouTube",
        "spotify" to "Spotify",
        "chrome" to "Chrome",
        "瀏覽器" to "Chrome",
        "google" to "Chrome",
        "備忘錄" to "備忘錄",
        "筆記本" to "備忘錄",
        "筆記" to "備忘錄",
        "記事本" to "備忘錄",
        "keep" to "Keep 筆記",
        "samsung notes" to "Samsung Notes",
        "三星筆記" to "Samsung Notes",
        "line" to "LINE",
        "賴" to "LINE",
        "訊息" to "訊息",
        "簡訊" to "訊息",
        "時鐘" to "時鐘",
        "鬧鐘" to "時鐘",
        "行事曆" to "日曆",
        "日曆" to "日曆",
        "計算機" to "計算機",
        "算盤" to "計算機",
        "地圖" to "地圖",
        "google maps" to "地圖",
        "相簿" to "媒體瀏覽器",
        "gmail" to "Gmail",
        "信箱" to "Gmail"
    )

    private val NOTE_AND_SAVE_VERBS = listOf(
        "記下來", "記到", "寫進", "寫到", "存到", "存入", "記錄到", "貼到", "貼上", "新增筆記", "寫備忘錄"
    )

    private val SHARE_AND_SEND_VERBS = listOf(
        "傳給", "傳到", "發給", "發送到", "分享給", "分享到", "貼給", "告訴"
    )

    private val CURRENT_SCREEN_REF_KEYWORDS = listOf(
        "現在畫面", "當前畫面", "這個畫面", "這頁", "這首歌", "這篇文章", "這個結果", "畫面上的重點", "畫面重點"
    )

    private val UI_CHROME_NOISE_WORDS = setOf(
        "返回", "上一頁", "首頁", "主頁", "搜尋", "搜索", "選單", "更多", "設定", "分享", "關閉",
        "略過", "廣告", "登入", "註冊", "個人資料", "通知", "探索", "媒體庫", "shorts", "訂閱內容"
    )

    private val FALLBACK_CANONICAL_PACKAGES = mapOf(
        "設定" to ("設定" to "com.android.settings"),
        "Chrome" to ("Chrome" to "com.android.chrome"),
        "YouTube" to ("YouTube" to "com.google.android.youtube"),
        "Spotify" to ("Spotify" to "com.spotify.music"),
        "備忘錄" to ("備忘錄" to "com.google.android.keep"),
        "Keep 筆記" to ("Keep 筆記" to "com.google.android.keep"),
        "Samsung Notes" to ("Samsung Notes" to "com.samsung.android.app.notes"),
        "LINE" to ("LINE" to "jp.naver.line.android"),
        "訊息" to ("訊息" to "com.google.android.apps.messaging"),
        "時鐘" to ("時鐘" to "com.google.android.deskclock"),
        "日曆" to ("日曆" to "com.google.android.calendar"),
        "計算機" to ("計算機" to "com.google.android.calculator"),
        "地圖" to ("地圖" to "com.google.android.apps.maps"),
        "Gmail" to ("Gmail" to "com.google.android.gm")
    )

    fun resolveFallbackAppInfo(appHint: String?): InstalledAppLauncher.InstalledAppInfo? {
        if (appHint.isNullOrBlank()) return null
        val trimmed = appHint.trim()
        val canonical = EXPLICIT_APP_ALIASES[trimmed.lowercase()] ?: trimmed
        val pair = FALLBACK_CANONICAL_PACKAGES[canonical]
        if (pair != null) {
            return InstalledAppLauncher.InstalledAppInfo(
                label = pair.first,
                packageName = pair.second,
                packageTail = pair.second.substringAfterLast('.')
            )
        }
        // 整合 7 大開源數據庫中的 87+ 台灣/大陸/國際主流 App 包名與別名庫
        val openSourceApp = OpenSourceAiDatabaseManager.resolveAppByColloquialAlias(trimmed)
        if (openSourceApp != null) {
            return InstalledAppLauncher.InstalledAppInfo(
                label = openSourceApp.canonicalLabel,
                packageName = openSourceApp.packageName,
                packageTail = openSourceApp.packageName.substringAfterLast('.')
            )
        }
        return null
    }

    @Synchronized
    fun resetClipboard() {
        latestClipboardEntry = null
    }

    @Synchronized
    fun storeExtractedScreenData(
        sourceApp: String,
        queryTopic: String,
        extractedText: String,
        context: Context? = null,
        entityCategory: TargetEntityCategory = TargetEntityCategory.GENERAL,
        isPreciseEntitySpan: Boolean = false,
        syncToSystemClipboard: Boolean = false
    ): CrossAppClipboardEntry {
        val cleanText = extractedText.trim().ifBlank { queryTopic.trim().ifBlank { "已擷取畫面重點資訊" } }
        val entry = CrossAppClipboardEntry(
            sourcePackageOrApp = sourceApp.ifBlank { "未知來源" },
            queryTopic = queryTopic.trim(),
            extractedText = cleanText,
            timestampMs = System.currentTimeMillis(),
            entityCategory = entityCategory,
            isPreciseEntitySpan = isPreciseEntitySpan
        )
        latestClipboardEntry = entry

        // 預設僅存入 AI 內部專屬記憶體槽位 (latestClipboardEntry / {{CROSS_APP_CLIPBOARD}})，
        // 不覆寫使用者系統剪貼簿與鍵盤剪貼簿歷史；僅在明確要求同步系統剪貼簿時才寫入。
        if (syncToSystemClipboard && context != null) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("AI_CrossApp_Extract", cleanText))
            }
        }
        return entry
    }

    /**
     * 將含有 `{{CROSS_APP_CLIPBOARD}}` 或 `$MEMORY` 的輸入 Payload 動態替換為上一階段從畫面擷取的真實資訊。
     * 若擷取結果為精準實體子字串（如地鐵站名、地址、單號、驗證碼），直接回傳純實體字串，不附加主題前綴以免干擾地圖/搜尋框檢索！
     */
    fun resolveDynamicPayload(rawPayload: String?): String? {
        if (rawPayload.isNullOrBlank()) return rawPayload
        if (!isMemoryPlaceholder(rawPayload)) return rawPayload
        val entry = latestClipboardEntry
        val replacement = if (entry != null && entry.extractedText.isNotBlank()) {
            if (entry.isPreciseEntitySpan || entry.entityCategory != TargetEntityCategory.GENERAL) {
                entry.extractedText
            } else if (entry.queryTopic.isNotBlank() && !entry.extractedText.contains(entry.queryTopic)) {
                "${entry.queryTopic}: ${entry.extractedText}"
            } else {
                entry.extractedText
            }
        } else {
            "跨App自動摘要紀錄"
        }
        return rawPayload
            .replace(CLIPBOARD_PLACEHOLDER, replacement)
            .replace(MEMORY_REF_TOKEN, replacement)
            .trim()
    }

    fun detectTargetEntityCategory(extractHint: String): TargetEntityCategory {
        val lower = extractHint.lowercase().trim()
        return when {
            lower.contains("地鐵") || lower.contains("地铁") || lower.contains("捷運") ||
                lower.contains("車站") || lower.contains("车站") || lower.contains("火車站") ||
                lower.contains("高鐵") || lower.contains("公車") || lower.contains("地點") ||
                lower.contains("地点") || lower.contains("地址") || lower.contains("位置") ||
                lower.contains("餐廳") || lower.contains("餐厅") || lower.contains("店名") ||
                lower.contains("哪裡") || lower.contains("哪里") -> TargetEntityCategory.LOCATION_TRANSIT

            lower.contains("時間") || lower.contains("时间") || lower.contains("幾點") ||
                lower.contains("几点") || lower.contains("日期") || lower.contains("幾號") ||
                lower.contains("星期幾") -> TargetEntityCategory.TIME_DATE

            lower.contains("驗證碼") || lower.contains("验证码") || lower.contains("單號") ||
                lower.contains("单号") || lower.contains("編號") || lower.contains("訂單號") ||
                lower.contains("電話") || lower.contains("电话") || lower.contains("手機") ||
                lower.contains("號碼") || lower.contains("密碼") -> TargetEntityCategory.CODE_IDENTIFIER

            lower.contains("匯率") || lower.contains("汇率") || lower.contains("股價") ||
                lower.contains("股价") || lower.contains("金額") || lower.contains("價格") ||
                lower.contains("多少錢") || lower.contains("天氣") || lower.contains("氣溫") ||
                lower.contains("溫度") -> TargetEntityCategory.PRICE_METRIC

            else -> TargetEntityCategory.GENERAL
        }
    }

    /**
     * 從長句子節點中精準切取出目標實體子字串（例如從信件內文「大家週五晚上七點在捷運忠孝復興站4號出口集合聚餐喔！」抽出「捷運忠孝復興站4號出口」）
     */
    fun extractPreciseEntitySpan(text: String, category: TargetEntityCategory): String? {
        val clean = text.trim()
        if (clean.length < 2) return null

        when (category) {
            TargetEntityCategory.LOCATION_TRANSIT -> {
                // 1. 優先匹配「捷運/地鐵 + 站名 + 出口」或「站名 + 捷運站/地鐵站/車站 + 出口」
                val transitPrefixRegex = Regex(
                    "((?:捷運|地鐵|地铁|高鐵|台鐵|火車)[一-龥A-Za-z0-9]{1,8}站(?:[A-Za-z0-9一-龥]{1,5}號?出口)?)"
                )
                transitPrefixRegex.find(clean)?.groupValues?.getOrNull(1)?.let { return it }

                val transitSuffixRegex = Regex(
                    "([一-龥A-Za-z0-9]{2,8}(?:捷運站|地鐵站|地铁站|火車站|高鐵站|車站|车站)(?:[A-Za-z0-9一-龥]{1,5}號?出口)?)"
                )
                transitSuffixRegex.find(clean)?.groupValues?.getOrNull(1)?.let {
                    val trimmedStation = it.replace(Regex("^(?:在|於|到|去|從|約在|集合於|地點在|位置在)"), "")
                    if (trimmedStation.length >= 3) return trimmedStation
                }

                // 2. 匹配完整街道地址（市/縣/區 + 路/街/段/巷/號）
                val addressRegex = Regex(
                    "([一-龥]{2,5}[市縣區鄉鎮][一-龥0-9巷弄段路街大道]{2,14}[0-9]{1,5}號?(?:[0-9一-龥]{1,5}樓)?)"
                )
                addressRegex.find(clean)?.groupValues?.getOrNull(1)?.let { return it }

                // 3. 匹配介詞（「在 / 於 / 到 / 地點： / 位置： / 約在」）後方的地標/站點/餐廳名稱
                val prepLocRegex = Regex(
                    "(?:地點(?:是|在|為|定在)?[:：\\s]*|位置(?:是|在|為)?[:：\\s]*|約在|集合(?:點|地點)?(?:是|在)?[:：\\s]*|在|於|到)\\s*([一-龥A-Za-z0-9]{2,14}?(?:站(?:[A-Za-z0-9]{1,4}號?出口)?|出口|餐廳|餐厅|火鍋|燒肉|居酒屋|咖啡廳|飯店|酒店|百貨|大樓|廣場|園區|夜市|門市|分店))"
                )
                prepLocRegex.find(clean)?.groupValues?.getOrNull(1)?.let { return it }
            }

            TargetEntityCategory.TIME_DATE -> {
                val timeRegex = Regex(
                    "((?:\\d{1,2}月\\d{1,2}[日號]|(?:這|下|本)?(?:週|星期|禮拜)[一二三四五六日天]|明天|後天|今晚|今天)\\s*(?:早上|上午|中午|下午|晚上|晚間)?\\s*(?:\\d{1,2}(?:[:：點時]\\d{0,2}分?|點半)|[一二三四五六七八九十]{1,2}點半?)|(?:\\d{1,2}[:：]\\d{2}))"
                )
                timeRegex.find(clean)?.groupValues?.getOrNull(1)?.let { return it.trim() }
            }

            TargetEntityCategory.CODE_IDENTIFIER -> {
                val codeRegex = Regex("(?<![A-Za-z0-9])([A-Z0-9]{4,18}|09\\d{2}[-\\s]?\\d{3}[-\\s]?\\d{3})(?![A-Za-z0-9])")
                codeRegex.find(clean)?.groupValues?.getOrNull(1)?.let { return it.trim() }
            }

            TargetEntityCategory.PRICE_METRIC, TargetEntityCategory.GENERAL -> {
                return null
            }
        }
        return null
    }

    /**
     * 從當前畫面 `VisionPipelineReport` 中提煉與 `queryTopic` 最相關的核心結果文字，並存入跨 App 語義剪貼簿
     */
    fun extractAndStoreFromReport(
        report: VisionPipelineReport,
        queryTopic: String,
        sourceApp: String,
        context: Context? = null
    ): String {
        val cleanTopic = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(
            queryTopic
                .replace(Regex("^(擷取畫面資訊|擷取重點|擷取|搜尋|查詢|查找)[:：\\s]*"), "")
                .trim(),
            context
        )
        val category = detectTargetEntityCategory(cleanTopic)
        val topicTokens = cleanTopic.lowercase()
            .split(Regex("[\\s,，、:：]+"))
            .filter { it.isNotBlank() }

        // 先嘗試針對目標實體類別（如 LOCATION_TRANSIT 地鐵/地點、TIME_DATE 時間、CODE_IDENTIFIER 單號）執行精準子字串抽取（同時支援簡體與繁體畫面文字）
        if (category != TargetEntityCategory.GENERAL && category != TargetEntityCategory.PRICE_METRIC) {
            for (node in report.finalNodes) {
                val label = node.label.trim()
                if (label.length < 2 || node.shortTypeTag == "INP") continue
                val normLabel = OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(label, context)
                val span = extractPreciseEntitySpan(label, category) ?: extractPreciseEntitySpan(normLabel, category)
                if (!span.isNullOrBlank()) {
                    storeExtractedScreenData(
                        sourceApp = sourceApp,
                        queryTopic = cleanTopic,
                        extractedText = span,
                        context = context,
                        entityCategory = category,
                        isPreciseEntitySpan = true
                    )
                    return span
                }
            }
        }

        val candidateTexts = ArrayList<Pair<String, Float>>()

        for (node in report.finalNodes) {
            val label = node.label.trim()
            if (label.length < 2) continue
            if (node.shortTypeTag == "INP") continue
            val lower = label.lowercase()
            if (lower in UI_CHROME_NOISE_WORDS) continue
            if (UI_CHROME_NOISE_WORDS.any { lower == it }) continue

            var score = 0f
            // 1. 包含主題關鍵字的節點優先
            for (tk in topicTokens) {
                if (tk.length >= 2 && lower.contains(tk)) {
                    score += 3.5f
                }
            }
            for (ch in cleanTopic) {
                if (!ch.isWhitespace() && label.contains(ch)) {
                    score += 0.45f
                }
            }

            // 2. 含有數值、單位（°C、度、元、$、%、匯率、股價、比分）或地點地標特徵的高資訊密度節點大幅加分
            if (Regex("(\\d+(?:\\.\\d+)?)\\s*(?:°c|度|%|元|塊|nt\\$|usd|us\\$|萬|億)").containsMatchIn(lower) ||
                Regex("(?:nt\\$|usd|us\\$|\\$|¥)\\s*\\d+").containsMatchIn(lower)
            ) {
                score += 4.2f
            } else if (category == TargetEntityCategory.LOCATION_TRANSIT &&
                Regex("(?:捷運|地鐵|地铁|車站|车站|號出口|出口|路|街|段|餐廳|飯店)").containsMatchIn(lower)
            ) {
                score += 4.5f
            } else if (label.any { it.isDigit() }) {
                score += 1.5f
            }

            // 3. 位於畫面中央核心內容區（非頂部狀態列與底部導覽列）加分
            if (node.gridTapPoint.y in 160..840) {
                score += 1.2f
            }
            if (label.length in 4..45) {
                score += 0.8f
            }

            if (score > 0.5f) {
                candidateTexts.add(label to score)
            }
        }

        val sortedUnique = candidateTexts
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()

        val extractedSummary = when {
            sortedUnique.isNotEmpty() -> sortedUnique.take(2).joinToString(" | ")
            report.finalNodes.isNotEmpty() -> {
                report.finalNodes
                    .map { it.label.trim() }
                    .filter { it.length >= 2 && it.lowercase() !in UI_CHROME_NOISE_WORDS }
                    .take(2)
                    .joinToString(" | ")
                    .ifBlank { cleanTopic.ifBlank { "已完成畫面資訊擷取" } }
            }
            else -> cleanTopic.ifBlank { "已完成畫面資訊擷取" }
        }

        storeExtractedScreenData(
            sourceApp = sourceApp,
            queryTopic = cleanTopic,
            extractedText = extractedSummary,
            context = context,
            entityCategory = category,
            isPreciseEntitySpan = false
        )
        return extractedSummary
    }

    /**
     * 判斷一條自然語言指令是否屬於「跨 App 多階段指令」並拆解為完整的跨 App 子目標序列。
     * 若僅為單一 App 指令則回傳 `null`，交由既有單 App 流程處理。
     */
    fun buildCrossAppSubGoalChain(
        rawGoal: String,
        context: Context?,
        highRiskKeywords: List<String>,
        lockToCurrentApp: Boolean = false
    ): List<CooperativeSubGoal>? {
        if (lockToCurrentApp) return null
        val clean = rawGoal.trim()
        if (clean.length < 4) return null

        // 委派至階層式「先分 App 大塊、再塊內碎化」引擎
        val plan = HierarchicalCommandChunker.chunkInstruction(
            rawGoal = clean,
            context = context,
            highRiskKeywords = highRiskKeywords,
            lockToCurrentApp = false
        )
        if (plan.totalAppBlocks >= 2 && plan.flattenedSubGoals.size >= 2) {
            return plan.flattenedSubGoals
        }

        val phases = parseCrossAppPhases(clean, context)
        if (phases.size < 2 && phases.firstOrNull()?.requiresCurrentScreenExtractFirst != true) {
            return null
        }

        val subGoals = ArrayList<CooperativeSubGoal>()

        for (phase in phases) {
            val clause = phase.rawClause.trim()
            val lower = clause.lowercase()
            val isRisk = highRiskKeywords.any { lower.contains(it) }

            // 情況 A：此階段為「直接從當前畫面擷取重點」（例如：「把現在畫面上的重點存到備忘錄」的第 1 階段）
            if (phase.requiresCurrentScreenExtractFirst && phase.targetAppHint == null) {
                subGoals.add(
                    CooperativeSubGoal(
                        stepIndex = subGoals.size + 1,
                        totalSteps = 0,
                        intentCategory = "EXTRACT_SCREEN_INFO",
                        targetAppHint = null,
                        targetSemanticPhrase = "擷取當前畫面重點資訊",
                        inputPayload = null,
                        isHighRiskHint = false,
                        appPhaseIndex = phase.phaseIndex
                    )
                )
                continue
            }

            // 1. 若該階段有目標 App，先加入 LAUNCH_APP 子目標
            if (!phase.targetAppHint.isNullOrBlank()) {
                subGoals.add(
                    CooperativeSubGoal(
                        stepIndex = subGoals.size + 1,
                        totalSteps = 0,
                        intentCategory = "LAUNCH_APP",
                        targetAppHint = phase.targetAppHint,
                        targetSemanticPhrase = "啟動目標 App：${phase.targetAppHint}",
                        inputPayload = null,
                        isHighRiskHint = false,
                        appPhaseIndex = phase.phaseIndex
                    )
                )
            }

            // 2. 若此階段是「接收跨 App 剪貼簿內容並寫入備忘錄或傳送訊息」
            if (phase.consumesClipboardPayload) {
                val targetActionHint = phase.recipientOrNoteTarget
                if (!targetActionHint.isNullOrBlank()) {
                    // 例如傳 LINE 給「媽媽」或點擊「新增筆記」
                    subGoals.add(
                        CooperativeSubGoal(
                            stepIndex = subGoals.size + 1,
                            totalSteps = 0,
                            intentCategory = "TAP_TARGET",
                            targetAppHint = phase.targetAppHint,
                            targetSemanticPhrase = targetActionHint,
                            inputPayload = null,
                            isHighRiskHint = isRisk,
                            appPhaseIndex = phase.phaseIndex
                        )
                    )
                }
                subGoals.add(
                    CooperativeSubGoal(
                        stepIndex = subGoals.size + 1,
                        totalSteps = 0,
                        intentCategory = "INPUT_SEARCH",
                        targetAppHint = phase.targetAppHint,
                        targetSemanticPhrase = "輸入跨App擷取內容",
                        inputPayload = CLIPBOARD_PLACEHOLDER,
                        isHighRiskHint = isRisk,
                        appPhaseIndex = phase.phaseIndex
                    )
                )
                continue
            }

            // 3. 一般 App 階段內的搜尋或點擊操作拆解
            val actionPart = stripLeadingAppOpenPrefix(clause, phase.targetAppHint)
            if (actionPart.isNotBlank()) {
                val (query, followUp) = InAppOperationPlanner.extractSearchQueryAndFollowUp(actionPart)
                val implicitSearchQuery = if (query.isNotBlank()) {
                    query
                } else {
                    extractImplicitLookupTopic(actionPart)
                }

                if (implicitSearchQuery.isNotBlank()) {
                    subGoals.add(
                        CooperativeSubGoal(
                            stepIndex = subGoals.size + 1,
                            totalSteps = 0,
                            intentCategory = "INPUT_SEARCH",
                            targetAppHint = phase.targetAppHint,
                            targetSemanticPhrase = "搜尋 $implicitSearchQuery",
                            inputPayload = implicitSearchQuery,
                            isHighRiskHint = isRisk,
                            appPhaseIndex = phase.phaseIndex
                        )
                    )
                    if (phase.shouldExtractResultAtEnd) {
                        // 在搜尋送出並看到結果畫面後，自動插入 EXTRACT_SCREEN_INFO 將結果提煉進跨 App 剪貼簿
                        subGoals.add(
                            CooperativeSubGoal(
                                stepIndex = subGoals.size + 1,
                                totalSteps = 0,
                                intentCategory = "EXTRACT_SCREEN_INFO",
                                targetAppHint = phase.targetAppHint,
                                targetSemanticPhrase = implicitSearchQuery,
                                inputPayload = null,
                                isHighRiskHint = false,
                                appPhaseIndex = phase.phaseIndex
                            )
                        )
                    } else {
                        val tapPhrase = if (followUp.isNotBlank()) "$implicitSearchQuery $followUp" else implicitSearchQuery
                        subGoals.add(
                            CooperativeSubGoal(
                                stepIndex = subGoals.size + 1,
                                totalSteps = 0,
                                intentCategory = "TAP_TARGET",
                                targetAppHint = phase.targetAppHint,
                                targetSemanticPhrase = tapPhrase,
                                inputPayload = null,
                                isHighRiskHint = isRisk,
                                appPhaseIndex = phase.phaseIndex
                            )
                        )
                    }
                } else {
                    val toggleHint = when {
                        actionPart.contains("開啟") || actionPart.contains("打開") -> true
                        actionPart.contains("關閉") || actionPart.contains("關掉") -> false
                        else -> null
                    }
                    val cleanTarget = actionPart
                        .replace(Regex("^(請幫我|幫我|先|再|點擊|點選|進入|開啟|打開|關閉|關掉|切換)"), "")
                        .trim()
                        .ifBlank { actionPart }
                    subGoals.add(
                        CooperativeSubGoal(
                            stepIndex = subGoals.size + 1,
                            totalSteps = 0,
                            intentCategory = if (toggleHint != null && (cleanTarget.contains("藍牙") || cleanTarget.contains("wi-fi") || cleanTarget.contains("wifi") || cleanTarget.contains("深色"))) "TOGGLE_SWITCH" else "TAP_TARGET",
                            targetAppHint = phase.targetAppHint,
                            targetSemanticPhrase = cleanTarget,
                            inputPayload = null,
                            desiredToggleState = toggleHint,
                            isHighRiskHint = isRisk,
                            appPhaseIndex = phase.phaseIndex
                        )
                    )
                    if (phase.shouldExtractResultAtEnd) {
                        subGoals.add(
                            CooperativeSubGoal(
                                stepIndex = subGoals.size + 1,
                                totalSteps = 0,
                                intentCategory = "EXTRACT_SCREEN_INFO",
                                targetAppHint = phase.targetAppHint,
                                targetSemanticPhrase = cleanTarget,
                                inputPayload = null,
                                isHighRiskHint = false,
                                appPhaseIndex = phase.phaseIndex
                            )
                        )
                    }
                }
            }
        }

        if (subGoals.size < 2) return null
        val distinctPhases = subGoals.map { it.appPhaseIndex }.distinct().size
        if (distinctPhases < 2) return null

        val total = subGoals.size
        return subGoals.mapIndexed { idx, item ->
            item.copy(stepIndex = idx + 1, totalSteps = total)
        }
    }

    /**
     * 將自然語言指令解析為多個跨 App 階段 (`ParsedAppPhase`)
     */
    fun parseCrossAppPhases(rawGoal: String, context: Context? = null): List<ParsedAppPhase> {
        val clean = rawGoal.trim()

        // 1. 檢查是否為「把現在畫面上的重點/這首歌 -> 存到備忘錄 / 傳到 LINE / 去 YouTube 搜尋」
        val mentionsCurrentScreen = CURRENT_SCREEN_REF_KEYWORDS.any { clean.contains(it) }
        if (mentionsCurrentScreen) {
            val secondApp = detectAppInClause(clean, context) ?: inferImplicitAppFromClause(clean)
            if (secondApp != null) {
                val isSearchInSecondApp = clean.contains("搜尋") || clean.contains("搜索") || clean.contains("查找")
                val recipientOrAction = extractRecipientOrNoteEntry(clean, secondApp)
                return listOf(
                    ParsedAppPhase(
                        phaseIndex = 1,
                        rawClause = "擷取當前畫面重點資訊",
                        targetAppHint = null,
                        requiresCurrentScreenExtractFirst = true,
                        shouldExtractResultAtEnd = true
                    ),
                    ParsedAppPhase(
                        phaseIndex = 2,
                        rawClause = clean,
                        targetAppHint = secondApp,
                        consumesClipboardPayload = true,
                        recipientOrNoteTarget = if (isSearchInSecondApp) null else recipientOrAction
                    )
                )
            }
        }

        // 2. 依跨阶段連接詞切分句子
        val rawSegments = clean.split(
            Regex("(?:然後再到|然後再去|然後打開|然後開啟|然後去|接著打開|接著去|再去|再打開|再開啟|之後打開|之後去|查完後|複製結果後|複製後|並打開|並去|，然後|，接著|，再|,\\s*then\\s*|->)")
        ).map { it.trim() }.filter { it.isNotEmpty() }

        // 若未命中複合連接詞，但句子中同時有「去 A 查... 記到/傳到 B」結構，進行二次切分
        val segments = if (rawSegments.size == 1) {
            splitImplicitTwoStageDataTransfer(clean)
        } else {
            rawSegments
        }

        if (segments.size < 2) return emptyList()

        val parsedList = ArrayList<ParsedAppPhase>()
        for ((idx, seg) in segments.withIndex()) {
            val explicitApp = detectAppInClause(seg, context)
            val inferredApp = explicitApp ?: inferImplicitAppFromClause(seg)
            val isTransferConsumer = idx > 0 && (
                NOTE_AND_SAVE_VERBS.any { seg.contains(it) } ||
                    SHARE_AND_SEND_VERBS.any { seg.contains(it) } ||
                    seg.contains("記下來") || seg.contains("貼上")
                )
            parsedList.add(
                ParsedAppPhase(
                    phaseIndex = idx + 1,
                    rawClause = seg,
                    targetAppHint = inferredApp,
                    shouldExtractResultAtEnd = false,
                    consumesClipboardPayload = isTransferConsumer,
                    recipientOrNoteTarget = if (isTransferConsumer) extractRecipientOrNoteEntry(seg, inferredApp) else null
                )
            )
        }

        // 若後一階段需要消費剪貼簿資料 (`consumesClipboardPayload = true`)，則自動標記前一階段在結束時執行 `shouldExtractResultAtEnd = true`
        for (i in 0 until parsedList.size - 1) {
            if (parsedList[i + 1].consumesClipboardPayload) {
                parsedList[i] = parsedList[i].copy(shouldExtractResultAtEnd = true)
            }
        }

        // 確認至少有 2 個階段具有不同的目標 App（或 1 個查詢 App + 1 個寫入/傳送 App）
        val validAppCount = parsedList.count { !it.targetAppHint.isNullOrBlank() }
        if (validAppCount < 2) return emptyList()

        return parsedList
    }

    private fun splitImplicitTwoStageDataTransfer(sentence: String): List<String> {
        val m = Regex("^(.+?)(?:，|,|\\s+)?((?:把結果|把重點|把它)?(?:記到|寫進|存到|傳到|傳給|發給|分享到|分享給|記在).+)$").find(sentence)
        if (m != null) {
            val part1 = m.groupValues[1].trim()
            val part2 = m.groupValues[2].trim()
            if (part1.length >= 3 && part2.length >= 3) {
                return listOf(part1, part2)
            }
        }
        return listOf(sentence)
    }

    private fun detectAppInClause(clause: String, context: Context?): String? {
        val lower = clause.lowercase()
        for ((keyword, canonicalApp) in EXPLICIT_APP_ALIASES) {
            if (lower.contains(keyword)) {
                return canonicalApp
            }
        }
        val openSourceApp = OpenSourceAiDatabaseManager.resolveAppByColloquialAlias(clause, context)
        if (openSourceApp != null) {
            return openSourceApp.canonicalLabel
        }
        if (context != null) {
            val matched = InstalledAppLauncher.resolveTargetAppForGoal(context, clause, null)
            if (matched != null && matched.second >= 0.85f) {
                return matched.first.label
            }
        }
        return null
    }

    private fun inferImplicitAppFromClause(clause: String): String? {
        val lower = clause.lowercase()
        return when {
            NOTE_AND_SAVE_VERBS.any { lower.contains(it) } || lower.contains("筆記") || lower.contains("備忘") -> "備忘錄"
            SHARE_AND_SEND_VERBS.any { lower.contains(it) } -> "LINE"
            lower.contains("查") || lower.contains("搜尋") || lower.contains("匯率") || lower.contains("天氣") || lower.contains("新聞") -> "Chrome"
            lower.contains("聽") || lower.contains("播放") || lower.contains("mv") -> "YouTube"
            lower.contains("藍牙") || lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("熱點") || lower.contains("亮度") -> "設定"
            else -> null
        }
    }

    private fun extractRecipientOrNoteEntry(clause: String, targetAppHint: String?): String? {
        val recipientMatch = Regex("(?:傳給|發給|分享給|告訴|傳LINE給|傳訊息給)\\s*([^，,。\\s]{1,10})").find(clause)
        if (recipientMatch != null) {
            val person = recipientMatch.groupValues[1].trim()
            if (person.isNotEmpty()) return person
        }
        val appLower = targetAppHint?.lowercase().orEmpty()
        if (appLower.contains("備忘") || appLower.contains("keep") || appLower.contains("note") || appLower.contains("筆記")) {
            return "新增"
        }
        return null
    }

    private fun stripLeadingAppOpenPrefix(clause: String, appHint: String?): String {
        var s = clause.trim()
        s = s.replace(Regex("^(?:請幫我|幫我|我要|我想|請)?\\s*(?:先|再|然後|接著)?\\s*(?:去|到|打開|開啟|進入|切換到)\\s*"), "").trim()
        if (!appHint.isNullOrBlank()) {
            for ((alias, canonical) in EXPLICIT_APP_ALIASES) {
                if (canonical.equals(appHint, ignoreCase = true) || alias.equals(appHint, ignoreCase = true)) {
                    if (s.lowercase().startsWith(alias)) {
                        s = s.substring(alias.length).trim()
                    }
                }
            }
            if (s.lowercase().startsWith(appHint.lowercase())) {
                s = s.substring(appHint.length).trim()
            }
        }
        s = s.replace(Regex("^(?:裡面|裡|內|中|去|並|然後)\\s*"), "").trim()
        return s
    }

    private fun extractImplicitLookupTopic(actionPart: String): String {
        val m = Regex("(?:查詢|查一下|查看|查|找一下|找|搜尋|搜索|看看)\\s*(.+)$").find(actionPart.trim())
        if (m != null) {
            return m.groupValues[1]
                .replace(Regex("(?:並複製|然後複製|的結果)$"), "")
                .trim()
        }
        return ""
    }
}
