package com.wae.core.predicate

/**
 * 編譯器需要的**詞彙資料**（全部由呼叫端從既有 TSV 注入；核心不讀檔案、不碰 Android）。
 *
 * 這裡只放「詞屬於哪一類」，不放「這句話該做什麼」——
 * 後者就是技能，而我們要把它消滅掉。
 */
class PredicateLexicon(
    /** 開關動詞（開、關、開啟、關閉、打開、關掉…） */
    val toggleWords: Set<String> = emptySet(),
    /** 表示「開」的詞（含英文 on / enable） */
    val onWords: Set<String> = emptySet(),
    /** 表示「關」的詞 */
    val offWords: Set<String> = emptySet(),
    /** 填充詞（幫我、請、把、將…） */
    val fillers: Set<String> = emptySet(),
    /** 調整類動詞（調、設、改、轉、推、拉…） */
    val adjustVerbs: Set<String> = emptySet(),
    /** 方向詞 → +1 / −1（繁簡各自獨立） */
    val directions: Map<String, Int> = emptyMap(),
    /** 小幅度詞 */
    val degreeSmall: Set<String> = emptySet(),
    /** 大幅度詞 */
    val degreeLarge: Set<String> = emptySet(),
    /** 小幅度值（0..1） */
    val smallDelta: Float = 0.10f,
    /** 大幅度值（0..1） */
    val largeDelta: Float = 0.30f,
    /** 沒有程度詞時的預設幅度 */
    val defaultDelta: Float = 0.15f,
    /** 數值目標的標籤線索（亮度→亮度、音量→音量…）；key 是折疊後的詞，value 是畫面標籤 */
    val valueLabels: Map<String, String> = emptyMap(),
    /** App 別名 → 套件（折疊後的別名 → pkg） */
    val appAliases: Map<String, String> = emptyMap(),
    /** 系統設定 App 的套件（用來開關類指令先開設定；由資料提供，不硬編碼） */
    val settingsPkg: String = "",
    /** 「百分之 X」與「X%」的解析 */
    val percentRegex: Regex = Regex("""(\d{1,3}(?:\.\d+)?)\s*[%％]|百分之\s*(\d{1,3}(?:\.\d+)?)""")
) {

    /** 開關方向：true＝要開、false＝要關、null＝不是開關指令。 */
    fun onOffOf(foldedText: String): Boolean? {
        for (w in onWords) if (foldedText.contains(TextFold.fold(w))) return true
        for (w in offWords) if (foldedText.contains(TextFold.fold(w))) return false
        return null
    }

    /** 把殘餘詞對到畫面上真正的標籤（用 valueLabels 的反向線索）；對不到就原樣回傳。 */
    fun canonicalTargetOf(folded: String): String? {
        for ((k, v) in valueLabels) {
            if (folded.contains(TextFold.fold(k))) return v
        }
        // 目標本身可能就是畫面標籤（例如「熱點」「藍牙」）：原樣回傳，交給 TextFold 比對
        return if (folded.isNotEmpty()) folded else null
    }

    /** 這段文字裡最長的調整動詞是否出現。 */
    fun hasAdjustVerb(foldedText: String): Boolean =
        adjustVerbs.any { it.isNotEmpty() && foldedText.contains(TextFold.fold(it)) }

    /** 方向詞：回傳 +1 / −1；同時出現就取最長（避免「提高」被「高」搶）。 */
    fun directionOf(foldedText: String): Int? {
        var best: String? = null
        for (w in directions.keys) {
            val f = TextFold.fold(w)
            if (f.isNotEmpty() && foldedText.contains(f) && (best == null || f.length > TextFold.fold(best).length)) {
                best = w
            }
        }
        return best?.let { directions[it] }
    }

    /** 程度：回傳幅度；沒有程度詞回 null。 */
    fun degreeOf(foldedText: String): Float? {
        if (degreeLarge.any { TextFold.fold(it).let { f -> f.isNotEmpty() && foldedText.contains(f) } }) return largeDelta
        if (degreeSmall.any { TextFold.fold(it).let { f -> f.isNotEmpty() && foldedText.contains(f) } }) return smallDelta
        return null
    }

    /** 數值目標的標籤線索（「亮度」「音量」）。 */
    fun valueLabelHintsFor(foldedText: String): String? {
        for ((k, v) in valueLabels) {
            val f = TextFold.fold(k)
            if (f.isNotEmpty() && foldedText.contains(f)) return v
        }
        return null
    }

    /** App 別名 → 套件。 */
    fun pkgOf(foldedText: String): Pair<String, String>? {
        var best: Pair<String, String>? = null
        for ((alias, pkg) in appAliases) {
            val f = TextFold.fold(alias)
            if (f.isNotEmpty() && foldedText.contains(f) &&
                (best == null || f.length > TextFold.fold(best!!.first).length)
            ) {
                best = alias to pkg
            }
        }
        return best?.let { (alias, pkg) -> pkg to alias }
    }
}
