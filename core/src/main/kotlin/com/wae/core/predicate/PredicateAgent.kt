package com.wae.core.predicate

import com.wae.core.model.ControlKind

/**
 * 代理能做的**原語**（與 App 無關、與裝置無關）。
 *
 * 這是「通用」的第二根柱子：動作集固定且極小，
 * 所有「技能」都被還原成這些原語的序列，而序列由 [PredicateAgent] 算出來。
 */
sealed class AgentAction {

    /** 點一個控制項（用穩定鍵綁定，不用座標）。 */
    data class Tap(val key: String, val label: String = "") : AgentAction()

    /** 把滑桿設到目標比例（0..1）。 */
    data class SetValue(val key: String, val fraction: Float, val label: String = "") : AgentAction()

    /** 捲動找東西（+1 往下、-1 往上）。 */
    data class Scroll(val direction: Int) : AgentAction()

    /** 返回上一層。 */
    data class Back(val reason: String) : AgentAction()

    /** 開啟某個套件（來源只能來自資料表或使用者明示，不可硬編碼）。 */
    data class Launch(val pkg: String) : AgentAction()

    /** 點某個「分類入口」列（例如「網路與網際網路」），用來鑽進子頁。 */
    data class OpenSection(val sectionKey: String, val sectionLabel: String) : AgentAction()

    /**
     * **用 App 自己的搜尋**：把目標詞打進搜尋框。
     *
     * 為什麼需要：目標藏在三層底下時，與其一層一層鑽（3–5 步、可能鑽錯），
     * 不如叫 App 自己找（1 步）。設定頁幾乎都有搜尋框，這是通用性最高的一招。
     */
    data class TypeSearch(val fieldKey: String, val query: String) : AgentAction()

    /** 目標已成立。 */
    data object Done : AgentAction()

    /** 做不了（附原因，讓上層誠實回報而不是亂點）。 */
    data class GiveUp(val reason: String) : AgentAction()
}

/**
 * 述詞代理：給「世界」與「目標述詞」，算出**下一個原語**。
 *
 * 它做的決策只有三種：
 *  1. 目標已成立 → [AgentAction.Done]
 *  2. 畫面上有能消掉原子的元素 → 直接操作它（Tap / SetValue）
 *  3. 畫面上沒有 → 用**資料驅動的線索**去別的畫面找（Launch / OpenSection / Scroll / Back）
 *
 * 第 3 種是通用性的關鍵：它不查「技能表」，只查「這個標籤通常在哪個分類底下」這種**弱線索**，
 * 而且線索是資料（`ui_toggle_section_map.tsv`），不是程式碼。
 */
object PredicateAgent {

    /** 一次決策的輸入。 */
    /**
     * 步數／捲動／深度的預算（第 1 步：**只新增、不改任何行為**）。
     *
     * 遷移計畫（每步都跑 `:core:test`）：
     *  1. 加 `Budget` 與唯讀 `budget` 屬性（本步）
     *  2. 內部改用 `budget.x`（欄位仍在）
     *  3. 欄位收成 `budget: Budget`，舊名改成唯讀存取子
     */
    data class Budget(
        val step: Int = 0,
        val maxSteps: Int = 12,
        val scrolls: Int = 0,
        val maxScrolls: Int = 4,
        val depth: Int = 0,
        val maxDepth: Int = 3
    )

    data class Context(
        val world: PredicateWorld,
        /** 目標述詞。 */
        val goal: Phi,
        /** 已經試過幾步（由呼叫端維護，用來收斂）。 */
        val step: Int = 0,
        /** 允許的最大步數。 */
        val maxSteps: Int = 12,
        /** 已經捲過的次數（避免無限捲動）。 */
        val scrolls: Int = 0,
        /** 最大捲動次數。 */
        val maxScrolls: Int = 4,
        /** 目前的深度（TaskLib 展開／畫面鑽取的層數）。 */
        val depth: Int = 0,
        /** 最大深度。 */
        val maxDepth: Int = 3,
        /** 已經鑽過的分類（避免無限迴圈）。 */
        val visitedSections: Set<String> = emptySet(),

        /** 目前所在套件是否已經是「承載這個目標的 App」。 */
        val expectedPkg: String = "",
        /** 語意相似度（決策模型）；預設不啟用。 */
        val similarity: LabelSimilarity = LabelSimilarity { _, _ -> 0f },
        /** 語意相似度的採用門檻（低於此值不動作）。 */
        val similarityFloor: Float = 0.60f,
        /** 已經試過、但畫面沒有進展的元素鍵（避免同一動作重複十次）。 */
        val tried: Set<String> = emptySet(),
        /**
         * 語意下降的門檻：目標不在這一頁時，往「最相關的可點列」鑽進去。
         *
         * 這是**不需要任何人工麵包屑表**的導航方式：
         * 對每一列算「與目標的相關度」，鑽最相關的那一列，錯了下一輪會換。
         */
        val descentFloor: Float = 0.45f,
        /** 是否已經用過 App 內建搜尋（只用一次，避免鬼打牆）。 */
        val searchUsed: Boolean = false,
        /** 現在畫面的指紋（用來判斷「換頁了沒」）。 */
        val frameFp: String = "",
        /** 去過的畫面指紋（含現在這頁）。 */
        val visitedFrames: Set<String> = emptySet(),
        /** 能不能退回上一層（depth > 0 時為 true）。 */
        val canGoBack: Boolean = false
    ) {
        /** 唯讀檢視：把六個預算欄位視為一組（第 1 步不改任何行為）。 */
        val budget: Budget
            get() = Budget(step, maxSteps, scrolls, maxScrolls, depth, maxDepth)
    }

    /**
     * 語意相似度（**決策模型的掛鉤**）。
     *
     * 為什麼需要：字面比對在「純圖示按鈕」「同義詞」「不同 OEM 的翻譯」上必敗。
     * 這裡只留一個介面，實作由 app 注入（小模型或 embedding），核心保持純 Kotlin。
     */
    fun interface LabelSimilarity {
        /** 回傳 0..1 的相似度；不支援時回 0。 */
        fun score(goalLabel: String, candidateLabel: String): Float
    }

    /** 分類線索查詢（由 app 端注入，資料來自 TSV）。 */
    fun interface SectionLookup {
        /** 回傳「這個標籤通常在哪些分類底下」。 */
        fun sectionsFor(label: String): List<Pair<String, String>>
    }

    /**
     * 決定下一個原語。
     *
     * 判定順序即優先序（越前面越便宜、越不容易誤動作）：
     *  1. 已成立 → Done
     *  2. 有恰好能滿足的候選 → 操作它
     *  3. 有「差一個原子」的候選 → 操作它（例如文字對、但 checked 不對 → 點它翻轉）
     *  4. 需要換 App／鑽分類 → Launch / OpenSection
     *  5. 還沒有 → Scroll
     *  6. 都不行 → GiveUp（誠實）
     */
    fun next(ctx: Context, sections: SectionLookup = SectionLookup { emptyList() }): AgentAction {
        // 已經試過而沒有進展的元素，不再送進決策
        // （否則會出現實測那個現象：同一顆 radio_button 連點十次）
        val w: PredicateWorld = if (ctx.tried.isEmpty()) ctx.world else FilteredWorld(ctx.world, ctx.tried)
        val goal = ctx.goal

        if (PredicateEval.eval(goal, w)) return AgentAction.Done
        if (ctx.budget.step >= ctx.budget.maxSteps) return AgentAction.GiveUp("步數上限（${ctx.budget.maxSteps}）")

        // 只對「單一 Exists」做引導式操作；And/Or 先降級成最接近的那一支
        val target = nearestExists(goal, w) ?: return AgentAction.GiveUp("目標不是可操作形狀")
        val atoms = target.atoms

        // 2–3. 找最接近的候選
        val ranked = PredicateEval.rankControls(atoms, w)
        val best = ranked.firstOrNull()
        if (best != null && best.second == 0) {
            return actionFor(atoms, best.first)
        }
        if (best != null && best.second == 1) {
            // 只差一個原子 → 直接操作它（最常見：文字對、狀態不對）
            return actionFor(atoms, best.first)
        }
        // 2.5 語意重排：字面比對失敗（或只差很多）時，用決策模型挑一個
        val labelAtom = atoms.filterIsInstance<Atom.LabelContains>().firstOrNull()?.text
        if (labelAtom != null) {
            val needKind = atoms.filterIsInstance<Atom.KindIs>().firstOrNull()?.kind
            // 決策模型很貴（cross-encoder 一次 ~50 ms）：只問前 12 個候選，
            // 由型別先篩，避免整頁 40+ 個元素都送進去。
            val pool = (if (needKind != null) w.controls.filter { it.kind == needKind } else w.controls).take(12)
            val scored = pool
                .map { c -> c to ctx.similarity.score(labelAtom, c.label) }
                .filter { it.second >= ctx.similarityFloor }
                .maxByOrNull { it.second }
            if (scored != null && (best == null || best.second > 1)) {
                return actionFor(atoms, scored.first)
            }
        }

        if (best != null && best.second == 2 &&
            best.first.kind == ControlKind.ROW &&
            atoms.any { it is Atom.LabelContains }
        ) {
            // 文字對、但型別與狀態都不對的「列」→ 這是分類入口，鑽進去
            return AgentAction.OpenSection(best.first.key, best.first.label)
        }

        // 3.5 **唯一開關備援**：畫面上恰好只有一個 TOGGLE，且狀態與目標相反 → 就是它。
        //     實測：三星 Wi-Fi 頁的總開關 label 是「開」，不是「Wi-Fi」，
        //     純文字比對永遠不成立；但「整頁只有一個開關」是安全的推論。
        val wantChecked = atoms.filterIsInstance<Atom.CheckedIs>().firstOrNull()?.value
        if (wantChecked != null && atoms.any { it is Atom.KindIs && it.kind == ControlKind.TOGGLE }) {
            val toggles = w.controls.filter { it.kind == ControlKind.TOGGLE }
            if (toggles.size == 1 && toggles[0].checked != null && toggles[0].checked != wantChecked) {
                return actionFor(atoms, toggles[0])
            }
        }

        // 4. 換 App / 鑽分類
        val label = atoms.filterIsInstance<Atom.LabelContains>().firstOrNull()?.text
        if (label != null) {
            val hints = sections.sectionsFor(label)
            // 4a. 該 App 還沒開 → 先開（套件由呼叫端依資料決定）
            if (ctx.expectedPkg.isNotEmpty() && w.pkg != ctx.expectedPkg) {
                return AgentAction.Launch(ctx.expectedPkg)
            }
            // 4b. 有分類線索且還沒鑽過 → 鑽進去
            val unvisited = hints.firstOrNull { (key, _) -> key !in ctx.visitedSections }
            if (unvisited != null) {
                val (key, sectionLabel) = unvisited
                // 分類入口若已在畫面上就點它，否則先捲動找
                val entry = w.controls.firstOrNull {
                    it.label.isNotBlank() && TextFold.containsFold(it.label, sectionLabel)
                }
                return if (entry != null) {
                    AgentAction.OpenSection(entry.key, sectionLabel)
                } else {
                    AgentAction.Scroll(if (ctx.budget.scrolls % 2 == 0) 1 else -1)
                }
            }
        }

        // 5. 捲動找
        if (ctx.budget.scrolls < ctx.budget.maxScrolls) {
            return AgentAction.Scroll(if (ctx.budget.scrolls % 2 == 0) 1 else -1)
        }

        // 6. **語意下降**：目標不在這一頁，也沒有分類線索 → 往最相關的可點列鑽
        //    這是取代「人工麵包屑表」的通用做法：不需要知道「WiFi 在連接底下」，
        //    只要知道「連接」與「WiFi」相關，就值得進去看一眼；錯了下一輪會被 tried 排除。
        //    深度用完就不再往裡鑽（改走「退回上一層」）。
        if (label != null && ctx.budget.depth < ctx.budget.maxDepth) {
            val candidates = w.controls.filter {
                it.label.isNotBlank() && it.enabled && it.key !in ctx.tried &&
                    (it.kind == ControlKind.ROW || it.kind == ControlKind.TAB || it.kind == ControlKind.BUTTON)
            }.take(12)
            val best = candidates
                .map { c -> c to ctx.similarity.score(label, c.label) }
                .maxByOrNull { it.second }
            if (best != null && best.second >= ctx.descentFloor) {
                return AgentAction.OpenSection(best.first.key, best.first.label)
            }
        }

        // 7. **叫 App 自己找**：有搜尋框就把目標詞打進去（通用性最高的一招）
        if (label != null && !ctx.searchUsed) {
            val field = w.controls.firstOrNull {
                it.enabled && it.key !in ctx.tried &&
                    (it.kind == ControlKind.FIELD || it.editable) &&
                    (it.label.isBlank() || LanguageWords.searchWords().any { w0 -> TextFold.containsFold(it.label, w0) })
            }
            if (field != null) return AgentAction.TypeSearch(field.key, label)
        }

        // 8. **退回上一層**：這一頁已經沒招了，而且我們是鑽進來的 → 退出去換一條路。
        //    沒有這一步，鑽錯分支就會卡死在裡面（實測：開熱點跑到「儲存空間」）。
        if (ctx.budget.depth > 0 && ctx.canGoBack) {
            return AgentAction.Back("depth=${ctx.budget.depth} 無路可走，退回上一層")
        }

        return AgentAction.GiveUp("畫面上找不到目標，且已無可用線索")
    }


    /** 只看「還沒試過」的元素的視圖（不複製世界，只過濾）。 */
    private class FilteredWorld(
        private val delegate: PredicateWorld,
        private val tried: Set<String>
    ) : PredicateWorld {
        override val pkg: String get() = delegate.pkg
        override val controls: List<PredicateControl> by lazy {
            delegate.controls.filter { it.key !in tried }
        }
    }

    /** 對一個控制項，該做哪個原語。 */
    private fun actionFor(atoms: List<Atom>, c: PredicateControl): AgentAction {
        val wantsValue = atoms.any { it is Atom.ValueIn }
        if (wantsValue || c.kind == ControlKind.SLIDER) {
            val want = atoms.filterIsInstance<Atom.ValueIn>().firstOrNull()
            val target = want?.let { (it.min + it.max) / 2f } ?: 0.5f
            return AgentAction.SetValue(c.key, target, c.label)
        }
        return AgentAction.Tap(c.key, c.label)
    }

    /**
     * 把任意述詞降級成「最接近成立的那一個 Exists」。
     *
     * 為什麼可以降級：`And` 只要先滿足最接近的一支，下一輪會再算一次距離；
     * `Or` 本來就是挑最近的。這讓代理不必一次處理整個布林式。
     */
    private fun nearestExists(phi: Phi, w: PredicateWorld): Phi.Exists? = when (phi) {
        is Phi.Exists -> phi
        is Phi.And -> phi.items.mapNotNull { nearestExists(it, w) }
            .minByOrNull { PredicateEval.distance(it, w) }
        is Phi.Or -> phi.items.mapNotNull { nearestExists(it, w) }
            .minByOrNull { PredicateEval.distance(it, w) }
        is Phi.Not -> null
        else -> null
    }
}
