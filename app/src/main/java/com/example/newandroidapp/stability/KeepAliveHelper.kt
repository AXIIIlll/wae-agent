package com.example.newandroidapp.stability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.newandroidapp.MainActivity

/**
 * 【第 8 項】廠商省電防殺保活助手 (KeepAliveHelper)
 *
 * 1. 以「真正的前景服務 (startForeground, specialUse)」提升無障礙服務的行程優先級（不只是貼一則通知）。
 *    若系統拒絕，會退回一般通知並把失敗原因記錄下來，成績單會如實顯示。
 * 2. 提供電池最佳化白名單狀態檢查與一鍵申請。
 */
object KeepAliveHelper {

    private const val CHANNEL_ID = "ai_vision_keep_alive_channel"
    private const val NOTIFICATION_ID = 8801

    @Volatile
    var isRealForegroundActive: Boolean = false
        private set

    @Volatile
    var lastForegroundError: String = ""
        private set

    fun statusLine(): String {
        return if (isRealForegroundActive) {
            "已進入前景服務"
        } else {
            "未進入前景服務${if (lastForegroundError.isNotBlank()) "（原因：$lastForegroundError）" else "（尚未啟動）"}"
        }
    }

    fun startForegroundKeepAlive(service: Service, statusText: String = "實時視覺流與雙本地 AI 穩定待命中") {
        val nm = runCatching { service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }.getOrNull() ?: return
        val notification = runCatching { buildNotification(service, nm, statusText) }.getOrNull() ?: return

        // 先嘗試帶 specialUse 型別的真正前景服務（Android 14+ 必須宣告型別）
        var ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.onFailure { lastForegroundError = it.javaClass.simpleName + ": " + (it.message ?: "").take(80) }
            .getOrDefault(false)

        // 退回無型別版本（舊系統）
        if (!ok) {
            ok = runCatching {
                service.startForeground(NOTIFICATION_ID, notification)
                true
            }.onFailure { lastForegroundError = it.javaClass.simpleName + ": " + (it.message ?: "").take(80) }
                .getOrDefault(false)
        }

        isRealForegroundActive = ok
        if (ok) {
            lastForegroundError = ""
        } else {
            // 最後退路：至少顯示一般通知
            runCatching { nm.notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun buildNotification(service: Service, nm: NotificationManager, statusText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "AI 實時視覺保活服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "確保跨 App 實時視覺與自動巡航不被系統省電機制中斷"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            service, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(service, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(service)
        }

        return builder
            .setContentTitle("AI 助手 — 本機無障礙輔助服務（保活中）")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        // Google Play 合規：不使用受限制的 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接彈窗權限，
        // 而是引導使用者至系統「電池最佳化設定清單」或「應用程式資訊頁」手動調整電池設定。
        runCatching {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }
}
