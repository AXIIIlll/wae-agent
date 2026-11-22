package com.wae.agent.core.runtime

import android.content.Context

/**
 * 進階管線開關的持久化（P1 #13/#14）。
 *
 * 預設都是 false（未驗證程式碼不上線）；使用者可在設定裡做 A/B：
 *  `TASK_OS`（通用長任務）、`WAE_OMEGA`（可驗證執行）、`GROUNDING_2`（grounding 2.0）。
 */
object RuntimeFlags {

    private const val PREF = "wae_runtime_flags"
    private const val KEY_TASKOS = "taskos_enabled"
    private const val KEY_OMEGA = "omega_enabled"
    private const val KEY_GROUNDING2 = "grounding2_enabled"

    fun applyFromPrefs(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        ExtremeRuntimeHooks.TASK_OS_ENABLED = p.getBoolean(KEY_TASKOS, ExtremeRuntimeHooks.TASK_OS_ENABLED)
        ExtremeRuntimeHooks.WAE_OMEGA_ENABLED = p.getBoolean(KEY_OMEGA, ExtremeRuntimeHooks.WAE_OMEGA_ENABLED)
        ExtremeRuntimeHooks.GROUNDING_2_ENABLED = p.getBoolean(KEY_GROUNDING2, ExtremeRuntimeHooks.GROUNDING_2_ENABLED)
    }

    fun isTaskOsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_TASKOS, ExtremeRuntimeHooks.TASK_OS_ENABLED)

    fun isOmegaEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_OMEGA, ExtremeRuntimeHooks.WAE_OMEGA_ENABLED)

    fun isGrounding2Enabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_GROUNDING2, ExtremeRuntimeHooks.GROUNDING_2_ENABLED)

    fun setTaskOs(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TASKOS, enabled).apply()
        ExtremeRuntimeHooks.TASK_OS_ENABLED = enabled
    }

    fun setOmega(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OMEGA, enabled).apply()
        ExtremeRuntimeHooks.WAE_OMEGA_ENABLED = enabled
    }

    fun setGrounding2(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GROUNDING2, enabled).apply()
        ExtremeRuntimeHooks.GROUNDING_2_ENABLED = enabled
    }
}
