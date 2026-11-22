# -*- coding: utf-8 -*-
"""M3：從 emulator 收集 UI 畫面並產生 reranker 訓練資料（(目標, 元素) 對）。

用法：
  1. 先開 emulator，並把要收集的 App 開起來（或讓腳本用 am start 開）
  2. python collect_ui_samples.py --serial emulator-5556 --out E:\WAE\datasets\reranker_ui.jsonl
"""
import argparse, json, os, re, subprocess, sys, xml.etree.ElementTree as ET

ADB = r"E:\WAE\android-sdk\platform-tools\adb.exe"

def adb(serial, *args, timeout=60):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="ignore", timeout=timeout)

def dump_ui(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui_dump.xml")
    p = adb(serial, "shell", "cat", "/sdcard/ui_dump.xml")
    return p.stdout

def parse_elements(xml_text):
    if not xml_text or "<hierarchy" not in xml_text:
        return []
    try:
        root = ET.fromstring(xml_text)
    except Exception:
        return []
    out = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        label = text or desc
        if not label:
            continue
        out.append({
            "text": label,
            "cls": (node.get("class") or "").split(".")[-1],
            "clickable": node.get("clickable") == "true",
            "editable": node.get("class", "").endswith("EditText"),
            "bounds": node.get("bounds") or "",
            "pkg": node.get("package") or "",
        })
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--out", default=r"E:\WAE\datasets\reranker_ui.jsonl")
    ap.add_argument("--screens", default="settings,settings_battery,photos,chrome")
    args = ap.parse_args()

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    screens = {
        "settings": ["shell", "am", "start", "-a", "android.settings.SETTINGS"],
        "settings_battery": ["shell", "am", "start", "-a", "android.settings.BATTERY_SAVER_SETTINGS"],
        "photos": ["shell", "monkey", "-p", "com.google.android.apps.photos", "-c",
                   "android.intent.category.LAUNCHER", "1"],
        "chrome": ["shell", "monkey", "-p", "com.android.chrome", "-c",
                   "android.intent.category.LAUNCHER", "1"],
    }
    want = [s.strip() for s in args.screens.split(",") if s.strip()]
    rows = []
    for name in want:
        cmd = screens.get(name)
        if not cmd:
            continue
        adb(args.serial, *cmd)
        import time
        time.sleep(3.0)
        els = parse_elements(dump_ui(args.serial))
        if not els:
            print(f"[{name}] no elements")
            continue
        print(f"[{name}] elements={len(els)}")
        # 產生 pair：每個元素當正樣本，同畫面其他元素當 hard negatives
        for pos in els:
            target = pos["text"]
            rows.append({"screen": name, "target": target, "candidate": pos["text"],
                         "label": 1, "clickable": pos["clickable"], "editable": pos["editable"], "cls": pos["cls"]})
            for neg in els:
                if neg is pos or neg["text"] == pos["text"]:
                    continue
                rows.append({"screen": name, "target": target, "candidate": neg["text"],
                             "label": 0, "clickable": neg["clickable"], "editable": neg["editable"], "cls": neg["cls"]})
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("wrote", len(rows), "pairs ->", args.out)

if __name__ == "__main__":
    main()
