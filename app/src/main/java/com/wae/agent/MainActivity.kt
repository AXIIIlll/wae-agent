package com.wae.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wae.agent.core.RunMode
import com.wae.agent.core.WaeDeepLinkCapabilityManager
import com.wae.agent.core.WaeNativeBridge
import com.wae.agent.core.WaeOpenSourceDatabaseRegistry
import com.wae.agent.core.WaePatternMiningEngine
import com.wae.agent.core.WaeV26PipelineOrchestrator
import com.wae.agent.core.WaeVisionAccessibilityService
import com.wae.agent.ui.glass.GlassDrawable
import com.wae.agent.ui.glass.WaeBackgroundDrawable
import com.wae.agent.ui.ghost.GhostView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WAE V2.6 極簡置中主介面：
 * - 全螢幕純白背景 (#FFFFFF)，核心功能框框置於手機畫面正中間
 * - 單一指令輸入框 + 執行按鈕 + 動態歧義澄清選項列 (ASK_USER 時自動浮現)
 * - 折疊式「⚙️ 設定」抽屜（無障礙服務設定、TEST/PRODUCTION 模式切換、Deep Link 與模式庫狀態）
 */
class MainActivity : Activity() {

    companion object {
        /** 設定預設助理（RoleManager.ROLE_ASSISTANT）的請求碼 */
        private const val REQ_ASSISTANT_ROLE = 1001
        private const val REQ_MIC_MAIN = 1002
    private const val REQ_NOTIF = 1003
    }

    private lateinit var goalInputBox: EditText
    private lateinit var statusLineView: TextView
    private lateinit var ambiguityContainer: LinearLayout
    private lateinit var settingsDrawer: LinearLayout
    private lateinit var runModeBtn: Button
    private lateinit var engineStatsView: TextView
    private lateinit var executeBtnView: Button
    private lateinit var serviceBannerRow: LinearLayout
    private lateinit var serviceBannerText: TextView
    private lateinit var crashLogBtn: Button
    private var devBenchmarkRow: LinearLayout? = null
    private var devTierSpinner: android.widget.Spinner? = null
    private var devCountInput: EditText? = null
    private var devBenchmarkStatus: TextView? = null

    private lateinit var safetyConfirmRow: LinearLayout
    private lateinit var safetyConfirmText: TextView

    // P1：進階設定 / onboarding / 診斷
    private lateinit var onboardingCard: LinearLayout
    private lateinit var languageBtn: Button
    private lateinit var taskOsBtn: Button
    private lateinit var omegaBtn: Button
    private lateinit var grounding2Btn: Button
    private lateinit var wakeWordBtn: Button
    private lateinit var recentCommandsView: TextView
    private lateinit var versionView: TextView

    // P1：系統檢查
    private lateinit var systemCheckContainer: LinearLayout
    private val checkRows = LinkedHashMap<String, CheckRow>()
    private data class CheckRow(val dot: TextView, val fix: Button)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWhiteLightSystemBars()
        if (WaeNativeBridge.isNativeReady()) {
            WaeNativeBridge.waeLoadAllModelsMmap(assets)
        }
        Thread {
            WaeOpenSourceDatabaseRegistry.ensureLoaded(this)
        }.start()
        WaeDeepLinkCapabilityManager.ensureInitialized(this)

        val outerScreenFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padH = dp(24)
            // 乾淨版面：小留白即可，內容置中
            setPadding(padH, dp(24), padH, dp(24))
        }

        // S50：移除大卡片外框——內容直接放在白底上（原本的圓角卡片沒有任何價值）
        val centerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            elevation = 0f
            val padH = dp(4)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
        }

        // 頂部列：左側標題 + 右側「⚙️ 設定」按鈕
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 品牌識別：小助手（向量圖，跟著 App 配色）
        val logoMark = GhostView(this).apply {
            bloom = 0.75f
            animated = true
        }
        centerCard.addView(
            logoMark,
            LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            }
        )

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val titleView = TextView(this).apply {
            text = "WAE"
            textSize = 22f
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F2F4FF"))
            letterSpacing = 0.04f
        }
        titleBox.addView(titleView)
        titleBox.addView(TextView(this).apply {
            text = "手機 AI 代理人"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8B96B4"))
        })
        headerRow.addView(
            titleBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val settingsBtn = Button(this).apply {
            text = "設定"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.parseColor("#D7DEF2"))
            background = GlassDrawable(radius = dp(14).toFloat())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                settingsDrawer.visibility = if (settingsDrawer.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    refreshEngineStats()
                    refreshCrashLogButton()
                    refreshSystemChecks()
                    refreshRecentCommands()
                    refreshRuntimeFlagButtons()
                    refreshLanguageButton()
                    View.VISIBLE
                }
            }
        }
        headerRow.addView(settingsBtn)
        centerCard.addView(
            headerRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        // P1：首次使用引導（3 步）
        onboardingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F3FF"))
                setStroke(dp(1), Color.parseColor("#DDD6FE"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        onboardingCard.addView(TextView(this).apply {
            text = "開始使用（3 步）"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4C1D95"))
        })
        onboardingCard.addView(TextView(this).apply {
            text = "1. 開啟無障礙  2. 設為預設助理  3. 按住電源鍵說話"
            textSize = 11.5f
            setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(0, dp(4), 0, dp(8))
        })
        val onboardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        onboardRow.addView(Button(this).apply {
            text = "開啟無障礙"
            isAllCaps = false
            textSize = 11.5f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { com.wae.core.util.Degrade.orNull("main:openAccessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        })
        onboardRow.addView(Button(this).apply {
            text = "設為助理"
            isAllCaps = false
            textSize = 11.5f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { fixCheck("assistant") }
        })
        onboardRow.addView(Button(this).apply {
            text = "語音"
            isAllCaps = false
            textSize = 11.5f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                runCatching {
                    startActivity(Intent(this@MainActivity, com.wae.agent.voice.AssistActivity::class.java))
                }
            }
        })
        onboardRow.addView(TextView(this).apply {
            text = "略過"
            textSize = 11.5f
            setTextColor(Color.parseColor("#7C3AED"))
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener {
                getSharedPreferences("wae_ui", MODE_PRIVATE).edit().putBoolean("onboarded", true).apply()
                onboardingCard.visibility = View.GONE
            }
        })
        onboardingCard.addView(onboardRow)
        centerCard.addView(
            onboardingCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        )
        refreshOnboarding()

        // 安全確認列：當動作命中金流/破壞性/公開發布政策時出現，需使用者明確放行
        safetyConfirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FEF2F2"))
                setStroke(dp(1), Color.parseColor("#F87171"))
                cornerRadius = dp(10).toFloat()
            }
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        safetyConfirmText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#991B1B"))
        }
        safetyConfirmRow.addView(safetyConfirmText)
        val confirmBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        repeat(2) { idx ->
            val isConfirm = idx == 0
            val btn = Button(this).apply {
                text = if (isConfirm) "確認執行" else "取消"
                isAllCaps = false
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                minHeight = 0
                minimumHeight = 0
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isConfirm) "#DC2626" else "#64748B"))
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    val svc = WaeVisionAccessibilityService.activeInstance
                    if (isConfirm) svc?.confirmPendingSafetyAction() else svc?.cancelPendingSafetyAction()
                    statusLineView.text = if (isConfirm) "已放行一次動作" else "已取消該動作"
                    refreshServiceBanner()
                }
            }
            confirmBtnRow.addView(
                btn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            )
        }
        safetyConfirmRow.addView(confirmBtnRow)
        centerCard.addView(
            safetyConfirmRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        )

        // 無障礙服務未啟用時的醒目橫幅（重裝 APK 後 Android 會自動關閉該服務）
        serviceBannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GlassDrawable(radius = dp(18).toFloat())
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        serviceBannerText = TextView(this).apply {
            text = "無障礙服務未啟用，AI 無法點擊任何東西"
            textSize = 12f
            setTextColor(Color.parseColor("#FCD34D"))
        }
        serviceBannerRow.addView(
            serviceBannerText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val bannerOpenBtn = Button(this).apply {
            text = "去開啟"
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F59E0B"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { openAccessibilitySettingsSafely() }
        }
        serviceBannerRow.addView(bannerOpenBtn)
        centerCard.addView(
            serviceBannerRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        // 單一指令輸入框
        goalInputBox = EditText(this).apply {
            hint = "想做什麼？"
            setHintTextColor(Color.parseColor("#6C7899"))
            setTextColor(Color.parseColor("#F2F4FF"))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            background = GlassDrawable(radius = dp(18).toFloat())
            val padH = dp(14)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
        }
        centerCard.addView(
            goalInputBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        // 開發輔助：若 filesDir 存在 debug_command，自動填入指令。
        // 用途：adb 無法輸入中文，靠這個檔案把中文指令送進 App 做端到端驗證；讀取後立即刪除。
        applyDebugCommandIfPresent()
        // S35：開發輔助——存在 filesDir/debug_overlay 就自動開懸浮窗（adb 驗證用）
        applyDebugOverlayIfPresent()
        // Gen-5 開發輔助：存在外部 gen5_command.txt 就走新管線（不影響正式路徑）
        com.wae.agent.gen5.Gen5Probe.check(this)

        // 執行按鈕（執行中會切換為「停止」，支援協作式中止）
        executeBtnView = Button(this).apply {
            text = "執行"
            isAllCaps = false
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#4F46E5"))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                if (WaeVisionAccessibilityService.isBusy()) {
                    WaeVisionAccessibilityService.activeInstance?.requestCancel()
                    statusLineView.text = "⏹ 正在停止...（於下一個檢查點安全結束）"
                    refreshServiceBanner()
                    return@setOnClickListener
                }
                triggerCommandExecution()
            }
        }
        centerCard.addView(
            executeBtnView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        // ── DEV_BENCHMARK：開發期內建評測（正式版請整塊刪除）──────────────
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F1F5F9"))
                    setStroke(dp(1), Color.parseColor("#CBD5E1"))
                    cornerRadius = dp(10).toFloat()
                }
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
            }
            val title = TextView(this).apply {
                text = " 開發期評測（debug only）"
                textSize = 12f
                setTextColor(Color.parseColor("#334155"))
            }
            row.addView(title)

            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            devTierSpinner = android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("全部", "EASY", "MEDIUM", "HARD", "EXTREME")
                )
            }
            controls.addView(
                devTierSpinner,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            )
            devCountInput = EditText(this).apply {
                hint = "數量"
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 12f
                setText("10")
            }
            controls.addView(
                devCountInput,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            val startBtn = Button(this).apply {
                text = "開始"
                isAllCaps = false
                textSize = 12f
                setOnClickListener { startDevBenchmark() }
            }
            controls.addView(startBtn)
            val promptsBtn = Button(this).apply {
                text = "▶ 提示詞"
                isAllCaps = false
                textSize = 12f
                setOnClickListener { startPromptSuite() }
            }
            controls.addView(promptsBtn)
            val scanBtn = Button(this).apply {
                text = " 相容性"
                isAllCaps = false
                textSize = 12f
                setOnClickListener { startCompatibilityScan() }
            }
            controls.addView(scanBtn)
            row.addView(controls)

            devBenchmarkStatus = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#475569"))
                text = "任務數：${com.wae.agent.dev.DevBenchmark.load(this@MainActivity).size}"
            }
            row.addView(devBenchmarkStatus)

            devBenchmarkRow = row
            centerCard.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }

        // 動態歧義澄清選項列 (僅在 AMBIGUOUS + ASK_USER 觸發時顯示 Top-3 選項)
        ambiguityContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        centerCard.addView(
            ambiguityContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 單行精簡狀態提示
        statusLineView = TextView(this).apply {
            text = buildMinimalStatusText()
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
        }
        centerCard.addView(
            statusLineView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 可折疊「⚙️ 設定」抽屜
        settingsDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#E2E8F0"))
        }
        settingsDrawer.addView(
            divider,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(12)
            }
        )

        // P1：系統檢查（一眼看出缺什麼，一鍵修復）
        settingsDrawer.addView(TextView(this).apply {
            text = "系統檢查"
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            setPadding(0, 0, 0, dp(6))
        })
        systemCheckContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsDrawer.addView(
            systemCheckContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        )
        addCheckRow("a11y", "無障礙服務")
        addCheckRow("assistant", "助理角色")
        addCheckRow("mic", "麥克風")
        addCheckRow("voice", "語音服務")

        // 自我測試：不用 adb 就知道「它現在還能不能用」
        settingsDrawer.addView(Button(this).apply {
            text = " 自我測試（9 項）"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { runSelfTest() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // 反例檢視（H7）：讓使用者看得到它學到哪些禁令，而且能刪
        settingsDrawer.addView(Button(this).apply {
            text = " 反例（它學到的禁令）"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FEF2F2"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { showRefutations() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // App 類型排序（每個型別的 App 順序，可拖動）
        settingsDrawer.addView(Button(this).apply {
            text = "\uD83E\uDDE9 App 類型排序（拖拉順序）"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF2FF"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, com.wae.agent.settings.AppTypeOrderActivity::class.java)
                )
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val openA11yBtn = Button(this).apply {
            text = "開啟無障礙服務"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Throwable) {
                }
            }
        }
        settingsDrawer.addView(
            openA11yBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        runModeBtn = Button(this).apply {            text = getRunModeButtonText()
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F3FF"))
                setStroke(dp(1), Color.parseColor("#DDD6FE"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                WaeV26PipelineOrchestrator.currentRunMode =
                    if (WaeV26PipelineOrchestrator.currentRunMode == RunMode.PRODUCTION) {
                        RunMode.TEST
                    } else {
                        RunMode.PRODUCTION
                    }
                runModeBtn.text = getRunModeButtonText()
                statusLineView.text = buildMinimalStatusText()
            }
        }
        settingsDrawer.addView(
            runModeBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        // S35：懸浮窗 UI（HTML/WebView）開關（開發輔助）
        val overlayBtn = Button(this).apply {
            text = "懸浮窗"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#93C5FD"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                val svc = WaeVisionAccessibilityService.activeInstance
                if (svc == null) {
                    statusLineView.text = "⚠ 請先開啟無障礙服務，懸浮窗需要它以 TYPE_ACCESSIBILITY_OVERLAY 顯示"
            
        settingsDrawer.visibility = View.VISIBLE
                } else {
                    val showing = svc.toggleFloatingOverlay()
                    statusLineView.text = if (showing) " 懸浮窗已開啟（可拖曳標題列移動）" else " 懸浮窗已關閉"
                }
            }
        }
        settingsDrawer.addView(
            overlayBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        // S36：語音助理（設為系統預設助理 → 長按電源鍵／Home 召喚，不用開 App）
        val assistRoleBtn = Button(this).apply {
            text = "設為手機助理（長按電源鍵）"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F3FF"))
                setStroke(dp(1), Color.parseColor("#DDD6FE"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                val rm = com.wae.core.util.Degrade.orNull("main:roleManager") { getSystemService(RoleManager::class.java) }
                if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    statusLineView.text = "⚠ 此裝置不支援助理角色；可用「 懸浮窗」或快捷設定磚"
                } else if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    statusLineView.text = "✓ WAE 已是預設助理：長按電源鍵／Home 即可召喚"
                } else {
                    runCatching {
                        startActivityForResult(
                            rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                            REQ_ASSISTANT_ROLE
                        )
                    }.onFailure {
                        statusLineView.text = "⚠ 無法開啟助理設定：${it.message}"
                    }
                }
            }
        }
        settingsDrawer.addView(
            assistRoleBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        val voiceNowBtn = Button(this).apply {
            text = "語音指令"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#93C5FD"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                runCatching {
                    startActivity(Intent(this@MainActivity, com.wae.agent.voice.AssistActivity::class.java))
                }.onFailure { statusLineView.text = "⚠ 無法開啟語音助理：${it.message}" }
            }
        }
        settingsDrawer.addView(
            voiceNowBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        // 上次崩潰紀錄入口（WaeApp 會把未捕捉例外寫到 filesDir/crash-last.txt）
        crashLogBtn = Button(this).apply {            text = " 查看上次崩潰紀錄"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#7C2D12"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF7ED"))
                setStroke(dp(1), Color.parseColor("#FDBA74"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { showCrashLogDialog() }
        }
        settingsDrawer.addView(
            crashLogBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        // P1：進階設定
        settingsDrawer.addView(TextView(this).apply {
            text = "進階"
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            setPadding(0, dp(8), 0, dp(4))
        })
        languageBtn = Button(this).apply {
            text = "語音語言"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.parseColor("#52525B"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F4F5"))
                setStroke(dp(1), Color.parseColor("#E4E4E7"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { showLanguagePicker() }
        }
        settingsDrawer.addView(
            languageBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        // 設定頁只留「語言」。其餘四個開關改成自動判斷，不再讓使用者手動切：
        //   - LLM Agent：由「APK 是否有模型 / 是否有模板可解析」自動決定（ExecutionRouter）
        //   - Grounding 2.0：預設開啟（多來源 grounding 融合）
        //   - Task OS：預設開啟（模板沒命中會自動回落舊拆解路徑）
        //   - WAE-Ω：shadow 模式只寫 log，對使用者沒有好處 → 不顯示
        // 需要除錯時用 adb 改 SharedPreferences，不必留在 UI。
        refreshRuntimeFlagButtons()
        refreshRuntimeFlagButtons()

        recentCommandsView = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#52525B"))
            text = "最近指令：—"
            setPadding(0, dp(4), 0, dp(4))
        }
        settingsDrawer.addView(recentCommandsView)
        settingsDrawer.addView(Button(this).apply {
            text = "清除紀錄"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                com.wae.agent.voice.SessionHistory.clear(this@MainActivity)
                refreshRecentCommands()
            }
        })

        val diagBtn = Button(this).apply {
            text = "匯出診斷包"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { exportDiagnostics() }
        }
        settingsDrawer.addView(
            diagBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        versionView = TextView(this).apply {
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#C7CFE4"))
            text = "WAE 2.1.0-wae · 離線 · 無 INTERNET 權限"
        }
        settingsDrawer.addView(versionView)

        engineStatsView = TextView(this).apply {
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
        }
        refreshCrashLogButton()
        refreshEngineStats()
        settingsDrawer.addView(
            engineStatsView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        wakeWordBtn = makeToggleButton("Hey WAE 喚醒詞（測試）") { enabled ->
            handleWakeWordToggle(enabled)
        }
        settingsDrawer.addView(
            wakeWordBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )

        centerCard.addView(
            settingsDrawer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        outerScreenFrame.addView(
            centerCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        installMinimalHome(centerCard)

        // 版面：淺灰底 + 白卡；不再用波浪漸層（與小助手的藍白配色一致）
        val rootFrame = FrameLayout(this).apply { background = WaeBackgroundDrawable() }
        val contentScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        contentScroll.addView(
            outerScreenFrame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        rootFrame.addView(
            contentScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        settingsDrawer.post { restyleSettingsTree(settingsDrawer) }

        rootFrame.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(rootFrame)

        WaeVisionAccessibilityService.uiUpdateCallback = {
            runOnUiThread {
                statusLineView.text = WaeVisionAccessibilityService.latestStatusMessage
                val msg = WaeVisionAccessibilityService.latestStatusMessage
                if (!WaeVisionAccessibilityService.isBusy() &&
                    (msg.contains("SUCCESS") || msg.contains("ABSTAIN") || msg.startsWith("✓") || msg.startsWith("⚠"))
                ) {
                    goalInputBox.setText("")
                }
                com.wae.core.util.Degrade.orNull("main:refreshMascot") { refreshMascotState() }
                renderAmbiguousClarificationButtons()
                refreshEngineStats()
                refreshServiceBanner()
            }
        }
        refreshServiceBanner()
    }

    /** 開發輔助：debug_command 檔案存在時自動填入指令（adb 無法輸入中文） */
    private fun applyDebugCommandIfPresent() {
        try {
            val f = File(filesDir, "debug_command")
            if (!f.exists()) return
            val text = f.readText(Charsets.UTF_8).trim()
            f.delete()
            if (text.isNotEmpty() && ::goalInputBox.isInitialized) {
                goalInputBox.setText(text)
                goalInputBox.setSelection(text.length)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * S35 開發輔助：filesDir/debug_overlay 存在時，自動開啟懸浮窗（給 adb 端到端驗證用）。
     * 讀取後立即刪除，正式使用者不會觸發。
     */
    private fun applyDebugOverlayIfPresent() {
        try {
            val f = File(filesDir, "debug_overlay")
            if (!f.exists()) return
            f.delete()
            window.decorView.postDelayed({
                val svc = WaeVisionAccessibilityService.activeInstance
                val ok = svc?.toggleFloatingOverlay() ?: false
                if (::statusLineView.isInitialized) {
                    statusLineView.text =
                        if (ok) " 懸浮窗已開啟（debug_overlay）"
                        else "⚠ 懸浮窗開啟失敗（無障礙服務未啟用？）"
                }
            }, 1200L)
        } catch (_: Throwable) {
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ASSISTANT_ROLE) {
            refreshSystemChecks()
            runCatching {
                statusLineView.text = if (resultCode == Activity.RESULT_OK) {
                    "✓ 已設為預設助理：長按電源鍵／Home 即可召喚 WAE"
                } else {
                    "未變更助理設定（仍可用懸浮窗或快捷設定磚）"
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 開發輔助：MainActivity 是 singleTask，第二次 am start 不會走 onCreate；
        // 在 onResume 再讀一次 debug_command，adb 端到端腳本才可靠（讀後即刪，正式使用者無感）。
        applyDebugCommandIfPresent()
        // Gen-5 開發輔助：singleTask 下第二次 am start 不會走 onCreate；
        // 在 onResume 也讀一次，adb 端到端才可靠（檔案不存在就什麼都不做）。
        com.wae.core.util.Degrade.orNull("main:gen5Probe") { com.wae.agent.gen5.Gen5Probe.check(this) }
        refreshSystemChecks()
        if (::recentCommandsView.isInitialized) refreshRecentCommands()
        if (::taskOsBtn.isInitialized) refreshRuntimeFlagButtons()
        if (::languageBtn.isInitialized) refreshLanguageButton()
        if (::statusLineView.isInitialized) {            // 使用者主動切回 WAE 就等於要中止正在跑的任務：
            // 一來 AI 一旦看到自己的介面就會亂點自己（已於服務層另加防護），
            // 二來這是目前唯一能「從別的 App 畫面叫停」的入口。
            if (WaeVisionAccessibilityService.isBusy()) {
                WaeVisionAccessibilityService.activeInstance?.requestCancel()
                statusLineView.text = "⏹ 已中止上一項任務（切回 App 即停止）"
            } else {
                statusLineView.text = buildMinimalStatusText()
            }
            renderAmbiguousClarificationButtons()
            refreshEngineStats()
            refreshServiceBanner()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 從系統無障礙設定頁返回時立即重新偵測服務是否已連上
        if (hasFocus) refreshServiceBanner()
    }

    override fun onPause() {
        super.onPause()
        serviceBannerHandler.removeCallbacks(serviceBannerPoll)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceBannerHandler.removeCallbacks(serviceBannerPoll)
        WaeVisionAccessibilityService.uiUpdateCallback = null
    }

    /**
     * 同步「無障礙服務是否已啟用/已連線」橫幅與「執行 / 停止」按鈕狀態。
     * 重裝 APK 後 Android 會自動關閉無障礙服務，這是最常見的「AI 完全不會點」原因，必須主動提示。
     * 注意：activeInstance 在服務重新綁定時會有空窗，所以橫幅以系統啟用清單為準，並持續輪詢。
     */
    private fun refreshServiceBanner() {
        if (!::serviceBannerRow.isInitialized || !::executeBtnView.isInitialized) return
        val connected = WaeVisionAccessibilityService.activeInstance != null
        val enabledInSystem = WaeVisionAccessibilityService.isEnabledInSystem(this)

        serviceBannerRow.visibility = if (connected) View.GONE else View.VISIBLE
        if (!connected && ::serviceBannerText.isInitialized) {
            serviceBannerText.text = if (enabledInSystem) {
                "無障礙服務已啟用，正在連線中…（若持續如此請重新開關一次）"
            } else {
                "無障礙服務未啟用，AI 無法點擊任何東西"
            }
            serviceBannerText.setTextColor(
                Color.parseColor(if (enabledInSystem) "#FDE68A" else "#FCD34D")
            )
        }

        val busy = WaeVisionAccessibilityService.isBusy()
        executeBtnView.text = if (busy) "停止" else "執行"
        executeBtnView.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (busy) intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#B91C1C"))
            else intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#4F46E5"))
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }

        // 安全確認列（命中金流/破壞性/公開發布政策時出現）
        if (::safetyConfirmRow.isInitialized && ::safetyConfirmText.isInitialized) {
            val pending = WaeVisionAccessibilityService.activeInstance?.pendingSafetyMessage()
            if (pending != null) {
                safetyConfirmRow.visibility = View.VISIBLE
                safetyConfirmText.text = pending
            } else {
                safetyConfirmRow.visibility = View.GONE
            }
        }

        // 未連線時每秒重試，連上後自動收起橫幅（避免使用者以為壞了）
        serviceBannerHandler.removeCallbacks(serviceBannerPoll)
        if (!connected) {
            serviceBannerHandler.postDelayed(serviceBannerPoll, 1000L)
        }
    }

    private val serviceBannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val serviceBannerPoll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) refreshServiceBanner()
        }
    }

    private fun crashLogFile(): File = File(filesDir, "crash-last.txt")

    /**
     * 更新「查看上次崩潰紀錄」按鈕：有紀錄時顯示時間，沒有時顯示「無紀錄」並停用。
     */
    private fun refreshCrashLogButton() {
        if (!::crashLogBtn.isInitialized) return
        val f = crashLogFile()
        if (!f.exists()) {
            // S49：沒有崩潰紀錄就不佔版面
            crashLogBtn.visibility = android.view.View.GONE
            return
        }
        crashLogBtn.visibility = android.view.View.VISIBLE
        crashLogBtn.isEnabled = true
        crashLogBtn.alpha = 1f
        val whenText = try {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
        } catch (_: Throwable) {
            ""
        }
        val sizeKb = (f.length() / 1024).coerceAtLeast(1)
        crashLogBtn.text = "上次錯誤（$whenText）"
    }

    private fun showCrashLogDialog() {
        val f = crashLogFile()
        if (!f.exists()) {
            statusLineView.text = " 目前沒有崩潰紀錄"
            refreshCrashLogButton()
            return
        }
        val content = try {
            f.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            "讀取失敗：${t.javaClass.simpleName} ${t.message.orEmpty()}"
        }
        val body = TextView(this).apply {
            text = content
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#0F172A"))
            setTextIsSelectable(true)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        val scroller = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("上次崩潰紀錄")
            .setView(scroller)
            .setPositiveButton("關閉", null)
            .setNeutralButton("清除紀錄") { _, _ ->
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
                statusLineView.text = " 崩潰紀錄已清除"
                refreshCrashLogButton()
            }
            .show()
    }

    private fun openAccessibilitySettingsSafely() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
        }
    }

    /** Gen-5 接線用：避免「新管線棄權 → 舊路徑」無限遞迴。 */

    /** 自我測試跑一次就好，避免連點。 */
    private var selfTestRunning = false

    /**
     * 反例檢視（H7）：看得到它學到哪些禁令，而且點一下就刪掉。
     * 這是「信任」的一部分——使用者要能改它的認知，而不是只能重裝。
     */
    private fun showRefutations() {
        val config = com.wae.core.config.MapConfig(emptyMap())
        val clock = object : com.wae.core.time.Clock {
            override fun nowMs(): Long = System.currentTimeMillis()
        }
        val memory = com.wae.core.memory.FileMemory(java.io.File(filesDir, "mem"), config, clock)
        val entries = com.wae.core.memory.RefutationView.entries(memory.refutations())

        if (entries.isEmpty()) {
            statusLineView.text = " 目前沒有反例（它還沒學到任何禁令）"
            runCatching {
                android.app.AlertDialog.Builder(this)
                    .setTitle("反例")
                    .setMessage("目前沒有反例。\n\n當某條路在這台機器上失敗兩次以上，它會被記錄成禁令，之後不會再優先走同一條。")
                    .setPositiveButton("關閉") { d, _ -> d.dismiss() }
                    .show()
            }
            return
        }

        val labels = entries.map { it.text }.toTypedArray()
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle("反例（點一下刪除）")
                .setItems(labels) { d, which ->
                    d.dismiss()
                    val removed = memory.removeRefutation(entries[which].key)
                    statusLineView.text = if (removed) " 已刪除一條反例，下次會重新嘗試那條路" else "⚠ 刪除失敗（可能已過期）"
                }
                .setNegativeButton("關閉") { d, _ -> d.dismiss() }
                .show()
        }
    }

    /**
     * App 內自我測試（9 個狀態無關的動作）。
     * 會改變裝置狀態（音量、亮度、開 App），所以一定要使用者按下才跑。
     */
    // ── 極簡主畫面：只有小助手 ─────────────────────────────
    private lateinit var mascotView: GhostView
    private lateinit var homeHeading: TextView
    private lateinit var homeSub: TextView
    private lateinit var homeTopRow: LinearLayout

    /**
     * 主畫面只留一顆小助手：
     *  - 長按：輸入（鍵盤或語音）
     *  - 有輸入內容：小助手變反轉色（黑底白眼）
     *  - 點一下：執行目前的指令
     */
    private fun installMinimalHome(centerCard: LinearLayout) {
        for (i in 0 until centerCard.childCount) {
            centerCard.getChildAt(i).visibility = View.GONE
        }
        com.wae.core.util.Degrade.orNull("main:removeInput") { centerCard.removeView(goalInputBox) }
        com.wae.core.util.Degrade.orNull("main:removeExecute") { centerCard.removeView(executeBtnView) }

        homeTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, dp(2))
        }
        val settingsPill = TextView(this).apply {
            text = "設定"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DCE3F7"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GlassDrawable(radius = dp(16).toFloat())
            setOnClickListener {
                com.wae.core.util.Degrade.orNull("main:openSettings") { startActivity(Intent(this@MainActivity, com.wae.agent.settings.SettingsActivity::class.java)) }
            }
        }
        homeTopRow.addView(
            settingsPill,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        centerCard.addView(
            homeTopRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        homeHeading = TextView(this).apply {
            text = "想做什麼？"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
            setTextColor(Color.parseColor("#F2F4FF"))
        }
        centerCard.addView(
            homeHeading,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        homeSub = TextView(this).apply {
            text = "說一句話，它自己去按"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#7C87A6"))
            setPadding(0, dp(6), 0, dp(10))
        }
        centerCard.addView(
            homeSub,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        mascotView = GhostView(this).apply {
            bloom = 1f
            animated = true
            setOnLongClickListener {
                showCommandSheet()
                true
            }
            setOnTouchListener { v, event ->
                val curve = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.animate()
                        .scaleX(0.96f).scaleY(0.96f).setDuration(90L).setInterpolator(curve).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate()
                        .scaleX(1f).scaleY(1f).setDuration(220L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.35f)).start()
                }
                false
            }
            setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!goalInputBox.text.isNullOrBlank() && now - lastMascotTapMs <= 600L) {
                    // 雙擊 → 執行（取消剛才排定的「開對話」）
                    pendingChatOpen?.let { hintView.removeCallbacks(it) }
                    pendingChatOpen = null
                    lastMascotTapMs = 0L
                    hintView.text = "雙擊執行 · 點一下對話"
                    triggerCommandExecution()
                    return@setOnClickListener
                }
                // 單擊 → 對話（延遲 320ms，等看看有沒有第二下）
                lastMascotTapMs = now
                val pending = Runnable { pendingChatOpen = null; openChat() }
                pendingChatOpen = pending
                hintView.postDelayed(pending, 320L)
                hintView.postDelayed({
                    if (lastMascotTapMs != 0L && android.os.SystemClock.elapsedRealtime() - lastMascotTapMs >= 300L) {
                        lastMascotTapMs = 0L
                    }
                }, 700L)
            }
        }
        centerCard.addView(
            mascotView,
            LinearLayout.LayoutParams(dp(200), dp(200)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        hintView = TextView(this).apply {
            text = "長按輸入 · 點一下對話"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#7C87A6"))
            setPadding(0, dp(10), 0, 0)
        }
        centerCard.addView(
            hintView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        goalInputBox.visibility = View.VISIBLE
        executeBtnView.visibility = View.VISIBLE
        centerCard.addView(
            goalInputBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16); bottomMargin = dp(10) }
        )
        centerCard.addView(
            executeBtnView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        )

        backToMinimalBtn = Button(this).apply {
            text = "回到小助手"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = dp(10).toFloat()
            }
            visibility = View.GONE
            setOnClickListener { showMinimalUi() }
        }
        centerCard.addView(
            backToMinimalBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )
        centerCard.gravity = Gravity.CENTER_HORIZONTAL
        refreshMascotState()
        goalInputBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshMascotState()
        })
    }

    private lateinit var backToMinimalBtn: Button
    private lateinit var hintView: TextView
    private var voiceForSheet: com.wae.agent.voice.VoiceRecognizerController? = null
    private var pendingChatOpen: Runnable? = null
    private var lastMascotTapMs = 0L

    /** 切換到完整介面（設定、狀態、除錯都在那裡）。 */
    private fun showFullUi() {
        val parent = mascotView.parent as? android.widget.LinearLayout ?: return
        for (i in 0 until parent.childCount) parent.getChildAt(i).visibility = View.VISIBLE
        mascotView.visibility = View.GONE
        hintView.visibility = View.GONE
        backToMinimalBtn.visibility = View.VISIBLE
    }

    /** 回到只有小助手的畫面。 */
    private fun showMinimalUi() {
        val parent = mascotView.parent as? android.widget.LinearLayout ?: return
        for (i in 0 until parent.childCount) parent.getChildAt(i).visibility = View.GONE
        mascotView.visibility = View.VISIBLE
        hintView.visibility = View.VISIBLE
        homeTopRow.visibility = View.VISIBLE
        homeHeading.visibility = View.VISIBLE
        homeSub.visibility = View.VISIBLE
        goalInputBox.visibility = View.VISIBLE
        executeBtnView.visibility = View.VISIBLE
        backToMinimalBtn.visibility = View.GONE
    }

    /** 有字＝反轉色（可執行）；沒字＝正常色（可長按輸入）。 */
    private fun refreshMascotState() {
        if (!::mascotView.isInitialized) return
        val hasText = !goalInputBox.text.isNullOrBlank()
        mascotView.bodyAlpha = if (hasText) 0.62f else 1f
        if (::hintView.isInitialized) {
            hintView.text = if (hasText) "雙擊執行 · 點一下對話" else "長按輸入 · 點一下對話"
        }
        lastMascotTapMs = 0L
    }

    /** 對話框裡的「語音」：只做辨識，把結果填進輸入框，不開另一個畫面。 */
    private fun startVoiceIntoBox(box: EditText) {
        val controller = com.wae.agent.voice.VoiceRecognizerController(this)
        if (!controller.isAvailable()) {
            statusLineView.text = "此裝置沒有可用的語音辨識服務，請用鍵盤輸入"
            return
        }
        voiceForSheet?.destroy()
        voiceForSheet = controller
        statusLineView.text = " 請說…"
        android.widget.Toast.makeText(this, " 請說…", android.widget.Toast.LENGTH_SHORT).show()
        controller.start(
            onPartial = { text ->
                box.setText(text)
                box.setSelection(box.text.length)
            },
            onFinal = { text ->
                box.setText(text)
                box.setSelection(box.text.length)
                statusLineView.text = " 已填入：$text（按「好了」確認）"
                android.widget.Toast.makeText(this, " 已填入：$text", android.widget.Toast.LENGTH_SHORT).show()
                voiceForSheet?.destroy()
                voiceForSheet = null
            },
            onError = { message, _ ->
                statusLineView.text = " $message"
                android.widget.Toast.makeText(this, " $message", android.widget.Toast.LENGTH_LONG).show()
                voiceForSheet?.destroy()
                voiceForSheet = null
            }
        )
    }

    /** 長按小助手：輸入（鍵盤）／對話／語音／設定。 */
    private fun showCommandSheet() {
        val box = EditText(this).apply {
            hint = "想做什麼？"
            setText(goalInputBox.text)
            setSelection(text.length)
            textSize = 16f
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
                cornerRadius = dp(12).toFloat()
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(2))
        }
        wrap.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        val dialog = AlertDialog.Builder(this).setTitle("要做什麼？").setView(wrap).create()
        fun actionBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            minHeight = 0
            minimumHeight = 0
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = dp(9).toFloat()
            }
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { onClick() }
        }
        row.addView(actionBtn("好了") {
            goalInputBox.setText(box.text.toString().trim())
            goalInputBox.setSelection(goalInputBox.text.length)
            refreshMascotState()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        row.addView(actionBtn("對話") {
            goalInputBox.setText(box.text.toString().trim())
            openChat()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        row.addView(actionBtn("語音") {
            startVoiceIntoBox(box)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        row.addView(actionBtn("設定") {
            showFullUi()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        wrap.addView(row)
        dialog.setOnShowListener { box.requestFocus() }
        dialog.show()
    }

    /** 進入對話模式（小助手會縮到左上角再展開）。 */
    private fun openChat() {
        runCatching {
            startActivity(Intent(this, com.wae.agent.chat.ChatActivity::class.java))
            overridePendingTransition(0, 0)
        }
    }

    private fun runSelfTest() {
        if (selfTestRunning) return
        if (WaeVisionAccessibilityService.activeInstance == null) {
            statusLineView.text = "⚠ 請先開啟無障礙服務，自我測試才能跑"
            settingsDrawer.visibility = View.VISIBLE
            return
        }
        selfTestRunning = true
        statusLineView.text = " 自我測試中…（會開 App、調音量亮度）"
        Thread {
            val results = runCatching {
                com.wae.agent.gen5.SelfTest.run(this) { command ->
                    runOnUiThread { statusLineView.text = " 測試中：$command" }
                }
            }.getOrDefault(emptyList())
            val message = com.wae.agent.gen5.SelfTest.render(results)
            runOnUiThread {
                selfTestRunning = false
                statusLineView.text = com.wae.agent.gen5.SelfTest.summary(results)
                runCatching {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("自我測試")
                        .setMessage(message)
                        .setPositiveButton("關閉") { d, _ -> d.dismiss() }
                        .show()
                }
            }
        }.start()
    }

    /** 把 Gen-5 的結果畫成人看得懂的卡（C6 棄權／C7 確認共用）。 */
    private fun showGen5Card(card: com.wae.core.ui.Card) {
        if (isFinishing) return
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(card.title)
            .setMessage(card.body)
            .setCancelable(true)
        val labels = card.options.map { it.label }
        when (labels.size) {
            0 -> builder.setPositiveButton("知道了") { d, _ -> d.dismiss() }
            1 -> builder.setPositiveButton(labels[0]) { d, _ -> d.dismiss() }
            2 -> builder.setNegativeButton(labels[0]) { d, _ -> d.dismiss() }
                .setPositiveButton(labels[1]) { d, _ -> d.dismiss() }
            else -> builder.setPositiveButton(labels.last()) { d, _ -> d.dismiss() }
        }
        com.wae.core.util.Degrade.orNull("main:dialogShow") { builder.show() }
    }

    private fun triggerCommandExecution() {        val raw = goalInputBox.text?.toString()?.trim().orEmpty()
        val svc = WaeVisionAccessibilityService.activeInstance

        if (raw.isEmpty()) {
            // 若未輸入指令但無障礙已啟用，單純執行一次感知快照
            if (svc != null) {
                statusLineView.text = "⏳ 正在執行 L1 感知快照..."
                svc.capturePerceptionSnapshotAsync {
                    runOnUiThread {
                        val cnt = WaeVisionAccessibilityService.latestNodeCount
                        val pos = WaeVisionAccessibilityService.latestPostureSummary
                        statusLineView.text = "✓ 感知完成：$cnt 個有效候選 · 姿態 $pos"
                    }
                }
            } else {
                statusLineView.text = "⚠ 請先輸入指令，或點「⚙️ 設定」開啟無障礙服務"
                settingsDrawer.visibility = View.VISIBLE
            }
            return
        }

        // Gen-5 已經搬到 UserCommandEntry（與題庫共用同一條路徑）。
        // 以前這段寫在這裡，題庫頁沒有，於是同一句指令兩邊結果不同。

        // G：使用者回饋。「不是這個／換一個」把剛剛選的 App 記為不喜歡，
        // 「記住這個／就是這個」記為喜歡；下次同類意圖直接用偏好修正分數。
        val feedback = raw.trim()
        if (feedback in setOf("不是這個", "不是这个", "換一個", "换一个", "不要這個", "记错这个")) {
            val applied = com.wae.agent.core.app.AppChoiceMemo.applyToLastPick(this, liked = false)
            statusLineView.text = if (applied == null) "⚠ 還沒有選過 App，無法記錄"
            else " 記住了：${applied.second} 不是 ${applied.first} 的選擇"
            return
        }
        if (feedback in setOf("記住這個", "记住这个", "就是這個", "就是这个", "選對了", "选对了")) {
            val applied = com.wae.agent.core.app.AppChoiceMemo.applyToLastPick(this, liked = true)
            statusLineView.text = if (applied == null) "⚠ 還沒有選過 App，無法記錄"
            else " 記住了：${applied.second} 用於 ${applied.first}"
            return
        }

        // 使用者指令的唯一入口（與驗證題庫共用）：路由 → 啟動目標 App → 快速管線／開放式執行器。
        // 以前這段流程在 MainActivity 與題庫頁各寫一份，導致兩邊行為不一致
        // （實測：「打開時鐘」主畫面成功、題庫失敗）。
        if (svc != null && com.wae.agent.core.UserCommandEntry.run(this, raw, { msg ->
                runOnUiThread {
                    statusLineView.text = msg
                    renderAmbiguousClarificationButtons()
                }
            }, { result ->
                // Gen-5 結果卡沿用原本的呈現
                com.wae.core.util.Degrade.orNull("ui:gen5Card") {
                    com.wae.core.ui.CardFactory.from(result.outcome)?.let { showGen5Card(it) }
                }
            })
        ) {
            refreshServiceBanner()
            return
        }

        // 若無障礙服務尚未開啟，但指令目標是打開某個外部 App，仍可直接透過 PackageManager 啟動該 App
        val goals = WaeV26PipelineOrchestrator.decomposeCommandIntoLocalGoals(raw, this)
        val firstGoal = goals.firstOrNull()
        if (svc == null && firstGoal != null) {
            if (firstGoal.targetPackageHint.isNotEmpty() &&
                WaeOpenSourceDatabaseRegistry.launchAppByPackageName(this, firstGoal.targetPackageHint)
            ) {
                statusLineView.text = " 已啟動 ${firstGoal.targetPackageHint}（若需自動操作內部畫面請開啟無障礙服務）"
                return
            }
            val dl = WaeDeepLinkCapabilityManager.findMatchingDeepLink(firstGoal)
            if (dl != null && WaeDeepLinkCapabilityManager.launchDeepLinkFastPath(
                    this,
                    dl,
                    firstGoal.inputPayload.ifEmpty { firstGoal.goalText }
                )
            ) {
                statusLineView.text = "⚡ 已透過 L0 Deep Link 直達啟動 (${dl.ruleId})"
                return
            }
            statusLineView.text = "⚠ 請先開啟無障礙服務（下方紅色橫幅可一鍵前往）"
    
        settingsDrawer.visibility = View.VISIBLE
            refreshServiceBanner()
            return
        }

        if (firstGoal != null && firstGoal.targetPackageHint.isNotEmpty()) {
            // 由前景 MainActivity 直接發起 startActivity，徹底避開 Android 14/15 背景服務啟動 Activity (BAL) 限制
            WaeOpenSourceDatabaseRegistry.launchAppByPackageName(this, firstGoal.targetPackageHint)
        } else if (firstGoal != null && firstGoal.targetPackageHint.isEmpty()) {
            // 若指令未指定啟動新 App，代表要操作底層畫面，先將 WAE 主視窗退至背景以露出目標 App
            try {
                moveTaskToBack(true)
            } catch (_: Throwable) {
            }
        }

        svc?.executeUserCommandPipelineAsync(raw) { msg ->
            runOnUiThread {
                statusLineView.text = msg
                renderAmbiguousClarificationButtons()
                refreshServiceBanner()
            }
        }
        refreshServiceBanner()
    }

    private fun renderAmbiguousClarificationButtons() {
        val opts = WaeVisionAccessibilityService.pendingAmbiguousOptions
        ambiguityContainer.removeAllViews()
        if (opts.isEmpty()) {
            ambiguityContainer.visibility = View.GONE
            return
        }
        ambiguityContainer.visibility = View.VISIBLE
        for ((idx, opt) in opts.withIndex()) {
            val btn = Button(this).apply {
                val label = opt.coreText.ifEmpty { opt.weldedDescription }
                text = "選項 ${idx + 1}：$label"
                isAllCaps = false
                textSize = 12.5f
                setTextColor(Color.parseColor("#0F172A"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FEF3C7"))
                    setStroke(dp(1), Color.parseColor("#F59E0B"))
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    WaeVisionAccessibilityService.activeInstance?.resolveAmbiguousSelectionAsync(opt) { msg ->
                        runOnUiThread {
                            statusLineView.text = msg
                            renderAmbiguousClarificationButtons()
                        }
                    }
                }
            }
            ambiguityContainer.addView(
                btn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
            )
        }
    }

    private fun getRunModeButtonText(): String {
        return if (WaeV26PipelineOrchestrator.currentRunMode == RunMode.PRODUCTION) {
            "模式：正式"
        } else {
            "模式：測試"
        }
    }

    private fun refreshEngineStats() {
        if (!::engineStatsView.isInitialized) return
        // S49：極簡——只留一行服務狀態
        val svcActive = WaeVisionAccessibilityService.activeInstance != null
        engineStatsView.text = if (svcActive) {
            "服務：開啟 · 模式：" + WaeV26PipelineOrchestrator.currentRunMode.name
        } else {
            "服務：未開啟"
        }
    }

    /** DEV_BENCHMARK：依選擇的難度層與數量執行內建評測 */
    private fun startDevBenchmark() {
        if (com.wae.agent.dev.DevBenchmark.isRunning()) {
            devBenchmarkStatus?.text = "評測進行中…"
            return
        }
        val all = com.wae.agent.dev.DevBenchmark.load(this)
        val tier = devTierSpinner?.selectedItem?.toString().orEmpty()
        val filtered = if (tier.isEmpty() || tier == "全部") all else all.filter { it.tier == tier }
        val count = devCountInput?.text?.toString()?.toIntOrNull() ?: 10
        val tasks = filtered.take(count.coerceIn(1, filtered.size))
        if (tasks.isEmpty()) {
            devBenchmarkStatus?.text = "沒有符合條件的任務"
            return
        }
        devBenchmarkStatus?.text = "開始 ${tasks.size} 個任務…"
        val host = object : com.wae.agent.dev.DevBenchmark.Host {
            override fun setCommand(cmd: String) {
                runOnUiThread { goalInputBox.setText(cmd) }
            }

            override fun startTask() {
                runOnUiThread { triggerCommandExecution() }
            }

            override fun isBusy(): Boolean = WaeVisionAccessibilityService.isBusy()

            override fun taskRunSeq(): Long =
                WaeVisionAccessibilityService.activeInstance?.taskRunSeq ?: 0L

            override fun lastTaskSuccess(): Boolean? =
                WaeVisionAccessibilityService.activeInstance?.lastTaskSuccess

            override fun currentStatus(): String = latestStatusForBenchmark()

            override fun onProgress(text: String) {
                runOnUiThread { devBenchmarkStatus?.text = text }
            }

            override fun onFinished(summary: String) {
                runOnUiThread {
                    devBenchmarkStatus?.text = summary
                    statusLineView.text = summary.lineSequence().firstOrNull() ?: summary
                }
            }
        }
        com.wae.agent.dev.DevBenchmark.run(this, host, tasks)
    }

    /**
     * 相容性掃描：只啟動 App + 讀畫面文字，判斷是否被阻擋。
     * 不做任何點擊或任務執行。
     */
    private fun startCompatibilityScan() {
        if (com.wae.agent.dev.CompatibilityScanner.isRunning()) {
            devBenchmarkStatus?.text = "掃描進行中…"
            return
        }
        val svc = WaeVisionAccessibilityService.activeInstance
        if (svc == null) {
            devBenchmarkStatus?.text = "無障礙服務未啟用，無法掃描"
            return
        }
        val host = object : com.wae.agent.dev.CompatibilityScanner.Host {
            override fun installedPackages(): Set<String> =
                packageManager.getInstalledPackages(0).map { it.packageName }.toSet()

            override fun launch(pkg: String): Boolean = svc.probeLaunchPublic(pkg)

            override fun screenTexts(): List<String> = svc.probeScreenTextsPublic()

            override fun foregroundPackage(): String = svc.probeForegroundPackagePublic()

            override fun goHome() {
                WaeVisionAccessibilityService.goHomePublic()
            }

            override fun onProgress(text: String) {
                runOnUiThread { devBenchmarkStatus?.text = "掃描 $text" }
            }
        }
        devBenchmarkStatus?.text = "開始相容性掃描…（只讀畫面，不點擊）"
        com.wae.agent.dev.CompatibilityScanner.run(this, host)
        devBenchmarkStatus?.postDelayed({
            val f = java.io.File(filesDir, "compat_matrix.tsv")
            devBenchmarkStatus?.text = if (f.exists()) {
                "掃描完成 → files/compat_matrix.tsv（" + f.readLines().size + " 列）"
            } else "掃描進行中…"
        }, 20_000)
    }

    /**
     * 提示詞套件：只負責「依序執行 + 記錄原始事實」。
     * 成敗由後端（adb logcat / 截圖）判定，App 不自我判定。
     */
    private fun startPromptSuite() {
        if (com.wae.agent.dev.PromptSuiteRunner.isRunning()) {
            devBenchmarkStatus?.text = "提示詞套件進行中…"
            return
        }
        if (WaeVisionAccessibilityService.activeInstance == null) {
            devBenchmarkStatus?.text = "無障礙服務未啟用，無法執行"
            return
        }
        val items = com.wae.agent.dev.PromptSuiteRunner.load(this)
        if (items.isEmpty()) {
            devBenchmarkStatus?.text = "找不到提示詞套件（assets/dev/prompt_suite.tsv）"
            return
        }
        devBenchmarkStatus?.text = "開始 ${items.size} 項提示詞…（判定請看 logcat: WAE_PROMPTS）"
        val host = object : com.wae.agent.dev.PromptSuiteRunner.Host {
            override fun goHome() {
                WaeVisionAccessibilityService.goHomePublic()
            }

            override fun setCommand(cmd: String) {
                runOnUiThread { goalInputBox.setText(cmd) }
            }

            override fun startTask() {
                runOnUiThread { triggerCommandExecution() }
            }

            override fun isBusy(): Boolean = WaeVisionAccessibilityService.isBusy()

            override fun taskRunSeq(): Long =
                WaeVisionAccessibilityService.activeInstance?.taskRunSeq ?: 0L

            override fun lastTaskSuccess(): Boolean? =
                WaeVisionAccessibilityService.activeInstance?.lastTaskSuccess

            override fun currentStatus(): String = latestStatusForBenchmark()

            override fun onProgress(text: String) {
                runOnUiThread { devBenchmarkStatus?.text = text }
            }

            override fun onFinished(summary: String) {
                runOnUiThread {
                    devBenchmarkStatus?.text = summary
                    statusLineView.text = summary
                }
            }
        }
        com.wae.agent.dev.PromptSuiteRunner.run(this, host, items)
    }

    private fun latestStatusForBenchmark(): String = try {
        statusLineView.text.toString()
    } catch (_: Throwable) {
        ""
    }

    /**
     * 設定抽屜的統一外觀：按鈕一律玻璃、小節標題提亮。
     * 只改「長相」，不動任何點擊行為或文字。
     */
    private fun restyleSettingsTree(root: android.view.ViewGroup) {
        val sectionColor = 0xFF71717A.toInt()
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Button) {
                child.isAllCaps = false
                child.background = GlassDrawable(radius = dp(14).toFloat())
                child.setTextColor(Color.parseColor("#DCE3F7"))
            } else if (child is TextView && child.currentTextColor == sectionColor && child.text.length > 2) {
                child.setTextColor(Color.parseColor("#8B96B4"))
            }
            if (child is android.view.ViewGroup) restyleSettingsTree(child)
        }
    }

    private fun buildMinimalStatusText(): String {
        val svcActive = WaeVisionAccessibilityService.activeInstance != null
        return if (svcActive) "待命" else "無障礙未開啟"
    }

    @Suppress("DEPRECATION")
    private fun configureWhiteLightSystemBars() {
        try {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            // 深色底 → 系統列圖示用預設（淺色），不要開 LIGHT_* 旗標
            window.decorView.systemUiVisibility = 0
        } catch (_: Throwable) {
        }
    }

    // ── P1：系統檢查 ──────────────────────────────────────
    private fun addCheckRow(key: String, title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 10f
            setTextColor(Color.parseColor("#C7CFE4"))
            setPadding(0, 0, dp(6), 0)
        }
        val label = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#F2F4FF"))
        }
        val fix = Button(this).apply {
            isAllCaps = false
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1D4ED8"))
            background = GlassDrawable(radius = dp(12).toFloat())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { fixCheck(key) }
        }
        row.addView(dot)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(fix)
        systemCheckContainer.addView(row)
        checkRows[key] = CheckRow(dot, fix)
    }

    private fun makeToggleButton(label: String, onChange: (Boolean) -> Unit): Button {
        val b = Button(this).apply {
            isAllCaps = false
            textSize = 12.5f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                val now = !(tag as? Boolean ?: false)
                tag = now
                applyToggleStyle(this, label, now)
                onChange(now)
            }
        }
        b.tag = false
        applyToggleStyle(b, label, false)
        return b
    }

    private fun applyToggleStyle(b: Button, label: String, enabled: Boolean) {
        b.text = label + "：" + if (enabled) "開" else "關"
        b.setTextColor(Color.parseColor(if (enabled) "#1D4ED8" else "#71717A"))
        b.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (enabled) "#F5F3FF" else "#F4F4F5"))
            setStroke(dp(1), Color.parseColor(if (enabled) "#DDD6FE" else "#E4E4E7"))
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun refreshRuntimeFlagButtons() {
        if (!::taskOsBtn.isInitialized) return
        val to = com.wae.agent.core.runtime.RuntimeFlags.isTaskOsEnabled(this)
        val om = com.wae.agent.core.runtime.RuntimeFlags.isOmegaEnabled(this)
        val g2 = com.wae.agent.core.runtime.RuntimeFlags.isGrounding2Enabled(this)
        taskOsBtn.tag = to
        omegaBtn.tag = om
        grounding2Btn.tag = g2
        applyToggleStyle(taskOsBtn, "Task OS（長任務）", to)
        applyToggleStyle(omegaBtn, "WAE-Ω（可驗證執行）", om)
        applyToggleStyle(grounding2Btn, "Grounding 2.0", g2)
        if (::wakeWordBtn.isInitialized) {
            val wake = com.wae.core.util.Degrade.orElse("main:wakeEnabled", false) { com.wae.agent.voice.WakeWordService.isEnabled(this) }
            wakeWordBtn.tag = wake
            applyToggleStyle(wakeWordBtn, "Hey WAE 喚醒詞（測試）", wake)
        }
    }

    private fun handleWakeWordToggle(enabled: Boolean) {
        if (enabled) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC_MAIN)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
        com.wae.agent.voice.WakeWordService.setEnabled(this, enabled)
        statusLineView.text = if (enabled) "喚醒詞已開啟（常駐通知；關閉即停止）" else "喚醒詞已關閉"
        refreshRuntimeFlagButtons()
    }

    private fun refreshRecentCommands() {
        if (!::recentCommandsView.isInitialized) return
        val list = com.wae.core.util.Degrade.orEmpty("main:sessionHistory") { com.wae.agent.voice.SessionHistory.recent(this, 5) }
        recentCommandsView.text = if (list.isEmpty()) {
            "最近指令：—"
        } else {
            "最近指令：" + list.joinToString("\n") { e ->
                (if (e.voice) "[聲] " else "[字] ") + e.text +
                    when (e.success) {
                        true -> " ✓"
                        false -> " ✕"
                        null -> ""
                    }
            }
        }
    }

    private fun refreshLanguageButton() {
        if (!::languageBtn.isInitialized) return
        // 只有一種語言時不顯示這個按鈕：設定頁不該讓使用者選一個「選了也沒差」的東西。
        val langs = loadLanguages()
        if (langs.size <= 1) {
            languageBtn.visibility = android.view.View.GONE
            return
        }
        languageBtn.visibility = android.view.View.VISIBLE
        val code = getSharedPreferences("wae_voice", MODE_PRIVATE)
            .getString("voice_lang", "zh-TW") ?: "zh-TW"
        val name = com.wae.core.util.Degrade.orNull("main:langName") { langs.firstOrNull { it.first == code }?.second } ?: code
        languageBtn.text = "語音語言：$name"
    }

    private fun loadLanguages(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            assets.open("open_source_ai_databases/ui_voice_languages.tsv").bufferedReader().useLines { seq ->
                seq.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val p = line.split('	')
                    if (p.size >= 2) out.add(p[0].trim() to p[1].trim())
                }
            }
        }
        return out
    }

    private fun showLanguagePicker() {
        val langs = loadLanguages()
        if (langs.isEmpty()) return
        val cur = getSharedPreferences("wae_voice", MODE_PRIVATE).getString("voice_lang", "zh-TW")
        val idx = langs.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("語音語言")
            .setSingleChoiceItems(langs.map { it.second }.toTypedArray(), idx) { d, which ->
                getSharedPreferences("wae_voice", MODE_PRIVATE).edit()
                    .putString("voice_lang", langs[which].first).apply()
                refreshLanguageButton()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshOnboarding() {
        if (!::onboardingCard.isInitialized) return
        val done = getSharedPreferences("wae_ui", MODE_PRIVATE).getBoolean("onboarded", false)
        onboardingCard.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun exportDiagnostics() {
        val f = com.wae.core.util.Degrade.orNull("main:exportDiagnostics") { com.wae.agent.core.DiagnosticsExporter.export(this) }
        statusLineView.text = if (f != null) {
            "診斷包：Android/data/com.wae.agent/files/${f.parentFile?.name}/${f.name}"
        } else {
            "診斷包匯出失敗"
        }
    }

    private fun refreshSystemChecks() {
        if (!::systemCheckContainer.isInitialized) return
        // 1) 無障礙
        val a11yOk = WaeVisionAccessibilityService.activeInstance != null
        applyCheck("a11y", a11yOk, if (a11yOk) "已開啟" else "未開啟", "去開啟")
        // 2) 助理角色
        val roleOk = runCatching {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
        applyCheck("assistant", roleOk, if (roleOk) "已設定" else "未設定", "設定")
        // 3) 麥克風
        val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        applyCheck("mic", micOk, if (micOk) "已允許" else "未允許", "允許")
        // 4) 語音服務（Google 語音輸入 / 系統辨識是否可用）
        val voiceOk = isVoiceInputAvailable()
        applyCheck("voice", voiceOk, if (voiceOk) "可用" else "不可用", "設定")
        android.util.Log.i(
            "WAE_CHECKS",
            "a11y=" + a11yOk + " role=" + roleOk + " mic=" + micOk + " voice=" + voiceOk
        )
    }

    private fun applyCheck(key: String, ok: Boolean, statusText: String, actionText: String) {
        val row = checkRows[key] ?: return
        row.dot.setTextColor(Color.parseColor(if (ok) "#4ADE80" else "#F87171"))
        row.fix.isEnabled = !ok
        row.fix.text = if (ok) statusText else actionText
        row.fix.setTextColor(Color.parseColor(if (ok) "#9AA6C4" else "#E0E7FF"))
        row.fix.alpha = if (ok) 0.65f else 1f
    }

    /** 語音是否可用：Google 語音輸入 Activity 能解析，或系統有 SpeechRecognizer */
    private fun isVoiceInputAvailable(): Boolean {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
        }
        val resolves = runCatching {
            packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        val recognizer = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        }.getOrDefault(false)
        return resolves || recognizer
    }

    private fun fixCheck(key: String) {
        when (key) {
            "a11y" -> com.wae.core.util.Degrade.orNull("main:fixA11y") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            "assistant" -> {
                val rm = com.wae.core.util.Degrade.orNull("main:roleManager") { getSystemService(RoleManager::class.java) }
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    runCatching {
                        startActivityForResult(
                            rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                            REQ_ASSISTANT_ROLE
                        )
                    }
                } else {
                    refreshSystemChecks()
                }
            }
            "mic" -> runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC_MAIN)
            }
            "voice" -> runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            }.onFailure {
                statusLineView.text = "此裝置沒有語音輸入設定頁"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC_MAIN) refreshSystemChecks()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
