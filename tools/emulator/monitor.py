"""QEMU monitor 自動操作：不需要管理員、不需要 GUI 點擊。

用途：
  - 讀 QEMU 畫面（screendump → PPM → PNG）
  - 送按鍵（sendkey）
  - 用「看畫面 → 決定下一步」的方式自動走完 Android-x86 安裝與後續 UI

用法：
  python tools/emulator/monitor.py shot out.png
  python tools/emulator/monitor.py key down
  python tools/emulator/monitor.py keys down down ret
"""

from __future__ import annotations

import socket
import sys
import time

HOST, PORT = "127.0.0.1", 4444


def connect(timeout: float = 5.0) -> socket.socket:
    return socket.create_connection((HOST, PORT), timeout=timeout)


def cmd(s: socket.socket, c: str, wait: float = 0.6) -> str:
    s.sendall((c + "\n").encode())
    time.sleep(wait)
    buf = b""
    try:
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
            if b"(qemu)" in buf[-400:]:
                break
    except socket.timeout:
        pass
    return buf.decode("utf-8", "replace")


def shot(s: socket.socket, path: str) -> None:
    cmd(s, f"screendump {path}", 1.2)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    s = connect()
    try:
        op = sys.argv[1]
        if op == "shot":
            shot(s, sys.argv[2])
            print("ok")
        elif op == "key":
            cmd(s, "sendkey " + sys.argv[2])
            print("ok")
        elif op == "keys":
            for k in sys.argv[2:]:
                cmd(s, "sendkey " + k, 0.35)
            print("ok")
        elif op == "type":
            for ch in sys.argv[2]:
                cmd(s, "sendkey " + ch, 0.15)
            print("ok")
        elif op == "status":
            print(cmd(s, "info status"))
        else:
            print("unknown op")
            return 2
    finally:
        s.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
