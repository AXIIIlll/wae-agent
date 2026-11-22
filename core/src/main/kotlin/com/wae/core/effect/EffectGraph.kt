package com.wae.core.effect

import com.wae.core.model.Effect
import com.wae.core.model.Op

/**
 * 效果圖上的一個節點。
 *
 * `needs` 是資料流：例如「送出訊息」需要「選好的照片」與「選好的聯絡人」。
 * 這是長任務的最小表示（B10）——沒有它，「把照片傳給媽媽」只能被拆成一串互不相關的點擊。
 */
data class EffectNode(
    val id: String,
    val effect: Effect,
    val needs: List<String> = emptyList()
)

/**
 * 多效果計畫（B10）。
 *
 * 只做三件事：**查環、拓撲排序、缺依賴**。執行順序確定，才可能重播。
 */
data class EffectGraph(val nodes: List<EffectNode>) {

    init {
        require(nodes.map { it.id }.toSet().size == nodes.size) { "EffectGraph: 節點 id 重複" }
    }

    /** 缺的依賴（引用了不存在的節點）。 */
    fun missingDependencies(): List<String> {
        val ids = nodes.map { it.id }.toSet()
        return nodes.flatMap { n -> n.needs.filterNot { it in ids }.map { "${n.id} 需要 $it" } }
    }

    /** 有環嗎？（Kahn 排不完就是有環） */
    fun hasCycle(): Boolean = topoOrder().size != nodes.size

    /** 穩定拓撲排序：同層依 id 字典序，保證同輸入同輸出。 */
    fun topoOrder(): List<EffectNode> {
        val byId = nodes.associateBy { it.id }
        val indegree = LinkedHashMap<String, Int>()
        val outgoing = LinkedHashMap<String, MutableList<String>>()
        for (n in nodes) {
            indegree[n.id] = n.needs.count { it in byId }
            for (dep in n.needs) {
                outgoing.getOrPut(dep) { ArrayList() }.add(n.id)
            }
        }
        val ready = indegree.filterValues { it == 0 }.keys.sorted().toMutableList()
        val out = ArrayList<EffectNode>(nodes.size)
        while (ready.isNotEmpty()) {
            val id = ready.removeAt(0)
            byId[id]?.let { out.add(it) }
            for (next in outgoing[id].orEmpty().sorted()) {
                val d = (indegree[next] ?: continue) - 1
                indegree[next] = d
                if (d == 0) {
                    ready.add(next)
                    ready.sort()
                }
            }
        }
        return out
    }

    /** 給人看的一行（收據與 log 用）。 */
    fun describe(): String = topoOrder().joinToString(" → ") { it.id + ":" + it.effect.key() }

    companion object {
        val EMPTY = EffectGraph(emptyList())

        /** 常見的「傳送媒體」組合：選照片 → 選收件人 → 送出。 */
        fun sendMedia(media: Effect, recipient: Effect, send: Effect): EffectGraph = EffectGraph(
            listOf(
                EffectNode("media", media),
                EffectNode("recipient", recipient),
                EffectNode("send", send, needs = listOf("media", "recipient"))
            )
        )

        /** 單一效果的最小圖（給不需要資料流的任務）。 */
        fun single(effect: Effect): EffectGraph = EffectGraph(listOf(EffectNode("e0", effect)))
    }
}
