#!/usr/bin/env bash
# WAE 雲端 Android 模擬器（GCP Ubuntu + KVM）
# 用法：bash setup_android_emu.sh
set -u
mkdir -p ~/android && cd ~/android

echo "===== 1. 安裝 QEMU / 工具 / adb ====="
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qemu-system-x86 qemu-utils curl unzip python3
if [ ! -x ~/platform-tools/adb ]; then
  curl -sL -o /tmp/pt.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip
  unzip -q -o /tmp/pt.zip -d ~/
fi
ADB=~/platform-tools/adb
export PATH=$HOME/platform-tools:$PATH

echo "===== 2. KVM 檢查 ====="
ls -l /dev/kvm 2>/dev/null || echo "警告：沒有 /dev/kvm（VM 可能沒開巢狀虛擬化）"
sudo chmod 666 /dev/kvm 2>/dev/null || true

echo "===== 3. 下載 Android-x86 ISO ====="
ISO=android-x86_64-9.0-r2.iso
if [ ! -f "$ISO" ]; then
  curl -sL --retry 3 -C - -o "$ISO" \
    "https://master.dl.sourceforge.net/project/android-x86/Release%209.0/android-x86_64-9.0-r2.iso"
fi
ls -lh "$ISO"

echo "===== 4. 建立磁碟 ====="
[ -f android.qcow2 ] || qemu-img create -f qcow2 android.qcow2 8G

echo "===== 5. 開機（KVM 加速）====="
pkill -f qemu-system-x86_64 2>/dev/null || true
sleep 1
nohup qemu-system-x86_64 \
  -enable-kvm -cpu host -smp 4 -m 4096 \
  -cdrom "$ISO" -boot d \
  -drive file=android.qcow2,if=virtio \
  -netdev user,id=n0,hostfwd=tcp::5555-:5555 -device virtio-net-pci,netdev=n0 \
  -vga std -display none \
  -monitor tcp:127.0.0.1:4444,server,nowait \
  -no-reboot > qemu.log 2>&1 &
echo "QEMU PID=$!"

inject_adb_setup() {
  python3 - <<'PY'
import socket, time
try:
    s = socket.create_connection(("127.0.0.1", 4444), timeout=5)
except Exception:
    raise SystemExit(0)
def send(cmd, wait=0.15):
    s.sendall((cmd + "\n").encode()); time.sleep(wait)
    try: s.recv(65536)
    except Exception: pass
KEYMAP = {" ": "spc", ".": "dot", "-": "minus", "/": "slash"}
for line in ["setprop service.adb.tcp.port 5555", "stop adbd", "start adbd",
             "setprop persist.adb.tcp.port 5555"]:
    for ch in line:
        send("sendkey " + KEYMAP.get(ch, ch))
    send("sendkey ret", 0.3)
s.close()
PY
}

echo "===== 6. 等 Android 起來並開啟 adb（最多約 8 分鐘）====="
for i in $(seq 1 16); do
  sleep 30
  inject_adb_setup
  sleep 3
  $ADB connect 127.0.0.1:5555 >/dev/null 2>&1 || true
  STATE=$($ADB devices 2>/dev/null | grep 5555 | awk '{print $2}')
  echo "[$i] adb=${STATE:-none}"
  if [ "$STATE" = "device" ]; then
    echo "===== ADB 已連上 ====="
    break
  fi
done

echo "===== 7. 結果 ====="
$ADB devices || true
$ADB shell getprop ro.build.version.release 2>/dev/null || echo "(adb 還沒連上)"
echo ""
echo "下一步：bash install_apps_cloud.sh（安裝 F-Droid 與開源 App）"
