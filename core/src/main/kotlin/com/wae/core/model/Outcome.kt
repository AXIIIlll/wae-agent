package com.wae.core.model

/** 失敗歸因，**只能五選一**（Gen-5 §C4）。這是除錯的骨架，不允許「其他」。 */
enum class Attribution {
    /** 看不見／看錯（沒有節點、OCR 漏字、樹是舊的） */
    PERCEPTION,

    /** 看得見但點錯（歧義、座標偏、選錯候選） */
    LOCATION,

    /** 時序（畫面還沒穩、彈窗、動畫中、Toctou） */
    TIMING,

    /** 權限／系統拒絕（WRITE_SETTINGS、藍牙、後台限制） */
    PERMISSION,

    /** 型別判斷錯（把 toggle 當 button、把欄位當列） */
    CONTRACT
}

/** 棄權時給使用者的選項（最多兩個；UI 直接照這個畫）。 */
data class Option(val id: String, val label: String)

/**
 * 結果六態。**成功 = 後置條件成立**，不是「動作已送出」。
 */
sealed class Outcome {

    abstract val isSuccess: Boolean

    /** 效果成立 */
    data object Success : Outcome() {
        override val isSuccess: Boolean get() = true
    }

    /** 什麼都沒變 → 可換手段 */
    data object NoEffect : Outcome() {
        override val isSuccess: Boolean get() = false
    }

    /** 跳到錯的地方 → 必須回退 */
    data object WrongEffect : Outcome() {
        override val isSuccess: Boolean get() = false
    }

    /** 安全閘門拒絕 */
    data object Blocked : Outcome() {
        override val isSuccess: Boolean get() = false
    }

    /** 承認不會（停 → 說清楚 → 給選項） */
    data class Abstain(
        val reason: String,
        val options: List<Option> = emptyList()
    ) : Outcome() {
        override val isSuccess: Boolean get() = false
    }

    /** 感知不足，需要補感知（不是失敗） */
    data class Unknown(val reason: String) : Outcome() {
        override val isSuccess: Boolean get() = false
    }
}
