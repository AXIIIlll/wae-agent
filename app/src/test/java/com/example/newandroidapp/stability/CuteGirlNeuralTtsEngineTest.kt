package com.example.newandroidapp.stability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CuteGirlNeuralTtsEngineTest {

    private val forbiddenParticles = listOf("啦", "囉", "唷", "喔", "咧", "嘛", "耶", "齁")

    @Test
    fun `test blunt colloquial persona keeps replies concise around 10 chars under 20 chars with zero modal particles`() {
        val rewritten1 = CuteGirlNeuralTtsEngine.rewriteWithCuteGirlPersona("開始執行：去Spotify搜尋struct")
        assertTrue("正常回應應在 20 字以內: $rewritten1", rewritten1.length <= 20)
        assertTrue(rewritten1.startsWith("知道了"))
        assertFalse(forbiddenParticles.any { rewritten1.contains(it) })

        val rewritten2 = CuteGirlNeuralTtsEngine.rewriteWithCuteGirlPersona("已成功為您打開「YouTube」！")
        assertTrue("正常回應應在 20 字以內: $rewritten2", rewritten2.length <= 20)
        assertTrue(rewritten2.contains("YouTube"))
        assertFalse(forbiddenParticles.any { rewritten2.contains(it) })

        val rewritten3 = CuteGirlNeuralTtsEngine.rewriteWithCuteGirlPersona("語音播報已開啟")
        assertTrue("正常回應應在 20 字以內: $rewritten3", rewritten3.length <= 20)
        assertFalse(forbiddenParticles.any { rewritten3.contains(it) })

        // 驗證所有錯誤與系統訊息皆消除專業術語、零語助詞且 <= 20 字
        val systemJargons = listOf(
            "連續點擊候選元件後畫面均無反應，已安全停止避免誤觸",
            "已達單次任務安全步數上限 (18步)",
            "找不到對應的已安裝 App",
            "無有效點擊座標",
            "偵測到誤跳外部程式，自動拉回目標程式"
        )
        for (raw in systemJargons) {
            val spoken = CuteGirlNeuralTtsEngine.rewriteWithCuteGirlPersona(raw)
            assertTrue("任何系統回應不得超過 20 字: $spoken (${spoken.length})", spoken.length <= 20)
            assertFalse("不得殘留專業術語: $spoken", spoken.contains("候選元件") || spoken.contains("步數上限") || spoken.contains("座標"))
            assertFalse("不得含有裝可愛語助詞: $spoken", forbiddenParticles.any { spoken.contains(it) })
        }
    }

    @Test
    fun `test blunt sassy complaint and correction responses have zero modal particles and are under 20 chars`() {
        val reply1 = HumanColloquialPersonaEngine.detectComplaintOrEmotionalChat("不是這個意思")
        assertNotNull(reply1)
        assertEquals("不是，誰知道你是這個意思。", reply1!!.spokenAndDisplayText)
        assertTrue(reply1.spokenAndDisplayText.length <= 20)
        assertFalse(forbiddenParticles.any { reply1.spokenAndDisplayText.contains(it) })
        assertEquals("返回上一頁", reply1.suggestedRemedialCommand)

        val reply2 = HumanColloquialPersonaEngine.detectComplaintOrEmotionalChat("你點錯了")
        assertNotNull(reply2)
        assertTrue(reply2!!.spokenAndDisplayText.length <= 20)
        assertFalse(forbiddenParticles.any { reply2.spokenAndDisplayText.contains(it) })
        assertEquals("返回上一頁", reply2.suggestedRemedialCommand)

        val reply3 = HumanColloquialPersonaEngine.detectComplaintOrEmotionalChat("你真的很笨")
        assertNotNull(reply3)
        assertTrue(reply3!!.spokenAndDisplayText.length <= 20)
        assertTrue(reply3.spokenAndDisplayText.contains("說誰笨"))
        assertFalse(forbiddenParticles.any { reply3.spokenAndDisplayText.contains(it) })
    }

    @Test
    fun `test bilingual prosody planner assigns distinct intonation roles and cadence pauses for Chinese and English spans`() {
        val phrases = CuteGirlNeuralTtsEngine.buildProsodicPlan(
            rawText = "知道了，幫你去 Spotify 搜尋 struct 這首歌？",
            applyPersonaRewrite = false
        )
        assertTrue("應切分為多個具備不同抑揚頓挫角色的韻律片語", phrases.size >= 4)

        val leadIn = phrases.first()
        assertEquals("知道了", leadIn.text)
        assertEquals(CuteGirlNeuralTtsEngine.ProsodyRole.LEAD_IN_BRIGHT, leadIn.role)
        assertTrue(leadIn.basePitchRatio >= 1.22f)
        assertTrue(leadIn.pauseAfterMs >= 100L)

        val englishSpans = phrases.filter { it.role == CuteGirlNeuralTtsEngine.ProsodyRole.BILINGUAL_FOCUS_STRESS }
        assertTrue(englishSpans.any { it.text.equals("Spotify", ignoreCase = true) })
        assertTrue(englishSpans.any { it.text.equals("struct", ignoreCase = true) })
        for (eng in englishSpans) {
            assertTrue("英文關鍵詞應微幅放慢語速以確保發音清晰", eng.speechRateRatio <= 0.98f)
            assertTrue("英文關鍵詞應具備重音能量增益", eng.energyGain >= 1.15f)
        }

        val tail = phrases.last()
        assertEquals(CuteGirlNeuralTtsEngine.ProsodyRole.CUTE_RISING_ENDING, tail.role)
        assertTrue("句尾必須具備正半音上揚滑音 (Tail Glide)", tail.tailGlideSemitones >= 2.5f)
    }

    @Test
    fun `test formant vocoder transforms PCM waveform with f0 intonation contour and soft clipping`() {
        val sampleRate = 22050
        val numSamples = 2205
        val rawPcm = ShortArray(numSamples) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 14000.0).toInt().toShort()
        }

        val phrase = CuteGirlNeuralTtsEngine.buildProsodicPlan("知道了，幫你找。", applyPersonaRewrite = false).last()
        val transformed = CuteGirlNeuralTtsEngine.transformPcmWithCuteGirlVocoder(rawPcm, sampleRate, phrase)

        assertEquals(numSamples, transformed.size)
        assertTrue(transformed.any { it.toInt() != 0 })
        var diffCount = 0
        for (i in rawPcm.indices) {
            if (rawPcm[i] != transformed[i]) diffCount++
        }
        assertTrue("聲碼器必須實際重塑波形共振峰與基頻包絡", diffCount > numSamples / 2)
        assertFalse("柔性限幅器必須防止任何樣本溢位破音", transformed.any { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
    }
}
