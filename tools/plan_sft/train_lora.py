"""Qwen3-0.6B planner LoRA 訓練（RTX 5050 8GB 可跑）。

資料格式：tools/plan_sft/gen_plan_dataset.py 產生的 train.jsonl
prompt 與 App 端 PromptBuilder.wrapChat 逐字一致（見 waeplan.render_chatml）。

用法：
  python tools/plan_sft/train_lora.py --data E:/WAE/datasets/plan_sft --out E:/WAE/models/planner-lora
"""
from __future__ import annotations

import argparse
import json
import os
import sys

# 所有快取都放 E 槽（C 槽留給系統）
os.environ.setdefault("HF_HOME", r"E:\WAE\hf-home")
os.environ.setdefault("HF_HUB_CACHE", r"E:\WAE\hf-home\hub")
os.environ.setdefault("TRANSFORMERS_CACHE", r"E:\WAE\hf-home\hub")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from waeplan import ASSISTANT_SUFFIX, render_chatml  # noqa: E402

import torch  # noqa: E402
from datasets import Dataset  # noqa: E402
from peft import LoraConfig, get_peft_model  # noqa: E402
from transformers import (  # noqa: E402
    AutoModelForCausalLM,
    AutoTokenizer,
    DataCollatorForSeq2Seq,
    Trainer,
    TrainingArguments,
)


def load_rows(path: str) -> list[dict]:
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def build_example(tok, row: dict, max_len: int) -> dict:
    prompt = render_chatml(row["goal"], row.get("selected_app"), row["installed_apps"])
    target = json.dumps(row["plan"], ensure_ascii=False, separators=(",", ":")) + ASSISTANT_SUFFIX

    p_ids = tok(prompt, add_special_tokens=False)["input_ids"]
    t_ids = tok(target, add_special_tokens=False)["input_ids"]

    # 保留 target 的尾巴（計畫本身比 system prompt 重要）
    if len(p_ids) + len(t_ids) > max_len:
        keep_t = min(len(t_ids), max_len // 2)
        keep_p = max_len - keep_t
        p_ids = p_ids[-keep_p:]
        t_ids = t_ids[:keep_t]

    input_ids = p_ids + t_ids
    labels = [-100] * len(p_ids) + t_ids
    return {"input_ids": input_ids, "labels": labels, "attention_mask": [1] * len(input_ids)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="Qwen/Qwen3-0.6B")
    ap.add_argument("--data", default=r"E:/WAE/datasets/plan_sft")
    ap.add_argument("--out", default=r"E:/WAE/models/planner-lora")
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--max-len", type=int, default=2048)
    ap.add_argument("--bs", type=int, default=1)
    ap.add_argument("--accum", type=int, default=8)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--max-steps", type=int, default=-1)
    ap.add_argument("--resume", action="store_true", help="從 output_dir/ckpt 的最新 checkpoint 續訓")
    ap.add_argument("--no-gc", action="store_true", help="關閉 gradient checkpointing（較快，較吃 VRAM）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    torch.manual_seed(0)
    if not torch.cuda.is_available():
        raise SystemExit("CUDA 不可用：請確認 torch 是 cu128 版本且驅動正常")
    print("GPU:", torch.cuda.get_device_name(0), "VRAM(GB):", round(torch.cuda.get_device_properties(0).total_memory / 1e9, 1))

    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token

    rows = load_rows(os.path.join(args.data, "train.jsonl"))
    print("train rows:", len(rows))

    feats = [build_example(tok, r, args.max_len) for r in rows]
    lens = sorted(len(f["input_ids"]) for f in feats)
    print("tokens p50/p90/max:", lens[len(lens) // 2], lens[int(len(lens) * 0.9)], lens[-1])

    if args.dry_run:
        i = 0
        print("--- prompt head ---")
        print(tok.decode(feats[i]["input_ids"][:200]))
        print("--- target ---")
        print(tok.decode([t for t in feats[i]["labels"] if t != -100]))
        return

    ds = Dataset.from_list([{"input_ids": f["input_ids"], "labels": f["labels"], "attention_mask": f["attention_mask"]} for f in feats])

    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.bfloat16, attn_implementation="sdpa"
    )
    model.config.use_cache = False
    if not args.no_gc:
        model.gradient_checkpointing_enable()
        model.enable_input_require_grads()

    lora = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_r * 2,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    # transformers v5 沒有 warmup_ratio，改成先算總步數再給 warmup_steps
    steps_per_epoch = max(1, len(ds) // (args.bs * args.accum))
    total_steps = max(1, int(steps_per_epoch * args.epochs)) if args.max_steps <= 0 else args.max_steps
    warmup_steps = max(1, int(total_steps * 0.03))

    targs = TrainingArguments(
        output_dir=os.path.join(args.out, "ckpt"),
        per_device_train_batch_size=args.bs,
        gradient_accumulation_steps=args.accum,
        num_train_epochs=args.epochs,
        max_steps=args.max_steps,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=warmup_steps,
        logging_steps=20,
        save_strategy="steps",
        save_steps=400,
        save_total_limit=3,
        bf16=True,
        optim="adamw_torch_fused" if torch.cuda.is_available() else "adamw_torch",
        report_to=[],
        dataloader_num_workers=0,
        remove_unused_columns=False,
        gradient_checkpointing=not args.no_gc,
    )

    collator = DataCollatorForSeq2Seq(tok, padding=True, label_pad_token_id=-100)
    trainer = Trainer(model=model, args=targs, train_dataset=ds, data_collator=collator)
    trainer.train(resume_from_checkpoint=True if args.resume else None)

    os.makedirs(args.out, exist_ok=True)
    model.save_pretrained(args.out)
    tok.save_pretrained(args.out)
    print("saved adapter →", args.out)


if __name__ == "__main__":
    main()
