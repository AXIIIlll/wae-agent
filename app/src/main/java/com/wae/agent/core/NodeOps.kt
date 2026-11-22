package com.wae.agent.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 舊路徑的節點操作（保留這個名字給既有呼叫點）。
 *
 * 真正的實作在 `com.wae.agent.gen5.DeviceIO`——全 App 只有那裡碰裝置。
 * 這一層只是轉呼叫，不要再往裡面加邏輯。
 */
object NodeOps {

    fun perform(node: AccessibilityNodeInfo, action: Int, arguments: Bundle? = null): Boolean =
        com.wae.agent.gen5.DeviceIO.perform(node, action, arguments)

    fun tap(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean =
        com.wae.agent.gen5.DeviceIO.tap(service, node)

    fun gesture(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 60L): Boolean =
        com.wae.agent.gen5.DeviceIO.gestureAt(service, x, y, durationMs)

    fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback? = null
    ): Boolean = com.wae.agent.gen5.DeviceIO.dispatch(service, gesture, callback)
}
