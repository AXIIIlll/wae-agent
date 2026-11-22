#!/usr/bin/env python3
"""
100 任務評測執行器。

原理：把手語指令寫成 debug_command 檔案推進 App 私有目錄，
喚起 App 讓 onResume 自動填入指令，再點「執行」，
最後從 logcat 判斷該任務是否 `stepOk=true`。

用法：
  python tools/run_benchmark.py                     # 全部 100 任務
  python tools/run_benchmark.py --tier EASY         # 只跑某一層
  python tools/run_benchmark.py --id H03 --id X05   # 只跑指定任務
  python tools/run_benchmark.py --limit 5           # 只跑前 N 個（試跑）
  python tools/run_benchmark.py --taps 540:1269 --input 540:1099

輸出：benchmark/results/<timestamp>.tsv + 終端摘要（各難度成功率 / 平均耗時）
"""
import argparse
import datetime
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = os.path.join(ROOT, "benchmark", "tasks.tsv")
RESULTS_DIR = os.path.join(ROOT, "benchmark", "results")
ADB = os.environ.get(
    "ADB",
    r"C:\Users\Wade\AppData\Local\Android\Sdk\platform-tools\adb.exe",
)
PKG = "com.wae.agent"


def load_tasks(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 7 or parts[0] == "id":
                continue
            rows.append({
                "id": parts[0],
                "tier": parts[1],
                "app_family": parts[2],
                "cmd": parts[3],
                "min_steps": int(parts[4]),
                "timeout_s": int(parts[5]),
                "notes": parts[6],
            })
    return rows


def adb(*args, timeout=60):
    return subprocess.run(
        [ADB, "-s", SERIAL, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def run_task(task, tap_xy, input_xy):
    """回傳 (ok, elapsed_s, steps, detail)"""
    cmd_file_local = os.path.join(ROOT, "benchmark", ".cmd.tmp")
    with open(cmd_file_local, "w", encoding="utf-8", newline="\n") as f:
        f.write(task["cmd"])

    adb("push", cmd_file_local, "/data/local/tmp/debug_command")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/debug_command",
        f"/data/data/{PKG}/files/debug_command")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(4)
    adb("shell", "input", "tap", str(tap_xy[0]), str(tap_xy[1]))

    started = time.time()
    deadline = started + task["timeout_s"]
    steps = 0
    ok = False
    detail = "timeout"
    while time.time() < deadline:
        out = adb("logcat", "-d", "-v", "brief").stdout or ""
        steps = max(steps, len(re.findall(r"L4 Dispatched", out)))
        m = re.search(r"finished with stepOk=(true|false)", out)
        if m:
            ok = m.group(1) == "true"
            detail = "stepOk"
            break
        time.sleep(3)
    elapsed = round(time.time() - started, 1)
    return ok, elapsed, steps, detail


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", action="append", default=[])
    ap.add_argument("--id", action="append", default=[])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--taps", default="540:1269")
    ap.add_argument("--input", default="540:1099")
    args = ap.parse_args()

    global SERIAL
    SERIAL = os.environ.get("SERIAL", "")
    if not SERIAL:
        out = subprocess.run(
            [ADB, "devices"], capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        ).stdout
        devs = [l.split("\t")[0] for l in out.splitlines()[1:] if "\tdevice" in l]
        if not devs:
            print("找不到裝置")
            return 2
        SERIAL = devs[0]

    tap_xy = tuple(int(x) for x in args.taps.split(":"))
    input_xy = tuple(int(x) for x in args.input.split(":"))

    tasks = load_tasks(TASKS)
    if args.tier:
        tasks = [t for t in tasks if t["tier"] in args.tier]
    if args.id:
        tasks = [t for t in tasks if t["id"] in args.id]
    if args.limit:
        tasks = tasks[: args.limit]
    if not tasks:
        print("沒有符合條件的任務")
        return 1

    os.makedirs(RESULTS_DIR, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    out_path = os.path.join(RESULTS_DIR, f"{stamp}.tsv")

    rows = []
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("id\ttier\tok\telapsed_s\tsteps\tmin_steps\tdetail\tcmd\n")
        for i, t in enumerate(tasks, 1):
            ok, elapsed, steps, detail = run_task(t, tap_xy, input_xy)
            print(f"[{i}/{len(tasks)}] {t['id']} {t['tier']:<7} {'PASS' if ok else 'FAIL'} "
                  f"{elapsed:>5}s steps={steps:<3} {t['cmd'][:28]}")
            f.write(f"{t['id']}\t{t['tier']}\t{ok}\t{elapsed}\t{steps}\t{t['min_steps']}\t{detail}\t{t['cmd']}\n")
            f.flush()
            rows.append({"tier": t["tier"], "ok": ok, "elapsed": elapsed, "steps": steps,
                         "min_steps": t["min_steps"]})

    print("\n=== 摘要 ===")
    for tier in ("EASY", "MEDIUM", "HARD", "EXTREME"):
        sub = [r for r in rows if r["tier"] == tier]
        if not sub:
            continue
        rate = 100.0 * sum(1 for r in sub if r["ok"]) / len(sub)
        avg = sum(r["elapsed"] for r in sub) / len(sub)
        depth = sum(1 for r in sub if r["steps"] >= r["min_steps"])
        print(f"{tier:<7} n={len(sub):<3} 成功率={rate:5.1f}%  平均耗時={avg:6.1f}s  達到預期步深={depth}/{len(sub)}")
    print(f"\n結果檔：{out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
