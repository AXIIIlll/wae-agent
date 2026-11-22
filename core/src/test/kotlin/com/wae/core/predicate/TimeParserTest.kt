package com.wae.core.predicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

/** 時間解析：相對日期 + 中文數字 + 時段。 */
class TimeParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Taipei")
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 22, 10, 0) // 2026-11-22 10:00

    private fun at(text: String): LocalDateTime? =
        TimeParser.parse(text, now, zone)?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime()
        }

    @Test
    fun `tomorrow nine`() {
        assertEquals(LocalDateTime.of(2026, 11, 23, 9, 0), at("明天九點"))
    }

    @Test
    fun `afternoon three`() {
        assertEquals(LocalDateTime.of(2026, 11, 22, 15, 0), at("下午三點"))
    }

    @Test
    fun `half past`() {
        assertEquals(LocalDateTime.of(2026, 11, 23, 9, 30), at("明天九點半"))
    }

    @Test
    fun `explicit clock`() {
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 30), at("9:30"))
    }

    @Test
    fun `time already passed rolls to tomorrow`() {
        // 現在 10:00，說「九點」→ 明天九點
        assertEquals(LocalDateTime.of(2026, 11, 23, 9, 0), at("九點"))
    }

    @Test
    fun `chinese numerals`() {
        assertEquals(11, TimeParser.cnToInt("十一"))
        assertEquals(20, TimeParser.cnToInt("二十"))
        assertEquals(9, TimeParser.cnToInt("九"))
    }

    @Test
    fun `unparsable returns null`() {
        assertNull(at("幫我訂位"))
        assertNull(at("   "))
    }
}
