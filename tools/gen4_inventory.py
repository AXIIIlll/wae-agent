#!/usr/bin/env python3
"""Gen-4 庫存盤點（D2／D3 的基線數字）。

只讀原始碼、不改任何東西。輸出四組數字：
  1. 直接寫 filesDir 的檔案（＝各自的儲存庫）
  2. `object` 單例數量（全域可變狀態）
  3. 服務／總管的行數（上帝物件）
  4. 仍有多個地方在操作裝置的位置（I1 違規候選）

用法：python tools/gen4_inventory.py
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

OBJECT_RE = re.compile(r"^\s*object\s+\w+")
FILES_DIR_RE = re.compile(r"filesDir")
DEVICE_RE = re.compile(r"dispatchGesture|performAction\(")
JSON_RE = re.compile(r"[\"']([\w\-]+\.json[ln]?)[\"']")

BIG_FILES = [
    "app/src/main/java/com/wae/agent/core/WaeVisionAccessibilityService.kt",
    "app/src/main/java/com/wae/agent/core/WaeV26PipelineOrchestrator.kt",
    "app/src/main/java/com/wae/agent/MainActivity.kt",
]


def main() -> None:
    kt = list(SRC.rglob("*.kt"))
    objects: list[str] = []
    files_dir_hits = 0
    device_hits: dict[str, int] = {}
    json_names: set[str] = set()

    for path in kt:
        text = path.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            if OBJECT_RE.match(line):
                objects.append(line.strip())
        files_dir_hits += len(FILES_DIR_RE.findall(text))
        hits = len(DEVICE_RE.findall(text))
        if hits:
            device_hits[str(path.relative_to(ROOT))] = hits
        json_names.update(JSON_RE.findall(text))

    print(f"kt files:            {len(kt)}")
    print(f"object singletons:   {len(objects)}")
    print(f"filesDir refs:       {files_dir_hits}")
    print(f"distinct json files: {len(json_names)}")
    for name in sorted(json_names):
        print(f"  - {name}")

    print("\ndevice-touch sites (I1 candidates):")
    for path, hits in sorted(device_hits.items(), key=lambda kv: -kv[1]):
        print(f"  {hits:>3}x  {path}")

    ok = check_allowlist(device_hits)

    print("\nbig files:")
    for rel in BIG_FILES:
        p = ROOT / rel
        if p.exists():
            print(f"  {len(p.read_text(encoding='utf-8', errors='replace').splitlines()):>5}  {rel}")

    print("")
    print("I1 GUARD: PASS" if ok else "I1 GUARD: FAIL (new device-touch site or count grew)")
    raise SystemExit(0 if ok else 1)


ALLOWLIST = ROOT / "tools/i1_allowlist.txt"


def check_allowlist(actual: dict[str, int]) -> bool:
    """I1 守門：現有違規點可以留，但不能**變多**。

    規則寫在這裡而不是 CI 裡，因為現階段的主線是「先把新路徑做對」，
    舊路徑要等 D1 才刪；在那之前至少要阻止它繼續長大。
    """
    if not ALLOWLIST.exists():
        ALLOWLIST.write_text(
            "# path\tmax_sites（只能減少，不能增加）\n"
            + "".join(f"{p}\t{n}\n" for p, n in sorted(actual.items())),
            encoding="utf-8",
        )
        print(f"allowlist created: {ALLOWLIST}")
        return True

    allowed: dict[str, int] = {}
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        allowed[parts[0]] = int(parts[1])

    ok = True
    for path, hits in actual.items():
        limit = allowed.get(path)
        if limit is None:
            print(f"  NEW violation: {path} ({hits})")
            ok = False
        elif hits > limit:
            print(f"  GREW: {path} {limit} -> {hits}")
            ok = False
    return ok


if __name__ == "__main__":
    main()
