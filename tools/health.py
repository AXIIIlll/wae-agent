"""一條命令看整體健康：I1 守門 → 清單一致性 →（可選）單元測試 → 裝置檢查 R1–R7。

用法：
    python tools/health.py                    # 守門 + 裝置檢查（約 8 分鐘）
    python tools/health.py --with-tests       # 再加單元測試（約 11 分鐘）
    python tools/health.py --guards-only      # 只跑守門（幾秒）
    python tools/health.py -s emulator-5554

為什麼要有這個：收尾時要跑的東西有四種，每次手動拼命令就會漏。
這裡把它們串成一個有 exit code 的檢查——**漏跑等於沒跑**。
"""

import argparse
import datetime
import io
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def run(name: str, cmd: list[str], timeout: int = 1800) -> tuple[str, bool, str]:
    """回傳 (名稱, 是否通過, 摘要)。"""
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    try:
        out = subprocess.run(
            cmd, cwd=str(ROOT), capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout, env=env,
        )
    except subprocess.TimeoutExpired:
        return name, False, "逾時"
    text = (out.stdout or "") + (out.stderr or "")
    ok = out.returncode == 0
    summary = ""
    for line in text.splitlines():
        if "GUARD" in line or "一致性檢查" in line or "總計：" in line or "BUILD" in line:
            summary = line.strip()
    return name, ok, summary or ("PASS" if ok else "FAIL")


def verification_status() -> list[str]:
    """真機驗證狀態：幾項已驗、最舊的一項幾天前。"""
    import datetime

    path = ROOT / "bench" / "device_verification.tsv"
    if not path.exists():
        return ["真機驗證狀態：沒有紀錄檔"]

    today = datetime.date.today()
    verified = 0
    oldest_days = None
    never = []
    for raw in io.open(path, encoding="utf-8").read().splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        cells = raw.split("\t")
        if len(cells) < 4:
            continue
        cap_id, ts, _apk, result = cells[0], cells[1].strip(), cells[2], cells[3].strip()
        if result in ("PASS", "FAIL") and ts:
            verified += 1
            try:
                days = (today - datetime.date.fromisoformat(ts[:10])).days
                oldest_days = days if oldest_days is None else max(oldest_days, days)
            except ValueError:
                pass
        else:
            never.append(cap_id)

    line = f"真機驗證：已驗 {verified} 項"
    if oldest_days is not None:
        line += f"，最舊的一次 {oldest_days} 天前"
    if never:
        line += f"，未驗：{', '.join(never)}"
    return [line]


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="")
    parser.add_argument("--with-tests", action="store_true", help="加跑單元測試（core + app）")
    parser.add_argument("--guards-only", action="store_true", help="只跑靜態守門")
    parser.add_argument("--no-dialogue", action="store_true", help="跳過對話劇本（省 4 分鐘）")
    args = parser.parse_args()

    checks: list[tuple[str, list[str]]] = [
        ("I1 裝置入口守門", [PY, "tools/gen4_inventory.py"]),
        ("能力清單一致性", [PY, "tools/doc_consistency.py"]),
        ("安靜 catch 守門", [PY, "tools/silent_catch_scan.py", "--all"]),
        ("文件數字一致", [PY, "tools/doc_facts.py", "--strict"]),
    ]
    if args.with_tests:
        checks.append((
            "單元測試",
            [r".\\gradlew.bat", ":core:test", ":app:testFullDebugUnitTest", "--offline",
             "--console=plain", "--no-daemon"],
        ))
    if not args.guards_only:
        cmd = [PY, "tools/device_checklist.py", "--full"]
        if args.serial:
            cmd += ["-s", args.serial]
        checks.append(("裝置檢查 R1–R7", cmd))
        if not args.no_dialogue:
            dlg = [PY, "tools/dialogue_script.py"]
            if args.serial:
                dlg += ["-s", args.serial]
            checks.append(("對話劇本", dlg))

    for line in verification_status():
        print(line)

    results = []
    for name, cmd in checks:
        print(f"… {name}")
        results.append(run(name, cmd))

    print("\n健康檢查：")
    for name, ok, summary in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {name:<16} {summary[:90]}")

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n總計：{passed}/{len(results)}")

    # 只有跑到裝置檢查才記歷史（守門 only 的分數不能當「整體健康」）
    if not args.guards_only:
        hist = ROOT / "bench" / "health_history.tsv"
        hist.parent.mkdir(parents=True, exist_ok=True)
        new = not hist.exists()
        with io.open(hist, "a", encoding="utf-8", newline="") as fh:
            if new:
                fh.write("# timestamp\tpass\ttotal\tfailed\n")
            failed = ",".join(name for name, ok, _ in results if not ok)
            fh.write("%s\t%d\t%d\t%s\n" % (
                datetime.datetime.now().isoformat(timespec="seconds"), passed, len(results), failed))

    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
