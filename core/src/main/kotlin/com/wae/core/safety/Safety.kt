package com.wae.core.safety

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.model.Constraints
import com.wae.core.model.Risk
import com.wae.core.model.Step

/** 安全閘門的判定（三態，沒有「大概可以」）。 */
enum class Decision { SAFE, CONFIRM, BLOCK }

/**
 * 安全閘門（L4 橫切）。
 *
 * 規則很簡單，但**順序不能改**：
 * 1. `FORBIDDEN` → BLOCK（永遠擋，不看設定）
 * 2. `HIGH` → CONFIRM（語音不得單獨放行）
 * 3. 反例庫說要確認 → CONFIRM
 * 4. 其餘 SAFE
 */
class Safety(private val config: Config) {

    fun decide(step: Step, constraints: Constraints, warmup: Boolean = false): Decision {
        if (step.risk == Risk.FORBIDDEN) return Decision.BLOCK
        // C12：前 N 次使用（暖機期）連 MEDIUM 都要求確認，避免陌生環境亂動
        if (warmup && step.risk != Risk.LOW) return Decision.CONFIRM
        if (step.risk == Risk.HIGH && confirmHighRisk()) return Decision.CONFIRM
        if (constraints.needsConfirm(step.effect.key(), step.means.describe())) return Decision.CONFIRM
        return Decision.SAFE
    }

    private fun confirmHighRisk(): Boolean =
        config.bool(Key.SAFETY_CONFIRM_HIGH_RISK, Default.CONFIRM_HIGH_RISK)
}
