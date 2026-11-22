package com.wae.core.worldmodel

/**
 * 一幀畫面的**可比較摘要**（給世界模型用，不是完整 Frame）。
 *
 * 只用結構量：指紋、App、元素數、感知成本。不存文字內容。
 */
data class Observation(
    val fp: String,
    val pkg: String,
    val controlCount: Int,
    /** 只由「元素鍵」算出的結構指紋：內容變了但版面沒變時，它不變。 */
    val structureHash: String = "",
    val scrollableCount: Int = 0
)

/**
 * 動作造成的**差異類別**。
 *
 * 為什麼預測「差異」而不是「下一幀」：
 *  - 差異的類別數很少（5 個），樣本效率高
 *  - 差異才是決策需要的（有沒有生效、有沒有跳頁）
 *  - 下一幀的完整重建對 UI 這種高熵輸入幾乎不可能，也沒有用
 */
enum class DeltaKind {
    /** 什麼都沒變（動作沒生效，或本來就不該變） */
    NO_CHANGE,

    /** 同一個畫面內內容變了（列表更新、文字出現） */
    SAME_SCREEN_CONTENT,

    /** 換頁但同一個 App */
    SCREEN_CHANGED,

    /** 換到別的 App（或跳出去） */
    APP_CHANGED
}

/** 一次「動作 → 結果」的配對；這就是世界模型的最小訓練單位。 */
data class Transition(
    val pkg: String,
    val appVersion: Long,
    val actionKey: String,
    val before: Observation,
    val after: Observation,
    /** 服務內部回報的結果（可能說謊，這正是靜默失敗要抓的） */
    val reportedSuccess: Boolean
) {

    /** 真實發生的差異（由觀測決定，不由回報決定）。 */
    fun actualDelta(): DeltaKind = when {
        before.fp == after.fp -> DeltaKind.NO_CHANGE
        before.pkg != after.pkg -> DeltaKind.APP_CHANGED
        before.structureHash.isNotEmpty() &&
            before.structureHash == after.structureHash -> DeltaKind.SAME_SCREEN_CONTENT
        else -> DeltaKind.SCREEN_CHANGED
    }

    /**
     * 靜默失敗：回報成功，但畫面根本沒變。
     * 這是整個系統最傷信任的一種錯，也是世界模型最值得抓的東西。
     */
    fun isSilentFailure(): Boolean = reportedSuccess && actualDelta() == DeltaKind.NO_CHANGE
}

/** 預測器的介面：任何模型（規則、45M backbone、未來的大模型）都接這個。 */
fun interface DeltaPredictor {
    fun predict(before: Observation, actionKey: String): DeltaKind
}

/**
 * 規則 baseline（**必須先有它**）。
 *
 * 沒有 baseline 的模型分數沒有意義：UI 轉移在已知動作下高度確定，
 * 一個規則猜「有可點元素就要換頁」可能就有 50% 以上。
 * 世界模型要證明的是「**打敗這個**」，不是打敗 60%。
 */
class RuleBaselinePredictor : DeltaPredictor {

    override fun predict(before: Observation, actionKey: String): DeltaKind = when {
        actionKey.startsWith(ACTION_TAP) || actionKey.startsWith(ACTION_CLICK) ->
            if (before.controlCount > 0) DeltaKind.SCREEN_CHANGED else DeltaKind.NO_CHANGE

        actionKey.startsWith(ACTION_INPUT) -> DeltaKind.SAME_SCREEN_CONTENT
        actionKey.startsWith(ACTION_SCROLL) -> DeltaKind.SAME_SCREEN_CONTENT
        actionKey.startsWith(ACTION_BACK) -> DeltaKind.SCREEN_CHANGED
        actionKey.startsWith(ACTION_LAUNCH) -> DeltaKind.APP_CHANGED
        else -> DeltaKind.SAME_SCREEN_CONTENT
    }

    private companion object {
        const val ACTION_TAP = "gesture:TAP"
        const val ACTION_CLICK = "a11y:click"
        const val ACTION_INPUT = "input:"
        const val ACTION_SCROLL = "gesture:SCROLL"
        const val ACTION_BACK = "global_action:BACK"
        const val ACTION_LAUNCH = "intent:"
    }
}

/** 評測結果：這才是「世界模型有沒有用」的判準。 */
data class WorldModelMetrics(
    val samples: Int,
    val deltaAccuracy: Float,
    val silentFailureTotal: Int,
    val silentFailuresCaught: Int
) {
    /** 抓到的靜默失敗比例；分母為 0 時回 0（沒有樣本就沒有結論）。 */
    val silentFailureRecall: Float
        get() = if (silentFailureTotal == 0) 0f else silentFailuresCaught.toFloat() / silentFailureTotal
}

/**
 * 世界模型的評測器（離線、純函式）。
 *
 * 兩個數字：
 *  - `deltaAccuracy`：預測的差異類別對不對
 *  - `silentFailureRecall`：回報成功但實際沒變的情況，抓到多少
 *
 * 第二個比第一個重要：Even 一個不準的模型，只要能標出「這一動跟預期不符」，
 * 就能把靜默失敗擋在回報之前。
 */
class WorldModelEvaluator(private val predictor: DeltaPredictor) {

    fun evaluate(transitions: List<Transition>): WorldModelMetrics {
        if (transitions.isEmpty()) return WorldModelMetrics(0, 0f, 0, 0)

        var correct = 0
        var silentTotal = 0
        var silentCaught = 0

        for (t in transitions) {
            val predicted = predictor.predict(t.before, t.actionKey)
            val actual = t.actualDelta()
            if (predicted == actual) correct++

            if (t.isSilentFailure()) {
                silentTotal++
                // 模型預期「會變」但實際沒變 → 抓到
                if (predicted != DeltaKind.NO_CHANGE) silentCaught++
            }
        }

        return WorldModelMetrics(
            samples = transitions.size,
            deltaAccuracy = correct.toFloat() / transitions.size,
            silentFailureTotal = silentTotal,
            silentFailuresCaught = silentCaught
        )
    }
}
