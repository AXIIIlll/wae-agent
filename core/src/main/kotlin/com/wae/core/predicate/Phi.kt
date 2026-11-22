package com.wae.core.predicate

/**
 * **目標述詞**（Goal = predicate over the world）。
 *
 * ```
 * Φ ::= ⊤ | Atom | Φ ∧ Φ | Φ ∨ Φ | ¬Φ | ∃c ∈ Frame: Φ(c)
 * ```
 *
 * 這是「通用性」的單位：技能是「指令 → 技能 id → 寫死的步驟」，
 * 述詞是「指令 → 目標狀態」。目標可以組合，路徑由規劃器算。
 *
 * 三個關鍵性質：
 *  - `eval(Φ, world)`：目標成不成立（驗證與規劃共用同一個判定，不另寫一套）
 *  - `distance(Φ, world)`：還差幾個原子（規劃的啟發函數，也是「逼近」的依據）
 *  - `atoms(Φ)`：所有原子（打分器用它判斷「哪個候選能消掉哪個原子」）
 */
sealed class Phi {

    /** 恆真（用在「本來就不會有畫面變化」的動作；不能用來當萬用後置條件）。 */
    data object Top : Phi()

    /**
     * 「存在一個控制項，同時滿足 [atoms] 全部」。
     *
     * 為什麼是「同一個控制項」而不是「分別存在」：
     *   `LabelContains("藍牙") ∧ CheckedIs(true)` 若允許分屬兩個元素，
     *   會在「某列寫著藍牙」+「另一個開關是開的」時誤判成功。
     */
    data class Exists(val atoms: List<Atom>) : Phi() {
        init {
            require(atoms.isNotEmpty()) { "Phi.Exists.atoms 不可為空" }
        }
    }

    /** 合取：全部成立。 */
    data class And(val items: List<Phi>) : Phi() {
        init {
            require(items.size >= 2) { "Phi.And 至少兩項（單項請直接用子述詞）" }
        }
    }

    /** 析取：任一成立。 */
    data class Or(val items: List<Phi>) : Phi() {
        init {
            require(items.size >= 2) { "Phi.Or 至少兩項" }
        }
    }

    /** 否定。 */
    data class Not(val inner: Phi) : Phi()

    /** 前景套件是 [pkg]（這是唯一允許提到套件名的地方）。 */
    data class PkgIs(val pkg: String) : Phi() {
        init {
            require(pkg.isNotBlank()) { "Phi.PkgIs.pkg 不可為空" }
        }
    }

    /**
     * 前景套件**不是** [pkg]（用於「離開某個畫面」這種目標）。
     * 與 `Not(PkgIs(pkg))` 等價，但保留語意讓錯誤訊息更好讀。
     */
    data class PkgIsNot(val pkg: String) : Phi() {
        init {
            require(pkg.isNotBlank()) { "Phi.PkgIsNot.pkg 不可為空" }
        }
    }
}

/** 述詞建構子（讓呼叫端讀起來像在寫規格，而不是在建物件）。 */
object P {

    fun label(text: String): Phi = Phi.Exists(listOf(Atom.LabelContains(text)))

    fun toggle(label: String, on: Boolean): Phi =
        Phi.Exists(listOf(Atom.LabelContains(label), Atom.KindIs(com.wae.core.model.ControlKind.TOGGLE), Atom.CheckedIs(on)))

    fun slider(label: String, min: Float, max: Float): Phi =
        Phi.Exists(listOf(Atom.LabelContains(label), Atom.KindIs(com.wae.core.model.ControlKind.SLIDER), Atom.ValueIn(min, max)))

    fun field(label: String, text: String): Phi =
        Phi.Exists(listOf(Atom.LabelContains(label), Atom.EditableIs(true), Atom.LabelContains(text)))

    fun pkg(pkg: String): Phi = Phi.PkgIs(pkg)

    fun and(vararg items: Phi): Phi = when (items.size) {
        0 -> Phi.Top
        1 -> items[0]
        else -> Phi.And(items.toList())
    }

    fun or(vararg items: Phi): Phi = when (items.size) {
        0 -> Phi.Top
        1 -> items[0]
        else -> Phi.Or(items.toList())
    }

    fun not(inner: Phi): Phi = Phi.Not(inner)
}
