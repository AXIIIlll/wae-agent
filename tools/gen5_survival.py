#!/usr/bin/env python3
"""Gen-5 存活率紀錄（D9）。

每次呼叫跑一輪冒煙測試，把結果追加到 bench/smoke_history.tsv，並印出最近 N 輪的存活率。
「存活率」＝ 這一輪裡仍然通過的案例比例；連續掉下去就是版本腐爛的訊號。

用法：
    python tools/gen5_survival.py
    python tools/gen5_survival.py --runs 3
    python tools/gen5_survival.py --skip-install --runs 1
"""

from __future__ import annotations

import argparse
import datetime as dt
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SMOKE = ROOT / "tools/gen5_smoke.py"
HISTORY = ROOT / "bench/smoke_history.tsv"
HEADER = "timestamp\truns_passed\truns_total\tdetail\n"


def parse_result(output: str) -> tuple[str, str]:
    """從冒煙輸出抓 RESULT 行；回傳 (verdict, detail)。"""
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("RESULT:"):
            return line, line
    return "RESULT: UNKNOWN", "no RESULT line"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()

    HISTORY.parent.mkdir(parents=True, exist_ok=True)
    if not HISTORY.exists():
        HISTORY.write_text(HEADER, encoding="utf-8")

    passes = 0
    details: list[str] = []

    for i in range(args.runs):
        cmd = [sys.executable, str(SMOKE)]
        if args.skip_install:
            cmd.append("--skip-install")
        proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        result, detail = parse_result(proc.stdout)
        ok = "PASS" in result
        passes += 1 if ok else 0
        details.append(f"{i + 1}:{result}")
        print(f"run {i + 1}/{args.runs}: {result}")

    stamp = dt.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
    with HISTORY.open("a", encoding="utf-8") as fh:
        fh.write(f"{stamp}\t{passes}\t{args.runs}\t{' | '.join(details)}\n")

    rows = [ln for ln in HISTORY.read_text(encoding="utf-8").splitlines()[1:] if ln.strip()]
    recent = rows[-RECENT_RUNS:]
    recent_pass = sum(int(r.split("\t")[1]) for r in recent)
    recent_total = sum(int(r.split("\t")[2]) for r in recent)
    rate = (recent_pass / recent_total * 100.0) if recent_total else 0.0

    print(f"history rows={len(rows)} recent={len(recent)} survival={rate:.1f}%")
    return 0 if passes == args.runs else 1


RECENT_RUNS = 10

if __name__ == "__main__":
    sys.exit(main())
