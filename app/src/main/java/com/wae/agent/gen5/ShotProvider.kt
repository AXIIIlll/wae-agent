package com.wae.agent.gen5

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 把「我們自己拍的截圖」以 `content://` 交給別的 App（D11 分享照片）。
 *
 * 為什麼自己寫：這個專案沒有 androidx.core，`FileProvider` 用不了；
 * 而這個 provider 只做一件唯讀的事——把 `cache/shots/<name>` 開成檔案描述子。
 * 不列目錄、不寫入、不查詢，權限只給拿到 URI 的那個 App。
 */
class ShotProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        // 副檔名決定 mime：截圖是 png，診斷包是 txt（分享時對方才不會誤判）
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return when {
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".json") -> "application/json"
            else -> "image/png"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        if (name.contains("..") || name.contains('/') || name.contains('\\')) {
            throw FileNotFoundException("bad name")
        }
        val ctx = context ?: throw FileNotFoundException("no context")
        val file = File(File(ctx.cacheDir, DIR), name)
        if (!file.exists()) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val DIR = "shots"
        fun authority(pkg: String): String = "$pkg.shots"
    }
}
