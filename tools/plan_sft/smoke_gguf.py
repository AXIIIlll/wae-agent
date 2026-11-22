"""量化後的 GGUF 冒煙測試（CPU，用 llama-cpp-python）。

用法：
  python tools/plan_sft/smoke_gguf.py --model E:/WAE/models/qwen3-0.6b-wae-planner.Q4_K_M.gguf
  python tools/plan_sft/smoke_gguf.py --model <orig.gguf>   # 對照舊模型
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from waeplan import render_chatml, validate_plan  # noqa: E402

GOALS = [
    ("我想看劇", ["設定", "Netflix", "紅果短劇", "LINE", "Disney+"]),
    ("幫我把藍牙關掉", ["設定", "相簿", "Chrome", "LINE"]),
    ("查一下電池剩多少", ["設定", "相簿", "Chrome", "LINE"]),
    ("打開 Netflix", ["設定", "相簿", "Chrome", "LINE", "Netflix"]),
    ("幫我訂餐廳", ["設定", "Chrome", "foodpanda"]),
    ("用瀏覽器搜尋台北一零一", ["設定", "Chrome", "LINE"]),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--n-ctx", type=int, default=1024)
    ap.add_argument("--max-tokens", type=int, default=200)
    args = ap.parse_args()

    from llama_cpp import Llama

    llm = Llama(
        model_path=args.model,
        n_ctx=args.n_ctx,
        n_threads=6,
        verbose=False,
    )

    ok = 0
    for goal, apps in GOALS:
        prompt = render_chatml(goal, None, apps)
        out = llm(prompt, max_tokens=args.max_tokens, temperature=0.0, stop=["<|im_end|>"])
        text = out["choices"][0]["text"].strip()
        plan = None
        start = text.find("{")
        if start >= 0:
            depth = 0
            for i in range(start, len(text)):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        try:
                            plan = json.loads(text[start:i + 1])
                        except Exception:
                            plan = None
                        break
        valid, why = validate_plan(plan) if plan else (False, "no_json")
        ok += 1 if valid else 0
        print(f"\n--- {goal} [{ 'OK' if valid else 'FAIL:' + why }]")
        print(text[:400])
    print(f"\n=== schema_ok {ok}/{len(GOALS)} ===")


if __name__ == "__main__":
    main()
