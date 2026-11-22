#!/usr/bin/env python3
"""Gen-5 冒煙測試（模擬器 / 真機通用）。

為什麼是 Python 而不是 PowerShell：Windows PowerShell 5.1 讀沒有 BOM 的 .ps1
會用 ANSI 解碼，中文命令會被靜默破壞（實際踩過：所有指令都變成 unknown_verb）。
Python 的 UTF-8 處理是明確的，不會有這種隱形問題。

用法：
    python tools/gen5_smoke.py
    python tools/gen5_smoke.py --skip-install
    python tools/gen5_smoke.py --apk app/build/outputs/apk/lite/debug/app-lite-debug.apk
"""

from __future__ import annotations

import argparse
import io
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
from device_probe import Device

PKG = "com.wae.agent"
DEVICE_DIR = f"/sdcard/Android/data/{PKG}/files"
DEFAULT_APK = ROOT / "app/build/outputs/apk/lite/debug/app-lite-debug.apk"
CASES = ROOT / "tools/gen5_smoke_cases.tsv"

# 由 --serial 設定；空字串＝用唯一的裝置
SERIAL = ""


def adb(*args: str, check: bool = False) -> str:
    cmd = [str(ADB), *args]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr}")
    return (result.stdout or "") + (result.stderr or "")


def load_cases(path: Path) -> list[tuple[str, str]]:
    cases: list[tuple[str, str]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        cases.append((parts[0].strip(), parts[1].strip()))
    if not cases:
        raise RuntimeError(f"no cases parsed from {path}")
    return cases


def run_command(command: str, wait_s: int) -> str:
    """送出指令並等「結果檔真的更新」（統一走 device_probe）。"""
    # home=True：每條之前先回桌面。
    # 為什麼一定要：選圖器（或任何全螢幕第三方 Activity）開著時，
    # `am start` 不一定能把 WAE 帶回前景 → 探針不會跑 → 每條都等滿 wait（實測卡 36 分鐘）。
    return Device(SERIAL).run(command, wait_s=wait_s, home=True)


def verdict_of(detail: str) -> str:
    if "SUCCESS" in detail:
        return "SUCCESS"
    if "ABSTAIN" in detail:
        return "ABSTAIN"
    return "UNKNOWN"


def file_lines(path: str) -> str:
    out = adb("shell", "run-as", PKG, "wc", "-l", path)
    return out.strip().splitlines()[0] if out.strip() else "?"


def wait_for_service(timeout_s: int = 30) -> bool:
    """等無障礙服務真的綁上再開始跑。

    實測：剛裝完/剛 rebind 時，前 1–2 條指令會拿到「沒有 root」的畫面 → ABSTAIN，
    看起來像功能壞了，其實是服務還沒連上。
    """
    import time as _time

    deadline = _time.time() + timeout_s
    while _time.time() < deadline:
        out = adb("shell", "dumpsys", "accessibility")
        if PKG in out and "Enabled services" in out:
            return True
        _time.sleep(1)
    return False


def _safe_console() -> None:
    """Windows 主控台是 cp950：簡體字（例「打开」）會讓 print 直接爆掉。

    實測踩過：extra 套件加了簡體案例後，整支工具在印結果時
    UnicodeEncodeError → 評測只看到「無結果（腳本輸出異常）」。
    """
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", default=str(DEFAULT_APK))
    parser.add_argument("--wait", type=int, default=14)
    parser.add_argument("--out", default="", help="把結果寫成 UTF-8 TSV")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("-s", "--serial", default="")
    parser.add_argument("--cases", default=str(CASES), help="TSV with command<TAB>expect rows")
    _safe_console()
    args = parser.parse_args()
    global SERIAL
    SERIAL = args.serial

    if not ADB.exists():
        print(f"adb not found: {ADB}")
        return 2

    cases_path = Path(args.cases)
    cases = load_cases(cases_path)
    print(f"cases={len(cases)} file={cases_path}")

    if not args.skip_install:
        print(Device(SERIAL).install(args.apk))
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services",
            f"{PKG}/{PKG}.core.WaeVisionAccessibilityService")
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        time.sleep(8)

    adb("shell", "mkdir", "-p", DEVICE_DIR)

    results = []
    if not wait_for_service():
        print("警告：無障礙服務在 30 秒內沒有連上，結果可能全部 ABSTAIN")

    # 暖機：剛安裝完第一次啟動最慢（dexopt＋服務重綁），先跑一條不影響狀態的指令，
    # 否則第一條正式案例會被誤判成 UNKNOWN（實測踩過）。
    Device(SERIAL).run("!refs", wait_s=60)

    for command, expect in cases:
        detail = run_command(command, args.wait)
        actual = verdict_of(detail)
        results.append((command, expect, actual, actual == expect, detail))

    print("\n=== Gen-5 smoke ===")
    width = max(len(c) for c, *_ in results)
    for command, expect, actual, ok, _ in results:
        print(f"{command:<{width}}  expect={expect:<8} actual={actual:<8} {'PASS' if ok else 'FAIL'}")
    print(f"receipts:    {file_lines('files/mem/receipts.tsv')}")
    print(f"transitions: {file_lines('files/mem/transitions.tsv')}")
    print(f"refutations: {file_lines('files/mem/refutations.tsv')}")

    failed = [r for r in results if not r[3]]
    if args.out:
        with io.open(args.out, "w", encoding="utf-8", newline="") as f:
            f.write("# command\texpect\tactual\tpass\n")
            for command, expect, actual, ok, _detail in results:
                f.write("%s\t%s\t%s\t%s\n" % (command, expect, actual, "PASS" if ok else "FAIL"))

    if failed:
        print(f"RESULT: FAIL ({len(results) - len(failed)}/{len(results)})")
        for command, _, actual, _, detail in failed:
            print(f"  x {command} -> {actual}  ({detail})")
        return 1
    print(f"RESULT: PASS ({len(results)}/{len(results)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
