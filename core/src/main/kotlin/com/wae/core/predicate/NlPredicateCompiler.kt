package com.wae.core.predicate

/**
 * 自然語言 → 述詞 的**規則前端**（不碰 Android、不讀檔案；資料由呼叫端注入）。
 *
 * 設計原則（對應「不能以技能為單位」）：
 *  - 這裡**沒有**「句子 → 技能 id」的對照表；只有「句子 → 目標述詞」的組合規則。
 *  - 所有詞彙都來自既有資料表（方向詞、動詞、開關詞、程度詞、App 別名），
 *    詞表只提供「這個詞屬於哪一類」，不提供「這句話要怎麼做」。
 *  - 編譯結果一定要能過 [PredicateEval.wellFormed]；過不了就回 null，不要猜。
 */
class NlPredicateCompiler(private val lex: PredicateLexicon) {

    /** 編譯結果。 */
    data class Compiled(
        /** 絕對目標（可直接 eval）。 */
        val goal: Phi?,
        /** 相對目標（需要先讀當前值才能變成絕對目標；例如「調亮一點」）。 */
        val relative: Relative? = null,
        /** 若要換 App，目標套件。 */
        val targetPkg: String? = null,
        val kind: GoalKind,
        /** 信心 0..1（由證據累積，不是句型命中）。 */
        val confidence: Float,
        /** 無法編譯時的原因（給上層回報，不要靜默）。 */
        val issues: List<String> = emptyList()
    ) {
        val operable: Boolean get() = goal != null || relative != null
    }

    data class Relative(val direction: Int, val delta: Float, val label: String?)

    enum class GoalKind { TOGGLE, VALUE, NAVIGATE, UNKNOWN }

    /**
     * 編譯一句話。
     *
     * 判定順序（先絕對、後相對、最後才是換 App）：
     *  1. 百分比／數字 → 絕對數值述詞
     *  2. 開關詞 + 目標 → 開關述詞
     *  3. 方向詞 + 證據（動詞或程度詞）→ 相對數值
     *  4. App 別名 → 換 App
     *  5. 以上皆非 → UNKNOWN（誠實）
     */
    fun compile(rawText: String): Compiled {
        val text = TextFold.fold(rawText)
        if (text.isEmpty()) return unknown("空指令")

        // 1. 絕對數值：40% / 百分之40 / 40
        lex.percentRegex.find(rawText)?.let { m ->
            val num = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            val v = num?.toFloatOrNull()?.div(100f)
            if (v != null && v in 0f..1f) {
                val label = lex.valueLabelHintsFor(text)
                val atoms = buildList {
                    if (label != null) add(Atom.LabelContains(label))
                    add(Atom.KindIs(com.wae.core.model.ControlKind.SLIDER))
                    add(Atom.ValueIn(v, v))
                }
                if (label == null) return unknown("有數值但不知道要調哪個元件")
                return compiled(Phi.Exists(atoms), GoalKind.VALUE, 0.85f)
            }
        }

        // 2. 開關：開/關 + 目標
        val onOff = lex.onOffOf(text)
        if (onOff != null) {
            val target = stripToggleWords(text)
            if (target.isNotEmpty()) {
                // 「打開相機」「打開設定」是**開 App**，不是切換開關。
                // 判斷依據是資料（App 別名表），不是寫死的例外清單。
                lex.pkgOf(TextFold.fold(target))?.let { (pkg, _) ->
                    return Compiled(Phi.PkgIs(pkg), null, pkg, GoalKind.NAVIGATE, 0.92f)
                }
                val label = lex.canonicalTargetOf(target) ?: target
                // 開關的驗證不能只靠畫面（很多 App 不暴露 isChecked）。
                // key 由標籤折疊推導（wifi → "wifi"），查不到就自動忽略——
                // 所以不需要「標籤 → 狀態key」的對照表。
                val atoms = listOf(
                    Atom.LabelContains(label),
                    Atom.KindIs(com.wae.core.model.ControlKind.TOGGLE),
                    Atom.CheckedIs(onOff),
                    Atom.Observable(TextFold.fold(label), onOff)
                )
                return compiled(
                    Phi.Exists(atoms),
                    GoalKind.TOGGLE,
                    confidence = 0.9f,
                    pkg = lex.settingsPkg
                )
            }
            return unknown("只知道開或關，不知道要開什麼")
        }

        // 3. 方向詞（相對數值）
        val dir = lex.directionOf(text)
        if (dir != null) {
            val hasVerb = lex.hasAdjustVerb(text)
            val degree = lex.degreeOf(text)
            if (hasVerb || degree != null) {
                val label = lex.valueLabelHintsFor(text)
                val delta = degree ?: lex.defaultDelta
                return Compiled(
                    goal = null,
                    relative = Relative(dir, delta, label),
                    kind = GoalKind.VALUE,
                    confidence = if (hasVerb && degree != null) 0.85f else 0.7f
                )
            }
            return unknown("有方向詞但沒有調整動詞或程度詞（可能是『長按』這類誤判）")
        }

        // 4. App 別名 → 換 App
        lex.pkgOf(text)?.let { (pkg, alias) ->
            return Compiled(Phi.PkgIs(pkg), null, pkg, GoalKind.NAVIGATE, 0.9f).also {
                require(alias.isNotEmpty())
            }
        }

        return unknown("無法從這句話得到目標述詞")
    }

    /** 相對目標 → 絕對目標（需要當前值）。 */
    fun absoluteOf(relative: Relative, current: Float): Phi {
        val target = (current + relative.direction * relative.delta).coerceIn(0f, 1f)
        val atoms = buildList {
            relative.label?.let { add(Atom.LabelContains(it)) }
            add(Atom.KindIs(com.wae.core.model.ControlKind.SLIDER))
            add(Atom.ValueIn(target, target))
        }
        return Phi.Exists(atoms)
    }

    // ── 內部 ──

    private fun compiled(phi: Phi, kind: GoalKind, confidence: Float, pkg: String? = null): Compiled {
        val issues = PredicateEval.wellFormed(phi)
        return if (issues.isEmpty()) Compiled(phi, null, pkg, kind, confidence)
        else Compiled(null, null, pkg, GoalKind.UNKNOWN, 0f, issues)
    }

    private fun unknown(reason: String) =
        Compiled(null, null, null, GoalKind.UNKNOWN, 0f, listOf(reason))

    private fun stripToggleWords(text: String): String {
        var t = text
        for (w in lex.toggleWords.sortedByDescending { it.length }) {
            if (t.startsWith(w)) t = t.removePrefix(w)
            if (t.endsWith(w)) t = t.removeSuffix(w)
        }
        for (f in lex.fillers) t = t.replace(f, "")
        return t.trim()
    }
}
