package com.wae.core.model

import java.security.MessageDigest

/**
 * 畫面指紋（I5：可離線重放的必要條件）。
 *
 * 三條紀律：
 *  1. 參與 hash 的集合**先排序**（`HashMap`/`HashSet` 迭代序不穩定）。
 *  2. 只用整數與既有字串，不格式化浮點（`Locale` 會影響結果）。
 *  3. 不使用 `String.hashCode`（碰撞機率對指紋太高），用 SHA-1 截前 16 碼。
 */
object FrameFingerprint {

    private const val HEX_RADIX = 16
    private const val HEX_OFFSET = 0x100
    private const val PREFIX = "fp:"

    /**
     * 這個標籤是不是「會自己變動」的內容（時間、數字、電量百分比）。
     * 這類文字對「畫面是否改變」沒有意義，卻會讓指紋每秒都不同。
     */
    private fun looksVolatile(label: String): Boolean {
        if (label.isEmpty()) return false
        val digits = label.count { it.isDigit() }
        val others = label.count { !it.isDigit() && !it.isWhitespace() && it != ':' && it != '.' && it != '%' && it != '-' && it != '+' }
        // 幾乎只有數字與時間符號 → 視為會變動
        return digits >= 2 && others <= 1
    }

    fun of(pkg: String, appVersion: Long, controls: List<Control>): String {
        // 會變動的文字（時間、秒數、電量…）不能進指紋：時鐘 頁的秒數每秒都變，
        // 指紋就會每秒不同，於是「畫面有沒有變」的判定永遠是「變了」（第 19 題踩到）。
        val canonical = controls
            .filterNot { looksVolatile(it.label) }
            .map { c ->
                buildString {
                    append(c.key).append('|')
                    append(c.kind.name).append('|')
                    append(c.textHash).append('|')
                    append(c.bounds.left).append(',').append(c.bounds.top).append(',')
                    append(c.bounds.right).append(',').append(c.bounds.bottom).append('|')
                    append(if (c.enabled) '1' else '0')
                }
            }
            .sorted()

        val body = StringBuilder()
        body.append(pkg).append('#').append(appVersion).append('\n')
        for (line in canonical) body.append(line).append('\n')
        return PREFIX + sha1Hex(body.toString())
    }

    private fun sha1Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit((v shr 4) and 0xF, HEX_RADIX))
            sb.append(Character.forDigit(v and 0xF, HEX_RADIX))
        }
        return sb.toString()
    }
}
