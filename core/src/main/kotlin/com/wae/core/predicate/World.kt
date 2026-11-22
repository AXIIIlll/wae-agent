package com.wae.core.predicate

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.Frame

/**
 * 述詞求值的「世界」抽象。
 *
 * 為什麼要多一層介面（而不是直接吃 [Frame]）：
 *  1. **規劃時**只有「自動機狀態的特徵快照」，沒有真畫面；
 *  2. **執行時**有真畫面。
 *  兩者都要能用**同一套** `eval`/`distance`，否則驗證與規劃就會變成兩套標準
 *  （歷史上這個專案就踩過「後置說成功、驗證說失敗」的鬼打牆）。
 */
interface PredicateWorld {

    /** 前景套件（空字串＝未知）。 */
    val pkg: String

    /** 這一刻看到的控制項（唯讀）。 */
    val controls: List<PredicateControl>

    /**
     * 畫面外狀態查詢（缺口 2 的接口）。
     * 回傳 null＝這個 key 查不到（等於不成立，但**不是** false）。
     */
    fun observable(key: String): Boolean? = null
}

/** 單一控制項在述詞層看到的樣子（只放原子需要的欄位）。 */
interface PredicateControl {
    /** 穩定鍵（跨幀不變）；內部驗證用。 */
    val key: String
    val label: String
    val kind: ControlKind
    val checked: Boolean?
    val editable: Boolean
    val enabled: Boolean

    /** 數值正規化到 0..1；讀不到數值（非滑桿）時為 null。 */
    val valueFraction: Float?
    val textHash: String
}

// ── Frame 轉接 ──────────────────────────────────────────────

/** 把 [Control] 包成 [PredicateControl]。 */
class FrameControl(private val c: Control) : PredicateControl {
    override val key: String get() = c.key
    override val label: String get() = c.label
    override val kind: ControlKind get() = c.kind
    override val checked: Boolean? get() = c.state.on ?: c.state.checked
    override val editable: Boolean get() = c.kind == ControlKind.FIELD
    override val enabled: Boolean get() = c.enabled
    override val valueFraction: Float? get() = c.valueFraction
    override val textHash: String get() = c.state.text?.let { com.wae.core.util.TextHash.of(it) } ?: c.textHash
}

/** 把整幀 [Frame] 包成 [PredicateWorld]。 */
class FrameWorld(
    private val frame: Frame,
    private val observables: ((String) -> Boolean?)? = null
) : PredicateWorld {
    override val pkg: String get() = frame.pkg
    override fun observable(key: String): Boolean? = observables?.invoke(key)
    override val controls: List<PredicateControl> by lazy { frame.controls.map { FrameControl(it) } }
}

fun Frame.asWorld(observables: ((String) -> Boolean?)? = null): PredicateWorld = FrameWorld(this, observables)

// ── 離線／圖上的特徵快照 ────────────────────────────────────

/** 一個控制項的特徵快照（可序列化；不含座標與原文以外的隱私資訊）。 */
data class FactsControl(
    override val key: String = "",
    override val label: String,
    override val kind: ControlKind = ControlKind.UNKNOWN,
    override val checked: Boolean? = null,
    override val editable: Boolean = false,
    override val enabled: Boolean = true,
    override val valueFraction: Float? = null,
    override val textHash: String = ""
) : PredicateControl

/** 一個畫面的特徵快照（給「沒有真畫面」的規劃與重放用）。 */
data class FactsWorld(
    override val pkg: String = "",
    override val controls: List<PredicateControl> = emptyList(),
    private val observables: Map<String, Boolean> = emptyMap()
) : PredicateWorld {

    override fun observable(key: String): Boolean? = observables[key]

    companion object {
        val EMPTY = FactsWorld()

        fun of(pkg: String, vararg controls: FactsControl): FactsWorld =
            FactsWorld(pkg, controls.toList())

        fun from(frame: Frame): FactsWorld = FactsWorld(
            pkg = frame.pkg,
            controls = frame.controls.map {
                FactsControl(
                    key = it.key,
                    label = it.label,
                    kind = it.kind,
                    checked = it.state.on ?: it.state.checked,
                    editable = it.kind == ControlKind.FIELD,
                    enabled = it.enabled,
                    valueFraction = it.valueFraction,
                    textHash = it.textHash
                )
            }
        )
    }
}
