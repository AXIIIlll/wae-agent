package com.wae.core.screen

import com.wae.core.model.Control

/**
 * 把一份 [com.wae.core.model.Frame] 的控制項，簡化成 [ScreenModel]。
 *
 * 規則（刻意保守，寧可少列也不要列錯）：
 *  1. 只有 `clickable == true` 的控制項才是選項（可點／可聚焦／祖先可點，由擷取層判定）。
 *  2. 沒有標籤的可互動元素仍然列入，label 用角色名稱——「一個沒有名字的可點區塊」仍是資訊。
 *  3. 不可互動但有文字的元素 → `contextLines`（標題、區塊名、說明）。
 *  4. 群組＝垂直方向上「最近的上方唯讀標題」；距離超過門檻就不算。
 *  5. id 用「角色＋標籤＋群組＋同標籤序號」推導，**不含座標**，滑動後仍穩定。
 *
 * [locate] 用同一套規則回推「這個選項是哪一個控制項」，所以抽取與定位不會各寫一份而走鐘。
 */
object OptionExtractor {

    const val DEFAULT_MAX_OPTIONS = 60
    const val DEFAULT_MAX_CONTEXT = 12

    /** 標題離選項多遠就不算同一組（以螢幕高度比例計）。 */
    private const val GROUP_MAX_DISTANCE_RATIO = 0.25f

    /** 選項與它對應的控制項（同一個順序）。 */
    data class Scanned(
        val model: ScreenModel,
        val pairs: List<Pair<Control, ScreenOption>>
    )

    fun from(
        packageName: String,
        fingerprint: String,
        controls: List<Control>,
        screenHeightPx: Int = 0,
        maxOptions: Int = DEFAULT_MAX_OPTIONS,
        maxContextLines: Int = DEFAULT_MAX_CONTEXT
    ): ScreenModel = scan(packageName, fingerprint, controls, screenHeightPx, maxOptions, maxContextLines).model

    /** 用與 [from] 完全相同的規則，找出某個選項對應到哪一個控制項。 */
    fun locate(
        controls: List<Control>,
        optionId: String,
        screenHeightPx: Int = 0,
        maxOptions: Int = DEFAULT_MAX_OPTIONS
    ): Control? = scan("", "", controls, screenHeightPx, maxOptions, DEFAULT_MAX_CONTEXT)
        .pairs.firstOrNull { it.second.id == optionId }?.first

    fun scan(
        packageName: String,
        fingerprint: String,
        controls: List<Control>,
        screenHeightPx: Int = 0,
        maxOptions: Int = DEFAULT_MAX_OPTIONS,
        maxContextLines: Int = DEFAULT_MAX_CONTEXT
    ): Scanned {
        if (controls.isEmpty()) {
            return Scanned(ScreenModel(packageName, fingerprint, emptyList(), emptyList()), emptyList())
        }

        // 依畫面順序（上→下、左→右）處理，群組判定才穩定
        val sorted = controls.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val height = if (screenHeightPx > 0) screenHeightPx else
            (sorted.maxOfOrNull { it.bounds.bottom } ?: 1).coerceAtLeast(1)
        val groupDistance = (height * GROUP_MAX_DISTANCE_RATIO).toInt().coerceAtLeast(120)

        data class Header(val text: String, val bottom: Int)

        val headers = ArrayList<Header>()
        val pairs = ArrayList<Pair<Control, ScreenOption>>()
        val context = ArrayList<String>()
        val ordinalByLabel = HashMap<String, Int>()

        for (c in sorted) {
            val label = c.label.trim()
            val role = c.kind.name
            val w = (c.bounds.right - c.bounds.left).coerceAtLeast(0)
            val h = (c.bounds.bottom - c.bounds.top).coerceAtLeast(0)
            if (w == 0 || h == 0) continue

            if (!c.clickable) {
                if (label.isNotEmpty() && context.size < maxContextLines && label.length <= 40) {
                    context.add(label)
                    // 短文字才可能是區塊標題（長句子通常是說明）
                    if (label.length <= 12) headers.add(Header(label, c.bounds.bottom))
                }
                continue
            }

            if (pairs.size >= maxOptions) break

            val group = headers.lastOrNull { c.bounds.top - it.bottom in 0..groupDistance }?.text.orEmpty()
            val display = label.ifEmpty { roleLabel(role) }
            val ordinal = (ordinalByLabel[display] ?: 0) + 1
            ordinalByLabel[display] = ordinal

            val option = ScreenOption(
                id = optionId(role, display, group, ordinal),
                label = display,
                role = role,
                group = group,
                source = c.source.name,
                enabled = c.enabled,
                checked = c.state.checked,
                left = c.bounds.left,
                top = c.bounds.top,
                right = c.bounds.right,
                bottom = c.bounds.bottom,
                viewId = c.viewId,
                layer = c.layer,
                inWeb = c.inWeb,
                ordinal = ordinal
            )
            pairs.add(c to option)
        }

        val model = ScreenModel(packageName, fingerprint, pairs.map { it.second }, context)
        return Scanned(model, pairs)
    }

    /**
     * 穩定 id：角色＋標籤＋群組＋序號。**刻意不含座標**——
     * 用座標當 id 的話，畫面一滑動 id 就全部變了，記錄與重播都會失效。
     */
    fun optionId(role: String, label: String, group: String, ordinal: Int): String {
        val raw = "$role|$label|$group|$ordinal"
        var h = 1125899906842597L
        for (ch in raw) h = 31 * h + ch.code
        return "opt" + java.lang.Long.toHexString(h and 0x7FFFFFFFFFFFFFFFL)
    }

    private fun roleLabel(role: String): String = when (role) {
        "BUTTON", "TAB", "ROW", "ITEM" -> "（無名稱的$role）"
        "EDITABLE", "FIELD" -> "輸入框"
        "TOGGLE", "SWITCH", "CHECKBOX" -> "開關"
        else -> "（無名稱的$role）"
    }
}
