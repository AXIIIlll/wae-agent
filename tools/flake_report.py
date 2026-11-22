"""多次基線結果比對：找出「同一條指令有時過、有時不過」的抖動。

用法：
    python tools/flake_report.py bench/flake_run_1.tsv bench/flake_run_2.tsv bench/flake_run_3.tsv
    python tools/flake_report.py bench/flake_run_*.tsv

為什麼要這個工具：一次 38/39 看不出是哪一條在抖；跑三次、逐條統計，
才知道是「固定某一條慢」還是「隨機掉條」。猜測會修錯地方，統計不會。
"""

import io
import sys
from collections import defaultdict
from pathlib import Path


def load(path: Path) -> list[tuple[str, str]]:
    """保留順序與重複：同一條指令在基線裡會出現多次（例如「回到主頁」）。"""
    out: list[tuple[str, str]] = []
    if not path.exists():
        return out
    for raw in io.open(path, encoding="utf-8").read().splitlines():
        if not raw or raw.startswith("#"):
            continue
        cells = raw.split("\t")
        if len(cells) < 4:
            continue
        out.append((cells[0], cells[3]))
    return out


def main(argv: list[str]) -> int:
    paths = [Path(p) for p in argv[1:]]
    if not paths:
        print("用法：python tools/flake_report.py <run1.tsv> <run2.tsv> ...")
        return 2

    runs = [load(p) for p in paths]
    runs = [r for r in runs if r]
    if not runs:
        print("沒有可讀的結果檔")
        return 2

    stats: dict[str, list[str]] = defaultdict(list)
    for run in runs:
        for command, verdict in run:
            stats[command].append(verdict)

    total_rows = sum(len(r) for r in runs)
    print(f"讀取 {len(runs)} 次執行、{total_rows} 筆結果，共 {len(stats)} 種指令\n")
    flaky = []
    for command, verdicts in stats.items():
        passed = verdicts.count("PASS")
        if passed != len(verdicts):
            flaky.append((command, passed, len(verdicts)))

    if not flaky:
        print("沒有抖動：所有指令在每次執行都通過")
        return 0

    print("抖動的指令（通過次數/總次數）：")
    for command, passed, total in sorted(flaky, key=lambda x: x[1]):
        print(f"  {command}  {passed}/{total}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
