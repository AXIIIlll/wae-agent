"""Planner 離線評測：base vs LoRA adapter，同一份 prompt 與解碼參數。

用法：
  python tools/plan_sft/eval_plans.py --data E:/WAE/datasets/plan_sft --adapter E:/WAE/models/planner-lora
  python tools/plan_sft/eval_plans.py --data E:/WAE/datasets/plan_sft            # 只測 base
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time

os.environ.setdefault("HF_HOME", r"E:\WAE\hf-home")
os.environ.setdefault("HF_HUB_CACHE", r"E:\WAE\hf-home\hub")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from waeplan import render_chatml, validate_plan  # noqa: E402

import torch  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: E402

APP_RE = re.compile(r"\$app:([^\"，,\s]+)")


def load_rows(path: str) -> list[dict]:
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def first_app(plan) -> str:
    for s in plan.get("steps", []):
        m = APP_RE.search(str(s.get("target", "")))
        if m:
            return m.group(1)
    return ""


def norm(plan):
    return [
        (str(s.get("do", "")).strip(), str(s.get("target", "")).strip(), str(s.get("verify", "")).strip())
        for s in plan.get("steps", [])
    ]


def parse_json(text: str):
    start = text.find("{")
    if start < 0:
        return None
    depth, in_str, esc = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:i + 1])
                    except Exception:
                        return None
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="Qwen/Qwen3-0.6B")
    ap.add_argument("--adapter", default=None)
    ap.add_argument("--data", default=r"E:/WAE/datasets/plan_sft")
    ap.add_argument("--files", default="valid.jsonl,valid_hard.jsonl")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-new", type=int, default=256)
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.bfloat16, attn_implementation="sdpa"
    )
    if args.adapter:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, args.adapter)
    model.eval()
    model.to("cuda")

    all_results = {}
    for fname in args.files.split(","):
        path = os.path.join(args.data, fname)
        if not os.path.exists(path):
            continue
        rows = load_rows(path)
        if args.limit:
            rows = rows[: args.limit]

        stats = dict(n=0, json_ok=0, schema_ok=0, goal_kind=0, steps_eq=0, app_ok=0, exact=0, tok=0, sec=0.0,
                     ask_tp=0, ask_fp=0, ask_fn=0)
        by_source = {}
        samples = []
        for r in rows:
            prompt = render_chatml(r["goal"], r.get("selected_app"), r["installed_apps"])
            inp = tok(prompt, return_tensors="pt", add_special_tokens=False).to("cuda")
            t0 = time.time()
            with torch.no_grad():
                out = model.generate(
                    **inp, max_new_tokens=args.max_new, do_sample=False,
                    pad_token_id=tok.pad_token_id,
                )
            dt = time.time() - t0
            gen = tok.decode(out[0][inp["input_ids"].shape[1]:], skip_special_tokens=True)

            got = parse_json(gen)
            ok, _ = validate_plan(got) if got else (False, "no_json")
            gold = r["plan"]

            stats["n"] += 1
            stats["sec"] += dt
            stats["tok"] += out.shape[1] - inp["input_ids"].shape[1]
            if got is not None:
                stats["json_ok"] += 1
            if ok:
                stats["schema_ok"] += 1
            if got and got.get("goalKind") == gold.get("goalKind"):
                stats["goal_kind"] += 1
            if got and len(got.get("steps", [])) == len(gold["steps"]):
                stats["steps_eq"] += 1
            ga, wa = first_app(gold), (first_app(got) if got else "")
            if ga == wa or (not ga and not wa):
                stats["app_ok"] += 1
            if got and norm(got) == norm(gold):
                stats["exact"] += 1

            g_ask = any(s.get("ask") for s in gold["steps"])
            m_ask = any(s.get("ask") for s in (got.get("steps", []) if got else []))
            if g_ask and m_ask:
                stats["ask_tp"] += 1
            elif g_ask and not m_ask:
                stats["ask_fn"] += 1
            elif (not g_ask) and m_ask:
                stats["ask_fp"] += 1

            src = r.get("source", "?")
            b = by_source.setdefault(src, [0, 0, 0])  # n, schema, exact
            b[0] += 1
            b[1] += 1 if ok else 0
            b[2] += 1 if (got and norm(got) == norm(gold)) else 0

            if len(samples) < 5 and not ok:
                samples.append({"goal": r["goal"], "gen": gen[:400], "gold": json.dumps(gold, ensure_ascii=False)})

        n = max(1, stats["n"])
        tp, fp, fn = stats["ask_tp"], stats["ask_fp"], stats["ask_fn"]
        ask_prec = round(tp / (tp + fp) * 100, 1) if (tp + fp) else None
        ask_rec = round(tp / (tp + fn) * 100, 1) if (tp + fn) else None
        res = {
            "file": fname,
            "n": stats["n"],
            "json_ok": round(stats["json_ok"] / n * 100, 1),
            "schema_ok": round(stats["schema_ok"] / n * 100, 1),
            "goal_kind": round(stats["goal_kind"] / n * 100, 1),
            "steps_eq": round(stats["steps_eq"] / n * 100, 1),
            "app_ok": round(stats["app_ok"] / n * 100, 1),
            "exact": round(stats["exact"] / n * 100, 1),
            "ask_precision": ask_prec,
            "ask_recall": ask_rec,
            "sec_per_item": round(stats["sec"] / n, 2),
            "tok_per_sec": round(stats["tok"] / max(1e-6, stats["sec"]), 1),
            "by_source": {k: {"n": v[0], "schema": round(v[1] / max(1, v[0]) * 100, 1),
                              "exact": round(v[2] / max(1, v[0]) * 100, 1)} for k, v in by_source.items()},
            "fail_samples": samples,
        }
        all_results[fname] = res
        print(json.dumps({k: v for k, v in res.items() if k != "fail_samples"}, ensure_ascii=False, indent=2))

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(all_results, f, ensure_ascii=False, indent=2)
        print("report →", args.json)


if __name__ == "__main__":
    main()
