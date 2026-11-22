package com.wae.agent.llm

/**
 * llama.cpp JNI 橋接（M1 實作 native）。
 * 未載入原生庫時 available() 回 false，所有呼叫都會安全失敗。
 */
object LlamaBridge {

    @Volatile
    private var loaded = false

    @Volatile
    private var handle: Long = 0L

    private external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeLoadFd(fd: Int, offset: Long, nCtx: Int, nThreads: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeComplete(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): String

    fun available(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("wae_llama")
            loaded = true
            true
        }.getOrDefault(false)
    }

    fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        if (!available()) return false
        return runCatching {
            val h = nativeLoad(modelPath, nCtx, nThreads)
            handle = h
            h != 0L
        }.getOrDefault(false)
    }

    /**
     * 零拷貝載入：直接從 APK 內的 gguf（AssetFileDescriptor）載入，不再複製到 filesDir。
     * @return 成功與否
     */
    fun loadFromFd(fd: Int, offset: Long, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        if (!available()) return false
        return runCatching {
            val h = nativeLoadFd(fd, offset, nCtx, nThreads)
            if (h != 0L) handle = h
            h != 0L
        }.getOrDefault(false)
    }

    fun complete(prompt: String, maxTokens: Int = 256, temperature: Float = 0.2f, topP: Float = 0.9f): String? {
        if (!loaded || handle == 0L) return null
        return runCatching { nativeComplete(handle, prompt, maxTokens, temperature, topP) }.getOrNull()
    }

    fun unload() {
        runCatching { if (loaded && handle != 0L) nativeFree(handle) }
        handle = 0L
    }
}
