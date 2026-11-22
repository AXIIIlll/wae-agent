package com.wae.core.config

import com.wae.core.contract.ContractDeriver
import com.wae.core.contract.ContractRule
import com.wae.core.effect.IntentCompiler
import com.wae.core.effect.VerbRule
import com.wae.core.model.ControlKind

/**
 * 內建資料（隨 JAR 出貨的預設值）。
 *
 * 為什麼放在 `resources/ui/` 而不是程式碼：這是 Gen-5 的「資料驅動」承諾——
 * 加能力＝改 TSV，不是改 Kotlin。App 層可以在啟動時用 `assets` 覆蓋同一份內容。
 *
 * 三條防呆（§18.2 #2 #3）：
 *  1. 路徑不存在 → **立刻丟錯**，不要靜默用空表。
 *  2. 解析結果為空 → 立刻丟錯。
 *  3. 解析器本身會剝 BOM、跳過註解與壞列。
 */
object BundledData {

    private const val PATH_TUNABLES = "/ui/ui_tunables.tsv"
    private const val PATH_CONTRACT_RULES = "/ui/contract_rules.tsv"
    private const val PATH_VERBS = "/ui/intent_verbs.tsv"
    private const val PATH_INTENT_ROUTES = "/ui/intent_routes.tsv"
    private const val PATH_PURPOSES = "/ui/purposes.tsv"
    private const val PATH_OPERATIONS = "/ui/operations.tsv"
    private const val PATH_COMMAND_PURPOSES = "/ui/command_purposes.tsv"
    private const val PATH_TASK_METHODS = "/ui/task_methods.tsv"
    private const val PATH_SPECIFICITY = "/ui/specificity.tsv"
    private const val PATH_TRAD_MAP = "/ui/trad_map.tsv"
    private const val PATH_SIMP_TRAD = "/ui/simp_trad.tsv"
    private const val PATH_LEXICON_COMMON = "/ui/lexicon_common.tsv"
    private const val PATH_LEXICON_FAMILY_MAP = "/ui/lexicon_family_map.tsv"
    private const val PATH_PKG_CATEGORY = "/ui/pkg_category.tsv"
    private const val PATH_URI_SEARCH = "/ui/uri_search.tsv"
    private const val PATH_OBJECT_TYPES = "/ui/object_types.tsv"
    private const val PATH_PURPOSE_PARAPHRASES = "/ui/purpose_paraphrases.tsv"
    private const val PATH_TYPE_APPS = "/ui/type_apps.tsv"
    private const val PATH_VERB_TYPES = "/ui/verb_types.tsv"
    private const val PATH_PRECONDITIONS = "/ui/preconditions.tsv"

    /** 前置條件表（效果前綴 → 要先開的 App 標籤）。沒有這張表就退化成「不推導前置條件」。 */
    fun preconditions(): com.wae.core.pipeline.Preconditions =
        com.wae.core.pipeline.Preconditions.parse(linesOrEmpty(PATH_PRECONDITIONS))

    fun tunables(): Map<String, String> {
        val parsed = ConfigParser.parse(lines(PATH_TUNABLES))
        require(parsed.isNotEmpty()) { "內建參數表是空的：$PATH_TUNABLES" }
        return parsed
    }

    /** 規則系統的五張表（v1）。 */
    fun purposeLines(): Sequence<String> = linesOrEmpty(PATH_PURPOSES)
    fun operationLines(): Sequence<String> = linesOrEmpty(PATH_OPERATIONS)
    fun commandPurposeLines(): Sequence<String> = linesOrEmpty(PATH_COMMAND_PURPOSES)
    fun taskMethodLines(): Sequence<String> = linesOrEmpty(PATH_TASK_METHODS)
    fun specificityLines(): Sequence<String> = linesOrEmpty(PATH_SPECIFICITY)
    fun tradMapLines(): Sequence<String> = linesOrEmpty(PATH_TRAD_MAP)
    fun simpTradLines(): Sequence<String> = linesOrEmpty(PATH_SIMP_TRAD)

    /** 角色詞庫：統一 + 家族 + 家族對照 + 套件分類。 */
    fun lexiconCommonLines(): Sequence<String> = linesOrEmpty(PATH_LEXICON_COMMON)
    fun lexiconFamilyLines(family: String): Sequence<String> =
        linesOrEmpty("/ui/lexicon_" + family.lowercase() + ".tsv")
    fun lexiconFamilyMapLines(): Sequence<String> = linesOrEmpty(PATH_LEXICON_FAMILY_MAP)
    fun pkgCategoryLines(): Sequence<String> = linesOrEmpty(PATH_PKG_CATEGORY)
    fun uriSearchLines(): Sequence<String> = linesOrEmpty(PATH_URI_SEARCH)
    fun objectTypeLines(): Sequence<String> = linesOrEmpty(PATH_OBJECT_TYPES)

    /** 目的 → 口語換句話說（檢索語料用；`purpose<TAB>phrase`）。 */
    fun purposeParaphraseLines(): Sequence<String> = linesOrEmpty(PATH_PURPOSE_PARAPHRASES)
    fun typeAppLines(): Sequence<String> = linesOrEmpty(PATH_TYPE_APPS)
    fun verbTypeLines(): Sequence<String> = linesOrEmpty(PATH_VERB_TYPES)

    /** 目的型意圖的路由表（看影片→VIDEO_MEDIA＋home）；沒有就回空（選配）。 */
    fun intentRoutes(): Sequence<String> = linesOrEmpty(PATH_INTENT_ROUTES)

    fun contractRules(): Map<ControlKind, ContractRule> {
        val parsed = ContractDeriver.parseRules(lines(PATH_CONTRACT_RULES))
        require(parsed.isNotEmpty()) { "內建契約規則表是空的：$PATH_CONTRACT_RULES" }
        return parsed
    }

    fun verbRules(): List<VerbRule> {
        val parsed = IntentCompiler.parseRules(lines(PATH_VERBS))
        require(parsed.isNotEmpty()) { "內建動詞表是空的：$PATH_VERBS" }
        return parsed
    }

    private fun lines(path: String): Sequence<String> {
        val stream = BundledData::class.java.getResourceAsStream(path)
            ?: error("缺少內建資料：$path（resources/ui/ 是否漏打包？）")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.asSequence()
    }

    /** 可選資料：沒有就回空（前置條件表是選配，不該讓 App 起不來）。 */
    private fun linesOrEmpty(path: String): Sequence<String> {
        val stream = BundledData::class.java.getResourceAsStream(path) ?: return emptySequence()
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.asSequence()
    }
}
