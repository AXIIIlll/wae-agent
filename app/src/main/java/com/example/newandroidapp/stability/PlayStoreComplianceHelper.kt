package com.example.newandroidapp.stability

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.newandroidapp.chat.ConversationManager
import com.example.newandroidapp.vision.VisionAccessibilityService

/**
 * 【Google Play 商店上架合規與醒目揭露中心 (PlayStoreComplianceHelper)】
 *
 * 嚴格遵循 Google Play Developer Program Policies：
 * 1. AccessibilityService API Policy（無障礙服務政策）：
 *    - 明確宣告 `android:isAccessibilityTool="true"` 與 `feedbackSpoken|feedbackGeneric`
 *    - 在引導使用者開啟無障礙服務前，必須顯示獨立、不可略過的「醒目揭露聲明（Prominent Disclosure）」，
 *      並取得使用者的「明確正面同意（Affirmative Consent）」。
 * 2. User Data & Permissions Policy（使用者資料與最小權限原則）：
 *    - 移除受限制的 `QUERY_ALL_PACKAGES` 與 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
 *    - 不申請 `android.permission.INTERNET` 網路權限，從 OS 底層證明 100% 離線運算、零資料外傳。
 *    - 支援使用者隨時檢視隱私權揭露、撤銷同意並一鍵清除所有對話紀錄、本機記憶庫與日誌。
 */
object PlayStoreComplianceHelper {

    private const val PREFS_NAME = "google_play_compliance_prefs"
    private const val KEY_DISCLOSURE_ACCEPTED = "prominent_disclosure_accepted_v1"
    private const val KEY_ACCEPTED_TIMESTAMP = "prominent_disclosure_timestamp_ms"

    fun hasAcceptedProminentDisclosure(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
    }

    fun setProminentDisclosureAccepted(context: Context, accepted: Boolean) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted)
            .putLong(KEY_ACCEPTED_TIMESTAMP, if (accepted) System.currentTimeMillis() else 0L)
            .apply()
    }

    fun getComplianceStatusBadge(context: Context): String {
        val accepted = hasAcceptedProminentDisclosure(context)
        val consentTag = if (accepted) "已簽署醒目揭露與風險自負免責聲明" else "待確認醒目揭露與免責聲明"
        return "Google Play 合規模式：100% 離線無網路權限 | 零受限權限 | $consentTag"
    }

    /**
     * 在引導使用者開啟無障礙服務或啟動輔助前，強制檢查是否已通過 Google Play 規範之「醒目揭露（Prominent Disclosure）」同意。
     */
    fun ensureDisclosureBeforeAccessibilityAction(
        activity: Activity,
        onProceed: () -> Unit
    ) {
        if (hasAcceptedProminentDisclosure(activity)) {
            onProceed()
        } else {
            showProminentDisclosureDialog(
                activity = activity,
                forceReviewMode = false,
                onAccepted = onProceed
            )
        }
    }

    /**
     * 顯示符合 Google Play 人工審核標準之「無障礙服務醒目揭露與資料安全同意視窗」
     */
    fun showProminentDisclosureDialog(
        activity: Activity,
        forceReviewMode: Boolean = false,
        onAccepted: (() -> Unit)? = null
    ) {
        if (activity.isFinishing) return

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 36, 44, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
        }

        val titleTv = TextView(activity).apply {
            text = "無障礙服務醒目揭露、隱私權與使用者風險自負免責聲明"
            textSize = 15.0f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }

        val bodyScroll = ScrollView(activity)
        val bodyTv = TextView(activity).apply {
            text = buildString {
                appendLine("【1. 服務目的與無障礙輔助定位】")
                appendLine("本應用為符合 Google Play 規範之「無障礙語音與視覺輔助工具（Accessibility Tool）」，專為視障、長輩及手部行動不便之使用者設計，提供「一鍵語音下令」與「繁體中文語音播報（TTS）」輔助操作手機。")
                appendLine()
                appendLine("【2. 為什麼需要使用 AccessibilityService API？】")
                appendLine("當您主動下達語音或文字輔助指令時，本應用需要透過 Android 無障礙服務（AccessibilityService API）執行以下輔助功能：")
                appendLine("• 讀取當前螢幕之介面元件結構（按鈕標籤、輸入框位置與視窗層級）以理解畫面內容。")
                appendLine("• 針對無文字標籤之圖示或自繪區域進行局部螢幕畫面辨識（本機自研離線筆畫與視覺特徵運算）。")
                appendLine("• 代替手部不便之使用者執行點擊、文字輸入、頁面捲動與返回等無障礙手勢。")
                appendLine()
                appendLine("【3. 麥克風（RECORD_AUDIO）與應用探索說明】")
                appendLine("• 麥克風權限僅在您主動點擊「語音」按鈕時啟用，用於將您的語音轉為輔助指令，絕不在背景錄音。")
                appendLine("• 本應用透過合規之 <queries> 讀取桌面可啟動應用名稱，以便您透過語音快速切換應用程式。")
                appendLine()
                appendLine("【4. 100% 本機離線與零資料收集保證（Data Safety）】")
                appendLine("• 零網路連線：本應用未申請任何網路權限（無 INTERNET 權限），從作業系統底層保證物理上無法將任何資料傳送至外部伺服器。")
                appendLine("• 零個資外洩：所有 AI 視覺感知、雙本地模型推理與本機操作記憶庫皆 100% 僅在您的手機內部處理，絕不收集、儲存或分享您的密碼、金融資訊或個人隱私。")
                appendLine("• 高風險安全閘門（C!）與觸碰即停保護：遇到付款、下單、轉帳、刪除等高敏感頁面或金融/購物應用時，AI 會自動暫停或於您觸碰螢幕時立即強制停機讓位。")
                appendLine()
                appendLine("【5. 自動化操作風險自負與法律免責聲明（Assumption of Risk & Disclaimer）】")
                appendLine("• 使用者主動授權與監督義務：所有輔助點擊、滑動與文字輸入均由您本人主動下達指令觸發；執行期間您應全程注視螢幕並負起監督責任，若遇非預期狀況請立即觸碰螢幕或按下停止鍵中斷。")
                appendLine("• 按「現狀（AS-IS）」提供與風險自負：第三方應用程式介面可能隨時改版或彈出突發視窗，本軟體不保證視覺辨識與手勢執行百分之百無誤。因使用本工具所產生之任何操作結果、第三方帳號限制、資料變動、訂單成立或任何直接與間接財產損益，均由使用者本人全權承擔一切風險與責任，開發者概不負任何賠償或法律責任。")
                appendLine("• 金融交易與購物之最終確認責任：涉及股票證券下單、銀行轉帳匯款、行動支付、電商購物結帳或重要資料刪除時，最終確認與法律責任完全歸屬於使用者本人。")
                appendLine("• 合法使用義務：使用者不得將本工具用於非法用途、惡意搶票刷單或違反第三方服務條款之行為，如有違反概由使用者自負一切法律責任。")
            }
            textSize = 12.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            setLineSpacing(6f, 1.05f)
        }
        bodyScroll.addView(bodyTv)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 0)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(forceReviewMode)
            .create()

        val agreeBtn = Button(activity).apply {
            text = if (hasAcceptedProminentDisclosure(activity) && forceReviewMode) {
                "我已瞭解並維持同意上述揭露與風險自負免責聲明"
            } else {
                "我已詳閱並同意（自負操作風險並開啟無障礙服務）"
            }
            textSize = 12.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = 18f
            }
            setPadding(20, 18, 20, 18)
            setOnClickListener {
                setProminentDisclosureAccepted(activity, true)
                dialog.dismiss()
                onAccepted?.invoke()
            }
        }

        val revokeBtn = Button(activity).apply {
            text = "撤銷同意並清除所有對話紀錄、本機記憶庫與日誌"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#475569"))
                cornerRadius = 18f
            }
            setPadding(18, 14, 18, 14)
            setOnClickListener {
                setProminentDisclosureAccepted(activity, false)
                ConversationManager.clearConversation(activity.applicationContext)
                EpisodicAppMemory.clearMemory(activity.applicationContext)
                StabilityFlightRecorder.clearLogs(activity.applicationContext)
                CrashGuardManager.clearCrashLogs(activity.applicationContext)
                VisionAccessibilityService.instance?.removeFloatingHud()
                dialog.dismiss()
                Toast.makeText(activity, "已撤銷同意並清除所有對話紀錄、本機記憶與日誌", Toast.LENGTH_SHORT).show()
            }
        }

        val declineBtn = Button(activity).apply {
            text = if (forceReviewMode) "關閉視窗" else "暫不同意（不開啟無障礙服務）"
            textSize = 11.5f
            isAllCaps = false
            setTextColor(Color.parseColor("#CBD5E1"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 18f
            }
            setPadding(18, 14, 18, 14)
            setOnClickListener {
                dialog.dismiss()
                if (!forceReviewMode) {
                    Toast.makeText(activity, "您已選擇暫不同意，未開啟無障礙服務", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(agreeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 10
        })
        if (hasAcceptedProminentDisclosure(activity) || forceReviewMode) {
            btnRow.addView(revokeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10
            })
        }
        btnRow.addView(declineBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        container.addView(titleTv)
        container.addView(bodyScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 760))
        container.addView(btnRow)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.show()
    }
}
