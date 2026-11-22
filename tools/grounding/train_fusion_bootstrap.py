#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Grounding 2.0 融合權重 bootstrap 產生器（G1 可選工具）。

用途：用**確定性的 bootstrap 參數**重新產生
`app/src/main/assets/open_source_ai_databases/ui_grounding_weights.tsv`，
讓 Kotlin 端的 DEFAULT_ROWS 與資產檔不會漂移（兩邊方向必須一致）。

設計原則：
  - 只依賴 Python 標準庫（不引入 numpy/pandas），任何 E 槽 venv 都能跑
  - 輸出 UTF-8 **無 BOM**、LF 換行（AGENTS.md 硬規則：TSV 不可有 BOM）
  - `--check` 模式：只比對現有檔案與預期內容，不一致時 exit 1（可放進 CI）
  - 這不是正式訓練器：正式權重由 `tools/grounding/train_grounding.py`
    （Phase 3 飛輪）用真機 JSONL 樣本產生，本檔只保證「有可重現的保底版本」

用法（在 repo 根目錄）：
    python tools/grounding/train_fusion_bootstrap.py
    python tools/grounding/train_fusion_bootstrap.py --check
    python tools/grounding/train_fusion_bootstrap.py --out path/to/ui_grounding_weights.tsv
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 方向 = 通道可信度 + 可動作性 + 與目標文字的空間關係（與 GroundingFusion.DEFAULT_ROWS 一致）
META: list[tuple[str, str, float]] = [
    ("meta", "alpha_fusion", 0.60),
    ("meta", "fusion_bias", 0.00),
]

WEIGHTS: list[tuple[str, float]] = [
    ("chan:a11y", 0.25),
    ("chan:ocr", 0.10),
    ("chan:icon", -0.10),
    ("chan:spatial", -0.20),
    ("flag:clickable", 0.80),
    ("flag:editable", 0.50),
    ("flag:checkable", 0.40),
    ("flag:checked", 0.10),
    ("flag:enabled", 0.60),
    ("flag:actionable", 0.40),
    ("flag:hasText", 0.20),
    ("flag:hasViewId", 0.15),
    ("flag:topOfChannel", 0.50),
    ("flag:singleOfChannel", 0.20),
    ("flag:sameRowText", 0.70),
    ("flag:containsText", 0.50),
    ("flag:leftOfText", -0.05),
    ("flag:rightOfText", 0.15),
    ("num:score", 0.90),
    ("num:channelRank", 0.40),
    ("num:statusRank", 0.20),
    ("num:area", -0.15),
    ("num:textLen", 0.10),
    ("num:vertOverlap", 0.30),
    ("num:edgeDist", 0.30),
]

HEADER = """\
# ui_grounding_weights.tsv — Grounding 2.0 多通道融合權重（Bootstrap 版本）
# 格式：model<TAB>feature<TAB>weight
#   model = fusion   （跨通道候選融合，輸出 bonus 加進 fusedScore）
#           meta     （alpha_fusion / fusion_bias；alpha 乘在 clamp 後的分數上）
# 註解以 # 開頭；空白行忽略；由 tools/grounding/train_fusion_bootstrap.py 產生或覆蓋。
# 分數規則：raw = bias + Σ(weight × feature)，clamp 在 ±2，再乘 alpha。
# 注意：ExtremeRuntimeHooks.GROUNDING_2_ENABLED 預設 false；旗標關閉時本檔完全不被使用。
"""


def render() -> str:
    """產生完整 TSV 內容（以 \n 結尾；不含 BOM）。"""
    lines = [HEADER.rstrip("\n")]
    for model, key, value in META:
        lines.append(f"{model}\t{key}\t{value:.2f}")
    for key, value in WEIGHTS:
        lines.append(f"fusion\t{key}\t{value:.2f}")
    return "\n".join(lines) + "\n"


def default_out() -> Path:
    """repo 根目錄下的 assets 路徑（由本檔位置往上兩層推算）。"""
    repo_root = Path(__file__).resolve().parents[2]
    return repo_root / "app/src/main/assets/open_source_ai_databases/ui_grounding_weights.tsv"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="產生 Grounding 2.0 融合 bootstrap 權重 TSV")
    ap.add_argument("--out", default=None, help="輸出檔路徑（預設 = assets 內的 ui_grounding_weights.tsv）")
    ap.add_argument("--check", action="store_true", help="只檢查現有檔案是否等於預期內容，不寫檔")
    args = ap.parse_args(argv)

    out = Path(args.out) if args.out else default_out()
    expected = render()

    if args.check:
        if not out.exists():
            print(f"[FAIL] 缺少權重檔：{out}", file=sys.stderr)
            return 1
        raw = out.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"):
            print(f"[FAIL] {out} 有 UTF-8 BOM（會讓第一行 # 註解被當成資料列）", file=sys.stderr)
            return 1
        actual = raw.decode("utf-8").replace("\r\n", "\n")
        if actual != expected:
            print(f"[FAIL] {out} 與 bootstrap 定義不同步，請重跑本工具：{sys.argv[0]}", file=sys.stderr)
            return 1
        print(f"[OK] {out} 與 bootstrap 定義一致（{len(META) + len(WEIGHTS)} 列）")
        return 0

    out.parent.mkdir(parents=True, exist_ok=True)
    # newline="\n" 明確鎖 LF，encoding="utf-8" 不寫 BOM
    out.write_text(expected, encoding="utf-8", newline="\n")
    print(f"[OK] 已寫出 {out}（{len(META) + len(WEIGHTS)} 列，UTF-8 無 BOM）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
