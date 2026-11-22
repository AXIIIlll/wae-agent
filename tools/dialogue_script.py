"""對話行為劇本：把「反問 → 記住答案 → 取消 → 再做一次 → 糾正」串起來跑一次。

為什麼要有這個：這五件事各自都有測試，但**它們會互相影響**——
反問留下的缺口會不會吃掉下一個指令？糾正留下的禁令會不會擋住別的事？
只有照順序跑一次才看得出來。

用法：
    python tools/dialogue_script.py                 # emulator-5554
    python tools/dialogue_script.py -s R5CX71DTN7L  # 真機
"""

import argparse
import io
import subprocess
import sys
import time
from pathlib import Path

from device_probe import Device

ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
ROOT = Path(__file__).resolve().parent.parent
PKG = "com.wae.agent"
FILES = f"/sdcard/Android/data/{PKG}/files"


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def adb(serial: str, *args: str) -> str:
    cmd = [str(ADB)] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return (out.stdout or "") + (out.stderr or "")


def run(serial: str, command: str, wait: int = 40) -> str:
    """送出並等「結果檔真的更新」（統一走 device_probe，避免各寫一份）。"""
    return Device(serial).run(command, wait_s=wait)


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="emulator-5554")
    parser.add_argument("--wait", type=int, default=40)
    args = parser.parse_args()

    adb(args.serial, "shell", "run-as", PKG, "rm", "-f",
        "files/mem/refutations.tsv", "files/followup_pending.tsv", "files/last_command.txt")

    steps = [
        ("打開設定", "SUCCESS", "基準動作"),
        ("再做一次", "SUCCESS", "重跑上一次"),
        ("不是這個", "correction=applied", "糾正生效"),
        ("打開設定", "ABSTAIN", "被自己的糾正擋住"),
        ("__clear_refutations__", "", "清掉禁令"),
        ("打開設定", "SUCCESS", "恢復"),
        ("傳訊息", "missing_slot", "缺對象 → 反問"),
        ("給小明", "sensitive_operation", "「給小明」正規化後接回原句 → 安全閘門"),
        ("取消", "cancelled", "取消"),
        ("打開設定", "SUCCESS", "取消後仍可正常操作"),
        ("複製結果", "copy=ok", "把上一次結果放進剪貼簿"),
        ("選一張照片", "SUCCESS", "打開系統選圖器（選哪張留給人）"),
    ]

    results = []
    for command, expect, note in steps:
        if command == "__clear_refutations__":
            adb(args.serial, "shell", "run-as", PKG, "rm", "-f", "files/mem/refutations.tsv")
            results.append((command, True, note))
            continue
        out = run(args.serial, command, args.wait)
        ok = expect in out
        results.append((command, ok, f"{note}｜{out[:70]}"))

    print("對話劇本：")
    for command, ok, detail in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {command:<10} {detail}")

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n總計：{passed}/{len(results)}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
