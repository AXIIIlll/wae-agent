"""合併兩個訓練集：既有設定/查詢資料 + CMGUI 真實開放式指令。

輸出目錄結構與 train_lora.py 相容：
  train.jsonl / valid.jsonl
另外輸出 valid_cmgui.jsonl（只含 CMGUI 的留出樣本，用來單獨看泛化）。

用法：
  python tools/cmgui/merge_datasets.py --base E:/WAE/datasets/plan_sft --cmgui E:/WAE/datasets/cmgui_plans/plan_rows.jsonl --out E:/WAE/datasets/plan_mix
"""
from __future__ import annotations

import argparse
import json
import os
import random


def read_jsonl(path: str) -> list[dict]:
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=r"E:/WAE/datasets/plan_sft")
    ap.add_argument("--cmgui", default=r"E:/WAE/datasets/cmgui_plans/plan_rows.jsonl")
    ap.add_argument("--out", default=r"E:/WAE/datasets/plan_mix")
    ap.add_argument("--base-ratio", type=float, default=1.0, help="既有資料保留比例（1.0=全留）")
    ap.add_argument("--cmgui-ratio", type=float, default=1.0, help="CMGUI 資料保留比例")
    ap.add_argument("--seed", type=int, default=11)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    base_train = read_jsonl(os.path.join(args.base, "train.jsonl"))
    base_valid = read_jsonl(os.path.join(args.base, "valid.jsonl"))
    cmgui = read_jsonl(args.cmgui)
    if args.cmgui_ratio < 1.0:
        rng.shuffle(cmgui)
        cmgui = cmgui[: int(len(cmgui) * args.cmgui_ratio)]

    if args.base_ratio < 1.0:
        rng.shuffle(base_train)
        base_train = base_train[: int(len(base_train) * args.base_ratio)]

    # CMGUI 依 95/5 切分（用 episode_id 的雜湊，避免同段洩漏）
    cm_train, cm_valid = [], []
    for r in cmgui:
        h = hash(str(r.get("plan", {}).get("summary", "")) + r.get("goal", "")) % 100
        (cm_valid if h < 5 else cm_train).append(r)

    train = base_train + cm_train
    rng.shuffle(train)
    valid = base_valid + cm_valid

    os.makedirs(args.out, exist_ok=True)

    def dump(name, rows):
        with open(os.path.join(args.out, name), "w", encoding="utf-8", newline="\n") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")

    dump("train.jsonl", train)
    dump("valid.jsonl", valid)
    dump("valid_cmgui.jsonl", cm_valid)

    print(f"base train={len(base_train)} valid={len(base_valid)}")
    print(f"cmgui train={len(cm_train)} valid={len(cm_valid)}")
    print(f"mixed train={len(train)} valid={len(valid)} -> {args.out}")


if __name__ == "__main__":
    main()
