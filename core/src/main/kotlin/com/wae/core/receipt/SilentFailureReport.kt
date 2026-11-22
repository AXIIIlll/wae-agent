package com.wae.core.receipt

/**
 * 靜默失敗報表（C3）：把「回報成功但其實沒發生」按 App／版本切開。
 *
 * 為什麼要按版本切：同一個 App，上一版正常、這一版壞掉——這是版本腐爛最早的訊號。
 */
data class AppSilentStats(
    val pkg: String,
    val appVersion: Long,
    val tasks: Int,
    val tasksWithSilentFailure: Int,
    val steps: Int,
    val silentSteps: Int
) {
    val taskRate: Float
        get() = if (tasks == 0) 0f else tasksWithSilentFailure.toFloat() / tasks
}

object SilentFailureReport {

    fun summarize(receipts: List<TaskReceipt>): List<AppSilentStats> =
        receipts
            .groupBy { it.pkg to it.appVersion }
            .map { (key, group) ->
                val (pkg, version) = key
                AppSilentStats(
                    pkg = pkg,
                    appVersion = version,
                    tasks = group.size,
                    tasksWithSilentFailure = group.count { it.silentFailures > 0 },
                    steps = group.sumOf { it.steps.size },
                    silentSteps = group.sumOf { it.silentFailures }
                )
            }
            .sortedWith(compareByDescending<AppSilentStats> { it.taskRate }.thenBy { it.pkg })

    fun overallRate(receipts: List<TaskReceipt>): Float {
        if (receipts.isEmpty()) return 0f
        val withSilent = receipts.count { it.silentFailures > 0 }
        return withSilent.toFloat() / receipts.size
    }

    /** 給人看的一行（log／報表用）。 */
    fun render(stats: List<AppSilentStats>): String = stats.joinToString("\n") { s ->
        "pkg=${s.pkg} ver=${s.appVersion} tasks=${s.tasks} silent=${s.tasksWithSilentFailure} rate=${"%.2f".format(java.util.Locale.ROOT, s.taskRate)}"
    }
}
