package com.wae.agent.core.predicate

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * **實體解析：人名 → 聯絡人**。
 *
 * 方法（不猜）：
 *  1. 查通訊錄（display name 完全相符優先，其次包含）
 *  2. 回傳所有候選，由呼叫端決定：
 *     - 0 個 → 反問「我找不到這個人」
 *     - 1 個 → 用它的顯示名稱
 *     - ≥2 個 → 反問「你要傳給哪一個？」
 *
 * 沒有權限時回傳空清單（呼叫端自然走「反問」），不丟例外。
 */
object ContactResolver {

    data class Person(val displayName: String, val lookupKey: String)

    private const val MAX = 20

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** @return 候選人（可能為空；已去重） */
    fun find(context: Context, name: String): List<Person> {
        if (name.isBlank() || !hasPermission(context)) return emptyList()
        val out = LinkedHashMap<String, Person>()

        // ① 完全相符
        query(context, name, exact = true, out)
        // ② 包含（「媽」→「媽媽」）
        if (out.isEmpty()) query(context, name, exact = false, out)

        return out.values.take(MAX)
    }

    private fun query(
        context: Context,
        name: String,
        exact: Boolean,
        out: MutableMap<String, Person>
    ) {
        val selection = if (exact) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        } else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        }
        val args = arrayOf(if (exact) name else "%$name%")
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, args, null
            )?.use { c ->
                val iName = c.getColumnIndex(projection[0])
                val iKey = c.getColumnIndex(projection[1])
                while (c.moveToNext() && out.size < MAX) {
                    val dn = c.getString(iName) ?: continue
                    val key = c.getString(iKey) ?: continue
                    out.putIfAbsent(key, Person(dn, key))
                }
            }
        }
    }
}
