package com.wae.agent.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.wae.agent.R
import com.wae.agent.core.app.InstalledAppCatalog
import com.wae.agent.gen5.Gen5Runtime
import com.wae.agent.ui.glass.GlassDrawable
import com.wae.agent.ui.glass.WaeBackgroundDrawable
import com.wae.agent.ui.ghost.GhostView
import com.wae.core.chat.ConversationMemory

/**
 * 對話模式（玻璃 + 小幽靈版）。
 *
 * 兩份記憶從第一版就分開：
 *  - 對話記憶：ConversationMemory（只放最近幾輪對話，不落盤）
 *  - 執行記憶：Gen5Runtime 內部（目的、槽位、收據），不進這裡
 *
 * 回話不靠模型：打招呼、能力清單、上一步結果、失敗原因都用模板／事實產生。
 * 指令本身照舊走規則 → 路由 → 名詞 fallback，成功率不受影響。
 *
 * 視覺：深色漸層底 + 玻璃卡 + 半透明小幽靈（GhostView）。全畫面零 emoji，圖示皆為自繪 VectorDrawable。
 */
class ChatActivity : Activity() {

    private val memory = ConversationMemory()
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var overlayGhost: GhostView
    private lateinit var headerGhost: GhostView
    private lateinit var content: LinearLayout
    private var lastCommand = ""
    private var pendingCorrection = ""
    private var voiceController: com.wae.agent.voice.VoiceRecognizerController? = null
    private var lastResult = ""

    private val ink = Color.parseColor("#F2F4FF")
    private val inkDim = Color.parseColor("#9AA6C4")
    private val inkFaint = Color.parseColor("#6C7899")
    private val accent = Color.parseColor("#8B5CF6")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        )
        setContentView(buildRoot())
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            content.setPadding(0, bars.top + dp(8), 0, bars.bottom)
            insets
        }
        playEnterAnimation()
        addBubble("嗨，要做什麼？", fromUser = false)
        intent?.getStringExtra("q")?.takeIf { it.isNotBlank() }?.let { q ->
            input.setText(q)
            input.postDelayed({ send() }, 900)
        }
    }

    // ── 動態（禁線性；進場減速、退場加速）──────────────────────
    private val enterCurve = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val exitCurve = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    private val stateCurve = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 按壓回饋：按下 0.96（90ms），放開回彈（220ms）。 */
    private fun pressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(90L).setInterpolator(stateCurve).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(220L).setInterpolator(OvershootInterpolator(1.35f)).start()
            }
            false
        }
    }

    // ── 版面 ────────────────────────────────────────────────
    private fun buildRoot(): View {
        val root = FrameLayout(this).apply { background = WaeBackgroundDrawable() }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            setPadding(0, dp(10), 0, 0)
        }

        // ── 標題列（玻璃）──
        val headerWrap = FrameLayout(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GlassDrawable(radius = dp(30).toFloat())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(14); rightMargin = dp(14) }
        }

        val avatarBox = FrameLayout(this)
        headerGhost = GhostView(this).apply {
            animated = true
            bloom = 0.55f
        }
        avatarBox.addView(headerGhost, FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER })
        header.addView(avatarBox, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(10) })

        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        who.addView(TextView(this).apply {
            text = "小幽靈"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        })
        who.addView(TextView(this).apply {
            text = "有問題直接問，或叫我做事"
            textSize = 11.5f
            setTextColor(inkFaint)
        })
        header.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_wae_close)
            setColorFilter(inkDim)
            background = GlassDrawable(radius = dp(20).toFloat())
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { closeWithAnimation() }
            pressFeedback(this)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))

        headerWrap.addView(header)
        content.addView(headerWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // ── 訊息串 ──
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(messages)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 快速回覆 ──
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), dp(10))
        }
        fun addChip(label: String, action: () -> Unit) {
            val chip = quickChip(label, action)
            quick.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        addChip("再說一次") { resend() }
        addChip("你能做什麼") { appendUserAndReply("你能做什麼") }
        addChip("為什麼失敗") { appendUserAndReply("為什麼失敗") }
        content.addView(quick)

        // ── 輸入列（玻璃膠囊）──
        val composerWrap = FrameLayout(this)
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(7), dp(7))
            background = GlassDrawable(radius = dp(30).toFloat())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(10)
            }
        }

        input = EditText(this).apply {
            hint = "說點什麼，或按麥克風…"
            setHintTextColor(inkFaint)
            setTextColor(ink)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            background = null
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    send(); true
                } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_wae_mic)
            setColorFilter(inkDim)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GlassDrawable(radius = dp(22).toFloat())
            setOnClickListener { startVoiceIntoInput() }
            pressFeedback(this)
        }
        composer.addView(mic, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(7) })

        val sendBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_wae_send)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#4F46E5"))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            setOnClickListener { send() }
            pressFeedback(this)
        }
        composer.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        composerWrap.addView(composer)
        content.addView(composerWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 轉場用的大幽靈（一開始在畫面中央）
        overlayGhost = GhostView(this).apply { bloom = 1f }
        val size = dp(190)
        root.addView(
            overlayGhost,
            FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
        )
        return root
    }

    private fun quickChip(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.parseColor("#C7D2FE"))
        gravity = Gravity.CENTER
        pressFeedback(this)
        background = GlassDrawable(radius = dp(19).toFloat())
        setPadding(dp(14), dp(9), dp(14), dp(9))
        minHeight = dp(42)
        setOnClickListener { onClick() }
    }

    // ── 轉場：小幽靈縮小飛到左上角，對話再淡入 ────────────────
    private fun playEnterAnimation() {
        headerGhost.postDelayed({
            val targetScale = 46f / 190f
            val rootLoc = IntArray(2)
            val headerLoc = IntArray(2)
            (overlayGhost.parent as? View)?.getLocationInWindow(rootLoc)
            headerGhost.getLocationInWindow(headerLoc)
            val dx = (headerLoc[0] - rootLoc[0] + dp(23)) - (overlayGhost.width / 2f)
            val dy = (headerLoc[1] - rootLoc[1] + dp(23)) - (overlayGhost.height / 2f)
            overlayGhost.animate()
                .translationX(dx)
                .translationY(dy)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(460L)
                .setInterpolator(enterCurve)
                .start()
            content.animate().alpha(1f).setStartDelay(280L).setDuration(240L)
                .setInterpolator(enterCurve).start()
            overlayGhost.postDelayed({
                overlayGhost.visibility = View.GONE
                overlayGhost.animated = false
            }, 500L)
        }, 60L)
    }

    private fun closeWithAnimation() {
        content.animate().alpha(0f).setDuration(180L).setInterpolator(exitCurve).start()
        headerGhost.visibility = View.INVISIBLE
        overlayGhost.visibility = View.VISIBLE
        overlayGhost.animated = true
        overlayGhost.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(90L)
            .setDuration(400L)
            .setInterpolator(enterCurve)
            .withEndAction { finish() }
            .start()
    }

    override fun onBackPressed() {
        closeWithAnimation()
    }

    // ── 對話 ────────────────────────────────────────────────
    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        runCatching {
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        appendUserAndReply(text)
    }

    private fun resend() {
        if (lastCommand.isBlank()) {
            replyDelayed("還沒有可以重試的指令。")
            return
        }
        appendUserAndReply(lastCommand)
    }

    private fun appendUserAndReply(text: String) {
        addBubble(text, fromUser = true)
        memory.addUser(text)
        if (pendingCorrection.isNotBlank()) {
            val wrong = pendingCorrection
            pendingCorrection = ""
            runCatching {
                com.wae.agent.chat.KnowledgeHost.recordCorrection(this, wrong, "OP: " + text)
            }
            replyDelayed("好，記住了：「$wrong」不是你要的，下次我會照「$text」做。")
            return
        }
        when (classify(text)) {
            "greeting" -> replyDelayed(greeting())
            "capability" -> replyDelayed(capabilityText())
            "last" -> replyDelayed(lastResult.ifBlank { "還沒執行過任何指令。" })
            "why" -> replyDelayed(
                if (lastResult.contains("ABSTAIN") || lastResult.contains("失敗")) lastResult
                else "上一次沒有失敗，最近一次結果：${lastResult.ifBlank { "尚未執行" }}"
            )
            "thanks" -> replyDelayed("不客氣。")
            "wrong" -> {
                pendingCorrection = lastCommand
                replyDelayed("好，那不是你要的。那你要的是什麼？（說一次，我記住）")
            }
            else -> runCommand(text)
        }
    }

    private fun classify(text: String): String {
        val t = text.lowercase().trim()
        if (t in setOf("hello", "hi", "嗨", "你好", "哈囉", "hey", "安安")) return "greeting"
        if (t.contains("你能做什麼") || t.contains("會什麼") || t.contains("會做什麼")) return "capability"
        if (t.contains("剛剛") || t.contains("上一步") || t.contains("做了什麼")) return "last"
        if (t.contains("為什麼失敗") || t.contains("怎麼失敗")) return "why"
        if (t.contains("謝謝") || t.contains("thank")) return "thanks"
        if (t.contains("不是這個") || t.contains("不是這樣") || t.contains("錯了")) return "wrong"
        return "command"
    }

    private fun greeting(): String = "嗨，要做什麼？"

    private fun capabilityText(): String {
        val apps = runCatching { InstalledAppCatalog.load(this, force = false) }.getOrDefault(emptyList())
        val cats = apps.groupBy { it.category }.mapValues { it.value.size }
            .entries.filter { it.key.isNotBlank() }
            .sortedByDescending { it.value }.take(6)
            .joinToString("、") { it.key + " " + it.value }
        val caps = com.wae.agent.gen5.AppCapabilityStore.summary(this)
        return "這支手機上，我目前可以：開 App、地圖搜尋、傳訊息、調音量／亮度、找餐廳、看影片。" +
            "\n已安裝 ${apps.size} 個 App（$cats）。\n$caps"
    }

    private fun runCommand(text: String) {
        lastCommand = text
        val thinking = addBubble("好，我試試看", fromUser = false)
        startThinkingDots(thinking)
        Thread {
            val result = runCatching { Gen5Runtime.runDetailed(this, text) }.getOrDefault(null)
            runOnUiThread {
                val raw = result?.summary ?: ""
                lastResult = raw
                val summary = raw.ifBlank { "這次沒有執行成功。" }
                (thinking.parent as? ViewGroup)?.removeView(thinking)
                addBubble(summary, fromUser = false)
                if (!summary.contains("SUCCESS")) {
                    addBubble("要換個說法，或告訴我更多細節嗎？", fromUser = false)
                }
            }
        }.start()
    }

    /** 三點呼吸動畫（自繪圓點，不用符號）。 */
    private fun startThinkingDots(target: View) {
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GlassDrawable(radius = dp(18).toFloat())
        }
        repeat(3) { i ->
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.argb(200, 242, 244, 255))
                    cornerRadius = dp(4).toFloat()
                }
                alpha = 0.45f
            }
            dots.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                rightMargin = if (i < 2) dp(7) else 0
            })
        }
        val row = target.parent as? LinearLayout ?: return
        row.addView(dots, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL; leftMargin = dp(8) })

        fun pulse(i: Int) {
            val child = dots.getChildAt(i) ?: return
            child.animate().alpha(1f).scaleX(1.25f).scaleY(1.25f).setDuration(320L)
                .withEndAction {
                    child.animate().alpha(0.45f).scaleX(1f).scaleY(1f).setDuration(320L).start()
                }.start()
            dots.postDelayed({ pulse((i + 1) % 3) }, 220L)
        }
        pulse(0)
    }

    private fun replyDelayed(text: String) {
        val holder = addBubble("…", fromUser = false)
        holder.postDelayed({
            (holder.parent as? ViewGroup)?.removeView(holder)
            addBubble(text, fromUser = false)
        }, 240L)
    }

    /** 回傳 bubble 本體（外層 row 是它的 parent）。 */
    private fun addBubble(text: String, fromUser: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.BOTTOM
        }
        if (!fromUser) {
            row.addView(GhostView(this).apply { bloom = 0f; bodyAlpha = 1f; animated = false },
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(6); bottomMargin = dp(2) })
        }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(if (fromUser) Color.WHITE else ink)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = if (fromUser) {
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#7C3AED"), Color.parseColor("#4F46E5"))
                ).apply {
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.argb(60, 255, 255, 255))
                }
            } else {
                GlassDrawable(radius = dp(20).toFloat())
            }
        }
        row.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        messages.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        row.alpha = 0f
        row.translationY = dp(14).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(220L)
            .setInterpolator(stateCurve).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    /** 麥克風：直接辨識，結果填進輸入框（不開另一個畫面）。 */
    private fun startVoiceIntoInput() {
        val controller = com.wae.agent.voice.VoiceRecognizerController(this)
        if (!controller.isAvailable()) {
            addBubble("這台裝置沒有可用的語音辨識，請用鍵盤輸入。", fromUser = false)
            return
        }
        voiceController?.destroy()
        voiceController = controller
        Toast.makeText(this, "請說…", Toast.LENGTH_SHORT).show()
        controller.start(
            onPartial = { text ->
                input.setText(text)
                input.setSelection(input.text.length)
            },
            onFinal = { text ->
                input.setText(text)
                input.setSelection(input.text.length)
                Toast.makeText(this, "已填入", Toast.LENGTH_SHORT).show()
                voiceController?.destroy()
                voiceController = null
            },
            onError = { message, _ ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                voiceController?.destroy()
                voiceController = null
            }
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
