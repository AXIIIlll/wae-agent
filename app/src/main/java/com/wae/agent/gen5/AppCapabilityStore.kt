package com.wae.agent.gen5

import android.content.Context
import android.util.Log
import com.wae.core.rule.AppCapabilityTable
import java.io.File

/**
 * 每 App 能力表的落盤層（`files/mem/app_capability.tsv`）。
 *
 * 記的是「這個 App 做過這個目的、成功幾次、失敗幾次」——只存結構，
 * 不含畫面內容或使用者文字；換手機可以整批丟掉重學。
 */
object AppCapabilityStore {

    private const val TAG = "WAE_CAPS"
    private const val FILE = "app_capability.tsv"
    private const val MAX_ROWS = 2000

    @Volatile
    private var cached: AppCapabilityTable? = null

    fun table(context: Context): AppCapabilityTable {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching {
                AppCapabilityTable.parse(lines(context))
            }.getOrDefault(AppCapabilityTable())
            Log.i(TAG, "loaded rows=" + loaded.rows().size)
            cached = loaded
            return loaded
        }
    }

    private fun file(context: Context): File = File(File(context.filesDir, "mem"), FILE)

    private fun lines(context: Context): Sequence<String> {
        val f = file(context)
        if (!f.exists()) return emptySequence()
        return runCatching { f.readLines(Charsets.UTF_8).asSequence() }.getOrDefault(emptySequence())
    }

    fun record(context: Context, pkg: String, purpose: String, ok: Boolean) {
        if (pkg.isBlank() || purpose.isBlank()) return
        val table = table(context)
        table.record(pkg, purpose, ok)
        runCatching { save(context, table) }
        Log.i(TAG, "record pkg=$pkg purpose=$purpose ok=$ok")
    }

    fun supports(context: Context, pkg: String, purpose: String): Boolean? =
        table(context).supports(pkg, purpose)

    fun lacks(context: Context, pkg: String, purpose: String): Boolean =
        table(context).lacks(pkg, purpose)

    private fun save(context: Context, table: AppCapabilityTable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val rows = table.rows().sortedByDescending { it.lastTs }.take(MAX_ROWS)
        val text = "# pkg\tpurpose\tsuccess\tfailure\tlastTs\n" +
            rows.joinToString("\n") {
                listOf(it.pkg, it.purpose, it.success, it.failure, it.lastTs).joinToString("\t")
            } + "\n"
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }

    /** 給 `!facts` 的一行摘要。 */
    fun summary(context: Context): String {
        val rows = table(context).rows()
        if (rows.isEmpty()) return "caps=0"
        val ok = rows.count { it.success > 0 }
        val lack = rows.count { it.success == 0 && it.failure >= AppCapabilityTable.LACK_THRESHOLD }
        return "caps=" + rows.size + " knownOK=" + ok + " knownLack=" + lack
    }
}
