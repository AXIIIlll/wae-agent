package com.example.newandroidapp.e2e

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Rect
import com.example.newandroidapp.chat.ChatMessage
import com.example.newandroidapp.chat.ChatRole
import com.example.newandroidapp.chat.ConversationTurnContext
import com.example.newandroidapp.chat.ScreenCandidateItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Shared test doubles, utilities, and invariant assertions for E2E testing.
 */
object TestHelpers {

    // Regex for emojis and unicode dingbats
    private val EMOJI_PATTERN = Pattern.compile(
        "[\\u2600-\\u27BF]|\\p{So}|[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"
    )
    private val DINGBAT_PATTERN = Pattern.compile(
        "[\\u2794\\uD83D\\uDD0D\\u2715\\u2713\\u2605\\u2699\\u2630]"
    )

    fun parseHexColor(hex: String): Int {
        val clean = hex.removePrefix("#")
        val parsed = clean.toLong(16)
        return if (clean.length == 6) {
            (0xFF000000 or parsed).toInt()
        } else {
            parsed.toInt()
        }
    }

    fun assertZeroEmojis(text: String, contextDescription: String = "") {
        assertFalse(
            "Found forbidden emoji in $contextDescription: \"$text\"",
            EMOJI_PATTERN.matcher(text).find()
        )
        assertFalse(
            "Found forbidden dingbat in $contextDescription: \"$text\"",
            DINGBAT_PATTERN.matcher(text).find()
        )
    }

    fun containsEmoji(text: String): Boolean {
        return EMOJI_PATTERN.matcher(text).find() || DINGBAT_PATTERN.matcher(text).find()
    }

    /**
     * Creates an in-memory / temporary-directory test Context on JVM.
     */
    fun createTestContext(tempDir: File): Context {
        if (!tempDir.exists()) tempDir.mkdirs()
        val prefsMap = ConcurrentHashMap<String, Any>()

        val mockPrefs = object : SharedPreferences {
            override fun getAll(): Map<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? =
                prefsMap[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
                @Suppress("UNCHECKED_CAST")
                (prefsMap[key] as? Set<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int =
                (prefsMap[key] as? Number)?.toInt() ?: defValue
            override fun getLong(key: String?, defValue: Long): Long =
                (prefsMap[key] as? Number)?.toLong() ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float =
                (prefsMap[key] as? Number)?.toFloat() ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                (prefsMap[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                private val pending = HashMap<String, Any?>()
                private var clearPending = false
                override fun putString(key: String?, value: String?) = apply { pending[key ?: ""] = value }
                override fun putStringSet(key: String?, values: Set<String>?) = apply { pending[key ?: ""] = values }
                override fun putInt(key: String?, value: Int) = apply { pending[key ?: ""] = value }
                override fun putLong(key: String?, value: Long) = apply { pending[key ?: ""] = value }
                override fun putFloat(key: String?, value: Float) = apply { pending[key ?: ""] = value }
                override fun putBoolean(key: String?, value: Boolean) = apply { pending[key ?: ""] = value }
                override fun remove(key: String?) = apply { pending[key ?: ""] = null }
                override fun clear() = apply { clearPending = true }
                override fun commit(): Boolean {
                    apply()
                    return true
                }
                override fun apply() {
                    if (clearPending) prefsMap.clear()
                    for ((k, v) in pending) {
                        if (v == null) prefsMap.remove(k) else prefsMap[k] = v
                    }
                }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mockPrefs
            override fun getPackageName(): String = "com.example.newandroidapp"
        }
    }

    /**
     * Intent classifier simulator reflecting the specification in PROJECT.md and Survey 2.
     */
    enum class IntentCategory {
        SCREEN_QA,
        TASK_STATUS,
        MATH_CALCULATION,
        FOLLOW_UP,
        PHYSICAL_AUTOMATION,
        GENERAL_CHAT
    }

    data class IntentClassificationResult(
        val category: IntentCategory,
        val isZeroTap: Boolean,
        val targetCandidateIndex: Int? = null,
        val extractedQuery: String? = null
    )

    fun classifyIntent(query: String, context: ConversationTurnContext? = null): IntentClassificationResult {
        val q = query.trim()

        // 1. Math calculation pattern (Zero-Tap)
        if (q.matches(Regex(".*\\d+\\s*[+\\-*/加減乘除]\\s*\\d+.*"))) {
            return IntentClassificationResult(IntentCategory.MATH_CALCULATION, isZeroTap = true)
        }

        // 2. Task status questions (Zero-Tap)
        if (q.contains("剛剛做到") || q.contains("哪一步") || q.contains("做到了哪") || q.contains("目前進度")) {
            return IntentClassificationResult(IntentCategory.TASK_STATUS, isZeroTap = true)
        }

        // 3. Screen Q&A questions (Zero-Tap)
        if (q.contains("畫面上有什麼") || q.contains("畫面重點") || q.contains("有哪些選項") ||
            q.contains("這頁有什麼") || q.contains("畫面有什麼重點") || q.contains("現在畫面")
        ) {
            return IntentClassificationResult(IntentCategory.SCREEN_QA, isZeroTap = true)
        }

        // 4. Multi-turn follow-up answers
        if (q == "好" || q == "確定" || q == "確認" || q == "可以") {
            return IntentClassificationResult(
                IntentCategory.FOLLOW_UP,
                isZeroTap = false,
                targetCandidateIndex = 1
            )
        }
        if (q.contains("第一個") || q.contains("第1個") || q == "選一" || q == "點一") {
            return IntentClassificationResult(
                IntentCategory.FOLLOW_UP,
                isZeroTap = false,
                targetCandidateIndex = 1
            )
        }
        if (q.contains("第二個") || q.contains("第2個") || q == "選二" || q == "點二") {
            return IntentClassificationResult(
                IntentCategory.FOLLOW_UP,
                isZeroTap = false,
                targetCandidateIndex = 2
            )
        }
        if (q.contains("往下滑") || q.contains("找別的")) {
            return IntentClassificationResult(
                IntentCategory.FOLLOW_UP,
                isZeroTap = false,
                extractedQuery = "SCROLL_DOWN"
            )
        }
        if (q.startsWith("改搜") || q.startsWith("改找")) {
            val newQuery = q.removePrefix("改搜").removePrefix("改找").trim()
            return IntentClassificationResult(
                IntentCategory.FOLLOW_UP,
                isZeroTap = false,
                extractedQuery = newQuery
            )
        }

        // 5. Automation command (e.g. "打開 YouTube 搜尋...")
        if (q.startsWith("打開") || q.startsWith("搜尋") || q.contains("點擊") || q.contains("播放")) {
            return IntentClassificationResult(IntentCategory.PHYSICAL_AUTOMATION, isZeroTap = false)
        }

        // Default to General Chat (Zero-Tap)
        return IntentClassificationResult(IntentCategory.GENERAL_CHAT, isZeroTap = true)
    }

    /**
     * Proactive reporting synthesis logic conforming to R2 and PROJECT.md.
     */
    data class ProactiveReportResult(
        val conversationalText: String,
        val spokenText: String,
        val candidates: List<ScreenCandidateItem>,
        val metadataHeader: String
    )

    fun synthesizeProactiveReport(
        actionSummary: String,
        visibleLabels: List<String>,
        isSafetyBlocked: Boolean = false,
        isDeadEnd: Boolean = false
    ): ProactiveReportResult {
        if (isSafetyBlocked) {
            val text = "已暫停操作：偵測到敏感操作（${actionSummary}），請您確認是否繼續或接手。"
            assertZeroEmojis(text, "safety report")
            return ProactiveReportResult(
                conversationalText = text,
                spokenText = text,
                candidates = emptyList(),
                metadataHeader = "安全守門員 · 已暫停"
            )
        }

        if (isDeadEnd) {
            val text = "已停止：目前畫面無法繼續執行（${actionSummary}），請確認後續操作。"
            assertZeroEmojis(text, "dead end report")
            return ProactiveReportResult(
                conversationalText = text,
                spokenText = text,
                candidates = emptyList(),
                metadataHeader = "死胡同 · 已停止"
            )
        }

        val candidates = visibleLabels.take(5).mapIndexed { i, label ->
            val rect = Rect()
            rect.left = 0
            rect.top = (i + 1) * 100
            rect.right = 1080
            rect.bottom = (i + 2) * 100
            ScreenCandidateItem(
                index = i + 1,
                label = label,
                boundsInScreen = rect,
                targetNodeId = "node_$i"
            )
        }

        val itemsSummary = if (candidates.isNotEmpty()) {
            "目前畫面上顯示前幾項結果：\n" + candidates.joinToString("\n") { "【${it.index}】${it.label}" }
        } else {
            "目前畫面無額外結果選項。"
        }

        val followUpPrompt = if (candidates.isNotEmpty()) {
            "請問您要點選哪一項，或是往下滑找別的？"
        } else {
            "請問接下來要進行什麼操作？"
        }

        val fullText = "已為您完成${actionSummary}。\n$itemsSummary\n$followUpPrompt"
        val spoken = "已完成${actionSummary}。$itemsSummary $followUpPrompt"

        assertZeroEmojis(fullText, "proactive full text")
        assertZeroEmojis(spoken, "proactive spoken text")

        return ProactiveReportResult(
            conversationalText = fullText,
            spokenText = spoken,
            candidates = candidates,
            metadataHeader = "剛剛 · 已完成"
        )
    }
}
