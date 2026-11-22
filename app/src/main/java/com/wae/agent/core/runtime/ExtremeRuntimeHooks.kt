package com.wae.agent.core.runtime

import android.content.Context
import android.util.Log
import com.wae.agent.core.action.NegativeEdge
import com.wae.agent.core.automaton.Automaton
import com.wae.agent.core.automaton.AutomatonPlanner
import com.wae.agent.core.automaton.EdgeIndex
import com.wae.agent.core.automaton.MinHash
import com.wae.agent.core.decision.DecisionMemo

/**
 * 執行期掛鉤：只保留**會改變行為**或**有實際查詢點**的能力。
 *
 * 2026-11-16 清理：刪掉 9 個「只寫 log、沒有任何查詢點」的旗標與它們的計算
 * （S9 推測執行、S11 時間旅行、S16 AOT、S18/S22 參數校準與圖健康度、S20 meta graph、
 * S21 熱路徑、S10 負空間、S14 任務生成、S24 digest）。它們每跑完一個任務就花 CPU
 * 算一份沒有人讀的統計，是純負債。
 */
object ExtremeRuntimeHooks {

    /** 決策 memo：`WaeV26PipelineOrchestrator` 與服務層都有查詢點。 */
    @Volatile var MEMO_ENABLED: Boolean = true

    /** 靜態安全型別（S8）：gatePlan 會用它直接拒絕不安全的計畫。 */
    @Volatile var SAFE_TYPES_ENABLED: Boolean = true

    /**
     * S29 線性決策頭（候選元素 + 規劃邊重排序）。
     *
     * **預設開啟、且 UI 不提供關閉開關**（使用者 2026-11-14 決定）。
     * 保留成 var 只是為了單元測試能驗證「關閉時回傳 0」與緊急除錯時可從程式碼覆寫；
     * 正式 App 沒有任何關閉入口。
     */
    @Volatile var DECISION_HEAD_ENABLED: Boolean = true

    /**
     * S32 Grounding 2.0（多通道融合 + 空間關係 + 標籤歸屬 + 驗證式定位）。
     *
     * 預設 **false**：新模組先以 A/B 驗證 Top-1/錯點率不退化才開啟。
     * 關閉時完全走舊的候選產生/排序路徑，行為與現在一致。
     */
    @Volatile var GROUNDING_2_ENABLED: Boolean = true

    /**
     * S33 Task OS：通用長任務引擎（任務圖／資料流／修復／續跑／技能）。
     *
     * 預設 **false**：長任務會改變指令拆解與執行路徑，先以 A/B 驗證
     * EASY/MEDIUM 不退步、長任務真的完成，才切預設開。
     */
    @Volatile var TASK_OS_ENABLED: Boolean = true

    /**
     * S34 WAE-Ω：離線可驗證的 App 作業系統（本體論／App Model／意圖編譯／
     * 策略證明／模擬沙盒／驗證式執行／反單一能力合成／離線模型）。
     *
     * 預設 **false**。v1 採 shadow 模式：只做編譯/驗證/模擬並寫 `WAE_OMEGA` log，
     * 不改變實際執行路徑。
     */
    @Volatile var WAE_OMEGA_ENABLED: Boolean = false

    /**
     * LLM Agent（S60）：目標類語句 → LLM 規劃 → reranker 決策 → 既有執行/安全層。
     * 預設 **false**：M1–M4 驗證期間只開 emulator A/B；達標後才切預設。
     */
    @Volatile var AGENT_ENABLED: Boolean = false

    /** Agent 失敗時是否退回舊的快速路徑（明確動詞）。 */
    @Volatile var AGENT_FASTPATH_ENABLED: Boolean = true

    // ── 迴圈層 ────────────────────────────────────────────────────

    /** 每個迴圈開始。目前沒有需要記錄的東西，保留簽名給呼叫端。 */
    fun onLoopStart(
        stepIdx: Int,
        pkg: String,
        stateId: String?,
        sigHash: Long,
        posture: String,
        lastOp: String
    ) {
        // 原本的 S11 時間旅行快照 / S9 推測命中統計已移除（只寫 log，無查詢點）
    }

    /** 動作派發前。原本用於 S21 熱路徑收集，已移除。 */
    fun onDispatch(pkg: String, selector: String, bucket: MutableList<String>) {
        // no-op
    }

    /** 動作完成後：決策 memo +（可選）元素層級可操作圖。 */
    fun onStepResult(
        pkg: String,
        sigHash: Long,
        intentTag: String,
        selector: String,
        ok: Boolean,
        automaton: Automaton?,
        currentStateId: String?
    ) {
        if (MEMO_ENABLED && selector.isNotEmpty()) {
            DecisionMemo.record(pkg, sigHash, intentTag, selector, ok)
        }
        if (GROUNDING_2_ENABLED && selector.isNotEmpty()) {
            runCatching {
                com.wae.agent.core.grounding.ElementGraph.record(
                    pkg = pkg,
                    stateKey = sigHash.toString(),
                    selector = selector,
                    success = ok
                )
            }
        }
    }

    /** 目標完成。原本用於 S21 熱路徑記錄，已移除。 */
    fun onGoalDone(pkg: String, selectors: List<String>) {
        // no-op
    }

    /** 失敗。原本用於 S11 回退建議（只寫 log），已移除。 */
    fun onGoalFailed(pkg: String) {
        // no-op
    }

    // ── 規劃層 ────────────────────────────────────────────────────

    /**
     * S8 靜態安全過濾 + 乾跑閘門。
     *
     * @return 可執行的計畫；若靜態不安全或乾跑失敗則回 null（誠實拒絕）
     */
    fun gatePlan(
        pkg: String,
        plan: AutomatonPlanner.Plan,
        presentSelectors: Set<String>,
        spineChanged: Boolean
    ): AutomatonPlanner.Plan? {
        if (SAFE_TYPES_ENABLED) {
            val verdict = com.wae.agent.core.safety.SafetyTypeSystem.checkPlan(pkg, plan)
            if (verdict is com.wae.agent.core.safety.SafetyTypeSystem.Verdict.Reject) {
                Log.i("WAE_TYPES", "plan rejected statically: ${verdict.rule} (${verdict.detail})")
                return null
            }
        }
        // S16：先乾跑，任何一步守衛不成立就不落真機
        val program = com.wae.agent.core.program.ProgramCompiler.compileFromEdges(
            pkg = pkg,
            entryState = plan.states.firstOrNull() ?: "",
            goalState = plan.states.lastOrNull() ?: "",
            edges = plan.edges,
            executions = plan.edges.maxOfOrNull { it.total } ?: 0,
            successRate = plan.expectedSuccess,
            minExecutions = 1,
            minSuccessRate = 0.0f
        ) ?: return plan
        val verdict = com.wae.agent.core.program.ProgramRunner.dryRun(
            program,
            com.wae.agent.core.program.ProgramRunner.Facts(
                presentSelectors = presentSelectors,
                spineChanged = spineChanged,
                pkg = pkg
            )
        )
        if (!verdict.ok) {
            Log.i("WAE_PROGRAM", "dry run failed → fallback (${verdict.reason})")
            return null
        }
        return plan
    }

    // ── 任務結束層 ────────────────────────────────────────────────

    /** 一次把「任務結束才跑」的分析做完。目前只剩 Grounding 2.0 的元素圖落盤。 */
    fun onTaskEnd(
        ctx: Context?,
        pkg: String,
        automaton: Automaton?,
        negatives: List<NegativeEdge>,
        steps: Int
    ) {
        if (GROUNDING_2_ENABLED) {
            runCatching { com.wae.agent.core.grounding.ElementGraph.save() }
        }
    }

    /** S23：在快取圖時建一次 O(1) 查表（供未來熱路徑查詢） */
    fun buildIndex(automaton: Automaton): Any? {
        return runCatching {
            val idx = EdgeIndex.build(automaton.edges)
            idx
        }.getOrNull()
    }

    fun sigHashOf(sigs: List<Long>): Long = MinHash.fingerprint(sigs).firstOrNull() ?: 0L
}
