"""文件裡的數字，跟實際跑得出來的數字對一下。

為什麼要有：這份專案最常犯的錯不是程式錯，是**文件說謊**——
能力清單寫「還沒做」（其實做了）、手冊寫「101 條」（其實早就是 109 條）。
每次都要人手去追，不如讓它自己講。

檢查的數字（全部現場算，不寫死）：
  - 評測總條數：四個套件案例檔加起來
  - 能力清單：✅／🟡／⬜ 各幾列
  - 服務行數：`WaeVisionAccessibilityService.kt` 的行數（允許 ±50 的漂移）

用法：
    python tools/doc_facts.py            # 只報告
    python tools/doc_facts.py --strict   # 有不一致就 exit 1
"""

import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
MAP = DOCS / "WAE_Gen5_Capability_Map.md"
SERVICE = ROOT / "app" / "src" / "main" / "java" / "com" / "wae" / "agent" / "core" / "WaeVisionAccessibilityService.kt"
SUITES = [
    ROOT / "tools" / "gen5_baseline20.tsv",
    ROOT / "tools" / "gen5_phrasings.tsv",
    ROOT / "tools" / "gen5_longphrases.tsv",
    ROOT / "tools" / "gen5_suite4.tsv",
]
# 這些是「現況」文件：裡面的數字應該是現在的值
CURRENT_DOCS = [
    DOCS / "README.md",
    DOCS / "WAE_How_To_Verify.md",
    DOCS / "WAE_RealDevice_Checklist.md",
    DOCS / "WAE_Remaining_Plan.md",
]


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def suite_total() -> int:
    total = 0
    for path in SUITES:
        if not path.exists():
            continue
        for line in io.open(path, encoding="utf-8").read().splitlines():
            if line.strip() and not line.startswith("#"):
                total += 1
    return total


def capability_counts() -> tuple[int, int, int]:
    text = io.open(MAP, encoding="utf-8").read()
    rows = [l for l in text.splitlines() if re.match(r"^\|\s*[A-I]\d+\s*\|", l)]
    done = sum(1 for l in rows if "✅" in l)
    partial = sum(1 for l in rows if "🟡" in l)
    todo = sum(1 for l in rows if "⬜" in l)
    return done, partial, todo


def service_lines() -> int:
    return len(io.open(SERVICE, encoding="utf-8").read().splitlines())


def check(verbose: bool = True) -> list[str]:
    problems: list[str] = []
    total = suite_total()
    done, partial, todo = capability_counts()
    lines = service_lines()

    if verbose:
        print(f"現場數字：評測 {total} 條、能力 ✅{done}／🟡{partial}／⬜{todo}、服務 {lines} 行\n")

    for doc in CURRENT_DOCS:
        if not doc.exists():
            continue
        text = io.open(doc, encoding="utf-8").read()
        for m in re.finditer(r"(\d+)\s*條(?:評測|檢查|回歸)", text):
            claimed = int(m.group(1))
            if claimed != total:
                problems.append(f"{doc.name}: 說「{claimed} 條」，實際 {total} 條")

    readme = DOCS / "README.md"
    if readme.exists():
        text = io.open(readme, encoding="utf-8").read()
        for m in re.finditer(r"能力清單[^\n]*?✅\s*(\d+)\s*條", text):
            claimed = int(m.group(1))
            if claimed != done:
                problems.append(f"README.md: 說「✅ {claimed} 條」，實際 {done} 條")
        for m in re.finditer(r"🟡\s*(\d+)[^\n]{0,8}⬜\s*(\d+)", text):
            claimed_partial, claimed_todo = int(m.group(1)), int(m.group(2))
            if claimed_partial != partial:
                problems.append(f"README.md: 說「🟡 {claimed_partial}」，實際 {partial}")
            if claimed_todo != todo:
                problems.append(f"README.md: 說「⬜ {claimed_todo}」，實際 {todo}")

    # 服務行數：只抓「現在是」這種句子，容許 ±50（文件不必跟著每次搬家改）
    howto = DOCS / "WAE_How_To_Verify.md"
    if howto.exists():
        text = io.open(howto, encoding="utf-8").read()
        for m in re.finditer(r"服務[^\n]{0,12}?([\d,]{3,6})\s*行", text):
            claimed = int(m.group(1).replace(",", ""))
            if abs(claimed - lines) > 50:
                problems.append(f"WAE_How_To_Verify.md: 說服務 {claimed} 行，實際 {lines} 行")

    return problems


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    problems = check()
    if problems:
        print("不一致：")
        for p in problems:
            print("  - " + p)
        return 1 if args.strict else 0
    print("DOC FACTS: PASS（文件裡的數字跟現場一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
