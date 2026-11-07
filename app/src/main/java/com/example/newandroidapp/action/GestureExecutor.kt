package com.example.newandroidapp.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.newandroidapp.stability.SystemBarBlockerGuard

object GestureExecutor {

    const val DEFAULT_TAP_DURATION_MS: Long = 38L
    const val DEFAULT_DOUBLE_TAP_INTERVAL_MS: Long = 100L
    const val DEFAULT_LONG_PRESS_DURATION_MS: Long = 600L
    const val DEFAULT_SWIPE_DURATION_MS: Long = 200L
    const val DEFAULT_PICKER_FLICK_DURATION_MS: Long = 140L
    const val DEFAULT_SLIDER_DRAG_DURATION_MS: Long = 240L

    interface NodeTextActionTarget {
        fun getText(): CharSequence?
        fun getHintText(): CharSequence?
        fun performSetText(text: String): Boolean
        fun performSetSelection(start: Int, end: Int): Boolean
        fun performPaste(): Boolean
    }

    class AccessibilityNodeWrapper(val node: AccessibilityNodeInfo) : NodeTextActionTarget {
        override fun getText(): CharSequence? = node.text
        override fun getHintText(): CharSequence? = node.hintText

        override fun performSetText(text: String): Boolean {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        override fun performSetSelection(start: Int, end: Int): Boolean {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }

        override fun performPaste(): Boolean {
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    fun calculateSliderTargetCoordinate(
        bounds: Rect,
        percentage: Float,
        isHorizontal: Boolean = (bounds.right - bounds.left) >= (bounds.bottom - bounds.top)
    ): Point {
        val width = (bounds.right - bounds.left).coerceAtLeast(1)
        val height = (bounds.bottom - bounds.top).coerceAtLeast(1)
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val p = percentage.coerceIn(0.0f, 1.0f)
        return if (isHorizontal) {
            val thumbPadding = (height * 0.35f).coerceIn(12f, 28f)
            val usableWidth = (width - 2 * thumbPadding).coerceAtLeast(1f)
            val tx = (bounds.left + thumbPadding + p * usableWidth).toInt()
                .coerceIn(bounds.left, bounds.right)
            Point(tx, cy).apply { this.x = tx; this.y = cy }
        } else {
            val thumbPadding = (width * 0.35f).coerceIn(12f, 28f)
            val usableHeight = (height - 2 * thumbPadding).coerceAtLeast(1f)
            val ty = (bounds.bottom - thumbPadding - p * usableHeight).toInt()
                .coerceIn(bounds.top, bounds.bottom)
            Point(cx, ty).apply { this.x = cx; this.y = ty }
        }
    }

    fun calculatePickerFlickCoordinates(
        bounds: Rect,
        increment: Boolean
    ): Pair<Point, Point> {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val height = (bounds.bottom - bounds.top).coerceAtLeast(1)
        val stepDistance = (height * 0.30f).coerceIn(16f, 120f)
        return if (increment) {
            val startY = (cy + stepDistance * 0.5f).toInt()
            val endY = (cy - stepDistance * 0.5f).toInt()
            Pair(
                Point(cx, startY).apply { this.x = cx; this.y = startY },
                Point(cx, endY).apply { this.x = cx; this.y = endY }
            )
        } else {
            val startY = (cy - stepDistance * 0.5f).toInt()
            val endY = (cy + stepDistance * 0.5f).toInt()
            Pair(
                Point(cx, startY).apply { this.x = cx; this.y = startY },
                Point(cx, endY).apply { this.x = cx; this.y = endY }
            )
        }
    }

    fun calculateSwipeEndpoints(
        screenWidth: Int,
        screenHeight: Int,
        isLeft: Boolean,
        customY: Float? = null
    ): Pair<Point, Point> {
        val y = (customY ?: (screenHeight * 0.50f)).toInt().coerceIn(10, screenHeight - 10)
        val x20 = (screenWidth * 0.20f).toInt().coerceIn(0, screenWidth)
        val x80 = (screenWidth * 0.80f).toInt().coerceIn(0, screenWidth)
        return if (isLeft) {
            Pair(
                Point(x80, y).apply { this.x = x80; this.y = y },
                Point(x20, y).apply { this.x = x20; this.y = y }
            )
        } else {
            Pair(
                Point(x20, y).apply { this.x = x20; this.y = y },
                Point(x80, y).apply { this.x = x80; this.y = y }
            )
        }
    }

    fun hasExistingText(text: CharSequence?, hintText: CharSequence?): Boolean {
        val t = text?.toString() ?: return false
        val h = hintText?.toString()
        return t.isNotBlank() && t != h
    }

    fun hasExistingText(node: AccessibilityNodeInfo): Boolean =
        hasExistingText(node.text, node.hintText)

    fun clearExistingText(target: NodeTextActionTarget): Boolean {
        if (!hasExistingText(target.getText(), target.getHintText())) return true
        val oldText = target.getText()?.toString().orEmpty()

        val tier1Success = runCatching {
            target.performSetText("")
        }.getOrDefault(false)

        val tier2Success = runCatching {
            target.performSetSelection(0, oldText.length)
        }.getOrDefault(false)

        return tier1Success || tier2Success
    }

    fun clearExistingText(node: AccessibilityNodeInfo): Boolean =
        clearExistingText(AccessibilityNodeWrapper(node))

    /**
     * 建立帶有 Android 敏感無痕標記 (`android.content.extra.IS_SENSITIVE`) 的臨時 ClipData，
     * 當極少數元件不支援 ACTION_SET_TEXT 而必須退回 ACTION_PASTE 時，
     * 防止系統彈出底部「已複製」提示或被輸入法（Gboard / Samsung 鍵盤）寫入永久剪貼簿歷史紀錄。
     */
    fun buildSilentTransientClipData(text: String): ClipData {
        val clip = ClipData.newPlainText("AI_TRANSIENT_INPUT", text)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clip.description.extras = extras
            }
        }
        return clip
    }

    private fun snapshotUserPrimaryClip(cm: ClipboardManager): ClipData? {
        return runCatching {
            if (!cm.hasPrimaryClip()) return null
            val orig = cm.primaryClip ?: return null
            if (orig.itemCount <= 0) return null
            val copied = ClipData(orig.description, orig.getItemAt(0))
            for (i in 1 until orig.itemCount) {
                copied.addItem(orig.getItemAt(i))
            }
            copied
        }.getOrNull()
    }

    private fun restoreUserPrimaryClip(cm: ClipboardManager, savedClip: ClipData?) {
        runCatching {
            if (savedClip != null) {
                cm.setPrimaryClip(savedClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            }
        }
    }

    fun autoClearAndSetText(
        target: NodeTextActionTarget,
        newText: String,
        onPrepareTransientClipboardForPaste: (() -> Unit)? = null,
        onRestoreClipboardAfterPaste: (() -> Unit)? = null
    ): Boolean {
        clearExistingText(target)

        // 第一優先：直接透過無障礙 Bundle (ACTION_SET_TEXT) 整段瞬間灌入文字，100% 不動用系統剪貼簿！
        var success = runCatching {
            target.performSetText(newText)
        }.getOrDefault(false)

        // 第二保底：僅在目標元件不支援 ACTION_SET_TEXT 時，才瞬時無痕借用剪貼簿執行 ACTION_PASTE 並立即還原
        if (!success) {
            onPrepareTransientClipboardForPaste?.invoke()
            try {
                success = runCatching {
                    target.performPaste()
                }.getOrDefault(false)
            } finally {
                onRestoreClipboardAfterPaste?.invoke()
            }
        }
        return success
    }

    fun autoClearAndSetText(
        node: AccessibilityNodeInfo,
        newText: String,
        context: Context? = null
    ): Boolean {
        runCatching { node.refresh() }
        var cm: ClipboardManager? = null
        var savedUserClip: ClipData? = null
        var clipboardTouched = false

        val success = autoClearAndSetText(
            target = AccessibilityNodeWrapper(node),
            newText = newText,
            onPrepareTransientClipboardForPaste = {
                if (context != null) {
                    runCatching {
                        cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val mgr = cm
                        if (mgr != null) {
                            savedUserClip = snapshotUserPrimaryClip(mgr)
                            mgr.setPrimaryClip(buildSilentTransientClipData(newText))
                            clipboardTouched = true
                        }
                    }
                }
            },
            onRestoreClipboardAfterPaste = {
                val mgr = cm
                if (clipboardTouched && mgr != null) {
                    restoreUserPrimaryClip(mgr, savedUserClip)
                }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return success
    }

    fun buildTapGesture(x: Float, y: Float, durationMs: Long = DEFAULT_TAP_DURATION_MS): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun buildLongPressGesture(x: Float, y: Float, durationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun buildDoubleTapGesture(
        x: Float,
        y: Float,
        intervalMs: Long = DEFAULT_DOUBLE_TAP_INTERVAL_MS,
        tapDurationMs: Long = DEFAULT_TAP_DURATION_MS
    ): GestureDescription {
        val path1 = Path().apply { moveTo(x, y) }
        val path2 = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, tapDurationMs)
        val stroke2 = GestureDescription.StrokeDescription(path2, tapDurationMs + intervalMs, tapDurationMs)
        return GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
    }

    fun buildSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun dispatchGestureSafely(
        service: AccessibilityService,
        gesture: GestureDescription,
        onDone: (() -> Unit)? = null
    ) {
        val dispatched = runCatching {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onDone?.invoke()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onDone?.invoke()
                    }
                },
                null
            )
        }.getOrDefault(false)

        if (!dispatched) {
            onDone?.invoke()
        }
    }

    fun executeTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        onDone: (() -> Unit)? = null
    ) {
        val (sw, sh) = SystemBarBlockerGuard.getRealScreenSizePx(service, 1080, 2400)
        val (safePt, _) = SystemBarBlockerGuard.clampPointToSafeAppZone(service, x, y, sw, sh)
        val gesture = buildTapGesture(safePt.x.toFloat(), safePt.y.toFloat())
        dispatchGestureSafely(service, gesture, onDone)
    }

    fun executeLongPress(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS,
        onDone: (() -> Unit)? = null
    ) {
        val (sw, sh) = SystemBarBlockerGuard.getRealScreenSizePx(service, 1080, 2400)
        val (safePt, _) = SystemBarBlockerGuard.clampPointToSafeAppZone(service, x, y, sw, sh)
        val gesture = buildLongPressGesture(safePt.x.toFloat(), safePt.y.toFloat(), durationMs)
        dispatchGestureSafely(service, gesture, onDone)
    }

    fun executeDoubleTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        intervalMs: Long = DEFAULT_DOUBLE_TAP_INTERVAL_MS,
        onDone: (() -> Unit)? = null
    ) {
        val (sw, sh) = SystemBarBlockerGuard.getRealScreenSizePx(service, 1080, 2400)
        val (safePt, _) = SystemBarBlockerGuard.clampPointToSafeAppZone(service, x, y, sw, sh)
        val gesture = buildDoubleTapGesture(safePt.x.toFloat(), safePt.y.toFloat(), intervalMs, DEFAULT_TAP_DURATION_MS)
        dispatchGestureSafely(service, gesture, onDone)
    }

    fun executeSwipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
        onDone: (() -> Unit)? = null
    ) {
        val (sw, sh) = SystemBarBlockerGuard.getRealScreenSizePx(service, 1080, 2400)
        val (p1, _) = SystemBarBlockerGuard.clampPointToSafeAppZone(service, startX, startY, sw, sh)
        val (p2, _) = SystemBarBlockerGuard.clampPointToSafeAppZone(service, endX, endY, sw, sh)
        val gesture = buildSwipeGesture(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), durationMs)
        dispatchGestureSafely(service, gesture, onDone)
    }

    fun executeSwipeLeft(
        service: AccessibilityService,
        y: Float? = null,
        onDone: (() -> Unit)? = null
    ) {
        val (sw, sh) = SystemBarBlockerGuard.getRealScreenSizePx(service, 1080, 2400)
        val (p1, p2) = calculateSwipeEndpoints(sw, sh, isLeft = true, customY = y)
        executeSwipe(
            service = service,
            startX = p1.x.toFloat(),
            startY = p1.y.toFloat(),
            endX = p2.x.toFloat(),
            endY = p2.y.toFloat(),
            durationMs = DEFAULT_SWIPE_DURATION_MS,
            onDone = onDone
        )
    }

    fun executeSwipeRight(
        service: AccessibilityService,
        y: Float? = null,
        onDone: (() -> Unit)? = null
    ) {
        val (sw, sh) = SystemBarBlockerGuard.getRealScreenSizePx(service, 1080, 2400)
        val (p1, p2) = calculateSwipeEndpoints(sw, sh, isLeft = false, customY = y)
        executeSwipe(
            service = service,
            startX = p1.x.toFloat(),
            startY = p1.y.toFloat(),
            endX = p2.x.toFloat(),
            endY = p2.y.toFloat(),
            durationMs = DEFAULT_SWIPE_DURATION_MS,
            onDone = onDone
        )
    }

    fun executeSliderSet(
        service: AccessibilityService,
        bounds: Rect,
        percentage: Float,
        isVertical: Boolean = false,
        onDone: (() -> Unit)? = null
    ) {
        val targetPoint = calculateSliderTargetCoordinate(bounds, percentage, !isVertical)
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        val distance = Math.hypot(
            (targetPoint.x - cx).toDouble(),
            (targetPoint.y - cy).toDouble()
        )

        if (distance > 10.0) {
            executeSwipe(
                service = service,
                startX = cx.toFloat(),
                startY = cy.toFloat(),
                endX = targetPoint.x.toFloat(),
                endY = targetPoint.y.toFloat(),
                durationMs = DEFAULT_SLIDER_DRAG_DURATION_MS,
                onDone = onDone
            )
        } else {
            executeTap(
                service = service,
                x = targetPoint.x.toFloat(),
                y = targetPoint.y.toFloat(),
                onDone = onDone
            )
        }
    }

    fun executePickerNudge(
        service: AccessibilityService,
        bounds: Rect,
        increment: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        val (start, end) = calculatePickerFlickCoordinates(bounds, increment)
        executeSwipe(
            service = service,
            startX = start.x.toFloat(),
            startY = start.y.toFloat(),
            endX = end.x.toFloat(),
            endY = end.y.toFloat(),
            durationMs = DEFAULT_PICKER_FLICK_DURATION_MS,
            onDone = onDone
        )
    }

    fun executeClearInputAndType(
        service: AccessibilityService,
        node: AccessibilityNodeInfo?,
        targetX: Float,
        targetY: Float,
        text: String,
        onDone: (() -> Unit)? = null
    ) {
        if (node != null) {
            autoClearAndSetText(node, text, service)
            onDone?.invoke()
        } else {
            executeTap(service, targetX, targetY) {
                onDone?.invoke()
            }
        }
    }
}
