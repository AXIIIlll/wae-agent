package com.wae.agent.core.predicate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/**
 * **畫面外狀態查詢**（缺口 2 的實作）。
 *
 * 為什麼需要：`eval(Φ, frame)` 只看得到當前畫面。
 * 「開 WiFi → 回首頁 → 再問它 WiFi 開了嗎」，畫面裡沒有開關就答不出來。
 * 這裡把系統狀態接成述詞可以查的 key。
 *
 * 目前支援（全部是 Android 公開 API，不需要額外權限）：
 *  - `wifi`        ：目前是否有可用的 Wi-Fi 連線
 *  - `mobile_data` ：是否有行動數據連線
 *  - `internet`    ：是否有任何可上網的連線
 *  - `airplane`    ：飛航模式是否開啟
 *
 * 刻意**不假裝**能查到的（回 null，代表「不知道」而不是 false）：
 *  - 藍牙開關（需要 BLUETOOTH_CONNECT 權限）
 *  - 熱點開關（沒有公開 API）
 * 這兩者只能靠畫面驗證——誠實比假裝重要。
 */
object AndroidObservables {

    fun lookup(context: Context): (String) -> Boolean? = { key ->
        when (key.lowercase()) {
            "wifi" -> hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
            "mobile_data", "cellular" -> hasTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)
            "internet" -> hasInternet(context)
            "airplane" -> airplaneOn(context)
            else -> null
        }
    }

    private fun hasTransport(context: Context, transport: Int): Boolean? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(transport)
    }.getOrNull()

    private fun hasInternet(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrNull()

    private fun airplaneOn(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrNull()
}
