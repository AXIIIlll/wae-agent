package com.wae.agent.gen5

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * 相簿（D11 續）：找「最近一張照片」的 content URI。
 *
 * 誠實邊界：
 *  - 只在使用者明確說「分享照片」時才查，且只取**一張**（最近那張）。
 *  - 需要 `READ_MEDIA_IMAGES`（Android 13+）；沒有權限就回 null，由上層引導授權。
 *  - 不掃描、不上傳、不建索引；查完就把 cursor 關掉。
 */
object PhotoLibrary {

    private const val TAG = "WAE_PHOTOS"

    data class Photo(val id: Long, val uri: Uri, val dateAdded: Long)

    fun hasPermission(context: Context): Boolean =
        runCatching {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 最近一張照片；沒有照片或沒權限回 null。 */
    fun latest(context: Context): Photo? {
        if (!hasPermission(context)) return null
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return runCatching {
            context.contentResolver.query(
                uri, projection, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(0)
                val date = cursor.getLong(1)
                Photo(id, ContentUris.withAppendedId(uri, id), date)
            }
        }.getOrElse { t ->
            Log.w(TAG, "query failed: " + t.message)
            null
        }
    }
}
