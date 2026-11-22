package com.wae.core

import com.wae.core.match.Resolution
import com.wae.core.match.Similarity
import com.wae.core.match.TargetResolver
import com.wae.core.match.Via
import com.wae.core.model.ControlKind
import com.wae.core.model.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目標解析階梯（§4.7）的測試。
 *
 * 這裡刻意**不新增任何動詞或句型**：只驗證「新說法也能被解析」的推導能力。
 */
class TargetResolverTest {

    private val bluetooth = TestFrames.toggleControl(key = "sw1", text = "藍牙")
    private val wifi = TestFrames.toggleControl(key = "sw2", text = "Wi-Fi")
    private val controls = listOf(bluetooth, wifi)

    @Test
    fun `exact match wins`() {
        val r = TargetResolver().resolve("藍牙", controls, ControlKind.TOGGLE)
        assertEquals(Via.EXACT, (r as Resolution.Found).via)
        assertEquals("sw1", r.control.key)
    }

    @Test
    fun `alias resolves a different wording without new parser rules`() {
        val resolver = TargetResolver(aliases = mapOf("藍芽" to "藍牙"))
        val r = resolver.resolve("藍芽", controls, ControlKind.TOGGLE)
        assertEquals(Via.ALIAS, (r as Resolution.Found).via)
        assertEquals("sw1", r.control.key)
    }

    @Test
    fun `similarity resolves unseen wording`() {
        // 假的語意模型：只要兩邊有共同字元就給分。真機上換成 MiniLM 嵌入。
        val fake = Similarity { a, b ->
            if (a.isEmpty() || b.isEmpty()) 0f
            else if (a.any { it in b }) 0.9f else 0f
        }
        val resolver = TargetResolver(similarity = fake)
        val r = resolver.resolve("無線藍牙", controls, ControlKind.TOGGLE)
        assertEquals(Via.SIMILARITY, (r as Resolution.Found).via)
        assertEquals("sw1", r.control.key)
    }

    @Test
    fun `close scores are treated as ambiguous instead of guessing`() {
        val fake = Similarity { _, _ -> 0.8f }
        val resolver = TargetResolver(similarity = fake)
        val r = resolver.resolve("開關", controls, ControlKind.TOGGLE)
        assertTrue(r is Resolution.Ambiguous)
        assertEquals(2, (r as Resolution.Ambiguous).candidates.size)
    }

    @Test
    fun `missing theme returns missing`() {
        val r = TargetResolver().resolve("飛航模式", controls, ControlKind.TOGGLE)
        assertTrue(r is Resolution.Missing)
    }

    @Test
    fun `structure prior only applies to a generic reference`() {
        val onlyOne = listOf(TestFrames.toggleControl(key = "only", text = "Wi-Fi"))
        val generic = TargetResolver().resolve("這個", onlyOne, ControlKind.TOGGLE)
        assertEquals(Via.STRUCTURE, (generic as Resolution.Found).via)

        // 有明確名稱時，結構先驗必須讓位給解析（否則就是猜）
        val named = TargetResolver().resolve("藍牙", onlyOne, ControlKind.TOGGLE)
        assertTrue(named is Resolution.Missing)
    }

    @Test
    fun `resolver ignores controls without labels`() {
        val unlabeled = TestFrames.toggleControl(key = "x", text = "").copy(label = "")
        val frame = Frame(
            fp = "fp:x",
            pkg = TestFrames.PKG,
            appVersion = 1L,
            controls = listOf(unlabeled)
        )
        val r = TargetResolver().resolve("藍牙", frame.controls, ControlKind.TOGGLE)
        assertTrue(r is Resolution.Missing)
    }
}
