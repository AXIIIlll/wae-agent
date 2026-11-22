package com.wae.agent.gen5

import android.content.Context
import android.util.Log

/**
 * App 內自我測試（不用 adb）。
 *
 * 為什麼要做進 App：使用者（跟未來的我）不該為了「它到底還能不能用」去接電腦。
 * 九個案例刻意選「狀態無關」的動作，任何畫面下都能跑；
 * 會改變裝置狀態（音量、亮度、開 App），所以一定要由使用者主動按下。
 */
object SelfTest {

    private const val TAG = "WAE_GEN5"
    private const val ASSET_CASES = "open_source_ai_databases/selftest_cases.tsv"

    data class Case(val command: String, val expect: String)

    data class Result(
        val command: String,
        val expect: String,
        val actual: String,
        val pass: Boolean,
        val detail: String
    )

    fun cases(context: Context): List<Case> {
        val lines = runCatching {
            context.assets.open(ASSET_CASES).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrDefault(emptyList())
        return lines.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val cells = line.split('\t')
            if (cells.size < 2) return@mapNotNull null
            Case(cells[0].trim(), cells[1].trim())
        }
    }

    fun run(context: Context, onProgress: (String) -> Unit = {}): List<Result> {
        val out = ArrayList<Result>()
        for (case in cases(context)) {
            onProgress(case.command)
            val detail = runCatching { Gen5Runtime.run(context, case.command) }
                .getOrElse { "ERROR:" + it.message }
            val actual = when {
                detail.contains("SUCCESS") -> "SUCCESS"
                detail.contains("ABSTAIN") -> "ABSTAIN"
                else -> "UNKNOWN"
            }
            out.add(Result(case.command, case.expect, actual, actual == case.expect, detail))
            Log.i(TAG, "selftest ${case.command} expect=${case.expect} actual=$actual")
        }
        return out
    }

    fun summary(results: List<Result>): String {
        val passed = results.count { it.pass }
        return "自我測試：$passed/${results.size} 通過"
    }

    /** 給 probe 用的單行輸出（adb 驗證）。 */
    fun runToText(context: Context): String {
        val results = run(context)
        return render(results).replace('\n', '|')
    }

    fun render(results: List<Result>): String = buildString {
        append(summary(results)).append("\n\n")
        for (r in results) {
            append(if (r.pass) "✓ " else "✗ ").append(r.command)
            if (!r.pass) append("（預期 ").append(r.expect).append("，實際 ").append(r.actual).append("）")
            append('\n')
        }
    }

    /** 有沒有可跑的案例（assets 漏打包時回 false）。 */
    fun hasCases(context: Context): Boolean = cases(context).isNotEmpty()
}
