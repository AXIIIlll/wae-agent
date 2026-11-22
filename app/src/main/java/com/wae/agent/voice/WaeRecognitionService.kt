package com.wae.agent.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * 最小 RecognitionService 實作（S36）。
 *
 * 為什麼需要：Android 的助理角色（ROLE_ASSISTANT）要求 VoiceInteractionService 宣告
 * recognitionService，否則系統會警告「必須設定 RecognitionService」並可能拒絕切換
 * （實測 logcat: VoiceInteractionManager: The RecognitionService must be set...）。
 *
 * 實際辨識仍交給系統 SpeechRecognizer（AssistActivity 使用），這裡只是角色註冊用的樁；
 * 若系統真的把辨識請求導到這裡，直接回錯誤，由上層 fallback 到手動輸入。
 */
class WaeRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback?) {
        // no-op
    }

    override fun onCancel(listener: Callback?) {
        // no-op
    }
}
