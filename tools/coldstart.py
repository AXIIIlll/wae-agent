"""冷啟動量測：同一台裝置上，各 App 從「送出啟動」到「首幀」要多久。

為什麼要量：`perception.launch_settle_timeout_ms` 現在是 12 秒（經驗值）。
經驗值會過期——真機接上、模擬器變慢、換 Android 版本，都可能不同。
這支工具把「實測」變成一個可重跑的動作，而不是一次性的猜測。

用法：
    python tools/coldstart.py                      # 預設 3 個 App × 2 次
    python tools/coldstart.py --rounds 3
    python tools/coldstart.py --apps com.android.chrome=Chrome

輸出：`bench/coldstart.tsv`（每次一列，附平均值）
"""

import argparse
import io
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from device_probe import Device  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench" / "coldstart.tsv"

DEFAULT_APPS = [
    ("com.android.chrome", "Chrome"),
    ("com.google.android.apps.maps", "地圖"),
    ("com.android.camera2", "相機"),
    ("com.android.settings", "設定"),
]


def _console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def _launcher_activity(dev: Device, package: str) -> str:
    out = dev.adb("shell", "cmd", "package", "resolve-activity", "--brief",
                  "-c", "android.intent.category.LAUNCHER", package)
    for line in reversed(out.splitlines()):
        line = line.strip()
        if "/" in line and not line.startswith("priority") and "No activity" not in line:
            return line
    return ""


def measure(dev: Device, package: str, rounds: int) -> list[int]:
    """量「送出啟動 → 首幀」；先解析出真正的 launcher activity，再 am start -W。"""
    activity = _launcher_activity(dev, package)
    times = []
    for _ in range(rounds):
        dev.adb("shell", "am", "force-stop", package)   # 只有目標 App，不是 com.wae.agent
        time.sleep(0.8)
        if activity:
            out = dev.adb("shell", "am", "start", "-W", "-n", activity)
        else:
            out = dev.adb("shell", "monkey", "-p", package, "-c",
                          "android.intent.category.LAUNCHER", "1")
        m = re.search(r"TotalTime:\s*(\d+)", out)
        if m:
            times.append(int(m.group(1)))
        dev.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(0.5)
    return times


def main() -> int:
    _console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="emulator-5554")
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--apps", default="", help="pkg=名稱，逗號分隔")
    args = parser.parse_args()

    apps = DEFAULT_APPS
    if args.apps:
        apps = []
        for item in args.apps.split(","):
            pkg, _, name = item.partition("=")
            apps.append((pkg.strip(), name.strip() or pkg.strip()))

    dev = Device(args.serial)
    rows = []
    print(f"冷啟動量測（{args.rounds} 次／App）")
    for package, name in apps:
        times = measure(dev, package, args.rounds)
        avg = sum(times) // len(times) if times else 0
        rows.append((name, package, times, avg))
        print(f"  {name:<6} {times}  平均 {avg} ms")

    BENCH.parent.mkdir(parents=True, exist_ok=True)
    new = not BENCH.exists()
    with io.open(BENCH, "a", encoding="utf-8", newline="") as f:
        if new:
            f.write("# timestamp\tapp\tpackage\tavg_ms\tsamples\n")
        ts = time.strftime("%Y-%m-%dT%H:%M:%S")
        for name, package, times, avg in rows:
            f.write(f"{ts}\t{name}\t{package}\t{avg}\t{','.join(map(str, times))}\n")
    print(f"\n已寫入 {BENCH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
