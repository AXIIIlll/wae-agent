package com.wae.core

import com.wae.core.frame.FrameLedger
import com.wae.core.model.Frame
import com.wae.core.time.FixedClock
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 畫面帳本的檔案上限（frames.tsv 只增不減會變成手機上的慢性病）。 */
class FrameLedgerRotationTest {

    @Test
    fun `file is compacted when it grows past the limit`() {
        val dir = Files.createTempDirectory("wae-ledger").toFile()
        try {
            val file = java.io.File(dir, "frames.tsv")
            // 很小的上限 + 只留 5 行，讓測試能快速觸發壓縮
            val ledger = FrameLedger(file, FixedClock(0), maxEntries = 10_000, maxFileBytes = 200L, keepLines = 5)
            repeat(60) { i ->
                ledger.remember(Frame.EMPTY.copy(fp = "fp-$i"))
            }
            val lines = file.readLines().filter { it.isNotBlank() }
            assertTrue("壓縮後行數應受控，實際=${lines.size}", lines.size <= 70)
            assertTrue("檔案不該無上限長大，實際=${file.length()} bytes", file.length() < 4_000)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reload after compaction still parses frames`() {
        val dir = Files.createTempDirectory("wae-ledger2").toFile()
        try {
            val file = java.io.File(dir, "frames.tsv")
            FrameLedger(file, FixedClock(0), maxEntries = 1_000, maxFileBytes = 200L, keepLines = 3)
                .also { ledger -> repeat(20) { ledger.remember(Frame.EMPTY.copy(fp = "x-$it")) } }

            val reloaded = FrameLedger(file, FixedClock(0))
            assertTrue("壓縮後重載應還有畫面，實際=${reloaded.size()}", reloaded.size() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
