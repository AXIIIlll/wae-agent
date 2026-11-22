#!/usr/bin/env bash
# 在雲端 VM 上：安裝 F-Droid 並列出可用 App
set -u
export PATH=$HOME/platform-tools:$PATH
ADB=~/platform-tools/adb
$ADB connect 127.0.0.1:5555 >/dev/null 2>&1 || true
sleep 2
$ADB devices
cd ~/android
if [ ! -f fdroid.apk ]; then
  curl -sL -o fdroid.apk https://f-droid.org/F-Droid.apk
fi
$ADB install -r fdroid.apk || true
echo "===== 已安裝的 App ====="
$ADB shell pm list packages -3 || true
