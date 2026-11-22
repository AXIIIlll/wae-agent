"""每日回歸：一個指令跑完「自我測試 + 對話劇本」，輸出一行結果。

為什麼要有：真機不常在線，模擬器是唯一每天都能跑的地方。
把兩個最有意義的檢查串成一支，讓「今天有沒有退步」變成 30 秒的判斷。

用法：
    python tools/daily.py                 # emulator-5554
    python tools/daily.py -s R5CX71DTN7L  # 真機
    python tools/daily.py --no-dialogue   # 只跑自我測試（約 2 分鐘）

輸出（一行）：
    DAILY PASS  selftest=9/9  dialogue=10/10  312s

歷史：`bench/daily.tsv`（只留最近 30 次）。
"""

import argparse
import io
import subprocess
import sys
import time
from pathlib import Path

ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
ROOT = Path(__file__).resolve().parent.parent
from device_probe import Device

PKG = "com.wae.agent"
FILES = f"/sdcard/Android/data/{PKG}/files"
BENCH = ROOT / "bench" / "daily.tsv"
KEEP = 30


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def adb(serial: str, *args: str) -> str:
    cmd = [str(ADB)] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (out.stdout or "") + (out.stderr or "")


def run_selftest(serial: str, wait: int = 150) -> tuple[int, int]:
    out = Device(serial).run("!selftest", wait_s=wait)
    import re

    m = re.search(r"(\d+)/(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


def run_dialogue(serial: str) -> tuple[int, int, str]:
    out = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "dialogue_script.py"), "-s", serial],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(ROOT),
    )
    text = (out.stdout or "") + (out.stderr or "")
    import re

    m = re.search(r"總計：(\d+)/(\d+)", text)
    return ((int(m.group(1)), int(m.group(2)), text) if m else (0, 0, text))


def append_history(line: str) -> None:
    rows = []
    if BENCH.exists():
        rows = [l for l in io.open(BENCH, encoding="utf-8").read().splitlines() if l.strip()]
    rows.append(line)
    rows = rows[-KEEP:]
    BENCH.parent.mkdir(parents=True, exist_ok=True)
    io.open(BENCH, "w", encoding="utf-8", newline="").write("\n".join(rows) + "\n")


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="emulator-5554")
    parser.add_argument("--no-dialogue", action="store_true")
    parser.add_argument("-v", "--verbose", action="store_true", help="印出對話劇本每一條")
    args = parser.parse_args()

    started = time.monotonic()
    st_pass, st_total = run_selftest(args.serial)
    st_raw = f"selftest {st_pass}/{st_total}"
    dl_pass, dl_total, dl_text = (0, 0, "")
    if not args.no_dialogue:
        dl_pass, dl_total, dl_text = run_dialogue(args.serial)
        if args.verbose:
            print(dl_text)

    # 留一份完整輸出：daily 紅了不必重跑才知道哪一條、為什麼
    log_path = BENCH.parent / "daily_last.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    io.open(log_path, "w", encoding="utf-8", newline="").write(
        st_raw + "\n\n" + dl_text
    )

    ok = st_total > 0 and st_pass == st_total
    if not args.no_dialogue:
        ok = ok and dl_total > 0 and dl_pass == dl_total

    elapsed = int(time.monotonic() - started)
    line = (
        f"{time.strftime('%Y-%m-%dT%H:%M:%S')}\t{'PASS' if ok else 'FAIL'}"
        f"\tselftest={st_pass}/{st_total}\tdialogue={dl_pass}/{dl_total}\t{elapsed}s"
    )
    append_history(line)
    print("DAILY " + ("PASS" if ok else "FAIL") +
          f"  selftest={st_pass}/{st_total}  dialogue={dl_pass}/{dl_total}  {elapsed}s")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
