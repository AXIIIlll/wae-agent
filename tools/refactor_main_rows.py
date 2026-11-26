"""D 續：把 onCreate 的「安全確認列」與「無障礙服務橫幅」抽成方法。

兩塊都只用既有欄位 + onCreate 的區域變數 centerCard（以參數傳入）。
用大括號計數決定區塊結尾，避免上一版抓錯括號的問題。
"""
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\MainActivity.kt")


def extract(text, start_marker, end_marker, method_src_head, params, arg):
    lines = text.split("\n")
    s = next(i for i, ln in enumerate(lines) if start_marker in ln)
    e = next(i for i, ln in enumerate(lines) if end_marker in ln and i > s)

    body_lines = lines[s + 1:e]
    # 去掉尾端空行
    while body_lines and body_lines[-1].strip() == "":
        body_lines.pop()
    # 去縮排 4 格（原本在 onCreate 內 8 格）
    body = [ln[4:] if ln.startswith("        ") else ln for ln in body_lines]

    method = method_src_head + body + ["    }"]
    call = [f"        {params}({arg})", ""]
    new_lines = lines[:s] + call + lines[e:]
    out = "\n".join(new_lines)
    # 方法插在 buildDevBenchmarkRow 之後
    anchor = "    private fun buildDevBenchmarkRow(centerCard: LinearLayout) {"
    idx = out.find(anchor)
    if idx < 0:
        raise SystemExit("找不到插入錨點")
    # 找該方法的結束（下一個 "\n    private fun " 或 "\n    override fun "）
    rest = out[idx:]
    nxt = min(
        [p for p in (rest.find("\n    private fun ", 5), rest.find("\n    override fun ", 5)) if p > 0]
        or [len(rest)],
    )
    end_idx = idx + nxt
    out = out[:end_idx] + "\n" + "\n".join(method) + out[end_idx:]
    return out


text = P.read_text(encoding="utf-8")

text = extract(
    text,
    "安全確認列：當動作命中金流",
    "無障礙服務未啟用時的醒目橫幅",
    [
        "    /** 安全確認列：命中金流／破壞性操作時出現，需要使用者明確放行。 */",
        "    private fun buildSafetyConfirmRow(centerCard: LinearLayout) {",
    ],
    "buildSafetyConfirmRow",
    "centerCard",
)

text = extract(
    text,
    "無障礙服務未啟用時的醒目橫幅",
    "單一指令輸入框",
    [
        "    /** 無障礙服務未啟用時的醒目橫幅（重裝 APK 後 Android 會自動關閉該服務）。 */",
        "    private fun buildServiceBanner(centerCard: LinearLayout) {",
    ],
    "buildServiceBanner",
    "centerCard",
)

P.write_text(text, encoding="utf-8")
print("完成：onCreate 已少了兩塊")
print("目前行數:", len(text.split(chr(10))))
