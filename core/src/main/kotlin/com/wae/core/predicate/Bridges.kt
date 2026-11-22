package com.wae.core.predicate

import com.wae.core.model.Effect
import com.wae.core.model.Op
import com.wae.core.model.PostCondition
import com.wae.core.util.TextHash

/**
 * 述詞 ↔ 後置條件 ↔ 效果 的**唯一轉接層**。
 *
 * 為什麼要集中在一支檔案：這個專案踩過最痛的一次 bug 就是
 * 「後置條件用家族比對、效果驗證用精確比對」，兩個標準打架
 * （後置說成功、驗證說失敗 → 重試三次 → 棄權）。
 *
 * 現在只有一條路：**述詞**。
 *  - 目標是述詞（[Phi]）
 *  - 驗證是 `eval(述詞, world)`（[PredicateEval.eval]）
 *  - `PostCondition` 只是述詞的舊介面（給既有 `Step` 用）
 */

/** 把述詞包成既有的 [PostCondition]（讓 `Step` 不必改簽名）。 */
fun Phi.asPostCondition(): PostCondition = PostCondition { frame ->
    PredicateEval.eval(this, frame.asWorld())
}

/** 述詞的距離版後置條件工廠（供除錯／規劃對照用）。 */
fun Phi.distanceIn(frame: com.wae.core.model.Frame): Int =
    PredicateEval.distance(this, frame.asWorld())

// ── 既有 PostConditions 的述詞版本 ─────────────────────────

object PhiConditions {

    /** 永遠成立。 */
    fun always(): Phi = Phi.Top

    /** 前景是這個套件。 */
    fun pkgIs(pkg: String): Phi = Phi.PkgIs(pkg)

    /** 前景不是這個套件（且讀得到前景）。 */
    fun pkgIsNot(pkg: String): Phi = Phi.PkgIsNot(pkg)

    /** 前景是目標，或已離開原本的套件（開 App 的實務驗證）。 */
    fun pkgIsOrChanged(expectedPkg: String, beforePkg: String): Phi =
        Phi.Or(listOf(Phi.PkgIs(expectedPkg), Phi.PkgIsNot(beforePkg)))

    /** 某個（以穩定鍵綁定的）開關被切成 [on]。 */
    fun toggleIs(controlKey: String, on: Boolean): Phi =
        Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.CheckedIs(on)))

    /** 某個開關被翻轉（`before == null` 時只要求「現在讀得到狀態」）。 */
    fun toggleFlipped(controlKey: String, before: Boolean?): Phi = when (before) {
        null -> Phi.Or(
            listOf(
                Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.CheckedIs(true))),
                Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.CheckedIs(false)))
            )
        )
        else -> Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.CheckedIs(!before)))
    }

    /** 畫面上出現這段文字（不綁元素；DOM 換掉也成立）。 */
    fun textAppeared(expectedText: String): Phi =
        Phi.Exists(listOf(Atom.TextHashIs(TextHash.of(expectedText))))

    /** 欄位裡就是這段文字（以穩定鍵綁定欄位）。 */
    fun textEntered(controlKey: String, expectedText: String): Phi =
        Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.TextHashIs(TextHash.of(expectedText))))

    /** 欄位有內容（弱驗證）。 */
    fun textPresent(controlKey: String): Phi =
        Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.HasText))

    /** 元素還在且可用。 */
    fun controlPresent(controlKey: String): Phi =
        Phi.Exists(listOf(Atom.KeyIs(controlKey), Atom.EnabledIs(true)))
}

// ── 效果 → 述詞 ────────────────────────────────────────────

/**
 * [Effect] 是「使用者要什麼」的舊表示（`TOGGLE:bluetooth=on`）。
 * 它**允許攜帶述詞**：`Effect(..., goal = Phi)`；若沒有，才用下面這套保守推導。
 *
 * 保守的意思：**不確定就回 null**，不要猜。
 * （以前為了「讓流程繼續」而猜，結果是把「開 WiFi」猜成「開一個叫 WiFi 的 App」。）
 */
object EffectPhi {

    private val PKG_LIKE = Regex("""^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$""")

    fun of(effect: Effect): Phi? {
        effect.goal?.let { return it }
        return when (effect.op) {
            Op.NAVIGATE ->
                if (PKG_LIKE.matches(effect.target)) Phi.PkgIs(effect.target) else null

            Op.TOGGLE -> {
                val on = parseOnOff(effect.value) ?: return null
                Phi.Exists(
                    listOf(
                        Atom.LabelContains(effect.target),
                        Atom.KindIs(com.wae.core.model.ControlKind.TOGGLE),
                        Atom.CheckedIs(on)
                    )
                )
            }

            Op.INPUT, Op.SUBMIT ->
                Phi.Exists(listOf(Atom.LabelContains(effect.target), Atom.EditableIs(true)))

            Op.SELECT ->
                Phi.Exists(listOf(Atom.LabelContains(effect.target), Atom.EnabledIs(true)))

            Op.SET -> {
                val f = effect.value.toFloatOrNull() ?: return null
                val v = if (f > 1f) f / 100f else f
                Phi.Exists(
                    listOf(
                        Atom.LabelContains(effect.target),
                        Atom.KindIs(com.wae.core.model.ControlKind.SLIDER),
                        Atom.ValueIn(v, v)
                    )
                )
            }
        }
    }

    /** `on/off/true/false/開/關` → 布林；其他回 null。 */
    fun parseOnOff(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "on", "true", "1", "開", "开启", "開啟", "打开", "打開" -> true
        "off", "false", "0", "關", "关", "关闭", "關閉", "关闭" -> false
        else -> null
    }
}
