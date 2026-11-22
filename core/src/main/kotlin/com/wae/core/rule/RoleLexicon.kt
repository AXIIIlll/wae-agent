package com.wae.core.rule

/**
 * 角色詞庫（兩層）：**統一詞庫**（所有 App 共用）＋**家族詞庫**（依 Play 分類）。
 *
 * 為什麼要兩層：每個 App 的用詞不同，但「角色」只有幾十種。
 * 先把通用角色（搜尋／送出／返回…）學起來，再依 App 所屬分類補上該領域才會出現的角色
 * （購物車、加入片單、開始導航…）。這樣不需要為每個 App 各寫一份。
 *
 * 資料來源（皆為資料檔，改資料不改程式）：
 *  - `ui/lexicon_common.tsv`
 *  - `ui/lexicon_<family>.tsv`（20 個家族）
 *  - `ui/lexicon_family_map.tsv`（Play 分類 → 家族）
 *  - `ui/pkg_category.tsv`（套件 → Play 分類；2017 公開資料，僅作 fallback）
 */
class RoleLexicon(
    /** 角色 → 詞（正規化後）。 */
    private val terms: Map<String, List<String>>,
    /** 詞 → 角色（反查；同詞多角色時取先出現者）。 */
    private val byTerm: Map<String, String>
) {

    /** 這個標籤屬於哪個角色？找不到回 null（誠實）。 */
    fun roleOf(label: String): String? {
        val text = label.trim().lowercase()
        if (text.isEmpty()) return null
        byTerm[text]?.let { return it }
        // 包含比對：按鈕文字常帶前後贅字（例「加入購物車 (2)」）
        for ((term, role) in byTerm) {
            if (term.length >= 2 && text.contains(term)) return role
        }
        return null
    }

    fun termsOf(role: String): List<String> = terms[role].orEmpty()

    fun roles(): Set<String> = terms.keys

    companion object {
        /** 合併兩個詞庫；後者（家族）優先覆蓋同一個詞。 */
        fun merge(common: RoleLexicon, family: RoleLexicon): RoleLexicon {
            val terms = LinkedHashMap<String, MutableList<String>>()
            for (src in listOf(common, family)) {
                for ((role, list) in src.terms) {
                    terms.getOrPut(role) { ArrayList() }.addAll(list)
                }
            }
            val byTerm = LinkedHashMap<String, String>()
            for (src in listOf(family, common)) {
                for ((term, role) in src.byTerm) byTerm.putIfAbsent(term, role)
            }
            return RoleLexicon(terms.mapValues { it.value.distinct() }, byTerm)
        }

        fun parse(lines: Sequence<String>): RoleLexicon {
            val terms = LinkedHashMap<String, List<String>>()
            val byTerm = LinkedHashMap<String, String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 2) continue
                val role = c[0].trim()
                if (role.isEmpty()) continue
                val list = c[1].split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                if (list.isEmpty()) continue
                terms[role] = (terms[role].orEmpty() + list).distinct()
                for (term in list) byTerm.putIfAbsent(term, role)
            }
            return RoleLexicon(terms, byTerm)
        }

        fun empty(): RoleLexicon = RoleLexicon(emptyMap(), emptyMap())
    }
}

/**
 * Play 分類 → 詞庫家族（資料驅動）。
 *
 * 沒有對應的分類一律回 `COMMON`（只使用統一詞庫，不亂套領域詞）。
 */
class LexiconFamilyMap(private val map: Map<String, String>) {

    fun familyOf(playCategory: String): String =
        map[playCategory.trim()] ?: COMMON

    fun families(): Set<String> = map.values.toSet()

    companion object {
        const val COMMON = "COMMON"

        fun parse(lines: Sequence<String>): LexiconFamilyMap {
            val out = LinkedHashMap<String, String>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size >= 2) out[c[0].trim()] = c[1].trim().uppercase()
            }
            return LexiconFamilyMap(out)
        }
    }
}
