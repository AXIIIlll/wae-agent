import io, sys, re
from pathlib import Path

ROOT = Path(r"E:\WAE\NewAndroidApp")
BASE = ROOT / "app" / "src" / "main" / "java" / "com" / "wae" / "agent" / "core"

# (檔案, 舊字串, 新字串)
JOBS = []

# ── 變體 A：有 context 判斷，失敗要設欄位 fallback ─────────────────────────
A_FILES = {
    "decision/ContentItemEvidence.kt": ("navKeywords = DEFAULT_NAV", "ContentItemEvidence"),
    "decision/TargetPreference.kt": ("loadFromLines(DEFAULT_ROWS)", "TargetPreference"),
    "explore/ExplorationGuard.kt": ("policy = Policy()", "ExplorationGuard"),
    "goal/GoalCompiler.kt": ("rules = DEFAULT_RULES", "GoalCompiler"),
    "nlu/GoalConnectors.kt": ("sequential = DEFAULT_SEQUENTIAL\n            independent = DEFAULT_INDEPENDENT", "GoalConnectors"),
}

for rel, (fallback, _name) in A_FILES.items():
    p = BASE / rel
    if not p.is_file():
        print("缺少", p)
        continue
    src = p.read_text(encoding="utf-8")
    old = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        if (loadAttempted || context == null) return\n"
        "        loadAttempted = true\n"
        "        try {\n"
        "            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }\n"
        "            loadFromLines(lines)\n"
        "        } catch (_: Throwable) {\n"
        f"            {fallback}\n"
        "        }\n"
        "    }"
    )
    new = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        val lines = table.linesOrNull(context)\n"
        "        if (lines == null) {\n"
        f"            {fallback}\n"
        "            return\n"
        "        }\n"
        "        loadFromLines(lines)\n"
        "    }"
    )
    if old not in src:
        print("樣式不符，跳過:", rel)
        continue
    src = src.replace(old, new)
    src = re.sub(r"(?m)^    @Volatile\n    private var loadAttempted = false$", "    private val table = AssetTable(DB_PATH)", src)
    if "loadAttempted" in src:
        src = re.sub(r"(?m)^    private var loadAttempted = false$", "    private val table = AssetTable(DB_PATH)", src)
    if "import com.wae.agent.core.data.AssetTable" not in src:
        # 插在第一個 import 之後
        src = re.sub(r"(?m)^(import .*\n)", r"\1import com.wae.agent.core.data.AssetTable\n", src, count=1)
    p.write_text(src, encoding="utf-8")
    print("已改:", rel)

# ── 變體 B：loadFromLines(DEFAULT_ROWS) ────────────────────────────────────
for rel in ["grounding/DynamicUiClassifier.kt", "grounding/GroundingFusion.kt",
            "taskos/SlotFiller.kt", "taskos/TaskTemplates.kt"]:
    p = BASE / rel
    if not p.is_file():
        print("缺少", p); continue
    src = p.read_text(encoding="utf-8")
    old = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        if (loadAttempted) return\n"
        "        loadAttempted = true\n"
        "        try {\n"
        "            val lines = context?.assets?.open(DB_PATH)\n"
        "                ?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }\n"
        "            loadFromLines(lines ?: DEFAULT_ROWS)\n"
        "        } catch (_: Throwable) {\n"
        "            loadFromLines(DEFAULT_ROWS)\n"
        "        }\n"
        "    }"
    )
    new = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        loadFromLines(table.lines(context, DEFAULT_ROWS))\n"
        "    }"
    )
    if old not in src:
        print("樣式不符，跳過:", rel); continue
    src = src.replace(old, new)
    src = re.sub(r"(?m)^    @Volatile\n    private var loadAttempted = false$", "    private val table = AssetTable(DB_PATH)", src)
    if "loadAttempted" in src:
        src = re.sub(r"(?m)^    private var loadAttempted = false$", "    private val table = AssetTable(DB_PATH)", src)
    if "import com.wae.agent.core.data.AssetTable" not in src:
        src = re.sub(r"(?m)^(import .*\n)", r"\1import com.wae.agent.core.data.AssetTable\n", src, count=1)
    p.write_text(src, encoding="utf-8")
    print("已改:", rel)

# ── 變體 C：catch 內什麼都不做 ─────────────────────────────────────────────
p = BASE / "nlu" / "ActionSelectorTable.kt"
if p.is_file():
    src = p.read_text(encoding="utf-8")
    old = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        if (loadAttempted || context == null) return\n"
        "        loadAttempted = true\n"
        "        try {\n"
        "            val lines = context.assets.open(DB_PATH).bufferedReader(Charsets.UTF_8).use { it.readLines() }\n"
        "            loadFromLines(lines)\n"
        "        } catch (_: Throwable) {\n"
        "        }\n"
        "    }"
    )
    new = (
        "    fun ensureLoaded(context: Context?) {\n"
        "        table.linesOrNull(context)?.let { loadFromLines(it) }\n"
        "    }"
    )
    if old in src:
        src = src.replace(old, new)
        src = re.sub(r"(?m)^    @Volatile\n    private var loadAttempted = false$", "    private val table = AssetTable(DB_PATH)", src)
        if "import com.wae.agent.core.data.AssetTable" not in src:
            src = re.sub(r"(?m)^(import .*\n)", r"\1import com.wae.agent.core.data.AssetTable\n", src, count=1)
        p.write_text(src, encoding="utf-8")
        print("已改: nlu/ActionSelectorTable.kt")
    else:
        print("樣式不符，跳過: nlu/ActionSelectorTable.kt")
