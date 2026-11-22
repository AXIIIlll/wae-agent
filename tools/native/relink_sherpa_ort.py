"""把 sherpa 的 DT_NEEDED 從 `libsherpa_ort1.so` 改成 app 自帶的 `libonnxruntime.so`。

為什麼：專案裡有兩份 ONNX Runtime（app 的 1.22 與 sherpa 自帶改名後的 1.17.1），
合計 32.6MB。ORT 的 C API 是版本化且向後相容的（GetApi(17) 在 1.22 runtime 仍然可用），
所以 sherpa 有機會直接吃 app 的 ORT，省下 15.2MB。

作法：`.dynstr` 裡的 DT_NEEDED 是固定偏移的字串，只要**總長度不變**就能原地換。
  "libsherpa_ort1.so\\0"  = 18 + 1 = 19 bytes
  "libonnxruntime.so\\0\\0" = 17 + 1 + 1 = 19 bytes  ← 補一個 NUL，偏移表不動

用法：
  python tools/native/relink_sherpa_ort.py            # 套用
  python tools/native/relink_sherpa_ort.py --revert   # 還原（從 tools/native_backup 複製）
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JNI = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
BACKUP = ROOT / "tools" / "native_backup"

OLD = b"libsherpa_ort1.so\x00"   # 18 bytes（14 字名 + .so + NUL）
NEW = b"libonnxruntime.so\x00"   # 18 bytes（同名長度，偏移不動）
TARGETS = ["libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so"]


def apply() -> int:
    assert len(OLD) == len(NEW), "長度必須相同，否則 .dynstr 偏移會壞掉"
    changed = 0
    for name in TARGETS:
        p = JNI / name
        data = p.read_bytes()
        n = data.count(OLD)
        if n == 0:
            print(f"{name}: 已經沒有 {OLD!r}（可能已套用過）")
            continue
        p.write_bytes(data.replace(OLD, NEW))
        print(f"{name}: 替換 {n} 處 -> {NEW!r}")
        changed += 1
    ort = JNI / "libsherpa_ort1.so"
    if ort.exists():
        ort.unlink()
        print("已移除 libsherpa_ort1.so（15.2MB）")
    return 0 if changed or not ort.exists() else 1


def revert() -> int:
    """還原成「sherpa 自帶 15.2MB ORT」的原始狀態（並移除 shim）。"""
    if not BACKUP.is_dir():
        print("找不到備份目錄 tools/native_backup", file=sys.stderr)
        return 1
    for name in TARGETS + ["libsherpa_ort1.so"]:
        src = BACKUP / name
        if src.is_file():
            shutil.copy2(src, JNI / name)
            print(f"還原 {name}")
    return 0


def use_shim() -> int:
    """套用 shim：保留 sherpa 原始 DT_NEEDED，但把 libsherpa_ort1.so 換成幾 KB 的轉發墊片。

    注意：shim 已經編譯好在 app/src/main/jniLibs/arm64-v8a/libsherpa_ort1.so；
    這個函式只負責確認它存在、且 sherpa 的 DT_NEEDED 指向它。
    """
    shim = JNI / "libsherpa_ort1.so"
    if not shim.is_file() or shim.stat().st_size > 64 * 1024:
        print("libsherpa_ort1.so 不是 shim（大小異常），請先編譯 shim", file=sys.stderr)
        return 1
    for name in TARGETS:
        p = JNI / name
        data = p.read_bytes()
        if OLD in data:
            continue  # 原始 DT_NEEDED 就是它，不用改
        if NEW in data:
            p.write_bytes(data.replace(NEW, OLD))
            print(f"{name}: 還原 DT_NEEDED -> libsherpa_ort1.so")
    print("shim 就緒：libsherpa_ort1.so =", shim.stat().st_size, "bytes")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--revert", action="store_true")
    ap.add_argument("--shim", action="store_true", help="改用 shim（保留 DT_NEEDED）")
    a = ap.parse_args()
    raise SystemExit(revert() if a.revert else (use_shim() if a.shim else apply()))
