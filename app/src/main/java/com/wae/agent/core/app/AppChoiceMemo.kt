package com.wae.agent.core.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 使用者對「意圖類別 → App」的偏好記憶（G）。
 *
 * 為什麼需要：`AppSelector` 只能靠類別、別名、使用量猜；猜錯時使用者說「不是這個」，
 * 系統下次還選同一個。這裡把「喜歡／不喜歡」記下來，直接用分數修正，不必重訓模型。
 *
 * 檔案：`filesDir/app_choice.json`
 * ```json
 * { "VIDEO_MEDIA": { "like": ["Netflix"], "dislike": ["YouTube"] } }
 * ```
 * 只存類別與 App 名稱，不含任何指令內容。
 */
object AppChoiceMemo {

    private const val FILE_NAME = "app_choice.json"
    private const val LIKE_BONUS = 0.8f
    private const val DISLIKE_PENALTY = -2.0f

    private val like = HashMap<String, MutableSet<String>>()
    private val dislike = HashMap<String, MutableSet<String>>()
    private var loaded = false

    /** 最近一次選定的 App（給「不是這個」用）。 */
    @Volatile
    var lastPickCategory: String? = null
        private set

    @Volatile
    var lastPickLabel: String? = null
        private set

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.isFile) return
            val root = JSONObject(f.readText(Charsets.UTF_8))
            for (cat in root.keys()) {
                val obj = root.optJSONObject(cat) ?: continue
                readSet(obj, "like").forEach { like.getOrPut(cat) { HashSet() }.add(it) }
                readSet(obj, "dislike").forEach { dislike.getOrPut(cat) { HashSet() }.add(it) }
            }
        }
    }

    private fun readSet(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    /** 分數修正：喜歡 +0.8、不喜歡 −2.0（足以讓第二名翻盤）。 */
    fun bias(context: Context, category: String, label: String): Float = synchronized(this) {
        load(context)
        if (category.isBlank() || label.isBlank()) return 0f
        val l = like[category]?.contains(label) == true
        val d = dislike[category]?.contains(label) == true
        when {
            d -> DISLIKE_PENALTY
            l -> LIKE_BONUS
            else -> 0f
        }
    }

    @Synchronized
    fun recordPick(category: String, label: String) {
        lastPickCategory = category
        lastPickLabel = label
    }

    @Synchronized
    fun markLike(context: Context, category: String, label: String) {
        load(context)
        like.getOrPut(category) { HashSet() }.add(label)
        dislike[category]?.remove(label)
        save(context)
    }

    @Synchronized
    fun markDislike(context: Context, category: String, label: String) {
        load(context)
        dislike.getOrPut(category) { HashSet() }.add(label)
        like[category]?.remove(label)
        save(context)
    }

    /** 「記住這個／不是這個」：套用到最近一次選擇。回傳被記錄的 (類別, App)。 */
    @Synchronized
    fun applyToLastPick(context: Context, liked: Boolean): Pair<String, String>? {
        load(context)
        val cat = lastPickCategory ?: return null
        val label = lastPickLabel ?: return null
        if (liked) markLike(context, cat, label) else markDislike(context, cat, label)
        return cat to label
    }

    fun likeList(context: Context, category: String): List<String> = synchronized(this) {
        load(context); like[category]?.toList() ?: emptyList()
    }

    fun dislikeList(context: Context, category: String): List<String> = synchronized(this) {
        load(context); dislike[category]?.toList() ?: emptyList()
    }

    fun clear(context: Context) {
        synchronized(this) {
            like.clear()
            dislike.clear()
            loaded = true
            runCatching { File(context.filesDir, FILE_NAME).delete() }
        }
    }

    private fun save(context: Context) {
        runCatching {
            val root = JSONObject()
            val cats = (like.keys + dislike.keys).toSet()
            for (cat in cats) {
                val obj = JSONObject()
                like[cat]?.takeIf { it.isNotEmpty() }?.let { obj.put("like", org.json.JSONArray(it.toList())) }
                dislike[cat]?.takeIf { it.isNotEmpty() }?.let { obj.put("dislike", org.json.JSONArray(it.toList())) }
                if (obj.length() > 0) root.put(cat, obj)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    fun resetForTest() {
        synchronized(this) {
            like.clear()
            dislike.clear()
            loaded = false
            lastPickCategory = null
            lastPickLabel = null
        }
    }
}
