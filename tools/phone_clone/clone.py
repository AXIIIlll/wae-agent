"""用 ADB 把真機的「真實介面」複製到電腦（讀取用，不改手機設定、不點敏感元素）。

抓什麼（每個 App）：
  - 實際截圖 PNG（pixel-perfect，之後可當像素層教材）
  - uiautomator dump XML（真實 view hierarchy：class/text/bounds/clickable/checkable…）
  - 解析後的 nodes.json（符號環境可直接吃：真實座標、真實元件、真實文字）
  - 往下捲動幾次再抓（只做「捲動」這個非破壞性動作）
  - manifest.json（裝置規格 + 每個 App 的畫面清單）

不做什麼：
  - 不點任何按鈕（只啟動 App、截圖、dump、捲動、回首頁）
  - 不改手機任何設定
  - 不動開發工具（config.json 的 exclude_packages）

用法：
  python tools/phone_clone/clone.py --list            # 列出已安裝 App
  python tools/phone_clone/clone.py --all             # 複製 config 裡的所有 include_packages
  python tools/phone_clone/clone.py --packages com.android.settings,com.google.android.gm
  python tools/phone_clone/clone.py --all --no-scroll # 只抓首屏（最快）
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
CONFIG = os.path.join(HERE, "config.json")
ADB_FALLBACK = "adb"


def load_config() -> dict:
    with open(CONFIG, encoding="utf-8") as f:
        return json.load(f)


def adb_path(cfg: dict) -> str:
    p = cfg.get("device", {}).get("adb", ADB_FALLBACK)
    return p if os.path.exists(p) else ADB_FALLBACK


def sh(adb: str, *args: str, timeout: int = 30) -> subprocess.CompletedProcess:
    return subprocess.run([adb, *args], capture_output=True, timeout=timeout)


def text_of(adb: str, *args: str) -> str:
    r = sh(adb, *args)
    return (r.stdout or b"").decode("utf-8", "replace").strip()


def devices(adb: str) -> list:
    out = text_of(adb, "devices")
    return [ln.split("\t")[0] for ln in out.splitlines()[1:] if "\tdevice" in ln]


def device_metrics(adb: str) -> dict:
    size = text_of(adb, "shell", "wm", "size")
    density = text_of(adb, "shell", "wm", "density")
    sdk = text_of(adb, "shell", "getprop", "ro.build.version.sdk")
    release = text_of(adb, "shell", "getprop", "ro.build.version.release")
    model = text_of(adb, "shell", "getprop", "ro.product.model")
    w, h = 0, 0
    if "Physical size:" in size:
        try:
            dims = size.split("Physical size:")[1].strip().split(" ")[0]
            w, h = [int(x) for x in dims.lower().split("x")]
        except Exception:
            pass
    d = 0
    if "Physical density:" in density:
        try:
            d = int(density.split("Physical density:")[1].strip().split()[0])
        except Exception:
            pass
    return {
        "size_px": [w, h],
        "density_dpi": d,
        "density_scale": round(d / 160.0, 3) if d else 0,
        "dp_size": [round(w / (d / 160.0)), round(h / (d / 160.0))] if d and w else [0, 0],
        "sdk": sdk, "release": release, "model": model,
        "aspect": round(h / w, 3) if w else 0,
    }


def list_packages(adb: str) -> list:
    out = text_of(adb, "shell", "pm", "list", "packages")
    return sorted(ln.replace("package:", "").strip() for ln in out.splitlines()
                  if ln.startswith("package:"))


def launch(adb: str, pkg: str) -> bool:
    r = sh(adb, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
    return r.returncode == 0


def capture_screen(adb: str, out_dir: str, index: int) -> tuple[bool, str]:
    png = os.path.join(out_dir, f"{index:02d}.png")
    with open(png, "wb") as f:
        r = subprocess.run([adb, "exec-out", "screencap", "-p"], stdout=f, timeout=30)
    if r.returncode != 0 or os.path.getsize(png) < 1000:
        return False, ""
    remote = "/sdcard/wae_clone_dump.xml"
    sh(adb, "shell", "uiautomator", "dump", remote)
    xml = os.path.join(out_dir, f"{index:02d}.xml")
    r2 = subprocess.run([adb, "pull", remote, xml], capture_output=True, timeout=30)
    if r2.returncode != 0 or not os.path.exists(xml):
        return True, ""
    return True, xml


def parse_xml(xml_path: str, screen_px: tuple[int, int]) -> dict:
    """把 uiautomator XML 轉成符號環境可用的節點清單（真實座標）。"""
    try:
        tree = ET.parse(xml_path)
    except Exception:
        return {"nodes": [], "error": "parse failed"}
    root = tree.getroot()
    nodes = []

    def walk(node, depth: int):
        for child in list(node):
            if child.tag != "node":
                continue
            a = child.attrib
            bounds = a.get("bounds", "")
            rect = None
            if bounds:
                try:
                    l, t = bounds.split("][")[0].replace("bounds=\"[", "").split(",")
                    r, b = bounds.split("][")[1].replace("]", "").split(",")
                    rect = [int(l), int(t), int(r), int(b)]
                except Exception:
                    rect = None
            text = (a.get("text") or "").strip()
            desc = (a.get("content-desc") or "").strip()
            if rect and (text or desc or a.get("clickable") == "true"
                         or a.get("checkable") == "true" or a.get("scrollable") == "true"):
                nodes.append({
                    "depth": depth,
                    "class": a.get("class", ""),
                    "text": text,
                    "desc": desc,
                    "resource_id": a.get("resource-id", ""),
                    "package": a.get("package", ""),
                    "clickable": a.get("clickable") == "true",
                    "checkable": a.get("checkable") == "true",
                    "checked": a.get("checked") == "true",
                    "scrollable": a.get("scrollable") == "true",
                    "enabled": a.get("enabled") == "true",
                    "bounds": rect,
                })
            walk(child, depth + 1)

    walk(root, 0)
    return {"screen_px": list(screen_px), "nodes": nodes}


def capture_app(adb: str, pkg: str, metrics: dict, scrolls: int) -> dict:
    out_dir = os.path.join(DATA, pkg)
    os.makedirs(out_dir, exist_ok=True)
    result = {"package": pkg, "captures": []}
    if not launch(adb, pkg):
        result["error"] = "launch failed"
        return result
    time.sleep(load_config()["device"]["wait_after_launch_ms"] / 1000.0)
    idx = 0
    ok, xml = capture_screen(adb, out_dir, idx)
    if ok:
        nodes = parse_xml(xml, tuple(metrics["size_px"])) if xml else {"nodes": []}
        with open(os.path.join(out_dir, f"{idx:02d}.nodes.json"), "w", encoding="utf-8") as f:
            json.dump(nodes, f, ensure_ascii=False, indent=1)
        result["captures"].append({"index": idx, "nodes": len(nodes.get("nodes", []))})
    w, h = metrics["size_px"] if metrics["size_px"][0] else (1080, 2400)
    for k in range(scrolls):
        # 只做「往上滑」這個非破壞性動作（內容捲動，不會觸發按鈕）
        sh(adb, "shell", "input", "swipe", str(w // 2), str(int(h * 0.75)),
           str(w // 2), str(int(h * 0.35)), "350")
        time.sleep(1.2)
        idx += 1
        ok, xml = capture_screen(adb, out_dir, idx)
        if ok:
            nodes = parse_xml(xml, (w, h)) if xml else {"nodes": []}
            with open(os.path.join(out_dir, f"{idx:02d}.nodes.json"), "w", encoding="utf-8") as f:
                json.dump(nodes, f, ensure_ascii=False, indent=1)
            result["captures"].append({"index": idx, "nodes": len(nodes.get("nodes", []))})
    sh(adb, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(0.8)
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--packages", type=str, default="")
    ap.add_argument("--no-scroll", action="store_true")
    args = ap.parse_args()

    cfg = load_config()
    adb = adb_path(cfg)
    devs = devices(adb)
    if not devs:
        print("找不到裝置。請確認手機已插上並開啟 USB 偵錯。")
        return 2
    print(f"裝置：{devs[0]}")
    metrics = device_metrics(adb)
    print(f"規格：{metrics['model']}  Android {metrics['release']} (SDK {metrics['sdk']})")
    print(f"      {metrics['size_px'][0]}x{metrics['size_px'][1]}px  "
          f"density {metrics['density_dpi']}dpi (×{metrics['density_scale']})  "
          f"→ {metrics['dp_size'][0]}x{metrics['dp_size'][1]}dp  比例 {metrics['aspect']}")

    installed = list_packages(adb)
    if args.list:
        excludes = cfg.get("exclude_packages", [])
        includes = set(cfg.get("include_packages", []))
        print(f"\n已安裝 {len(installed)} 個 package。")
        print("\n[將複製的 App]")
        for p in installed:
            if p in includes:
                print(f"  ✓ {p}")
        missing = [p for p in includes if p not in installed]
        if missing:
            print(f"\n[config 有列但未安裝] {missing}")
        print("\n[排除的開發工具]")
        for p in installed:
            if any(x in p for x in excludes):
                print(f"  ✗ {p}")
        return 0

    if args.packages:
        targets = [p.strip() for p in args.packages.split(",") if p.strip()]
    elif args.all:
        excludes = cfg.get("exclude_packages", [])
        targets = [p for p in cfg.get("include_packages", [])
                   if p in installed and not any(x in p for x in excludes)]
    else:
        print("請指定 --list / --all / --packages a,b,c")
        return 2

    scrolls = 0 if args.no_scroll else cfg["device"].get("scroll_captures", 2)
    results = []
    for i, pkg in enumerate(targets, 1):
        print(f"[{i}/{len(targets)}] 複製 {pkg} …", end="", flush=True)
        r = capture_app(adb, pkg, metrics, scrolls)
        results.append(r)
        print(f" 完成（{len(r['captures'])} 個畫面，節點 {[c['nodes'] for c in r['captures']]}）")

    manifest = {
        "_comment": "真機介面複製（唯讀：啟動→截圖→dump→捲動→回首頁，不點按鈕）",
        "device": metrics,
        "packages": results,
        "captured_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    os.makedirs(DATA, exist_ok=True)
    with open(os.path.join(DATA, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print(f"\n已寫入 {os.path.join(DATA, 'manifest.json')}")
    print("下一步：python tools/phone_clone/build_env.py（把真實版面接進符號模擬器）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
