package com.wae.agent.core.twin

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 「使用者在別的 App 點了一下」→ 記成一條被動軌跡（Twin M2）。
 *
 * 從 4,000 多行的服務裡抽出來的第一塊：這段邏輯只依賴外界的幾個查詢
 * （被動記錄是否關閉、是否正在跑任務、已知套件、當前畫面指紋），
 * 所以可以用 lambda 注入，不需要把整個服務傳進來。
 *
 * 邊界：**只記錄、不執行、不改畫面**。
 */
class TwinClickRecorder(
    private val context: Context,
    private val handler: Handler,
    private val passiveDisabled: () -> Boolean,
    private val busyWithTask: () -> Boolean,
    private val knownPackages: () -> Set<String>,
    private val ambient: () -> Pair<Long, List<Long>>
) {

    fun onClick(pkg: String, src: AccessibilityNodeInfo) {
        val shouldRecord = PassiveRecordPolicy.shouldRecord(
            passiveDisabled = passiveDisabled(),
            isBusyWithTask = busyWithTask(),
            pkg = pkg,
            knownPackages = knownPackages(),
            eventType = EVENT_CLICK
        )
        Log.i(
            TAG,
            "click pkg=$pkg record=$shouldRecord disabled=${passiveDisabled()} busy=${busyWithTask()} " +
                "known=${knownPackages().size}"
        )
        if (!shouldRecord) return

        val r = Rect()
        runCatching { src.getBoundsInScreen(r) }.getOrNull() ?: return
        val before = ambient()

        TwinRecorder.begin(
            pkg = pkg,
            spineBefore = before.first,
            action = ACTION_TAP,
            target = (src.text ?: src.contentDescription ?: "").toString(),
            posture = "",
            sigsBefore = before.second,
            viewId = src.viewIdResourceName ?: "",
            className = src.className?.toString() ?: "",
            roleTag = "",
            gridX = r.centerX(),
            gridY = r.centerY()
        )

        handler.postDelayed({
            runCatching {
                val after = ambient()
                TwinRecorder.complete(after.first, after.second)
                if (TwinRecorder.steps().isNotEmpty()) {
                    TwinRecorder.flush(context)
                    Log.i(TAG, "passive step recorded pkg=$pkg")
                }
            }
        }, COMPLETE_DELAY_MS)
    }

    private companion object {
        const val TAG = "WAE_PASSIVE"
        const val EVENT_CLICK = "CLICK"
        const val ACTION_TAP = "TAP"
        const val COMPLETE_DELAY_MS = 700L
    }
}
