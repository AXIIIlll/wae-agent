package com.wae.core

import com.wae.core.rule.AppCapabilityTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每 App 能力表：把「這支手機／這個 App 做得到什麼」變成可累積的事實。
 *
 * 設計原則：**沒經驗 ≠ 不行**。
 * 只有「成功 0 次、失敗達門檻」才算「這個 App 不支援」，避免一次倒楣就永久跳過。
 */
class AppCapabilityTest {

    @Test
    fun `unknown app purpose is not treated as unsupported`() {
        val table = AppCapabilityTable()
        assertNull(table.supports("com.a", "G2.2"))
        assertFalse(table.lacks("com.a", "G2.2"))
    }

    @Test
    fun `one failure is not enough to mark as lacking`() {
        val table = AppCapabilityTable()
        table.record("com.a", "G2.2", ok = false)
        assertNull("一次失敗只是沒把握，不該直接判死", table.supports("com.a", "G2.2"))
        assertFalse(table.lacks("com.a", "G2.2"))
    }

    @Test
    fun `repeated failures mark the app as lacking that purpose`() {
        val table = AppCapabilityTable()
        repeat(AppCapabilityTable.LACK_THRESHOLD) { table.record("com.a", "G2.2", ok = false) }
        assertTrue(table.lacks("com.a", "G2.2"))
        assertEquals(false, table.supports("com.a", "G2.2"))
    }

    @Test
    fun `success wins over previous failures`() {
        val table = AppCapabilityTable()
        repeat(AppCapabilityTable.LACK_THRESHOLD) { table.record("com.a", "G2.2", ok = false) }
        table.record("com.a", "G2.2", ok = true)
        assertEquals(true, table.supports("com.a", "G2.2"))
        assertFalse("有成功過就不算不支援", table.lacks("com.a", "G2.2"))
    }

    @Test
    fun `apps for purpose lists only known-good apps`() {
        val table = AppCapabilityTable()
        table.record("com.good", "G2.2", ok = true)
        table.record("com.bad", "G2.2", ok = false)
        assertEquals(listOf("com.good"), table.appsFor("G2.2"))
    }

    @Test
    fun `round trip through lines keeps counts`() {
        val table = AppCapabilityTable()
        table.record("com.a", "G2.2", ok = true)
        table.record("com.a", "G2.2", ok = false)
        val parsed = AppCapabilityTable.parse(table.toLines().asSequence())
        val row = parsed.get("com.a", "G2.2")!!
        assertEquals(1, row.success)
        assertEquals(1, row.failure)
    }
}
