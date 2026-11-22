package com.wae.core.learn

import com.wae.core.model.Attribution
import com.wae.core.model.Frame
import com.wae.core.model.Outcome
import com.wae.core.model.Step

/**
 * 失敗歸因（L3）。
 *
 * 目的不是「解釋得漂亮」，而是**把失敗分到能修的那一類**：
 * 感知錯 → 補感知；定位錯 → 換候選；時序錯 → 等畫面；權限錯 → 換手段；
 * 契約錯 → 修型別規則。分錯類，後面的修法一定錯。
 */
class Attributor {

    fun attribute(step: Step, outcome: Outcome, before: Frame, after: Frame): Attribution = when (outcome) {
        // 「送出了但沒效果」= 點到了東西，但它不是我們要的（實測最常發生的就是這個）
        is Outcome.Success -> if (after.controls.isEmpty()) {
            Attribution.PERCEPTION
        } else {
            Attribution.LOCATION
        }
        is Outcome.NoEffect -> {
            val byGesture = step.means.kind == com.wae.core.model.Means.Kind.GESTURE
            when {
                // 前後幀指紋一樣，且這一幀幾乎沒有元素 → 根本沒看到東西
                before.fp == after.fp && after.controls.size < FEW_CONTROLS -> Attribution.PERCEPTION
                // 畫面沒變但元素很多 → 點到了但沒效（多半點錯位置；座標手勢尤其）
                before.fp == after.fp && byGesture -> Attribution.LOCATION
                before.fp == after.fp -> Attribution.LOCATION
                // 畫面有變但後置條件不成立 → 時序（動畫／延遲）
                else -> Attribution.TIMING
            }
        }
        is Outcome.WrongEffect -> Attribution.TIMING
        is Outcome.Blocked -> Attribution.PERMISSION
        is Outcome.Unknown -> Attribution.PERCEPTION
        else -> Attribution.LOCATION
    }

    companion object {
        /** 「幾乎沒有元素」的門檻；低於此值視為感知失敗。 */
        private const val FEW_CONTROLS = 3
    }
}
