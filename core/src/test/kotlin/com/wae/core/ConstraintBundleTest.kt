package com.wae.core

import com.wae.core.memory.ConstraintBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 約束可攜格式（G9）：**被改過一定驗不過**。
 *
 * 這幾條用單元測試比用 adb 可靠——在裝置上很難穩定製造「檔案被改一個位元」的情境。
 */
class ConstraintBundleTest {

    private val row1 = "global\tfp1\tNAVIGATE:app:日曆\tintent:MAIN\tBAN\tLOCATION\t2\t1\t0"
    private val row2 = "global\tfp2\tNAVIGATE:app:設定\tintent:MAIN\tBAN\tLOCATION\t2\t2\t0"

    @Test
    fun `checksum is stable for the same rows`() {
        val a = ConstraintBundle.checksum(ConstraintBundle.bodyOf(listOf(row1, row2)))
        val b = ConstraintBundle.checksum(ConstraintBundle.bodyOf(listOf(row1, row2)))
        assertEquals(a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun `changing a row breaks verification`() {
        val claimed = ConstraintBundle.checksum(ConstraintBundle.bodyOf(listOf(row1)))
        assertTrue(ConstraintBundle.verify(listOf(row1), claimed))
        // 把 BAN 改成 LOWER：內容變了，checksum 不該再過
        val tampered = row1.replace("\tBAN\t", "\tLOWER\t")
        assertFalse(ConstraintBundle.verify(listOf(tampered), claimed))
    }

    @Test
    fun `adding a row breaks verification`() {
        val claimed = ConstraintBundle.checksum(ConstraintBundle.bodyOf(listOf(row1)))
        assertFalse(ConstraintBundle.verify(listOf(row1, row2), claimed))
    }

    @Test
    fun `parses rows and checksum from file lines`() {
        val lines = listOf(
            "# WAE 約束匯出 v1",
            "# checksum=deadbeefdeadbeef",
            row1,
            "",
            row2
        )
        assertEquals(listOf(row1, row2), ConstraintBundle.rowsOf(lines))
        assertEquals("deadbeefdeadbeef", ConstraintBundle.claimedChecksumOf(lines))
        assertNull(ConstraintBundle.claimedChecksumOf(listOf(row1)))
    }

    @Test
    fun `drops half written rows`() {
        assertEquals(listOf(row1), ConstraintBundle.validRows(listOf(row1, "global\tfp2")))
    }
}
