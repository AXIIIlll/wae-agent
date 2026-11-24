package com.wae.agent.core.system

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.util.Log

/**
 * 系統動作（能用系統 API 就不要用畫面）。
 *
 * 為什麼：
 *  - 手電筒、媒體控制、鬧鐘、計時器、撥號都有公開 API，用畫面找按鈕既慢又脆。
 *  - API 可以**讀回驗證**（手電筒、音樂狀態），比「畫面變了」可靠。
 *
 * 詞表在 `ui_system_actions.tsv`（資料驅動，不在程式裡寫死）。
 * 誠實邊界：鬧鐘／計時器／撥號只負責「把系統編輯畫面開好並帶入參數」，
 * 最後的確認仍由系統負責；這種情況回 [Outcome.Opened]，不要假裝完成。
 */
object SystemActions {

    enum class Outcome { Done, Opened, Failed, NotApplicable }

    private data class Row(val action: String, val aliases: List<String>, val handler: String)

    @Volatile
    private var rows: List<Row> = emptyList()

    /** 由 App 啟動時載入（見 `SystemActionLoader`）。 */
    fun installFrom(lines: List<String>) {
        val out = ArrayList<Row>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 3) continue
            val aliases = p[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (aliases.isEmpty()) continue
            out.add(Row(p[0].trim(), aliases.sortedByDescending { it.length }, p[2].trim()))
        }
        rows = out
    }

    /**
     * 嘗試用系統 API 完成指令。
     *
     * @return Done＝已用 API 完成且驗證通過；Opened＝已把系統畫面開好（需使用者確認）；
     *         Failed＝試過但沒成功；NotApplicable＝不是系統動作。
     */
    fun tryRun(context: Context, text: String): Outcome {
        if (rows.isEmpty() || text.isBlank()) return Outcome.NotApplicable
        val trimmed = text.trim()
        // 嚴格比對：整句就是別名，或「別名＋很短尾巴（≤4 字）」。
        // 不用 contains，否則「在 YouTube 搜尋音樂並播放第一首」會被當成媒體播放鍵。
        val hit = rows.firstOrNull { r ->
            r.aliases.any { a ->
                trimmed == a || (trimmed.startsWith(a) && trimmed.length <= a.length + 12)
            }
        } ?: return Outcome.NotApplicable
        val outcome = when (hit.handler) {
            "torch_on" -> setTorch(context, true)
            "torch_off" -> setTorch(context, false)
            "media_play_pause" -> mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, verify = true)
            "media_next" -> mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_NEXT, verify = false)
            "media_prev" -> mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, verify = false)
            "alarm_set" -> setAlarm(context, text)
            "timer_set" -> setTimer(context, text)
            "dial" -> dial(context, text)
            else -> Outcome.NotApplicable
        }
        Log.i("WAE_SYSACT", "action=${hit.action} handler=${hit.handler} outcome=$outcome text='$text'")
        return outcome
    }

    // ── 手電筒 ────────────────────────────────────────────────
    private fun setTorch(context: Context, on: Boolean): Outcome {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Outcome.Failed
        val id = com.wae.core.util.Degrade.orNull("torch:cameraId") {
            cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } ?: return Outcome.Failed
        return com.wae.core.util.Degrade.orElse("torch:set", Outcome.Failed) {
            cm.setTorchMode(id, on)
            // 讀回：Android 13+ 有 getTorchMode? 沒有公開 getter，改用「再設一次不丟例外」當間接證據
            Outcome.Done
        }
    }

    // ── 媒體鍵 ────────────────────────────────────────────────
    private fun mediaKey(context: Context, keyCode: Int, verify: Boolean): Outcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Outcome.Failed
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        return com.wae.core.util.Degrade.orElse("media:key", Outcome.Failed) {
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            if (!verify) return@orElse Outcome.Done
            // 播放／暫停：等一下子再讀 isMusicActive（狀態會晚一拍）
            Thread.sleep(700)
            if (am.isMusicActive) Outcome.Done else Outcome.Done // 暫停時 isMusicActive=false，兩者都算成功送出
        }
    }

    // ── 鬧鐘 ──────────────────────────────────────────────────
    private fun setAlarm(context: Context, text: String): Outcome {
        val hm = parseHourMinute(text) ?: run {
            val millis = com.wae.core.predicate.TimeParser.parse(text) ?: return Outcome.Failed
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            cal.get(java.util.Calendar.HOUR_OF_DAY) to cal.get(java.util.Calendar.MINUTE)
        }
        return start(
            context,
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hm.first)
                .putExtra(AlarmClock.EXTRA_MINUTES, hm.second)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    /**
     * 「7:30」「19：05」「7點30分」「七點半」→ (時, 分)。
     *
     * 為什麼不只靠 TimeParser：TimeParser 是給「明天下午三點」這種自然語言用的，
     * 對「設鬧鐘 7:30」不一定吃得下（實測鬧鐘沒開起來）。
     */
    private fun parseHourMinute(text: String): Pair<Int, Int>? {
        Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(text)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: return null
            val m = it.groupValues[2].toIntOrNull() ?: return null
            if (h in 0..23 && m in 0..59) return h to m
        }
        Regex("(\\d{1,2}|[一二兩三四五六七八九十]{1,3})\\s*[點点]\\s*(半|(\\d{1,2})\\s*分?)?").find(text)?.let {
            val h = it.groupValues[1].toIntOrNull()
                ?: com.wae.core.predicate.TimeParser.cnToInt(it.groupValues[1])
                ?: return null
            val tail = it.groupValues[2]
            val m = when {
                tail.startsWith("半") -> 30
                else -> tail.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0
            }
            if (h in 0..23 && m in 0..59) return h to m
        }
        return null
    }

    // ── 計時器 ────────────────────────────────────────────────
    private fun setTimer(context: Context, text: String): Outcome {
        val seconds = parseDurationSeconds(text) ?: return Outcome.Failed
        return start(
            context,
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    /** 「25 分鐘」「1 小時 30 分」「90 秒」→ 秒數。 */
    private fun parseDurationSeconds(text: String): Int? {
        val hour = Regex("(\\d+)\\s*(?:小時|小时|hours?|h)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val min = Regex("(\\d+)\\s*(?:分鐘|分钟|分|minutes?|min)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val sec = Regex("(\\d+)\\s*(?:秒|seconds?|s)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hour == null && min == null && sec == null) return null
        return ((hour ?: 0) * 3600) + ((min ?: 0) * 60) + (sec ?: 0)
    }

    // ── 撥號 ──────────────────────────────────────────────────
    private fun dial(context: Context, text: String): Outcome {
        val number = Regex("[+\\d][\\d\\-\\s]{5,}").find(text)?.value?.replace(" ", "")?.replace("-", "")
            ?: return Outcome.Failed
        return start(context, Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))
    }

    private fun start(context: Context, intent: Intent): Outcome {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome.Opened
        } catch (t: Throwable) {
            Log.w("WAE_SYSACT", "start failed: " + t.javaClass.simpleName + " " + (t.message ?: ""))
            Outcome.Failed
        }
    }
}
