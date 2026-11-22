#!/usr/bin/env python3
"""
生成通用「UI 動作詞彙庫」：app/src/main/assets/open_source_ai_databases/ui_action_vocabulary.tsv

用途：把「按鈕文字 → 動作角色」的判定做成跨 App 的通用資料，而不是寫死某一款 App 的按鈕。

資料來源：AOSP Settings 的多語系字串資源（Apache-2.0）
  https://github.com/aosp-mirror/platform_packages_apps_settings
  res/values/strings.xml            (英文)
  res/values-zh-rCN/strings.xml     (簡體中文)
  res/values-zh-rTW/strings.xml     (繁體中文)
  res/values-ja/strings.xml         (日文)
  res/values-ko/strings.xml         (韓文)

為什麼用系統設定而非某個 App：這些是 Android 系統 UI 的標準用語（確定/取消/下一頁…），
屬於「平台通用詞彙」，可安全套用到任意 App 的按鈕判讀。

用法：
  python tools/build_ui_action_vocabulary.py
"""
import json
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

BASE = "https://raw.githubusercontent.com/aosp-mirror/platform_packages_apps_settings/master/res/"
LOCALES = {
    "en": "values",
    "zh_CN": "values-zh-rCN",
    "zh_TW": "values-zh-rTW",
    "ja": "values-ja",
    "ko": "values-ko",
}
OUT = os.path.join("app", "src", "main", "assets", "open_source_ai_databases", "ui_action_vocabulary.tsv")
OPENCC = os.path.join("app", "src", "main", "assets", "open_source_ai_databases", "opencc_zh_tw_cn_lexicon.tsv")

# 以 AOSP 資源鍵名（技術命名，非 App 資料）篩選「動作類」字串
KEY_PATTERN = re.compile(
    r"(^|_)(ok|cancel|next|back|done|save|delete|remove|search|share|send|allow|deny|"
    r"close|skip|retry|continue|confirm|accept|reject|submit|apply|reset|clear|add|edit|"
    r"rename|enable|disable|install|uninstall|update|copy|paste|cut|select|open|view|"
    r"yes|no|agree|disagree|sign_in|signin|sign_out|signout|log_in|login|log_out|logout|"
    r"download|upload|buy|purchase|pay|subscribe|unsubscribe|follow|unfollow|like|"
    r"comment|reply|forward|refresh|reload|undo|redo|dismiss|finish|start|stop|pause|resume)"
    r"($|_)"
)
PLACEHOLDER = re.compile(r"%(\d+\$)?[sd]")
MAX_LEN = 16


def fetch(locale_dir: str) -> str:
    url = f"{BASE}{locale_dir}/strings.xml"
    req = urllib.request.Request(url, headers={"User-Agent": "wae-ui-vocab-builder"})
    return urllib.request.urlopen(req, timeout=180).read().decode("utf-8", "replace")


def parse(xml_text: str) -> dict:
    """回傳 {key: value}；跳過不可翻譯、含格式占位符、過長或含標籤的值"""
    out = {}
    root = ET.fromstring(xml_text)
    for node in root:
        if not node.tag == "string":
            continue
        key = node.get("name")
        if not key or node.get("translatable") == "false":
            continue
        value = "".join(node.itertext()).strip()
        if not value or len(value) > MAX_LEN or PLACEHOLDER.search(value):
            continue
        # 去掉 XML 內嵌標籤造成的多餘空白
        value = re.sub(r"\s+", " ", value)
        # AOSP 有些字串用引號包住（例如 "允许"），去掉成對引號
        if len(value) >= 2 and value[0] in "\"'" and value[-1] == value[0]:
            value = value[1:-1].strip()
        if not value:
            continue
        out[key] = value
    return out


def load_opencc() -> tuple:
    """回傳 (簡→繁, 繁→簡) 的單字映射"""
    s2t, t2s = {}, {}
    if not os.path.exists(OPENCC):
        return s2t, t2s
    with open(OPENCC, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[0] == "CHAR":
                s2t.setdefault(parts[1], parts[2])
                t2s.setdefault(parts[2], parts[1])
    return s2t, t2s


def main() -> int:
    s2t, t2s = load_opencc()

    def variants(value: str) -> list:
        out = set()
        t = "".join(s2t.get(ch, ch) for ch in value)
        s = "".join(t2s.get(ch, ch) for ch in value)
        if t != value:
            out.add(t)
        if s != value:
            out.add(s)
        return sorted(out)

    per_locale = {}
    for loc, dirname in LOCALES.items():
        print(f"fetching {dirname} ...")
        per_locale[loc] = parse(fetch(dirname))

    keys = set(per_locale["en"].keys())
    for loc in LOCALES:
        keys &= set(per_locale[loc].keys())
    action_keys = sorted(k for k in keys if KEY_PATTERN.search(k))

    rows = []
    for k in action_keys:
        values = [per_locale[loc][k] for loc in LOCALES]
        if len(set(values)) == 1 and len(values[0]) > 12:
            continue
        # 以 OpenCC 補繁簡變體，提升跨語系覆蓋（例如 下一页 ↔ 下一頁）
        extra = set()
        for v in values:
            extra.update(variants(v))
        rows.append((k, values, sorted(extra - set(values))))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 通用 UI 動作詞彙庫（跨 App 通用，無任何單一 App 資料）\n")
        f.write("# 來源: AOSP Settings 多語系字串資源 (Apache-2.0) + OpenCC 繁簡變體 (Apache-2.0)\n")
        f.write("# 格式: key \t en \t zh_CN \t zh_TW \t ja \t ko \t 繁簡變體(|分隔)\n")
        for k, values, extra in rows:
            f.write("\t".join([k] + values + ["|".join(extra)]) + "\n")

    print(json.dumps({
        "rows": len(rows),
        "bytes": os.path.getsize(OUT),
        "locales": list(LOCALES.keys()),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
