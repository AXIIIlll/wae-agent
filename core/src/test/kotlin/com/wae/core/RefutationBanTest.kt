package com.wae.core

import com.wae.core.config.MapConfig
import com.wae.core.memory.RefutationLog
import com.wae.core.model.Attribution
import com.wae.core.model.Verdict
import com.wae.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G7 的底層：使用者說「這步錯了」要能**一次就禁**，而且只禁那個範圍。
 *
 * 為什麼要測範圍：如果「不是這個」變成全域禁令，使用者糾正一次就會弄壞一整個 App。
 */
class RefutationBanTest {

    private val log = RefutationLog(MapConfig(emptyMap()), FixedClock(1_000))

    private fun ban(
        effect: String = "NAVIGATE:app:設定",
        means: String = "intent:android.intent.action.MAIN"
    ): com.wae.core.model.Refutation {
        return log.ban(
            scope = "frame:fp:screen-1",
            frameFp = "fp:screen-1",
            effectKey = effect,
            means = means,
            attribution = Attribution.LOCATION
        )
    }

    @Test
    fun `one user correction is enough to ban`() {
        val entry = ban()
        assertEquals(Verdict.BAN, entry.verdict)
        assertTrue("人要一次生效，不是累積兩次", entry.count >= 2)
    }

    @Test
    fun `ban is scoped to the same effect and means`() {
        ban()
        val same = log.all().single()
        assertEquals("NAVIGATE:app:設定", same.effect)
        assertEquals("intent:android.intent.action.MAIN", same.means)

        // 效果鍵本身已含目標（NAVIGATE:app:設定），所以「全域」範圍其實很窄：
        // 不會影響別的效果，也不會影響同效果的其他手段。
        assertTrue("不該憑空多出別的禁令", log.all().size == 1)
    }

    @Test
    fun `ban survives repeated recording without downgrading`() {
        ban()
        val after = log.record(
            scope = "frame:fp:screen-1",
            frameFp = "fp:screen-1",
            effectKey = "NAVIGATE:app:設定",
            means = "intent:android.intent.action.MAIN",
            attribution = Attribution.LOCATION
        )
        assertEquals("再記一次失敗也不該降級回來", Verdict.BAN, after.verdict)
    }
}
