package com.wae.agent.core.action

/**
 * 動作契約（Action Contract）——把「執行」從「送出就結束」變成有語意的契約。
 *
 * 每個動作都帶三件事：
 *  1. **意圖鍵（intentKey）**：同一段時間內同一個意圖只執行一次 → 防重複副作用（冪等）
 *  2. **後置驗證（postcondition）**：三態判定，而不是「有動就算成功」
 *  3. **失敗處理**：NO_EFFECT 重試一次；WRONG_EFFECT 立即標紅並回退
 *
 * 借鑑分散式系統的「冪等鍵 + 至少一次 + 補償」，但落在 GUI 動作層，
 * 全部離線、與 App 無關。
 */
enum class ActionEffect {
    /** 動作生效（頁面狀態確實改變，或已導覽到目標） */
    APPLIED,

    /** 動作沒生效（畫面、頁面、套件都沒變）→ 可重試 */
    NO_EFFECT,

    /** 動作產生了非預期結果（例如跳錯 App / 離開目標頁）→ 必須回退 */
    WRONG_EFFECT
}

/** 一次動作的意圖描述（只含結構，不含輸入值） */
data class ActionIntent(
    val pkg: String,
    val action: String,
    val selector: String,
    val posture: String,
    val pageSpine: Long,
    /** INPUT 類的參數槽（用於區分「同一個框輸入不同內容」） */
    val slot: String = ""
) {
    fun key(): String = buildString {
        append(pkg).append('|')
        append(action).append('|')
        append(selector).append('|')
        append(posture).append('|')
        append(pageSpine).append('|')
        append(slot)
    }
}

object ActionContract {

    /**
     * 冪等視窗：同一意圖在此期間內第二次出現 → 視為重複，直接跳過。
     * 2.5 秒足以覆蓋「重試」與「連續兩輪誤點同一個按鈕」，又不會擋掉正常重複操作。
     */
    const val IDEMPOTENCY_WINDOW_MS = 2_500L

    /**
     * 三態後置驗證（純邏輯）。
     *
     * @param expectedPackage 目標 App（有值時可用來偵測「跳錯 App」）
     */
    fun classifyEffect(
        preSpine: Long,
        postSpine: Long,
        prePackage: String,
        postPackage: String,
        preNodeCount: Int = 0,
        postNodeCount: Int = 0,
        expectedPackage: String = ""
    ): ActionEffect {
        val packageChanged = prePackage.isNotEmpty() && postPackage.isNotEmpty() &&
            prePackage != postPackage
        if (packageChanged) {
            val jumpedToExpected = expectedPackage.isNotEmpty() && postPackage == expectedPackage
            return if (jumpedToExpected || expectedPackage.isEmpty()) {
                ActionEffect.APPLIED
            } else {
                ActionEffect.WRONG_EFFECT
            }
        }
        if (preSpine != 0L && postSpine != 0L && preSpine != postSpine) {
            return ActionEffect.APPLIED
        }
        // 頁面哈希沒變：只有「節點數明顯改變」才算有反應（例如輸入框文字變化）
        val nodeDelta = kotlin.math.abs(postNodeCount - preNodeCount)
        if (preNodeCount > 0 && postNodeCount > 0 && nodeDelta >= 3) {
            return ActionEffect.APPLIED
        }
        return ActionEffect.NO_EFFECT
    }
}

/**
 * 冪等帳本（任務內有效，跨任務重置）。
 *
 * 只記「已套用的意圖鍵 + 時間」，不記內容，無隱私負擔。
 */
object ActionLedger {

    private data class Entry(val key: String, val atMs: Long, val effect: ActionEffect)

    private const val MAX_ENTRIES = 128
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun reset() {
        entries.clear()
    }

    /** 該意圖是否在視窗內已成功套用過（= 應跳過） */
    @Synchronized
    fun shouldSkip(
        key: String,
        nowMs: Long,
        windowMs: Long = ActionContract.IDEMPOTENCY_WINDOW_MS
    ): Boolean {
        prune(nowMs, windowMs)
        return entries.any { it.key == key && it.effect == ActionEffect.APPLIED }
    }

    @Synchronized
    fun record(key: String, effect: ActionEffect, nowMs: Long) {
        if (effect != ActionEffect.APPLIED) {
            // 失敗不進冪等帳本（允許重試），但要記錄供遥測
            entries.addLast(Entry(key, nowMs, effect))
            trim()
            return
        }
        entries.addLast(Entry(key, nowMs, effect))
        trim()
    }

    @Synchronized
    fun recentEffects(nowMs: Long, windowMs: Long = 10_000L): List<ActionEffect> {
        prune(nowMs, windowMs)
        return entries.map { it.effect }
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun prune(nowMs: Long, windowMs: Long) {
        while (entries.isNotEmpty() && nowMs - entries.first().atMs > windowMs) {
            entries.removeFirst()
        }
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }
}
