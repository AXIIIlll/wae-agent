package com.wae.core.config

/**
 * 可調參數的**鍵名**（I7：不准出現裸字串）。
 *
 * 命名規則見 Gen-5 §19.4：`Key.` 參數鍵、`Default.` 最後防線、`Col.` TSV 欄位、
 * `FileName.` 檔名、`Reason.` 回報字串。
 */
object Key {
    const val STEP_MAX_ATTEMPTS = "step.max_attempts"
    const val STEP_TIMEOUT_MS = "step.timeout_ms"
    const val PLAN_MAX_REPLANS = "plan.max_replans"
    const val PERCEPTION_OCR_A11Y_THRESHOLD = "perception.ocr_a11y_threshold"
    const val REFUTATION_BAN_THRESHOLD = "refutation.ban_threshold"
    const val REFUTATION_TTL_MS = "refutation.ttl_ms"
    const val EXEC_IDEMPOTENCY_WINDOW_MS = "exec.idempotency_window_ms"
    const val ABSTAIN_MIN_CONFIDENCE = "abstain.min_confidence"
    const val SAFETY_CONFIRM_HIGH_RISK = "safety.confirm_high_risk"
    const val GESTURE_SCROLL_DELTA_PX = "gesture.scroll_delta_px"
    const val GEN5_ENABLED = "agent.gen5.enabled"

    /** 找不到目標時，最多捲動幾次（廣度的來源：不是加動詞，是會找）。 */
    const val SEEK_MAX_SCROLLS = "seek.max_scrolls"

    /** 走錯畫面時，最多退回幾層。 */
    const val RECOVER_MAX_BACKS = "recover.max_backs"

    /** 效果路由：沒有資料時的保守先驗（1.0 → 沒資料 p=0.5）。 */
    const val ROUTE_PRIOR_SUCCESS = "route.prior_success"

    /** 被反例降權的路線要乘多少（<1）。 */
    const val ROUTE_LOWER_PENALTY = "route.lower_penalty"

    /** 沒有步數資料時的估計步數（寧可估高，不要樂觀）。 */
    const val ROUTE_FALLBACK_STEPS = "route.fallback_steps"

    /** 合約預算（C4）：一個任務最多花多少。 */
    const val BUDGET_MAX_STEPS = "budget.max_steps"
    const val BUDGET_MAX_WALLCLOCK_MS = "budget.max_wallclock_ms"
    const val BUDGET_MAX_HIGH_RISK = "budget.max_high_risk"

    /** 動作之後等畫面變化的上限與輪詢間隔（C8）。 */
    const val SETTLE_TIMEOUT_MS = "perception.settle_timeout_ms"

    /** 開 App 的等待上限（冷啟動比換頁慢很多，實測 Chrome 在負載下要 >8 秒）。 */
    const val LAUNCH_SETTLE_TIMEOUT_MS = "perception.launch_settle_timeout_ms"
    const val SETTLE_INTERVAL_MS = "perception.settle_interval_ms"

    /** 畫面「變了」之後，再等它安靜下來的時間（慢速載入頁用）。 */
    const val SETTLE_STABLE_MS = "perception.settle_stable_ms"

    /** 系統對話框（權限、安裝…）的套件名片段；蓋在目標 App 上時不該亂點。 */
    const val DIALOG_PKG_HINTS = "agent.dialog_pkg_hints"

    /** 手段成本鍵的前綴：`means.cost.<kind 小寫>`（例：`means.cost.a11y`）。 */
    const val MEANS_COST_PREFIX = "means.cost."
}

/** 參數的最後防線值（讀不到 TSV 時才用；不是真相）。 */
object Default {
    const val MAX_ATTEMPTS = 2
    const val STEP_TIMEOUT_MS = 12_000L
    const val MAX_REPLANS = 2
    const val OCR_A11Y_THRESHOLD = 6
    const val BAN_THRESHOLD = 2
    const val REFUTATION_TTL_MS = 30L * 24 * 60 * 60 * 1000
    const val IDEMPOTENCY_WINDOW_MS = 2_500L
    const val MIN_CONFIDENCE = 0.35f
    const val CONFIRM_HIGH_RISK = true
    const val SCROLL_DELTA_PX = 1_200
    const val GEN5_ENABLED = false
    const val MAX_SCROLLS = 5
    const val MAX_BACKS = 2
    const val ROUTE_PRIOR_SUCCESS = 1.0f
    const val ROUTE_LOWER_PENALTY = 0.6f
    const val ROUTE_FALLBACK_STEPS = 8
    const val BUDGET_MAX_STEPS = 20
    const val BUDGET_MAX_WALLCLOCK_MS = 120_000L
    const val BUDGET_MAX_HIGH_RISK = 1
    const val SETTLE_TIMEOUT_MS = 4_000L

    /** 開 App 專用的等待上限：12 秒（只等到「畫面變了」為止，不是每次都等滿）。 */
    const val LAUNCH_SETTLE_TIMEOUT_MS = 12_000L
    const val SETTLE_INTERVAL_MS = 100L

    /**
     * 預設再等 300ms 確認畫面不再變（避免在骨架屏上動作）。
     *
     * 實測：500ms 會讓每個任務中位數從 1.3s 漲到 2.0s（+60%），
     * 而安靜 300ms 已能擋掉「先變一半」的情況；慢速頁真的還沒好時，
     * 重試機制會再試一次，比「每次都多等 200ms」划算。
     */
    const val SETTLE_STABLE_MS = 300L

    /** 預設的系統對話框套件片段（各版本/廠牌略有不同，用包含比對）。 */
    const val DIALOG_PKG_HINTS = "permissioncontroller"
}

/** TSV 欄位索引（不准寫 `parts[3]`）。 */
object Col {
    const val SCOPE = 0
    const val FRAME_FP = 1
    const val EFFECT = 2
    const val MEANS = 3
    const val VERDICT = 4
    const val ATTRIBUTION = 5
    const val COUNT = 6
    const val LAST_TS = 7

    const val VERB = 0
    const val OP = 1
    const val TARGET_PREFIX = 2
}

/** 落盤檔名（單一記憶體目錄，見 §7）。 */
object FileName {
    const val MEMORY_DIR = "mem"
    const val META = "meta.json"
    const val FRAMES = "frames.jsonl"
    const val FRAMES_TSV = "frames.tsv"
    const val RECEIPTS = "receipts.tsv"
    const val CONTRACTS = "contracts.tsv"
    const val REFUTATIONS = "refutations.tsv"
    const val PROGRAMS = "programs.tsv"
    const val SCHEMA_VERSION = 5
}

/** 回報／反例用的固定字串（不准在程式各處手寫）。 */
object Reason {
    const val NOT_IMPLEMENTED = "not_implemented"
    const val NO_CANDIDATE = "no_candidate"
    const val REPLANS_EXHAUSTED = "replans_exhausted"
    const val LOW_CONFIDENCE = "low_confidence"
    const val SENSITIVE_OPERATION = "sensitive_operation"
    const val UNKNOWN_VERB = "unknown_verb"
    const val TIMEOUT = "timeout"
    const val BLOCKED_BY_CONSTRAINT = "blocked_by_constraint"
    const val ALL_MEANS_BANNED = "all_means_banned"
    const val MEANS_NOT_SUPPORTED = "means_not_supported"
    const val BUDGET_EXCEEDED = "budget_exceeded"
    const val FORBIDDEN_EFFECT = "forbidden_effect"
    const val POPUP_SUSPECTED = "popup_suspected"
    const val ROUTE_LAUNCH_FAILED = "route_launch_failed"
    const val LOGIN_WALL = "login_wall"
    /** 已開啟系統面板，但需要使用者自己切換（Android 10+ 不給第三方直接改 Wi-Fi／藍牙）。 */
    const val PANEL_OPENED = "panel_opened"
    /** 缺少必要權限（例如改亮度需要「修改系統設定」）。 */
    const val PERMISSION_MISSING = "permission_missing"
    /** 缺少必要資訊（例如「傳訊息」沒說對象）：反問並記住答案。 */
    const val MISSING_SLOT = "missing_slot"

    /** 這是一個「查詢」而不是「動作」：我把查到的內容給你，但沒有操作任何東西。 */
    const val INFO = "info"

    /** 使用者主動取消（「取消」「不要」「算了」）：什麼都不做就是正確行為。 */
    const val CANCELLED = "cancelled"

    /** 按了快門，但無法確認照片真的存進相簿（沒有讀取媒體權限）。 */
    const val CAPTURE_UNVERIFIED = "capture_unverified"
}
