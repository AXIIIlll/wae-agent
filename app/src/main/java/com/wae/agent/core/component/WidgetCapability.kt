package com.wae.agent.core.component

import android.content.Context

/**
 * 通用元件能力圖譜（Widget Capability Ontology）
 *
 * 為什麼需要它：
 *  過去的角色推斷只看「文字 / 圖示 / viewId」，等於只知道元件「叫什麼」，
 *  不知道元件「天生能做什麼」。結果就是：看到滑桿只會點一下、看到開關只會點一下、
 *  看到純文字圖片也會去點。
 *
 * 這裡把知識拆成兩條互相獨立的軸：
 *  **A 軸｜能力（capability）**：由「元件類別 + 運行期語意訊號」決定，
 *     例如 SeekBar 天生可 SET_VALUE（設絕對值）、可 DRAG、可 SCROLL_H。
 *  **B 軸｜用途（purpose）**：由文字標籤、圖示語意、所在頁面語境決定，
 *     同一根滑桿可以調亮度、音量、字體大小、播放進度。
 *
 * 兩軸相乘才會得到「該對它做什麼」：
 *  「亮度」+ SeekBar + RangeInfo → SET_VALUE
 *  「亮度」+ 一般列      → TAP（進下一層）
 *  「深色模式」+ Switch  → TOGGLE
 *
 * 資料全部來自 `assets/open_source_ai_databases/ui_widget_capability_ontology.tsv`，
 * 程式碼裡不列任何元件清單（符合本專案的資料來源政策）。
 */
enum class Capability {
    TAP,
    TOGGLE,
    SET_VALUE,
    STEP_VALUE,
    SCROLL_V,
    SCROLL_H,
    PICK_ONE,
    PICK_MANY,
    EXPAND,
    OPEN_MENU,
    TEXT_INPUT,
    SUBMIT,
    NAVIGATE,
    DISMISS,
    DRAG,
    LONG_HOLD,
    MEDIA,
    PINCH,
    REVEAL,
    READ_ONLY;

    companion object {
        private val byToken: Map<String, Capability> = entries.associateBy { it.name }

        fun fromToken(token: String): Capability? = byToken[token.trim().uppercase()]
    }
}

/** 一個元件家族（例如「滑桿」「開關」「設定列」）及其固定知識 */
data class WidgetFamily(
    val id: String,
    val classPatterns: List<String>,
    val capabilities: Set<Capability>,
    val ariaRole: String,
    val purposes: List<String>,
    val zhName: String,
    val enName: String,
    val source: String,
    /**
     * 類別先驗是否可信。
     * false（weak）= 這個類別名不帶能力資訊（通用容器 / Compose 根節點），
     * 能力必須完全由運行期語意訊號決定，否則會把「任何 View」都當成可點。
     */
    val strongPrior: Boolean = true
) {
    val adjustable: Boolean get() = capabilities.contains(Capability.SET_VALUE)

    /** 一句話描述，供日誌/候選描述使用 */
    fun summary(): String =
        "$zhName($enName) 能力=${capabilities.joinToString("/")} 用途=${purposes.take(4).joinToString("/")}"
}

/** 一個「用途」所屬的設定分類（例如 亮度 → 顯示），用來決定該往哪一列鑽 */
data class PurposeCategory(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val purposes: List<String>,
    val source: String
) {
    private val matchTerms: List<String>
        get() = (listOf(name) + aliases).map { it.trim() }.filter { it.length >= 2 }.distinct()

    /** 這段文字是不是「這個分類」的入口（例如「顯示螢幕」屬於顯示） */
    fun matches(text: String): Boolean {
        if (text.isEmpty()) return false
        return matchTerms.any { text.contains(it) }
    }

    fun coversPurpose(purpose: String): Boolean {
        val p = purpose.trim()
        if (p.isEmpty()) return false
        return purposes.any { it == p || (p.length >= 2 && it.contains(p)) || (it.length >= 2 && p.contains(it)) }
    }
}

object WidgetOntology {

    private const val DB_RELATIVE_PATH = "open_source_ai_databases/ui_widget_capability_ontology.tsv"
    private const val CATEGORY_DB_RELATIVE_PATH = "open_source_ai_databases/ui_purpose_category_map.tsv"

    @Volatile
    private var categories: List<PurposeCategory> = emptyList()

    @Volatile
    private var families: List<WidgetFamily> = emptyList()

    /** 類別片段（小寫）→ 家族；查詢時由長到短比對，確保專屬類別優先於通用類別 */
    @Volatile
    private var patternsByLength: List<Pair<String, WidgetFamily>> = emptyList()

    @Volatile
    private var purposeIndex: Map<String, List<WidgetFamily>> = emptyMap()

    @Volatile
    private var loadAttempted: Boolean = false

    fun isLoaded(): Boolean = families.isNotEmpty()

    fun familyCount(): Int = families.size

    fun allFamilies(): List<WidgetFamily> = families

    /** 由 Android assets 載入（僅第一次有效；可由 [resetForTest] 清除） */
    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val text = context.assets.open(DB_RELATIVE_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            loadFromLines(text.lineSequence().toList())
        } catch (_: Throwable) {
            // 載入失敗時保持空表，所有查詢都會回退到「未知元件」語意（不會崩潰）
        }
        try {
            val text = context.assets.open(CATEGORY_DB_RELATIVE_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            loadCategoriesFromLines(text.lineSequence().toList())
        } catch (_: Throwable) {
        }
    }

    /** 載入「用途 → 所屬分類」關聯表 */
    fun loadCategoriesFromLines(lines: List<String>) {
        val parsed = ArrayList<PurposeCategory>()
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size < 5) continue
            parsed.add(
                PurposeCategory(
                    id = parts[0].trim(),
                    name = parts[1].trim(),
                    aliases = parts[2].split("|").map { it.trim() }.filter { it.isNotEmpty() },
                    purposes = parts[3].split("|").map { it.trim() }.filter { it.isNotEmpty() },
                    source = parts[4].trim()
                )
            )
        }
        categories = parsed
    }

    /** 依用途詞找出所屬分類（例如「亮度」→ 顯示） */
    fun categoryFor(purpose: String): PurposeCategory? {
        val p = purpose.trim()
        if (p.isEmpty() || categories.isEmpty()) return null
        return categories.firstOrNull { it.coversPurpose(p) }
    }

    fun categoryCount(): Int = categories.size

    /** 由已讀入的文字行載入（JVM 測試與 assets 共用同一條解析路徑） */
    fun loadFromLines(lines: List<String>) {
        val parsed = ArrayList<WidgetFamily>()
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size < 8) continue
            val caps = parts[2].split("|").mapNotNull { Capability.fromToken(it) }.toSet()
            if (caps.isEmpty()) continue
            parsed.add(
                WidgetFamily(
                    id = parts[0].trim(),
                    classPatterns = parts[1].split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                    capabilities = caps,
                    ariaRole = parts[3].trim(),
                    purposes = parts[4].split("|").map { it.trim() }.filter { it.isNotEmpty() },
                    zhName = parts[5].trim(),
                    enName = parts[6].trim(),
                    source = parts[7].trim(),
                    strongPrior = parts.getOrNull(8)?.trim()?.lowercase() != "weak"
                )
            )
        }
        families = parsed
        val pairs = ArrayList<Pair<String, WidgetFamily>>()
        for (f in parsed) for (p in f.classPatterns) pairs.add(p to f)
        patternsByLength = pairs.sortedByDescending { it.first.length }

        val idx = HashMap<String, MutableList<WidgetFamily>>()
        for (f in parsed) for (p in f.purposes) idx.getOrPut(p) { ArrayList() }.add(f)
        purposeIndex = idx
    }

    fun resetForTest() {
        families = emptyList()
        patternsByLength = emptyList()
        purposeIndex = emptyMap()
        categories = emptyList()
        loadAttempted = false
    }

    /**
     * 由類別名稱（或完整類別鏈，見 [AffordanceFusion]）找出最貼近的元件家族。
     * 比對方式：類別名稱小寫後「包含」資料庫裡的片段，取最長片段者。
     */
    fun familyOfClass(className: String): WidgetFamily? {
        val cls = className.trim().lowercase()
        if (cls.isEmpty()) return null
        for ((pattern, family) in patternsByLength) {
            if (cls.contains(pattern)) return family
        }
        return null
    }

    fun capabilitiesOfClass(className: String): Set<Capability> =
        familyOfClass(className)?.capabilities ?: emptySet()

    /** 反向查詢：想做某種操作時，哪些元件家族做得到 */
    fun familiesWithCapability(capability: Capability): List<WidgetFamily> =
        families.filter { it.capabilities.contains(capability) }

    /**
     * 反向查詢（語意）：某個用途詞（例如「亮度」）可能落在哪些元件家族。
     * 支援雙向包含，讓「調亮度」也能命中資料庫裡的「亮度」。
     */
    fun familiesForPurpose(purpose: String): List<WidgetFamily> {
        val p = purpose.trim()
        if (p.isEmpty()) return emptyList()
        purposeIndex[p]?.let { return it }
        val hit = ArrayList<WidgetFamily>()
        for (f in families) {
            if (f.purposes.any { it.contains(p) || p.contains(it) }) hit.add(f)
        }
        return hit
    }

    /** 這個用途詞是否「屬於可調整數值」的語意（決定要不要找滑桿） */
    fun purposeImpliesValueAdjustment(purpose: String): Boolean =
        familiesForPurpose(purpose).any { it.adjustable }

    /** 供日誌/除錯：一行摘要 */
    fun describe(className: String): String {
        val f = familyOfClass(className) ?: return "unknown($className)"
        return f.summary()
    }
}
