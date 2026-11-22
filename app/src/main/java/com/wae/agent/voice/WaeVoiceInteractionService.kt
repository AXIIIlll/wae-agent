package com.wae.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * 系統助理服務（S36）。
 *
 * Android 的「預設助理」是 VoiceInteractionService，不是一般 Activity。
 * 這裡只做最小實作：onReady 之後就等系統叫 session；session 一被叫起，
 * 立刻把語音 UI（[AssistActivity]）叫出來，讓使用者在任何畫面都能長按召喚。
 *
 * 不做 always-on 熱詞（避免背景麥克風、耗電與 Android 14 前景服務限制）。
 */
class WaeVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i("WAE_VOICE", "VoiceInteractionService ready")
    }
}

/** 系統叫起助理時建立 session（每次按鍵/手勢一次） */
class WaeVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i("WAE_VOICE", "onNewSession")
        return WaeVoiceSession(this)
    }
}

/** 最小 session：把 UI 交給 AssistActivity（語音卡片），然後收起 session */
class WaeVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching {
            val intent = Intent(context, AssistActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }.onFailure { Log.e("WAE_VOICE", "launch AssistActivity failed: ${it.message}") }
        hide()
    }
}
