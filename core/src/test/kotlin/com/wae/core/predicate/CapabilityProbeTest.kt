package com.wae.core.predicate

import org.junit.Test
import java.io.File

/**
 * **能力探針**（診斷用）：用「真實的 TSV 詞表」跑一遍編譯器，
 * 印出「這句話現在會被編譯成什麼述詞」。
 *
 * 為什麼要有它：使用者問「現在能執行什麼」時，
 * 答案不能靠推測，要能用一張表回答。
 *
 * 它同時是一份契約測試：常見說法必須編譯得出來（operable）。
 */
class CapabilityProbeTest {

    private fun db(name: String): File {
        val candidates = listOf(
            "../app/src/main/assets/open_source_ai_databases/$name",
            "app/src/main/assets/open_source_ai_databases/$name",
            "src/main/assets/open_source_ai_databases/$name"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("找不到 $name（工作目錄=${File(".").absolutePath}）")
    }

    private fun rows(name: String): List<List<String>> =
        db(name).readLines(Charsets.UTF_8)
            .map { it.trim('\uFEFF') }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { it.split('\t') }

    private fun buildLexicon(): Pair<PredicateLexicon, Map<String, List<Pair<String, String>>>> {
        val on = LinkedHashSet<String>()
        val off = LinkedHashSet<String>()
        rows("ui_toggle_state_words.tsv").forEach { p ->
            if (p.size >= 2 && p[0].isNotBlank()) {
                when (p[1].trim().uppercase()) {
                    "ON" -> on.add(p[0].trim())
                    "OFF" -> off.add(p[0].trim())
                }
            }
        }

        val directions = LinkedHashMap<String, Int>()
        rows("ui_value_direction.tsv").forEach { p ->
            if (p.size >= 2 && p[0].isNotBlank()) {
                when (p[1].trim().lowercase()) {
                    "up" -> directions[p[0].trim()] = 1
                    "down" -> directions[p[0].trim()] = -1
                }
            }
        }

        val small = LinkedHashSet<String>()
        val large = LinkedHashSet<String>()
        val fillers = LinkedHashSet<String>()
        val adjust = LinkedHashSet<String>()
        rows("ui_value_adjust_lexicon.tsv").forEach { p ->
            if (p.size >= 2 && p[0].isNotBlank()) {
                val term = p[0].trim()
                when (p[1].trim().uppercase()) {
                    "DELTA_SMALL" -> small.add(term)
                    "DELTA_LARGE" -> large.add(term)
                    "FILLER" -> fillers.add(term)
                    "UP", "DOWN" -> if (term.length >= 2) adjust.add(term.substring(0, 1))
                    else -> Unit
                }
            }
        }

        val aliases = LinkedHashMap<String, String>()
        rows("global_tw_cn_app_metadata.tsv").forEach { p ->
            if (p.size >= 3) {
                val pkg = p[0].trim()
                val name = p[1].trim()
                if (pkg.isNotEmpty() && name.isNotEmpty()) aliases[name] = pkg
            }
        }

        val sections = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        rows("ui_toggle_section_map.tsv").forEach { p ->
            if (p.size >= 2) {
                p[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach { kw ->
                    sections.getOrPut(p[0].trim()) { ArrayList() }.add(kw to kw)
                }
            }
        }

        val lex = PredicateLexicon(
            toggleWords = on + off,
            onWords = on,
            offWords = off,
            fillers = fillers,
            adjustVerbs = adjust,
            directions = directions,
            degreeSmall = small,
            degreeLarge = large,
            valueLabels = mapOf("亮度" to "亮度", "音量" to "音量"),
            appAliases = aliases,
            settingsPkg = aliases["設定"] ?: ""
        )
        return lex to sections
    }

    @Test
    fun `print what the app can compile today`() {
        val (lex, sections) = buildLexicon()
        val compiler = NlPredicateCompiler(lex)
        println("＝".repeat(72))
        println("詞表統計：開關詞 ${lex.onWords.size}+${lex.offWords.size}、方向詞 ${lex.directions.size}、" +
            "調整動詞 ${lex.adjustVerbs.size}、App 別名 ${lex.appAliases.size}、分類線索 ${sections.size}")
        println("＝".repeat(72))

        val commands = listOf(
            "打開 WiFi", "開 WiFi", "關掉 WiFi", "打開藍牙", "關藍牙",
            "開熱點", "關熱點", "開飛航", "關飛航", "開手電筒",
            "調亮一點", "調暗一點", "音量調大", "音量小一點", "螢幕調暗",
            "把亮度調到 40%", "亮度百分之四十", "音量調到 30",
            "打開相機", "打開設定", "打開計算機", "打開 Chrome",
            "長按這個圖片", "這個東西怎麼辦", "幫我訂機票"
        )
        var ok = 0
        for (c in commands) {
            val out = compiler.compile(c)
            val tag = when {
                out.relative != null -> "RELATIVE(dir=${out.relative!!.direction}, δ=${out.relative!!.delta})"
                out.goal != null -> out.goal.toString()
                else -> "—"
            }
            if (out.operable) ok += 1
            val mark = if (out.operable) "[OK]" else "[--]"
            println("$mark $c -> kind=${out.kind} conf=${out.confidence} $tag")
        }
        println("＝".repeat(72))
        println("可編譯：$ok / ${commands.size}")
        println("＝".repeat(72))

        // 契約：最常用的開關一定要編譯得出來
        for (must in listOf("打開 WiFi", "開熱點", "把亮度調到 40%", "調亮一點", "打開相機")) {
            assert(compiler.compile(must).operable) { "必須可編譯：$must" }
        }
        // 「長按」不能被誤判成調整
        assert(!compiler.compile("長按這個圖片").operable) { "長按不可編譯成述詞" }
    }

    private companion object {
        fun String.format(vararg args: Any?): String = String.format(this, *args)
        operator fun String.invoke() = this
    }
}
