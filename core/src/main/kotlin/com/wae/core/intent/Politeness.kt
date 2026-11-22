package com.wae.core.intent

/**
 * 禮貌詞與句尾語助詞（口語清理）。
 *
 * 使用者不會像指令手冊那樣講話：會加「幫我」「請」「可以…嗎」「謝謝」。
 * 這些詞對「要做什麼」沒有資訊，但會讓句型比對失敗。
 *
 * 原則：**只剝掉開頭與結尾的禮貌層**，中間的內容一個字都不動
 * （避免「幫我把檔案移動到資料夾」這種句子被吃掉語意）。
 */
object Politeness {

    private val PREFIXES = listOf(
        "請幫我", "可以幫我", "能不能幫我", "麻煩幫我", "幫我",
        "請你", "麻煩你", "請", "麻煩",
        "可以", "能不能", "可否",
        "我要", "我想", "我需要"
    ).sortedByDescending { it.length }

    private val SUFFIXES = listOf(
        "可以嗎", "好嗎", "行嗎", "嗎", "呢", "吧", "謝謝", "拜託"
    ).sortedByDescending { it.length }

    fun strip(text: String): String {
        var out = text.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in PREFIXES) {
                if (out.startsWith(prefix)) {
                    out = out.removePrefix(prefix).trimStart('，', ',', '、', ' ')
                    changed = true
                }
            }
            for (suffix in SUFFIXES) {
                if (out.endsWith(suffix)) {
                    out = out.removeSuffix(suffix).trimEnd('。', '！', '!', '，', ',', ' ')
                    changed = true
                }
            }
        }
        return out.ifEmpty { text.trim() }
    }
}
