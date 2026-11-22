#!/usr/bin/env python3
"""開放式意圖評測 runner（100+ 題，只讀 logcat，不看畫面文字）。

用法：
  python tools/open_ended_benchmark.py --dry-run
  python tools/open_ended_benchmark.py --tier EASY --limit 10
  python tools/open_ended_benchmark.py                     # 全部 120 題
  python tools/open_ended_benchmark.py --id E01 --id X01

判定訊號（與 AGENTS.md 一致）：
  WAE_EXEC taskResult=SUCCESS|FAIL seq=N
  WAE_AGENT app selected by intent/category: <App>   ← AppSelector 直接選
  WAE_AGENT shortlist=... -> selected=<App>          ← 走 embedding/LLM
  WAE_AGENT_TEMPLATE deterministic plan: ...         ← 模板計畫
"""
from __future__ import annotations

import argparse
import csv
import datetime
import os
import re
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = os.path.join(ROOT, "benchmark", "open_ended_tasks.tsv")
PKG = "com.wae.agent"
ADB_CANDIDATES = [
    r"E:\WAE\android-sdk\platform-tools\adb.exe",
    r"C:\Users\Wade\AppData\Local\Android\Sdk\platform-tools\adb.exe",
]
RE_TASK = re.compile(r"WAE_EXEC:?\s+taskResult=(SUCCESS|FAIL)\s+seq=(\d+)")
RE_APP = re.compile(r"app selected by intent/category: (\S+)")
RE_APP2 = re.compile(r"shortlist=.*?-> selected=(\S+)")
RE_TEMPLATE = re.compile(r"WAE_AGENT_TEMPLATE.*deterministic plan: (.+)")
RE_STEP = re.compile(r"L4 Dispatched")

SERIAL = ""


def find_adb() -> str:
    for p in ADB_CANDIDATES:
        if os.path.isfile(p):
            return p
    return "adb"


ADB = find_adb()


def adb(*args, timeout=120):
    cmd = [ADB]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)


def pick_serial() -> str:
    out = adb("devices").stdout or ""
    devs = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
    if not devs:
        raise SystemExit("找不到裝置（adb devices 為空）")
    physical = [d for d in devs if not d.startswith("emulator-")]
    return (physical or devs)[0]


def load_tasks() -> list[dict]:
    rows = []
    with open(TASKS, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) < 7:
                continue
            rows.append({
                "id": p[0], "tier": p[1], "instruction": p[2], "intent": p[3],
                "apps": p[4], "end_state": p[5], "judge": p[6],
            })
    return rows


def wait_result(timeout_s: int) -> dict:
    started = time.time()
    steps = 0
    app = ""
    template = ""
    while time.time() - started < timeout_s:
        out = (adb("logcat", "-d", "-v", "brief").stdout or "")
        steps = max(steps, len(RE_STEP.findall(out)))
        m_app = RE_APP.search(out) or RE_APP2.search(out)
        if m_app and not app:
            app = m_app.group(1)
        m_tpl = RE_TEMPLATE.search(out)
        if m_tpl and not template:
            template = m_tpl.group(1).strip()
        m = RE_TASK.search(out)
        if m:
            return {
                "ok": m.group(1) == "SUCCESS",
                "elapsed": round(time.time() - started, 1),
                "steps": steps,
                "app": app,
                "template": template,
                "detail": "taskResult",
            }
        time.sleep(3)
    return {"ok": False, "elapsed": round(time.time() - started, 1),
            "steps": steps, "app": app, "template": template, "detail": "timeout"}


def run_task(task: dict, tap_xy: tuple[int, int], timeout_s: int) -> dict:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix=".txt") as f:
        f.write(task["instruction"])
        local = f.name
    try:
        adb("push", local, "/data/local/tmp/debug_command")
        adb("shell", "run-as", PKG, "cp", "/data/local/tmp/debug_command",
            f"/data/data/{PKG}/files/debug_command")
        # 先回首頁，避免上一個任務的畫面干擾
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(0.6)
        adb("logcat", "-c")
        # MainActivity 是 singleTask：先 BACK 收掉，onCreate/onResume 才會重讀 debug_command
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.6)
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(4)
        adb("shell", "input", "tap", str(tap_xy[0]), str(tap_xy[1]))
        res = wait_result(timeout_s)
    finally:
        os.unlink(local)
    return res


def main() -> int:
    global SERIAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", action="append", default=[])
    ap.add_argument("--id", action="append", default=[])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--serial", default=os.environ.get("SERIAL", ""))
    ap.add_argument("--taps", default="540:1269", help="執行按鈕座標 x:y")
    ap.add_argument("--timeout", type=int, default=180, help="單題逾時秒數")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    tasks = load_tasks()
    if args.tier:
        tasks = [t for t in tasks if t["tier"] in args.tier]
    if args.id:
        tasks = [t for t in tasks if t["id"] in args.id]
    if args.limit:
        tasks = tasks[: args.limit]

    if args.dry_run:
        for t in tasks:
            print(f"{t['id']:<5} {t['tier']:<7} {t['intent']:<15} {t['instruction']}")
        print(f"[dry-run] {len(tasks)} 題")
        return 0

    SERIAL = args.serial or pick_serial()
    print(f"adb={ADB} serial={SERIAL} 題數={len(tasks)}")
    tap_xy = tuple(int(x) for x in args.taps.split(":"))

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = args.out or os.path.join(r"E:\WAE\reports", f"open_ended-{stamp}.csv")
    os.makedirs(os.path.dirname(out), exist_ok=True)

    rows = []
    for i, t in enumerate(tasks, 1):
        r = run_task(t, tap_xy, args.timeout)
        row = {**t, **r}
        rows.append(row)
        mark = "PASS" if r["ok"] else "FAIL"
        print(f"[{i}/{len(tasks)}] {t['id']} {t['tier']:<7} {mark} {r['elapsed']}s "
              f"steps={r['steps']} app={r['app'] or '-'} intent={t['intent']}")

    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    # 摘要
    print("\n=== 摘要 ===")
    for key in ("tier", "intent"):
        agg = {}
        for r in rows:
            a = agg.setdefault(r[key], [0, 0])
            a[0] += 1
            a[1] += 1 if r["ok"] else 0
        for k, (n, ok) in sorted(agg.items()):
            print(f"{key}={k:<15} n={n:<3} 成功率={ok / n * 100:5.1f}%")
    total = len(rows)
    ok = sum(1 for r in rows if r["ok"])
    print(f"總計 n={total} 成功率={ok / total * 100:.1f}%  報表={out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
