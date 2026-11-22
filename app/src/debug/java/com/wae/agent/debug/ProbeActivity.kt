package com.wae.agent.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.wae.agent.gen5.Gen5Runtime

/**
 * **只存在於 debug build** 的探針 Activity：讓 adb 能把一句話送進正式管線並取回結果。
 *
 * 為什麼需要：`ChatActivity` 是 `exported=false`（正確的安全設定），
 * adb 無法直接送指令。沒有這個探針，就只能靠人在手機上打字驗證，
 * 無法自動化、無法回歸、也無法在 CI 上跑。
 *
 * 用法：
 * ```
 * adb shell am start -n com.wae.agent/.debug.ProbeActivity --es q "開熱點"
 * adb logcat -s WAE_PROBE
 * ```
 * 安全性：只在 `src/debug`，release 不會打包。
 */
class ProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent?.getStringExtra("q")?.takeIf { it.isNotBlank() } ?: run {
            Log.i(TAG, "PROBE no_command")
            finish()
            return
        }
        Thread {
            val t0 = System.currentTimeMillis()
            val result = runCatching { Gen5Runtime.runDetailed(this, command) }
            val ms = System.currentTimeMillis() - t0
            result.onSuccess {
                Log.i(TAG, "PROBE command='$command' outcome=${it.outcome} summary='${it.summary}' steps=${it.stepsUsed} ms=$ms")
            }.onFailure {
                Log.i(TAG, "PROBE command='$command' EXCEPTION=${it.message} ms=$ms")
            }
            runOnUiThread { finish() }
        }.start()
    }

    private companion object {
        const val TAG = "WAE_PROBE"
    }
}
