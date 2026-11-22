package com.wae.agent.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 靜默失敗的**棘輪**（第 5 項）。
 *
 * 背景：整個 App 有 558 處 `runCatching`，決策核心（述詞＋比對）佔 41 處。
 * 一次把它們全改掉風險太高，但**不能讓它繼續增加**——這個專案吃過
 * 「runCatching 吞掉錯誤、一小時找不到原因」的虧。
 *
 * 棘輪的規則：
 *  1. 決策核心的數量只能減不能增；要新增「吞錯」，先問自己能不能不吞，
 *     真的要降級就改用 `Degrade.orNull(where) { ... }`（會留痕）。
 *  2. 總數同理，但門檻寬鬆：其他模組還有很多待處理的歷史債。
 *  3. `Degrade` 必須真的有人用，否則這個機制就是裝飾品。
 *
 * 降低門檻的方式：把一處 `runCatching` 改寫成 `Degrade`，然後把下面的
 * 常數改成新數字。改常數不是作弊，是「已經還了這筆債」的記錄。
 */
class SilentFailureRatchetTest {

    private fun repoDir(rel: String): File? =
        listOf(File("../$rel"), File(rel), File("E:/WAE/NewAndroidApp/$rel")).firstOrNull { it.exists() }

    private fun countIn(rel: String, needle: String): Int {
        val dir = repoDir(rel) ?: return -1
        var total = 0
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            total += Regex(Regex.escape(needle)).findAll(f.readText(Charsets.UTF_8)).count()
        }
        return total
    }

    @Test
    fun `decision core does not add new silent catches`() {
        val predicate = countIn("app/src/main/java/com/wae/agent/core/predicate", "runCatching")
        val match = countIn("app/src/main/java/com/wae/agent/core/match", "runCatching")
        if (predicate < 0 || match < 0) return   // 在別的目錄跑測試時跳過
        val total = predicate + match
        assertTrue(
            "決策核心的 runCatching 增加了（$total > $BASELINE_DECISION_CORE）。" +
                "要降級請改用 Degrade.orNull(where) { ... }（會留 log）；" +
                "若確實減少了，請把 BASELINE_DECISION_CORE 調成 $total。",
            total <= BASELINE_DECISION_CORE
        )
    }

    @Test
    fun `whole app does not add new silent catches`() {
        val total = countIn("app/src/main/java", "runCatching")
        if (total < 0) return
        assertTrue(
            "全 App 的 runCatching 增加了（$total > $BASELINE_APP）。" +
                "新程式碼請盡量不要吞錯；要降級請用 Degrade 並留痕。",
            total <= BASELINE_APP
        )
    }

    @Test
    fun `degrade is actually used`() {
        val app = countIn("app/src/main/java", "Degrade.or")
        val core = countIn("core/src/main/kotlin", "Degrade.or")
        val total = maxOf(app, 0) + maxOf(core, 0)
        if (app < 0 && core < 0) return
        assertTrue(
            "Degrade 沒人用（$total），那靜默失敗治理就是空的；" +
                "至少要有 $BASELINE_DEGRADE_USES 處改用它。",
            total >= BASELINE_DEGRADE_USES
        )
    }

    private companion object {
        /** 2026-11-22：把述詞執行器 7 處吞錯改成 Degrade 之後的數字。 */
        const val BASELINE_DECISION_CORE = 41

        /** 2026-11-22：同一天的全 App 數字（565 → 558）。 */
        const val BASELINE_APP = 558

        /** 至少要有這麼多處真的用 Degrade（目前 9）。 */
        const val BASELINE_DEGRADE_USES = 9
    }
}
