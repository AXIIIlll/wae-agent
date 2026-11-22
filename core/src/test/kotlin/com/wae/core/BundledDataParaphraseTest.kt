package com.wae.core

import com.wae.core.config.BundledData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出貨資料表必須「有內容」：`linesOrEmpty` 是選配載入，漏打包時只會靜默變空。
 *
 * verifier 在 t5 指出：`purposeParaphrases` 這張新表沒有非空測試，
 * 未來如果 processResources 漏掉它，44 條別名會無聲消失（docs 從 183 掉回 139）。
 */
class BundledDataParaphraseTest {

    @Test
    fun `purpose paraphrases table ships with content`() {
        val rows = BundledData.purposeParaphraseLines()
            .map { it.trimStart('\uFEFF').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        assertTrue("purpose_paraphrases.tsv 不該是空的（漏打包會靜默失效）", rows.size >= 40)
        for (row in rows) {
            val cols = row.split('\t')
            assertTrue("每列要有 purpose 與 phrase：$row", cols.size >= 2)
            assertTrue("purpose 不可為空：$row", cols[0].isNotBlank())
            assertTrue("phrase 不可為空：$row", cols[1].isNotBlank())
        }
    }
}
