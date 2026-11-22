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
        suffixes: List<String> = emptyList()
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
    }

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
     * 句型判定（句首不是動詞時用）：「在／到／進 X 裡／中 搜尋 Y」＝ FIND。
     *
     * 為什麼要這個：分類器對這種句型會誤判（實測「在設定裡搜尋藍牙」被判成 CREATE，
     * 信心 0.83），而句首是「在」，[typeOfHead] 的動詞優先規則接不到。
     * 這裡只用文法助詞（`ui_grammar_particles.tsv`）與搜尋詞（`ui_language_words.tsv`），
     * 不列任何 App 名稱或名詞。
     */
    fun typeOfFrame(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val hasSearch = searchWords.any { it.isNotEmpty() && t.contains(it) }
        if (!hasSearch) return null
        val hasPrefix = prefixes.any { it.isNotEmpty() && t.contains(it) }
        val hasSuffix = suffixes.any { it.isNotEmpty() && t.contains(it) }
        return if (hasPrefix && hasSuffix) "FIND" else null
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
    }
}
