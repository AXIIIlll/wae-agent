package com.wae.agent.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 語音模組的紀錄工具（Phase 0 / D3）。
 *
 * 規則：
 *  - release（非 debuggable）只記「事件碼」，**絕不記使用者指令原文或畫面文字**。
 *  - debug 才記完整內容，方便開發。
 */
object VoiceLog {

    fun isDebug(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 事件碼（任何 build 都可記；不含使用者資料） */
    fun event(tag: String, code: String) {
        Log.i(tag, code)
    }

    /** 詳細內容（只有 debug build 會輸出） */
    fun detail(context: Context, tag: String, message: String) {
        if (isDebug(context)) Log.d(tag, message)
    }

    /** 錯誤：只記類型與訊息，不含使用者文字 */
    fun error(tag: String, what: String, t: Throwable? = null) {
        if (t == null) Log.e(tag, what) else Log.e(tag, what, t)
    }
}
