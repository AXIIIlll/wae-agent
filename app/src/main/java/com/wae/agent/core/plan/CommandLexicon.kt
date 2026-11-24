package com.wae.agent.core.plan

import android.content.Context
import com.wae.core.plan.Tokenizer

/**
 * 指令切詞（L0）的 App 端接線：把 `command_lexicon.tsv` 的詞載進來，交給 [Tokenizer]。
 *
 * 為什麼要獨立一支：詞典是資料（AGENTS §4 不得內建詞表），載入屬於 App 層，
 * 切詞演算法留在 core（純函式、可測試）。
 *
 * 目前用途：
 *  - 診斷：印出「這句話被切成什麼」，是除錯斷句問題最快的方式
 *  - 明確度訊號：一句話有多少比例是已知詞，可作為「這句話我懂不懂」的參考
 */
object CommandLexicon {

    @Volatile
    private var terms: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val lines = com.wae.core.util.Degrade.orEmpty("lexicon:load") {
            context.assets.open("open_source_ai_databases/command_lexicon.tsv")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
        }
        val out = HashSet<String>(1024)
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val term = line.substringBefore('\t').trim()
            // 只收 2 字以上、非 ASCII 為主的詞；單字留給 fallback
            if (term.length in 2..8) out.add(term)
        }
        if (out.isNotEmpty()) {
            terms = out
            loaded = true
            android.util.Log.i("WAE_LEXICON", "loaded ${out.size} terms")
        }
    }

    /** 切詞（詞典沒載入時退化為單字）。 */
    fun words(context: Context, text: String): List<String> {
        ensureLoaded(context)
        return Tokenizer.words(text, terms)
    }

    /**
     * 這句話有多少比例由已知詞組成（0..1）。
     *
     * 用途：作為「明確度」的輔助訊號——比例很低通常代表這句話不是指令，
     * 或是用了我們沒見過的說法。**只用來記錄，不直接改變行為**。
     */
    fun coverage(context: Context, text: String): Float {
        ensureLoaded(context)
        return Tokenizer.coverage(text, terms)
    }
}
