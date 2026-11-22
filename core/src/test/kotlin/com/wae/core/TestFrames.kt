package com.wae.core

import com.wae.core.model.Channel
import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.ControlState
import com.wae.core.model.Evidence
import com.wae.core.model.Frame
import com.wae.core.model.RectI
import com.wae.core.util.TextHash

/** 測試用的畫面建構器：讓每個測試都讀得懂自己在建什麼。 */
object TestFrames {

    const val PKG = "com.example.demo"
    const val APP_VERSION = 1L

    fun toggleControl(
        key: String = "sw1",
        text: String = "藍牙",
        on: Boolean = true,
        enabled: Boolean = true
    ): Control = Control(
        key = key,
        kind = ControlKind.TOGGLE,
        label = text,
        textHash = TextHash.of(text),
        bounds = RectI(0, 100, 400, 200),
        enabled = enabled,
        state = ControlState(on = on)
    )

    fun fieldControl(key: String = "et1", text: String = "搜尋"): Control = Control(
        key = key,
        kind = ControlKind.FIELD,
        label = text,
        textHash = TextHash.of(text),
        bounds = RectI(0, 200, 400, 300),
        state = ControlState(text = "")
    )

    fun rowControl(key: String = "row1", text: String = "設定"): Control = Control(
        key = key,
        kind = ControlKind.ROW,
        label = text,
        textHash = TextHash.of(text),
        bounds = RectI(0, 300, 400, 400)
    )

    /** 可捲動的列表（找不到目標時，Seeker 要在這種畫面上工作）。 */
    fun scrollableFrame(itemText: String = "項目"): Frame = frame(
        listOf(
            Control(
                key = "list",
                kind = ControlKind.ROW,
                label = itemText,
                textHash = TextHash.of(itemText),
                bounds = RectI(0, 0, 400, 800),
                scrollable = true
            )
        )
    )

    fun frame(controls: List<Control>): Frame = Frame(
        fp = com.wae.core.model.FrameFingerprint.of(PKG, APP_VERSION, controls),
        pkg = PKG,
        appVersion = APP_VERSION,
        controls = controls,
        evidence = Evidence(setOf(Channel.A11Y), costMs = 10L)
    )

    fun toggleFrame(on: Boolean = true): Frame = frame(listOf(toggleControl(on = on)))
}
