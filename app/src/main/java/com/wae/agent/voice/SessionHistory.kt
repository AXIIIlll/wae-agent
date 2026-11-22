package com.wae.agent.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 語音/指令的近期歷史（P0 #4）。
 *
 * 落盤在 `filesDir/voice_session_history.json`，保留最近 20 筆：
 * `{ts, text, voice, success, undoable, undone}`。
 * 用途：助理顯示「上一步」與復原、主畫面顯示最近指令、診斷包。
 */
object SessionHistory {

    private const val FILE = "voice_session_history.json"
    private const val MAX = 20

    data class Entry(
        val ts: Long,
        val text: String,
        val voice: Boolean,
        val success: Boolean?,
        val undoable: Boolean,
        val undone: Boolean
    )

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun loadRaw(context: Context): MutableList<JSONObject> {
        val f = file(context)
        if (!f.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
            list
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    private fun saveRaw(context: Context, list: List<JSONObject>) {
        runCatching {
            val arr = JSONArray()
            list.takeLast(MAX).forEach { arr.put(it) }
            file(context).writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun append(context: Context, text: String, voice: Boolean, undoable: Boolean = true) {
        val list = loadRaw(context)
        list.add(
            JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("text", text)
                .put("voice", voice)
                .put("success", JSONObject.NULL)
                .put("undoable", undoable)
                .put("undone", false)
        )
        saveRaw(context, list)
    }

    @Synchronized
    fun markLastResult(context: Context, success: Boolean) {
        val list = loadRaw(context)
        if (list.isEmpty()) return
        list[list.lastIndex].put("success", success)
        saveRaw(context, list)
    }

    @Synchronized
    fun markLastUndone(context: Context) {
        val list = loadRaw(context)
        if (list.isEmpty()) return
        list[list.lastIndex].put("undone", true)
        saveRaw(context, list)
    }

    fun recent(context: Context, limit: Int = 5): List<Entry> =
        loadRaw(context).takeLast(limit).map { o ->
            Entry(
                ts = o.optLong("ts", 0L),
                text = o.optString("text", ""),
                voice = o.optBoolean("voice", false),
                success = if (o.isNull("success")) null else o.optBoolean("success", false),
                undoable = o.optBoolean("undoable", true),
                undone = o.optBoolean("undone", false)
            )
        }.reversed()

    /** 最近一筆可復原的動作（預設 10 分鐘內） */
    fun lastUndoable(context: Context, maxAgeMs: Long = 10 * 60_000L): Entry? {
        val now = System.currentTimeMillis()
        return recent(context, MAX).firstOrNull {
            it.undoable && !it.undone && !it.text.isBlank() && now - it.ts <= maxAgeMs
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
