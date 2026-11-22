#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「圖示語意表」轉成多定義目標表（P0：只產生資料，不改行為）。

為什麼要有生成器：這個專案的規則是「程式不得內建詞表、也不要手列清單」。
現有的 `google_material_icons_full_semantics.tsv` 已經同時具備
「圖示名 → 正規 id → 中文詞」三層，正好是多定義需要的原料，
所以這裡只做**轉換與統計**，不新增任何人工詞條。

輸出（全部 UTF-8、無 BOM、LF）：
  ui_entity_definitions.tsv  entity \t group \t view \t value \t specificity \t note
  ui_entity_groups.tsv       group \t exclusivity \t note
  ui_view_weights.tsv        view \t weight \t note

specificity 用 IDF 算：一個詞被越多 entity 共用，權重越低（「介面圖標」這種
填充詞會自動壓到 0.05，不需要有人去列黑名單）。之後可人工覆寫單列。
"""

import argparse
import math
import os
from collections import defaultdict

MIN_SPEC = 0.05
VIEW_WEIGHTS = [
    ("TEXT", "0.55", "中文詞（使用者怎麼說）"),
    ("DESC", "0.15", "contentDescription（開發者怎麼描述）"),
    ("ICON", "0.15", "圖示名（沒有文字時唯一的訊號）"),
    ("ROLE", "0.08", "控制項角色（按鈕／開關／輸入框）"),
    ("STATE", "0.07", "開關狀態"),
]


GENERIC_ROLE = "GENERAL_UI_ICON"


def read_rows(path):
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n").lstrip("\ufeff")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            cells = line.split("\t")
            if len(cells) < 6:
                continue
            yield [c.strip() for c in cells]


def pick_entity(icon, role):
    """具體 role（例如 MAP_DIRECTIONS_ROUTE）代表同一個目標可以有多個圖示；
    `GENERAL_UI_ICON` 這種通用 role 沒有鑑別力，改用圖示名本身當 entity。"""
    role = role.strip()
    if role and role != GENERIC_ROLE:
        return role
    return icon.strip()


def build(src, def_path, group_path, weight_path):
    per_entity = defaultdict(list)   # entity -> [(group, view, value, note)]
    entity_group = {}
    skipped = 0

    for icon, categories, role, zh_tw, _zh_cn, _tags in read_rows(src):
        entity = pick_entity(icon, role)
        if not entity or not icon:
            skipped += 1
            continue
        group = categories.split("|")[-1].strip() or "misc"
        entity_group.setdefault(entity, group)
        per_entity[entity].append((group, "ICON", icon, "圖示名"))
        for term in zh_tw.split("|"):
            term = term.strip()
            if term:
                per_entity[entity].append((group, "TEXT", term, "中文詞"))

    if not per_entity:
        raise SystemExit("來源表沒有任何可用列，停止（避免產生空表）")

    # 第二來源：`ui_icon_aliases.tsv`（圖示名 → 中文叫法），用圖示名當鍵 join。
    # 這樣「wifi → Wi-Fi」「bluetooth → 藍牙」這種中文命名會併進同一個 entity，
    # 不需要在程式裡列任何詞。
    icon_to_entities = defaultdict(set)
    for entity, defs in per_entity.items():
        for _g, view, value, _n in defs:
            if view == "ICON":
                icon_to_entities[value].add(entity)
    alias_src = os.path.join(os.path.dirname(src), "ui_icon_aliases.tsv")
    joined = 0
    if os.path.exists(alias_src):
        with open(alias_src, "r", encoding="utf-8") as f:
            for raw in f:
                line = raw.rstrip("\n").lstrip("\ufeff")
                if not line.strip() or line.lstrip().startswith("#") or line.startswith("!"):
                    continue
                cells = [c.strip() for c in line.split("\t")]
                if len(cells) < 2 or not cells[1]:
                    continue
                pattern, zh = cells[0], cells[1]
                # 只做精確 join。曾試過「圖示名有相同前綴就併」，結果把 Wi-Fi 潑到
                # wifi_calling 甚至 ADD_CREATE_NEW 這種共用圖示的實體上——
                # 寧可少接幾條，也不要污染資料（多定義的價值建立在精度上）。
                for entity in icon_to_entities.get(pattern, ()):
                    group = entity_group[entity]
                    per_entity[entity].append((group, "TEXT", zh, "圖示別名"))
                    joined += 1

    # IDF：同一個 value 被幾個 entity 用到
    df = defaultdict(set)
    for entity, defs in per_entity.items():
        for _g, view, value, _n in defs:
            df[(view, value)].add(entity)

    total = len(per_entity)
    denom = math.log(total + 1.0) or 1.0

    rows = []
    for entity in sorted(per_entity):
        group = entity_group[entity]
        seen = set()
        for _g, view, value, note in per_entity[entity]:
            key = (view, value)
            if key in seen:
                continue
            seen.add(key)
            idf = math.log((total + 1.0) / (len(df[key]) + 1.0)) / denom
            spec = max(MIN_SPEC, min(1.0, idf))
            rows.append((entity, group, view, value, f"{spec:.3f}", note))

    write_tsv(def_path, [
        "# 目標多定義表（由 tools/build_entity_definitions.py 產生，請勿手改）",
        "# 格式：entity <TAB> group <TAB> view <TAB> value <TAB> specificity <TAB> note",
        "# entity＝正規 id；group＝兄弟群（負向自動來自同群）；view＝TEXT/DESC/ICON/ROLE/STATE/ID",
        "# specificity＝IDF（越籠統越小）；改值請改生成器設定，不要改這張表",
    ], rows)

    group_rows = []
    for group in sorted(set(entity_group.values())):
        members = sum(1 for e, g in entity_group.items() if g == group)
        group_rows.append((group, "0.7", f"{members} 個 entity；同群視為互斥，負向強度可調"))
    write_tsv(group_path, [
        "# 兄弟群（負向自動生成用）：同群的其他 entity 會成為彼此的負向定義",
        "# 格式：group <TAB> exclusivity <TAB> note（0＝不互斥，1＝完全互斥）",
    ], group_rows)

    write_tsv(weight_path, [
        "# 多視圖融合權重（一個目標可以由多個視圖的證據命中）",
        "# 格式：view <TAB> weight <TAB> note",
    ], [(v, w, n) for v, w, n in VIEW_WEIGHTS])

    print(f"entities={total} definitions={len(rows)} groups={len(group_rows)} skipped={skipped}")
    print(f"定義檔：{def_path}")
    print(f"群組檔：{group_path}")
    print(f"權重檔：{weight_path}")


def write_tsv(path, header, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for h in header:
            f.write(h + "\n")
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    default_out = os.path.join(root, "app", "src", "main", "assets", "open_source_ai_databases")
    ap.add_argument("--src", default=os.path.join(default_out, "google_material_icons_full_semantics.tsv"))
    ap.add_argument("--out", default=default_out)
    args = ap.parse_args()
    build(
        args.src,
        os.path.join(args.out, "ui_entity_definitions.tsv"),
        os.path.join(args.out, "ui_entity_groups.tsv"),
        os.path.join(args.out, "ui_view_weights.tsv"),
    )


if __name__ == "__main__":
    main()
