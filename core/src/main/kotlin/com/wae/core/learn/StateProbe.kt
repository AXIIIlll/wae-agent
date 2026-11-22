package com.wae.core.learn

import com.wae.core.model.ControlKind
import com.wae.core.model.Frame

/**
 * 隱狀態的**被動**推斷（C9）。
 *
 * 只讀畫面上已經有的東西：開關數量、欄位有沒有內容、列表有幾列。
 * 不做主動探測（不替你開購物車、不查餘額）——那是消費者的真實帳號，不能拿來試。
 *
 * 用途：① 收據裡多一行「當時畫面狀態」；② 判斷「這一步真的改變了隱狀態嗎」。
 */
object StateProbe {

    data class Snapshot(
        val togglesOn: Int,
        val togglesOff: Int,
        val fields: Int,
        val fieldsWithText: Int,
        val rows: Int
    ) {
        fun describe(): String =
            "toggles=${togglesOn}on/${togglesOff}off fields=$fieldsWithText/$fields rows=$rows"
    }

    fun snapshot(frame: Frame): Snapshot {
        val toggles = frame.controls.filter { it.kind == ControlKind.TOGGLE }
        val fields = frame.controls.filter { it.kind == ControlKind.FIELD }
        return Snapshot(
            togglesOn = toggles.count { it.state.on == true || it.state.checked == true },
            togglesOff = toggles.count { it.state.on == false || it.state.checked == false },
            fields = fields.size,
            fieldsWithText = fields.count { !it.state.text.isNullOrEmpty() },
            rows = frame.controls.count { it.kind == ControlKind.ROW }
        )
    }

    /** 兩個快照之間有沒有變化（給驗證通道用）。 */
    fun changed(before: Snapshot, after: Snapshot): Boolean = before != after
}
