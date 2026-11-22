package com.wae.core.model

data class Point(val x: Int, val y: Int)

enum class GestureKind { TAP, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SCROLL }

/**
 * **手段**：達到同一個效果的所有合法方式。
 *
 * 關鍵設計（Gen-5 §4.4）：點螢幕只是最後一個手段。
 * 手段越多，對「任意 App」的依賴越少——這是用「手段多樣性」換廣度。
 */
sealed class Means {

    enum class Kind { SYSTEM_API, INTENT, A11Y, INPUT, GESTURE, TEACH }

    abstract val kind: Kind

    /** 穩定的手段鍵（反例庫用它記「這條手段在這畫面不行」）。 */
    abstract fun describe(): String

    data class SystemApi(val api: String) : Means() {
        override val kind: Kind get() = Kind.SYSTEM_API
        override fun describe(): String = "system_api:$api"
    }

    data class Intent(val action: String, val pkg: String? = null, val data: String? = null) : Means() {
        override val kind: Kind get() = Kind.INTENT
        override fun describe(): String = "intent:$action"
    }

    data class A11y(val key: String, val action: String) : Means() {
        override val kind: Kind get() = Kind.A11Y
        override fun describe(): String = "a11y:$action"
    }

    data class Input(val text: String, val viaIme: Boolean = true) : Means() {
        override val kind: Kind get() = Kind.INPUT
        override fun describe(): String = "input:${if (viaIme) "ime" else "paste"}"
    }

    data class Gesture(val gesture: GestureKind, val at: Point) : Means() {
        override val kind: Kind get() = Kind.GESTURE
        override fun describe(): String = "gesture:${gesture.name}"
    }

    data class Teach(val assetIds: List<String>) : Means() {
        override val kind: Kind get() = Kind.TEACH
        override fun describe(): String = "teach:${assetIds.size}"
    }
}
