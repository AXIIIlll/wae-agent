"""第二批：把剩下的 assets 載入樣板換成 AssetTable。

刻意**不改**這四個，因為它們不是樣板而是真的有額外行為：
  - AutomatonStore：ensureLoaded 只是轉呼叫 GraphBudget（沒有讀檔）
  - ValueAdjust / ValueVerbLexicon / WidgetCapability：有自己的 readAssetLines，
    支援「JVM 單元測試沒有 Android Context 時從工作目錄讀檔」——這是測試友善設計，
    換成 AssetTable（需要 Context）會讓那批測試失敗
  - DecisionHead：載入後還要記錄 log、保存 appContext
"""
import re
from pathlib import Path

BASE = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\core")

A = (
    "    fun ensureLoaded(context: Context?) {{\n"
    "        if (loadAttempted || context == null) return\n"
    "        loadAttempted = true\n"
    "        try {{\n"
    "            val lines = context.assets.open({path})\n"
    "                .bufferedReader(Charsets.UTF_8).use {{ it.readLines() }}\n"
    "            loadFromLines(lines)\n"
    "        }} catch (_: Throwable) {{\n"
    "{fallback}"
    "        }}\n"
    "    }}"
)

JOBS = [
    # (相對路徑, DB 運算式, fallback 行（含縮排；空字串＝不做事）)
    ("automaton/GraphBudget.kt",
     '"open_source_ai_databases/ui_graph_budget_policy.tsv"',
     "            // 保持預設表（與資料檔同值）\n"),
    ("grounding/ConfidenceCalibrator.kt", None, None),   # 走變體 B，下面處理
    ("nlu/GrammarParticles.kt", "DB_PATH", "            // 保持空表（安全失敗）\n"),
    ("nlu/ToggleIntent.kt", "DB_PATH", "            onWords = DEFAULT_ON\n            offWords = DEFAULT_OFF\n"),
    ("nlu/ToggleSectionMap.kt", "DB_PATH", "            mapping = emptyList()\n"),
    ("transfer/TransferEngine.kt", "DB_PATH", "            families = DEFAULT_FAMILIES\n"),
]

for rel, path_expr, fallback in JOBS:
    if path_expr is None:
        continue
    p = BASE / rel
    src = p.read_text(encoding="utf-8")
    old = A.format(path=path_expr, fallback=fallback)
    new = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        val lines = table.linesOrNull(context)\n"
        "        if (lines == null) {\n"
        f"{fallback}"
        "            return\n"
        "        }\n"
        "        loadFromLines(lines)\n"
        "    }"
    )
    if old not in src:
        print("樣式不符:", rel)
        continue
    src = src.replace(old, new)
    src = re.sub(r"(?m)^    @Volatile\n    private var loadAttempted(: Boolean)? = false$",
                 "    private val table = AssetTable(%s)" % path_expr, src)
    src = re.sub(r"(?m)^    private var loadAttempted(: Boolean)? = false$",
                 "    private val table = AssetTable(%s)" % path_expr, src)
    src = src.replace("loadAttempted = false", "table.reset()")
    if "import com.wae.agent.core.data.AssetTable" not in src:
        src = re.sub(r"(?m)^(import .*\n)", r"\1import com.wae.agent.core.data.AssetTable\n", src, count=1)
    p.write_text(src, encoding="utf-8")
    print("已改:", rel)

# 變體 B：context 可為 null、失敗要 resetToDefaults()
p = BASE / "grounding" / "ConfidenceCalibrator.kt"
src = p.read_text(encoding="utf-8")
old = (
    "    fun ensureLoaded(context: Context?) {\n"
    "        if (loadAttempted) return\n"
    "        loadAttempted = true\n"
    "        try {\n"
    "            val lines = context?.assets?.open(DB_PATH)\n"
    "                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }\n"
    "            if (lines != null) loadFromLines(lines) else resetToDefaults()\n"
    "        } catch (_: Throwable) {\n"
    "            resetToDefaults()\n"
    "        }\n"
    "    }"
)
new = (
    "    fun ensureLoaded(context: Context?) {\n"
    "        val lines = table.linesOrNull(context)\n"
    "        if (lines == null) {\n"
    "            resetToDefaults()\n"
    "            return\n"
    "        }\n"
    "        loadFromLines(lines)\n"
    "    }"
)
if old in src:
    src = src.replace(old, new)
    src = re.sub(r"(?m)^    @Volatile\n    private var loadAttempted(: Boolean)? = false$",
                 "    private val table = AssetTable(DB_PATH)", src)
    src = src.replace("loadAttempted = false", "table.reset()")
    if "import com.wae.agent.core.data.AssetTable" not in src:
        src = re.sub(r"(?m)^(import .*\n)", r"\1import com.wae.agent.core.data.AssetTable\n", src, count=1)
    p.write_text(src, encoding="utf-8")
    print("已改: grounding/ConfidenceCalibrator.kt")
else:
    print("樣式不符: grounding/ConfidenceCalibrator.kt")
