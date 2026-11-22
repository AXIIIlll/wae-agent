#!/usr/bin/env python3
"""
G0：Grounding trace 收集器（真機 benchmark，stdlib only）。

原理：沿用 `tools/run_benchmark.py` 的 debug_command 注入流程：
  1. 把中文指令寫成 debug_command 推進 App 私有目錄
  2. 喚起 MainActivity 讓 onResume 自動填入指令，再點「執行」
  3. 等 `WAE_EXEC taskResult=SUCCESS|FAIL seq=N`（內部訊號，不看畫面文字）
  4. 用 `adb exec-out run-as` 把 `files/grounding_trace.jsonl` 拉回 E 槽

前置條件（缺一項就會拿到空檔，不是本腳本的錯）：
  - 手機已安裝 debug 版 APK、無障礙服務已啟用
  - 服務層已把 grounding 決策接上 `GroundingTraceRecorder.record()`
    並在任務結束時呼叫 `flushToFile(context)`（Lead 的 G3 整合）
  - App 是 debuggable（release 版刻意不寫 trace）

用法：
  python tools/grounding/capture_benchmark.py --limit 3        # 先試跑 3 題
  python tools/grounding/capture_benchmark.py --tier EASY
  python tools/grounding/capture_benchmark.py --id E01 --id M02
  python tools/grounding/capture_benchmark.py --dry-run        # 只印任務清單

輸出（全部在 E 槽）：
  tools/grounding/traces/<timestamp>_<taskId>.jsonl   # 一題一檔，一行一個 trace
  tools/grounding/traces/<timestamp>_manifest.tsv     # 執行摘要（id/tier/ok/步數/trace 行數）
"""
import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TASKS_FILE = os.path.join(ROOT, "benchmark", "tasks.tsv")
DEFAULT_OUT_DIR = os.path.join(ROOT, "tools", "grounding", "traces")

PKG = "com.wae.agent"
DEVICE_TRACE = "files/grounding_trace.jsonl"
LOG_TASK_RESULT = re.compile(r"WAE_EXEC:?\s+taskResult=(SUCCESS|FAIL)\s+seq=(\d+)")
LOG_STEP = re.compile(r"L4 Dispatched")

ADB_CANDIDATES = [
    r"E:\WAE\android-sdk\platform-tools\adb.exe",
    r"C:\Users\Wade\AppData\Local\Android\Sdk\platform-tools\adb.exe",
]


def display_path(path):
    """相對專案根的路徑；跨磁碟機時退回絕對路徑。"""
    try:
        return os.path.relpath(path, ROOT)
    except ValueError:
        return path


def find_adb():
    """adb 順序：$ADB → PATH → 專案慣用位置（SDK 已搬 E 槽）。"""
    env = os.environ.get("ADB")
    candidates = []
    if env:
        candidates.append(env)
    on_path = shutil.which("adb")
    if on_path:
        candidates.append(on_path)
    candidates.extend(ADB_CANDIDATES)
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return candidate
    raise SystemExit("找不到 adb：請設定環境變數 ADB，或確認 SDK 在 E:\\WAE\\android-sdk")


class Adb(object):
    def __init__(self, adb_path, serial):
        self.adb_path = adb_path
        self.serial = serial

    def run(self, *args, **kwargs):
        timeout = kwargs.pop("timeout", 60)
        binary = kwargs.pop("binary", False)
        command = [self.adb_path]
        if self.serial:
            command += ["-s", self.serial]
        command += list(args)
        return subprocess.run(
            command,
            capture_output=True,
            timeout=timeout,
            text=not binary,
            **({} if binary else {"encoding": "utf-8", "errors": "replace"}),
        )


def pick_serial(adb_path):
    output = subprocess.run(
        [adb_path, "devices"], capture_output=True, text=True, encoding="utf-8", errors="replace"
    ).stdout
    devices = [
        line.split("\t")[0].strip()
        for line in output.splitlines()[1:]
        if "\tdevice" in line
    ]
    if not devices:
        raise SystemExit("找不到已連線裝置：請確認 USB 偵錯與 adb devices")
    return devices[0]


def load_tasks(path):
    """與 tools/run_benchmark.py 相同的 tasks.tsv 格式。"""
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 7 or parts[0] == "id":
                continue
            rows.append(
                {
                    "id": parts[0],
                    "tier": parts[1],
                    "app_family": parts[2],
                    "cmd": parts[3],
                    "min_steps": int(parts[4]),
                    "timeout_s": int(parts[5]),
                    "notes": parts[6],
                }
            )
    return rows


def inject_debug_command(adb, command):
    """寫暫存檔 → push → run-as cp 進 files/debug_command（adb 不支援中文輸入）。"""
    handle = tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", newline="\n", suffix=".cmd", delete=False
    )
    try:
        handle.write(command)
        handle.close()
        adb.run("push", handle.name, "/data/local/tmp/wae_grounding_cmd")
        adb.run(
            "shell",
            "run-as",
            PKG,
            "cp",
            "/data/local/tmp/wae_grounding_cmd",
            "/data/data/%s/files/debug_command" % PKG,
        )
    finally:
        try:
            os.unlink(handle.name)
        except OSError:
            pass


def clear_device_trace(adb):
    adb.run("shell", "run-as", PKG, "rm", "-f", DEVICE_TRACE)


def pull_trace_text(adb):
    """回傳裝置上的 trace JSONL 文字；檔案不存在時回傳空字串。"""
    result = adb.run("exec-out", "run-as", PKG, "cat", DEVICE_TRACE, timeout=120)
    if result.returncode != 0:
        return ""
    return result.stdout or ""


def parse_trace_lines(text):
    """逐行驗證 JSON；回傳 (合法行 list, 壞行數)。"""
    rows = []
    bad = 0
    for line in (text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            payload = json.loads(line)
        except ValueError:
            bad += 1
            continue
        if not isinstance(payload, dict) or not isinstance(payload.get("candidates"), list):
            bad += 1
            continue
        rows.append(payload)
    return rows, bad


def wait_for_task_result(adb, timeout_s):
    """輪詢 logcat 直到出現 WAE_EXEC taskResult；回傳 (ok, steps, detail)。"""
    started = time.time()
    deadline = started + timeout_s
    steps = 0
    while time.time() < deadline:
        output = adb.run("logcat", "-d", "-v", "brief", timeout=60).stdout or ""
        steps = max(steps, len(LOG_STEP.findall(output)))
        match = LOG_TASK_RESULT.search(output)
        if match:
            return match.group(1) == "SUCCESS", steps, "taskResult"
        time.sleep(3)
    return False, steps, "timeout"


def run_task(adb, task, args):
    """執行一題並把 trace 存成一檔；回傳 manifest 列。"""
    started = time.time()
    if not args.keep_device_file:
        clear_device_trace(adb)

    inject_debug_command(adb, task["cmd"])
    adb.run("logcat", "-c")
    # 先把 MainActivity 收掉：debug_command 只在 onCreate 讀取，單例啟動不會重跑
    adb.run("shell", "input", "keyevent", "BACK")
    time.sleep(0.8)
    adb.run("shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(args.startup_wait)
    adb.run("shell", "input", "tap", str(args.taps[0]), str(args.taps[1]))

    ok, steps, detail = wait_for_task_result(adb, task["timeout_s"])
    elapsed = round(time.time() - started, 1)
    if args.flush_wait > 0:
        time.sleep(args.flush_wait)

    trace_text = pull_trace_text(adb)
    traces, bad_lines = parse_trace_lines(trace_text)
    trace_file = ""
    if traces:
        trace_file = os.path.join(args.out, "%s_%s.jsonl" % (args.stamp, task["id"]))
        with open(trace_file, "w", encoding="utf-8", newline="\n") as handle:
            for row in traces:
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=False) + "\n")
    return {
        "id": task["id"],
        "tier": task["tier"],
        "ok": ok,
        "detail": detail,
        "elapsed_s": elapsed,
        "steps": steps,
        "trace_lines": len(traces),
        "bad_lines": bad_lines,
        "trace_file": display_path(trace_file) if trace_file else "",
    }


def write_manifest(args, records):
    path = os.path.join(args.out, "%s_manifest.tsv" % args.stamp)
    header = "id\ttier\tok\tdetail\telapsed_s\tsteps\ttrace_lines\tbad_lines\ttrace_file\n"
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(header)
        for row in records:
            handle.write(
                "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n"
                % (
                    row["id"],
                    row["tier"],
                    "1" if row["ok"] else "0",
                    row["detail"],
                    row["elapsed_s"],
                    row["steps"],
                    row["trace_lines"],
                    row["bad_lines"],
                    row["trace_file"],
                )
            )
    return path


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    parser = argparse.ArgumentParser(description="收集真機 grounding trace（G0）")
    parser.add_argument("--serial", default=os.environ.get("SERIAL", ""))
    parser.add_argument("--tier", action="append", default=[], help="只跑指定難度（可重複）")
    parser.add_argument("--id", action="append", default=[], help="只跑指定任務 id（可重複）")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 題（試跑用）")
    parser.add_argument("--taps", default="540:1269", help="執行按鈕座標 x:y")
    parser.add_argument("--out", default=DEFAULT_OUT_DIR, help="trace 輸出目錄")
    parser.add_argument("--startup-wait", type=float, default=4.0, help="開 App 後等幾秒再點執行")
    parser.add_argument("--flush-wait", type=float, default=1.5, help="任務結束後等幾秒讓服務 flush trace")
    parser.add_argument("--keep-device-file", action="store_true", help="不在每題前清空裝置端 trace")
    parser.add_argument("--dry-run", action="store_true", help="只印任務清單，不碰裝置")
    args = parser.parse_args()

    try:
        tap_x, tap_y = [int(part) for part in args.taps.split(":")]
    except ValueError:
        raise SystemExit("--taps 格式應為 x:y，例如 540:1269")
    args.taps = (tap_x, tap_y)

    tasks = load_tasks(TASKS_FILE)
    if args.tier:
        tasks = [task for task in tasks if task["tier"] in args.tier]
    if args.id:
        tasks = [task for task in tasks if task["id"] in args.id]
    if args.limit:
        tasks = tasks[: args.limit]
    if not tasks:
        print("沒有符合條件的任務")
        return 1

    if args.dry_run:
        for task in tasks:
            print("%s\t%s\t%s\t%s" % (task["id"], task["tier"], task["timeout_s"], task["cmd"]))
        print("\n[dry-run] 共 %d 題，未連接裝置。" % len(tasks))
        return 0

    adb_path = find_adb()
    serial = args.serial or pick_serial(adb_path)
    adb = Adb(adb_path, serial)
    print("adb=%s serial=%s 任務數=%d" % (adb_path, serial, len(tasks)))

    os.makedirs(args.out, exist_ok=True)
    args.stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")

    records = []
    for index, task in enumerate(tasks, 1):
        record = run_task(adb, task, args)
        records.append(record)
        print(
            "[%d/%d] %s %-7s %s %5.1fs steps=%-3d trace=%-3d bad=%-2d"
            % (
                index,
                len(tasks),
                record["id"],
                record["tier"],
                "PASS" if record["ok"] else "FAIL",
                record["elapsed_s"],
                record["steps"],
                record["trace_lines"],
                record["bad_lines"],
            )
        )
        if record["trace_lines"] == 0:
            print(
                "  ! 沒收到 trace：確認 debug 版 APK、無障礙服務，"
                "且服務層已呼叫 GroundingTraceRecorder.flushToFile()"
            )

    manifest = write_manifest(args, records)
    total_lines = sum(record["trace_lines"] for record in records)
    print("\n=== 摘要 ===")
    print("任務 %d、成功 %d、trace 步數 %d" % (
        len(records), sum(1 for r in records if r["ok"]), total_lines))
    print("trace 目錄：%s" % display_path(args.out))
    print("manifest：%s" % display_path(manifest))
    print("下一步：python tools/grounding/build_fixtures.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
