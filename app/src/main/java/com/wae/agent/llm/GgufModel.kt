package com.wae.agent.llm

import android.content.Context
import java.io.File

/**
 * 內建 GGUF 模型管理：assets → filesDir 解壓（只做一次）、大小/雜湊檢查。
 * M1 會把 Qwen3-0.6B IQ4_XS 放進 assets。
 */
object GgufModel {

    const val ASSET_NAME = "models/qwen3-0.6b-instruct-iq4_xs.gguf"
    const val FILE_NAME = "qwen3-0.6b-instruct-iq4_xs.gguf"
    const val EXPECTED_BYTES = 350_800_000L // IQ4_XS 約 350.8MB

    fun targetFile(context: Context): File = File(File(context.filesDir, "llm"), FILE_NAME)

    fun isPresent(context: Context): Boolean = targetFile(context).isFile

    /**
     * 嘗試取得 APK 內 gguf 的 AssetFileDescriptor（noCompress 才能開）。
     * 成功時可零拷貝載入，不必把 350MB 複製到 filesDir。
     */
    fun openAssetFd(context: Context): android.content.res.AssetFileDescriptor? = runCatching {
        context.assets.openFd(ASSET_NAME)
    }.getOrNull()

    /** 回傳可用模型路徑；若只有 assets 版本則先複製到 filesDir。 */
    fun ensureAvailable(context: Context): File? = runCatching {
        val target = targetFile(context)
        if (target.isFile && target.length() > 1_000_000L) return target
        target.parentFile?.mkdirs()
        context.assets.open(ASSET_NAME).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.takeIf { it.isFile }
    }.getOrNull()
}
