package com.wae.core.memory

import com.wae.core.model.Attribution
import com.wae.core.model.Refutation
import com.wae.core.model.Verdict
import java.security.MessageDigest

/**
 * 約束可攜（C11）：把「這台手機學到的禁令」整包帶走。
 *
 * 三條紀律：
 *  1. **不含使用者內容**：只有 scope／指紋／效果鍵／手段／次數。
 *  2. **有校驗**：整包 SHA-1，改一個字就整包拒收。
 *  3. **可撤銷**：匯入只是「候選」，使用者可以看、可以刪（C10）。
 */
object ConstraintPort {

    private const val HEADER = "# wae-constraints"
    private const val SCHEMA = "5"
    private const val COLUMNS = 9

    fun export(rows: List<Refutation>): String {
        val body = StringBuilder()
        for (r in rows) {
            body.append(r.scope).append('\t')
                .append(r.frameFp).append('\t')
                .append(r.effect).append('\t')
                .append(r.means).append('\t')
                .append(r.verdict.name).append('\t')
                .append(r.attribution.name).append('\t')
                .append(r.count).append('\t')
                .append(r.lastTs).append('\t')
                .append(r.appVersion).append('\n')
        }
        val text = body.toString()
        return "$HEADER schema=$SCHEMA sha1=${sha1(text)}\n$text"
    }

    /** 匯入：格式或校驗不對就回空清單（寧可不用，也不要吃進壞資料）。 */
    fun import(text: String): List<Refutation> {
        val lines = text.lines()
        val header = lines.firstOrNull() ?: return emptyList()
        if (!header.startsWith(HEADER)) return emptyList()

        val body = lines.drop(1).filter { it.isNotBlank() }
        val bodyText = body.joinToString("\n", postfix = if (body.isEmpty()) "" else "\n")
        val expected = header.split(' ').firstOrNull { it.startsWith("sha1=") }?.removePrefix("sha1=")
            ?: return emptyList()
        if (expected != sha1(bodyText)) return emptyList()

        return body.mapNotNull { decode(it) }
    }

    private fun decode(line: String): Refutation? {
        val c = line.split('\t')
        if (c.size < COLUMNS) return null
        val verdict = runCatching { Verdict.valueOf(c[4]) }.getOrNull() ?: return null
        val attribution = runCatching { Attribution.valueOf(c[5]) }.getOrNull() ?: return null
        val count = c[6].toIntOrNull() ?: return null
        return Refutation(
            scope = c[0],
            frameFp = c[1],
            effect = c[2],
            means = c[3],
            verdict = verdict,
            attribution = attribution,
            count = count,
            lastTs = c[7].toLongOrNull() ?: 0L,
            appVersion = c[8].toLongOrNull() ?: 0L
        )
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit((v shr 4) and 0xF, 16))
            sb.append(Character.forDigit(v and 0xF, 16))
        }
        return sb.toString()
    }
}
