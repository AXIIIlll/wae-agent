package com.wae.core.rule

/**
 * 槽位的**正規名稱 + 別名**（封閉集合）。
 *
 * 為什麼要這個：句型表寫 `query=餐廳`、目的表寫 `place`，
 * 兩邊都沒錯，但**名字不一樣就對不上**——實機就是這樣把「找餐廳」打到舊管線。
 *
 * 規則：
 *  1. 只用這裡的 canonical 名稱當介面。
 *  2. 舊資料的別名（place／keyword／person…）在載入時自動轉成 canonical。
 *  3. 轉不過去就是 bug（schema 測試會紅）。
 */
object Slots {

    const val APP = "app"
    const val TARGET_APP = "target_app"
    const val QUERY = "query"
    const val TEXT = "text"
    const val VALUE = "value"
    const val CONTROL = "control"
    const val CONTACT = "contact"
    const val PLACE = "place"
    const val TIME = "time"
    const val AMOUNT = "amount"
    const val FILE = "file"

    /** 通配槽位：句型用來吃掉「一下」「幫我」這類贅字；不需要目的宣告，也不參與驗證。 */
    val WILDCARDS: Set<String> = setOf("pre", "rest", "prefix", "suffix", "filler", "ignored")

    val ALL: Set<String> = setOf(
        APP, TARGET_APP, QUERY, TEXT, VALUE, CONTROL, CONTACT, PLACE, TIME, AMOUNT, FILE
    )

    /** 別名 → canonical（越前面越常見）。 */
    private val ALIASES: Map<String, String> = mapOf(
        "app" to APP, "appname" to APP, "package" to APP, "pkg" to APP,
        "target_app" to TARGET_APP, "targetapp" to TARGET_APP, "to" to TARGET_APP,
        "query" to QUERY, "keyword" to QUERY, "term" to QUERY, "q" to QUERY,
        "place" to PLACE, "location" to PLACE, "where" to PLACE,
        "text" to TEXT, "content" to TEXT, "message" to TEXT, "body" to TEXT,
        "value" to VALUE, "dir" to VALUE, "direction" to VALUE, "level" to VALUE,
        "control" to CONTROL, "name" to CONTROL, "toggle" to CONTROL,
        "contact" to CONTACT, "person" to CONTACT, "recipient" to CONTACT,
        "time" to TIME, "when" to TIME,
        "amount" to AMOUNT, "number" to AMOUNT, "count" to AMOUNT,
        "file" to FILE, "path" to FILE
    )

    /** 轉成 canonical；不認識就原樣回傳（讓 schema 測試去抓）。 */
    fun canonical(name: String): String =
        ALIASES[name.trim().lowercase()] ?: name.trim()

    fun canonical(list: List<String>): List<String> = list.map { canonical(it) }.distinct()

    fun canonical(map: Map<String, String>): Map<String, String> =
        map.mapKeys { canonical(it.key) }

    fun isKnown(name: String): Boolean = canonical(name) in ALL

    /**
     * 兩個槽位是不是「同一個意思」。
     * 例：place 與 query 都是「使用者要找什麼」，允許互相補位。
     */
    fun compatible(a: String, b: String): Boolean {
        val ca = canonical(a)
        val cb = canonical(b)
        if (ca == cb) return true
        val searchish = setOf(QUERY, PLACE)
        if (ca in searchish && cb in searchish) return true
        return false
    }
}
