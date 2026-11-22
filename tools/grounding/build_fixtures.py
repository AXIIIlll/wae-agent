#!/usr/bin/env python3
"""
G0：把真機 trace JSONL 轉成可重播 fixture（stdlib only）。

為什麼要轉：
  - trace 含真實畫面文字 / view id / 套件名 → 有隱私風險，且難以 commit
  - fixture 只保留「文字長度 + 幾何 + 旗標 + 分數」，並把文字 token 化成
    T1/T2、viewId 化成 V1/V2、pkg 化成 P1（可用 --keep-pkg / --keep-ids 保留原值）
  - 弱標籤沿用 G2 的規則（畫面有變化 / 後置條件成立 → chosenIndex 當答案），
    沒有正訊號的步記為 labelIndex=-1（不計入正確率）

輸入：`tools/grounding/traces/*.jsonl`（capture_benchmark.py 的輸出）
輸出：`tools/grounding/fixtures/<name>.json`（GroundingReplay 可直接解析）

用法：
  python tools/grounding/build_fixtures.py
  python tools/grounding/build_fixtures.py --input tools/grounding/traces/20260101_x.jsonl --name real_20260101
  python tools/grounding/build_fixtures.py --max-steps 300 --keep-pkg

已知邊界：
  - 弱標籤不是真值：畫面有變化不保證點對，所以 fixture 只用來量「相對退步」，
    不是對外宣稱的絕對正確率（要真值需人工抽查或後置條件設計）
  - 一步一候選的 slot 原序會被保留；排序在 GroundingReplay 由 score 重算
"""
import argparse
import datetime
import glob
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_INPUT_DIR = os.path.join(ROOT, "tools", "grounding", "traces")
DEFAULT_OUT_DIR = os.path.join(ROOT, "tools", "grounding", "fixtures")

SCHEMA = "wae-grounding-fixture/1"


def display_path(path):
    """相對專案根的路徑；跨磁碟機（例如暫存測試放 C:）時退回絕對路徑。"""
    try:
        return os.path.relpath(path, ROOT)
    except ValueError:
        return path


class Tokenizer(object):
    """把文字/套件/viewId 換成穩定 token（同一份 fixture 內一致）。"""

    def __init__(self, keep_pkg=False, keep_ids=False):
        self.keep_pkg = keep_pkg
        self.keep_ids = keep_ids
        self.maps = {"pkg": {}, "text": {}, "view": {}}
        self.counters = {"pkg": 0, "text": 0, "view": 0}

    def token(self, kind, raw, prefix):
        raw = "" if raw is None else str(raw)
        if raw == "":
            return ""
        if kind == "pkg" and self.keep_pkg:
            return raw
        if kind == "view" and self.keep_ids:
            return raw
        mapping = self.maps[kind]
        if raw not in mapping:
            self.counters[kind] += 1
            mapping[raw] = "%s%d" % (prefix, self.counters[kind])
        return mapping[raw]


def as_bool(value):
    return bool(value) if isinstance(value, (bool, int)) else False


def safe_int(value, default=-1):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def safe_float(value, default=0.0):
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def convert_step(trace, tokenizer, step_id):
    """一條 trace → fixture 的一個 step；資料不足以定位時回傳 None。"""
    candidates = trace.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        return None

    converted = []
    for index, candidate in enumerate(candidates):
        if not isinstance(candidate, dict):
            return None
        bounds = candidate.get("bounds")
        if not (isinstance(bounds, list) and len(bounds) == 4):
            bounds = [0, 0, 0, 0]
        raw_text = candidate.get("text", "") or ""
        raw_view = candidate.get("viewId", "") or ""
        converted.append(
            {
                "slot": safe_int(candidate.get("slot"), index),
                "text": tokenizer.token("text", raw_text, "T"),
                "textLen": len(str(raw_text)),
                "className": str(candidate.get("className", "") or ""),
                "viewId": tokenizer.token("view", raw_view, "V"),
                "viewIdLen": len(str(raw_view)),
                "bounds": [safe_int(value, 0) for value in bounds],
                "clickable": as_bool(candidate.get("clickable")),
                "editable": as_bool(candidate.get("editable")),
                "checkable": as_bool(candidate.get("checkable")),
                "checked": as_bool(candidate.get("checked")),
                "enabled": as_bool(candidate.get("enabled")),
                "score": round(safe_float(candidate.get("score")), 3),
            }
        )

    chosen_index = safe_int(trace.get("chosenIndex"), -1)
    if chosen_index < -1 or chosen_index >= len(converted):
        chosen_index = -1
    changed = as_bool(trace.get("changed"))
    postcondition = as_bool(trace.get("postconditionVerified"))

    # 弱標籤：有正訊號才把「實際選中的候選」當答案，否則未知
    if chosen_index >= 0 and (changed or postcondition):
        label_index = chosen_index
        label_reason = "screen_changed" if changed else "postcondition"
    else:
        label_index = -1
        label_reason = "unknown"

    return {
        "stepId": step_id,
        "pkg": tokenizer.token("pkg", trace.get("pkg", ""), "P"),
        "posture": str(trace.get("posture", "") or ""),
        "stateId": str(trace.get("stateId", "") or ""),
        "goal": tokenizer.token("text", trace.get("goal", ""), "T"),
        "chosenIndex": chosen_index,
        "labelIndex": label_index,
        "labelReason": label_reason,
        "changed": changed,
        "postconditionVerified": postcondition,
        "elapsedMs": max(0, safe_int(trace.get("elapsedMs"), 0)),
        "candidates": converted,
    }


def convert_file(path, name, args):
    tokenizer = Tokenizer(keep_pkg=args.keep_pkg, keep_ids=args.keep_ids)
    stem = os.path.splitext(os.path.basename(path))[0]
    steps = []
    skipped = 0
    with open(path, encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                trace = json.loads(line)
            except ValueError:
                print("  ! %s:%d 不是合法 JSON，跳過" % (os.path.basename(path), line_number))
                skipped += 1
                continue
            if not isinstance(trace, dict):
                skipped += 1
                continue
            step = convert_step(trace, tokenizer, "%s_s%04d" % (stem, len(steps)))
            if step is None:
                skipped += 1
                continue
            steps.append(step)
            if args.max_steps and len(steps) >= args.max_steps:
                break

    if not steps:
        print("  ! %s 沒有可轉換的 trace" % os.path.basename(path))
        return None

    labeled = sum(1 for step in steps if step["labelIndex"] >= 0)
    fixture = {
        "schema": SCHEMA,
        "name": name,
        "source": os.path.basename(path),
        "createdAt": datetime.datetime.now().strftime("%Y-%m-%d"),
        "tokenized": True,
        "keptPkg": bool(args.keep_pkg),
        "keptViewIds": bool(args.keep_ids),
        "stats": {
            "steps": len(steps),
            "labeled": labeled,
            "skipped": skipped,
            "textTokens": tokenizer.counters["text"],
            "viewTokens": tokenizer.counters["view"],
            "pkgTokens": tokenizer.counters["pkg"],
        },
        "labelPolicy": "changed|postconditionVerified -> chosenIndex; otherwise -1 (unknown)",
        "steps": steps,
    }
    return fixture


def collect_inputs(inputs):
    files = []
    for entry in inputs:
        if os.path.isdir(entry):
            files.extend(sorted(glob.glob(os.path.join(entry, "*.jsonl"))))
        elif os.path.isfile(entry):
            files.append(entry)
        else:
            print("  ! 找不到輸入：%s" % entry)
    return files


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    parser = argparse.ArgumentParser(description="trace JSONL → 可重播 grounding fixture（G0）")
    parser.add_argument(
        "--input", action="append", default=[], help="trace JSONL 檔或目錄（可重複；預設 traces/）"
    )
    parser.add_argument("--out", default=DEFAULT_OUT_DIR, help="fixture 輸出目錄")
    parser.add_argument("--name", default="", help="輸出 fixture 名稱（多輸入時自動加序號）")
    parser.add_argument("--max-steps", type=int, default=0, help="每個 fixture 最多幾步（0=不限）")
    parser.add_argument("--keep-pkg", action="store_true", help="保留原始套件名（預設 token 化成 P1）")
    parser.add_argument("--keep-ids", action="store_true", help="保留原始 viewId（預設 token 化成 V1）")
    args = parser.parse_args()

    inputs = args.input or [DEFAULT_INPUT_DIR]
    files = collect_inputs(inputs)
    if not files:
        print("沒有 trace 檔可轉換；先跑 python tools/grounding/capture_benchmark.py")
        return 1

    os.makedirs(args.out, exist_ok=True)
    written = 0
    for path in files:
        base_name = args.name or os.path.splitext(os.path.basename(path))[0]
        name = base_name
        suffix = 2
        while os.path.exists(os.path.join(args.out, "%s.json" % name)):
            name = "%s_%d" % (base_name, suffix)
            suffix += 1
        fixture = convert_file(path, name, args)
        if fixture is None:
            continue
        out_path = os.path.join(args.out, "%s.json" % name)
        with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(fixture, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        written += 1
        print(
            "  + %-40s steps=%-4d labeled=%-4d skipped=%-3d"
            % (display_path(out_path), len(fixture["steps"]),
               fixture["stats"]["labeled"], fixture["stats"]["skipped"])
        )

    if written == 0:
        print("沒有產生任何 fixture")
        return 1
    print("\n完成：%d 份 fixture → %s" % (written, display_path(args.out)))
    print("下一步：python tools/grounding/evaluate_grounding.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
