package com.wae.core.predicate

import com.wae.core.model.ControlKind

/**
 * 述詞的三個基本操作：**判定**、**距離**、**原子展開**。
 *
 * 這三者是整個通用代理的心臟：
 *  - [eval] 同時是「驗證」與「規劃的目標判定」→ 只有一套標準，不會出現兩套打架。
 *  - [distance] 是 A* 的啟發函數，也是「還要幾步」的量化指標。
 *  - [atoms] 讓打分器知道「哪個候選能消掉哪個未成立的原子」。
 */
object PredicateEval {

    /** 目標是否成立。純函式：同一 (Φ, world) 必得同一結果。 */
    fun eval(phi: Phi, world: PredicateWorld): Boolean = when (phi) {
        is Phi.Top -> true
        is Phi.PkgIs -> world.pkg.isNotEmpty() && world.pkg == phi.pkg
        is Phi.PkgIsNot -> world.pkg.isNotEmpty() && world.pkg != phi.pkg
        is Phi.Exists -> {
            // 可觀測原子（畫面外狀態）先檢查；其餘原子再找控制項
            val obs = phi.atoms.filterIsInstance<Atom.Observable>()
            val onScreen = phi.atoms.filter { it !is Atom.Observable }
            // 三態：查得到才驗，查不到（null）代表「不知道」→ 不阻擋
            val obsOk = obs.all { a ->
                val v = world.observable(a.key)
                v == null || v == a.expected
            }
            obsOk && (onScreen.isEmpty() || bestControl(onScreen, world) != null)
        }
        is Phi.And -> phi.items.all { eval(it, world) }
        is Phi.Or -> phi.items.any { eval(it, world) }
        is Phi.Not -> !eval(phi.inner, world)
    }

    /**
     * 還差幾個原子才成立（0 = 成立）。
     *
     * 語意是「最少需要幾個原子被補上」：
     *  - `And` 相加（每個都得成）
     *  - `Or` 取最小（挑最接近的那條）
     *  - `Exists` 取「所有控制項中，失敗原子數最少的那個」
     *
     * 這是一個**單調**估計：任何一個動作最多能消掉有限個原子，
     * 所以拿它當 A* 啟發不會把「遠的」誤判成「近的」。
     */
    fun distance(phi: Phi, world: PredicateWorld): Int = when (phi) {
        is Phi.Top -> 0
        is Phi.PkgIs -> if (world.pkg == phi.pkg) 0 else 1
        is Phi.PkgIsNot -> if (world.pkg.isNotEmpty() && world.pkg != phi.pkg) 0 else 1
        is Phi.Exists -> {
            val obs = phi.atoms.filterIsInstance<Atom.Observable>()
            val onScreen = phi.atoms.filter { it !is Atom.Observable }
            val obsMiss = obs.count { a ->
                val v = world.observable(a.key)
                v != null && v != a.expected
            }
            if (onScreen.isEmpty()) obsMiss
            else {
                val usable = world.controls
                val screenMiss = if (usable.isEmpty()) onScreen.size
                else usable.minOf { c -> onScreen.count { !holds(it, c) } }
                obsMiss + screenMiss
            }
        }
        is Phi.And -> phi.items.sumOf { distance(it, world) }
        is Phi.Or -> phi.items.minOf { distance(it, world) }
        is Phi.Not -> if (eval(phi.inner, world)) 1 else 0
    }

    /** 展開所有原子（順序穩定：深度優先、由左至右）。 */
    fun atoms(phi: Phi): List<Atom> = when (phi) {
        is Phi.Top -> emptyList()
        is Phi.PkgIs, is Phi.PkgIsNot -> emptyList()
        is Phi.Exists -> phi.atoms
        is Phi.And -> phi.items.flatMap { atoms(it) }
        is Phi.Or -> phi.items.flatMap { atoms(it) }
        is Phi.Not -> atoms(phi.inner)
    }

    /** 找出「最能滿足 [atomList]」的控制項（失敗原子最少者；全成即命中）。 */
    fun bestControl(atomList: List<Atom>, world: PredicateWorld): PredicateControl? {
        var best: PredicateControl? = null
        var bestMiss = Int.MAX_VALUE
        for (c in world.controls) {
            var miss = 0
            for (a in atomList) if (!holds(a, c)) miss += 1
            if (miss < bestMiss) {
                bestMiss = miss
                best = c
                if (miss == 0) return c
            }
        }
        return if (bestMiss == 0) best else null
    }

    /** 哪個控制項還差幾個原子（給「引導式動作選擇」用）。 */
    fun rankControls(atomList: List<Atom>, world: PredicateWorld): List<Pair<PredicateControl, Int>> =
        world.controls
            .map { c -> c to atomList.count { !holds(it, c) } }
            .sortedBy { it.second }

    /** 單一原子在單一控制項上是否成立。 */
    fun holds(atom: Atom, c: PredicateControl): Boolean = when (atom) {
        is Atom.LabelContains -> TextFold.containsFold(c.label, atom.text)
        is Atom.KindIs -> c.kind == atom.kind
        is Atom.CheckedIs -> c.checked == atom.value
        is Atom.EditableIs -> c.editable == atom.value
        is Atom.EnabledIs -> c.enabled == atom.value
        is Atom.ValueIn -> {
            val v = c.valueFraction
            v != null && v >= atom.min && v <= atom.max
        }
        is Atom.TextHashIs -> c.textHash == atom.hash
        is Atom.KeyIs -> c.key == atom.key
        is Atom.HasText -> c.textHash.isNotEmpty()
        // 可觀測原子不在畫面上：由 PredicateWorld.observable 回答
        is Atom.Observable -> false
    }

    /** 可觀測原子（畫面外狀態）：eval 需要世界而不是單一控制項。 */
    fun holdsObservable(atom: Atom.Observable, world: PredicateWorld): Boolean =
        world.observable(atom.key) == true

    // ── 靜態檢查（動手前就能判斷成敗）──────────────────────────

    /**
     * 語法／語意自洽檢查（well-formed）。
     *
     * 這裡只做「不需要畫面」就能判定的檢查：矛盾、空集合、佔位符未填。
     * 回傳問題清單；空清單＝合法。
     */
    fun wellFormed(phi: Phi): List<String> {
        val problems = ArrayList<String>()
        walk(phi) { node ->
            if (node is Phi.Exists) {
                val checks = node.atoms.filterIsInstance<Atom.CheckedIs>().map { it.value }.distinct()
                if (checks.size > 1) problems += "同一個 Exists 內同時要求 checked=true 與 false（矛盾）"
                val values = node.atoms.filterIsInstance<Atom.ValueIn>()
                if (values.size > 1) problems += "同一個 Exists 內有多個 ValueIn（請合成單一區間）"
                if (node.atoms.any { it is Atom.KindIs && it.kind == ControlKind.UNKNOWN }) {
                    problems += "Atom.KindIs(UNKNOWN) 無法判定，請移除"
                }
                if (node.atoms.none { it is Atom.LabelContains || it is Atom.TextHashIs || it is Atom.Observable }) {
                    problems += "Exists 缺少文字原子：會匹配到任意控制項（過寬）"
                }
            }
        }
        return problems
    }

    /** 便利版：布林。 */
    fun isWellFormed(phi: Phi): Boolean = wellFormed(phi).isEmpty()

    /**
     * 可達性（reachable）：在「已知的世界集合」裡，有沒有任何一個世界讓 Φ 成立，
     * 或至少讓每個文字原子都出現過。
     *
     * 這是**動手前**的第二次檢查：即使述詞語法正確，若自動機上根本沒有相關畫面，
     * 也不該派工（否則就是「先執行了才知道做不到」）。
     */
    fun reachable(phi: Phi, worlds: List<PredicateWorld>): Boolean {
        if (worlds.isEmpty()) return false
        if (worlds.any { eval(phi, it) }) return true
        // 弱可達：所有文字原子在歷史上有出現過（可能只是還沒到那個畫面）
        val labels = atoms(phi).filterIsInstance<Atom.LabelContains>().map { it.text }
        if (labels.isEmpty()) return true
        return labels.all { t -> worlds.any { w -> w.controls.any { it.label.contains(t, ignoreCase = true) } } }
    }

    // ── 內部 ──────────────────────────────────────────────────

    private fun walk(phi: Phi, visit: (Phi) -> Unit) {
        visit(phi)
        when (phi) {
            is Phi.And -> phi.items.forEach { walk(it, visit) }
            is Phi.Or -> phi.items.forEach { walk(it, visit) }
            is Phi.Not -> walk(phi.inner, visit)
            else -> Unit
        }
    }
}
