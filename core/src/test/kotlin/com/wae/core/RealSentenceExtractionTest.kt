package com.wae.core

import com.wae.core.predicate.LanguageWords
import com.wae.core.predicate.SlotExtractor
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 診斷用：用**出貨的 TSV** 載入語言層，然後看幾個實際失敗的句子被抽成什麼。
 *
 * 為什麼要這支：真機上一題要 25 秒，先在 JVM 把「斷句／槽位」這層看穿，
 * 才不會把時間花在重複跑真機上。
 */
class RealSentenceExtractionTest {

    private fun repoFile(rel: String): File? =
        listOf(File("../$rel"), File(rel), File("E:/WAE/NewAndroidApp/$rel")).firstOrNull { it.exists() }

    private fun installFromShippedData(): Boolean {
        val words = repoFile("app/src/main/assets/open_source_ai_databases/ui_language_words.tsv")
        val particles = repoFile("app/src/main/assets/open_source_ai_databases/ui_grammar_particles.tsv")
        if (words == null || particles == null) return false

        val seps = ArrayList<String>()
        val leads = ArrayList<String>()
        val search = HashSet<String>()
        val verbs = ArrayList<String>()
        val opens = ArrayList<String>()
        val containers = ArrayList<String>()
        val synonyms = HashMap<String, String>()
        for (raw in words.readLines(Charsets.UTF_8)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 2) continue
            val w = p[1].trim()
            if (w.isEmpty()) continue
            when (p[0].trim().uppercase()) {
                "SEPARATOR" -> seps.add(w)
                "LEAD" -> leads.add(w)
                "SEARCH" -> search.add(w)
                "TASKVERB" -> verbs.add(w)
                "OPEN" -> opens.add(w)
                "CONTAINER" -> containers.add(w)
                "LABEL_SYNONYM" -> {
                    val canonical = if (p.size > 2) p[2].trim() else ""
                    if (canonical.isNotEmpty()) synonyms[w] = canonical
                }
            }
        }
        val prefixes = ArrayList<String>()
        val suffixes = ArrayList<String>()
        val conjunctions = ArrayList<String>()
        for (raw in particles.readLines(Charsets.UTF_8)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 2) continue
            val term = p[0].trim()
            if (term.isEmpty()) continue
            when (p[1].trim().uppercase()) {
                "PREFIX" -> prefixes.add(term)
                "SUFFIX" -> suffixes.add(term)
                "CONJUNCTION" -> conjunctions.add(term)
            }
        }
        LanguageWords.install(
            separators = seps, leads = leads, searchWords = search, labels = emptyMap(),
            taskVerbs = verbs, openWords = opens,
            prefixes = prefixes, suffixes = suffixes, conjunctions = conjunctions,
            containerWords = containers,
            labelSynonyms = synonyms
        )
        return true
    }

    @Test
    fun `show extraction for the sentences that failed on device`() {
        if (!installFromShippedData()) return
        val cases = listOf(
            "在時鐘看世界時間" to listOf("時鐘"),
            "在時鐘查看鬧鐘清單" to listOf("時鐘"),
            "把亮度調高" to emptyList(),
            "把音量調低" to emptyList(),
            "打開 Wi-Fi" to emptyList(),
            "在設定裡搜尋藍牙" to listOf("設定"),
            "在 Chrome 搜尋今天天氣並打開第一個結果" to listOf("Chrome"),
            // 實測回報：小寫的 App 名會讓整句變成查詢字串
            "在chrome搜尋貓咪" to listOf("Chrome"),
            "在 CHROME 搜尋貓咪" to listOf("Chrome")
        )
        for ((sentence, known) in cases) {
            val s = SlotExtractor.extract(sentence, knownNames = known)
            println("EXTRACT '$sentence' -> time=${s.time} person=${s.person} content=${s.content}")
        }
        // 至少確保有跑出東西（實際值看測試輸出）
        assertNotNull(SlotExtractor.extract("把亮度調高").content)
    }
}
