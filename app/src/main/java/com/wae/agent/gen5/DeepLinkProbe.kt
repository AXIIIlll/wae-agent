package com.wae.agent.gen5

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 深鏈探測（B15）：**問系統**「這個連結誰能開」，不拆別人的程式（綠區允許）。
 *
 * 用途：同一個結果如果目標 App 有宣告 deep link，就可以跳過 3–5 步的畫面導覽。
 * 沒有宣告就回空清單，退回一般路徑。
 */
object DeepLinkProbe {

    fun handlersOf(context: Context, uri: String): List<String> {
        if (uri.isBlank()) return emptyList()
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    fun canOpen(context: Context, uri: String): Boolean = handlersOf(context, uri).isNotEmpty()
}
