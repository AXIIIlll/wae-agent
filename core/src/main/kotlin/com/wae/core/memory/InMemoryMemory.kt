package com.wae.core.memory

import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.model.Attribution
import com.wae.core.model.Constraints
import com.wae.core.model.Frame
import com.wae.core.model.Outcome
import com.wae.core.model.Refutation
import com.wae.core.model.Step

/**
 * 記憶體（記憶體內版）：JVM 測試與「還沒有裝置」時的預設實作。
 *
 * 容量有上限（`Key` 對應的 `Default.MAX_FRAMES`），超量時丟最舊的——
 * 記憶體不是資料庫，Frame Ledger 只需要「最近看過的畫面」。
 */
class InMemoryMemory(
    config: com.wae.core.config.Config,
    clock: com.wae.core.time.Clock,
    private val maxFrames: Int = 256
) : Memory {

    private val log = RefutationLog(config, clock)

    private val frames = object : LinkedHashMap<String, Frame>(DEFAULT_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Frame>): Boolean =
            size > maxFrames
    }

    override fun rememberFrame(frame: Frame) {
        frames[frame.fp] = frame
    }

    override fun frameOf(fp: String): Frame? = frames[fp]

    override fun recordFailure(
        step: Step,
        outcome: Outcome,
        attribution: Attribution,
        frame: Frame
    ): Refutation = log.record(
        scope = scopeFor(frame),
        frameFp = frame.fp,
        effectKey = step.effect.key(),
        means = step.means.describe(),
        attribution = attribution,
        appVersion = frame.appVersion
    )

    private var silentCount: Int = 0

    override fun recordSilentFailure(step: Step, frame: Frame) {
        silentCount++
    }

    override fun silentFailures(): Int = silentCount

    override fun noteStepSuccess(step: Step, frame: Frame) {
        log.noteSuccess(
            scope = scopeFor(frame),
            frameFp = frame.fp,
            effectKey = step.effect.key(),
            means = step.means.describe()
        )
    }

    override fun constraintsFor(frame: Frame): Constraints = log.constraintsFor(frame)

    override fun refutations(): List<Refutation> = log.all()

    override fun ban(effectKey: String, means: String, frameFp: String): Boolean {
        if (effectKey.isEmpty() || means.isEmpty() || frameFp.isEmpty()) return false
        log.ban(
            // 使用者糾正用全域 scope：effect 鍵本身已含目標（例 NAVIGATE:app:日曆），
            // 用畫面指紋當範圍反而會失效——因為規劃時看的是「我們自己的畫面」。
            scope = com.wae.core.memory.RefutationLog.SCOPE_GLOBAL,
            frameFp = frameFp,
            effectKey = effectKey,
            means = means,
            attribution = Attribution.LOCATION
        )
        return true
    }

    override fun removeRefutation(key: String): Boolean = log.remove(key)

    /** 由 `FileMemory` 載入既有反例（同樣走 log，確保統計規則一致）。 */
    fun loadRefutations(rows: List<Refutation>) = log.load(rows)

    override fun flush() = Unit

    private fun scopeFor(frame: Frame): String =
        if (frame.fp.isEmpty()) RefutationLog.SCOPE_GLOBAL
        else RefutationLog.SCOPE_FRAME_PREFIX + frame.fp

    companion object {
        private const val DEFAULT_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
