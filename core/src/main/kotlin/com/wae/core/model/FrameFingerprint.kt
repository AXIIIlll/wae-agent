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

    fun of(pkg: String, appVersion: Long, controls: List<Control>): String {
        val canonical = controls
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
