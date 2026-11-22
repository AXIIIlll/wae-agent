package com.wae.core.rule

/**
 * 名詞 → 型別 → App 類別（最後一層 fallback）。
 *
 * 為什麼需要：句型表只認得寫過的名詞（找餐廳、找加油站…）。
 * 使用者說「我想找 AI 資料」時，沒有任何句型命中，舊管線也不認識，只能失敗。
 * 這一層把**名詞**對到型別（資料→INFO、醫院→PLACE），再把型別對到 App 類別，
 * 於是「找 AI 資料」就會變成「用瀏覽器搜尋」。
 *
 * 只接在規則／路由都失敗之後，所以不會改變既有成功的路徑。
 */
class NounResolver(
    /** 詞 → 型別（例：醫院 → PLACE）。 */
    private val objects: Map<String, String>,
    /** 型別 → App 類別與動作。 */
    private val typeApps: Map<String, TypeApps>,
    /** 動詞 → 預設型別（名詞查不到時用）。 */
    private val verbs: Map<String, String>
) {

    data class TypeApps(
        val type: String,
        val categories: List<String>,
        /** search：進 App 後搜尋；open：只開 App；contact：找人。 */
        val action: String,
        val note: String = "",
        /** 給設定畫面看的中文標籤（例：地點）。 */
        val label: String = ""
    )

    data class Resolution(
        /** 型別（PLACE／INFO／PERSON…）。 */
        val type: String,
        /** 命中的名詞（沒有名詞時是動詞或整句）。 */
        val noun: String,
        val verb: String,
        val categories: List<String>,
        val action: String,
        /** noun＝名詞命中、verb＝用動詞預設型別。 */
        val matchedBy: String
    )

    fun resolve(command: String): Resolution? {
        val text = normalize(command)
        if (text.isEmpty()) return null

        // 動詞：取最長命中
        val verb = verbs.keys.filter { text.contains(it) }.maxByOrNull { it.length }.orEmpty()

        // 名詞 span：把動詞拿掉，去掉語助詞
        var nounSpan = if (verb.isEmpty()) text else text.replaceFirst(verb, "")
        nounSpan = nounSpan.trim(' ', '，', ',', '。', '！', '!', '？', '?', '、')
        for (particle in PARTICLES) {
            if (nounSpan.startsWith(particle)) nounSpan = nounSpan.removePrefix(particle)
            if (nounSpan.endsWith(particle)) nounSpan = nounSpan.removeSuffix(particle)
        }
        nounSpan = nounSpan.trim()

        // 名詞：取「在 span 裡出現且最長」的詞
        val noun = objects.keys
            .filter { it.length >= 2 && nounSpan.contains(it) }
            .maxByOrNull { it.length }

        val type = noun?.let { objects[it] }
            ?: verb.takeIf { it.isNotEmpty() }?.let { verbs[it] }
            ?: return null
        val apps = typeApps[type] ?: return null
        val matchedNoun = noun ?: nounSpan.ifEmpty { text }
        return Resolution(
            type = type,
            noun = matchedNoun,
            verb = verb,
            categories = apps.categories,
            action = apps.action,
            matchedBy = if (noun != null) "noun" else "verb"
        )
    }

    fun types(): Set<String> = typeApps.keys

    companion object {
        /** 語助詞／量詞：不影響要找什麼。 */
        private val PARTICLES = listOf(
            "一個", "一個的", "那個", "這個", "一些", "一下", "的", "了", "吧", "呢", "啊",
            "附近", "附近有沒有", "有沒有", "哪裡有", "哪邊有"
        )

        private fun normalize(command: String): String {
            var text = com.wae.core.intent.Politeness.strip(command).trim()
            text = com.wae.core.intent.Conditional.strip(text)
            for (filler in RuleEngine.FILLERS) text = text.replace(filler, "")
            return text.trim()
        }

        fun parse(
            objectLines: Sequence<String>,
            typeAppLines: Sequence<String>,
            verbLines: Sequence<String>
        ): NounResolver {
            val objects = LinkedHashMap<String, String>()
            val priorities = HashMap<String, Int>()
            for (raw in objectLines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 2) continue
                val word = c[0].trim()
                val type = c[1].trim().uppercase()
                val prio = c.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
                if (word.length < 2 || type.isEmpty()) continue
                // 同一個詞有多個型別時，優先權高的贏（手寫 > 詞庫）
                if (prio >= (priorities[word] ?: Int.MIN_VALUE)) {
                    objects[word] = type
                    priorities[word] = prio
                }
            }
            val typeApps = LinkedHashMap<String, TypeApps>()
            for (raw in typeAppLines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 2) continue
                val type = c[0].trim().uppercase()
                val cats = c[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (type.isEmpty() || cats.isEmpty()) continue
                typeApps[type] = TypeApps(
                    type = type,
                    categories = cats,
                    action = c.getOrNull(2)?.trim().orEmpty().ifEmpty { "search" },
                    note = c.getOrNull(3)?.trim().orEmpty(),
                    label = c.getOrNull(4)?.trim().orEmpty().ifEmpty { type }
                )
            }
            val verbs = LinkedHashMap<String, String>()
            for (raw in verbLines) {
                val line = raw.trimStart('\uFEFF').trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 2) continue
                val verb = c[0].trim()
                val type = c[1].trim().uppercase()
                if (verb.isNotEmpty() && type.isNotEmpty()) verbs[verb] = type
            }
            return NounResolver(objects, typeApps, verbs)
        }
    }
}
