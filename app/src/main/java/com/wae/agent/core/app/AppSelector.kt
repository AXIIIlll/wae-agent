package com.wae.agent.core.app

/**
 * 開放式意圖的 App 選擇器（純函式，JVM 可測）。
 *
 * 為什麼不用 embedding 當第一順位：
 * 「我想看劇」和 App 名稱（Netflix / Disney+ / 紅果短劇）在向量空間不接近，
 * 直接比 cosine 會被 0.42 門檻擋掉、變成永遠反問。正確順序是：
 *   意圖詞 → 類別（`AppIntentCategory`）→ 已安裝 App ∩ 類別 → 類別內排序。
 *
 * 排序訊號（由強到弱）：
 *   1. 類別命中（權重來自意圖表）
 *   2. 別名命中（App metadata 的中文別名，例如「追劇」「愛奇藝」）
 *   3. 名稱命中
 *   4. 使用頻率（UsageStats，取不到就 0）
 *   5. 字元 bigram 相似度（最後的微弱訊號）
 *
 * 純函式：不碰 Android API，`usageCounts` 由呼叫端提供。
 */
object AppSelector {

    data class AppCandidate(
        val label: String,
        val pkg: String,
        val category: String,
        val aliases: List<String> = emptyList()
    )

    data class Scored(
        val candidate: AppCandidate,
        val score: Float,
        val categoryHit: Boolean,
        val aliasHit: Boolean,
        val labelHit: Boolean
    )

    data class Decision(
        val ranked: List<Scored>,
        val matchedCategories: List<String>,
        /** 直接採用最高分（不必反問） */
        val confident: Boolean,
        /** 需要反問使用者的前三個選項 */
        val options: List<String>,
        val reason: String
    ) {
        val best: Scored? get() = ranked.firstOrNull()
    }

    /** 分數門檻：類別命中 2.0 起跳，所以 1.5 代表「至少名稱/別名命中」。 */
    const val CONFIDENT_SCORE = 1.5f

    /** Top1 與 Top2 太近就反問（0.25 約等於「同一類別、名稱都像」）。 */
    const val TIE_GAP = 0.25f

    fun select(
        goal: String,
        installed: List<AppCandidate>,
        usageCounts: Map<String, Long> = emptyMap(),
        maxOptions: Int = 3,
        /**
         * G：使用者偏好修正（`AppChoiceMemo.bias`）。
         * 參數是 (類別, App 名稱)，回傳加分／扣分；預設 0 代表沒有記憶。
         */
        bias: (String, String) -> Float = { _, _ -> 0f }
    ): Decision {
        if (goal.isBlank() || installed.isEmpty()) {
            return Decision(emptyList(), emptyList(), false, emptyList(), "no_candidates")
        }
        val matches = AppIntentCategory.match(goal)
        val matchedCats = matches.map { it.first }
        val catWeight = matches.toMap()

        val scored = installed.map { app ->
            val base = score(goal, app, catWeight, usageCounts)
            val bonus = runCatching { bias(app.category, app.label) }.getOrDefault(0f)
            if (bonus == 0f) base else base.copy(score = base.score + bonus)
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.candidate.label })

        val best = scored.firstOrNull()
        val second = scored.getOrNull(1)
        val gap = if (best != null && second != null) best.score - second.score else Float.MAX_VALUE
        val confident = best != null && best.score >= CONFIDENT_SCORE && gap >= TIE_GAP

        val options = scored.filter { it.score > 0f }
            .take(maxOptions)
            .map { it.candidate.label }

        val reason = when {
            best == null -> "no_candidates"
            best.score < CONFIDENT_SCORE -> "low_score"
            gap < TIE_GAP -> "tie"
            else -> "confident"
        }
        return Decision(scored, matchedCats, confident, options, reason)
    }

    /** 單一候選評分（公開給測試與除錯用）。 */
    fun score(
        goal: String,
        app: AppCandidate,
        categoryWeights: Map<String, Float>,
        usageCounts: Map<String, Long>
    ): Scored {
        val g = normalize(goal)
        val cat = app.category.trim()
        val catHit = cat.isNotEmpty() && categoryWeights.containsKey(cat)
        var s = if (catHit) 2.0f * (categoryWeights[cat] ?: 1f) else 0f

        val aliasHit = app.aliases.any { a -> a.isNotBlank() && g.contains(normalize(a)) }
        if (aliasHit) s += 1.5f

        val labelHit = g.contains(normalize(app.label))
        if (labelHit) s += 1.2f
        else s += 0.5f * bigramSimilarity(g, normalize(app.label))

        // 使用頻率：最多 +0.4，避免常用 App 壓過類別正確性
        val usage = usageCounts[app.pkg] ?: 0L
        if (usage > 0L) {
            val max = usageCounts.values.maxOrNull() ?: 1L
            s += 0.4f * (usage.toFloat() / max.toFloat())
        }
        return Scored(app, s, catHit, aliasHit, labelHit)
    }

    // ── 純文字工具 ────────────────────────────────────────────────

    fun normalize(text: String): String =
        TextNormalizer.normalize(text).replace("台", "臺")

    /** 正規化（簡繁＋ASR 同音）後的 bigram Jaccard（0..1）。 */
    fun bigramSimilarity(a: String, b: String): Float = TextNormalizer.similarity(a, b)
}
