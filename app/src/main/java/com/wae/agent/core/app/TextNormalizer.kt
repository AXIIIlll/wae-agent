package com.wae.agent.core.app

/**
 * 文字正規化：讓「同一個 App 的不同寫法」在比對前變成同一個字串。
 *
 * 為什麼需要（實例）：
 *  - 簡繁：`紅果短劇` / `红果短剧`（使用者手機是簡體 App 名稱、或跨區 App）
 *  - ASR 同音錯字：`高帖`→`高鐵`、`鋼鐵時刻表`→`高鐵時刻表`
 *  - 只講一半：`短句` 對 `短劇`（同音，字不同）
 *
 * 資料來源（全部資料驅動，不寫死清單）：
 *  - `opencc_zh_tw_cn_lexicon.tsv`（OpenCC，26k 行，CHAR 簡 繁）→ 建單字轉換表
 *  - `stopwords_and_asr_homophones.tsv` 的 `ASR` 行（錯誤寫法 → 正確寫法）
 *
 * 純函式，JVM 可測。
 */
object TextNormalizer {

    /** 簡體 → 繁體（單字）。以繁體為正規形式。 */
    private val simpToTrad = HashMap<Char, Char>(16384)

    /** 繁體 → 簡體（單字）。用來產生「同一個 App 的另一種寫法」。 */
    private val tradToSimp = HashMap<Char, Char>(16384)

    /** ASR 錯誤片語 → 正確片語；依長度由長到短套用，避免短詞先吃掉長詞。 */
    private val asrPairs = ArrayList<Pair<String, String>>()

    @Volatile
    var loaded: Boolean = false
        private set

    fun resetForTest() {
        synchronized(simpToTrad) { simpToTrad.clear() }
        synchronized(asrPairs) { asrPairs.clear() }
        loaded = false
    }

    fun loadFromLines(openccLines: Sequence<String>, asrLines: Sequence<String>) {
        val map = HashMap<Char, Char>(16384)
        val reverse = HashMap<Char, Char>(16384)
        for (raw in openccLines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 3 || p[0] != "CHAR") continue
            val simp = p[1]
            val trad = p[2]
            if (simp.length == 1 && trad.length == 1 && simp[0] != trad[0]) {
                map[simp[0]] = trad[0]
                reverse[trad[0]] = simp[0]
            }
        }
        val pairs = ArrayList<Pair<String, String>>()
        for (raw in asrLines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 3 || p[0] != "ASR") continue
            val wrong = p[1].trim()
            val right = p[2].trim()
            if (wrong.isNotEmpty() && right.isNotEmpty() && wrong != right) {
                pairs.add(wrong to right)
            }
        }
        pairs.sortByDescending { it.first.length }
        synchronized(simpToTrad) {
            simpToTrad.clear()
            simpToTrad.putAll(map)
        }
        synchronized(tradToSimp) {
            tradToSimp.clear()
            tradToSimp.putAll(reverse)
        }
        synchronized(asrPairs) {
            asrPairs.clear()
            asrPairs.addAll(pairs)
        }
        loaded = true
    }

    /** 只保留文字與數字（標點、空白、符號一律去掉）。 */
    private fun isNoise(c: Char): Boolean = !c.isLetterOrDigit()

    /**
     * 正規化：小寫 → 去標點空白 → 套 ASR 修正 → 簡轉繁（逐字）。
     * 例：「打開 紅果短剧!」→「打開紅果短劇」
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val cleaned = StringBuilder(text.length)
        for (c in text.lowercase()) if (!isNoise(c)) cleaned.append(c)
        var s = cleaned.toString()
        if (s.isEmpty()) return ""
        synchronized(asrPairs) {
            for ((wrong, right) in asrPairs) {
                if (s.contains(wrong)) s = s.replace(wrong, right)
            }
        }
        val sb = StringBuilder(s.length)
        synchronized(simpToTrad) {
            for (c in s) sb.append(simpToTrad[c] ?: c)
        }
        return sb.toString()
    }

    /** 繁體 → 簡體（逐字）。App 名稱的另一種寫法用得到。 */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        synchronized(tradToSimp) {
            for (c in text.lowercase()) sb.append(tradToSimp[c] ?: c)
        }
        return sb.toString()
    }

    /** 正規化後的 bigram 相似度（0..1）。 */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na == nb) return 1f
        var inter = 0
        val ba = bigrams(na)
        val bb = bigrams(nb)
        if (ba.isEmpty() || bb.isEmpty()) return 0f
        for (x in ba) if (bb.contains(x)) inter++
        val union = ba.size + bb.size - inter
        return if (union == 0) 0f else inter.toFloat() / union.toFloat()
    }

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }
}
