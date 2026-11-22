package com.wae.agent.agent

import android.app.Activity
import android.app.AlertDialog

/** 反問橋接：把 Agent 的問題變成助理卡片/對話框（M3 會接語音）。 */
class AskUserBridge(private val activity: Activity) : UserAsker {

    @Volatile
    private var lastAnswer: String? = null

    override fun ask(question: String, options: List<String>): String? {
        lastAnswer = null
        if (options.isEmpty()) {
            return null
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(question)
                .setItems(options.toTypedArray()) { _, which ->
                    lastAnswer = options.getOrNull(which)
                }
                .setOnCancelListener { lastAnswer = null }
                .show()
        }
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline && lastAnswer == null) {
            Thread.sleep(100)
        }
        return lastAnswer
    }
}
