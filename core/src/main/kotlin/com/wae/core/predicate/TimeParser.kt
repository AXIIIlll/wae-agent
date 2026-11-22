package com.wae.core.predicate

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * **時間解析**：把「明天九點」「下午三點半」「9:30」變成絕對時間。
 *
 * 方法（純計算，無權限、無模型）：
 *  1. **日期詞**（今天/明天/後天/昨天）→ 相對今天加減天數
 *  2. **時段詞**（上午/下午/晚上/中午/凌晨）→ 決定 12/24 小時制偏移
 *  3. **數字**（中文 `一二三…十二` 或阿拉伯）→ 小時
 *  4. **「半」** → +30 分；`H:MM` 直接採用
 *  5. 沒有日期詞且時間已過 → 順延到明天（口語習慣）
 *
 * 時區一律用 `ZoneId.systemDefault()`，不做 UTC 假設。
 */
object TimeParser {

    private val DAY_WORDS = mapOf(
        "今天" to 0, "今晚" to 0, "今日" to 0,
        "明天" to 1, "明早" to 1, "明日" to 1, "明晚" to 1,
        "後天" to 2,
        "昨天" to -1, "昨晚" to -1
    )

    /** 時段 → (起始小時, 是否 12 小時制)。 */
    private val PERIODS = listOf(
        "凌晨" to 0, "早上" to 6, "上午" to 9, "中午" to 12,
        "下午" to 12, "傍晚" to 18, "晚上" to 18, "今晚" to 18
    )

    private val CN_DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    /**
     * @return epoch millis；解析不出來回 null（不要猜）
     */
    fun parse(text: String, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (text.isBlank()) return null

        // 1) 日期
        var date: LocalDate = now.toLocalDate()
        var dayMatched = false
        for ((w, delta) in DAY_WORDS) {
            if (text.contains(w)) {
                date = date.plusDays(delta.toLong())
                dayMatched = true
                break
            }
        }

        // 2) 時段
        var periodStart: Int? = null
        for ((w, h) in PERIODS) {
            if (text.contains(w)) {
                periodStart = h
                break
            }
        }

        // 3) H:MM
        val hm = Regex("([0-9]{1,2}):([0-9]{2})").find(text)
        if (hm != null) {
            val h = hm.groupValues[1].toIntOrNull() ?: return null
            val m = hm.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return toMillis(date, h, m, zone)
        }

        // 4) 數字 + 時
        val hour = findHour(text) ?: periodStart?.let { 0 } ?: return null
        if (hour == null) return null

        var h = hour
        if (periodStart != null && periodStart >= 12 && hour < 12) h = hour + 12
        if (periodStart == null && text.contains("下午") && hour < 12) h += 12
        if (h !in 0..23) return null

        val minute = if (text.contains("半")) 30 else 0

        // 5) 沒指定日期且時間已過 → 明天
        var dt = LocalDateTime.of(date, LocalTime.of(h, minute))
        if (!dayMatched && !dt.isAfter(now)) dt = dt.plusDays(1)
        return dt.atZone(zone).toInstant().toEpochMilli()
    }

    /** 從文字抓小時：中文數字或阿拉伯數字（後接「點」優先）。 */
    private fun findHour(text: String): Int? {
        // 阿拉伯：9點 / 9 點
        Regex("([0-9]{1,2})\\s*[點时時]").find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        // 中文：九點 / 十一點
        val idx = text.indexOfFirst { it == '點' || it == '时' || it == '時' }
        if (idx > 0) {
            val sb = StringBuilder()
            var i = idx - 1
            while (i >= 0 && CN_DIGITS.containsKey(text[i])) {
                sb.insert(0, text[i]); i -= 1
            }
            if (sb.isNotEmpty()) return cnToInt(sb.toString())
        }
        return null
    }

    /** 中文數字轉整數（支援 一…十二、二十）。 */
    fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.length == 1) return CN_DIGITS[s[0]]
        // 十 / 十X / X十 / X十Y
        val tenIdx = s.indexOf('十')
        if (tenIdx >= 0) {
            val tens = if (tenIdx == 0) 1 else CN_DIGITS[s[tenIdx - 1]] ?: return null
            val units = if (tenIdx == s.length - 1) 0 else CN_DIGITS[s[tenIdx + 1]] ?: return null
            return tens * 10 + units
        }
        return null
    }

    private fun toMillis(date: LocalDate, h: Int, m: Int, zone: ZoneId): Long =
        LocalDateTime.of(date, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
}
