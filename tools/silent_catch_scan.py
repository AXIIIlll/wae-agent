"""掃「安靜吞掉錯誤」的 catch：空的、或只回一個值而沒有記錄。

為什麼要掃：`catch (_: Throwable) {}` 跟卡片保底分支一樣，會把「還沒處理」變成「看起來處理了」。
這在感知與執行路徑上等於**靜默失敗**——使用者只會覺得「它怪怪的」。

規則：
  - 空的 catch（或只有 `false` / `null` / `Unit` 這類回傳）→ 需要一個 `// 忽略：<原因>` 才放行
  - 有 `Log.` / `record` / `throw` 的 catch 視為有處理

用法：
    python tools/silent_catch_scan.py            # 掃 core + gen5 路徑
    python tools/silent_catch_scan.py --all      # 掃全部 app 原始碼
    python tools/silent_catch_scan.py --strict   # 有未標註的就回非 0
"""

import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "tools" / "silent_catch_baseline.tsv"
CORE = ROOT / "core" / "src" / "main" / "kotlin"
GEN5 = ROOT / "app" / "src" / "main" / "java" / "com" / "wae" / "agent" / "gen5"
IGNORE_MARK = "// 忽略"

CATCH_RE = re.compile(r"catch\s*\(([^)]*)\)\s*\{")


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def block_after(text: str, brace_index: int) -> str:
    depth = 0
    for i in range(brace_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace_index + 1:i]
    return ""


def is_handled(body: str) -> bool:
    if "Log." in body or "record" in body or "throw" in body or "report" in body:
        return True
    if IGNORE_MARK in body:
        return True
    # 只回一個值、沒有其他動作 → 視為吞掉
    stripped = re.sub(r"//[^\n]*", "", body).strip()
    return not (stripped.startswith("false") or stripped.startswith("null") or
                stripped.startswith("Unit") or stripped == "")


def scan(files: list[Path]) -> list[tuple[str, int, str]]:
    findings = []
    for path in files:
        text = io.open(path, encoding="utf-8", errors="replace").read()
        for m in CATCH_RE.finditer(text):
            body = block_after(text, text.find("{", m.start()))
            if is_handled(body):
                continue
            line = text[:m.start()].count("\n") + 1
            snippet = " ".join(body.split())[:60]
            findings.append((str(path.relative_to(ROOT)), line, snippet))
    return findings


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--write-baseline", action="store_true", help="把目前每檔的數量寫成基準（只能減，不能增）")
    args = parser.parse_args()

    if args.all:
        files = list((ROOT / "app" / "src" / "main").rglob("*.kt")) + list(CORE.rglob("*.kt"))
    else:
        files = list(CORE.rglob("*.kt")) + list(GEN5.rglob("*.kt"))

    findings = scan(files)
    by_file: dict[str, int] = {}
    for rel, _line, _snip in findings:
        by_file[rel] = by_file.get(rel, 0) + 1

    print(f"掃描 {len(files)} 個檔案，找到 {len(findings)} 個「安靜吞掉」的 catch\n")
    for rel, count in sorted(by_file.items(), key=lambda x: -x[1])[:12]:
        print(f"  {count:>3}  {rel}")
    if findings:
        print("\n前 10 個位置：")
        for rel, line, snip in findings[:10]:
            print(f"  {rel}:{line}  「{snip}」")

    print("\n判準：空的 catch，或只回 false/null/Unit；要有 `// 忽略：原因` 才算處理過。")

    if args.write_baseline:
        lines = ["# path\tcount"] + [f"{rel}\t{count}" for rel, count in sorted(by_file.items())]
        io.open(BASELINE, "w", encoding="utf-8", newline="").write("\n".join(lines) + "\n")
        print(f"\n已寫入基準：{BASELINE}")
        return 0

    # 基準守門：既有檔案只能減、不能增；新檔案不允許出現
    if BASELINE.exists():
        allowed = {}
        for raw in io.open(BASELINE, encoding="utf-8").read().splitlines():
            if not raw.strip() or raw.startswith("#"):
                continue
            cells = raw.split("\t")
            if len(cells) >= 2:
                allowed[cells[0]] = int(cells[1])
        growth = [(rel, count, allowed.get(rel, 0)) for rel, count in by_file.items()
                  if count > allowed.get(rel, 0)]
        if growth:
            print("\nGUARD: 安靜 catch 增加了（只能減少）：")
            for rel, now, was in growth:
                print(f"  {rel}: {was} → {now}")
            return 1
        print("\nGUARD: PASS（沒有新增的安靜 catch）")

    if args.strict and findings:
        print("STRICT: 有未標註的 catch")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
