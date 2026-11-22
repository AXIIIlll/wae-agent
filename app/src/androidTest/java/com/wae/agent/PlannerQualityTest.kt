package com.wae.agent

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wae.agent.llm.LlmPlanner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M1 驗收：Qwen3-0.6B 計畫品質測試（需 emulator/裝置 + 已打包模型）。
 *
 * 讀 `assets/agent/test_goals_50.tsv` 的 50 句目標，逐句產生計畫，
 * 統計：JSON 合法率、平均/最大規劃時間，並把每句結果寫到外部檔方便 adb pull。
 *
 * 判定：合法率 ≥75%（計畫書 M1 門檻；低於時仍寫報告，但測試會失敗）。
 */
@RunWith(AndroidJUnit4::class)
class PlannerQualityTest {

    @Test
    fun planValidity50() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val goals = ctx.assets.open("agent/test_goals_50.tsv").bufferedReader().useLines { seq ->
            seq.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
        assertTrue("測試語料不足", goals.size >= 20)

        val planner = LlmPlanner(ctx, installedAppsProvider = { emptyList() }, nThreads = 4)
        val loadStart = SystemClock.elapsedRealtime()
        val loaded = planner.ensureLoaded()
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        assertTrue("模型載入失敗", loaded)

        val out = StringBuilder()
        out.append("loadMs=").append(loadMs).append('\n')
        var ok = 0
        var total = 0
        var totalMs = 0L
        var maxMs = 0L
        for (goal in goals) {
            total++
            val t0 = SystemClock.elapsedRealtime()
            val plan = planner.plan(goal)
            val ms = SystemClock.elapsedRealtime() - t0
            totalMs += ms
            if (ms > maxMs) maxMs = ms
            val valid = plan != null && plan.steps.isNotEmpty()
            if (valid) ok++
            out.append(if (valid) "OK" else "FAIL").append('\t').append(ms).append('\t')
                .append(goal).append('\t')
                .append(plan?.steps?.size ?: 0).append('\n')
        }
        planner.unload()

        val rate = ok * 100.0 / total
        out.append("SUMMARY ok=").append(ok).append('/').append(total)
            .append(" rate=").append("%.1f".format(rate)).append('%')
            .append(" avgMs=").append(totalMs / total.coerceAtLeast(1))
            .append(" maxMs=").append(maxMs).append('\n')
        android.util.Log.i("WAE_PLAN_TEST", out.toString())
        runCatching {
            val f = File(ctx.getExternalFilesDir(null), "plan_quality_50.txt")
            f.writeText(out.toString(), Charsets.UTF_8)
        }
        assertTrue("計畫合法率 ${"%.1f".format(rate)}% < 75%", rate >= 75.0)
    }
}
