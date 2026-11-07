package com.example.newandroidapp.stability

import com.example.newandroidapp.ai.AiActionType
import com.example.newandroidapp.vision.Stage2ConvergedNode

/**
 * 終極操作意圖動詞枚舉
 */
enum class TerminalVerb {
    PLAY,         // 聽/放/播/查歌/買茶歌/點歌
    SEARCH_FIND,  // 查/找/搜/查詢/搜尋
    BUY,          // 買/購買/下單
    TOGGLE,       // 開/關/切換
    NAVIGATE,     // 去/進/打開
    TAP_BUTTON,   // 點擊/點選/按一下畫面按鈕 (c+B)
    UNKNOWN
}

/**
 * 逆向回推階級枚舉
 */
enum class BackwardChainLevel {
    LEVEL_0_DIRECT_TARGET,        // 目標已在主內容區
    LEVEL_1_SELECT_RESULT,        // 搜尋已送出，挑選結果/篩選籤
    LEVEL_2_INPUT_KEYWORD,        // 真實輸入框打字輸入並送出
    LEVEL_3A_EXPAND_SEARCH_BOX,   // 頂部假搜尋框/搜尋列展開真實輸入框
    LEVEL_3B_BOTTOM_SEARCH_TAB,   // 底部導覽列搜尋分頁 Tab
    LEVEL_4_HIERARCHICAL_FALLBACK // 階層式父分類回推
}

/**
 * 終極目標結構化提煉規格
 */
data class TerminalGoalSpec(
    val appHint: String?,
    val terminalVerb: TerminalVerb,
    val targetEntity: String,
    val followUpAction: String = "",
    val rawGoal: String = ""
)

/**
 * 逆向回推評估決策結果
 */
data class BackwardChainEvaluation(
    val level: BackwardChainLevel,
    val actionType: AiActionType,
    val targetNode: Stage2ConvergedNode?,
    val inputText: String? = null,
    val confidence: Float = 0.0f,
    val reasoningTrace: String = ""
)

/**
 * 通用逆向目標回推思維引擎 (Backward-Chaining Goal-Conditioned Planner)
 * 當使用者目標實體未出現在當前畫面時，自動從終極目標逆向推演前置搜尋操作鏈：
 * Level 3B (底部搜尋分頁) -> Level 3A (頂部假搜尋框) -> Level 2 (真實輸入框) -> Level 1/0 (點選目標)
 * 100% 自研無第三方依賴，嚴格遵守禁點語音輸入與零 Emoji 規範。
 */
object BackwardChainingPlanner {

    private val KNOWN_APPS = listOf(
        "spotify" to "Spotify",
        "youtube" to "YouTube",
        "yt" to "YouTube",
        "蝦皮" to "蝦皮",
        "shopee" to "蝦皮",
        "netflix" to "Netflix",
        "google maps" to "Google 地圖",
        "google map" to "Google 地圖",
        "googlemaps" to "Google 地圖",
        "googlemap" to "Google 地圖",
        "google 地圖" to "Google 地圖",
        "google地圖" to "Google 地圖",
        "谷歌地圖" to "Google 地圖",
        "gmap" to "Google 地圖",
        "maps" to "Google 地圖",
        "高德地圖" to "高德地圖",
        "地圖" to "Google 地圖",
        "line" to "LINE",
        "google瀏覽器" to "Chrome",
        "google 瀏覽器" to "Chrome",
        "谷歌瀏覽器" to "Chrome",
        "chrome瀏覽器" to "Chrome",
        "chrome" to "Chrome",
        "瀏覽器" to "Chrome",
        "google" to "Google",
        "設定" to "設定",
        "settings" to "設定",
        "momo" to "momo",
        "apple music" to "Apple Music",
        "instagram" to "Instagram",
        "ig" to "Instagram",
        "threads" to "Threads",
        "facebook" to "Facebook",
        "fb" to "Facebook",
        "tiktok" to "TikTok",
        "抖音" to "抖音",
        "小紅書" to "小紅書",
        "gmail" to "Gmail",
        "信箱" to "Gmail",
        "電子郵件" to "Gmail"
    )

    private val RANDOM_OR_POSITIONAL_SELECTION_TOKENS = listOf(
        "隨機", "隨便", "任選", "任一", "任意", "挑一個", "選一個", "點一個", "開一個", "看一個",
        "挑一封", "選一封", "點一封", "開一封", "看一封", "選一則", "點一則", "選一部", "點一部",
        "第一個", "第一封", "第一則", "第一部", "第一篇", "第一條",
        "第二個", "第二封", "第二則", "第二部", "第三個", "第三封",
        "最上面", "最下面", "最後一個", "最後一封", "最新一封", "最新一個", "random"
    )

    private val GENERIC_CONTAINER_TARGET_WORDS = setOf(
        "mail", "email", "e-mail", "信", "信件", "郵件", "電子郵件",
        "訊息", "消息", "聊天", "貼文", "文章", "通知", "項目", "內容",
        "一個mail", "一封mail", "一個郵件", "一封郵件", "一封信", "一個信件",
        "一個人", "一個好友", "一個聯絡人", "一個聊天室", "一個對話", "一個群組"
    )

    /**
     * 判斷目標詞是否為「隨機挑選 / 序數位置挑選 / 泛稱容器項目」（例如：「隨機選一個mail來點」、「點第一封信」）。
     * 此類指令屬於在當前畫面直接挑選列表項目 (Direct List Selection)，嚴禁誤觸發關鍵字搜尋！
     * 注意：若指令同時帶有明確的搜尋/播放/購買動詞與具體關鍵字（例如：「搜尋 晴天 並播放第一個」、「去youtube查周杰倫點第一個」），
     * 代表「第一個」只是搜尋完成後的後續動作，並非純列表挑選，應回傳 false！
     */
    fun isGenericOrPositionalSelectionTarget(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return false

        // 若指令明確包含「搜尋/查找/播放/買 + 具體關鍵字 + 並/然後 + 第一個」，絕非純列表位置挑選
        val explicitSearchBeforeFollowUp = Regex(
            "(?:搜尋|搜索|查找|查詢|查一下|搜一下|找一下|去搜|search|find|買茶歌|茶歌|查歌|找歌|聽歌|放歌|點歌|我想聽|幫我放|我想看|查|找|搜)\\s*([^\\s，,。！!？?]{1,24})"
        ).find(clean)
        if (explicitSearchBeforeFollowUp != null) {
            val extractedTopic = explicitSearchBeforeFollowUp.groupValues[1]
                .replace(Regex("(並|並且|然後|接著|再|點|播放).*$"), "")
                .trim()
            if (extractedTopic.isNotEmpty() &&
                extractedTopic !in GENERIC_CONTAINER_TARGET_WORDS &&
                RANDOM_OR_POSITIONAL_SELECTION_TOKENS.none { extractedTopic == it }
            ) {
                return false
            }
        }

        if (RANDOM_OR_POSITIONAL_SELECTION_TOKENS.any { clean.contains(it) }) {
            return true
        }
        val strippedAction = clean
            .replace(Regex("(來點|點擊|點開|點選|打開|開啟|查看|看看|來聽|來看|點一下|按一下)$"), "")
            .replace(Regex("^(去|在|到|進|進入|打開|開啟|點|點擊|點選|點開|選|選擇|挑|挑選)\\s*"), "")
            .trim()
        if (strippedAction in GENERIC_CONTAINER_TARGET_WORDS) {
            return true
        }
        return false
    }

    private val ORAL_PREFIXES = listOf(
        "請幫我", "麻煩幫我", "可以幫我", "幫我", "我想", "我要", "請", "去", "在", "到", "上", "用", "把"
    )

    private val APP_OPEN_PREFIXES = listOf(
        "打開", "開啟", "進入", "前往", "啟動", "切到", "切換到"
    )

    private val FOLLOW_UP_CONNECTORS = listOf(
        "並且", "并且", "並", "并", "然後", "然后", "接著", "接着", "再來", "再来", "之後", "之后", "再", "，", ",", "->"
    )

    // 音樂/播放意圖同音詞與口語前綴（含語音辨識同音「買茶歌」）
    private val PLAY_VERB_PREFIXES = listOf(
        "買茶歌", "茶歌", "查歌", "找歌", "聽歌", "放歌", "搜歌", "放首歌", "聽首歌", "播首歌", "來一首歌", "來首歌",
        "點一首歌", "點一首", "點首歌", "點播一首", "點播", "點歌", "來一首", "來首", "播放", "點",
        "放一首", "播一首", "聽一首", "查一首", "找一首", "聽", "放", "播"
    )

    // 搜尋意圖前綴
    private val SEARCH_VERB_PREFIXES = listOf(
        "搜尋一下", "搜索一下", "查找一下", "查詢一下", "查查看", "找看看", "搜看看",
        "搜尋", "搜索", "查找", "查詢", "查一下", "找一下", "搜一下", "問一下", "找", "查", "搜"
    )

    // 購買意圖前綴
    private val BUY_VERB_PREFIXES = listOf(
        "幫我買", "找商品", "買", "購買", "下單", "購物", "訂購"
    )

    // 開關切換意圖前綴
    private val TOGGLE_VERB_PREFIXES = listOf(
        "開關", "開啟", "關閉", "啟用", "停用", "切換"
    )

    // 導航意圖前綴
    private val NAVIGATE_VERB_PREFIXES = listOf(
        "前往", "進入", "導航到", "打開"
    )

    // 點擊畫面按鈕意圖前綴 (CommandLexicon c 標籤)
    private val TAP_VERB_PREFIXES = listOf(
        "點擊", "點選", "點開", "點進", "點入", "點一下", "按一下", "按下", "輕觸", "點按", "按個", "點極", "點及", "選擇", "挑選", "點", "按"
    )

    // 載體贅詞（歌曲/商品/影片等主詞前綴）
    private val CARRIER_PREFIXES = listOf(
        "一首歌叫", "一首歌名為", "一首歌叫做", "一首歌是", "一首歌",
        "一首叫", "一首名為", "一首叫做",
        "歌曲叫", "歌曲名為", "歌曲叫做", "歌曲是", "歌曲",
        "歌叫", "歌名為", "歌叫做", "歌是",
        "這首歌叫", "這首歌名為", "這首歌叫做", "這首歌",
        "商品叫", "商品名為", "商品是", "商品",
        "影片叫", "影片名為", "影片是", "影片",
        "叫", "名為", "叫做"
    )

    // 成對引號與括號定義
    private val ENCLOSING_QUOTE_PAIRS = listOf(
        "「" to "」",
        "『" to "』",
        "\"" to "\"",
        "'" to "'",
        "“" to "”",
        "‘" to "’",
        "《" to "》",
        "【" to "】",
        "（" to "）",
        "(" to ")"
    )

    private val TRAILING_PUNCTUATION_REGEX = Regex("[！。，？?!.、…\\s]+$")
    private val LEADING_COLON_REGEX = Regex("^[：:\\s]+")
    private val TRAILING_UI_BUTTON_MODIFIER_REGEX = Regex(
        "(?:那個|這個|那顆|這顆|那個|該)?(?:按鈕|按鍵|圖示|圖標|鍵|一下)+$"
    )

    fun stripTrailingUiButtonModifierSuffix(rawTarget: String): String {
        val trimmed = rawTarget.trim()
        if (trimmed.isEmpty()) return ""
        val stripped = trimmed.replace(TRAILING_UI_BUTTON_MODIFIER_REGEX, "").trim()
        return stripped.ifEmpty { trimmed }
    }

    private fun stripEnclosingQuotesAndBrackets(input: String): String {
        var res = input.trim()
        var changed = true
        while (changed) {
            changed = false
            val withoutPunct = res.replace(TRAILING_PUNCTUATION_REGEX, "").trim()
            for ((open, close) in ENCLOSING_QUOTE_PAIRS) {
                if (res.startsWith(open) && res.endsWith(close) && res.length >= open.length + close.length) {
                    res = res.substring(open.length, res.length - close.length).trim()
                    changed = true
                    break
                }
                if (res.startsWith(open) && withoutPunct.endsWith(close) && withoutPunct.length >= open.length + close.length) {
                    res = withoutPunct.substring(open.length, withoutPunct.length - close.length).trim()
                    changed = true
                    break
                }
            }
            if (!changed && res.isNotEmpty() && "「『\"'“‘《【（(".contains(res.first())) {
                res = res.substring(1).trim()
                changed = true
            }
        }
        return res
    }

    // 【優化 #1：委派至全域單一詞庫 UiKeywordLexicon】
    val TOP_SEARCH_BOX_KEYWORDS: List<String>
        get() = UiKeywordLexicon.TOP_SEARCH_ENTRY_KEYWORDS

    val BOTTOM_SEARCH_TAB_KEYWORDS: List<String>
        get() = UiKeywordLexicon.BOTTOM_SEARCH_TAB_KEYWORDS

    /**
     * 從自然語言/語音辨識指令中提煉終極目標規格
     * 支援口語前綴過濾、同音詞消解（例如「買茶歌」-> PLAY）、目標 App 辨識與載體贅詞剝離
     */
    fun extractTerminalGoalSpec(rawGoal: String): TerminalGoalSpec {
        val rawTrimmed = rawGoal.trim()
        if (rawTrimmed.isEmpty()) {
            return TerminalGoalSpec(
                appHint = null,
                terminalVerb = TerminalVerb.UNKNOWN,
                targetEntity = "",
                followUpAction = "",
                rawGoal = ""
            )
        }
        val trimmed = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager
            .correctAsrHomophonesInCommand(rawTrimmed)
            .trim()

        var working = stripEnclosingQuotesAndBrackets(trimmed)
        var extractedFollowUp = ""

        // 1. 切分後續動作（例如：「並播放第一首」）
        for (connector in FOLLOW_UP_CONNECTORS) {
            val idx = working.indexOf(connector)
            if (idx > 0 && idx < working.length - 1) {
                val candidateFollow = working.substring(idx + connector.length).trim()
                if (candidateFollow.isNotEmpty()) {
                    extractedFollowUp = candidateFollow
                    working = working.substring(0, idx).trim()
                    break
                }
            }
        }

        // 2. 移除開頭口語前綴（例如：「去」、「請幫我」）
        working = stripLeadingOralPrefixes(working)

        // 3. 提取目標 App 名稱（支援「打開Spotify」、「開啟蝦皮」或直接「去Spotify」）
        var extractedAppHint: String? = null
        for (openPrefix in APP_OPEN_PREFIXES) {
            if (working.startsWith(openPrefix, ignoreCase = true)) {
                val candidateAppRest = working.substring(openPrefix.length).trim()
                val lowerCandidate = candidateAppRest.lowercase()
                for ((appToken, appLabel) in KNOWN_APPS) {
                    if (lowerCandidate.startsWith(appToken)) {
                        extractedAppHint = appLabel
                        working = candidateAppRest.substring(appToken.length).trim()
                        break
                    }
                }
                if (extractedAppHint != null) break
            }
        }
        if (extractedAppHint == null) {
            val lowerWorking = working.lowercase()
            for ((appToken, appLabel) in KNOWN_APPS) {
                if (lowerWorking.startsWith(appToken)) {
                    extractedAppHint = appLabel
                    working = working.substring(appToken.length).trim()
                    break
                }
            }
        }

        // 移除 App 後可能殘留的二次口語前綴
        working = stripLeadingOralPrefixes(working)

        // 4. 判斷意圖動詞並消解同音詞
        var extractedVerb = TerminalVerb.UNKNOWN

        // 優先比對音樂播放同音詞（「買茶歌」、「茶歌」、「查歌」、「聽歌」、「點歌」等）
        for (verbToken in PLAY_VERB_PREFIXES) {
            if (verbToken == "點") {
                val rawRem = working.removePrefix("點").trim()
                val cleanRem = stripTrailingUiButtonModifierSuffix(rawRem)
                val isUiBtnTarget = cleanRem.isNotEmpty() && (
                    com.example.newandroidapp.ai.CommandLexiconEngine.isKnownUiButtonNoun(cleanRem) ||
                        com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(cleanRem) != null
                    )
                if (working.startsWith("點擊") ||
                    working.startsWith("點選") ||
                    working.startsWith("點按") ||
                    working.startsWith("點開") ||
                    working.startsWith("點進") ||
                    working.startsWith("點入") ||
                    working.startsWith("點一下") ||
                    working.startsWith("點極") ||
                    working.startsWith("點及") ||
                    working.startsWith("點一封") ||
                    working.startsWith("點一個") ||
                    working.startsWith("點第一") ||
                    isUiBtnTarget ||
                    isGenericOrPositionalSelectionTarget(working)
                ) {
                    continue
                }
            }
            if (working.startsWith(verbToken, ignoreCase = true)) {
                extractedVerb = TerminalVerb.PLAY
                val remainder = working.substring(verbToken.length).trim()
                working = if (remainder.isEmpty() && verbToken.endsWith("歌")) {
                    "音樂"
                } else {
                    remainder
                }
                break
            }
        }

        // 若非播放，優先比對明確「點擊按鈕 (TAP_BUTTON)」動詞（防止「點擊行車路線」落入 Google 地圖預設搜尋）
        if (extractedVerb == TerminalVerb.UNKNOWN) {
            for (verbToken in TAP_VERB_PREFIXES) {
                if (working.startsWith(verbToken, ignoreCase = true)) {
                    val remainder = working.substring(verbToken.length).trim()
                    if (remainder.isNotEmpty()) {
                        extractedVerb = TerminalVerb.TAP_BUTTON
                        working = remainder
                        break
                    }
                }
            }
        }

        // 若非播放/點擊，比對搜尋動詞
        if (extractedVerb == TerminalVerb.UNKNOWN) {
            for (verbToken in SEARCH_VERB_PREFIXES) {
                if (working.startsWith(verbToken, ignoreCase = true)) {
                    extractedVerb = TerminalVerb.SEARCH_FIND
                    working = working.substring(verbToken.length).trim()
                    break
                }
            }
        }

        // 若非搜尋，比對購買動詞
        if (extractedVerb == TerminalVerb.UNKNOWN) {
            for (verbToken in BUY_VERB_PREFIXES) {
                if (working.startsWith(verbToken, ignoreCase = true)) {
                    extractedVerb = TerminalVerb.BUY
                    working = working.substring(verbToken.length).trim()
                    break
                }
            }
        }

        // 若非購買，比對開關動詞
        if (extractedVerb == TerminalVerb.UNKNOWN) {
            for (verbToken in TOGGLE_VERB_PREFIXES) {
                if (working.startsWith(verbToken, ignoreCase = true)) {
                    extractedVerb = TerminalVerb.TOGGLE
                    working = working.substring(verbToken.length).trim()
                    break
                }
            }
        }

        // 若非開關，比對導航動詞
        if (extractedVerb == TerminalVerb.UNKNOWN) {
            for (verbToken in NAVIGATE_VERB_PREFIXES) {
                if (working.startsWith(verbToken, ignoreCase = true)) {
                    extractedVerb = TerminalVerb.NAVIGATE
                    working = working.substring(verbToken.length).trim()
                    break
                }
            }
        }

        // 5. 剝離載體贅詞（「一首歌叫」、「歌曲叫」、「商品叫」等）
        for (carrier in CARRIER_PREFIXES) {
            if (working.startsWith(carrier, ignoreCase = true)) {
                working = working.substring(carrier.length).trim()
                break
            }
        }

        // 6. 清理目標實體的引號、冒號與尾隨標點符號
        var cleanTarget = working.trim()
        cleanTarget = cleanTarget.replace(LEADING_COLON_REGEX, "")
        cleanTarget = cleanTarget.replace(TRAILING_PUNCTUATION_REGEX, "").trim()

        var quoteCleaned = true
        while (quoteCleaned) {
            quoteCleaned = false
            for ((open, close) in ENCLOSING_QUOTE_PAIRS) {
                if (cleanTarget.startsWith(open) && cleanTarget.endsWith(close) && cleanTarget.length >= open.length + close.length) {
                    cleanTarget = cleanTarget.substring(open.length, cleanTarget.length - close.length).trim()
                    quoteCleaned = true
                    break
                }
            }
            if (!quoteCleaned && cleanTarget.isNotEmpty()) {
                val leadingQuotes = "「『\"'“‘《【（("
                val trailingQuotes = "」』\"'”’》】）)"
                if (leadingQuotes.contains(cleanTarget.first())) {
                    cleanTarget = cleanTarget.substring(1).trim()
                    quoteCleaned = true
                }
                if (cleanTarget.isNotEmpty() && trailingQuotes.contains(cleanTarget.last())) {
                    cleanTarget = cleanTarget.substring(0, cleanTarget.length - 1).trim()
                    quoteCleaned = true
                }
            }
            if (quoteCleaned) {
                cleanTarget = cleanTarget.replace(LEADING_COLON_REGEX, "")
                cleanTarget = cleanTarget.replace(TRAILING_PUNCTUATION_REGEX, "").trim()
            }
        }

        // 7. 若末尾未用連接詞但夾帶動作（例如：「晴天播放第一首」、「臺灣高鐵時刻表點進網站」）
        val tailActionRegex = Regex("^(.*)(播放第一[個首]|點擊第一[個首]|點第一[個首]|點進網站|點進官網|點進網頁|進入網站|進入官網|點開網站|加入購物車)$")
        val tailMatch = tailActionRegex.find(cleanTarget)
        if (tailMatch != null && tailMatch.groupValues[1].isNotBlank()) {
            cleanTarget = tailMatch.groupValues[1].trim()
            val tailAction = tailMatch.groupValues[2].trim()
            extractedFollowUp = if (extractedFollowUp.isEmpty()) tailAction else "$tailAction $extractedFollowUp"
        }

        // 8. 確保移除任何殘留的尾隨標點符號與空白，並還原口語按鈕後綴（如「點擊路線那個按鈕」->「路線」）
        cleanTarget = cleanTarget.replace(TRAILING_PUNCTUATION_REGEX, "").trim()
        val strippedButtonStem = stripTrailingUiButtonModifierSuffix(cleanTarget)
        if (strippedButtonStem.isNotEmpty() && strippedButtonStem != cleanTarget) {
            val isStemKnownButton = com.example.newandroidapp.ai.CommandLexiconEngine.isKnownUiButtonNoun(strippedButtonStem) ||
                com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(strippedButtonStem) != null
            if (extractedVerb == TerminalVerb.TAP_BUTTON || isStemKnownButton) {
                cleanTarget = strippedButtonStem
                if (isStemKnownButton && (extractedVerb == TerminalVerb.UNKNOWN || extractedVerb == TerminalVerb.NAVIGATE)) {
                    extractedVerb = TerminalVerb.TAP_BUTTON
                }
            }
        }

        // 9. 若為疑問句型（如「誰是蔣介石」、「什麼是相對論」），自動剝離疑問詞並設定為 Chrome 搜尋
        val questionTopic = com.example.newandroidapp.ai.LocalKnowledgeAndReasoningEngine.extractQuestionTopic(cleanTarget)
        if (questionTopic.isQuestionOrLookup && questionTopic.cleanTopic.isNotEmpty()) {
            cleanTarget = questionTopic.cleanTopic
            if (extractedVerb == TerminalVerb.UNKNOWN) {
                extractedVerb = TerminalVerb.SEARCH_FIND
            }
            if (extractedAppHint == null) {
                extractedAppHint = if (questionTopic.isLocationLookup) "Google 地圖" else "Chrome"
            }
        }

        // 10. 若目標為「隨機挑選 / 序數挑選 / 泛稱容器項目」（如「隨機選一個mail來點」、「點第一封信」），強制歸類為 NAVIGATE（直接列表選取，非關鍵字搜尋）
        if (isGenericOrPositionalSelectionTarget(cleanTarget) || isGenericOrPositionalSelectionTarget(trimmed)) {
            extractedVerb = TerminalVerb.NAVIGATE
        }

        // 10b. 若目標本身在 CommandLexicon 屬於明確 UI 按鈕名詞 (B，如「行車路線」、「規劃路線」、「路線」、「開始導航」)，強制歸類為 TAP_BUTTON，嚴禁當成搜尋關鍵字！
        if ((extractedVerb == TerminalVerb.UNKNOWN || extractedVerb == TerminalVerb.NAVIGATE) &&
            cleanTarget.isNotEmpty() &&
            com.example.newandroidapp.ai.CommandLexiconEngine.isKnownUiButtonNoun(cleanTarget)
        ) {
            extractedVerb = TerminalVerb.TAP_BUTTON
        }

        // 11. 若尚未確認動詞但目標實體存在，根據 App 類型推論預設動詞
        if (extractedVerb == TerminalVerb.UNKNOWN && cleanTarget.isNotEmpty()) {
            extractedVerb = when (extractedAppHint) {
                "Spotify", "Apple Music", "YouTube", "Netflix" -> TerminalVerb.PLAY
                "蝦皮", "momo" -> TerminalVerb.BUY
                "Chrome", "Google", "Google 地圖" -> TerminalVerb.SEARCH_FIND
                "設定", "Gmail" -> TerminalVerb.NAVIGATE
                else -> TerminalVerb.UNKNOWN
            }
        }

        return TerminalGoalSpec(
            appHint = extractedAppHint,
            terminalVerb = extractedVerb,
            targetEntity = cleanTarget,
            followUpAction = extractedFollowUp,
            rawGoal = trimmed
        )
    }

    private fun stripLeadingOralPrefixes(input: String): String {
        var res = input.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in ORAL_PREFIXES) {
                if (res.startsWith(prefix, ignoreCase = true)) {
                    val next = res.substring(prefix.length).trim()
                    if (next.isNotEmpty() && next != res) {
                        res = next
                        changed = true
                        break
                    }
                }
            }
        }
        return res
    }

    /**
     * 五階逆向因果推演：
     * Level 0: 檢查主內容區 (y in 180..780) 是否已存在 targetEntity -> 直接點擊目標
     * Level 1: 若已送出搜尋 (hasSubmittedQuery)，於結果列表挑選最佳目標
     * Level 2: 畫面已有真實可輸入之 INP (EditText) -> 輸入關鍵字並送出
     * Level 3A: 畫面頂部 (y <= 360) 有假搜尋框或搜尋按鈕 -> 點擊展開輸入框
     * Level 3B: 畫面底部導覽列 (y >= 780) 有搜尋分頁 Tab -> 點擊切換進入搜尋頁
     * Level 4: 階層式父分類回推備援
     */
    fun evaluateBackwardChain(
        goalSpec: TerminalGoalSpec,
        nodes: List<Stage2ConvergedNode>,
        @Suppress("UNUSED_PARAMETER") packageName: String,
        hasSubmittedQuery: Boolean = false,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): BackwardChainEvaluation {
        val target = goalSpec.targetEntity.trim().lowercase()
        val effW = screenWidth.coerceAtLeast(1)
        val effH = screenHeight.coerceAtLeast(1)

        // 嚴格安全過濾：排除所有語音搜尋/麥克風/鏡頭元件
        val safeNodes = nodes.filter { !InAppOperationPlanner.isForbiddenVoiceOrCameraNode(it) }

        val hasActiveUnsubmittedInput = !hasSubmittedQuery && safeNodes.any { it.shortTypeTag == "INP" }

        // 決策層把關：僅當明確具備搜尋/播放/購買意圖，且非 TAP_BUTTON 點擊按鈕動作、非 UI 按鈕名詞 (B)、非「隨機/序數/泛稱列表挑選」時，才允許展開搜尋框或填入文字！
        val isUiButtonTarget = com.example.newandroidapp.ai.CommandLexiconEngine.isKnownUiButtonNoun(goalSpec.targetEntity)
        val allowSearchChain = (
            goalSpec.terminalVerb == TerminalVerb.SEARCH_FIND ||
                goalSpec.terminalVerb == TerminalVerb.PLAY ||
                goalSpec.terminalVerb == TerminalVerb.BUY
            ) &&
            goalSpec.terminalVerb != TerminalVerb.TAP_BUTTON &&
            !isUiButtonTarget &&
            !isGenericOrPositionalSelectionTarget(goalSpec.targetEntity) &&
            !isGenericOrPositionalSelectionTarget(goalSpec.rawGoal)

        // =========================================================================
        // 【Level 0 檢查：目標實體已在主內容區（支援 TAP_BUTTON 同義詞與 UI Role 對齊）】
        // 注意：
        // 1. 若當前處於未送出搜尋的輸入頁 (hasActiveUnsubmittedInput)，或屬於明確「搜尋/查找 (SEARCH_FIND)」動詞且尚未送出搜尋，
        //    僅在出現「100% 完全相同」之精確詞時才允許直點，防止在首頁因為推薦卡片或頻道名稱包含子字串（如查「AI」時首頁有「前往頻道：人民公园说AI」）就跳過搜尋框展開！
        // 2. 排除「前往頻道：」頭像按鈕避免誤點頻道頁而非影片/歌曲本體。
        // =========================================================================
        if (target.isNotEmpty() && !isGenericOrPositionalSelectionTarget(target)) {
            val normTarget = target.replace('臺', '台')
            val isTapButtonGoal = goalSpec.terminalVerb == TerminalVerb.TAP_BUTTON || isUiButtonTarget
            val expandedSynonyms = if (isTapButtonGoal) {
                com.example.newandroidapp.ai.LookaheadPlannerEngine.expandTargetSynonyms(target)
                    .map { it.lowercase().replace('臺', '台') }
                    .toSet()
            } else {
                emptySet()
            }
            val targetUiRole = if (isTapButtonGoal) {
                com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(target)
            } else {
                null
            }
            val requireExactMatchOnly = !isTapButtonGoal && (
                hasActiveUnsubmittedInput ||
                    (goalSpec.terminalVerb == TerminalVerb.SEARCH_FIND && !hasSubmittedQuery)
                )
            val directCandidates = safeNodes.filter { node ->
                val cleanLbl = node.label.trim()
                val normLbl = cleanLbl.lowercase().replace('臺', '台')
                val isTopWebSearchEcho = hasSubmittedQuery && !isTapButtonGoal && node.gridTapPoint.y <= 265 && normLbl == normTarget
                val yInBounds = if (isTapButtonGoal) node.gridTapPoint.y in 80..940 else node.gridTapPoint.y in 180..780
                val xInBounds = if (isTapButtonGoal) true else node.gridTapPoint.x < 820
                node.shortTypeTag != "INP" &&
                    !isTopWebSearchEcho &&
                    yInBounds &&
                    xInBounds &&
                    !isSearchEntryNode(node) &&
                    !cleanLbl.startsWith("前往頻道") &&
                    if (requireExactMatchOnly) {
                        normLbl.equals(normTarget, ignoreCase = true)
                    } else {
                        val directHit = normLbl.contains(normTarget) ||
                            "${node.inlineStatePrefix}$cleanLbl".lowercase().replace('臺', '台').contains(normTarget)
                        val synonymHit = isTapButtonGoal && expandedSynonyms.any { syn ->
                            syn.length >= 2 && (normLbl == syn || normLbl.contains(syn))
                        }
                        val roleHit = isTapButtonGoal && targetUiRole != null &&
                            com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.resolveUiActionRoleByTermOrIcon(cleanLbl) == targetUiRole
                        directHit || synonymHit || roleHit
                    }
            }
            val directTargetNode = directCandidates.maxByOrNull { node ->
                InAppOperationPlanner.scoreQueryMatchQuality(node.label, target) - (node.gridTapPoint.y * 0.01f)
            } ?: if (isTapButtonGoal) {
                com.example.newandroidapp.ai.CommandLexiconEngine.findBestNodeByIconHint(
                    clauseOrGoal = goalSpec.rawGoal.ifEmpty { target },
                    nodes = safeNodes,
                    screenWidth = effW,
                    screenHeight = effH
                )
            } else {
                null
            }
            if (directTargetNode != null) {
                val iconCtx = if (isTapButtonGoal) {
                    com.example.newandroidapp.ai.CommandLexiconEngine.resolveIconInContext(
                        iconNameOrLabel = directTargetNode.label,
                        bounds = directTargetNode.convergedBounds,
                        screenWidth = effW,
                        screenHeight = effH,
                        allNodes = safeNodes
                    )
                } else {
                    null
                }
                val resolvedActionType = if (iconCtx != null && iconCtx.primaryActionCode == 'r' &&
                    (goalSpec.rawGoal.contains("上滑") || goalSpec.rawGoal.contains("拉開") || goalSpec.rawGoal.contains("展開抽屜"))
                ) {
                    AiActionType.SCROLL_UP
                } else {
                    AiActionType.TAP
                }
                return BackwardChainEvaluation(
                    level = BackwardChainLevel.LEVEL_0_DIRECT_TARGET,
                    actionType = resolvedActionType,
                    targetNode = directTargetNode,
                    inputText = null,
                    confidence = 0.96f,
                    reasoningTrace = "[逆向目標回推] 終極目標「${goalSpec.targetEntity}」已在主內容區 (#${directTargetNode.shortMarkId}「${directTargetNode.label}」)，執行 $resolvedActionType"
                )
            }
        }

        // =========================================================================
        // 【Level 1 檢查：搜尋已送出，等待選取結果】
        // =========================================================================
        if (allowSearchChain && hasSubmittedQuery && target.isNotEmpty()) {
            val contentResults = safeNodes.filter { node ->
                node.shortTypeTag != "INP" &&
                    node.gridTapPoint.y in 180..860 &&
                    node.gridTapPoint.x < 820 &&
                    !isSearchEntryNode(node) &&
                    node.label.trim().isNotEmpty()
            }
            val matchingResults = contentResults.filter {
                it.label.lowercase().contains(target)
            }
            val bestMatchingResult = matchingResults.maxByOrNull { node ->
                InAppOperationPlanner.scoreQueryMatchQuality(node.label, target) - (node.gridTapPoint.y * 0.02f)
            } ?: contentResults.maxByOrNull { node ->
                scoreContentCardCandidate(node)
            }

            if (bestMatchingResult != null) {
                return BackwardChainEvaluation(
                    level = BackwardChainLevel.LEVEL_1_SELECT_RESULT,
                    actionType = AiActionType.TAP,
                    targetNode = bestMatchingResult,
                    inputText = null,
                    confidence = 0.94f,
                    reasoningTrace = "[逆向目標回推] 已送出搜尋「${goalSpec.targetEntity}」，在結果列表挑選最高分目標 (#${bestMatchingResult.shortMarkId}「${bestMatchingResult.label}」)"
                )
            }
        }

        // =========================================================================
        // 【Level 2 檢查：畫面已有真實 INP 輸入框（優化 #3：以多維度計分取代 firstOrNull）】
        // =========================================================================
        if (allowSearchChain) {
            val realInputNode = safeNodes
                .filter { it.shortTypeTag == "INP" }
                .maxByOrNull { scoreInputBoxCandidate(it) }
            if (realInputNode != null && target.isNotEmpty()) {
                return BackwardChainEvaluation(
                    level = BackwardChainLevel.LEVEL_2_INPUT_KEYWORD,
                    actionType = AiActionType.INPUT_TEXT,
                    targetNode = realInputNode,
                    inputText = goalSpec.targetEntity,
                    confidence = 0.98f,
                    reasoningTrace = "[逆向目標回推] 畫面已有輸入框 (#${realInputNode.shortMarkId})，填入關鍵字「${goalSpec.targetEntity}」並送出"
                )
            }

            // =========================================================================
            // 【Level 3A 檢查：畫面頂部 (y <= 360) 假搜尋框或搜尋列】
            // 採用多維度計分挑選最佳搜尋入口（優先鎖定 predictedFunctionRole == SEARCH_ENTRY/SEARCH_INPUT_BOX 與精確「搜尋」按鈕）
            // =========================================================================
            val rankedTopSearchCandidates = safeNodes
                .filter { node ->
                    node.shortTypeTag != "INP" &&
                        node.gridTapPoint.y <= 360 &&
                        isTopFakeSearchEntryNode(node)
                }
                .map { node -> node to scoreTopSearchEntryCandidate(node) }
                .sortedByDescending { it.second }
            val topFakeSearchBoxNode = rankedTopSearchCandidates.firstOrNull()?.first
            if (topFakeSearchBoxNode != null) {
                val topScore = rankedTopSearchCandidates.first().second
                val runnerUp = rankedTopSearchCandidates.getOrNull(1)
                val marginNote = if (runnerUp != null && (topScore - runnerUp.second) <= 15f) {
                    " (勝出分:${topScore.toInt()} vs 次佳#${runnerUp.first.shortMarkId}「${runnerUp.first.label}」:${runnerUp.second.toInt()})"
                } else ""
                val targetDesc = if (target.isNotEmpty()) "「${goalSpec.targetEntity}」" else "目標"
                return BackwardChainEvaluation(
                    level = BackwardChainLevel.LEVEL_3A_EXPAND_SEARCH_BOX,
                    actionType = AiActionType.TAP,
                    targetNode = topFakeSearchBoxNode,
                    inputText = null,
                    confidence = 0.95f,
                    reasoningTrace = "[逆向目標回推] 終極目標${targetDesc}不在畫面，點擊頂部假搜尋框/搜尋列 (#${topFakeSearchBoxNode.shortMarkId}「${topFakeSearchBoxNode.label}」) 展開真實輸入框$marginNote"
                )
            }

            // =========================================================================
            // 【Level 3B 檢查：畫面底部導覽列 (y >= 780) 搜尋分頁 Tab（優化 #3：以計分取代 firstOrNull）】
            // =========================================================================
            val bottomSearchTabNode = safeNodes
                .filter { node ->
                    node.shortTypeTag != "INP" &&
                        node.gridTapPoint.y >= 780 &&
                        isBottomSearchTabNode(node)
                }
                .maxByOrNull { node -> scoreBottomSearchTabCandidate(node) }
            if (bottomSearchTabNode != null) {
                val targetDesc = if (target.isNotEmpty()) "「${goalSpec.targetEntity}」" else "目標"
                return BackwardChainEvaluation(
                    level = BackwardChainLevel.LEVEL_3B_BOTTOM_SEARCH_TAB,
                    actionType = AiActionType.TAP,
                    targetNode = bottomSearchTabNode,
                    inputText = null,
                    confidence = 0.93f,
                    reasoningTrace = "[逆向目標回推] 終極目標${targetDesc}不在首頁，回推前置條件 L3B: 點擊底部「搜尋」分頁 (#${bottomSearchTabNode.shortMarkId}「${bottomSearchTabNode.label}」)"
                )
            }
        }

        // =========================================================================
        // 【Level 4 備援：無搜尋入口之階層式選單回推】
        // =========================================================================
        return BackwardChainEvaluation(
            level = BackwardChainLevel.LEVEL_4_HIERARCHICAL_FALLBACK,
            actionType = AiActionType.TAP,
            targetNode = null,
            inputText = null,
            confidence = 0.50f,
            reasoningTrace = "[逆向目標回推] 當前畫面無直接搜尋入口，回推階層式父分類或前瞻推演"
        )
    }

    private fun isTopFakeSearchEntryNode(node: Stage2ConvergedNode): Boolean {
        if (node.predictedFunctionRole == "FILTER_SORT_CHIP" || node.shortTypeTag == "CHK") {
            return false
        }
        if (node.predictedFunctionRole == "SEARCH_ENTRY" || node.predictedFunctionRole == "SEARCH_INPUT_BOX") {
            return true
        }
        val full = "${node.inlineStatePrefix}${node.label}".lowercase()
        if (TOP_SEARCH_BOX_KEYWORDS.any { full.contains(it) }) {
            return true
        }
        if (node.nanoDetCategory == "UI_SEARCH_BAR" && node.visualConfidence >= 0.50f) {
            return true
        }
        return false
    }

    fun scoreInputBoxCandidate(node: Stage2ConvergedNode): Float {
        var s = 40f
        if (node.gridTapPoint.y <= 240) s += 35f else if (node.gridTapPoint.y <= 420) s += 15f
        val full = "${node.inlineStatePrefix}${node.label}".lowercase()
        if (UiKeywordLexicon.isTopSearchEntryText(full)) s += 30f
        if (node.predictedFunctionRole == "SEARCH_INPUT_BOX") s += 30f * node.functionRoleConfidence
        return s
    }

    fun scoreContentCardCandidate(node: Stage2ConvergedNode): Float {
        var s = 20f
        if (node.predictedFunctionRole == "CONTENT_CARD_ITEM") s += 30f * node.functionRoleConfidence
        if (node.label.length >= 6) s += 15f
        s -= (node.gridTapPoint.y * 0.02f)
        return s
    }

    fun scoreTopSearchEntryCandidate(node: Stage2ConvergedNode): Float {
        var s = 0f
        val cleanLabel = node.label.trim().lowercase()
        if (UiKeywordLexicon.isExactSearchWord(cleanLabel)) {
            s += 80f
        } else if (cleanLabel.contains("搜尋") || cleanLabel.contains("搜索") || cleanLabel.contains("search")) {
            s += 45f
        }
        if (node.predictedFunctionRole == "SEARCH_ENTRY" || node.predictedFunctionRole == "SEARCH_INPUT_BOX") {
            s += 50f * node.functionRoleConfidence
        }
        if (node.predictedFunctionRole == "FILTER_SORT_CHIP" || node.shortTypeTag == "CHK") {
            s -= 90f
        }
        if (node.gridTapPoint.y <= 140) {
            s += 25f
        }
        return s
    }

    fun scoreBottomSearchTabCandidate(node: Stage2ConvergedNode): Float {
        var s = 20f
        val cleanLabel = node.label.trim().lowercase()
        if (UiKeywordLexicon.isExactSearchWord(cleanLabel)) {
            s += 50f
        } else if (UiKeywordLexicon.isBottomSearchTabText(cleanLabel)) {
            s += 25f
        }
        if (node.predictedFunctionRole == "SEARCH_ENTRY" || node.predictedFunctionRole == "NAV_EXPLORE_DISCOVER_TAB") {
            s += 30f * node.functionRoleConfidence
        }
        return s
    }

    private fun isBottomSearchTabNode(node: Stage2ConvergedNode): Boolean {
        val full = "${node.inlineStatePrefix}${node.label}".lowercase()
        val keywordMatch = BOTTOM_SEARCH_TAB_KEYWORDS.any { full.contains(it) }
        if (keywordMatch) return true
        if (node.nanoDetCategory == "UI_TAB_NAV" && node.visualConfidence >= 0.50f) {
            if (BOTTOM_SEARCH_TAB_KEYWORDS.any { full.contains(it) }) return true
        }
        return false
    }

    private fun isSearchEntryNode(node: Stage2ConvergedNode): Boolean {
        if (node.gridTapPoint.y <= 360 && isTopFakeSearchEntryNode(node)) return true
        if (node.gridTapPoint.y >= 780 && isBottomSearchTabNode(node)) return true
        return false
    }
}
