"""D 續二（單趟版）：先在原始檔上一次算好所有區塊範圍，再由下往上替換。

前兩版失敗的原因：
  1. 大括號計數 → 在 `apply { }` 結束就停，後面的程式碼留在原地。
  2. 邊抽邊找 marker → 後面的 marker 已被前一次抽掉，找不到。
這版：先把 4 個區塊的 (start, end) 全部算出來，再一次性套用。
"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\MainActivity.kt")
orig = P.read_text(encoding="utf-8").split("\n")


def idx_of(needle):
    for i, ln in enumerate(orig):
        if needle in ln:
            return i
    raise SystemExit("找不到標記: " + needle)


# (開始註解, 結束註解, 方法簽章行, 呼叫名)
SPECS = [
    ("// 頂部列：左側標題", "// P1：首次使用引導（3 步）", "buildHeader", "centerCard"),
    ("// P1：首次使用引導（3 步）", "// 單一指令輸入框", "buildOnboarding", "centerCard"),
    ("// 單一指令輸入框", "// 動態歧義澄清選項列", "buildCommandInput", "centerCard"),
    ("// 動態歧義澄清選項列", "// 可折疊「⚙️ 設定」抽屜", "buildStatusRows", "centerCard"),
]

DOC = {
    "buildHeader": ["    /** 頂部列：品牌小助手（向量圖）＋ 右側設定入口。 */"],
    "buildOnboarding": ["    /** 首次使用引導（3 步）：只在尚未完成引導時顯示。 */"],
    "buildCommandInput": ["    /** 指令輸入框 ＋ 執行／停止鍵（含 debug_command 自動填入）。 */"],
    "buildStatusRows": ["    /** 狀態列 ＋ 動態歧義澄清按鈕列。 */"],
}

ranges = []
for start_m, end_m, name, arg in SPECS:
    s = idx_of(start_m)
    e = idx_of(end_m)
    if e <= s:
        raise SystemExit(f"範圍錯誤: {start_m}")
    ranges.append((s, e, name, arg))

methods = []
for (s, e, name, arg) in ranges:
    body_lines = orig[s:e]
    while body_lines and body_lines[-1].strip() == "":
        body_lines.pop()
    body = [ln[4:] if ln.startswith("        ") else ln for ln in body_lines]
    head = DOC[name] + [f"    private fun {name}({arg}: LinearLayout) {{"]
    methods.append(head + body + ["    }"])

# 由下往上替換（索引不會位移）
out = list(orig)
for (s, e, name, arg) in sorted(ranges, key=lambda r: -r[0]):
    out[s:e] = [f"        {name}({arg})", ""]

text = "\n".join(out)

# 所有新方法插在 buildDevBenchmarkRow 之後
anchor = "    private fun buildDevBenchmarkRow(centerCard: LinearLayout) {"
a = text.find(anchor)
if a < 0:
    raise SystemExit("找不到插入錨點")
rest = text[a:]
cand = [p for p in (rest.find("\n    private fun ", 5), rest.find("\n    override fun ", 5)) if p > 0]
end = a + (min(cand) if cand else len(rest))
block = "\n".join(sum(methods, []))
text = text[:end] + "\n\n" + block + text[end:]

P.write_text(text, encoding="utf-8")
print("完成；行數:", len(text.split("\n")))
