"""雲端 VM：透過 QEMU monitor 完成「exit 繼續開機 → 開 adb」流程。

用法：python3 inject_console.py
"""
import socket
import time

MONITOR = ("127.0.0.1", 4444)


def connect():
    s = socket.create_connection(MONITOR, timeout=5)
    s.settimeout(2)
    try:
        s.recv(4096)
    except Exception:
        pass
    return s


def send(s, cmd, wait=0.15):
    s.sendall((cmd + "\n").encode())
    time.sleep(wait)
    try:
        s.recv(65536)
    except Exception:
        pass


KEYMAP = {" ": "spc", ".": "dot", "-": "minus", "/": "slash"}


def type_line(s, line):
    for ch in line:
        send(s, "sendkey " + KEYMAP.get(ch, ch))
    send(s, "sendkey ret", 0.4)


def shot(s, path):
    send(s, f"screendump {path}", 1.5)


def main():
    s = connect()

    print("== 1. 切到主控台 + 截圖 ==")
    send(s, "sendkey alt-f1", 1.0)
    shot(s, "/tmp/s1.ppm")

    print("== 2. 送 exit（若是 initrd 除錯 shell，會繼續開機）==")
    type_line(s, "exit")
    time.sleep(2)

    print("== 3. 等 Android 開機（90 秒）==")
    time.sleep(90)
    send(s, "sendkey alt-f1", 1.0)
    shot(s, "/tmp/s2.ppm")

    print("== 4. 開 adb over network ==")
    for line in ["setprop service.adb.tcp.port 5555", "stop adbd", "start adbd",
                 "setprop persist.adb.tcp.port 5555"]:
        type_line(s, line)
    time.sleep(1)
    shot(s, "/tmp/s3.ppm")

    print("== 5. 回圖形介面 ==")
    send(s, "sendkey alt-f7", 1.0)
    s.close()
    print("完成：/tmp/s1.ppm /tmp/s2.ppm /tmp/s3.ppm")


if __name__ == "__main__":
    main()
