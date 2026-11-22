package com.wae.agent.voice

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wae.agent.R

/**
 * 快捷設定磚（S36）：下拉通知欄點一下就召喚語音助理。
 * 不需要任何額外權限，也不用開 App —— 是助理角色之外最穩的召喚入口。
 */
class WaeAssistTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = "WAE"
        tile.state = Tile.STATE_INACTIVE
        runCatching {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：用 PendingIntent 版本，避免在鎖定/背景被拒
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
