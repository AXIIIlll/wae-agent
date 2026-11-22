package com.wae.agent.core.omega

import android.content.Context

/**
 * WAE-Ω 第 1 層：通用 UI 本體論（UiOntology）。
 *
 * 這是「跨 App 同構」的關鍵：App Model 不使用 App 專屬的 selector，而是用
 * **語意型別**（列表列、開關列、文字欄位、搜尋框、對話框…）描述畫面。
 * 於是：
 *  - 在 A App 學到的技能，可以結構化轉移到 B App（同型別＝同操作方式）
 *  - 意圖編譯器可以用型別做靜態檢查（「這裡需要 TEXT_FIELD，但找到 BUTTON」）
 *  - 策略驗證器只需要對「型別 + 風險」驗證，不需要每個 App 重新寫規則
 *
 * 資料驅動：`assets/open_source_ai_databases/ui_ui_ontology.tsv`
 * 格式：`utype<TAB>signal<TAB>pattern`
 *   signal = class（className 子字串）| id（viewId 子字串）| role（roleTag 相符）|
 *            container（parentContainerType 相符）| text（文字關鍵字，會轉小寫比對）
 *
 * 純 Kotlin（除了 ensureLoaded），JVM 可測。
 */
object UiOntology {

    private const val DB_PATH = "open_source_ai_databases/ui_ui_ontology.tsv"

    enum class UiType {
        LIST_ROW,
        SWITCH_ROW,
        TEXT_FIELD,
        SEARCH_BOX,
        BUTTON,
        TAB,
        NAV_ITEM,
        DIALOG,
        CHECKBOX,
        RADIO,
        SLIDER,
        DROPDOWN,
        IMAGE_CARD,
        TEXT_LABEL,
        UNKNOWN
    }

    /** 分類輸入：刻意用純量，讓上層能從 CandidateOption 直接轉換而不依賴它 */
    data class Input(
        val className: String = "",
        val viewId: String = "",
        val roleTag: String = "",
        val text: String = "",
        val editable: Boolean = false,
        val checkable: Boolean = false,
        val clickable: Boolean = false,
        val hasRange: Boolean = false,
        val parentContainerType: String = ""
    )

    data class Match(val type: UiType, val confidence: Float, val evidence: String)

    private data class Rule(val type: UiType, val signal: String, val pattern: String)

    private val rules = ArrayList<Rule>()

    @Volatile
    private var loadAttempted = false

    private val DEFAULT_ROWS = listOf(
        "utype\tsignal\tpattern",
        "TEXT_FIELD\tclass\tEditText",
        "TEXT_FIELD\tclass\tAutoCompleteTextView",
        "SEARCH_BOX\tid\tsearch",
        "SEARCH_BOX\tdescription\t搜尋",
        "SEARCH_BOX\ttext\t搜尋",
        "SWITCH_ROW\tclass\tSwitch",
        "SWITCH_ROW\tclass\tToggleButton",
        "CHECKBOX\tclass\tCheckBox",
        "RADIO\tclass\tRadioButton",
        "SLIDER\tclass\tSeekBar",
        "DROPDOWN\tclass\tSpinner",
        "TAB\tclass\tTabLayout",
        "TAB\trole\ttab",
        "NAV_ITEM\tcontainer\tBOTTOM_NAV",
        "NAV_ITEM\tcontainer\tNAV_ITEM",
        "LIST_ROW\tcontainer\tLIST_ITEM",
        "LIST_ROW\tcontainer\tRECYCLER_ROW",
        "DIALOG\tclass\tDialog",
        "DIALOG\tcontainer\tMODAL_DIALOG",
        "BUTTON\tclass\tButton",
        "BUTTON\tclass\tImageButton",
        "IMAGE_CARD\tid\tcard",
        "IMAGE_CARD\tid\tthumbnail"
    )

    fun ensureLoaded(context: Context?) {
        if (loadAttempted) return
        loadAttempted = true
        try {
            val lines = context?.assets?.open(DB_PATH)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            loadFromLines(lines ?: DEFAULT_ROWS)
        } catch (_: Throwable) {
            loadFromLines(DEFAULT_ROWS)
        }
    }

    fun loadFromLines(lines: List<String>) {
        val parsed = ArrayList<Rule>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 3 || c[0].trim() != "utype") continue
            val type = runCatching { UiType.valueOf(c[1].trim().uppercase()) }.getOrNull() ?: continue
            val signal = c[2].trim().lowercase()
            val pattern = c.getOrElse(3) { "" }.trim().lowercase()
            if (signal.isEmpty() || pattern.isEmpty()) continue
            parsed.add(Rule(type, signal, pattern))
        }
        synchronized(rules) {
            rules.clear()
            rules.addAll(parsed)
        }
    }

    fun resetForTest() {
        synchronized(rules) { rules.clear() }
        loadAttempted = false
    }

    fun ruleCount(): Int = synchronized(rules) { rules.size }

    /**
     * 分類：規則命中累積分數，再與「結構性先驗」合併；分數最高的型別勝出。
     *
     * 權重：id 0.55 > class 0.45 > role 0.40 > container 0.35 > text 0.25
     * 結構先驗（editable/checkable/hasRange/clickable）是保底，避免未知 App 完全失控。
     */
    fun classify(input: Input): Match {
        val className = input.className.lowercase()
        val viewId = input.viewId.lowercase()
        val role = input.roleTag.lowercase()
        val text = input.text.lowercase()
        val container = input.parentContainerType.lowercase()

        val scores = HashMap<UiType, Float>()
        val evidence = HashMap<UiType, String>()

        fun add(type: UiType, w: Float, why: String) {
            scores[type] = (scores[type] ?: 0f) + w
            if (!evidence.containsKey(type) || w >= 0.4f) evidence[type] = why
        }

        val snapshot = synchronized(rules) { rules.toList() }
        for (r in snapshot) {
            val hit = when (r.signal) {
                "class" -> className.isNotEmpty() && className.contains(r.pattern)
                "id" -> viewId.isNotEmpty() && viewId.contains(r.pattern)
                "role" -> role.isNotEmpty() && role == r.pattern
                "container" -> container.isNotEmpty() && container.contains(r.pattern)
                "text" -> text.isNotEmpty() && text.contains(r.pattern)
                "description" -> text.isNotEmpty() && text.contains(r.pattern)
                else -> false
            }
            if (!hit) continue
            val w = when (r.signal) {
                "id" -> 0.55f
                "class" -> 0.45f
                "role" -> 0.40f
                "container" -> 0.35f
                else -> 0.25f
            }
            add(r.type, w, "${r.signal}:${r.pattern}")
        }

        // 結構性先驗（沒有規則命中時也能給出合理答案）
        if (input.editable) add(UiType.TEXT_FIELD, 0.40f, "flag:editable")
        if (input.hasRange) add(UiType.SLIDER, 0.40f, "flag:hasRange")
        if (input.checkable && !input.hasRange) add(UiType.SWITCH_ROW, 0.30f, "flag:checkable")
        if (input.clickable && container.contains("list_item")) add(UiType.LIST_ROW, 0.30f, "flag:row")
        if (input.clickable && role.contains("button")) add(UiType.BUTTON, 0.20f, "flag:clickable")
        if (!input.editable && !input.clickable && text.isNotEmpty()) {
            add(UiType.TEXT_LABEL, 0.20f, "flag:textOnly")
        }

        val best = scores.maxByOrNull { it.value }
            ?: return Match(UiType.UNKNOWN, 0f, "no_rule")
        val confidence = (best.value / 1.2f).coerceIn(0f, 1f)
        return Match(best.key, confidence, evidence[best.key] ?: "")
    }

    /** 便利函式：直接轉型別 */
    fun typeOf(input: Input): UiType = classify(input).type

    /** 這個型別是否可作為「讀資料」的來源（App Model 的 schema 用） */
    fun isReadable(type: UiType): Boolean = type in setOf(
        UiType.TEXT_LABEL, UiType.TEXT_FIELD, UiType.LIST_ROW, UiType.SWITCH_ROW,
        UiType.CHECKBOX, UiType.RADIO, UiType.SLIDER, UiType.SEARCH_BOX, UiType.DROPDOWN
    )

    /** 這個型別是否可作為「寫資料」的目標 */
    fun isWritable(type: UiType): Boolean = type in setOf(
        UiType.TEXT_FIELD, UiType.SEARCH_BOX, UiType.SWITCH_ROW, UiType.CHECKBOX,
        UiType.RADIO, UiType.SLIDER, UiType.DROPDOWN
    )

    /** 這個型別是否可「觸發動作」 */
    fun isActionable(type: UiType): Boolean = type !in setOf(
        UiType.TEXT_LABEL, UiType.UNKNOWN
    )
}
