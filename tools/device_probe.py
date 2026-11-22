"""裝置探針：一支工具，處理「送出指令 → 等新結果 → 讀回來」。

為什麼要集中：同一個 mtime 輪詢邏輯，我們在四個地方各寫了一次
（`gen5_smoke`、`dialogue_script`、`device_checklist`、`daily`），
然後就發生「其中一支還是用固定等待 → 讀到上一條結果」的事（Round 91）。

**紀律：所有「送指令並讀結果」的程式碼都應該走這裡**，不要再各自 sleep。

用法：
    from device_probe import Device
    dev = Device("emulator-5554")
    out = dev.run("打開設定")
"""

from __future__ import annotations

import subprocess
import time
from pathlib import Path

ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
PKG = "com.wae.agent"
REMOTE = f"/sdcard/Android/data/{PKG}/files"
RESULT = f"{REMOTE}/gen5_result.txt"
COMMAND = f"{REMOTE}/gen5_command.txt"
TMP = Path.home() / ".wae_probe_cmd.txt"


class Device:
    def __init__(self, serial: str = "", adb_path: Path | None = None):
        self.serial = serial
        self.adb_path = adb_path or ADB

    def adb(self, *args: str) -> str:
        cmd = [str(self.adb_path)] + (["-s", self.serial] if self.serial else []) + list(args)
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        return (out.stdout or "") + (out.stderr or "")

    def install(self, apk: str | Path) -> str:
        """安裝（-r）並重新綁定無障礙。安裝會讓服務解除綁定，所以兩件事綁在一起。"""
        out = self.adb("install", "-r", str(apk)).strip().splitlines()
        self.rebind()
        return out[-1] if out else ""

    def rebind(self, service: str = f"{PKG}/com.wae.agent.core.WaeVisionAccessibilityService") -> None:
        self.adb("shell", "settings", "put", "secure", "enabled_accessibility_services", service)
        self.adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")

    def grant(self, permission: str) -> None:
        self.adb("shell", "pm", "grant", PKG, permission)

    def mtime(self) -> str:
        return self.adb("shell", "stat", "-c", "%Y", RESULT).strip() or "0"

    def push_command(self, command: str) -> None:
        TMP.parent.mkdir(parents=True, exist_ok=True)
        TMP.write_text(command, encoding="utf-8")
        self.adb("push", str(TMP), COMMAND)

    def clear_memory(self, *names: str) -> None:
        targets = names or ("files/mem/refutations.tsv", "files/followup_pending.tsv", "files/last_command.txt")
        self.adb("shell", "run-as", PKG, "rm", "-f", *targets)

    def wait_ready(self, timeout_s: int = 30) -> bool:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            out = self.adb("shell", "dumpsys", "accessibility")
            if PKG in out and "Enabled services" in out:
                return True
            time.sleep(1)
        return False

    def run(self, command: str, wait_s: int = 45, home: bool = True) -> str:
        """送出指令並等結果檔真的更新；回傳最後一行結果。"""
        if home:
            self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(2)
        before = self.mtime()
        self.push_command(command)
        self.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        deadline = time.monotonic() + wait_s
        while time.monotonic() < deadline:
            time.sleep(1.2)
            if self.mtime() != before:
                time.sleep(0.5)
                break
        out = self.adb("shell", "cat", RESULT)
        lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
        return lines[-1] if lines else ""

    def run_full(self, command: str, wait_s: int = 45, home: bool = True) -> str:
        """跟 run 一樣送指令，但回傳完整結果（多行 probe 用）。"""
        if home:
            self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(2)
        before = self.mtime()
        self.push_command(command)
        self.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        deadline = time.monotonic() + wait_s
        while time.monotonic() < deadline:
            time.sleep(1.2)
            if self.mtime() != before:
                time.sleep(0.5)
                break
        return self.adb("shell", "cat", RESULT)


if __name__ == "__main__":
    import sys

    dev = Device(sys.argv[1] if len(sys.argv) > 1 else "emulator-5554")
    print(dev.run(sys.argv[2] if len(sys.argv) > 2 else "打開設定"))
