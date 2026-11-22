package com.wae.agent.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before

/**
 * G：使用者偏好（喜歡／不喜歡）要真的改變 App 選擇，而且不能影響其他類別。
 */
class AppChoiceMemoTest {

    private val videoApps = listOf(
        AppSelector.AppCandidate("Netflix", "com.netflix.mediaclient", "VIDEO_MEDIA", listOf("netflix")),
        AppSelector.AppCandidate("YouTube", "com.google.android.youtube", "VIDEO_MEDIA", listOf("yt")),
        AppSelector.AppCandidate("Disney+", "com.disney.disneyplus", "VIDEO_MEDIA", listOf("disney"))
    )

    @Before
    fun setUp() {
        AppChoiceMemo.resetForTest()
        AppIntentCategory.resetForTest()
        AppIntentCategory.loadFromLines(
            listOf("看劇\tVIDEO_MEDIA\t1.0\t測試").asSequence()
        )
    }

    @Test
    fun `bias changes ranking so dislike is avoided`() {
        val before = AppSelector.select("看劇", videoApps)
        val disliked = before.best!!.candidate.label

        val after = AppSelector.select("看劇", videoApps) { _, label ->
            if (label == disliked) -2.0f else 0f
        }
        assertTrue("被標記不喜歡的 App 不該還是第一，實際=${after.best!!.candidate.label}",
            after.best!!.candidate.label != disliked)
    }

    @Test
    fun `bias prefers liked app`() {
        val after = AppSelector.select("看劇", videoApps) { _, label ->
            if (label == "Disney+") 3.0f else 0f
        }
        assertEquals("Disney+", after.best!!.candidate.label)
    }

    @Test
    fun `bias for another category does not leak`() {
        val base = AppSelector.select("看劇", videoApps)
        val biased = AppSelector.select("看劇", videoApps) { cat, label ->
            if (cat == "MUSIC_MEDIA" && label == "YouTube") 5f else 0f
        }
        assertEquals(base.best!!.candidate.label, biased.best!!.candidate.label)
        assertEquals(base.best!!.score, biased.best!!.score, 0.001f)
    }

    @Test
    fun `last pick is remembered for feedback`() {
        AppChoiceMemo.recordPick("VIDEO_MEDIA", "Netflix")
        assertEquals("Netflix", AppChoiceMemo.lastPickLabel)
        assertEquals("VIDEO_MEDIA", AppChoiceMemo.lastPickCategory)
    }
}
