package com.wae.agent.core.plan

import android.content.Context
import com.wae.core.screen.ConceptMatcher

/**
 * 概念表載入（`ui_concepts.tsv`）。
 *
 * 資料是語言層級（概念、同族詞），不含任何 App 名稱或套件名——
 * 所以任何撥號 App、任何瀏覽器都適用，新增說法只要加一行 TSV。
 */
object ConceptLexicon {

    @Volatile
    private var table: ConceptMatcher.Table? = null

    fun table(context: Context): ConceptMatcher.Table {
        table?.let { return it }
        val lines = com.wae.core.util.Degrade.orEmpty("concept:load") {
            context.assets.open("open_source_ai_databases/ui_concepts.tsv")
                .bufferedReader(Charsets.UTF_8).readLines()
        }
        val concepts = LinkedHashMap<String, ConceptMatcher.Concept>()
        val families = LinkedHashMap<String, Set<String>>()
        val members = LinkedHashMap<String, MutableMap<String, String>>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split('\t').map { it.trim() }
            if (cells.size < 2) continue
            when (cells[0].uppercase()) {
                "CONCEPT" -> {
                    val id = cells.getOrNull(1) ?: continue
                    val domains = cells.getOrNull(2).orEmpty()
                        .split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    val labels = cells.getOrNull(3).orEmpty()
                        .split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    if (id.isNotEmpty() && labels.isNotEmpty()) {
                        concepts[id] = ConceptMatcher.Concept(id, domains, labels)
                    }
                }
                "FAMILY" -> {
                    val id = cells.getOrNull(1) ?: continue
                    val list = cells.getOrNull(2).orEmpty()
                        .split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    if (id.isNotEmpty() && list.isNotEmpty()) families[id] = list
                }
                "MEMBER" -> {
                    val conceptId = cells.getOrNull(1) ?: continue
                    val familyId = cells.getOrNull(2) ?: continue
                    val member = cells.getOrNull(3) ?: continue
                    if (conceptId.isEmpty() || familyId.isEmpty() || member.isEmpty()) continue
                    members.getOrPut(familyId) { LinkedHashMap() }[member] = conceptId
                }
            }
        }
        val built = ConceptMatcher.Table(concepts, families, members.mapValues { it.value.toMap() })
        if (concepts.isNotEmpty()) table = built
        android.util.Log.i(
            "WAE_CONCEPT",
            "loaded concepts=${concepts.size} families=${families.size} members=${members.size}"
        )
        return built
    }
}
