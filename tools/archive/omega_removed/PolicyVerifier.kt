package com.wae.agent.core.omega

import android.content.Context
import java.security.MessageDigest

/**
 * WAE-Ω 第 4 層：策略自動機 + 有界模型檢查（PolicyVerifier）。
 *
 * 目的：**在執行前就證明「這條計畫不會碰到不該碰的東西」**，而不是執行到一半才攔。
 *
 * 做法：
 *  1. 風險標註來自 App Model（[RiskMark]）或者型別先驗（未知 App 時）
 *  2. 策略規則（`ui_policy_rules.tsv`）定義每種風險：阻擋 / 需確認 / 允許
 *  3. 驗證器對 Task IR 逐步做：
 *     - 資料流完備性（WRITE 用到的變數一定要有來源）
 *     - 型別存在性（目標型別必須在 App Model 裡支援；沒有模型 → 不可判定）
 *     - 風險檢查（COMMIT / 私密讀取 / 破壞性 / 公開發布）
 *     - 計畫長度上限（有界檢查）
 *  4. 產出「證明書」：每一步的檢查結果 + 雜湊（可審計、可重播）
 *
 * 誠實邊界：沒有 App Model 時，型別與風險無法證明 → 回 `UNDECIDABLE`（不是 SAFE）。
 *
 * 純 Kotlin（除了 ensureLoaded），JVM 可測。
 */
object PolicyVerifier {

    private const val DB_PATH = "open_source_ai_databases/ui_policy_rules.tsv"

    /** 單一計畫的硬上限（有界模型檢查） */
    const val MAX_PLAN_STEPS = 40

    enum class Verdict {
        /** 已證明安全 */
        SAFE,

        /** 可以執行，但不可逆/敏感步驟需要使用者確認 */
        NEED_CONFIRM,

        /** 證明不安全（阻擋） */
        UNSAFE,

        /** 無法證明（例如沒有 App Model）；呼叫端應保守處理 */
        UNDECIDABLE
    }

    data class PolicyRule(
        val kind: RiskKind,
        val blocked: Boolean,
        val requiresConfirmation: Boolean
    )

    data class Check(
        val stepIndex: Int,
        val intent: TaskIrStep.Intent,
        val risks: List<RiskKind>,
        val ok: Boolean,
        val reason: String
    )

    data class Verification(
        val verdict: Verdict,
        val checks: List<Check>,
        val certificate: List<String>,
        val certificateHash: String
    ) {
        fun safeToExecuteWithoutPrompt(): Boolean = verdict == Verdict.SAFE
    }

    private val rules = HashMap<RiskKind, PolicyRule>()

    @Volatile
    private var loadAttempted = false

    private val DEFAULT_RULES = listOf(
        RiskKind.PAYMENT to PolicyRule(RiskKind.PAYMENT, blocked = false, requiresConfirmation = true),
        RiskKind.DESTRUCTIVE to PolicyRule(RiskKind.DESTRUCTIVE, blocked = false, requiresConfirmation = true),
        RiskKind.PUBLIC_WRITE to PolicyRule(RiskKind.PUBLIC_WRITE, blocked = false, requiresConfirmation = true),
        RiskKind.PERMISSION to PolicyRule(RiskKind.PERMISSION, blocked = true, requiresConfirmation = false),
        RiskKind.PRIVATE_READ to PolicyRule(RiskKind.PRIVATE_READ, blocked = false, requiresConfirmation = true)
    )

    fun ensureLoaded(context: Context?) {
        if (loadAttempted) return
        loadAttempted = true
        try {
            val lines = context?.assets?.open(DB_PATH)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            loadFromLines(lines ?: emptyList())
        } catch (_: Throwable) {
            resetToDefaults()
        }
    }

    /** 格式：`policy<TAB>RiskKind<TAB>blocked(0/1)<TAB>confirm(0/1)`；空檔＝用預設 */
    fun loadFromLines(lines: List<String>) {
        val parsed = HashMap<RiskKind, PolicyRule>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 4 || c[0].trim() != "policy") continue
            val kind = runCatching { RiskKind.valueOf(c[1].trim().uppercase()) }.getOrNull()
                ?: continue
            parsed[kind] = PolicyRule(
                kind = kind,
                blocked = c[2].trim() == "1",
                requiresConfirmation = c[3].trim() == "1"
            )
        }
        synchronized(rules) {
            rules.clear()
            if (parsed.isEmpty()) rules.putAll(DEFAULT_RULES)
            else rules.putAll(parsed)
        }
    }

    fun resetForTest() {
        synchronized(rules) { rules.clear() }
        loadAttempted = false
    }

    private fun resetToDefaults() {
        synchronized(rules) {
            rules.clear()
            rules.putAll(DEFAULT_RULES)
        }
    }

    fun ruleCount(): Int = synchronized(rules) { rules.size }

    fun ruleOf(kind: RiskKind): PolicyRule = synchronized(rules) {
        rules[kind] ?: DEFAULT_RULES.firstOrNull { it.first == kind }?.second
            ?: PolicyRule(kind, blocked = false, requiresConfirmation = true)
    }

    /**
     * 驗證一條 Task IR 計畫。
     *
     * @param model App Model（null＝沒有模型 → 型別/風險不可證明）
     */
    fun verify(plan: TaskIrPlan, model: AppModel?): Verification {
        val checks = ArrayList<Check>()
        val cert = ArrayList<String>()
        var verdict = Verdict.SAFE

        if (plan.isEmpty()) {
            return finish(Verdict.UNSAFE, listOf(Check(0, TaskIrStep.Intent.NAVIGATE, emptyList(), false, "empty_plan")), cert)
        }
        if (plan.steps.size > MAX_PLAN_STEPS) {
            return finish(
                Verdict.UNSAFE,
                listOf(Check(0, TaskIrStep.Intent.NAVIGATE, emptyList(), false, "plan_too_long:${plan.steps.size}")),
                cert
            )
        }

        // 資料流：可用變數集合（read/ compute 產生；write/commit 消耗）
        val available = HashSet<String>()
        plan.reads.forEach { available.add(it) }

        for ((idx, step) in plan.steps.withIndex()) {
            val risks = ArrayList<RiskKind>()
            var checkOk = true
            var reason = "ok"

            // 1) 型別存在性（有模型才檢查）
            if (model != null) {
                val target = step.target
                if (target != null && step.intent != TaskIrStep.Intent.COMPUTE &&
                    !model.supports(target.type)
                ) {
                    checkOk = false
                    reason = "type_not_in_model:${target.type.name}"
                }
            } else if (step.target != null) {
                // 沒有模型 → 型別不可證明（保守）
                if (verdict == Verdict.SAFE) verdict = Verdict.UNDECIDABLE
                reason = "no_model"
            }

            // 2) 資料流
            if (step.intent == TaskIrStep.Intent.COMPUTE) {
                val v = step.value
                if (v != null && v.kind == ValueRef.Kind.COMPUTE) {
                    if (v.name.isNotBlank()) available.add(v.name)
                } else if (v != null && v.kind == ValueRef.Kind.VAR) {
                    if (v.name.isNotBlank() && v.name !in available) {
                        checkOk = false
                        reason = "missing_var:${v.name}"
                    }
                }
            }
            if (step.intent == TaskIrStep.Intent.READ) {
                val v = step.value
                if (v != null && v.kind == ValueRef.Kind.VAR && v.name.isNotBlank()) {
                    available.add(v.name)
                } else if (v != null && v.kind == ValueRef.Kind.LITERAL) {
                    // READ 的結果一定寫進變數；literal 表示呼叫端寫錯
                    checkOk = false
                    reason = "read_without_var"
                }
            }
            if (step.intent == TaskIrStep.Intent.WRITE) {
                val v = step.value
                if (v == null) {
                    checkOk = false
                    reason = "write_without_value"
                } else if (v.kind == ValueRef.Kind.VAR && v.name !in available) {
                    checkOk = false
                    reason = "missing_var:${v.name}"
                }
            }

            // 3) 風險（有模型 → 用 selector/hint 對風險標註；沒有 → 只看 COMMIT）
            if (model != null && step.target != null) {
                val hint = step.target.textHint.ifBlank { step.target.fieldName }
                risks.addAll(model.risksOf(hint).map { it.kind }.distinct())
            }
            if (step.intent == TaskIrStep.Intent.COMMIT && risks.isEmpty()) {
                // COMMIT 一律保守：即使沒有標註，也要確認（除非模型明確說沒風險→仍 NEED_CONFIRM）
                if (verdict != Verdict.UNSAFE) verdict = Verdict.NEED_CONFIRM
                reason = if (reason == "ok") "commit_requires_confirmation" else reason
            }
            for (k in risks) {
                val rule = ruleOf(k)
                if (rule.blocked) {
                    verdict = Verdict.UNSAFE
                    checkOk = false
                    reason = "blocked:${k.name}"
                } else if (rule.requiresConfirmation && verdict == Verdict.SAFE) {
                    verdict = Verdict.NEED_CONFIRM
                    if (reason == "ok") reason = "confirm:${k.name}"
                }
            }

            checks.add(Check(idx, step.intent, risks, checkOk, reason))
            cert.add(
                "step=$idx intent=${step.intent.name} target=${step.target ?: "-"} " +
                    "risks=${risks.joinToString(",") { it.name }} ok=$checkOk reason=$reason"
            )
            // 靜態錯誤（型別不存在/資料流缺變數/寫入無值）是硬錯誤 → 直接判定不安全
            if (!checkOk && verdict != Verdict.UNSAFE) verdict = Verdict.UNSAFE
        }

        return finish(verdict, checks, cert)
    }

    private fun finish(verdict: Verdict, checks: List<Check>, cert: List<String>): Verification {
        val lines = ArrayList<String>()
        lines.add("# WAE_POLICY_CERT v1")
        lines.add("verdict=${verdict.name}")
        lines.addAll(cert)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Verification(verdict, checks, lines, hash)
    }

    /** 風險種類 → 中文（UI/證明書顯示用） */
    fun labelOf(kind: RiskKind): String = when (kind) {
        RiskKind.PAYMENT -> "金流"
        RiskKind.DESTRUCTIVE -> "刪除/破壞"
        RiskKind.PUBLIC_WRITE -> "公開發布"
        RiskKind.PERMISSION -> "權限/風控"
        RiskKind.PRIVATE_READ -> "私密資料"
    }
}
