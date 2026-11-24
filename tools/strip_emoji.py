#!/usr/bin/env python3
"""移除 Kotlin 原始碼裡的 emoji（AGENTS.md §2：UI 不得使用 emoji）。

為什麼用 Python：PowerShell 的 Set-Content 會破壞 UTF-8 中文（專案已踩過），
Python 讀寫 UTF-8 最安全。

規則（刻意保守）：
  - 只移除 U+1F000–U+1FAFF（真正的表情符號區塊）
  - ✓ ⚠ ⛔ ✗ 這些是 U+26xx／U+27xx 的符號，屬既有風格，保留不動
  - 不動任何中文文案，只把 emoji 字元拿掉並收斂多餘空白

用法：
  python tools/strip_emoji.py            # 實際修改
  python tools/strip_emoji.py --dry-run  # 只列出會改的行
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_DIRS = [ROOT / "app" / "src" / "main" / "java", ROOT / "core" / "src" / "main" / "kotlin"]


def is_emoji(ch: str) -> bool:
    return 0x1F000 <= ord(ch) <= 0x1FAFF


def main() -> int:
    # 主控台是 cp950，直接印 emoji 會炸；改成 UTF-8 並容錯
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    dry = "--dry-run" in sys.argv
    changed_files = 0
    changed_lines = 0
    for base in SRC_DIRS:
        for path in sorted(base.rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            if not any(is_emoji(ch) for ch in text):
                continue
            new_lines = []
            file_changed = False
            for line in text.splitlines(keepends=True):
                cleaned = "".join(ch for ch in line if not is_emoji(ch))
                if cleaned != line:
                    # 收斂 emoji 留下的多餘空白（只動非縮排處）
                    while "  " in cleaned:
                        idx = cleaned.index("  ")
                        if idx == 0 or cleaned[:idx].strip() == "":
                            break
                        cleaned = cleaned[:idx] + cleaned[idx + 1:]
                    file_changed = True
                    changed_lines += 1
                    if dry:
                        print(f"{path.relative_to(ROOT)}: {line.strip()[:80]}")
                new_lines.append(cleaned)
            if file_changed and not dry:
                path.write_text("".join(new_lines), encoding="utf-8", newline="\n")
                changed_files += 1
    if dry:
        print(f"[dry-run] 會修改 {changed_lines} 行")
    else:
        print(f"已修改 {changed_files} 個檔案、{changed_lines} 行")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
