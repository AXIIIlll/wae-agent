"""把 MainActivity 裡「整寬 × 高度自適應」的 LayoutParams 樣板換成 paramsFullWidth()。

只換這一種形狀（其餘多半是 0dp + weight、固定 dp、或 1dp 分隔線，語意不同不硬套）：

    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )                      ← 可能接 .apply { xxxMargin = dp(N) }
"""
import re
from pathlib import Path

P = Path(r"E:\WAE\NewAndroidApp\app\src\main\java\com\wae\agent\MainActivity.kt")
src = P.read_text(encoding="utf-8")

named = {
    "bottom": "bottom",
    "top": "top",
    "left": "left",
    "start": "start",
    "end": "end",
}

pat = re.compile(
    r"LinearLayout\.LayoutParams\(\s*\n"
    r"\s*ViewGroup\.LayoutParams\.MATCH_PARENT,\s*\n"
    r"\s*ViewGroup\.LayoutParams\.WRAP_CONTENT\s*\n"
    r"\s*\)"
    r"(?:\.apply\s*\{\s*(\w+)Margin\s*=\s*dp\((\d+)\)(?:\s*\n\s*)?\})?",
    re.M,
)


def repl(m):
    margin_name = m.group(1)
    value = m.group(2)
    if margin_name is None:
        return "paramsFullWidth()"
    key = named.get(margin_name)
    if key is None:
        # 不認得的邊距名稱：保守起見不要改
        return m.group(0)
    return f"paramsFullWidth({key} = {value})"


src2, n = pat.subn(repl, src)
P.write_text(src2, encoding="utf-8")
print("replaced:", n)
print("remaining multiline MATCH_PARENT patterns:",
      len(re.findall(r"ViewGroup\.LayoutParams\.MATCH_PARENT,\s*\n\s*ViewGroup\.LayoutParams\.WRAP_CONTENT", src2)))
