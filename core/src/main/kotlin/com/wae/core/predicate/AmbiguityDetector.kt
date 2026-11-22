package com.wae.core.predicate

import com.wae.core.model.ControlKind

/**
 * **語意模糊偵測**（ambiguity gate）。
 *
 * 為什麼要有這一層：
 *  現在的編譯器（規則 + 3 MB tiny BERT）**永遠會硬編出一個 Φ**——
 *  它沒有「我不確定」這個輸出。於是「調一下」會被當成「調亮度」，
 *  「開那個」會被當成某個開關，然後**安靜地做錯事**。
 *
 *  這個模組只回答一個問題：
 *  > 這句話／這個畫面，夠不夠具體到可以動手？
 *
 * 三種模糊（彼此獨立，可同時成立）：
 *  1. **指涉模糊**：目標詞在畫面上對到多個元素（兩個「確定」、三個「Wi-Fi」）
 *  2. **規格模糊**：缺目標（「調一下」）、缺幅度（「調亮」不知道多少）
 *  3. **決策模糊**：分數前二名太接近（交給 [ofDecision]）
 *
 * 輸出帶一句**可以直接問使用者的話**（不含 emoji，符合專案規範）。
 */
object AmbiguityDetector {

    enum class Kind {
        /** 不模糊，可以動手。 */
        NONE,
        /** 有目標詞，但畫面上對到多個。 */
        MULTIPLE_CANDIDATES,
        /** 沒有目標詞（不知道要動什麼）。 */
        UNDEFINED_TARGET,
        /** 有目標但沒有幅度（「調亮」）。 */
        MISSING_DEGREE,
        /** 前二名分數太近。 */
        CLOSE_SCORES,
        /** 根本編譯不出來（不是模糊，是聽不懂）。 */
        UNKNOWN_INTENT
    }

    /**
     * @param blocking true＝**不要執行**，先問；false＝可以執行但要知道自己在猜
     * @param score 0..1 的模糊程度（1＝最模糊）
     * @param candidates 造成模糊的候選文字（給反問句用）
     * @param question 可以直接顯示給使用者的反問句
     */
    data class Verdict(
        val kind: Kind,
        val blocking: Boolean,
        val score: Float,
        val candidates: List<String> = emptyList(),
        val question: String = ""
    ) {
        val isAmbiguous: Boolean get() = kind != Kind.NONE
    }

    val CLEAR = Verdict(Kind.NONE, blocking = false, score = 0f)

    /**
     * 語言層 + 畫面層的模糊判定。
     *
     * @param compiled 編譯結果（目標 Φ 或相對目標）
     * @param world 目前畫面（可為 null＝還沒有畫面）
     * @param maxCandidates 反問句最多列幾個（避免問句太長）
     */
    fun ofCompiled(
        compiled: NlPredicateCompiler.Compiled,
        world: PredicateWorld?,
        maxCandidates: Int = 3
    ): Verdict {
        // 0. 根本聽不懂：這不是模糊，是未知 → 仍然要問，但用不同說法
        if (!compiled.operable) {
            return Verdict(
                kind = Kind.UNKNOWN_INTENT,
                blocking = true,
                score = 1f,
                question = "我不確定你要做什麼。可以換個說法嗎？例如「開 WiFi」或「把亮度調到 40%」。"
            )
        }

        val goal = compiled.goal

        // 1. 有目標但沒有文字原子 → 不知道要動哪一個
        if (goal != null) {
            val atoms = PredicateEval.atoms(goal)
            val hasLabel = atoms.any { it is Atom.LabelContains || it is Atom.TextHashIs }
            if (!hasLabel) {
                return Verdict(
                    kind = Kind.UNDEFINED_TARGET,
                    blocking = true,
                    score = 0.9f,
                    question = "你要調整哪一個項目？我聽不出目標。"
                )
            }

            // 2. 指涉模糊：同一個目標詞在畫面上對到多個元素
            if (world != null) {
                val labelAtom = atoms.filterIsInstance<Atom.LabelContains>().firstOrNull()
                val wantKind = atoms.filterIsInstance<Atom.KindIs>().firstOrNull()?.kind
                if (labelAtom != null) {
                    val hits = world.controls.filter { c ->
                        TextFold.containsFold(c.label, labelAtom.text) &&
                            (wantKind == null || c.kind == wantKind || c.kind == ControlKind.UNKNOWN)
                    }
                    val distinct = hits.map { it.label.trim() }.filter { it.isNotEmpty() }.distinct()
                    if (distinct.size >= 2) {
                        val shown = distinct.take(maxCandidates)
                        return Verdict(
                            kind = Kind.MULTIPLE_CANDIDATES,
                            blocking = true,
                            score = (1f - 1f / distinct.size.toFloat()).coerceIn(0.5f, 0.98f),
                            candidates = shown,
                            question = "我找到 ${distinct.size} 個可能的目標：" +
                                shown.joinToString("、") + "。你要哪一個？"
                        )
                    }
                }
            }
        }

        // 3. 相對目標：沒有目標詞或沒有幅度
        val rel = compiled.relative
        if (rel != null) {
            if (rel.label == null) {
                return Verdict(
                    kind = Kind.UNDEFINED_TARGET,
                    blocking = true,
                    score = 0.85f,
                    question = "你要調整什麼？例如「調亮一點」或「音量小一點」。"
                )
            }
            if (rel.delta <= 0.001f) {
                return Verdict(
                    kind = Kind.MISSING_DEGREE,
                    blocking = false,
                    score = 0.4f,
                    question = "要調多少？我預設會調一小格。"
                )
            }
        }

        return CLEAR
    }

    /**
     * 決策層的模糊：分數前二名太接近。
     *
     * @param top1 最高的分數（0..1）
     * @param top2 第二高的分數（沒有第二個就傳 0f）
     * @param gap 容忍差距（小於它就視為模糊）
     */
    fun ofDecision(
        top1: Float,
        top2: Float,
        labels: List<String> = emptyList(),
        gap: Float = 0.08f,
        absFloor: Float = 0.45f
    ): Verdict {
        if (top1 < absFloor) {
            return Verdict(
                kind = Kind.UNDEFINED_TARGET,
                blocking = true,
                score = 0.8f,
                question = "我看不出哪個元素符合你要的目標。可以再說得具體一點嗎？"
            )
        }
        if (top1 - top2 < gap) {
            return Verdict(
                kind = Kind.CLOSE_SCORES,
                blocking = true,
                score = 0.7f,
                candidates = labels,
                question = if (labels.isNotEmpty()) {
                    "有幾個選項分數很接近：" + labels.take(3).joinToString("、") + "。你要哪一個？"
                } else {
                    "我不確定要選哪一個，可以再說得具體一點嗎？"
                }
            )
        }
        return CLEAR
    }
}
