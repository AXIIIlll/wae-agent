package com.wae.core

import com.wae.core.config.BundledData
import com.wae.core.rule.LexiconFamilyMap
import com.wae.core.rule.RoleLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兩層角色詞庫（統一 + 家族）。
 *
 * 這裡驗的是「資料有沒有接對」：統一詞庫要有通用角色、家族詞庫要有領域角色、
 * 分類對照要覆蓋 Play 的分類名稱、合併後反查要正確。
 */
class RoleLexiconTest {

    private val common: RoleLexicon by lazy {
        RoleLexicon.parse(BundledData.lexiconCommonLines())
    }

    @Test
    fun `common lexicon covers the universal roles`() {
        val roles = common.roles()
        assertTrue("統一詞庫太小：$roles", roles.size >= 10)
        for (expected in listOf("SEARCH", "SUBMIT", "CANCEL", "BACK", "SHARE", "SETTINGS")) {
            assertTrue("缺通用角色 $expected", expected in roles)
        }
        assertEquals("BACK", common.roleOf("返回"))
        assertEquals("SHARE", common.roleOf("分享"))
    }

    @Test
    fun `shopping family adds cart and buy roles`() {
        val shopping = RoleLexicon.parse(BundledData.lexiconFamilyLines("SHOPPING"))
        assertTrue("購物家族要有 CART", "CART" in shopping.roles())
        val merged = RoleLexicon.merge(common, shopping)
        assertEquals("CART", merged.roleOf("加入購物車"))
        assertEquals("CART", merged.roleOf("購物車"))
        assertEquals("BACK", merged.roleOf("返回"))
    }

    @Test
    fun `media family adds playback roles`() {
        val media = RoleLexicon.parse(BundledData.lexiconFamilyLines("MEDIA"))
        assertTrue("影音家族要有 PLAY", "PLAY" in media.roles())
        assertEquals("PLAY", RoleLexicon.merge(common, media).roleOf("播放"))
    }

    @Test
    fun `unknown label returns null instead of guessing`() {
        assertEquals(null, common.roleOf("完全不相干的字串"))
        assertEquals(null, common.roleOf(""))
    }

    @Test
    fun `family map covers play categories and falls back to common`() {
        val map = LexiconFamilyMap.parse(BundledData.lexiconFamilyMapLines())
        assertTrue("分類對照是空的", map.families().isNotEmpty())
        assertEquals("SHOPPING", map.familyOf("Shopping"))
        assertEquals("MEDIA", map.familyOf("Video Players & Editors"))
        assertEquals(LexiconFamilyMap.COMMON, map.familyOf("這個分類不存在"))
    }

    @Test
    fun `package category dataset is available`() {
        val first = BundledData.pkgCategoryLines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        assertNotNull("pkg_category.tsv 沒有資料", first)
        val cells = first!!.split('\t')
        assertEquals("每列要有 pkg 與分類", 2, cells.size.coerceAtMost(2))
    }
}
