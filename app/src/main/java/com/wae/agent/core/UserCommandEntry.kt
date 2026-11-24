package com.wae.agent.core

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 使用者指令的唯一入口（主畫面、驗證題庫、語音助理共用）。
 *
 * 為什麼要有這個：原本「主畫面輸入框」、「題庫頁的執行鍵」、「語音助理」是三份實作，
 * 主畫面先試 Gen-5、題庫直接呼叫 Agent、語音又自己寫一次路由。
 * 結果同一句「打開時鐘」在主畫面成功、在題庫失敗，語音的行為又跟兩者都不同。
 *
 * 現在的順序與主畫面完全一致：
 *  1. 系統值（音量／亮度）：直接走系統 API 並讀回確認
 *  2. 系統動作（手電筒／媒體鍵／鬧鐘／計時器／撥號）
 *  3. Gen-5（純啟動指令會跳過，避免它去畫面上摸索）
 *  4. 路由（FAST／AGENT／ASK）→ 啟動目標 App → 快速管線／開放式執行器
 *
 * 呼叫端只負責把訊息顯示出來（狀態列、Toast 或語音），不重複實作流程。
 */
object UserCommandEntry {

    /**
     * UI 端（Activity 在前景）用這個。
     *
     * @param onGen5 Gen-5 執行完畢時的額外回呼（主畫面用它顯示結果卡；可為 null）
     */
    fun run(
        activity: Activity,
        raw: String,
        onStatus: (String) -> Unit,
        onGen5: ((com.wae.agent.gen5.Gen5Runtime.RunResult) -> Unit)? = null
    ): Boolean = runInternal(activity, raw, onStatus, onGen5, activity)

    /**
     * 已經沒有前景 Activity 的情境（語音助理在 `finish()` 之後才送指令）。
     *
     * 差別只有兩點：不能用 `runOnUiThread`、也不能 `moveTaskToBack`。
     * 其餘流程與 [run] 完全相同——這正是「三個入口行為一致」的重點。
     */
    fun runFromContext(
        context: Context,
        raw: String,
        onStatus: (String) -> Unit,
        onGen5: ((com.wae.agent.gen5.Gen5Runtime.RunResult) -> Unit)? = null
    ): Boolean = runInternal(context, raw, onStatus, onGen5, null)

    private fun runInternal(
        context: Context,
        raw: String,
        onStatus: (String) -> Unit,
        onGen5: ((com.wae.agent.gen5.Gen5Runtime.RunResult) -> Unit)?,
        activity: Activity?
    ): Boolean {
        val svc = WaeVisionAccessibilityService.activeInstance ?: return false

        // ① 系統值（音量／亮度）在「規劃之前」就攔下來。
        //    實測「把音量調低」會被 Task 層當成「去設定找音量」，回報 DONE 但音量 7→7 沒動。
        val sysHandled = com.wae.core.util.Degrade.orNull("entry:sysValue") {
            com.wae.agent.core.system.SystemValueBridge.tryApply(context, raw, "")
        }
        if (sysHandled == true) {
            post(onStatus, "已用系統設定完成「$raw」")
            Log.i("WAE_ENTRY", "system value handled cmd='$raw'")
            return true
        }

        // ② 其他系統動作（手電筒／媒體鍵／鬧鐘／計時器／撥號）：同樣在規劃之前處理。
        com.wae.agent.core.system.SystemActionLoader.ensureLoaded(context)
        when (com.wae.agent.core.system.SystemActions.tryRun(context, raw)) {
            com.wae.agent.core.system.SystemActions.Outcome.Done -> {
                post(onStatus, "已用系統功能完成「$raw」")
                Log.i("WAE_ENTRY", "system action done cmd='$raw'")
                return true
            }
            com.wae.agent.core.system.SystemActions.Outcome.Opened -> {
                post(onStatus, "已開啟系統畫面處理「$raw」")
                Log.i("WAE_ENTRY", "system action opened cmd='$raw'")
                return true
            }
            else -> Unit
        }

        // 「打開 X」這種純啟動指令不需要 Gen-5 去摸索目前畫面。
        // 實測（第 5 題「打開聯絡人」）：Gen-5 先把設定上下滑了 12 步才 fallback。
        val launchOnly = com.wae.core.util.Degrade.orEmpty("entry:decomposeEarly") {
            WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(raw, context)
        }
        val pureLaunch = launchOnly.size == 1 && launchOnly[0].actionKind == ActionKind.LAUNCH_APP

        val gen5Enabled = !pureLaunch && com.wae.core.util.Degrade.orElse("entry:gen5Flag", false) {
            com.wae.agent.gen5.Gen5Runtime.isEnabled(context)
        }
        if (gen5Enabled) {
            // Gen-5 會做感知與多步執行，不能佔用 UI 執行緒
            Thread {
                val result = com.wae.core.util.Degrade.orNull("entry:gen5") {
                    com.wae.agent.gen5.Gen5Runtime.runDetailed(context, raw)
                }
                val handled = result != null &&
                    (result.outcome is com.wae.core.model.Outcome.Success || result.stepsUsed > 0)
                if (handled) {
                    onMain(activity) {
                        com.wae.core.util.Degrade.orNull("entry:gen5Card") { onGen5?.invoke(result!!) }
                        onStatus("Gen-5：" + result!!.summary)
                    }
                } else {
                    // Gen-5 沒處理 → 走原本的路由（在主執行緒做啟動與 UI）
                    onMain(activity) { runByRoute(context, raw, svc, onStatus, activity) }
                }
            }.start()
            return true
        }
        runByRoute(context, raw, svc, onStatus, activity)
        return true
    }

    /** 路由 → 啟動 → 快速管線／開放式執行器。 */
    private fun runByRoute(
        context: Context,
        raw: String,
        svc: WaeVisionAccessibilityService,
        onStatus: (String) -> Unit,
        activity: Activity?
    ) {
        val route = com.wae.core.util.Degrade.orElse("entry:route", ExecutionRouter.Route.FAST) {
            ExecutionRouter.route(context, raw)
        }
        if (route == ExecutionRouter.Route.AGENT) {
            onStatus("交給開放式執行器處理「$raw」…")
            svc.runAgentCommand(raw)
            return
        }
        if (route == ExecutionRouter.Route.ASK) {
            onStatus("我不確定這是不是指令，請換個說法")
            return
        }

        val goals = com.wae.core.util.Degrade.orEmpty("entry:decompose") {
            WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(raw, context)
        }
        val first = goals.firstOrNull()
        if (first != null && first.targetPackageHint.isNotEmpty()) {
            WaeOpenSourceDatabaseRegistry.launchAppByPackageName(context, first.targetPackageHint)
        } else if (first != null) {
            // 沒有要開新 App → 代表要操作底層畫面，把自己退到背景（只有 Activity 做得到）
            if (activity != null) {
                com.wae.core.util.Degrade.orNull("entry:moveBack") { activity.moveTaskToBack(true) }
            }
        }
        svc.executeUserCommandPipelineAsync(raw) { msg -> onStatus(msg) }
        Log.i("WAE_ENTRY", "run route=FAST goals=${goals.size} cmd='$raw'")
    }

    /** 有 Activity 就走它的 UI 執行緒；沒有（語音已 finish）就 post 到主 Looper。 */
    private fun onMain(activity: Activity?, block: () -> Unit) {
        if (activity != null) {
            activity.runOnUiThread(block)
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private fun post(onStatus: (String) -> Unit, message: String) {
        Handler(Looper.getMainLooper()).post { onStatus(message) }
    }
}
