package com.wae.agent.core.capability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 安裝 / 卸載 / 更新監聽：只做「標記為 dirty」，不在廣播裡做任何掃描，
 * 避免拖慢系統廣播與耗電。真正的重掃由 AppCapabilityIndex.ensureFresh 在背景完成。
 *
 * 注意：PACKAGE_ADDED / REMOVED / REPLACED 屬於隱式廣播豁免清單，manifest 註冊可收到。
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val pkg = intent.data?.schemeSpecificPart.orEmpty()
        when (action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED -> {
                AppCapabilityIndex.markDirty(context, "$action:$pkg")
            }
        }
    }
}
