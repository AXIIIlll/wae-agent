package com.wae.core.util

import java.security.MessageDigest
import java.util.Locale

/**
 * 文字正規化與雜湊。
 *
 * 為什麼不用 `String.lowercase()`：土耳其語系會把 `I` 變成 `ı`；
 * 一律 `Locale.ROOT`，否則同一段字在不同裝置得到不同 hash（指紋與契約都會歪）。
 *
 * 為什麼不存原文：記憶體只存 hash，符合「不存使用者內容」的離線承諾。
 */
object TextHash {

    private const val WHITESPACE = "\\s+"
    private const val HEX_RADIX = 16

    fun normalize(text: String): String =
        text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex(WHITESPACE), " ")

    fun of(text: String): String = sha1Hex(normalize(text))

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
