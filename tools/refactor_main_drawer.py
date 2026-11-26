"""D 續三：把「可折疊設定抽屜」整段（339 行）抽成 buildSettingsDrawer()。

抽屜本身用欄位 `settingsDrawer`，理論上不需要參數；
但為避免它相依到 onCreate 的區域變數，先把整段搬出去，編譯器會告訴我們還缺什麼。
"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\MainActivity.kt")
lines = P.read_text(encoding="utf-8").split("\n")


def idx_of(needle):
    for i, ln in enumerate(lines):
        if needle in ln:
            return i
    raise SystemExit("找不到標記: " + needle)


s = idx_of("// 可折疊「⚙️ 設定」抽屜")
e = idx_of("outerScreenFrame.addView(")
assert e > s, "範圍錯誤"

body_lines = lines[s:e]
while body_lines and body_lines[-1].strip() == "":
    body_lines.pop()
body = [ln[4:] if ln.startswith("        ") else ln for ln in body_lines]

method = (
    [
        "    /**",
        "     * 可折疊設定抽屜：系統檢查、自我測試、反例、App 排序、懸浮窗、語音助理、",
        "     * 崩潰紀錄、進階設定。",
        "     *",
        "     * 從 onCreate 抽出來（原本 339 行），內容彼此獨立、只依賴欄位，",
        "     * 所以可以整段搬走而不改變行為。",
        "     */",
        "    private fun buildSettingsDrawer(centerCard: LinearLayout) {",
    ]
    + body
    + ["    }"]
)

new_lines = lines[:s] + ["        buildSettingsDrawer(centerCard)", ""] + lines[e:]
text = "\n".join(new_lines)

anchor = "    private fun buildDevBenchmarkRow(centerCard: LinearLayout) {"
a = text.find(anchor)
if a < 0:
    raise SystemExit("找不到插入錨點")
rest = text[a:]
cand = [p for p in (rest.find("\n    private fun ", 5), rest.find("\n    override fun ", 5)) if p > 0]
end = a + (min(cand) if cand else len(rest))
text = text[:end] + "\n\n" + "\n".join(method) + text[end:]

P.write_text(text, encoding="utf-8")
print("抽出設定抽屜；行數:", len(text.split("\n")))
