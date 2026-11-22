package com.wae.agent.gen5

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.wae.core.rule.CapabilityProbe

/**
 * 裝置能力探測（把「這支手機能不能做」變成可查詢的事實）。
 *
 * 為什麼重要：大模型不知道手機上有什麼，會推論出「點下載」——
 * 但如果這個 App 沒有下載功能、或沒有權限，這條路根本不存在。
 * 有了探測，規則在「選方法」時就能先排除不可能的路。
 */
class DeviceProbe(private val context: Context) : CapabilityProbe {

    private val pm: PackageManager get() = context.packageManager

    override fun intentResolvable(action: String, pkg: String): Boolean {
        if (action.isBlank()) return true
        return runCatching {
            val intent = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
            if (pkg.isNotBlank()) intent.setPackage(pkg)
            pm.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(true)
    }

    override fun hasPermission(permission: String): Boolean {
        if (permission.isBlank()) return true
        return runCatching {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
    }

    override fun hasFeature(feature: String): Boolean = runCatching {
        pm.hasSystemFeature(feature)
    }.getOrDefault(true)

    override fun canOpenPanel(): Boolean = runCatching {
        com.wae.agent.core.WaeVisionAccessibilityService.activeInstance != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }.getOrDefault(false)
}
