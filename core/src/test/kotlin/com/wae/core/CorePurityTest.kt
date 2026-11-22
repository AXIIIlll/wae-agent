package com.wae.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I5 守門：`core` 必須是純 JVM（不得 import `android.*` / `androidx.*`）。
 *
 * 這是「核心可離線重放」的機械保證——沒有它，任何人順手加一行
 * `import android.content.Context` 就會讓 745 個 JVM 測試失去意義。
 */
class CorePurityTest {

    @Test
    fun `core sources do not import android`() {
        val root = File("src/main/kotlin")
        assertTrue("找不到 core 原始碼目錄：${root.absolutePath}", root.isDirectory)

        val forbidden = listOf("import android.", "import androidx.", "import com.wae.agent.")
        val violations = ArrayList<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    for (prefix in forbidden) {
                        if (trimmed.startsWith(prefix)) {
                            violations.add("${file.name}:${index + 1}  $trimmed")
                        }
                    }
                }
            }

        assertTrue(
            "core 不得依賴 Android（I5）：\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
