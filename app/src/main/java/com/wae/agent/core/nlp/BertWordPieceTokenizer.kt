package com.wae.agent.core.nlp

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * BERT uncased WordPiece 分詞器。
 *
 * all-MiniLM-L6-v2 使用的是 bert-base-uncased 的 30,522 詞表，因此必須嚴格對齊
 * HuggingFace `BertTokenizerFast(do_lower_case=true)` 的行為，否則 embedding 會整體偏移：
 *  1. 移除控制字元、空白歸一化
 *  2. 轉小寫（uncased）
 *  3. 中日韓字元逐字切分（CJK 不做整詞匹配）
 *  4. 標點逐字切分
 *  5. 貪婪最長匹配 + "##" 續接前綴
 *  6. [CLS] + tokens + [SEP]，其餘補 [PAD]
 */
class BertWordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    val maxLen: Int
) {

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val PAD = "[PAD]"
        private const val MAX_CHARS_PER_WORD = 100

        fun fromVocabularyStream(stream: InputStream, maxLen: Int = 128): BertWordPieceTokenizer {
            val map = HashMap<String, Int>(32768)
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var idx = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    map[line.trimEnd('\r')] = idx++
                }
            }
            return BertWordPieceTokenizer(map, maxLen)
        }

        fun fromVocabularyMap(vocab: Map<String, Int>, maxLen: Int = 128): BertWordPieceTokenizer {
            return BertWordPieceTokenizer(vocab, maxLen)
        }

        /** 與 HF `_is_chinese_char` 相同的區段判定 */
        fun isCjkChar(ch: Char): Boolean {
            val cp = ch.code
            return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
                (cp in 0x20000..0x2A6DF) || (cp in 0x2A700..0x2B73F) ||
                (cp in 0x2B740..0x2B81F) || (cp in 0x2B820..0x2CEAF) ||
                (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)
        }

        fun isPunctuation(ch: Char): Boolean {
            val cp = ch.code
            if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
            return when (Character.getType(ch)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        private fun isControl(ch: Char): Boolean {
            val cp = ch.code
            if (cp == 0 || cp == 0xFFFD) return true
            return when (Character.getType(ch)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.UNASSIGNED.toInt(),
                Character.PRIVATE_USE.toInt(),
                Character.SURROGATE.toInt() -> true
                else -> false
            }
        }
    }

    val padId: Int = vocab[PAD] ?: 0
    val clsId: Int = vocab[CLS] ?: 101
    val sepId: Int = vocab[SEP] ?: 102
    val unkId: Int = vocab[UNK] ?: 100

    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    ) {
        val length: Int get() = inputIds.size
    }

    /** 固定長度輸出（maxLen），不足補 [PAD]，供 ORT 固定 shape 使用 */
    fun encode(text: String): Encoding {
        val pieceIds = ArrayList<Int>(maxLen)
        for (word in basicTokenize(text)) {
            wordPiece(word, pieceIds)
            if (pieceIds.size >= maxLen - 2) break
        }
        val usable = if (pieceIds.size > maxLen - 2) pieceIds.subList(0, maxLen - 2) else pieceIds

        val ids = LongArray(maxLen) { padId.toLong() }
        val mask = LongArray(maxLen)
        var pos = 0
        ids[pos] = clsId.toLong(); mask[pos] = 1L; pos++
        for (id in usable) { ids[pos] = id.toLong(); mask[pos] = 1L; pos++ }
        ids[pos] = sepId.toLong(); mask[pos] = 1L
        return Encoding(ids, mask, LongArray(maxLen))
    }

    /** 只回傳有效 token 的 id（供測試與 debug） */
    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in basicTokenize(text)) wordPiece(word, out)
        return out
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        for (ch in text) {
            when {
                isControl(ch) -> Unit
                ch.isWhitespace() -> cleaned.append(' ')
                else -> cleaned.append(ch)
            }
        }
        val lowered = cleaned.toString().lowercase()

        val words = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) { words.add(cur.toString()); cur.setLength(0) }
        }
        for (ch in lowered) {
            when {
                ch == ' ' -> flush()
                isCjkChar(ch) -> { flush(); words.add(ch.toString()) }
                isPunctuation(ch) -> { flush(); words.add(ch.toString()) }
                else -> cur.append(ch)
            }
        }
        flush()
        return words
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        if (word.isEmpty()) return
        if (word.length > MAX_CHARS_PER_WORD) { out.add(unkId); return }
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched = -1
            while (start < end) {
                val candidate = if (start > 0) "##" + word.substring(start, end) else word.substring(start, end)
                val id = vocab[candidate]
                if (id != null) { matched = id; break }
                end--
            }
            if (matched < 0) {
                out.add(unkId)   // 與 HF 一致：整字失敗 → 單一 [UNK]
                return
            }
            out.add(matched)
            start = end
        }
    }
}
