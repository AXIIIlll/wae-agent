package com.wae.agent.core.app

import com.wae.agent.agent.ObservedElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 通用角色解析測試：$role:xxx 要能在「只有文字與旗標」的元素清單上找到正確元素。
 * 這是不依賴任何 App、也不依賴截圖的關鍵一層。
 */
class RoleResolverTest {

    private fun asset(name: String): File = listOf(
        File("../app/src/main/assets/open_source_ai_databases/$name"),
        File("app/src/main/assets/open_source_ai_databases/$name"),
        File("E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/$name")
    ).first { it.exists() }

    @Before
    fun setUp() {
        RoleResolver.resetForTest()
        RoleResolver.loadFromLines(asset("ui_role_keywords.tsv").readLines(Charsets.UTF_8).asSequence())
    }

    private fun el(index: Int, text: String, clickable: Boolean = true, editable: Boolean = false) =
        ObservedElement(index = index, text = text, clickable = clickable, editable = editable)

    @Test
    fun `table parses`() {
        assertTrue("至少要有 10 個角色，實際=${RoleResolver.roleCount()}", RoleResolver.roleCount() >= 10)
        assertTrue(RoleResolver.ruleFor("play") != null)
    }

    @Test
    fun `input role finds editable element`() {
        val obs = listOf(
            el(0, "搜尋", clickable = true),
            el(1, "", clickable = true, editable = true)
        )
        assertEquals(1, RoleResolver.resolve("input", obs))
    }

    @Test
    fun `play role finds play button`() {
        val obs = listOf(el(0, "首頁"), el(1, "播放"), el(2, "我的"))
        assertEquals(1, RoleResolver.resolve("play", obs))
    }

    @Test
    fun `cart role finds chinese button`() {
        val obs = listOf(el(0, "收藏"), el(1, "加入購物車"), el(2, "立即購買"))
        assertEquals(1, RoleResolver.resolve("cart", obs))
    }

    @Test
    fun `buy role finds buy button`() {
        val obs = listOf(el(0, "購物車"), el(1, "立即購買"))
        assertEquals(1, RoleResolver.resolve("buy", obs))
    }

    @Test
    fun `content roles have no keywords and need fallback`() {
        val obs = listOf(el(0, "搜尋"), el(1, "某個很長的推薦內容標題"))
        assertNull("first_result 不應該靠關鍵詞硬找", RoleResolver.resolve("first_result", obs))
        assertTrue(RoleResolver.isContentFallback("first_result"))
    }

    @Test
    fun `unknown role returns null`() {
        assertNull(RoleResolver.resolve("不存在", listOf(el(0, "播放"))))
    }

    @Test
    fun `clickable preferred over plain text`() {
        val obs = listOf(
            el(0, "播放清單", clickable = false),
            el(1, "播放", clickable = true)
        )
        assertEquals(1, RoleResolver.resolve("play", obs))
    }
}
