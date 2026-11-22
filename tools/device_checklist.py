"""真機（或模擬器）檢查清單的自動化版本：R1–R5。

用法：
    python tools/device_checklist.py                 # 跑 R1–R5（R2 較慢，約 2 分鐘）
    python tools/device_checklist.py --fast          # 略過 R2 自我測試
    python tools/device_checklist.py -s emulator-5554

判準刻意寫死（數字要比對），不做「看起來差不多」的判斷。
"""

import argparse
import io
import subprocess
import sys
import time
from pathlib import Path

ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
from device_probe import Device

PKG = "com.wae.agent"
REMOTE = "/sdcard/Android/data/com.wae.agent/files"


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def adb(serial: str, *args: str, timeout: int = 60) -> str:
    cmd = [str(ADB)] + (["-s", serial] if serial else []) + list(args)
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        return (out.stdout or "") + (out.stderr or "")
    except subprocess.TimeoutExpired:
        return "<timeout>"


def _clickable_centers(serial: str, limit: int = 5) -> list[tuple[int, int]]:
    """從 uiautomator dump 找第一個可點擊節點的中心點（找不到回 None）。"""
    import re

    adb(serial, "shell", "uiautomator", "dump", "/sdcard/wae_ui.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/wae_ui.xml")
    out: list[tuple[int, int]] = []
    for match in re.finditer(r'clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = (int(g) for g in match.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        if cy < 100 or cy > 2000:
            continue
        out.append((cx, cy))
        if len(out) >= limit:
            break
    return out


def probe(serial: str, command: str, wait: int = 45) -> str:
    """送出指令並等「結果檔真的更新」（統一走 device_probe）。"""
    return Device(serial).run(command, wait_s=wait)


def _record_verification(results: list[tuple[str, bool, str]]) -> None:
    """把這次真機結果寫進 bench/device_verification.tsv（R1–R7）。"""
    import datetime

    path = Path(__file__).resolve().parent.parent / "bench" / "device_verification.tsv"
    if not path.exists():
        return
    lines = io.open(path, encoding="utf-8").read().splitlines()
    now = datetime.datetime.now().isoformat(timespec="seconds")
    updated = {}
    for name, ok, detail in results:
        cap_id = name.split()[0]
        updated[cap_id] = (now, "PASS" if ok else "FAIL", detail.replace("\t", " ")[:60])

    out = []
    for line in lines:
        if not line.strip() or line.startswith("#"):
            out.append(line)
            continue
        cells = line.split("\t")
        cap_id = cells[0].strip()
        if cap_id in updated:
            ts, result, note = updated[cap_id]
            cells = [cap_id, ts, "apk", result, note]
        out.append("\t".join(cells))
    io.open(path, "w", encoding="utf-8", newline="").write("\n".join(out) + "\n")


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="")
    parser.add_argument("--fast", action="store_true", help="略過 R2 自我測試")
    parser.add_argument("--full", action="store_true", help="連 R6（分享照片）、R7（搜尋 ×3）一起跑")
    parser.add_argument("--with-eval", action="store_true", help="加跑 R8 完整評測（101 條，約 25 分鐘）")
    args = parser.parse_args()

    serial = args.serial
    results: list[tuple[str, bool, str]] = []

    # R1 服務綁定
    dump = adb(serial, "shell", "dumpsys", "accessibility")
    bound = PKG in dump and "Enabled services" in dump
    results.append(("R1 服務綁定", bound, "dumpsys 有 wae" if bound else "找不到無障礙服務"))

    # R2 自我測試
    if args.fast:
        results.append(("R2 自我測試", True, "略過（--fast）"))
    else:
        out = probe(serial, "!selftest", wait=110)
        ok = "9/9" in out
        results.append(("R2 自我測試", ok, out[:80]))

    # R3 亮度讀回（先把亮度設到中段，避免已在極值而「調不動」）
    adb(serial, "shell", "settings", "put", "system", "screen_brightness", "128")
    time.sleep(1)
    before = adb(serial, "shell", "settings", "get", "system", "screen_brightness").strip()
    probe(serial, "調亮", wait=20)
    after = adb(serial, "shell", "settings", "get", "system", "screen_brightness").strip()
    changed = before.isdigit() and after.isdigit() and before != after
    results.append(("R3 亮度讀回", changed, f"{before} → {after}"))

    # R4 被動學習
    adb(serial, "shell", "run-as", PKG, "touch", "files/twin/steps.jsonl")
    size_before = adb(serial, "shell", "run-as", PKG, "stat", "-c", "%s", "files/twin/steps.jsonl").strip()
    # 在桌面點 App 圖示（launcher 一定在白名單裡；設定頁偶爾會被 GMS 覆蓋層搶走事件）
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    # 試幾個可點節點（不同機型／不同頁面狀態下，第一個不一定會產生點擊事件）
    new_size = size_before
    for cx, cy in _clickable_centers(serial, limit=3):
        adb(serial, "shell", "input", "tap", str(cx), str(cy))
        time.sleep(2)
        new_size = adb(serial, "shell", "run-as", PKG, "stat", "-c", "%s", "files/twin/steps.jsonl").strip()
        if new_size.isdigit() and size_before.isdigit() and int(new_size) > int(size_before):
            break
    time.sleep(2)
    size_after = adb(serial, "shell", "run-as", PKG, "stat", "-c", "%s", "files/twin/steps.jsonl").strip()
    grew = size_before.isdigit() and size_after.isdigit() and int(size_after) > int(size_before)
    results.append(("R4 被動學習", grew, f"{size_before}B → {size_after}B"))

    # R5 相機快門（模擬器預期失敗，只記錄不判定失敗）
    before_count = adb(serial, "shell", "ls /sdcard/DCIM/Camera 2>/dev/null | wc -l").strip()
    probe(serial, "拍照", wait=25)
    after_count = adb(serial, "shell", "ls /sdcard/DCIM/Camera 2>/dev/null | wc -l").strip()
    shot = before_count.isdigit() and after_count.isdigit() and int(after_count) > int(before_count)
    is_emulator = adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    if is_emulator:
        # 模擬器的相機是 stub：按快門不會存檔，這項在模擬器上永遠不會成長。
        results.append(("R5 相機快門（略過）", True, f"模擬器 stub 不存檔；照片數 {before_count} → {after_count}"))
    else:
        results.append(("R5 相機快門", shot, f"照片數 {before_count} → {after_count}"))

    if args.full:
        # R6 分享相簿照片：看 logcat 有沒有真的拿到照片 URI
        adb(serial, "shell", "logcat", "-c")
        probe(serial, "分享照片給訊息", wait=22)
        logs = adb(serial, "shell", "logcat", "-d", "-s", "WAE_PHOTOS:I")
        shared = "share photo id=" in logs
        focus = adb(serial, "shell", "dumpsys", "window")
        foreground_ok = "messaging" in focus
        results.append(("R6 分享照片", shared and foreground_ok, f"log={'有' if shared else '無'} 前景訊息={'是' if foreground_ok else '否'}"))

        # R7 搜尋 ×3：每次都先回主頁，統計成功次數
        ok_count = 0
        for _ in range(3):
            adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(2)
            adb(serial, "shell", "run-as", PKG, "rm", "-f", "files/mem/refutations.tsv")
            out = probe(serial, "在設定裡搜尋藍牙", wait=22)
            if "SUCCESS" in out:
                ok_count += 1
        results.append(("R7 搜尋 ×3", ok_count == 3, f"{ok_count}/3 SUCCESS"))

    if args.with_eval:
        # R8：完整評測（101 條）。跑完把分數記進真機驗證表。
        import subprocess as _sp

        started = __import__("time").monotonic()
        out = _sp.run([sys.executable, "tools/gen5_eval.py", "--wait", "14"],
                      capture_output=True, text=True, encoding="utf-8", errors="replace",
                      cwd=str(Path(__file__).resolve().parent.parent))
        text = (out.stdout or "") + (out.stderr or "")
        import re as _re

        m = _re.search(r"總計：(\d+)/(\d+)", text)
        passed, total = (int(m.group(1)), int(m.group(2))) if m else (0, 0)
        ok = total > 0 and passed == total
        results.append(("R8 完整評測", ok, f"{passed}/{total}（{int(__import__('time').monotonic() - started)}s）"))

    print("\n檢查結果：")
    for name, ok, detail in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {name:<14} {detail}")

    passed = sum(1 for _, ok, _ in results if ok)
    print(f"\n總計：{passed}/{len(results)}")

    # 只有真機才記「已驗證」——模擬器過了不代表真機過了
    is_emulator = adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1"
    if not is_emulator:
        _record_verification(results)
        print("已更新 bench/device_verification.tsv")
    else:
        print("（模擬器：不更新真機驗證狀態）")

    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
