"""D 的第一步（修正版）：把 onCreate 裡的「開發期評測」整塊抽成方法。

上一版用「找第一個 `        }`」判斷結尾，結果抓到內層的括號 → 檔案壞掉。
這版改用**大括號計數**，只有在深度回到 0 時才算區塊結束。
"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\MainActivity.kt")
lines = P.read_text(encoding="utf-8").split("\n")

start = next(i for i, ln in enumerate(lines) if "DEV_BENCHMARK" in ln)

depth = 0
end = None
for i in range(start + 1, start + 200):
    depth += lines[i].count("{") - lines[i].count("}")
    if i > start + 2 and depth == 0:
        end = i
        break
if end is None:
    raise SystemExit("找不到區塊結尾")

block = lines[start:end + 1]

# 區塊內容：去掉第一行註解與最外層 if 的括號行、最後的 }
inner = block[1:]
# 去掉開頭的 if (...) { 行
if inner and "FLAG_DEBUGGABLE" in inner[0]:
    cond_line = inner.pop(0)
else:
    cond_line = "" 
# 去掉結尾的 }
if inner and inner[-1].strip() == "}":
    inner.pop()

body = []
for ln in inner:
    body.append(ln[4:] if ln.startswith("        ") else ln)

new_method = [
    "    /**",
    "     * 開發期評測列（debug only）：題庫抽樣、提示詞套件、相容性掃描。",
    "     *",
    "     * 從 onCreate 抽出來：這塊與畫面其餘部分無關，正式版預定整塊移除，",
    "     * 獨立成方法比較好刪，也讓 onCreate 少 77 行。",
    "     */",
    "    private fun buildDevBenchmarkRow() {",
    "        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return",
] + body + ["    }"]

lines2 = lines[:start] + ["        buildDevBenchmarkRow()", ""] + lines[end + 1:]
out = "\n".join(lines2)

anchor = "    private fun applyDebugCommandIfPresent() {"
if anchor not in out:
    raise SystemExit("找不到插入點")
out = out.replace(anchor, "\n".join(new_method) + "\n\n" + anchor, 1)

P.write_text(out, encoding="utf-8")
print("抽出行數:", len(block))
