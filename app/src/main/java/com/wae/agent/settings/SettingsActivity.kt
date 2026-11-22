package com.wae.agent.settings

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wae.agent.gen5.SelfTest
import com.wae.agent.ui.glass.GlassDrawable
import com.wae.agent.ui.glass.WaeBackgroundDrawable
import com.wae.agent.ui.ghost.GhostView

/**
 * 設定頁（S-C1 第一批）。
 *
 * 為什麼要有這個 Activity：`MainActivity` 原本把「主畫面＋設定抽屜＋開發工具＋系統檢查」
 * 全部塞在一個 2,274 行的檔案裡，換 UI 或改設定都得動到主畫面。這一頁把「設定」獨立出來，
 * 主畫面只剩輸入與狀態；之後開發工具也會搬到這裡。
 *
 * 這一頁只做三件事：**檢查、帶你去開、告訴你現在能不能用**。
 */
class SettingsActivity : Activity() {

    private val ink = Color.parseColor("#F2F4FF")
    private val inkDim = Color.parseColor("#8B96B4")
    private val inkFaint = Color.parseColor("#6C7899")
    private val okColor = Color.parseColor("#4ADE80")
    private val badColor = Color.parseColor("#F87171")

    private lateinit var rows: LinearLayout
    private lateinit var tail: LinearLayout
    private var target: LinearLayout? = null
    private var pendingRefresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildRoot())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ── 版面 ────────────────────────────────────────────────
    private fun buildRoot(): View {
        val root = FrameLayout(this).apply { background = WaeBackgroundDrawable() }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }

        // 標題列：返回 + 小幽靈 + 標題
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = GlassDrawable(radius = dp(20).toFloat())
            setOnClickListener { finish() }
        }
        head.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })

        head.addView(
            GhostView(this).apply { bloom = 0.5f },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) }
        )
        head.addView(
            TextView(this).apply {
                text = "設定"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        column.addView(head)

        column.addView(TextView(this).apply {
            text = "只放會影響「它能不能動」的東西。"
            textSize = 12.5f
            setTextColor(inkFaint)
            setPadding(dp(2), dp(10), 0, dp(4))
        })

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        tail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(tail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        target = tail

        // 工具
        section("工具")
        card(
            actionRow("自我測試", "跑一遍開 App／音量／亮度，看它自己能不能動") {
                runSelfTest()
            }
        )
        card(
            actionRow("診斷包", "只含結構與計數，回報問題時給這個") {
                exportDiagnostics()
            }
        )

        // 關於
        section("關於")
        card(
            infoRow("版本", "3.0.0-wae（versionCode 6）")
        )
        target = null

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        return root
    }

    private fun section(title: String) {
        (target ?: rows).addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(inkDim)
            setPadding(dp(6), dp(20), 0, dp(8))
        })
    }

    private fun card(vararg children: View) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(radius = dp(20).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        children.forEach { box.addView(it) }
        (target ?: rows).addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun actionRow(title: String, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(ink)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(inkFaint)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply {
                text = "›"
                textSize = 22f
                setTextColor(inkDim)
            })
        }

    private fun infoRow(title: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 14f
                setTextColor(inkDim)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply {
                text = value
                textSize = 13f
                setTextColor(ink)
            })
        }

    // ── 檢查 ────────────────────────────────────────────────
    private fun refresh() {
        rows.removeAllViews()

        section("系統檢查")
        val a11y = isA11yEnabled()
        val role = isAssistantRole()
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val voice = isVoiceAvailable()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(radius = dp(20).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(checkRow("無障礙服務", a11y, "已開啟", "去開啟") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        box.addView(checkRow("助理角色", role, "已設定", "去設定") {
            val rm = runCatching { getSystemService(RoleManager::class.java) }.getOrNull()
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            }
        })
        box.addView(checkRow("麥克風", mic, "已授權", "去授權") {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1002) }
        })
        box.addView(checkRow("語音服務", voice, "可用", "去設定") {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        })
        rows.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        section("助理")
        card(
            infoRow("語音辨識", "sherpa-onnx · 離線"),
            infoRow("喚醒", "在小幽靈上長按輸入，或說「小幽靈」")
        )

        if (!a11y) {
            rows.addView(TextView(this).apply {
                text = "沒有無障礙，它只能聊天，不能幫你按任何東西。"
                textSize = 12.5f
                setTextColor(Color.parseColor("#FCD34D"))
                setPadding(dp(6), dp(14), 0, dp(4))
            })
        }
    }

    private fun checkRow(title: String, ok: Boolean, okText: String, actionText: String, onFix: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))

            addView(TextView(this@SettingsActivity).apply {
                text = "●"
                textSize = 9f
                setTextColor(if (ok) okColor else badColor)
                setPadding(0, 0, dp(10), 0)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 15f
                setTextColor(ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply {
                text = if (ok) okText else actionText
                textSize = 12.5f
                setTextColor(if (ok) inkDim else Color.parseColor("#E0E7FF"))
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GlassDrawable(radius = dp(14).toFloat())
                if (!ok) setOnClickListener { onFix() }
            })
        }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(packageName + "/")
    }

    private fun isAssistantRole(): Boolean = runCatching {
        val rm = getSystemService(RoleManager::class.java) ?: return false
        rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }.getOrDefault(false)

    private fun isVoiceAvailable(): Boolean = runCatching {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        packageManager.resolveActivity(intent, 0) != null ||
            android.speech.SpeechRecognizer.isRecognitionAvailable(this)
    }.getOrDefault(false)

    private fun hasAsset(path: String): Boolean =
        runCatching { assets.open(path).use { true } }.getOrDefault(false)

    // ── 工具 ────────────────────────────────────────────────
    private fun runSelfTest() {
        if (!isA11yEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("先開無障礙")
                .setMessage("自我測試會真的去按按鈕，沒有無障礙只會全部失敗。")
                .setPositiveButton("去開啟") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                .setNegativeButton("算了", null)
                .show()
            return
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("自我測試")
            .setMessage("開始…")
            .setCancelable(false)
            .create()
        progress.show()
        Thread {
            val results = runCatching { SelfTest.run(this) { step ->
                runOnUiThread { runCatching { progress.setMessage(step) } }
            } }.getOrDefault(emptyList())
            runOnUiThread {
                runCatching { progress.dismiss() }
                val pass = results.count { it.pass }
                AlertDialog.Builder(this)
                    .setTitle(if (pass == results.size) "全部通過（$pass/${results.size}）" else "有失敗（$pass/${results.size}）")
                    .setMessage(results.joinToString("\n") { r ->
                        (if (r.pass) "PASS  " else "FAIL  ") + r.command + (if (r.pass) "" else "  → " + r.actual)
                    })
                    .setPositiveButton("好", null)
                    .show()
            }
        }.start()
    }

    private fun exportDiagnostics() {
        val text = buildString {
            appendLine("WAE 診斷包")
            appendLine("version=3.0.0-wae")
            appendLine("android=" + android.os.Build.VERSION.RELEASE + " sdk=" + android.os.Build.VERSION.SDK_INT)
            appendLine("device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            appendLine("a11y=" + isA11yEnabled())
            appendLine("assistantRole=" + isAssistantRole())
        }
        AlertDialog.Builder(this)
            .setTitle("診斷包（只含結構與計數）")
            .setMessage(text)
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
