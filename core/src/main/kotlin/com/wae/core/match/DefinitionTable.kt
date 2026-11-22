package com.wae.core.match

/**
 * 一個目標的「一種說法」（多定義的最小單位）。
 *
 * 為什麼不只用同義詞：同一個目標在不同視圖下的長相完全不同——
 * 文字叫「路線」、圖示叫 `directions`、角色是 BUTTON、狀態可能沒有。
 * 只加同義詞永遠補不到「沒有文字的圖示按鈕」（實測：地圖的「路線」卡在第 4 步）。
 */
data class Definition(
    val entity: String,
    val group: String,
    val view: View,
    val value: String,
    val specificity: Float
) {
    /** 定義的視圖種類（不是詞表，是一組固定的證據型態）。 */
    enum class View { TEXT, DESC, ICON, ROLE, STATE, ID }

    companion object {
        fun viewOf(raw: String): View? = when (raw.trim().uppercase()) {
            "TEXT" -> View.TEXT
            "DESC" -> View.DESC
            "ICON" -> View.ICON
            "ROLE" -> View.ROLE
            "STATE" -> View.STATE
            "ID" -> View.ID
            else -> null
        }
    }
}

/** 兄弟群：同群的目標被視為互斥，負向定義由群員自動生成（不必手寫負向清單）。 */
data class DefinitionGroup(val name: String, val exclusivity: Float)

/**
 * 目標多定義表（P0：只解析，不改行為）。
 *
 * 資料由 `tools/build_entity_definitions.py` 從既有的圖示語意表產生，
 * 程式碼不內建任何詞。格式：
 * ```
 * entity <TAB> group <TAB> view <TAB> value <TAB> specificity <TAB> note
 * ```
 *
 * 防呆（沿用 `ConfigParser` 的紀律）：剝 BOM、跳註解與壞列、未知 view 直接略過，
 * 不拋例外——資料表壞掉時應該是「少一個定義」，不是「整個 App 起不來」。
 */
class DefinitionTable(
    private val byEntity: Map<String, List<Definition>>,
    val groups: Map<String, DefinitionGroup> = emptyMap()
) {

    val entities: Set<String> get() = byEntity.keys

    fun definitionsOf(entity: String): List<Definition> = byEntity[entity].orEmpty()

    fun definitionsByView(entity: String, view: Definition.View): List<Definition> =
        definitionsOf(entity).filter { it.view == view }

    fun groupOf(entity: String): DefinitionGroup? =
        definitionsOf(entity).firstOrNull()?.let { groups[it.group] }

    /** 同群的其他目標（負向候選）；不含自己。 */
    fun siblingsOf(entity: String): List<String> {
        val def = definitionsOf(entity).firstOrNull() ?: return emptyList()
        return byEntity.keys.filter { it != entity && definitionsOf(it).any { d -> d.group == def.group } }
    }

    fun definitionCount(): Int = byEntity.values.sumOf { it.size }

    /** 依「籠統程度」排序的定義（越具體越前面）；同分時 TEXT 優先於 ICON 之外的其他視圖。 */
    fun ranked(entity: String): List<Definition> =
        definitionsOf(entity).sortedWith(
            compareByDescending<Definition> { it.specificity }.thenBy { it.view.ordinal }
        )

    companion object {

        private const val MIN_COLUMNS = 5
        private const val BOM = '\uFEFF'
        private const val COMMENT = '#'

        fun parse(
            definitionLines: Sequence<String>,
            groupLines: Sequence<String> = emptySequence()
        ): DefinitionTable {
            val acc = LinkedHashMap<String, MutableList<Definition>>()
            for (raw in definitionLines) {
                val line = raw.trimStart(BOM).trim()
                if (line.isEmpty() || line.startsWith(COMMENT)) continue
                val cells = line.split('\t')
                if (cells.size < MIN_COLUMNS) continue
                val entity = cells[0].trim()
                val group = cells[1].trim()
                val view = Definition.viewOf(cells[2]) ?: continue
                val value = cells[3].trim()
                if (entity.isEmpty() || value.isEmpty()) continue
                val spec = (cells[4].trim().toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
                acc.getOrPut(entity) { ArrayList() }
                    .add(Definition(entity, group.ifEmpty { "misc" }, view, value, spec))
            }

            val groups = LinkedHashMap<String, DefinitionGroup>()
            for (raw in groupLines) {
                val line = raw.trimStart(BOM).trim()
                if (line.isEmpty() || line.startsWith(COMMENT)) continue
                val cells = line.split('\t')
                if (cells.size < 2) continue
                val name = cells[0].trim()
                if (name.isEmpty()) continue
                val exclusivity = (cells[1].trim().toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
                groups[name] = DefinitionGroup(name, exclusivity)
            }

            return DefinitionTable(acc.mapValues { it.value.toList() }, groups)
        }
    }
}
