package com.wae.agent.dev

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.wae.agent.core.WaeVisionAccessibilityService

/**
 * 驗證題庫（開發用）：把 `ui_benchmark_tasks.tsv` 的每一題變成一列。
 *
 * 兩個硬規則（使用者要求）：
 *  1. **可以選題**：點一列只是選中，不会馬上執行；下方才有「複製／執行／通過／失敗」。
 *  2. **只能照順序由簡到難**：未通過的題目會鎖住，只能做「下一題」；
 *     按「通過」才解鎖下一題，按「失敗」留在原地重試。進度存在 SharedPreferences。
 *
 * 為什麼要鎖：一次跳著測，失敗時分不清是「這題太難」還是「前一個能力沒做好」。
 * 資料驅動：題目在 assets 的 TSV，順序也在資料裡（EASY → MEDIUM → HARD → EXTREME）。
 */
class BenchmarkTasksActivity : Activity() {

    private data class Task(val id: String, val tier: String, val command: String)

    private val tasks = ArrayList<Task>()
    private var selected = -1
    private var nextIndex = 0

    private lateinit var adapter: RowAdapter
    private lateinit var progressView: TextView
    private lateinit var selectionView: TextView
    private lateinit var copyBtn: Button
    private lateinit var runBtn: Button
    private lateinit var passBtn: Button
    private lateinit var failBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadTasks()
        nextIndex = prefs().getInt(KEY_NEXT, 0).coerceIn(0, tasks.size)
        selected = if (nextIndex < tasks.size) nextIndex else tasks.size - 1

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            // 按鈕不能壓在導覽列底下：貼著螢幕最底部的那 100px 會被系統攔掉，
            // 實測「點了沒反應」就是這個原因。
            fitsSystemWindows = true
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "驗證題庫"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })

        progressView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(progressView)

        val list = ListView(this).apply {
            divider = GradientDrawable().apply { setColor(Color.parseColor("#1C1C22")) }
            dividerHeight = dp(1)
        }
        adapter = RowAdapter(this, tasks)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> select(position) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selectionView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(selectionView)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        copyBtn = makeButton("複製") { copySelected() }
        runBtn = makeButton("執行") { runSelected() }
        passBtn = makeButton("通過") { passSelected() }
        failBtn = makeButton("失敗") { failSelected() }
        for (b in listOf(copyBtn, runBtn, passBtn, failBtn)) {
            buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(buttons)

        setContentView(root)
        refresh()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#242433"))
        setOnClickListener { onClick() }
    }

    private fun loadTasks() {
        tasks.clear()
        val lines = runCatching {
            assets.open("open_source_ai_databases/ui_benchmark_tasks.tsv")
                .bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrDefault(emptyList())
        val parsed = ArrayList<Task>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = line.split('\t')
            if (cells.size < 3) continue
            parsed.add(Task(cells[0].trim(), cells[1].trim(), cells[2].trim()))
        }
        // 由簡到難：難度排序 → 編號；同難度保持資料檔順序
        tasks.addAll(parsed.sortedWith(compareBy({ tierRank(it.tier) }, { it.id })))
    }

    /** 難度排序（EASY 最前）。這是題庫的**結構**，不是詞表。 */
    private fun tierRank(tier: String): Int = when (tier.uppercase()) {
        "EASY" -> 0
        "MEDIUM" -> 1
        "HARD" -> 2
        "EXTREME" -> 3
        else -> 4
    }

    private fun prefs() = getSharedPreferences("wae_benchmark", Context.MODE_PRIVATE)

    private fun select(position: Int) {
        if (position > nextIndex) {
            val need = tasks.getOrNull(nextIndex)
            toast("請先完成 " + (need?.id ?: "") + "（只能由簡到難）")
            return
        }
        selected = position
        refresh()
    }

    private fun currentTask(): Task? = tasks.getOrNull(selected)

    private fun copySelected() {
        val t = currentTask() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WAE 驗證題庫", t.command))
        toast("已複製 " + t.id + "：" + t.command)
    }

    private fun runSelected() {
        val t = currentTask() ?: return
        if (selected != nextIndex) {
            toast("只能執行目前的下一題（" + (tasks.getOrNull(nextIndex)?.id ?: "") + "）")
            return
        }
        val svc = WaeVisionAccessibilityService.activeInstance
        if (svc == null) {
            toast("無障礙服務未啟用，無法執行")
            return
        }
        toast("執行 " + t.id + "：" + t.command)
        runCatching { svc.runAgentCommand(t.command) }
    }

    private fun passSelected() {
        val t = currentTask() ?: return
        if (selected != nextIndex) {
            toast("只能對目前的下一題按通過")
            return
        }
        nextIndex = (nextIndex + 1).coerceAtMost(tasks.size)
        prefs().edit().putInt(KEY_NEXT, nextIndex).apply()
        selected = if (nextIndex < tasks.size) nextIndex else tasks.size - 1
        toast(t.id + " 通過，已解鎖下一題")
        refresh()
    }

    private fun failSelected() {
        val t = currentTask() ?: return
        toast(t.id + " 記為失敗，留在原地重試")
        refresh()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val done = nextIndex
        progressView.text = "已通過 $done / ${tasks.size}　·　點一列選題　·　只能由簡到難"
        val t = currentTask()
        selectionView.text = if (t == null) "沒有題目" else {
            val lock = when {
                selected < nextIndex -> "（已通過，可回看）"
                selected == nextIndex -> "（目前題）"
                else -> "（未解鎖）"
            }
            t.id + "　" + t.tier + "　" + lock + "\n" + t.command
        }
        val canAct = t != null && selected == nextIndex
        runBtn.isEnabled = canAct
        passBtn.isEnabled = canAct
        failBtn.isEnabled = t != null
        copyBtn.isEnabled = t != null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 一列＝一題：狀態（通過／目前／未解鎖）＋編號難度＋指令。 */
    private inner class RowAdapter(
        private val context: Context,
        private val items: List<Task>
    ) : ArrayAdapter<Task>(context, 0, items) {

        override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            row.removeAllViews()
            val t = items[position]
            val selectedRow = position == selected
            row.setBackgroundColor(if (selectedRow) Color.parseColor("#1B2A3C") else Color.TRANSPARENT)

            val status = when {
                position < nextIndex -> "已通過"
                position == nextIndex -> "目前"
                else -> "未解鎖"
            }
            row.addView(TextView(context).apply {
                text = t.id + "　" + t.tier + "　" + status
                setTextColor(
                    when (status) {
                        "已通過" -> Color.parseColor("#7BD88F")
                        "目前" -> Color.parseColor("#FFD166")
                        else -> Color.parseColor("#5A5A66")
                    }
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
            row.addView(TextView(context).apply {
                text = t.command
                setTextColor(if (position > nextIndex) Color.parseColor("#6E6E7A") else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(3), 0, 0)
            })
            return row
        }
    }

    private companion object {
        const val KEY_NEXT = "next_index"
    }
}
