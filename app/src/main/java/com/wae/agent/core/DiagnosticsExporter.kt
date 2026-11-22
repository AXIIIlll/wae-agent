package com.wae.agent.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 診斷包匯出（P1 #11）：把除錯需要的資訊打包成 zip，方便使用者回報。
 *
 * 內容：build/device 資訊、啟動器版本、runtime flags、崩潰紀錄、語音歷史、
 *      Task OS / Ω 檔案清單、logcat 尾巴。**只在使用者主動按下時產生**。
 * 位置：`/sdcard/Android/data/com.wae.agent/files/diagnostics/wae-diag-*.zip`
 */
object DiagnosticsExporter {

    fun export(context: Context): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "diagnostics")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(dir, "wae-diag-$stamp.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            put(zos, "build.txt", buildInfo(context))
            put(zos, "runtime_flags.txt", flagsInfo(context))
            copyFile(zos, context, "crash-last.txt", File(context.filesDir, "crash-last.txt"))
            copyFile(zos, context, "voice_session_history.json",
                File(context.filesDir, "voice_session_history.json"))
            copyFile(zos, context, "decision_memo.json", File(context.filesDir, "decision_memo.json"))
            put(zos, "files_list.txt", filesList(context))
            put(zos, "logcat_tail.txt", logcatTail())
        }
        zip
    }.getOrNull()

    private fun put(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun copyFile(zos: ZipOutputStream, context: Context, name: String, f: File) {
        if (!f.isFile) return
        runCatching {
            zos.putNextEntry(ZipEntry(name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun buildInfo(context: Context): String = buildString {
        append("package=").append(context.packageName).append('\n')
        append("versionName=").append(
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("?")
        ).append('\n')
        append("versionCode=").append(
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }.getOrDefault(0)
        ).append('\n')
        append("android=").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("pageSize=").append(runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(-1))
            .append('\n')
        append("abi=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
    }

    private fun flagsInfo(context: Context): String = buildString {
        append("TASK_OS=").append(com.wae.agent.core.runtime.ExtremeRuntimeHooks.TASK_OS_ENABLED).append('\n')
        append("WAE_OMEGA=").append(com.wae.agent.core.runtime.ExtremeRuntimeHooks.WAE_OMEGA_ENABLED).append('\n')
        append("GROUNDING_2=").append(com.wae.agent.core.runtime.ExtremeRuntimeHooks.GROUNDING_2_ENABLED).append('\n')
        append("accessibility_active=")
            .append(com.wae.agent.core.WaeVisionAccessibilityService.activeInstance != null).append('\n')
        append("assistant_role=").append(
            runCatching {
                val rm = context.getSystemService(android.app.role.RoleManager::class.java)
                rm?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
            }.getOrDefault(false)
        ).append('\n')
    }

    private fun filesList(context: Context): String = buildString {
        context.filesDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val size = if (f.isFile) f.length() else -1L
            append(f.name).append('\t').append(size).append('\n')
        }
    }

    private fun logcatTail(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "800").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
    }.getOrDefault("logcat unavailable")
}
