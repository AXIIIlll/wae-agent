package com.wae.agent.core.omega

import android.content.Context

/**
 * WAE-Ω 門面（OmegaEngine）：把八層串成一個可用的流程。
 *
 * 標準流程（先證明再執行）：
 * ```
 * 指令 → IntentCompiler（型別化 Task IR + 靜態檢查）
 *      → PolicyVerifier（策略自動機 + 有界模型檢查 → 證明書）
 *      → SimSandbox（在 App Model 裡先跑一遍 → 預期成功率/不確定處）
 *      → VerifiedExecutor（契約＋證據；失敗留反例）
 *      → AppModelCompiler（把結果併回模型）＋ OperatorSynthesizer（成功軌跡 → 運算子）
 * ```
 *
 * 旗標：`ExtremeRuntimeHooks.WAE_OMEGA_ENABLED`（預設 false）。
 * v1 在服務端採 **shadow 模式**：只做編譯/驗證/模擬並寫 log，不改變實際執行路徑；
 * 等真機驗證過再開執行。
 */
object OmegaEngine {

    const val VERSION = "wae-omega-v1"

    data class PlanReport(
        val command: String,
        val pkg: String,
        val modelFound: Boolean,
        val compile: IntentCompiler.Result,
        val verification: PolicyVerifier.Verification?,
        val simulation: SimSandbox.SimResult?,
        /** 是否可以直接執行（編譯成功 + 驗證非 UNSAFE/UNDECIDABLE + 模擬可行） */
        val readyToExecute: Boolean,
        val message: String
    ) {
        fun summary(): String =
            "omega($VERSION) pkg=$pkg model=$modelFound " +
                "ready=$readyToExecute msg=$message"
    }

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context?) {
        if (loaded) return
        loaded = true
        UiOntology.ensureLoaded(context)
        PolicyVerifier.ensureLoaded(context)
        IntentCompiler.ensureLoaded(context)
        ModelStore.ensureLoaded(context)
        OperatorRegistry.ensureLoaded(context)
    }

    fun resetForTest() {
        loaded = false
        UiOntology.resetForTest()
        PolicyVerifier.resetForTest()
        IntentCompiler.resetForTest()
        ModelStore.resetForTest()
        OperatorRegistry.resetForTest()
    }

    fun enabled(): Boolean =
        com.wae.agent.core.runtime.ExtremeRuntimeHooks.WAE_OMEGA_ENABLED

    /**
     * 規劃（編譯 + 驗證 + 模擬），不執行。
     *
     * @param startStateId 目前畫面在模型裡的狀態；null＝模型第一個狀態
     */
    fun plan(
        command: String,
        pkg: String,
        startStateId: String? = null,
        providedVars: Map<String, String> = emptyMap(),
        modelOverride: AppModel? = null
    ): PlanReport {
        val model = modelOverride ?: ModelStore.load(pkg)
        val compile = IntentCompiler.compile(
            command = command,
            model = model,
            providedVars = providedVars
        )
        if (compile !is IntentCompiler.Result.Compiled) {
            val msg = when (compile) {
                is IntentCompiler.Result.MissingVars -> "missing_vars:${compile.missing}"
                is IntentCompiler.Result.Unsupported -> "unsupported:${compile.reason}"
                else -> "unknown"
            }
            return PlanReport(command, pkg, model != null, compile, null, null, false, msg)
        }
        val verification = PolicyVerifier.verify(compile.plan, model)
        val simulation = model?.let { SimSandbox.simulate(compile.plan, it, startStateId) }
        val ready = verification.verdict != PolicyVerifier.Verdict.UNSAFE &&
            verification.verdict != PolicyVerifier.Verdict.UNDECIDABLE &&
            (simulation == null || simulation.feasible)
        val msg = buildString {
            append("verdict=").append(verification.verdict.name)
            simulation?.let { append(" sim=").append(it.summary()) }
            if (!ready) append(" blocked")
        }
        return PlanReport(command, pkg, model != null, compile, verification, simulation, ready, msg)
    }

    /** 執行一條已證明/已模擬的計畫（呼叫端要先確認 readyToExecute） */
    fun execute(
        plan: TaskIrPlan,
        bridge: VerifiedExecutor.ExecutorBridge,
        suppliedVars: MutableMap<String, String> = HashMap()
    ): VerifiedExecutor.ExecutionReport = VerifiedExecutor.run(plan, bridge, suppliedVars)

    /**
     * 從成功軌跡學習可重用運算子（反統一 → 倉庫）。
     * @return 寫入的運算子數量
     */
    fun learnOperators(traces: List<List<String>>, minSupport: Int = 2): Int {
        if (traces.size < 2) return 0
        val ops = OperatorSynthesizer.synthesize(traces, minSupport)
        var saved = 0
        for (op in ops) {
            if (OperatorRegistry.save(op)) saved++
        }
        return saved
    }

    /** 把一次觀察併進指定 App 的模型（主動探索/真實執行後呼叫） */
    fun mergeObservation(pkg: String, observation: AppModelCompiler.Observation): AppModel {
        val existing = ModelStore.load(pkg)
        val merged = if (existing == null) {
            AppModelCompiler.compile(pkg, listOf(observation))
        } else {
            AppModelCompiler.merge(existing, observation)
        }
        runCatching { ModelStore.save(merged) }
        return merged
    }

    /** 目前模型是否可用（有模型且雜湊驗證通過） */
    fun hasModel(pkg: String): Boolean = ModelStore.load(pkg)?.verifyIntegrity() == true
}
