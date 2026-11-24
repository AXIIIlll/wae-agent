package com.wae.agent.chat

import android.content.Context
import android.util.Log
import com.wae.core.chat.CorrectionStore
import com.wae.core.chat.KnowledgeBase
import com.wae.core.chat.KnowledgeDoc

/**
 * 檢索語料的組裝層：把「目的／操作／App／修正」變成可檢索文件。
 *
 * 關鍵設計：**每次呼叫模型前都先查**（不管模型想不想查），
 * 所以「忘記查」在架構上不存在。
 */
object KnowledgeHost {

    private const val TAG = "WAE_KB"
    private const val CORRECTIONS_FILE = "corrections.tsv"

    @Volatile
    private var base: KnowledgeBase? = null

    @Volatile
    private var corrections: CorrectionStore? = null

    fun base(context: Context): KnowledgeBase {
        base?.let { return it }
        synchronized(this) {
            base?.let { return it }
            val docs = ArrayList<KnowledgeDoc>()
            docs.addAll(purposeDocs(context))
            docs.addAll(operationDocs(context))
            docs.addAll(appDocs(context))
            docs.addAll(corrections(context).toDocs())
            val built = KnowledgeBase(docs)
            Log.i(TAG, "docs=" + built.size())
            base = built
            return built
        }
    }

    fun corrections(context: Context): CorrectionStore {
        corrections?.let { return it }
        synchronized(this) {
            corrections?.let { return it }
            val file = java.io.File(java.io.File(context.filesDir, "mem"), CORRECTIONS_FILE)
            val loaded = com.wae.core.util.Degrade.orElse("knowledgeHost:corrections", CorrectionStore()) {
                if (file.exists()) CorrectionStore.parse(file.readLines(Charsets.UTF_8).asSequence())
                else CorrectionStore()
            }
            corrections = loaded
            return loaded
        }
    }

    fun recordCorrection(context: Context, utterance: String, action: String) {
        val store = corrections(context)
        store.add(utterance, action)
        com.wae.core.util.Degrade.orNull("knowledgeHost:saveCorrections") {
            val file = java.io.File(java.io.File(context.filesDir, "mem"), CORRECTIONS_FILE)
            file.parentFile?.mkdirs()
            file.writeText(store.toLines().joinToString("\n") + "\n", Charsets.UTF_8)
        }
        // 語料變了要重建，下次檢索才含新修正
        base = null
    }

    /** 每次呼叫模型前的一句話檢索（含修正庫；修正權重 ×10）。 */
    fun contextFor(context: Context, utterance: String, topK: Int = 3): String =
        com.wae.core.util.Degrade.orElse("knowledgeHost:context", "") {
            KnowledgeBase.render(base(context).retrieve(utterance, topK))
        }

    // ── 文件生成 ───────────────────────────────────────────
    private fun purposeDocs(context: Context): List<KnowledgeDoc> {
        // 一個句型一篇文件：句子短、相似度才不會被長文件稀釋
        val docs = ArrayList<KnowledgeDoc>()
        val patterns = HashSet<String>()
        var index = 0
        for (raw in com.wae.core.config.BundledData.commandPurposeLines()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val c = raw.split('\t')
            if (c.size < 2) continue
            val pattern = c[0].trim()
            val purpose = c[1].trim()
            patterns.add(pattern)
            index++
            docs.add(
                KnowledgeDoc(
                    id = "P-" + purpose + "-" + index,
                    kind = "purpose",
                    text = "可以這樣說：$pattern（目的 $purpose）"
                )
            )
        }
        // 動詞／名詞別名：資料在 `ui/purpose_paraphrases.tsv`（同一目的的多種說法）。
        // 已經在句型表的說法仍會跳過，避免重複文件稀釋 idf。
        for (raw in com.wae.core.config.BundledData.purposeParaphraseLines()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val c = raw.split('\t')
            if (c.size < 2) continue
            val purpose = c[0].trim()
            val phrase = c[1].trim()
            if (purpose.isEmpty() || phrase.isEmpty()) continue
            if (phrase in patterns) continue
            index++
            docs.add(
                KnowledgeDoc(
                    id = "P-" + purpose + "-" + index,
                    kind = "purpose",
                    text = "可以這樣說：$phrase（目的 $purpose）"
                )
            )
        }
        // 名詞在前的口語（「Chrome 開起來」「看地圖」）：句型只有動詞在前，這裡用
        // 已安裝 App 名稱具體化成三種說法（打開X／看X／X開起來），全都指 A1.1。
        // 以裝置上的 App 清單生成，不是為某一句話硬編。
        val apps = com.wae.core.util.Degrade.orEmpty("knowledgeHost:apps") {
            com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false)
        }
        for (app in apps) {
            val label = app.label.trim()
            if (label.isEmpty()) continue
            index++
            docs.add(
                KnowledgeDoc(
                    id = "P-A1.1-" + index,
                    kind = "purpose",
                    text = "打開${label}、看${label}、${label}開起來（目的 A1.1 開啟 App）"
                )
            )
        }
        return docs
    }

    private fun operationDocs(context: Context): List<KnowledgeDoc> =
        com.wae.core.config.BundledData.operationLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { raw ->
                val c = raw.split('\t')
                if (c.size < 2) return@mapNotNull null
                val id = c[0].trim()
                KnowledgeDoc(
                    id = "O-$id",
                    kind = "op",
                    text = "操作 $id：前提 ${c[1].trim().ifEmpty { "無" }}"
                )
            }.toList()

    private fun appDocs(context: Context): List<KnowledgeDoc> = com.wae.core.util.Degrade.orEmpty("knowledgeHost:appDocs") {
        com.wae.agent.core.app.InstalledAppCatalog.load(context, force = false).map { app ->
            KnowledgeDoc(
                id = "A-" + app.pkg,
                kind = "app",
                text = "App「${app.label}」分類 ${app.category} 套件 ${app.pkg}"
            )
        }
    }
}
