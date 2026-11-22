package com.wae.agent.core.nlu

import android.content.Context
import com.wae.agent.core.CandidateOption

/**
 * S27：開關狀態推理（能力優先的第 1 步）。
 *
 * 問題：舊版對「關閉藍牙」只看語意相似度，然後盲目點一下，
 * 成功判定只看「畫面變了」。於是：
 *  - 開關本來就是關的 → 多點一下反而打開（做反）
 *  - 點到標題列（不是開關）→ 畫面變了但狀態沒變，仍算成功
 *
 * 正確的做法是**讀狀態 → 決定是否需要動作 → 動作後驗證狀態**：
 *
 * ```
 * desired = parse(cmd)           // 開啟 → ON、關閉 → OFF
 * current = 候選元件的 isChecked  // 來自無障礙節點
 * current == desired → 不需動作（alreadySatisfied）
 * 否則 → 點該開關，並用「狀態真的變成 desired」驗證
 * ```
 *
 * 詞表：`ui_toggle_state_words.tsv`（純語言層）
 */
object ToggleIntent {

    private const val DB_PATH = "open_source_ai_databases/ui_toggle_state_words.tsv"

    data class Intent(val desired: Boolean, val matched: String, val target: String)

    private val OBJECT_MARKERS = listOf("幫我把", "帮忙把", "把", "將", "将")

    private val DEFAULT_ON = listOf("開啟", "打開", "啟用", "啟動", "開", "enable")
    private val DEFAULT_OFF = listOf("關閉", "關掉", "關", "停用", "取消", "disable")

    @Volatile
    private var onWords: List<String> = emptyList()

    @Volatile
    private var offWords: List<String> = emptyList()

    @Volatile
    private var loadAttempted = false

    fun ensureLoaded(context: Context?) {
        if (loadAttempted || context == null) return
        loadAttempted = true
        try {
            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            loadFromLines(lines)
        } catch (_: Throwable) {
            onWords = DEFAULT_ON
            offWords = DEFAULT_OFF
        }
    }

    fun loadFromLines(lines: List<String>) {
        val on = ArrayList<String>()
        val off = ArrayList<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split("\t")
            if (p.size < 2) continue
            val w = p[0].trim()
            if (w.isEmpty()) continue
            when (p[1].trim().uppercase()) {
                "ON" -> on.add(w)
                "OFF" -> off.add(w)
            }
        }
        onWords = (if (on.isEmpty()) DEFAULT_ON else on).sortedByDescending { it.length }
        offWords = (if (off.isEmpty()) DEFAULT_OFF else off).sortedByDescending { it.length }
    }

    fun resetForTest() {
        onWords = emptyList()
        offWords = emptyList()
        loadAttempted = false
    }

    private fun onList(): List<String> = if (onWords.isEmpty()) DEFAULT_ON else onWords
    private fun offList(): List<String> = if (offWords.isEmpty()) DEFAULT_OFF else offWords

    /**
     * 解析「要開還是要關」。全部找不到回 null（＝不是開關指令）。
     * 同時出現時，以**最後出現**的詞為準（「關閉…再開啟」→ 開啟）。
     */
    fun parse(text: String): Intent? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        var bestPos = -1
        var matched = ""
        var desired = false
        for (w in onList()) {
            val p = lower.lastIndexOf(w.lowercase())
            if (p > bestPos) {
                bestPos = p; matched = w; desired = true
            }
        }
        for (w in offList()) {
            val p = lower.lastIndexOf(w.lowercase())
            if (p > bestPos) {
                bestPos = p; matched = w; desired = false
            }
        }
        if (bestPos < 0) return null
        var target = stripWords(text, onList() + offList()).trim()
        // 去掉受詞標記（把/將/幫我把…），否則 target 會變成「把飛航模式」
        for (m in OBJECT_MARKERS) {
            if (target.startsWith(m)) {
                target = target.substring(m.length).trim()
                break
            }
        }
        return Intent(desired = desired, matched = matched, target = target)
    }

    private fun stripWords(text: String, words: List<String>): String {
        var s = text
        for (w in words.sortedByDescending { it.length }) {
            s = s.replace(w, " ")
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 只在「點擊/送出」類目標才解析開關意圖。
     *
     * 為什麼要這層：`打開設定` 這種啟動指令裡也含「開」字，
     * 但它的 actionKind 是 LAUNCH_APP，永遠不該被當成「把某個開關打開」。
     */
    fun parseForToggle(text: String, actionKind: com.wae.agent.core.ActionKind): Intent? {
        if (actionKind != com.wae.agent.core.ActionKind.TAP &&
            actionKind != com.wae.agent.core.ActionKind.SUBMIT
        ) {
            return null
        }
        return parse(text)
    }

    /** 候選是否是「可以被我們安全切換」的開關 */
    fun isToggleCandidate(c: CandidateOption): Boolean =
        c.isCheckable ||
            c.capabilities.contains(com.wae.agent.core.component.Capability.TOGGLE) ||
            c.className.contains("Switch", ignoreCase = true) ||
            c.className.contains("CheckBox", ignoreCase = true) ||
            c.className.contains("ToggleButton", ignoreCase = true)

    /** 候選是否「已經是目標狀態」 */
    fun alreadySatisfied(c: CandidateOption, desired: Boolean): Boolean =
        isToggleCandidate(c) && c.isChecked == desired
}
