package com.wae.agent.core.predicate

import android.content.Context
import com.wae.core.model.Control
import com.wae.core.predicate.TextFold

/**
 * **圖示 → 文字** 轉換器。
 *
 * 為什麼需要：述詞的原子是文字（`LabelContains`），但現代 UI 大量用「純圖示」的入口
 * （設定頁的 Wi-Fi 是一顆圖示、工具列是圖示列）。沒有文字，述詞永遠比對不到，
 * 決策模型再準也沒用。
 *
 * 做法（刻意簡單）：
 *  1. 只轉**已知**圖示——對照表在 `ui_icon_aliases.tsv`，程式碼不內建任何圖示語意。
 *  2. 比對「view id 的結尾名稱」與「class 名稱」，pattern 越長越優先。
 *  3. 通用容器 id（`icon`、`title`、`summary`…）列入排除，避免把每一列都誤標成同一個東西。
 *
 * 這不是圖示分類模型，也不需要神經網路：換一個 OEM 只要補資料列。
 */
object IconTextConverter {

    private const val ASSET = "open_source_ai_databases/ui_icon_aliases.tsv"

    private data class Rule(val pattern: String, val text: String)

    @Volatile private var rules: List<Rule>? = null
    @Volatile private var excluded: Set<String>? = null

    fun ensureLoaded(context: Context) {
        if (rules != null) return
        synchronized(this) {
            if (rules != null) return
            val r = ArrayList<Rule>()
            val ex = HashSet<String>()
            runCatching {
                context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach { raw ->
                        val line = raw.trim('\uFEFF')
                        if (line.isBlank() || line.startsWith("#")) return@forEach
                        if (line.startsWith("!")) {
                            val p = TextFold.fold(line.substring(1))
                            if (p.isNotEmpty()) ex.add(p)
                            return@forEach
                        }
                        val p = line.split('\t')
                        if (p.size < 2) return@forEach
                        val pattern = TextFold.fold(p[0])
                        val text = p[1].trim()
                        if (pattern.isNotEmpty() && text.isNotEmpty()) r.add(Rule(pattern, text))
                    }
                }
            }
            // 長的 pattern 優先（wifi_switch 勝過 wifi）
            rules = r.sortedByDescending { it.pattern.length }
            excluded = ex
        }
    }

    /**
     * 嘗試把一個沒有文字的 [Control] 轉成有文字的。
     *
     * @return 轉換後的 Control；轉不出來就原樣回傳（不猜）。
     */
    fun convert(control: Control): Control {
        if (control.label.isNotBlank()) return control
        val text = textFor(control.key, control.classHint) ?: return control
        return control.copy(
            label = text,
            textHash = com.wae.core.util.TextHash.of(text),
            state = control.state.copy(text = text)
        )
    }

    /** 依 view id / class 猜文字；猜不到回 null。 */
    fun textFor(key: String, classHint: String): String? {
        val rs = rules ?: return null
        val ex = excluded ?: emptySet()

        // key 長成 "id:com.android.settings:id/wifi_switch@120,300"
        val raw = key.substringAfterLast('/', key)
            .substringBefore('@')
        val idName = TextFold.fold(raw)
        if (idName.isEmpty()) return null
        if (ex.any { idName == it }) return null

        val clsName = TextFold.fold(classHint.substringAfterLast('.'))
        for (rule in rs) {
            if (idName.contains(rule.pattern)) return rule.text
        }
        for (rule in rs) {
            if (clsName.contains(rule.pattern)) return rule.text
        }
        return null
    }
}
