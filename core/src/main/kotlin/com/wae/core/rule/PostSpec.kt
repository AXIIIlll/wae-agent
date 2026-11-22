package com.wae.core.rule

/**
 * 目的表的 `post` 變成**可執行**的判斷（不是字串）。
 *
 * 為什麼要這個：`post` 寫 `pkg_matches(app)` 但沒人能算，就等於沒有驗證。
 * 這裡把 token 解析成封閉集合，並用「事實」判斷；算不出來就回 null（誠實承認驗不到）。
 *
 * 事實（facts）由 App 層提供，key 就是 token 名稱，例：
 *   pkg=com.android.settings、pkg_label=設定、texts=...、screen_changed=true
 */
sealed class PostSpec {
    data class Pkg(val expected: String) : PostSpec()
    data class TextContains(val expected: String) : PostSpec()
    data class Flag(val key: String) : PostSpec()

    /** 回 true／false／null（null＝這個 token 無法從事實判斷）。 */
    fun holds(facts: Map<String, String>, slots: Map<String, String> = emptyMap()): Boolean? = when (this) {
        is Pkg -> {
            val pkg = facts["pkg"].orEmpty()
            val label = facts["pkg_label"].orEmpty()
            // post 寫 `pkg_matches(app)` 時，app 是槽位名，要用實際值（例「相機」）
            val wanted = slots[expected].orEmpty().ifEmpty { expected }
            when {
                wanted.isBlank() -> if (pkg.isNotEmpty() || label.isNotEmpty()) null else false
                pkg.equals(wanted, true) || label.equals(wanted, true) -> true
                pkg.contains(wanted, true) || label.contains(wanted, true) -> true
                pkg.isEmpty() && label.isEmpty() -> null
                else -> false
            }
        }
        is TextContains -> {
            val texts = facts["texts"].orEmpty()
            val wanted = slots[expected].orEmpty().ifEmpty { expected }
            when {
                texts.isEmpty() -> null
                wanted.isBlank() -> true
                texts.contains(wanted) -> true
                else -> false
            }
        }
        is Flag -> facts[key]?.let { it.equals("true", true) }
    }

    companion object {
        /**
         * 解析 `pkg_matches(app)`、`text_appears`、`video_playing|content_visible`。
         *
         * 有 `|` 代表「任一成立即可」，回傳多個 spec 由呼叫端 OR。
         * 未知 token 回 null（schema 測試會把它變成紅燈）。
         */
        fun parse(token: String): List<PostSpec>? {
            val out = ArrayList<PostSpec>()
            for (part in token.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                val spec = parseOne(part) ?: return null
                out.add(spec)
            }
            return out.ifEmpty { null }
        }

        private fun parseOne(part: String): PostSpec? {
            val name = part.substringBefore('(').trim()
            val arg = if (part.contains('(')) part.substringAfter('(').removeSuffix(")").trim() else ""
            return when (name) {
                "pkg_matches", "foreground_matches" -> PostSpec.Pkg(arg)
                "text_appears", "text_in_thread", "text_in_title", "clipboard_matches" ->
                    PostSpec.TextContains(arg)
                in FLAGS -> PostSpec.Flag(name)
                else -> null
            }
        }

        /** 封閉集合：新增 token 必須同時加判斷器＋測試。 */
        val FLAGS: Set<String> = setOf(
            "screen_changed", "api_ok", "panel_visible", "picker_visible",
            "photo_appeared", "artifact_created", "media_state_changed",
            "content_visible", "results_visible", "route_visible",
            "entry_created", "session_started", "note_created", "task_created",
            "contact_visible", "mail_composed", "call_active", "video_playing",
            "text_available", "value_changed", "state_is", "photo_removed",
            "file_removed"
        )

        /** 任一個成立就算達成。 */
        fun holdsAny(
            specs: List<PostSpec>,
            facts: Map<String, String>,
            slots: Map<String, String> = emptyMap()
        ): Boolean? {
            var unknown = false
            for (spec in specs) {
                when (spec.holds(facts, slots)) {
                    true -> return true
                    null -> unknown = true
                    false -> Unit
                }
            }
            return if (unknown) null else false
        }
    }
}
