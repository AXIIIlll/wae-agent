package com.wae.core.intent

import com.wae.core.model.Effect
import com.wae.core.model.Op

/**
 * 分享指令（D11）：「分享〈內容〉給〈App〉」。
 *
 * 這是**跨 App 傳遞**的最小形式：不需要知道對方 App 的畫面長什麼樣，
 * 用 Android 的 `ACTION_SEND` 直接把內容丟過去。
 * 後續「分享照片」只是把 mime type 從 text/plain 換成圖片類型。
 */
object ShareCommandParser {

    private const val PREFIX = "share:text:"
    private const val PREFIX_IMAGE = "share:image:"
    private const val PREFIX_PHOTO = "share:photo:"

    /** 分享相簿裡的照片：需要讀取媒體權限，但只讀最近一張。 */
    private val PHOTO_PATTERNS = listOf(
        Regex("^分享(?:最近一張)?(?:照片|相片)給(.+)$"),
        Regex("^把(?:照片|相片)(?:分享|傳|送)給(.+)$"),
        Regex("^傳(?:照片|相片)給(.+)$")
    )

    /** 截圖分享：不需要讀取媒體權限，自己拍的圖自己給。 */
    private val IMAGE_PATTERNS = listOf(
        Regex("^分享(?:螢幕)?截圖給(.+)$"),
        Regex("^把(?:螢幕)?截圖(?:分享|傳|送)給(.+)$"),
        Regex("^傳(?:螢幕)?截圖給(.+)$")
    )

    private val PATTERNS = listOf(
        Regex("^分享(.+?)給(.+)$"),
        Regex("^把(.+?)分享到(.+)$"),
        Regex("^傳送(.+?)到(.+)$")
    )

    data class Parsed(val effect: Effect, val appLabel: String)

    fun parse(text: String): Parsed? {
        val input = text.trim()

        // 相簿照片（需要讀取媒體權限）
        for (pattern in PHOTO_PATTERNS) {
            val m = pattern.find(input) ?: continue
            val app = m.groupValues[1].trim()
            if (app.isEmpty()) continue
            return Parsed(Effect(PREFIX_PHOTO + app, Op.SUBMIT), app)
        }

        // 先試截圖（避免「分享截圖給訊息」被當成「分享『截圖』給『訊息』」的純文字）
        for (pattern in IMAGE_PATTERNS) {
            val m = pattern.find(input) ?: continue
            val app = m.groupValues[1].trim()
            if (app.isEmpty()) continue
            return Parsed(Effect(PREFIX_IMAGE + app, Op.SUBMIT), app)
        }

        for (pattern in PATTERNS) {
            val m = pattern.find(input) ?: continue
            val content = m.groupValues[1].trim()
            val app = m.groupValues[2].trim()
            if (content.isEmpty() || app.isEmpty()) continue
            return Parsed(
                effect = Effect(PREFIX + app, Op.SUBMIT, content),
                appLabel = app
            )
        }
        return null
    }

    fun appLabelOf(effect: Effect): String? = when {
        effect.target.startsWith(PREFIX) -> effect.target.removePrefix(PREFIX)
        effect.target.startsWith(PREFIX_IMAGE) -> effect.target.removePrefix(PREFIX_IMAGE)
        effect.target.startsWith(PREFIX_PHOTO) -> effect.target.removePrefix(PREFIX_PHOTO)
        else -> null
    }

    fun isImageShare(effect: Effect): Boolean =
        effect.target.startsWith(PREFIX_IMAGE) || effect.target.startsWith(PREFIX_PHOTO)

    fun isPhotoShare(effect: Effect): Boolean = effect.target.startsWith(PREFIX_PHOTO)
}
