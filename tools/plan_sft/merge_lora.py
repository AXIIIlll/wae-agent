"""把 planner LoRA 合併回 base，輸出可直接轉 GGUF 的 HF 目錄。

用法：
  python tools/plan_sft/merge_lora.py --base Qwen/Qwen3-0.6B ^
      --adapter E:/WAE/models/planner-lora --out E:/WAE/models/qwen3-0.6b-wae-planner
"""
from __future__ import annotations

import argparse
import os

os.environ.setdefault("HF_HOME", r"E:\WAE\hf-home")
os.environ.setdefault("HF_HUB_CACHE", r"E:\WAE\hf-home\hub")

import torch  # noqa: E402
from peft import PeftModel  # noqa: E402
from transformers import AutoModelForCausalLM, AutoTokenizer  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen3-0.6B")
    ap.add_argument("--adapter", default=r"E:/WAE/models/planner-lora")
    ap.add_argument("--out", default=r"E:/WAE/models/qwen3-0.6b-wae-planner")
    args = ap.parse_args()

    base = AutoModelForCausalLM.from_pretrained(args.base, torch_dtype=torch.float16)
    model = PeftModel.from_pretrained(base, args.adapter)
    merged = model.merge_and_unload()
    merged.eval()
    os.makedirs(args.out, exist_ok=True)
    merged.save_pretrained(args.out, safe_serialization=True)
    AutoTokenizer.from_pretrained(args.base, trust_remote_code=True).save_pretrained(args.out)
    print("merged →", args.out)


if __name__ == "__main__":
    main()
