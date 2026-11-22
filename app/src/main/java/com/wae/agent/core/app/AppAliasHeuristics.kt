package com.wae.agent.core.app

/**
 * App 別名的**衍生**規則（不靠人工補清單）。
 *
 * 目標：同一個 App 的常見寫法都能對上——
 *   紅果短劇 / 红果短剧（簡繁）
 *   YouTube / youtube / YouTube Music（大小寫、空格）
 *   Instagram / IG（短名，來自 metadata 別名）
 *   某某 App / 某某（去後綴）
 *
 * 純函式，JVM 可測。全部由規則與 metadata 衍生，不依賴任何模型。
 */
object AppAliasHeuristics {

    private val suffix = Regex(
        "(?i)\\s*(app|應用程式|應用|客户端|客戶端|软件|軟體|pro|hd|lite|free|免費版|免费版|國際版|国际版|繁體版|繁体版)$"
    )

    /** 回傳「不等於原 label」的衍生別名。 */
    fun derive(label: String): List<String> {
        val out = LinkedHashSet<String>()
        val t = label.trim()
        if (t.isEmpty()) return emptyList()

        val lower = t.lowercase()
        if (lower != t) out.add(lower)

        val noSpace = t.replace(" ", "").replace("\u3000", "").lowercase()
        if (noSpace.isNotEmpty() && noSpace != lower) out.add(noSpace)

        val simp = TextNormalizer.toSimplified(t)
        if (simp.isNotEmpty() && simp != lower) out.add(simp)

        val trad = TextNormalizer.normalize(t)
        if (trad.isNotEmpty() && trad != lower && trad != simp) out.add(trad)

        val stripped = t.replace(suffix, "").trim()
        if (stripped.length >= 2 && stripped != t) out.add(stripped)

        out.remove(t)
        return out.filter { it.isNotBlank() && it.length >= 2 }
    }

    /** 合併：原本的別名 + 衍生別名（去重、去空）。 */
    fun withDerived(label: String, base: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (b in base) if (b.isNotBlank()) out.add(b)
        for (d in derive(label)) out.add(d)
        return out.toList()
    }
}
