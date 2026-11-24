package com.wae.agent.core.predicate

import android.content.Context
import com.wae.core.predicate.PredicateAgent
import com.wae.core.predicate.PredicateLexicon

/**
 * 把既有 TSV 詞表接成 [PredicateLexicon]（不新增任何詞表、不寫死詞）。
 *
 * 資料來源全部是專案原有的表：
 *  - `ui_toggle_state_words.tsv`  → 開／關詞
 *  - `ui_value_direction.tsv`    → 方向詞（+1 / −1）
 *  - `ui_value_adjust_lexicon.tsv` → 程度詞與幅度、填充詞、舊 UP/DOWN 片語
 *  - `ui_value_verbs.tsv`        → 動詞（**只用來當「這個字是動詞」的證據**）
 *  - `ui_toggle_section_map.tsv` → 分類線索（開關通常在哪一頁）
 *  - `global_tw_cn_app_metadata.tsv` → App 名稱／別名 → 套件
 *
 * 調整動詞怎麼來（關鍵，且不靠新表）：
 *   舊的 UP/DOWN 片語是「動詞＋方向」組成的（調高、提高、加大…），
 *   取這些片語的**首字**就是調整動詞本身（調／提／加／增／轉／變／拔／壓…）。
 *   這是從既有資料推導，不是另外列一張表。
 */
object PredicateLexiconLoader {

    private const val DB = "open_source_ai_databases"

    @Volatile
    private var cached: Pair<PredicateLexicon, PredicateAgent.SectionLookup>? = null

    fun of(context: Context): Pair<PredicateLexicon, PredicateAgent.SectionLookup> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val built = build(context)
            cached = built
            return built
        }
    }

    fun resetForTest() {
        cached = null
    }

    private fun build(context: Context): Pair<PredicateLexicon, PredicateAgent.SectionLookup> {
        val on = HashSet<String>()
        val off = HashSet<String>()
        readLines(context, "$DB/ui_toggle_state_words.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 2) return@forEach
            val word = p[0].trim()
            if (word.isEmpty()) return@forEach
            when (p[1].trim().uppercase()) {
                "ON" -> on.add(word)
                "OFF" -> off.add(word)
            }
        }

        val directions = LinkedHashMap<String, Int>()
        readLines(context, "$DB/ui_value_direction.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 2) return@forEach
            val w = p[0].trim()
            if (w.isEmpty()) return@forEach
            when (p[1].trim().lowercase()) {
                "up" -> directions[w] = 1
                "down" -> directions[w] = -1
            }
        }

        val small = HashSet<String>()
        val large = HashSet<String>()
        val fillers = HashSet<String>()
        var smallDelta = 0.10f
        var largeDelta = 0.30f
        val adjustVerbs = HashSet<String>()
        readLines(context, "$DB/ui_value_adjust_lexicon.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 2) return@forEach
            val term = p[0].trim()
            if (term.isEmpty()) return@forEach
            val mag = p.getOrNull(2)?.trim()?.toFloatOrNull()
            when (p[1].trim().uppercase()) {
                "DELTA_SMALL" -> {
                    small.add(term)
                    if (mag != null) smallDelta = mag
                }
                "DELTA_LARGE" -> {
                    large.add(term)
                    if (mag != null) largeDelta = mag
                }
                "FILLER" -> fillers.add(term)
                "UP", "DOWN" -> {
                    // 片語首字即調整動詞（調高→調、提高→提、加大→加…）
                    if (term.length >= 2) adjustVerbs.add(term.substring(0, 1))
                }
                else -> Unit
            }
        }

        // 方向詞本身也可以當「調整動詞」嗎？不行——「長按」的「長」就是方向詞。
        // 這正是「方向詞需要動詞證據」規則存在的原因。

        val valueLabels = LinkedHashMap<String, String>()
        // 標籤線索：先用常見系統標籤（來自 metadata 的表達，而不是硬編碼在程式裡）
        readLines(context, "$DB/global_tw_cn_app_metadata.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 2) return@forEach
            val name = p[1].trim()
            val pkg = p[0].trim()
            if (name.isEmpty() || pkg.isEmpty()) return@forEach
        }
        // 數值軸線詞彙全部來自 `ui_value_axis.tsv`（程式不內建任何詞）
        readLines(context, "$DB/ui_value_axis.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                valueLabels[p[0].trim()] = p[1].trim()
            }
        }

        val appAliases = LinkedHashMap<String, String>()
        readLines(context, "$DB/global_tw_cn_app_metadata.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 4) return@forEach
            val pkg = p[0].trim()
            if (pkg.isEmpty()) return@forEach
            val name = p[1].trim()
            if (name.isNotEmpty()) appAliases[name] = pkg
            p[3].split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach { appAliases[it] = pkg }
        }

        var settingsPkg = ""
        readLines(context, "$DB/global_tw_cn_app_metadata.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 2) return@forEach
            val pkg = p[0].trim()
            // 只用套件命名慣例（`.settings`），不比對任何語言的名稱
            if (settingsPkg.isEmpty() && pkg.endsWith(".settings")) {
                settingsPkg = pkg
            }
        }

        val sections = HashMap<String, MutableList<Pair<String, String>>>()
        readLines(context, "$DB/ui_toggle_section_map.tsv").forEach { line ->
            val p = line.split('\t')
            if (p.size < 2) return@forEach
            val target = p[0].trim()
            val section = p[1].trim()
            if (target.isEmpty() || section.isEmpty()) return@forEach
            // 多段路徑：連接|行動熱點與網路共享 → 先鑽「連接」，再鑽「行動熱點與網路共享」
            section.split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach { kw ->
                sections.getOrPut(target) { ArrayList() }.add(kw to kw)
            }
        }

        // 語言層詞彙（連接詞／語助詞／搜尋詞／介面標籤）全部來自 TSV
        run {
            val seps = ArrayList<String>()
            val leads = ArrayList<String>()
            val search = HashSet<String>()
            val labels = HashMap<String, String>()
            val verbs = ArrayList<String>()
            val opens = ArrayList<String>()
            val containers = ArrayList<String>()
            val firsts = ArrayList<String>()
            val backs = ArrayList<String>()
            val synonyms = HashMap<String, String>()
            val views = ArrayList<String>()
            val menus = ArrayList<String>()
            val urlBars = ArrayList<String>()
            readLines(context, "$DB/ui_language_words.tsv").forEach { line ->
                val p = line.split('\t')
                if (p.size < 2) return@forEach
                val kind = p[0].trim().uppercase()
                val word = p[1].trim()
                if (word.isEmpty()) return@forEach
                when (kind) {
                    "SEPARATOR" -> seps.add(word)
                    "LEAD" -> leads.add(word)
                    "SEARCH" -> search.add(word)
                    "LABEL" -> labels[word] = p.getOrNull(2)?.trim().orEmpty().ifEmpty { word }
                    "TASKVERB" -> verbs.add(word)
                    "CONTAINER" -> containers.add(word)
                    "ORDINAL_FIRST" -> firsts.add(word)
                    "BACK" -> backs.add(word)
                    "VIEW_VERB" -> views.add(word)
                    "MENU" -> menus.add(word)
                    "URL_BAR" -> urlBars.add(word)
                    "LABEL_SYNONYM" -> {
                        val canonical = if (p.size > 2) p[2].trim() else ""
                        if (canonical.isNotEmpty()) synonyms[word] = canonical
                    }
                    "OPEN" -> opens.add(word)
                }
            }
            // 文法助詞（介詞／方位助詞）：讓「在設定裡搜尋X」這種句型能被判定成 FIND，
            // 而不是交給分類器猜（實測分類器會判成 CREATE）。
            val prefixes = ArrayList<String>()
            val suffixes = ArrayList<String>()
            val conjunctions = ArrayList<String>()
            readLines(context, "$DB/ui_grammar_particles.tsv").forEach { line ->
                val p = line.split('\t')
                if (p.size < 2) return@forEach
                val term = p[0].trim()
                if (term.isEmpty()) return@forEach
                when (p[1].trim().uppercase()) {
                    "PREFIX" -> prefixes.add(term)
                    "SUFFIX" -> suffixes.add(term)
                    "CONJUNCTION" -> conjunctions.add(term)
                }
            }
            com.wae.core.predicate.LanguageWords.install(
                seps, leads, search, labels, verbs, opens,
                prefixes = prefixes,
                suffixes = suffixes,
                conjunctions = conjunctions,
                containerWords = containers,
                firstMarkers = firsts,
                backLabels = backs,
                labelSynonyms = synonyms,
                viewVerbs = views,
                menuLabels = menus,
                urlBarLabels = urlBars
            )

            val dismiss = ArrayList<String>()
            val never = ArrayList<String>()
            readLines(context, "$DB/ui_popup_buttons.tsv").forEach { line ->
                val c = line.split('	')
                if (c.size >= 2) {
                    when (c[0].trim()) {
                        "dismiss" -> dismiss.add(c[1].trim())
                        "never" -> never.add(c[1].trim())
                    }
                }
            }
            com.wae.core.predicate.PopupGate.install(dismiss, never)
        }

        val lexicon = PredicateLexicon(
            toggleWords = (on + off).toSet(),
            onWords = on,
            offWords = off,
            fillers = fillers,
            adjustVerbs = adjustVerbs,
            directions = directions,
            degreeSmall = small,
            degreeLarge = large,
            smallDelta = smallDelta,
            largeDelta = largeDelta,
            defaultDelta = ((smallDelta + largeDelta) / 2f).coerceIn(0.05f, 0.5f),
            valueLabels = valueLabels,
            appAliases = appAliases,
            settingsPkg = settingsPkg
        )

        val lookup = PredicateAgent.SectionLookup { label ->
            val folded = com.wae.core.predicate.TextFold.fold(label)
            sections.entries.firstOrNull { com.wae.core.predicate.TextFold.fold(it.key) == folded }
                ?.value?.toList().orEmpty()
        }

        return lexicon to lookup
    }

    private fun readLines(context: Context, path: String): List<String> = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { seq ->
            seq.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.toList()
        }
    }.getOrDefault(emptyList())
}
