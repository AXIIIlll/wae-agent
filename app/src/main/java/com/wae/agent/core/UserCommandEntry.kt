package com.wae.agent.core

import android.app.Activity
import android.util.Log

/**
 * 使用者指令的唯一入口（主畫面與驗證題庫共用）。
 *
 * 為什麼要有這個：原本「主畫面輸入框」與「題庫頁的執行鍵」走的是**兩條不同的引擎**——
 * 主畫面先試 Gen-5，沒處理才路由到快速管線；題庫卻直接呼叫 Agent 路徑。
 * 結果同一句「打開時鐘」在主畫面成功、在題庫失敗，題庫量到的根本不是使用者實際的行為。
 *
 * 現在的順序與主畫面完全一致：
 *  1. Gen-5（預設開啟；處理成功或有用到步數就結束）
 *  2. 路由（FAST／AGENT／ASK）
 *  3. FAST：先由 Activity 啟動目標 App（避開 Android 背景啟動 Activity 的限制），
 *     沒有目標 App 就把自己退到背景，露出底層畫面
 *  4. AGENT：交給開放式執行器
 *
 * 呼叫端只負責把訊息顯示出來（Toast 或狀態列），不重複實作流程。
 */
object UserCommandEntry {

    /**
     * @param activity 目前在前景的 Activity（啟動 App、退到背景都需要它）
     * @param raw 使用者輸入的原始句子
     * @param onStatus 狀態訊息（可能來自背景執行緒，呼叫端自行切回 UI 執行緒）
     * @param onGen5 Gen-5 執行完畢時的額外回呼（主畫面用它顯示結果卡；可為 null）
     * @return true 表示已接手執行
     */
    fun run(
        activity: Activity,
        raw: String,
        onStatus: (String) -> Unit,
        onGen5: ((com.wae.agent.gen5.Gen5Runtime.RunResult) -> Unit)? = null
    ): Boolean {
        val svc = WaeVisionAccessibilityService.activeInstance ?: return false

        // ① 系統值（音量／亮度）在「規劃之前」就攔下來。
        //    為什麼要在這麼前面：實測「把音量調低」會被 Task 層當成「去設定找音量」，
        //    回報 DONE（steps=1）但音量 7→7 根本沒動。系統值不該進規劃。
        //    只有系統 API 真的成功（讀回一致）才短路；失敗就照原本流程走，不假裝成功。
        val sysHandled = com.wae.core.util.Degrade.orNull("entry:sysValue") {
            com.wae.agent.core.system.SystemValueBridge.tryApply(activity, raw, "")
        }
        if (sysHandled == true) {
            onStatus("已用系統設定完成「$raw」")
            Log.i("WAE_ENTRY", "system value handled cmd='$raw'")
            return true
        }

        // ①.5 其他系統動作（手電筒／媒體鍵／鬧鐘／計時器／撥號）：同樣在規劃之前處理。
        //      Done＝API 完成；Opened＝已把系統畫面開好（鬧鐘／計時器／撥號仍需使用者確認）。
        com.wae.agent.core.system.SystemActionLoader.ensureLoaded(activity)
        when (com.wae.agent.core.system.SystemActions.tryRun(activity, raw)) {
            com.wae.agent.core.system.SystemActions.Outcome.Done -> {
                onStatus("已用系統功能完成「$raw」")
                Log.i("WAE_ENTRY", "system action done cmd='$raw'")
                return true
            }
            com.wae.agent.core.system.SystemActions.Outcome.Opened -> {
                onStatus("已開啟系統畫面處理「$raw」")
                Log.i("WAE_ENTRY", "system action opened cmd='$raw'")
                return true
            }
            else -> Unit
        }

        // 「打開 X」這種**純啟動**指令不需要 Gen-5 去摸索目前畫面。
        // 實測（2026-11-23，第 5 題「打開聯絡人」）：Gen-5 先把設定上下滑了 12 步、
        // steps_exhausted 之後才 fallback 用 intent 直接開——結果對，過程完全沒必要。
        val launchOnly = com.wae.core.util.Degrade.orEmpty("entry:decomposeEarly") {
            WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(raw, activity)
        }
        val pureLaunch = launchOnly.size == 1 &&
            launchOnly[0].actionKind == ActionKind.LAUNCH_APP

        val gen5Enabled = !pureLaunch && com.wae.core.util.Degrade.orElse("entry:gen5Flag", false) {
            com.wae.agent.gen5.Gen5Runtime.isEnabled(activity)
        }
        if (gen5Enabled) {
            // Gen-5 會做感知與多步執行，不能佔用 UI 執行緒（主畫面原本也是背景執行緒）
            Thread {
                val result = com.wae.core.util.Degrade.orNull("entry:gen5") {
                    com.wae.agent.gen5.Gen5Runtime.runDetailed(activity, raw)
                }
                val handled = result != null &&
                    (result.outcome is com.wae.core.model.Outcome.Success || result.stepsUsed > 0)
                if (handled) {
                    activity.runOnUiThread {
                        com.wae.core.util.Degrade.orNull("entry:gen5Card") { onGen5?.invoke(result!!) }
                        onStatus("Gen-5：" + result!!.summary)
                    }
                } else {
                    // Gen-5 沒處理 → 走原本的路由（在主執行緒做 UI 與啟動）
                    activity.runOnUiThread { runByRoute(activity, raw, svc, onStatus) }
                }
            }.start()
            return true
        }
        runByRoute(activity, raw, svc, onStatus)
        return true
    }

    /** 路由 → 啟動 → 快速管線／開放式執行器。 */
    private fun runByRoute(
        activity: Activity,
        raw: String,
        svc: WaeVisionAccessibilityService,
        onStatus: (String) -> Unit
    ) {
        val route = com.wae.core.util.Degrade.orElse("entry:route", ExecutionRouter.Route.FAST) {
            ExecutionRouter.route(activity, raw)
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
            WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(raw, activity)
        }
        val first = goals.firstOrNull()
        if (first != null && first.targetPackageHint.isNotEmpty()) {
            WaeOpenSourceDatabaseRegistry.launchAppByPackageName(activity, first.targetPackageHint)
        } else if (first != null) {
            // 沒有要開新 App → 代表要操作底層畫面，把自己退到背景
            com.wae.core.util.Degrade.orNull("entry:moveBack") { activity.moveTaskToBack(true) }
        }
        svc.executeUserCommandPipelineAsync(raw) { msg -> onStatus(msg) }
        Log.i("WAE_ENTRY", "run route=FAST goals=${goals.size} cmd='$raw'")
    }
}
