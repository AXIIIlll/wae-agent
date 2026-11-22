package com.wae.core.memory

import com.wae.core.config.Config
import com.wae.core.config.Default
import com.wae.core.config.Key
import com.wae.core.model.Attribution
import com.wae.core.model.Constraints
import com.wae.core.model.Frame
import com.wae.core.model.Refutation
import com.wae.core.model.Verdict
import com.wae.core.time.Clock

/**
 * 反例日誌（Gen-5 §4.6 / §7）。
 *
 * 兩條統計紀律（避免一次倒楣就永久禁用）：
 *  1. 同一 `(scope, frameFp, effect, means)` 累積到 `refutation.ban_threshold` 才升 `BAN`，
 *     之前都是 `LOWER`（降權，不是禁用）。
 *  2. 成功一次 `count--`，歸零即移除。
 *
 * 反例的鍵不含使用者內容：只有畫面指紋、效果鍵、手段描述。
 */
class RefutationLog(
    private val config: Config,
    private val clock: Clock
) {

    private val byKey = LinkedHashMap<String, Refutation>()

    fun record(
        scope: String,
        frameFp: String,
        effectKey: String,
        means: String,
        attribution: Attribution,
        appVersion: Long = 0L,
        nowMs: Long = clock.nowMs()
    ): Refutation {
        val key = Refutation.keyOf(scope, frameFp, effectKey, means)
        val previous = byKey[key]
        val count = (previous?.count ?: 0) + 1
        val updated = Refutation(
            scope = scope,
            frameFp = frameFp,
            effect = effectKey,
            means = means,
            verdict = verdictFor(count),
            attribution = attribution,
            count = count,
            lastTs = nowMs,
            appVersion = appVersion
        )
        byKey[key] = updated
        return updated
    }

    /**
     * 直接判定「這步錯了」（G7：使用者糾正）。
     *
     * 跟自動累積的差別：這裡**一次就到 BAN**，因為是人說的，不是統計猜的。
     * 但仍然只作用在同一個（畫面 × 效果 × 手段）上——人的糾正也要有範圍，
     * 否則「這次不對」會變成「以後都不准」。
     */
    fun ban(
        scope: String,
        frameFp: String,
        effectKey: String,
        means: String,
        attribution: Attribution,
        appVersion: Long = 0L,
        nowMs: Long = clock.nowMs()
    ): Refutation {
        val key = Refutation.keyOf(scope, frameFp, effectKey, means)
        val previous = byKey[key]
        val count = maxOf(previous?.count ?: 0, config.int(Key.REFUTATION_BAN_THRESHOLD, Default.BAN_THRESHOLD))
        val updated = Refutation(
            scope = scope,
            frameFp = frameFp,
            effect = effectKey,
            means = means,
            verdict = Verdict.BAN,
            attribution = attribution,
            count = count,
            lastTs = nowMs,
            appVersion = appVersion
        )
        byKey[key] = updated
        return updated
    }

    /** 成功一次就減一（可恢復：避免偶發失敗變永久禁令）。 */
    fun noteSuccess(scope: String, frameFp: String, effectKey: String, means: String) {
        val key = Refutation.keyOf(scope, frameFp, effectKey, means)
        val previous = byKey[key] ?: return
        if (previous.count <= 1) {
            byKey.remove(key)
        } else {
            byKey[key] = previous.copy(count = previous.count - 1)
        }
    }

    fun all(): List<Refutation> = byKey.values.toList()

    /** 手動刪除一條（使用者說「這條是錯的」）。 */
    fun remove(key: String): Boolean = byKey.remove(key) != null

    fun findByKey(key: String): Refutation? = byKey[key]

    fun load(rows: List<Refutation>) {
        byKey.clear()
        for (row in rows) byKey[row.key()] = row
    }

    /** 這一幀適用的約束（只收未過期的 BAN / NEED_CONFIRM）。 */
    fun constraintsFor(frame: Frame, nowMs: Long = clock.nowMs()): Constraints {
        val banned = HashSet<String>()
        val confirm = HashSet<String>()
        for (ref in byKey.values) {
            if (expired(ref, nowMs)) continue
            if (!appliesTo(ref, frame)) continue
            when (ref.verdict) {
                Verdict.BAN -> banned.add(Constraints.pair(ref.effect, ref.means))
                Verdict.NEED_CONFIRM -> confirm.add(Constraints.pair(ref.effect, ref.means))
                Verdict.LOWER -> Unit // 降權交給 solver 的成本表，不進約束
            }
        }
        return Constraints(banned = banned, confirm = confirm)
    }

    private fun verdictFor(count: Int): Verdict =
        if (count >= banThreshold()) Verdict.BAN else Verdict.LOWER

    private fun banThreshold(): Int =
        config.int(Key.REFUTATION_BAN_THRESHOLD, Default.BAN_THRESHOLD)

    private fun ttlMs(): Long =
        config.long(Key.REFUTATION_TTL_MS, Default.REFUTATION_TTL_MS)

    private fun expired(ref: Refutation, nowMs: Long): Boolean =
        nowMs - ref.lastTs > ttlMs()

    private fun appliesTo(ref: Refutation, frame: Frame): Boolean = when {
        ref.scope == SCOPE_GLOBAL -> true
        ref.scope == SCOPE_APP_PREFIX + frame.pkg -> true
        ref.scope == SCOPE_FRAME_PREFIX + frame.fp -> true
        else -> false
    }

    companion object {
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_APP_PREFIX = "app:"
        const val SCOPE_FRAME_PREFIX = "frame:"
    }
}
