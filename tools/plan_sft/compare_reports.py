"""把多份 eval_plans.py 的 JSON 報表併成比較表。

用法：
  python tools/plan_sft/compare_reports.py A.json B.json C.json
  python tools/plan_sft/compare_reports.py --glob "E:/WAE/reports/eval_*.json"
"""
from __future__ import annotations

import argparse
import glob
import json
import os


def load(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reports", nargs="*")
    ap.add_argument("--glob", default="")
    args = ap.parse_args()

    paths = list(args.reports)
    if args.glob:
        paths += sorted(glob.glob(args.glob))
    if not paths:
        raise SystemExit("沒有報表")

    header = f"{'report':<26}{'file':<20}{'n':>4}{'schema':>8}{'goalKind':>10}{'steps':>8}{'app':>7}{'exact':>7}{'askR':>7}"
    print(header)
    print("-" * len(header))
    for p in paths:
        try:
            data = load(p)
        except Exception as e:
            print(f"{os.path.basename(p):<26} 讀取失敗：{e}")
            continue
        for fname, r in data.items():
            name = os.path.basename(p).replace("eval_", "").replace(".json", "")
            print(
                f"{name:<26}{fname:<20}{r.get('n', 0):>4}"
                f"{r.get('schema_ok', 0):>8.1f}{r.get('goal_kind', 0):>10.1f}"
                f"{r.get('steps_eq', 0):>8.1f}{r.get('app_ok', 0):>7.1f}"
                f"{r.get('exact', 0):>7.1f}"
                f"{(r.get('ask_recall') if r.get('ask_recall') is not None else -1):>7.1f}"
            )


if __name__ == "__main__":
    main()
