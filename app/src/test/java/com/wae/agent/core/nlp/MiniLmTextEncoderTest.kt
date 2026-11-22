package com.wae.agent.core.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 真實 MiniLM 通道的純 JVM 測試（不需要 ORT / 真機）：
 * 覆蓋 WordPiece 分詞規則、固定長度編碼、mean pooling + L2 正規化、相似度映射。
 */
class MiniLmTextEncoderTest {

    private fun vocabOf(vararg entries: String): Map<String, Int> =
        entries.mapIndexed { i, s -> s to i }.toMap()

    private fun realVocab(): BertWordPieceTokenizer {
        // 用真詞表做端到端分詞檢查（assets 在 JVM 可讀，因為單元測試工作目錄是專案根目錄）
        val f = java.io.File("src/main/assets/tokenizer/vocab.txt")
        assertTrue("vocab.txt 必須存在於 assets", f.exists())
        return BertWordPieceTokenizer.fromVocabularyStream(f.inputStream(), 128)
    }

    @Test
    fun `vocabulary has bert-base-uncased size`() {
        val tk = realVocab()
        assertEquals(101, tk.clsId)
        assertEquals(102, tk.sepId)
        assertEquals(100, tk.unkId)
        assertEquals(0, tk.padId)
    }

    @Test
    fun `encodes to fixed length with cls and sep`() {
        val tk = realVocab()
        val enc = tk.encode("search weather")
        assertEquals(128, enc.inputIds.size)
        assertEquals(128, enc.attentionMask.size)
        assertEquals(101L, enc.inputIds[0])              // [CLS]
        // "search"/"weather" 都在詞表中 → 有效 token = CLS + 2 + SEP = 4
        val valid = enc.attentionMask.count { it == 1L }
        assertEquals(4, valid)
        assertEquals(102L, enc.inputIds[valid - 1])      // 有效區段最後是 [SEP]
        assertEquals(0L, enc.attentionMask[127])         // 其餘為 PAD
        assertEquals(0L, enc.inputIds[127])
    }

    @Test
    fun `splits word into wordpieces with double hash prefix`() {
        // 用合成詞表驗證貪婪最長匹配 + ## 續接（真實詞表裡 "unbelievable" 剛好是單一 token）
        val vocab = vocabOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "un", "##believ", "##able")
        val tk = BertWordPieceTokenizer.fromVocabularyMap(vocab, 16)
        val ids = tk.tokenize("unbelievable")
        assertEquals(listOf(4, 5, 6), ids)
    }

    @Test
    fun `chinese characters are split per char`() {
        val tk = realVocab()
        // bert-base-uncased 詞表裡含少量漢字（例如「天」=1811），因此只能斷言「逐字切分」，
        // 不能假設一定變成 [UNK]
        val ids = tk.tokenize("天氣")
        assertEquals(2, ids.size)
        assertEquals(1811, ids[0])                        // 「天」在詞表內
        assertEquals(tk.unkId, ids[1])                     // 「氣」不在詞表內
    }

    @Test
    fun `punctuation is split out`() {
        val vocab = vocabOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", ",", "!")
        val tk = BertWordPieceTokenizer.fromVocabularyMap(vocab, 16)
        val ids = tk.tokenize("hello, world!")
        assertEquals(listOf(4, 6, 5, 7), ids)
    }

    @Test
    fun `unknown token falls back to unk id`() {
        val vocab = vocabOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello")
        val tk = BertWordPieceTokenizer.fromVocabularyMap(vocab, 8)
        val ids = tk.tokenize("hello zzzzz")
        assertEquals(listOf(4, 1), ids)
    }

    @Test
    fun `mean pooling ignores padding and normalizes to unit length`() {
        val hidden = arrayOf(
            floatArrayOf(1f, 0f, 0f),   // token 0
            floatArrayOf(0f, 2f, 0f),   // token 1
            floatArrayOf(9f, 9f, 9f)    // padding（mask=0，必須被忽略）
        )
        val mask = longArrayOf(1, 1, 0)
        val vec = MiniLmPostProcess.meanPoolAndNormalize(hidden, mask)
        // 先平均得 (0.5, 1.0, 0) → 再 L2 正規化 → (0.4472, 0.8944, 0)
        assertEquals(0.4472136f, vec[0], 1e-5f)
        assertEquals(0.8944272f, vec[1], 1e-5f)
        assertEquals(0f, vec[2], 1e-5f)
        val norm = kotlin.math.sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("L2 norm 應為 1，實際=$norm", abs(norm - 1f) < 1e-4f)
    }

    @Test
    fun `cosine and similarity mapping are in range`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(1f, 0f)
        val c = floatArrayOf(0f, 1f)
        assertEquals(1f, MiniLmPostProcess.cosine(a, b), 1e-5f)
        assertEquals(0f, MiniLmPostProcess.cosine(a, c), 1e-5f)
        assertEquals(1f, MiniLmPostProcess.toSimilarity01(1f), 1e-5f)
        assertEquals(0f, MiniLmPostProcess.toSimilarity01(-0.5f), 1e-5f)
        val mid = MiniLmPostProcess.toSimilarity01(0.5f)
        assertTrue(mid in 0.5f..0.6f)
    }
}
