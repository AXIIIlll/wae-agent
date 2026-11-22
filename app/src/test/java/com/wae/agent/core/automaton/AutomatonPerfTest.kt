package com.wae.agent.core.automaton

import com.wae.agent.core.twin.TwinStep
import org.junit.Test

/** 診斷（不中斷建置）：量測編譯耗時，驗證「500 邊編譯 < 50ms」這個目標。 */
class AutomatonPerfTest {

    private val POSTURES = listOf("NAV_HOME", "LIST_FEED", "DETAIL", "FORM_INPUT", "SEARCH_RESULTS")

    private fun mk(i: Int, j: Int) = TwinStep(
        pkg = "p",
        spineBefore = i.toLong(),
        spineAfter = j.toLong(),
        action = "TAP",
        target = "t",
        posture = POSTURES[i % POSTURES.size],
        ok = true,
        ms = 200,
        ts = 1_700_000_000_000L + i,
        sigsBefore = (i * 1000L until i * 1000L + 40).toList(),
        sigsAfter = (j * 1000L until j * 1000L + 40).toList(),
        viewId = "pkg:id/v$i",
        className = "android.widget.Button",
        roleTag = "button"
    )

    @Test
    fun `measure compile latency`() {
        val steps = ArrayList<TwinStep>()
        for (i in 0 until 500) {
            for (k in 1..4) {
                steps.add(mk(i, i + k))
            }
        }
        // 暖機
        repeat(3) { AutomatonCompiler.compile("p", steps, null, false, 1_700_000_000_000L) }
        val t0 = System.nanoTime()
        val a = AutomatonCompiler.compile("p", steps, null, false, 1_700_000_000_000L)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("PERF steps=${steps.size} states=${a.stateCount()} edges=${a.edgeCount()} tier=${a.tier} compileMs=%.1f".format(ms))
        val t1 = System.nanoTime()
        repeat(20) { AutomatonCompiler.compile("p", steps, a, false, 1_700_000_000_000L) }
        val inc = (System.nanoTime() - t1) / 1_000_000.0 / 20
        println("PERF incrementalAvgMs=%.1f".format(inc))

        // 規劃耗時（同一張圖）
        val start = a.states.first().id
        repeat(20) { AutomatonPlanner.planToSelectorHint(a, start, "v500") }
        val t2 = System.nanoTime()
        var plan: AutomatonPlanner.Plan? = null
        repeat(20) { plan = AutomatonPlanner.planToSelectorHint(a, start, "v500") }
        val planMs = (System.nanoTime() - t2) / 1_000_000.0 / 20
        println("PERF plannerAvgMs=%.2f planSteps=${plan?.steps} success=%.2f"
            .format(planMs, plan?.expectedSuccess ?: 0f))

        // 以「已知兩點」量測實際有解的路徑（代表性數字）
        val from = a.states.first().id
        val to = a.states.last().id
        repeat(10) { AutomatonPlanner.planToState(a, from, to) }
        val t3 = System.nanoTime()
        var p2: AutomatonPlanner.Plan? = null
        repeat(20) { p2 = AutomatonPlanner.planToState(a, from, to) }
        val p2ms = (System.nanoTime() - t3) / 1_000_000.0 / 20
        println("PERF pathFindAvgMs=%.2f steps=${p2?.steps} success=%.2f"
            .format(p2ms, p2?.expectedSuccess ?: 0f))

        // M4：疊化效果（節點數下降倍率 + 耗時）
        val t4 = System.nanoTime()
        val m = AutomatonReducer.minimize(a)
        val reduceMs = (System.nanoTime() - t4) / 1_000_000.0
        val ratio = if (m.stateCount() > 0) a.stateCount().toFloat() / m.stateCount() else 0f
        println("PERF reduceMs=%.2f statesBefore=${a.stateCount()} statesAfter=${m.stateCount()} ratio=%.2fx edgesAfter=${m.edgeCount()}"
            .format(reduceMs, ratio))

        // 真實 App 常見型態：同一種「頁面模板」重複出現（只是內容不同）→ 應大幅合併
        val dupStates = ArrayList<AutomatonState>()
        val dupEdges = ArrayList<AutomatonEdge>()
        repeat(64) { i ->
            dupStates.add(AutomatonState("A$i", MinHash.fingerprint((i * 10L until i * 10L + 20).toList()), "LIST_FEED"))
            dupStates.add(AutomatonState("B$i", MinHash.fingerprint((i * 10L + 1 until i * 10L + 21).toList()), "DETAIL"))
            dupEdges.add(AutomatonEdge("A$i", "B$i", "TAP", "id:row", 5, 0, 300, 1L, 1L))
            dupEdges.add(AutomatonEdge("B$i", "A$i", "TAP", "id:back", 5, 0, 300, 1L, 1L))
        }
        val dup = Automaton(pkg = "p", states = dupStates, edges = dupEdges)
        val t5 = System.nanoTime()
        val md = AutomatonReducer.minimize(dup)
        val dupMs = (System.nanoTime() - t5) / 1_000_000.0
        println("PERF duplicateTemplate statesBefore=${dup.stateCount()} statesAfter=${md.stateCount()} " +
            "ratio=%.1fx reduceMs=%.2f".format(dup.stateCount().toFloat() / md.stateCount(), dupMs))
    }
}
