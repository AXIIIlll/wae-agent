package com.wae.agent.core.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * App 安裝狀態解析。
 *
 * 用途：
 *  1. 真實使用：使用者指定沒安裝的 App → 立刻回報「未安裝，無法開啟」
 *  2. 評測集：任務假設的 App 不存在 → 標記 skip，不計入成功率分母
 */
object AppInstallResolver {

    data class Check(val packages: List<String>, val missing: List<String>) {
        fun allMissing(): Boolean = packages.isNotEmpty() && missing.size == packages.size
    }

    fun isInstalled(context: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun check(context: Context, packages: List<String>): Check {
        val unique = packages.filter { it.isNotEmpty() }.distinct()
        val missing = unique.filter { !isInstalled(context, it) }
        return Check(unique, missing)
    }

    fun displayNames(context: Context, packages: List<String>): List<String> = packages.map { pkg ->
        runCatching {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
    }
}
