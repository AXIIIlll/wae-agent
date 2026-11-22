package com.wae.core.learn

import com.wae.core.model.ControlKind
import com.wae.core.model.Frame

/** 畫面姿態（B17）：先認出「這裡不能硬幹」，再決定要不要停手。 */
enum class Posture {
    /** 一般可操作畫面 */
    NORMAL,

    /** 登入／註冊／密碼：帳號邊界，不得自動填寫或繞過 */
    LOGIN_WALL,

    /** 大量輸入欄位（表單）：容易誤填，要多確認 */
    FORM_HEAVY,

    /** 系統權限對話框：只有人能決定「允許／不允許」 */
    PERMISSION_WALL
}

/**
 * 姿態分類（B17）。
 *
 * 只做**辨識**，不做繞過：遇到登入牆就是 `ABSTAIN + 教學`，
 * 因為「自動登入」是法律與信任的紅線（也是風控地獄的入口）。
 *
 * 判準刻意窄：寧可漏判，也不要亂判。
 */
object PostureClassifier {

    private const val FORM_FIELD_THRESHOLD = 6
    private val PASSWORD_HINTS = listOf("password", "passwd", "密碼")
    private val LOGIN_HINTS = listOf("登入", "登錄", "sign in", "login")

    fun classify(frame: Frame): Posture {
        val fields = frame.controls.filter { it.kind == ControlKind.FIELD }
        val hasPassword = fields.any { c ->
            val hay = (c.classHint + " " + c.label).lowercase()
            PASSWORD_HINTS.any { hay.contains(it) }
        }
        if (hasPassword) return Posture.LOGIN_WALL

        val looksLikeLogin = fields.size in 1..2 && frame.controls.any { c ->
            val hay = c.label.lowercase()
            LOGIN_HINTS.any { hay.contains(it) }
        }
        if (looksLikeLogin) return Posture.LOGIN_WALL

        if (fields.size >= FORM_FIELD_THRESHOLD) return Posture.FORM_HEAVY

        // 系統權限對話框（相機／定位…）：同時出現「允許」與「拒絕」才算，
        // 避免一般設定頁的「允許」字樣誤判。這種畫面只有人能決定，代理人不得代按。
        val labels = frame.controls.map { it.label }
        val hasAllow = labels.any { it.contains("允許") || it.contains("Allow", true) }
        val hasDeny = labels.any { it.contains("拒絕") || it.contains("不允許") || it.contains("Deny", true) }
        if (hasAllow && hasDeny) return Posture.PERMISSION_WALL

        return Posture.NORMAL
    }

    fun describe(posture: Posture): String = when (posture) {
        Posture.NORMAL -> "一般畫面"
        Posture.LOGIN_WALL -> "登入／密碼頁：需要你自己操作"
        Posture.FORM_HEAVY -> "表單頁：填寫前請先確認"
        Posture.PERMISSION_WALL -> "系統權限對話框：請你決定允許或不允許"
    }
}
