package com.wae.agent.core.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * App 安裝／移除／更新時，讓「已安裝 App 目錄」立即失效。
 *
 * 為什麼需要：`InstalledAppCatalog` 有 5 分鐘快取；使用者剛裝完 App 就說「打開它」時，
 * 目錄可能還是舊的。收到 PACKAGE_ADDED / REMOVED / REPLACED 就清快取，
 * 下一次解析就會看到新名單（別名也會重新衍生）。
 */
class AppCatalogWatcher : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED -> {
                val pkg = intent.data?.schemeSpecificPart.orEmpty()
                InstalledAppCatalog.invalidate()
                Log.i("WAE_CATALOG", "package changed: $pkg (${intent.action}); catalog invalidated")
            }
        }
    }
}
