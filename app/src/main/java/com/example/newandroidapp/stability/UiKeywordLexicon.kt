package com.example.newandroidapp.stability

/**
 * 【優化 #1：全域 UI 關鍵字與詞彙單一事實來源 (Single Source of Truth)】
 *
 * 將原本分散於 `BackwardChainingPlanner`、`InAppOperationPlanner` 與 `VisionAccessibilityService`
 * 的搜尋入口、底部導覽搜尋分頁、送出按鈕、語音/鏡頭禁用詞、自動完成調整詞與瀏覽器工具列詞庫統一收斂管理，
 * 並強制短英文禁用詞使用單詞邊界 (`\b`) 與長標題卡片保護，杜絕各模組詞庫不一致或誤傷正常內容標題。
 */
object UiKeywordLexicon {

    // 1. 精確搜尋入口單詞（最高優先級加權）
    val EXACT_SEARCH_ENTRY_WORDS = setOf(
        "搜尋", "搜索", "search", "查找", "查詢"
    )

    // 2. 頂部假搜尋框與搜尋入口關鍵詞（嚴格排除頂部分類膠囊如 Podcast / 探索）
    val TOP_SEARCH_ENTRY_KEYWORDS = listOf(
        "想聽什麼", "what do you want to listen to", "藝人、歌曲", "想找什麼",
        "搜尋或輸入網址", "search or type url", "搜尋 google", "url_bar", "search_box",
        "搜尋", "搜索", "search", "查找", "輸入關鍵字", "找商品", "找影片", "找音樂", "找地點"
    )

    // 3. 底部導覽列搜尋分頁 Tab 關鍵詞
    val BOTTOM_SEARCH_TAB_KEYWORDS = listOf(
        "搜尋", "搜索", "search", "探索", "explore"
    )

    // 4. 搜尋送出按鈕關鍵詞
    val SUBMIT_SEARCH_KEYWORDS = listOf(
        "搜尋", "搜索", "search", "前往", "送出", "完成", "go", "enter", "執行搜尋"
    )

    // 5. 搜尋結果頁特徵分頁與標記詞（含瀏覽器、地圖、YouTube、影音與電商結果篩選膠囊）
    val SEARCH_RESULTS_PAGE_MARKERS = setOf(
        "搜尋結果", "全部", "圖片", "影片", "新聞", "總覽", "維基百科", "資訊速覽", "路線", "導航", "評論",
        "Shorts", "shorts", "未觀看", "已上傳", "頻道", "播放清單", "直播", "觀看次數", "萬次觀看", "熱門", "最新", "綜合", "最相關"
    )

    // 6. 嚴禁點擊之語音/麥克風/相機鏡頭中文與複合詞
    val FORBIDDEN_VOICE_AND_CAMERA_SUBSTRINGS = listOf(
        "語音搜尋", "語音輸入", "語音搜索", "語音查詢", "語音助手", "語音", "麥克風", "話筒",
        "按住說話", "點擊說話", "說出", "聽寫",
        "voice search", "voice input", "audio input", "audio_input",
        "智慧鏡頭", "鏡頭搜尋", "以圖搜尋", "拍照搜尋", "相機搜尋", "google lens",
        "camera search"
    )

    // 7. 嚴禁點擊之語音/鏡頭英文單詞（強制使用 \b 單詞邊界，防止 MicroDuck 被 "mic" 誤傷）
    val FORBIDDEN_VOICE_AND_CAMERA_EN_WORD_REGEX = Regex(
        "\\b(voice|microphone|speak|dictate|mic|speech|asr|lens)\\b",
        RegexOption.IGNORE_CASE
    )

    // 8. 嚴禁點擊之語音/鏡頭 Resource ID 字根
    val FORBIDDEN_VOICE_ID_TOKENS = listOf(
        "voice", "mic", "microphone", "speech", "asr", "audio_input", "lens", "camera_search"
    )

    // 9. 嚴禁誤點之搜尋建議右側「↖ 填入搜尋欄 / 調整搜尋字詞」或「X 清除文字」按鈕
    val FORBIDDEN_AUTOCOMPLETE_REFINE_OR_CLEAR_KEYWORDS = listOf(
        "調整搜尋", "填入搜尋", "填入查詢", "填入網址", "修改搜尋", "優化搜尋",
        "refine", "edit_query", "fill_into", "清除查詢", "清除搜尋", "清除文字", "刪除文字", "clear query", "clear text"
    )

    val FORBIDDEN_AUTOCOMPLETE_REFINE_ID_TOKENS = listOf(
        "refine", "edit_query", "delete_btn", "clear_btn", "clear_text", "cancel_btn"
    )

    // 10. 嚴禁在一般搜尋任務中誤觸之瀏覽器頂部工具列按鈕
    val FORBIDDEN_BROWSER_TOOLBAR_KEYWORDS = listOf(
        "開啟無痕視窗", "新增無痕分頁", "新增無痕視窗", "無痕視窗", "無痕模式", "無痕分頁",
        "新增分頁", "開啟新分頁", "建立新分頁", "查看 1 個分頁", "查看分頁", "切換分頁",
        "自訂及管理 google chrome", "自訂及管理", "瀏覽器選單", "new incognito tab", "incognito", "new tab"
    )

    val FORBIDDEN_BROWSER_TOOLBAR_ID_TOKENS = listOf(
        "optional_toolbar_button", "tab_switcher_button", "menu_button", "incognito", "new_tab_button"
    )

    fun isExactSearchWord(label: String): Boolean {
        val clean = label.trim().lowercase()
        if (clean in EXACT_SEARCH_ENTRY_WORDS) return true
        val normTw = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean)
        return normTw in EXACT_SEARCH_ENTRY_WORDS
    }

    fun isTopSearchEntryText(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return false
        if (TOP_SEARCH_ENTRY_KEYWORDS.any { lower.contains(it) }) return true
        val normTw = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(lower)
        return TOP_SEARCH_ENTRY_KEYWORDS.any { normTw.contains(it) }
    }

    fun isBottomSearchTabText(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return false
        if (BOTTOM_SEARCH_TAB_KEYWORDS.any { lower.contains(it) }) return true
        val normTw = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(lower)
        return BOTTOM_SEARCH_TAB_KEYWORDS.any { normTw.contains(it) }
    }

    fun isSearchSubmitText(label: String): Boolean {
        val clean = label.trim().lowercase()
        if (clean.isEmpty()) return false
        val normTw = com.example.newandroidapp.ai.OpenSourceAiDatabaseManager.normalizeToTaiwanTraditional(clean)
        return SUBMIT_SEARCH_KEYWORDS.any { kw ->
            clean == kw || clean == "執行$kw" || clean == "${kw}按鈕" ||
                normTw == kw || normTw == "執行$kw" || normTw == "${kw}按鈕"
        }
    }

    /**
     * 統一判定某段文字與 Resource ID 是否屬於禁點之「語音輸入 / 麥克風 / 智慧鏡頭 / 調整搜尋詞 / 清除文字」控制項。
     * - 長標題內容卡片（`cleanLabel.length > maxLabelLengthForVoiceBlock`）自動豁免，防止影片或商品標題含關鍵字遭誤殺。
     */
    fun isForbiddenVoiceCameraOrRefine(
        cleanLabel: String,
        fullTextWithPrefix: String = cleanLabel,
        resourceId: String = "",
        maxLabelLengthForVoiceBlock: Int = 28
    ): Boolean {
        if (cleanLabel.trim().length > maxLabelLengthForVoiceBlock) {
            return false
        }
        val lowerText = fullTextWithPrefix.trim().lowercase()
        if (FORBIDDEN_VOICE_AND_CAMERA_SUBSTRINGS.any { lowerText.contains(it) }) {
            return true
        }
        if (FORBIDDEN_VOICE_AND_CAMERA_EN_WORD_REGEX.containsMatchIn(lowerText)) {
            return true
        }
        if (FORBIDDEN_AUTOCOMPLETE_REFINE_OR_CLEAR_KEYWORDS.any { lowerText.contains(it) }) {
            return true
        }
        val idLower = resourceId.trim().lowercase()
        if (idLower.isNotEmpty()) {
            if (FORBIDDEN_VOICE_ID_TOKENS.any { idLower.contains(it) }) return true
            if (FORBIDDEN_AUTOCOMPLETE_REFINE_ID_TOKENS.any { idLower.contains(it) }) return true
        }
        return false
    }
}
