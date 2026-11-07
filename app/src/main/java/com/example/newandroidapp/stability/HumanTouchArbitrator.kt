package com.example.newandroidapp.stability

import android.os.SystemClock
import com.example.newandroidapp.vision.VisionPipelineReport

/**
 * 100% 原創自研：真人觸碰螢幕智慧仲裁與誤觸自動復原引擎 (HumanTouchArbitrator)
 *
 * 專責解決「AI 自動執行任務期間，真人手指觸碰到手機螢幕時，究竟是想讓 AI 緊急停下、還是不小心誤觸」的難題：
 *
 * 1. 【第一層：包名攔截 (Package Sensitivity Interception)】：
 *    - 股票證券交易 App（三竹股市、元大投資先生、富邦 e 點通、國泰證券、永豐大戶投、幣安等）
 *    - 銀行網銀與行動支付 App（各家銀行、Line Pay、街口支付、台灣 Pay、Google Pay 等）
 *    - 電商購物與外送下單 App（蝦皮購物、momo、PChome、淘寶、Coupang 酷澎、UberEats、foodpanda 等）
 *    -> 只要進入上述高敏感或購物 App，真人隨便碰一下螢幕，100% 立即強制停止 (`IMMEDIATE_HARD_STOP`)！
 *
 * 2. 【第二層：畫面語義攔截 (Screen Semantic Interception)】：
 *    - 即使在一般 App，只要當前畫面節點包含「付費、下單、結帳、立即購買、去買單、提交訂單、確認付款、委託買進、委託賣出、轉帳、匯款」等敏感語義，
 *    -> 真人隨便碰一下螢幕，同樣 100% 立即強制停止 (`IMMEDIATE_HARD_STOP`)！
 *
 * 3. 【第三層：連續雙觸逃生閥 (Intentional Double-Touch Stop)】：
 *    - 在低風險 App（如聽歌 YouTube / Spotify）中，若真人在 2.5 秒內連續觸碰螢幕 2 次，判定為真人明確想讓 AI 停止，立即停機。
 *
 * 4. 【第四層：低風險誤觸前後快照比對與自動跳回原介面 (Accidental Touch Diff & Auto-Recovery)】：
 *    - 若在低風險情境（如聽歌、查一般資訊）不小心單次誤觸螢幕：
 *      - **無影響 (`NO_IMPACT_CONTINUE`)**：誤觸後的 `packageName` 與 `screenFingerprint` 未跳走 -> 不用按返回，直接繼續執行原任務。
 *      - **跳到別的介面或廣告 (`JUMPED_WITHIN_APP_BACK`)**：誤觸點到了廣告或跳進別的子介面 -> 自動按返回鍵 (`GLOBAL_ACTION_BACK`) 跳回誤觸前的原介面！
 *      - **跳出到其他 App 或外部廣告 (`JUMPED_EXTERNAL_APP_RECOVER`)**：誤觸導致跳出原 App（例如點到廣告跳去瀏覽器或商店） -> 自動按返回鍵並切回誤觸前的原 App 與原介面！
 */
object HumanTouchArbitrator {

    enum class TouchInitialVerdict {
        /** 處於股票/網銀/購物 App、或當前畫面含付費下單語義、或 2.5 秒內連續觸碰 2 次 -> 立即強制停止 AI */
        IMMEDIATE_HARD_STOP,
        /** 處於聽歌/日常低風險情境之單次觸碰 -> 先視為疑似誤觸，等待短暫穩定後比對誤觸前後介面決定是否跳回 */
        INSPECT_ACCIDENTAL_TOUCH
    }

    enum class PostTouchRecoveryAction {
        /** 誤觸沒有產生任何介面跳轉或影響 -> 不用跳回，直接繼續原任務 */
        NO_IMPACT_CONTINUE,
        /** 誤觸導致在同一個 App 內跳到了別的介面或點開了廣告彈窗 -> 自動執行 BACK 跳回誤觸前原介面 */
        JUMPED_WITHIN_APP_BACK,
        /** 誤觸導致跳轉到外部其他 App（如點擊廣告跳往瀏覽器/商店） -> 自動執行 BACK 並強制切回原 App 介面 */
        JUMPED_EXTERNAL_APP_RECOVER,
        /** 誤觸後意外進入了高敏感付費/下單畫面 -> 安全起見立即停止 */
        POST_TOUCH_ENTERED_HIGH_RISK_STOP
    }

    data class PreTouchAnchorSnapshot(
        val packageName: String,
        val appLabel: String,
        val screenFingerprint: ClosedLoopVerifier.ScreenFingerprint,
        val capturedAtElapsedMs: Long,
        val userGoal: String
    )

    data class TouchArbitrationResult(
        val verdict: TouchInitialVerdict,
        val reasonMessage: String,
        val preTouchSnapshot: PreTouchAnchorSnapshot? = null
    )

    data class PostTouchEvaluationResult(
        val action: PostTouchRecoveryAction,
        val reasonMessage: String,
        val targetPackageToRestore: String = ""
    )

    private const val DOUBLE_TOUCH_STOP_WINDOW_MS = 2500L

    @Volatile
    private var lastLowRiskTouchElapsedMs: Long = -10000L

    @Volatile
    var latestPreTouchSnapshot: PreTouchAnchorSnapshot? = null
        private set

    /**
     * 股票證券、期貨、加密貨幣交易 App 包名關鍵字與常見台灣/國際券商套件識別碼
     */
    private val STOCK_TRADING_PACKAGE_TOKENS = listOf(
        "mitake", "yuanta", "fubon", "cathaysec", "kgi", "sinopac", "fugle",
        "capital.sec", " President", "masterlink", "esunsec", "ctbcsec", "taishinsec",
        "megasec", "firstsec", "ibkr", "interactivebrokers", "firstrade", "schwab",
        "tradingview", "investing", "binance", "okx", "bybit", "max.maicoin", "bitoex",
        "stock", "securities", "broker", "trader", "trading", "futures", "forex", "crypto"
    )

    /**
     * 銀行、網銀、信用卡與行動支付錢包 App 包名關鍵字
     */
    private val BANK_AND_PAYMENT_PACKAGE_TOKENS = listOf(
        "bank", "netbank", "mbank", "ebank", "wallet", "pay", "jkopay", "linepay",
        "taiwanpay", "icashpay", "pxpay", "pluspay", "famipay", "openpoint",
        "cathaybk", "ctbcbank", "esunbank", "taishinbank", "richart", "fubonbank",
        "sinopac.dawho", "megabank", "bot.com.tw", "tcb-bank", "landbank", "post.gov.tw",
        "paypal", "alipay", "tenpay"
    )

    /**
     * 電商購物、拍賣、百貨與外送點餐 App 包名關鍵字（使用者明確指定：進入買東西的 App 人類隨便碰一下也必須停）
     */
    private val SHOPPING_AND_COMMERCE_PACKAGE_TOKENS = listOf(
        "shopee", "momo", "pchome", "ruten", "yahoo.mobile.client.android.ecstore",
        "taobao", "tmall", "alibaba", "aliexpress", "jd.com", "pinduoduo",
        "coupang", "amazon", "books.com", "eslite", "rakuten", "pinkoi", "carousell",
        "ubereats", "foodpanda", "inline.app", "klook", "kkday", "agoda", "booking",
        "shopping", "mall", "mart", "store.ec", "ecommerce"
    )

    /**
     * App 顯示名稱（中文/英文）高敏感分類詞彙（作為包名之外的雙重保障）
     */
    private val HIGH_SENSITIVITY_APP_LABEL_KEYWORDS = listOf(
        "證券", "股市", "股票", "期貨", "投資", "樹精靈", "投資先生", "e點通", "大戶投", "三竹", "幣安",
        "銀行", "網銀", "行動銀行", "支付", "錢包", "街口", "富邦", "國泰", "玉山", "中信", "台新",
        "蝦皮", "購物", "商城", "拍賣", "淘寶", "天貓", "京東", "酷澎", "momo", "pchome", "博客來",
        "ubereats", "foodpanda", "外送"
    )

    /**
     * 畫面語義攔截詞彙：當頁面出現付費、下單、結帳、委託買賣、轉帳等文字時，視為高敏感頁面
     */
    private val HIGH_SENSITIVITY_SCREEN_SEMANTICS = listOf(
        "下單", "立即下單", "提交訂單", "確認訂單", "去買單", "結帳", "去結帳", "立即結帳",
        "立即購買", "直接購買", "確認購買", "加入購物車並結帳",
        "確認付款", "立即付款", "前往付款", "付款方式", "信用卡付款", "輸入卡號", "安全碼", "cvv",
        "委託買進", "委託賣出", "買進", "賣出", "下單委託", "零股交易", "整股交易", "銀證轉帳", "交割",
        "確認轉帳", "立即轉帳", "匯款金額", "轉出帳號", "轉入帳號", "交易密碼", "提領", "充值",
        "確認刪除", "永久刪除", "註銷帳號"
    )

    private fun safeElapsedRealtime(): Long {
        return try {
            val t = SystemClock.elapsedRealtime()
            if (t > 0L) t else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    @Synchronized
    fun resetState() {
        lastLowRiskTouchElapsedMs = -10000L
        latestPreTouchSnapshot = null
    }

    /**
     * 於 AI 每一步動作穩定後更新「誤觸前基準快照 (Pre-Touch Anchor Snapshot)」，
     * 確保當真人不小心誤觸時，系統隨時擁有「誤觸前最後一個乾淨正常介面」的包名與畫面指紋。
     */
    @Synchronized
    fun updateCleanPreTouchAnchor(
        packageName: String,
        appLabel: String,
        report: VisionPipelineReport?,
        userGoal: String,
        nowElapsedMs: Long = safeElapsedRealtime()
    ) {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || report == null) return
        val fp = ClosedLoopVerifier.computeScreenFingerprint(cleanPkg, report)
        latestPreTouchSnapshot = PreTouchAnchorSnapshot(
            packageName = cleanPkg,
            appLabel = appLabel.ifBlank { cleanPkg },
            screenFingerprint = fp,
            capturedAtElapsedMs = nowElapsedMs,
            userGoal = userGoal
        )
    }

    /**
     * 【第一層：包名與 App 名稱攔截】
     * 判斷當前 App 是否屬於「股票交易 / 銀行支付 / 電商購物 App」
     * @return 若命中則回傳敏感類別描述（例如 `"股票交易App"`、`"電商購物App"`），否則回傳 `null`
     */
    fun detectHighSensitivityOrShoppingApp(
        packageName: String?,
        appLabel: String? = null
    ): String? {
        val pkgLower = packageName?.trim()?.lowercase().orEmpty()
        val labelLower = appLabel?.trim()?.lowercase().orEmpty()

        // 排除 Google Play 商店等純工具或音樂播放器本身（除非畫面出現付款語義，由第二層攔截）
        val isMusicOrMediaApp = pkgLower.contains("youtube") ||
            pkgLower.contains("spotify") ||
            pkgLower.contains("kkbox") ||
            pkgLower.contains("apple.android.music") ||
            pkgLower.contains("soundcloud") ||
            pkgLower.contains("mymusic") ||
            pkgLower.contains("netflix")
        if (isMusicOrMediaApp) return null

        if (STOCK_TRADING_PACKAGE_TOKENS.any { token -> pkgLower.contains(token.lowercase().trim()) }) {
            return "股票證券交易 App (${appLabel?.takeIf { it.isNotBlank() } ?: pkgLower})"
        }
        if (BANK_AND_PAYMENT_PACKAGE_TOKENS.any { token -> pkgLower.contains(token.lowercase().trim()) }) {
            return "銀行與行動支付 App (${appLabel?.takeIf { it.isNotBlank() } ?: pkgLower})"
        }
        if (SHOPPING_AND_COMMERCE_PACKAGE_TOKENS.any { token -> pkgLower.contains(token.lowercase().trim()) }) {
            return "電商購物下單 App (${appLabel?.takeIf { it.isNotBlank() } ?: pkgLower})"
        }

        if (labelLower.isNotEmpty()) {
            val matchedLabelKw = HIGH_SENSITIVITY_APP_LABEL_KEYWORDS.firstOrNull { kw ->
                labelLower.contains(kw)
            }
            if (matchedLabelKw != null) {
                return "高敏感/購物應用 ($appLabel)"
            }
        }
        return null
    }

    /**
     * 【第二層：畫面語義攔截】
     * 檢查當前頁面是否包含「付費、下單、結帳、立即購買、委託買賣、轉帳」等高敏感操作元件
     */
    fun detectHighSensitivityScreenSemantics(
        report: VisionPipelineReport?,
        @Suppress("UNUSED_PARAMETER") userGoal: String = ""
    ): String? {
        if (report == null) return null

        // 若 SafetyGateManager 已判定為認證碼/密碼/支付驗證頁，直接視為高敏感頁面
        val handoverCheck = SafetyGateManager.detectHumanHandoverRequirement(report)
        if (handoverCheck != null && handoverCheck.first != SafetyGateManager.HandoverCategory.NONE) {
            return "安全驗證畫面 (${handoverCheck.first.displayLabel})"
        }

        for (node in report.finalNodes) {
            val text = "${node.inlineStatePrefix} ${node.label}".trim().lowercase()
            if (text.isEmpty()) continue
            val matchedKw = HIGH_SENSITIVITY_SCREEN_SEMANTICS.firstOrNull { kw ->
                text.contains(kw)
            }
            if (matchedKw != null) {
                return "付費/下單/交易敏感頁面 (偵測到「${node.label}」)"
            }
        }
        return null
    }

    /**
     * 當系統偵測到真人手指觸碰螢幕時，立即執行第一時間仲裁：
     * - 若在股票/銀行/購物 App 或付費下單頁面 -> 回傳 `IMMEDIATE_HARD_STOP`（碰一下就必須停！）
     * - 若在 2.5 秒內連續觸碰第 2 次 -> 回傳 `IMMEDIATE_HARD_STOP`（真人明確要求停止）
     * - 若在聽歌/日常低風險情境首次觸碰 -> 回傳 `INSPECT_ACCIDENTAL_TOUCH`（鎖定誤觸前快照，準備檢查是否跳走並自動跳回）
     */
    @Synchronized
    fun arbitrateOnTouchStart(
        currentPackage: String?,
        currentAppLabel: String?,
        currentReport: VisionPipelineReport?,
        userGoal: String,
        nowElapsedMs: Long = safeElapsedRealtime()
    ): TouchArbitrationResult {
        // 1. 先檢查包名攔截（股票交易 / 銀行支付 / 電商購物 App -> 隨便碰一下立刻停）
        val pkgHit = detectHighSensitivityOrShoppingApp(currentPackage, currentAppLabel)
        if (pkgHit != null) {
            return TouchArbitrationResult(
                verdict = TouchInitialVerdict.IMMEDIATE_HARD_STOP,
                reasonMessage = "[包名安全攔截] 當前位於「$pkgHit」，偵測到您觸碰螢幕，AI 已立即強制停止！"
            )
        }

        // 2. 檢查當前畫面語義攔截（付費 / 下單 / 結帳 / 交易頁面 -> 隨便碰一下立刻停）
        val semanticHit = detectHighSensitivityScreenSemantics(currentReport, userGoal)
        if (semanticHit != null) {
            return TouchArbitrationResult(
                verdict = TouchInitialVerdict.IMMEDIATE_HARD_STOP,
                reasonMessage = "[畫面語義安全攔截] 當前位於「$semanticHit」，偵測到您觸碰螢幕，AI 已立即強制停止！"
            )
        }

        // 3. 檢查是否為 2.5 秒內連續第 2 次觸碰（讓真人在聽歌 App 也能透過連碰兩下強制停止）
        if (nowElapsedMs - lastLowRiskTouchElapsedMs in 1L..DOUBLE_TOUCH_STOP_WINDOW_MS) {
            lastLowRiskTouchElapsedMs = -10000L
            return TouchArbitrationResult(
                verdict = TouchInitialVerdict.IMMEDIATE_HARD_STOP,
                reasonMessage = "偵測到您連續觸碰螢幕，判定為主動要求停止，AI 已立即暫停讓位。"
            )
        }
        lastLowRiskTouchElapsedMs = nowElapsedMs

        // 4. 低風險情境（例如聽歌、一般工具）：鎖定誤觸前快照，進入「誤觸影響檢查與自動跳回」模式
        val cleanPkg = currentPackage?.trim().orEmpty()
        val preSnapshot = latestPreTouchSnapshot?.takeIf {
            nowElapsedMs - it.capturedAtElapsedMs <= 15000L && it.packageName.isNotBlank()
        } ?: if (cleanPkg.isNotBlank() && currentReport != null) {
            PreTouchAnchorSnapshot(
                packageName = cleanPkg,
                appLabel = currentAppLabel?.ifBlank { cleanPkg } ?: cleanPkg,
                screenFingerprint = ClosedLoopVerifier.computeScreenFingerprint(cleanPkg, currentReport),
                capturedAtElapsedMs = nowElapsedMs,
                userGoal = userGoal
            ).also { latestPreTouchSnapshot = it }
        } else {
            null
        }

        return TouchArbitrationResult(
            verdict = TouchInitialVerdict.INSPECT_ACCIDENTAL_TOUCH,
            reasonMessage = "偵測到低風險介面單次觸碰，正在檢查是否為誤觸並準備自動復原...",
            preTouchSnapshot = preSnapshot
        )
    }

    /**
     * 【第四層：誤觸後畫面比對與自動跳回決策】
     * 在誤觸發生 350ms 後擷取新畫面，與 `preTouchSnapshot`（誤觸前的原介面）進行比對：
     * 1. 若誤觸後意外跳進了高敏感付費/購物畫面 -> 立即停止 (`POST_TOUCH_ENTERED_HIGH_RISK_STOP`)
     * 2. 若誤觸導致跳出到其他 App（例如點到廣告跳去 Chrome / 商店） -> 自動跳回原 App (`JUMPED_EXTERNAL_APP_RECOVER`)
     * 3. 若誤觸導致在同 App 內跳到別的介面或點開廣告 -> 自動按返回跳回誤觸前原介面 (`JUMPED_WITHIN_APP_BACK`)
     * 4. 若誤觸沒有產生任何介面跳轉 -> 不用按返回，直接繼續執行 (`NO_IMPACT_CONTINUE`)
     */
    fun evaluatePostTouchImpact(
        preTouchSnapshot: PreTouchAnchorSnapshot?,
        postTouchPackage: String?,
        postTouchAppLabel: String?,
        postTouchReport: VisionPipelineReport?,
        userGoal: String
    ): PostTouchEvaluationResult {
        // 先確認誤觸後的畫面有沒有不小心點進高敏感/付費頁面
        val postPkgRisk = detectHighSensitivityOrShoppingApp(postTouchPackage, postTouchAppLabel)
        if (postPkgRisk != null) {
            return PostTouchEvaluationResult(
                action = PostTouchRecoveryAction.POST_TOUCH_ENTERED_HIGH_RISK_STOP,
                reasonMessage = "觸碰後進入高敏感應用「$postPkgRisk」，AI 已立即安全停止。"
            )
        }
        val postSemanticRisk = detectHighSensitivityScreenSemantics(postTouchReport, userGoal)
        if (postSemanticRisk != null) {
            return PostTouchEvaluationResult(
                action = PostTouchRecoveryAction.POST_TOUCH_ENTERED_HIGH_RISK_STOP,
                reasonMessage = "觸碰後畫面出現「$postSemanticRisk」，AI 已立即安全停止。"
            )
        }

        if (preTouchSnapshot == null || postTouchReport == null) {
            return PostTouchEvaluationResult(
                action = PostTouchRecoveryAction.NO_IMPACT_CONTINUE,
                reasonMessage = "誤觸未改變目標狀態，繼續執行原任務。"
            )
        }

        val prePkg = preTouchSnapshot.packageName.trim()
        val postPkg = postTouchPackage?.trim().orEmpty()

        // 情況 A：誤觸導致跳到別的 App（例如在聽歌時誤點廣告跳到瀏覽器或其他 App）
        if (prePkg.isNotEmpty() && postPkg.isNotEmpty() && postPkg != prePkg) {
            if (InAppOperationPlanner.isUnexpectedExternalAppHijack(postPkg, prePkg)) {
                return PostTouchEvaluationResult(
                    action = PostTouchRecoveryAction.JUMPED_EXTERNAL_APP_RECOVER,
                    reasonMessage = "[誤觸跳出復原] 偵測到誤觸跳轉至外部應用 ($postPkg)，正在自動跳回原應用「${preTouchSnapshot.appLabel}」繼續執行！",
                    targetPackageToRestore = prePkg
                )
            }
        }

        // 情況 B：在同一個 App 內，檢查是否跳到了別的介面或點開了廣告頁面
        val postFp = ClosedLoopVerifier.computeScreenFingerprint(postPkg.ifEmpty { prePkg }, postTouchReport)
        val screenChanged = ClosedLoopVerifier.hasScreenChangedMeaningfully(preTouchSnapshot.screenFingerprint, postFp)
        val hasAdOverlayNow = postTouchReport.finalNodes.any { node ->
            com.example.newandroidapp.ai.PopupDismissalPolicy.isPopupContext(node.label) ||
                com.example.newandroidapp.ai.PopupDismissalPolicy.isDarkPatternCta(node.label) ||
                com.example.newandroidapp.ai.PopupDismissalPolicy.matchAction(node.label)?.let {
                    it.kind == com.example.newandroidapp.ai.PopupDismissalPolicy.Kind.DISMISS && it.priority >= 0.98f
                } == true
        }

        if (screenChanged || hasAdOverlayNow) {
            return PostTouchEvaluationResult(
                action = PostTouchRecoveryAction.JUMPED_WITHIN_APP_BACK,
                reasonMessage = "[誤觸跳頁自動復原] 偵測到誤觸導致畫面跳離原介面（${preTouchSnapshot.screenFingerprint.shortCode()} -> ${postFp.shortCode()}），正在自動按返回鍵跳回誤觸前原介面！",
                targetPackageToRestore = prePkg
            )
        }

        // 情況 C：誤觸沒有產生任何影響（畫面仍停留在原本介面）-> 不用跳回，直接繼續
        return PostTouchEvaluationResult(
            action = PostTouchRecoveryAction.NO_IMPACT_CONTINUE,
            reasonMessage = "[誤觸無影響] 偵測到輕微誤觸但未跳離當前介面，無需返回，直接繼續執行任務。"
        )
    }
}
