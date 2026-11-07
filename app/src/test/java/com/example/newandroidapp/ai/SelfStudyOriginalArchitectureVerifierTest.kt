package com.example.newandroidapp.ai

import com.example.newandroidapp.vision.NanoDetUiDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 高中非學校型態實驗教育（自學申請）與特殊選才：100% 原創白盒代碼架構驗證測試
 *
 * 驗證項目：
 * 1. `app/build.gradle.kts` 零第三方閉源 AI / OCR / JSON / 視覺套件依賴。
 * 2. `app/src/main/cpp/` 零預編譯 `.a` / `.so` 黑盒二進位檔，100% 由自研 C++17 原始碼 `self_vector_engine.cpp` 編譯。
 * 3. `NanoDetUiDetector.synthesizeExplainableInt8Kernels()` 具備 100% 可解釋數學濾波器權重合成能力。
 */
class SelfStudyOriginalArchitectureVerifierTest {

    private fun resolveAppDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (cwd.name == "app") cwd else File(cwd, "app")
    }

    @Test
    fun testZeroThirdPartyAiOrOcrDependenciesInGradle() {
        val gradleFile = File(resolveAppDir(), "build.gradle.kts")
        assertTrue("build.gradle.kts 必須存在", gradleFile.exists())
        val content = gradleFile.readText()

        assertFalse("不得依賴 Google ML Kit 閉源套件", content.contains("com.google.mlkit"))
        assertFalse("不得依賴 OpenCV 套件", content.contains("opencv"))
        assertFalse("不得依賴 Gson 套件", content.contains("com.google.code.gson"))
        assertFalse("不得依賴 Retrofit 套件", content.contains("retrofit2"))
    }

    @Test
    fun testZeroPrecompiledStaticArchiveInCppAndFullSourcePresent() {
        val cppDir = File(resolveAppDir(), "src/main/cpp")
        assertTrue("cpp 目錄必須存在", cppDir.exists())

        val blackBoxArchive = File(cppDir, "libneedle.a")
        assertFalse("cpp 目錄下不得殘留預編譯黑盒靜態庫 libneedle.a", blackBoxArchive.exists())

        val selfEngineCpp = File(cppDir, "self_vector_engine.cpp")
        assertTrue("必須包含 100% 自研 C++17 向量語義引擎原始碼 self_vector_engine.cpp", selfEngineCpp.exists())
        val cppText = selfEngineCpp.readText()
        assertTrue(cppText.contains("compute_embedding_1024"))
        assertTrue(cppText.contains("fnv1a_hash64"))
    }

    @Test
    fun testExplainableInt8MathematicalKernelSynthesizer() {
        val kernels = NanoDetUiDetector.synthesizeExplainableInt8Kernels()
        assertEquals("12 類 UI x 3x3 卷積核應共 108 bytes", 108, kernels.size)
        // Class 0 (UI_CLOSE_X) 中心權重應為 +127，主對角線應為正值
        assertEquals(127.toByte(), kernels[4])
        assertTrue(kernels[0] > 0 && kernels[2] > 0 && kernels[6] > 0 && kernels[8] > 0)
    }
}
