package com.wae.agent.core.goal

import com.wae.agent.core.ActionKind
import com.wae.agent.core.LocalGoal
import com.wae.agent.core.nlu.ToggleIntent

/**
 * 目標結果判定（從無障礙服務抽出的第五塊）。
 *
 * 為什麼要抽：服務裡的 `verifyGoalOutcome` 其實是**純決策**——
 * 它只吃「畫面上觀察到的事實」與「目標」，然後回答「這個目標算不算達成」。
 * 觀察由服務負責，判定放這裡，兩邊就能各自替換、各自理解。
 *
 * 邊界：
 *  - **不可驗證就不判失敗**（`verifiable=false` 一律回過關）——寧可不知道，不要冤枉。
 *  - 這裡不碰任何 Android API，只吃字串與數值。
 */
object GoalOutcome {

    /** 一次判定的輸入：目標 ＋ 觀察到的事實。 */
    data class Inputs(
        val goal: LocalGoal,
        val dispatchVerified: Boolean,
        val pkgAfter: String,
        val pkgBefore: String,
        val spineChanged: Boolean,
        val texts: List<String>,
        val selectors: Set<String>,
        val values: Map<String, Float>,
        val valuesBefore: Map<String, Float>,
        val toggles: List<ToggleFact>
    )

    data class Verdict(
        val ok: Boolean,
        val verifiable: Boolean,
        val description: String,
        val goalText: String,
        val actionKind: ActionKind
    )

    fun decide(inputs: Inputs): Verdict {
        val goal = inputs.goal

        // 開關目標要先知道「它想要的是開還是關」（S27）
        val toggleIntent = ToggleIntent.parseForToggle(goal.goalText, goal.actionKind)
            ?: ToggleIntent.parseForToggle(goal.inputPayload, goal.actionKind)

        val condition = GoalCompiler.compile(
            actionKind = goal.actionKind,
            payload = goal.inputPayload.ifBlank { toggleIntent?.target.orEmpty() },
            packageHint = goal.targetPackageHint,
            desiredToggle = toggleIntent?.desired
        )

        val facts = PageFacts(
            pkg = inputs.pkgAfter,
            pkgBefore = inputs.pkgBefore,
            spineChanged = inputs.spineChanged,
            texts = inputs.texts,
            selectors = inputs.selectors,
            values = inputs.values,
            valuesBefore = inputs.valuesBefore,
            dispatchVerified = inputs.dispatchVerified,
            toggles = inputs.toggles
        )

        return Verdict(
            ok = condition.evaluate(facts),
            verifiable = condition.verifiable,
            description = condition.describe(),
            goalText = goal.goalText,
            actionKind = goal.actionKind
        )
    }
}
