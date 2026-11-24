package com.wae.core.predicate

/**
 * 語言層詞彙的**執行期容器**（由 app 從 `ui_language_words.tsv` 載入）。
 *
 * 為什麼要有這一層：核心（core）不能讀檔案、也不能碰 Android，
 * 但也不能把「然後」「搜尋」這些詞寫死在程式裡（資料驅動原則）。
 * 所以：**core 只定義形狀，App 注入內容**。
 *
 * 預設全部為空 —— 沒有資料就不作用，絕不偷偷用內建詞表。
 */
object LanguageWords {

    @Volatile
    private var separators: List<String> = emptyList()

    @Volatile
    private var leads: List<String> = emptyList()

    @Volatile
    private var searchWords: Set<String> = emptySet()

    @Volatile
    private var labels: Map<String, String> = emptyMap()

    @Volatile
    private var verbs: List<String> = emptyList()

    @Volatile
    private var opens: List<String> = emptyList()

    @Volatile
    private var verbTypes: List<Pair<String, String>> = emptyList()

    /** 介詞（在／到／進…）與方位助詞（中／裡／内…），由 ui_grammar_particles.tsv 注入。 */
    @Volatile
    private var prefixes: List<String> = emptyList()

    @Volatile
    private var suffixes: List<String> = emptyList()

    /** 連接詞（並／並且…）：後面接的是「下一個動作」，不是同一句話的內容。 */
    @Volatile
    private var conjunctions: List<String> = emptyList()

    /** 容器詞（清單／列表／頁面…）：使用者會說「查看鬧鐘清單」，但畫面上的頁籤只寫「鬧鐘」。 */
    @Volatile
    private var containerWords: List<String> = emptyList()

    /** 網址列標記（搜尋或輸入網址…）：用來判斷「這是瀏覽器」，瀏覽器的返回是上一頁。 */
    @Volatile
    private var urlBarLabels: List<String> = emptyList()

    /** 溢出選單標籤（更多選項／更多／選單…）：找不到目標時先打開它再找。 */
    @Volatile
    private var menuLabels: List<String> = emptyList()

    /** 瀏覽型動詞（看／查看／瀏覽…）：代表「帶我去那一頁」，不是新增資料。 */
    @Volatile
    private var viewVerbs: List<String> = emptyList()

    /** 畫面用詞同義對照（使用者說的詞 → 畫面上的字），來自 LABEL_SYNONYM 資料列。 */
    @Volatile
    private var labelSynonyms: Map<String, String> = emptyMap()

    /** 返回詞（返回／上一頁／Back…）：用來判斷是不是卡在子頁面。 */
    @Volatile
    private var backLabels: List<String> = emptyList()

    /** 「第一個／第一筆／第一首…」：出現在搜尋詞之後代表要打開結果，而不是只看結果。 */
    @Volatile
    private var firstMarkers: List<String> = emptyList()

    /** 由 App 在啟動時注入（見 `PredicateLexiconLoader`）。 */
    fun install(
        separators: List<String>,
        leads: List<String>,
        searchWords: Set<String>,
        labels: Map<String, String>,
        taskVerbs: List<String> = emptyList(),
        openWords: List<String> = emptyList(),
        verbTypes: List<Pair<String, String>> = emptyList(),
        prefixes: List<String> = emptyList(),
        suffixes: List<String> = emptyList(),
        conjunctions: List<String> = emptyList(),
        containerWords: List<String> = emptyList(),
        firstMarkers: List<String> = emptyList(),
        backLabels: List<String> = emptyList(),
        labelSynonyms: Map<String, String> = emptyMap(),
        viewVerbs: List<String> = emptyList(),
        menuLabels: List<String> = emptyList(),
        urlBarLabels: List<String> = emptyList()
    ) {
        this.separators = separators.sortedByDescending { it.length }
        this.leads = leads.sortedByDescending { it.length }
        this.searchWords = searchWords
        this.labels = labels
        this.verbs = taskVerbs.sortedBy { it.length }
        this.opens = openWords.sortedByDescending { it.length }
        this.verbTypes = verbTypes.sortedByDescending { it.first.length }
        this.prefixes = prefixes.sortedByDescending { it.length }
        this.suffixes = suffixes.sortedByDescending { it.length }
        this.conjunctions = conjunctions.sortedByDescending { it.length }
        this.containerWords = containerWords.sortedByDescending { it.length }
        this.firstMarkers = firstMarkers
        this.backLabels = backLabels
        this.labelSynonyms = labelSynonyms
        this.viewVerbs = viewVerbs.sortedByDescending { it.length }
        this.menuLabels = menuLabels.sortedByDescending { it.length }
        this.urlBarLabels = urlBarLabels
    }

    /** 介詞（在／到／進…）：不是內容，只是位置標記。 */
    fun prefixes(): List<String> = prefixes

    /** 方位助詞（中／裡／内…）：不是內容。 */
    fun suffixes(): List<String> = suffixes

    /** 連接詞（並／並且…）：後面接的是下一個動作。 */
    fun conjunctions(): List<String> = conjunctions

    /** 容器詞（清單／列表…）：只用在「去掉尾端」，不會當成內容。 */
    fun containerWords(): List<String> = containerWords

    /** 序數標記（第一個／第一筆…）。 */
    fun firstMarkers(): List<String> = firstMarkers

    /** 返回詞（返回／上一頁／Back…）。 */
    fun backLabels(): List<String> = backLabels

    /** 畫面用詞同義對照。 */
    fun labelSynonyms(): Map<String, String> = labelSynonyms

    /** 瀏覽型動詞（看／查看…）。 */
    fun viewVerbs(): List<String> = viewVerbs

    /** 溢出選單標籤。 */
    fun menuLabels(): List<String> = menuLabels

    /** 網址列標記。 */
    fun urlBarLabels(): List<String> = urlBarLabels

    fun separators(): List<String> = separators

    fun leads(): List<String> = leads

    fun searchWords(): Set<String> = searchWords

    /** 語意名稱 → 介面標籤（例如 `send` → `送出`）。 */
    fun label(key: String): String = labels[key] ?: key

    /** 任務動作詞（搜尋／點擊／導航…）。 */
    fun taskVerbs(): List<String> = verbs

    /** 「開 App」的動詞（打開／開啟／開）。 */
    fun openWords(): List<String> = opens

    /** 返回語意的所有詞（key 以 back 開頭，例如 back / back_en）。 */
    fun backWords(): List<String> = labels.filterKeys { it.startsWith("back") }.values.toList()

    /**
     * 句型判定（句首不是動詞時用）：「在／到／進 … 搜尋 Y」＝ FIND。
     *
     * 為什麼要這個：分類器對這種句型會誤判（實測「在設定裡搜尋藍牙」被判成 CREATE，
     * 信心 0.83；「在 Chrome 搜尋今天天氣」被判成 0.94），而句首是介詞，
     * [typeOfHead] 的動詞優先規則接不到。
     *
     * 判準刻意寬鬆：**有搜尋詞 + 有介詞**就算 FIND。方位助詞（裡／中）不是必要條件，
     * 因為「在 Chrome 搜尋…」沒有方位詞，但仍明顯是搜尋。沒有搜尋詞的句子不受影響
     * （「在設定裡開啟藍牙」不會被誤判）。
     * 詞彙全部來自 TSV（`ui_grammar_particles.tsv`、`ui_language_words.tsv`）。
     */
    fun typeOfFrame(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val hasPrefix = prefixes.any { it.isNotEmpty() && t.contains(it) }

        // 「打開 Chrome 的歷史紀錄」：開 App 之後直接進某個子頁（畫面上看不到那個字，
        // 通常在右上選單裡）。這種句子以前會被切成「打開的歷史紀錄」碎片。
        if (opens.any { it.isNotEmpty() && t.contains(it) } && t.contains("的")) {
            return "VIEW"
        }

        // 「在時鐘看世界時間」：沒有搜尋詞，但語意是「帶我去那一頁」→ VIEW（不是 CREATE）。
        // 必須放在「找搜尋詞」之前，否則這種句子會直接 return null 交給分類器，
        // 實測就被判成 CREATE（0.69），計畫裡沒有「去找那一頁」的步驟。
        if (hasPrefix && viewVerbs.any { it.isNotEmpty() && t.contains(it) }) return "VIEW"

        val searchAt = searchWords
            .filter { it.isNotEmpty() }
            .map { t.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return null
        if (!hasPrefix) return null

        // 「搜尋」之後是否還要「打開／播放第一個」？
        // 例：「在設定裡搜尋飛航模式並打開」→ 要進去；「在設定裡搜尋藍牙」→ 停在結果頁。
        // 只看搜尋詞之後的字，避免「打開設定並搜尋 X」被誤判成要打開結果。
        val tail = t.substring(searchAt)
        val wantsOpen = opens.any { it.length >= 2 && tail.contains(it) } ||
            firstMarkers.any { it.isNotEmpty() && tail.contains(it) }
        return if (wantsOpen) "FIND_OPEN" else "FIND"
    }

    /** 句首動詞 → 任務型別（資料驅動）。 */
    fun typeOfHead(text: String): String? {
        val t = text.trim()
        for ((v, type) in verbTypes) {
            if (v.isNotEmpty() && t.startsWith(v)) return type
        }
        return null
    }

    fun resetForTest() {
        separators = emptyList()
        leads = emptyList()
        searchWords = emptySet()
        labels = emptyMap()
        verbs = emptyList()
        opens = emptyList()
        verbTypes = emptyList()
        prefixes = emptyList()
        suffixes = emptyList()
        conjunctions = emptyList()
        containerWords = emptyList()
        firstMarkers = emptyList()
        backLabels = emptyList()
        labelSynonyms = emptyMap()
        viewVerbs = emptyList()
        menuLabels = emptyList()
        urlBarLabels = emptyList()
    }
}
