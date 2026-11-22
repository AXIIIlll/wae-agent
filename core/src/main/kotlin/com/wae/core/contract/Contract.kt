package com.wae.core.contract

import com.wae.core.model.Control
import com.wae.core.model.ControlKind
import com.wae.core.model.Frame
import com.wae.core.model.Op
import com.wae.core.model.Risk

/** 證據規則名稱（後置條件要用哪一種判定）。 */
object EvidenceRule {
    const val STATE_FLIP = "STATE_FLIP"
    const val FRAME_CHANGED = "FRAME_CHANGED"
    const val TEXT_ENTERED = "TEXT_ENTERED"
    const val VALUE_CHANGED = "VALUE_CHANGED"
}

/**
 * 一個控制項的**效果契約**（Gen-5 §4.2）。
 *
 * 它不是模板、不是 App 專屬資料：`kind` 由特徵推導，`effectOp`/`risk` 由規則表決定。
 */
data class Contract(
    val id: String,
    val controlKey: String,
    val kind: ControlKind,
    val effectOp: Op,
    val risk: Risk,
    val reversible: Boolean,
    val evidenceRule: String,
    val confidence: Float
) {

    init {
        require(id.isNotEmpty()) { "Contract.id 不可為空" }
        require(controlKey.isNotEmpty()) { "Contract.controlKey 不可為空" }
    }
}

/** 規則表的一列（`kind<TAB>op<TAB>risk<TAB>reversible<TAB>evidence`）。 */
data class ContractRule(
    val op: Op,
    val risk: Risk,
    val reversible: Boolean,
    val evidenceRule: String,
    val confidence: Float = 1f
)

/**
 * 契約推導器：`Frame → List<Contract>`（純函式）。
 *
 * 規則放 `contract_rules.tsv`；讀不到時用 `DEFAULT_RULES`（僅作最後防線）。
 * 不可得（`UNKNOWN`）的控制項**不產生契約**——它會被視為「不知道這是什麼」，
 * 由 `ABSTAIN` 或手勢手段處理，而不是硬猜。
 */
class ContractDeriver(
    private val rules: Map<ControlKind, ContractRule> = DEFAULT_RULES
) {

    fun derive(frame: Frame): List<Contract> = frame.controls.mapNotNull { deriveOne(it) }

    fun deriveOne(control: Control): Contract? {
        val rule = rules[control.kind] ?: return null
        return Contract(
            id = ID_PREFIX + control.key,
            controlKey = control.key,
            kind = control.kind,
            effectOp = rule.op,
            risk = rule.risk,
            reversible = rule.reversible,
            evidenceRule = rule.evidenceRule,
            confidence = rule.confidence * if (control.enabled) 1f else DISABLED_FACTOR
        )
    }

    companion object {
        const val ID_PREFIX = "ctl:"
        private const val DISABLED_FACTOR = 0.5f

        /** 最後防線：規則表讀不到時用（正式資料在 assets/ui/contract_rules.tsv）。 */
        val DEFAULT_RULES: Map<ControlKind, ContractRule> = mapOf(
            ControlKind.TOGGLE to ContractRule(Op.TOGGLE, Risk.MEDIUM, true, EvidenceRule.STATE_FLIP),
            ControlKind.ROW to ContractRule(Op.NAVIGATE, Risk.LOW, true, EvidenceRule.FRAME_CHANGED),
            ControlKind.FIELD to ContractRule(Op.INPUT, Risk.LOW, true, EvidenceRule.TEXT_ENTERED),
            ControlKind.SLIDER to ContractRule(Op.SET, Risk.MEDIUM, true, EvidenceRule.VALUE_CHANGED),
            ControlKind.TAB to ContractRule(Op.SELECT, Risk.LOW, true, EvidenceRule.FRAME_CHANGED),
            ControlKind.BUTTON to ContractRule(Op.SUBMIT, Risk.MEDIUM, true, EvidenceRule.FRAME_CHANGED)
        )

        /**
         * 解析規則表。格式：
         * ```
         * # kind<TAB>op<TAB>risk<TAB>reversible<TAB>evidence
         * TOGGLE	TOGGLE	MEDIUM	true	STATE_FLIP
         * ```
         */
        fun parseRules(lines: Sequence<String>): Map<ControlKind, ContractRule> {
            val out = LinkedHashMap<ControlKind, ContractRule>()
            for (raw in lines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < COLUMNS) continue
                val kind = runCatching { ControlKind.valueOf(c[0].trim()) }.getOrNull() ?: continue
                val op = runCatching { Op.valueOf(c[1].trim()) }.getOrNull() ?: continue
                val risk = runCatching { Risk.valueOf(c[2].trim()) }.getOrNull() ?: continue
                val reversible = c[3].trim().equals("true", ignoreCase = true)
                val evidence = c[4].trim().ifEmpty { EvidenceRule.FRAME_CHANGED }
                out[kind] = ContractRule(op, risk, reversible, evidence)
            }
            return out
        }

        private const val COLUMNS = 5
    }
}
