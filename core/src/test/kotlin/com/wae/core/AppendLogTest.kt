package com.wae.core

import com.wae.core.memory.AppendLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 只增不減的日誌要有上限（frames.tsv 實測長到 12.8 MB 才被發現）。 */
class AppendLogTest {

    @Test
    fun `compacts when file exceeds the byte cap`() {
        val dir = Files.createTempDirectory("wae-append").toFile()
        try {
            val file = java.io.File(dir, "receipts.tsv")
            repeat(200) { i ->
                AppendLog.append(file, "row-$i\t" + "x".repeat(50), maxBytes = 900L, keepLines = 5)
            }
            val lines = file.readLines().filter { it.isNotBlank() }
            assertTrue("行數應受控，實際=${lines.size}", lines.size <= 25)
            assertTrue("檔案大小應受控，實際=${file.length()}", file.length() < 2_000)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keeps the newest rows after compaction`() {
        val dir = Files.createTempDirectory("wae-append2").toFile()
        try {
            val file = java.io.File(dir, "t.tsv")
            repeat(50) { i -> AppendLog.append(file, "row-$i", maxBytes = 200L, keepLines = 3) }
            assertTrue("最新的資料要留下", file.readText().contains("row-49"))
            assertTrue("檔案不該超過上限太多，實際=${file.length()}", file.length() <= 200 + 40)
            // 壓縮後會再長回一批，所以驗「有上限」而不是「剛好 3 行」
            assertTrue("行數應受控，實際=${file.readLines().count { it.isNotBlank() }}",
                file.readLines().count { it.isNotBlank() } <= 30)
        } finally {
            dir.deleteRecursively()
        }
    }
}
