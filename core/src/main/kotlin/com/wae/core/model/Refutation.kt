package com.wae.core.model

/** 反例的處置方式。 */
enum class Verdict {
    /** 永久禁止（誤殺風險高，統計紀律見 §7） */
    BAN,

    /** 降權：可用，但排到後面 */
    LOWER,

    /** 需要人工確認才可走 */
    NEED_CONFIRM
}

/**
 * 反例：一次失敗的**結構化痕跡**（I3）。
 *
 * 設計來自既有的 `NegativeEdgeStore`，Gen-5 加兩件事：
 *  1. `attribution`（五類歸因）
 *  2. `scope` 可以是 app / frame / global
 */
data class Refutation(
    val scope: String,
    val frameFp: String,
    val effect: String,
    val means: String,
    val verdict: Verdict,
    val attribution: Attribution,
    val count: Int,
    val lastTs: Long,
    /** 這條反例是哪一版 App 學到的（版本戳；改版後可失效）。 */
    val appVersion: Long = 0L
) {

    init {
        require(scope.isNotEmpty()) { "Refutation.scope 不可為空" }
        require(count > 0) { "Refutation.count 必須 > 0" }
    }

    fun key(): String = scope + SEP + frameFp + SEP + effect + SEP + means

    companion object {
        private const val SEP = "\u0001"

        fun keyOf(scope: String, frameFp: String, effect: String, means: String): String =
            scope + SEP + frameFp + SEP + effect + SEP + means
    }
}

/**
 * 反例庫對「這一幀」施加的約束。求解手段時必須全部滿足。
 *
 * 鍵＝`effectKey|meansDesc`：同一個手段對不同效果可以有不同處置
 * （例如「手勢」對「開啟設定」可行，對「轉帳」被禁）。
 */
data class Constraints(
    val banned: Set<String> = emptySet(),
    val confirm: Set<String> = emptySet()
) {

    fun isBanned(effectKey: String, meansDesc: String): Boolean = pair(effectKey, meansDesc) in banned
    fun needsConfirm(effectKey: String, meansDesc: String): Boolean = pair(effectKey, meansDesc) in confirm

    operator fun plus(other: Constraints): Constraints = Constraints(
        banned = banned + other.banned,
        confirm = confirm + other.confirm
    )

    companion object {
        val NONE: Constraints = Constraints()
        private const val SEP = "\u0001"

        fun pair(effectKey: String, meansDesc: String): String = effectKey + SEP + meansDesc
    }
}
