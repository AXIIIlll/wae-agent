package com.wae.agent.settings

import android.app.Activity
import android.content.ClipData
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wae.agent.core.app.InstalledAppCatalog
import com.wae.core.config.BundledData
import com.wae.core.rule.NounResolver

/**
 * 「App 類型排序」設定畫面。
 *
 * 左邊是型別（地點／資訊／人物…），右邊是這支手機上屬於該型別的 App。
 * **越左邊越先用**；長按 App 可以拖動排序，放開即儲存。
 *
 * 這個順序會直接影響「名詞 fallback」挑哪個 App（例：找資料時先開哪個瀏覽器）。
 */
class AppTypeOrderActivity : Activity() {

    private lateinit var listContainer: LinearLayout

    private data class TypeRow(
        val type: String,
        val label: String,
        val categories: List<String>,
        val action: String
    )

    private val types = ArrayList<TypeRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "App 類型排序"
        loadTypes()
        setContentView(buildRoot())
        renderRows()
    }

    private fun loadTypes() {
        val parsed = runCatching {
            NounResolver.parse(emptySequence(), BundledData.typeAppLines(), emptySequence())
        }.getOrNull()
        val labels = runCatching {
            BundledData.typeAppLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val c = line.split('\t')
                    if (c.size >= 5) c[0].trim() to c[4].trim() else null
                }.toMap()
        }.getOrDefault(emptyMap())
        val cats: List<Triple<String, List<String>, String>> = runCatching {
            BundledData.typeAppLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val c = line.split('\t')
                    if (c.size >= 3) {
                        Triple(c[0].trim(), c[1].split('|').map { it.trim() }, c[2].trim())
                    } else {
                        null
                    }
                }.toList()
        }.getOrDefault(emptyList())
        for ((type, typeCats, action) in cats) {
            types.add(TypeRow(type, labels[type] ?: type, typeCats, action))
        }
        if (types.isEmpty() && parsed != null) {
            // 退路：至少列出型別名稱
            for (type in parsed.types()) types.add(TypeRow(type, type, emptyList(), "search"))
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "App 類型排序"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "長按 App 拖動排序　·　越左邊越先用"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 0, 0, dp(10))
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(listContainer)
        scroll.post { scroll.scrollTo(0, 0) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(Button(this).apply {
            text = "全部恢復預設順序"
            isAllCaps = false
            textSize = 13f
            setOnClickListener {
                for (row in types) AppTypeOrderStore.clear(this@AppTypeOrderActivity, row.type)
                renderRows()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        return root
    }

    private fun renderRows() {
        listContainer.removeAllViews()
        val apps = runCatching {
            InstalledAppCatalog.load(this, force = true)
        }.getOrDefault(emptyList())
        for (row in types) {
            val inType = apps.filter { row.categories.contains(it.category) }
            if (inType.isEmpty()) continue
            listContainer.addView(buildTypeRow(row, inType.map { it.pkg to it.label }))
        }
    }

    private fun buildTypeRow(row: TypeRow, apps: List<Pair<String, String>>): View {
        val ordered = AppTypeOrderStore.apply(this, row.type, apps) { it.first }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        wrapper.addView(TextView(this).apply {
            text = row.label + "（" + ordered.size + "）　" + hintOf(row.action)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, dp(6))
        })
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        wrapper.addView(hScroll)

        for ((pkg, label) in ordered) {
            chipRow.addView(buildChip(row, pkg, label, chipRow, ordered))
        }
        return wrapper
    }

    private fun buildChip(
        row: TypeRow,
        pkg: String,
        label: String,
        chipRow: LinearLayout,
        ordered: List<Pair<String, String>>
    ): View {
        val chip = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF2FF"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#C7D2FE"))
            }
            setOnLongClickListener { v ->
                val data = ClipData.newPlainText("pkg", pkg)
                v.startDragAndDrop(data, View.DragShadowBuilder(v), pkg, 0)
                true
            }
            setOnDragListener { _, event -> handleDrag(event, row, pkg, chipRow, ordered) }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(8)
        chip.layoutParams = lp
        return chip
    }

    private fun handleDrag(
        event: DragEvent,
        row: TypeRow,
        targetPkg: String,
        chipRow: LinearLayout,
        ordered: List<Pair<String, String>>
    ): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> {
                val dragged = event.localState as? String ?: event.clipData?.getItemAt(0)?.text?.toString()
                if (dragged != null && dragged != targetPkg) {
                    val current = ordered.map { it.first }.toMutableList()
                    val from = current.indexOf(dragged)
                    val to = current.indexOf(targetPkg)
                    if (from >= 0 && to >= 0) {
                        current.removeAt(from)
                        current.add(to, dragged)
                        AppTypeOrderStore.save(this, row.type, current)
                        // 重建這一列（簡單、可預期）
                        renderRows()
                    }
                }
                return true
            }
            DragEvent.ACTION_DRAG_STARTED, DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> return true
            DragEvent.ACTION_DROP -> return true
            DragEvent.ACTION_DRAG_ENDED -> return true
        }
        return false
    }

    private fun hintOf(action: String): String = when (action) {
        "open" -> "只用來開啟"
        "contact" -> "找人／聯絡"
        else -> "會進去搜尋"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
