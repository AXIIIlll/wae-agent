package com.wae.agent.core.app

/**
 * 模糊語義 → 意圖框（資料驅動）。
 *
 * 輸入「我想看劇」「導航到台北車站」「幫我查一下匯率」這種**沒有明確 App 與元件**的句子，
 * 輸出一個意圖（play_media / navigate / …）+ 已抽出的槽位；必要槽位缺就回 Ask。
 *
 * 這一層是規則路徑（`decomposeCommandIntoLocalGoals`）接不住時的第一個備援，
 * 也是 LLM 之前的最後一道便宜關卡：能在毫秒內處理的不要丟給 0.6B。
 *
 * 純資料 + 純函式（不碰 Android API），JVM 可測。
 */
object FuzzyIntent {

    data class Frame(
        val intent: String,
        val triggers: List<String>,
        val requiredSlots: List<String>,
        val template: String,
        val ask: String
    )

    sealed class Result {
        /** 意圖成立且槽位齊全。 */
        data class Resolved(
            val frame: Frame,
            val slots: Map<String, String>
        ) : Result()

        /** 意圖成立但缺必要槽位 → 應該反問。 */
        data class Ask(
            val frame: Frame,
            val missing: List<String>,
            val question: String,
            val slots: Map<String, String>
        ) : Result()
    }

    private val frames = ArrayList<Frame>()
    private val slotRegex = LinkedHashMap<String, Pair<Regex, String>>()

    @Volatile
    var loaded: Boolean = false
        private set

    fun resetForTest() {
        synchronized(frames) { frames.clear() }
        synchronized(slotRegex) { slotRegex.clear() }
        loaded = false
    }

    fun loadFrames(lines: Sequence<String>) {
        val parsed = ArrayList<Frame>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 4) continue
            val intent = p[0].trim()
            if (intent.isEmpty()) continue
            val triggers = p[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (triggers.isEmpty()) continue
            val required = p[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val template = p[3].trim()
            val ask = p.getOrNull(4)?.trim().orEmpty()
            parsed.add(Frame(intent, triggers, required, template, ask))
        }
        synchronized(frames) {
            frames.clear()
            frames.addAll(parsed.sortedByDescending { f -> f.triggers.maxOfOrNull { it.length } ?: 0 })
        }
        loaded = true
    }

    fun loadSlots(lines: Sequence<String>) {
        val parsed = LinkedHashMap<String, Pair<Regex, String>>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split('\t')
            if (p.size < 2) continue
            val slot = p[0].trim()
            if (slot.isEmpty()) continue
            val re = runCatching { Regex(p[1].trim()) }.getOrNull() ?: continue
            val ask = p.getOrNull(2)?.trim().orEmpty()
            parsed[slot] = re to ask
        }
        synchronized(slotRegex) {
            slotRegex.clear()
            slotRegex.putAll(parsed)
        }
    }

    fun frameCount(): Int = synchronized(frames) { frames.size }

    fun slotCount(): Int = synchronized(slotRegex) { slotRegex.size }

    /** 把句子抽成槽位（只抽有定義的槽位；抽不到就不放進 map）。 */
    fun extractSlots(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        synchronized(slotRegex) {
            for ((name, pair) in slotRegex) {
                val m = pair.first.find(text) ?: continue
                val v = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (v.isNotEmpty()) out[name] = v
            }
        }
        return out
    }

    /**
     * 主入口：句子的最長觸發詞決定意圖（避免「看片」被「看」吃掉）。
     * 完全沒命中回 null（交給 LLM 或反問）。
     */
    fun resolve(text: String): Result? {
        if (text.isBlank()) return null
        val t = text.trim()
        val slots = extractSlots(t)

        var best: Frame? = null
        var bestLen = 0
        synchronized(frames) {
            for (frame in frames) {
                for (trig in frame.triggers) {
                    if (!t.contains(trig)) continue
                    if (trig.length > bestLen) {
                        best = frame
                        bestLen = trig.length
                    }
                }
            }
        }
        val frame = best ?: return null

        val missing = frame.requiredSlots.filter { slots[it].isNullOrBlank() }
        return if (missing.isEmpty()) {
            Result.Resolved(frame, slots)
        } else {
            val q = frame.ask.ifBlank { "還需要：" + missing.joinToString("、") }
            Result.Ask(frame, missing, q, slots)
        }
    }
}
