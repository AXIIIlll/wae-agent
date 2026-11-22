package com.wae.agent.core.omega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WAE-Ω 第 2 層：內容定址模型儲存（ModelStore）。
 */
class ModelStoreTest {

    @Before
    fun setUp() {
        ModelStore.resetForTest()
    }

    private fun sample(pkg: String = "com.example.app", version: Int = 1): AppModel = AppModel(
        pkg = pkg,
        version = version,
        states = listOf(AppState("s1", listOf(1L), setOf(UiOntology.UiType.BUTTON))),
        fields = listOf(FieldSchema("f", UiOntology.UiType.TEXT_FIELD))
    )

    @Test
    fun `save then load returns model with hash`() {
        assertTrue(ModelStore.save(sample()))
        val loaded = ModelStore.load("com.example.app")
        assertNotNull(loaded)
        assertEquals("com.example.app", loaded!!.pkg)
        assertTrue(loaded.hash.isNotEmpty())
        assertTrue(loaded.verifyIntegrity())
        assertEquals(1, ModelStore.count())
    }

    @Test
    fun `save same content is idempotent`() {
        ModelStore.save(sample())
        ModelStore.save(sample())
        assertEquals(1, ModelStore.count())
        assertEquals(1, ModelStore.all().size)
    }

    @Test
    fun `import rejects tampered model`() {
        val valid = sample().withComputedHash().toLineFormat()
        assertNotNull(ModelStore.importModel(valid))
        // 改內容但保留舊 hash → 驗證失敗
        val tampered = valid.replace("version=1", "version=9").replace(
            "com.example.app", "com.evil.app"
        )
        assertNull(ModelStore.importModel(tampered))
    }

    @Test
    fun `export round trip`() {
        ModelStore.save(sample())
        val text = ModelStore.exportModel("com.example.app")
        assertNotNull(text)
        val restored = AppModel.fromLineFormat(text!!)
        assertNotNull(restored)
        assertTrue(restored!!.verifyIntegrity())
    }

    @Test
    fun `clear removes model`() {
        ModelStore.save(sample())
        assertTrue(ModelStore.clear("com.example.app"))
        assertNull(ModelStore.load("com.example.app"))
        assertEquals(0, ModelStore.count())
    }

    @Test
    fun `bad pkg or empty text is rejected`() {
        assertFalse(ModelStore.save(AppModel(pkg = "")))
        assertNull(ModelStore.importModel("garbage"))
        assertNull(ModelStore.load(""))
    }

    @Test
    fun `multiple apps coexist and newest version tracked`() {
        ModelStore.save(sample("com.a"))
        ModelStore.save(sample("com.b"))
        ModelStore.save(sample("com.a", version = 5))
        assertEquals(2, ModelStore.count())
        assertEquals(5, ModelStore.load("com.a")!!.version)
    }
}
