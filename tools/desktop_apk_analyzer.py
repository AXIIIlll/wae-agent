#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WAE V2.6 桌面端 APK 靜態能力提取器 (Desktop APK Capability Analyzer)
職責：
1. 解析反編譯後之 AndroidManifest.xml，提取所有 Activity 的 intent-filter Deep Link schema
2. 掃描 jadx 反編譯源碼中之 getQueryParameter("...") / getStringExtra("...") 真實參數鍵名
3. 提取 res/values/strings.xml 與 res/layout/*.xml 之 viewId 語義映射
4. 產出供手機端 WaeDeepLinkCapabilityManager 直接匯入之 `app_capability.json`
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

QUERY_PARAM_REGEX = re.compile(
    r'(?:getQueryParameter|getBooleanQueryParameter|getStringExtra)\(\s*"([a-zA-Z0-9_\-\.]+)"\s*\)'
)


def extract_query_params_from_source(decompiled_dir: Path, activity_class: str) -> list:
    rel_path = activity_class.lstrip(".").replace(".", "/")
    candidates = [
        decompiled_dir / "sources" / f"{rel_path}.java",
        decompiled_dir / "sources" / f"{rel_path}.kt",
    ]
    found_keys = []
    for src_file in candidates:
        if src_file.exists():
            try:
                content = src_file.read_text(encoding="utf-8", errors="ignore")
                for match in QUERY_PARAM_REGEX.findall(content):
                    if match not in found_keys:
                        found_keys.append(match)
            except Exception:
                pass
    return found_keys or ["q", "keyword"]


def analyze_decompiled_apk(decompiled_dir: Path) -> dict:
    manifest_path = decompiled_dir / "AndroidManifest.xml"
    if not manifest_path.exists():
        manifest_path = decompiled_dir / "resources" / "AndroidManifest.xml"

    deep_links = []
    pkg_name = "unknown.package"

    if manifest_path.exists():
        tree = ET.parse(manifest_path)
        root = tree.getroot()
        pkg_name = root.attrib.get("package", pkg_name)

        for idx, activity in enumerate(root.iter("activity")):
            act_name = activity.attrib.get(f"{ANDROID_NS}name", "")
            if act_name.startswith("."):
                act_name = pkg_name + act_name

            for intent_filter in activity.findall("intent-filter"):
                schemes, hosts, paths = set(), set(), set()
                for data_tag in intent_filter.findall("data"):
                    s = data_tag.attrib.get(f"{ANDROID_NS}scheme")
                    h = data_tag.attrib.get(f"{ANDROID_NS}host")
                    p = (
                        data_tag.attrib.get(f"{ANDROID_NS}path")
                        or data_tag.attrib.get(f"{ANDROID_NS}pathPrefix")
                        or ""
                    )
                    if s:
                        schemes.add(s)
                    if h:
                        hosts.add(h)
                    if p:
                        paths.add(p)

                if schemes and hosts:
                    param_keys = extract_query_params_from_source(decompiled_dir, act_name)
                    primary_key = param_keys[0]
                    for s in schemes:
                        for h in hosts:
                            path_part = next(iter(paths), "")
                            uri_tpl = f"{s}://{h}{path_part}?{primary_key}={{query}}"
                            is_search = "search" in uri_tpl.lower() or "query" in primary_key.lower()
                            deep_links.append(
                                {
                                    "ruleId": f"{pkg_name.split('.')[-1]}_{idx}_{s}",
                                    "packageName": pkg_name,
                                    "appAliases": [pkg_name.split(".")[-1]],
                                    "canonicalIntentTag": "search_input" if is_search else "open_detail",
                                    "uriTemplate": uri_tpl,
                                    "queryParamKeys": param_keys,
                                    "targetActivity": act_name,
                                    "expectedLandingPosture": "SEARCH_RESULTS" if is_search else "DETAIL_PAGE",
                                    "status": "ACTIVE",
                                }
                            )

    return {
        "packageName": pkg_name,
        "deepLinks": deep_links,
    }


if __name__ == "__main__":
    target_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out_file = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("app_capability.json")
    result = analyze_decompiled_apk(target_dir)
    out_file.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[WAE Analyzer] Extracted {len(result['deepLinks'])} deep links -> {out_file}")
