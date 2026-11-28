package com.wae.agent.core.goal

import com.wae.core.predicate.Phi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任務 → Phi 編譯器的契約。
 *
 * 最重要的一條：**編譯不出來時必須誠實說「不可編譯」**，
 * 因為呼叫端會用這個結果決定「能不能走圖導航」——
 * 給一個假的 `Top` 會讓圖回報成功而實際什麼都沒做。
 */
class TaskToPhiTest {

    private fun goal(
        command: String,
        app: String = "",
        intent: String = "",
        slots: Map<String, String> = emptyMap()
    ) = TaskToPhi.TaskGoal(command, app, intent, slots)

    @Test
    fun `launch compiles to package predicate`() {
        val r = TaskToPhi.compile(goal("打開設定", app = "com.android.settings", intent = "LAUNCH"))
        assertTrue(r is TaskToPhi.Result.Compiled)
        assertEquals(Phi.PkgIs("com.android.settings"), (r as TaskToPhi.Result.Compiled).phi)
    }

    @Test
    fun `launch without package is not compilable`() {
        val r = TaskToPhi.compile(goal("打開那個東西", intent = "LAUNCH"))
        assertTrue("沒有 App 就不可編譯", r is TaskToPhi.Result.NotCompilable)
        assertTrue((r as TaskToPhi.Result.NotCompilable).reason.contains("LAUNCH"))
    }

    @Test
    fun `view with app and target becomes package plus label`() {
        val r = TaskToPhi.compile(
            goal("在電話查看通話紀錄", app = "com.samsung.android.dialer", intent = "VIEW",
                slots = mapOf("target" to "通話紀錄"))
        )
        assertTrue(r is TaskToPhi.Result.Compiled)
        val phi = (r as TaskToPhi.Result.Compiled).phi
        assertTrue("應是 And(pkg, label)", phi is Phi.And)
        val items = (phi as Phi.And).items
        assertEquals(Phi.PkgIs("com.samsung.android.dialer"), items[0])
        assertTrue(items[1] is Phi.Exists)
    }

    @Test
    fun `find requires a query and keeps it as a slot`() {
        val ok = TaskToPhi.compile(
            goal("在 YouTube 搜尋烹飪影片", app = "com.google.android.youtube", intent = "FIND",
                slots = mapOf("query" to "烹飪影片"))
        )
        assertTrue(ok is TaskToPhi.Result.Compiled)
        val slots = (ok as TaskToPhi.Result.Compiled).slots
        assertEquals("烹飪影片", slots["query"])
        assertEquals("烹飪影片", slots["target"])

        val bad = TaskToPhi.compile(goal("在 YouTube 搜尋", app = "com.google.android.youtube", intent = "FIND"))
        assertTrue("缺 query 就不可編譯", bad is TaskToPhi.Result.NotCompilable)
    }

    @Test
    fun `toggle needs a desired state`() {
        val ok = TaskToPhi.compile(
            goal("關掉 Wi-Fi", intent = "TOGGLE", slots = mapOf("target" to "Wi-Fi", "desired" to "off"))
        )
        assertTrue(ok is TaskToPhi.Result.Compiled)
        assertEquals("false", (ok as TaskToPhi.Result.Compiled).slots["desired"])

        val bad = TaskToPhi.compile(goal("切換 Wi-Fi", intent = "TOGGLE", slots = mapOf("target" to "Wi-Fi")))
        assertTrue("缺 desired 就不可編譯", bad is TaskToPhi.Result.NotCompilable)
    }

    @Test
    fun `toggle accepts chinese on off words`() {
        for ((raw, expected) in listOf("開" to "true", "開啟" to "true", "關" to "false", "關閉" to "false")) {
            val r = TaskToPhi.compile(
                goal("x", intent = "TOGGLE", slots = mapOf("target" to "藍牙", "desired" to raw))
            )
            assertEquals("『$raw』應解析成 $expected", expected, (r as TaskToPhi.Result.Compiled).slots["desired"])
        }
    }

    @Test
    fun `unknown intent is not compilable instead of guessing`() {
        val r = TaskToPhi.compile(goal("把音量調低", intent = "ADJUST"))
        assertTrue(r is TaskToPhi.Result.NotCompilable)
    }

    @Test
    fun `target falls back across slot names`() {
        // 有的呼叫端叫 object，有的叫 payload；不要求上層統一，這裡吸收差異
        val a = TaskToPhi.compile(goal("x", app = "p", intent = "VIEW", slots = mapOf("object" to "歷史紀錄")))
        val b = TaskToPhi.compile(goal("x", app = "p", intent = "VIEW", slots = mapOf("payload" to "歷史紀錄")))
        assertEquals(
            (a as TaskToPhi.Result.Compiled).phi,
            (b as TaskToPhi.Result.Compiled).phi
        )
    }
}
