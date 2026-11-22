package com.wae.core.predicate

import com.wae.core.model.ControlKind

/**
 * 述詞的**原子**：對「單一控制項」的判斷。
 *
 * 設計原則（這是整個通用化的地基）：
 *  1. 原子只能用**與 App 無關**的感官屬性（文字、型別、狀態、數值、可見性）。
 *     → 所以同一個述詞形狀可以套在任何 App 上，不需要為每個 App 寫技能。
 *  2. 原子是**純函式**：同一個 (atom, world) 必得同一個布林值。
 *     → 所以可以用錄下來的畫面離線重放（I3）。
 *  3. 原子**不得**引用具體 App 名稱、view id、座標。
 *     → App 名稱只出現在 [Phi.PkgIs] 這種「前景套件」層級，不在原子裡。
 */
sealed class Atom {

    /** 這個控制項的可見文字包含 [text]（大小寫不敏感；繁簡由上游正規化）。 */
    data class LabelContains(val text: String) : Atom() {
        init {
            require(text.isNotBlank()) { "Atom.LabelContains.text 不可為空" }
        }
    }

    /** 這個控制項的語意型別是 [kind]（TOGGLE／SLIDER／FIELD／ROW／BUTTON／TAB）。 */
    data class KindIs(val kind: ControlKind) : Atom()

    /** 開關狀態等於 [value]（`null` 狀態視為不成立）。 */
    data class CheckedIs(val value: Boolean) : Atom()

    /** 可編輯性等於 [value]。 */
    data class EditableIs(val value: Boolean) : Atom()

    /** 可用性等於 [value]。 */
    data class EnabledIs(val value: Boolean) : Atom()

    /**
     * 數值落在「正規化區間」[$min, $max]（0..1）。
     *
     * 為什麼用正規化而非原始值：滑桿的 min/max 每個 App 都不同（0..255、0..100、1..10），
     * 用比例才能跨 App 共用述詞。原始值換算由 [com.wae.core.predicate.WorldControl.valueFraction] 負責。
     */
    data class ValueIn(val min: Float, val max: Float) : Atom() {
        init {
            require(min <= max) { "Atom.ValueIn: min($min) > max($max)" }
            require(min >= 0f && max <= 1f) { "Atom.ValueIn 必須在 0..1 之間" }
        }
    }

    /** 這個控制項的文字雜湊等於 [hash]（隱私版比對：不存原文）。 */
    data class TextHashIs(val hash: String) : Atom()

    /**
     * 這個控制項的穩定鍵是 [key]（Control.key，跨幀不變）。
     *
     * **只給內部驗證用**：後置條件原本就用 key 綁定元素，改用述詞表示時需要它。
     * 自然語言編譯器不會產生這個原子（否則等於把座標／id 寫死）。
     */
    data class KeyIs(val key: String) : Atom() {
        init {
            require(key.isNotEmpty()) { "Atom.KeyIs.key 不可為空" }
        }
    }

    /** 這個控制項有文字（label 或 textHash 非空）。 */
    data object HasText : Atom()

    /**
     * **可觀測狀態**：不在畫面上、但系統查得到的狀態（缺口 2）。
     *
     * 例：`Observable("wifi")` = WiFi 現在是開的嗎。
     *
     * 為什麼需要：`eval(Φ, frame)` 只看畫面。開完 WiFi 回首頁之後，
     * 畫面裡沒有那個開關，就無法確認「它真的開著」。
     * 有了這個原子，驗證可以離開畫面。
     *
     * 值由呼叫端注入（`PredicateWorld.observable`），核心不碰 Android。
     */
    data class Observable(val key: String, val expected: Boolean = true) : Atom() {
        init {
            require(key.isNotBlank()) { "Atom.Observable.key 不可為空" }
        }
    }
}
