#!/usr/bin/env python3
"""S29 線性決策頭的離線訓練器。

輸入：App 開發版輸出的 `decision_head_samples.jsonl`（每行一個樣本）
  {"model": "candidate"|"edge",
   "features": {"flag:isEditable": 1.0, "num:sim": 0.83, ...},
   "label": 1}                       # 1 = 這是正確動作 / 0 = 不是

輸出：`ui_decision_head.tsv`（App 直接載入的權重檔）
  meta<TAB>alpha_candidate<TAB>0.60
  candidate<TAB>flag:isEditable<TAB>1.234
  ...

用法：
  # 1) 用 JSONL 訓練（ridge logistic regression，純 numpy）
  python train_linear.py --input samples.jsonl --out ui_decision_head.tsv

  # 2) 沒有真機樣本時，產生合成樣本做冒煙測試
  python train_linear.py --synthesize 600 --out /tmp/head.tsv --report /tmp/report.md

注意：不引入新依賴（numpy 已隨 DSH bundled Python 提供）。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys

try:
    import numpy as np
except ImportError:  # pragma: no cover
    print("需要 numpy（DSH bundled Python 已內建）", file=sys.stderr)
    raise SystemExit(2)

# 合成樣本使用的「真值權重」（只用於冒煙測試，不是正式權重）
TRUTH_CANDIDATE = {
    "flag:isEditable": 1.2,
    "flag:hasSearchIdHint": 1.0,
    "flag:isSearchAffordance": 0.6,
    "flag:isClickable": 0.4,
    "flag:isBottomNavigationTab": -1.5,
    "flag:isNavigationRow": -1.2,
    "num:sim": 1.0,
    "num:area": -0.1,
}
TRUTH_EDGE = {
    "num:success": 0.8,
    "num:total": 0.3,
    "num:p95": -0.2,
    "flag:opMatch": 0.4,
    "num:overlap": 0.5,
}

ALL_FEATURES = sorted(set(TRUTH_CANDIDATE) | set(TRUTH_EDGE))


def load_samples(path: str) -> tuple[list[dict], list[float], list[str]]:
    rows, labels, models = [], [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            feats = obj.get("features") or {}
            if not isinstance(feats, dict) or not feats:
                continue
            rows.append({str(k): float(v) for k, v in feats.items()})
            labels.append(1.0 if float(obj.get("label", 0)) >= 0.5 else 0.0)
            models.append(str(obj.get("model", "candidate")))
    return rows, labels, models


def synthesize(n: int, seed: int = 7) -> tuple[list[dict], list[float], list[str]]:
    rng = random.Random(seed)
    rows, labels, models = [], [], []
    for _ in range(n):
        model = "edge" if rng.random() < 0.35 else "candidate"
        truth = TRUTH_EDGE if model == "edge" else TRUTH_CANDIDATE
        feats = {}
        for name in ALL_FEATURES:
            feats[name] = 1.0 if rng.random() < 0.35 else 0.0
        feats["num:sim"] = round(rng.random(), 3)
        feats["num:success"] = round(rng.random(), 3)
        feats["num:total"] = round(rng.random(), 3)
        feats["num:p95"] = round(rng.random(), 3)
        feats["num:overlap"] = rng.choice([0.0, 0.33, 0.66, 1.0])
        feats["num:area"] = round(rng.random() * 0.6, 3)
        logit = sum(truth.get(k, 0.0) * v for k, v in feats.items() if k in truth)
        logit += rng.gauss(0.0, 0.4)  # 標籤噪聲
        rows.append(feats)
        labels.append(1.0 if logit > 0 else 0.0)
        models.append(model)
    return rows, labels, models


def fit_logistic(
    X: np.ndarray, y: np.ndarray, l2: float = 0.05, lr: float = 0.5, steps: int = 4000
) -> np.ndarray:
    """Ridge logistic regression（全批次梯度下降；特徵數小，收斂很快）。"""
    n, d = X.shape
    w = np.zeros(d)
    b = 0.0
    for _ in range(steps):
        z = X @ w + b
        p = 1.0 / (1.0 + np.exp(-z))
        grad_w = (X.T @ (p - y)) / n + l2 * w
        grad_b = float(np.mean(p - y))
        w -= lr * grad_w
        b -= lr * grad_b
    return w


def holdout_accuracy(X: np.ndarray, y: np.ndarray, seed: int = 13) -> float:
    rng = np.random.default_rng(seed)
    idx = rng.permutation(len(y))
    cut = int(len(y) * 0.8)
    if cut < 10 or len(y) - cut < 5:
        return float("nan")
    tr, te = idx[:cut], idx[cut:]
    w = fit_logistic(X[tr], y[tr], steps=2500)
    pred = (X[te] @ w > 0).astype(float)
    return float(np.mean(pred == y[te]))


def write_tsv(
    path: str, weights_by_model: dict[str, dict[str, float]],
    alpha_candidate: float, alpha_edge: float
) -> None:
    lines = [
        "# ui_decision_head.tsv — 由 tools/decision_head/train_linear.py 產生",
        "# 格式：model<TAB>feature<TAB>weight（meta 列設定 alpha）",
        f"meta\talpha_candidate\t{alpha_candidate:.2f}",
        f"meta\talpha_edge\t{alpha_edge:.2f}",
        "meta\tcandidate_bias\t0.00",
        "meta\tedge_bias\t0.00",
    ]
    for model in ("candidate", "edge"):
        items = sorted(
            weights_by_model.get(model, {}).items(), key=lambda kv: (-abs(kv[1]), kv[0])
        )
        for name, w in items:
            lines.append(f"{model}\t{name}\t{w:.4f}")
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def write_report(path: str, info: dict, weights_by_model: dict[str, dict[str, float]]) -> None:
    lines = ["# 決策頭訓練報告", ""]
    for k, v in info.items():
        lines.append(f"- {k}: {v}")
    lines += ["", "## 權重（依絕對值排序）", ""]
    for model in ("candidate", "edge"):
        lines += [f"### {model}", "", "| feature | weight |", "|---|---|"]
        for name, w in sorted(
            weights_by_model.get(model, {}).items(), key=lambda kv: -abs(kv[1])
        ):
            lines.append(f"| {name} | {w:.4f} |")
        lines.append("")
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="decision_head_samples.jsonl")
    ap.add_argument("--out", default="ui_decision_head.tsv")
    ap.add_argument("--report", default="")
    ap.add_argument("--synthesize", type=int, default=0, help="產生合成樣本數（冒煙測試）")
    ap.add_argument("--alpha-candidate", type=float, default=0.60)
    ap.add_argument("--alpha-edge", type=float, default=0.25)
    ap.add_argument("--l2", type=float, default=0.05)
    args = ap.parse_args()

    if args.synthesize > 0:
        rows, labels, models = synthesize(args.synthesize)
        source = f"synthetic({args.synthesize})"
    elif args.input:
        rows, labels, models = load_samples(args.input)
        source = args.input
    else:
        print("請給 --input 或 --synthesize", file=sys.stderr)
        return 2

    if not rows:
        print("沒有可用樣本", file=sys.stderr)
        return 2

    # 特徵字典（固定排序，輸出穩定）
    feature_names = sorted({k for r in rows for k in r})
    index = {name: i for i, name in enumerate(feature_names)}
    X = np.zeros((len(rows), len(feature_names)), dtype=np.float64)
    for i, r in enumerate(rows):
        for k, v in r.items():
            if k in index:
                X[i, index[k]] = v
    y = np.asarray(labels, dtype=np.float64)

    weights_by_model: dict[str, dict[str, float]] = {"candidate": {}, "edge": {}}
    accs = {}
    for model in ("candidate", "edge"):
        sel = [i for i, m in enumerate(models) if m == model]
        if len(sel) < 5:
            continue
        Xm, ym = X[sel], y[sel]
        w = fit_logistic(Xm, ym, l2=args.l2)
        weights_by_model[model] = {
            name: float(w[index[name]])
            for name in feature_names
            if index[name] < len(w) and abs(w[index[name]]) >= 1e-3
        }
        accs[model] = round(holdout_accuracy(Xm, ym), 3)
        # 只保留「該模型實際用到的特徵」，避免 candidate/edge 互相污染
        used = {name for name, v in weights_by_model[model].items() if abs(v) >= 1e-3}
        weights_by_model[model] = {k: v for k, v in weights_by_model[model].items() if k in used}

    write_tsv(args.out, weights_by_model, args.alpha_candidate, args.alpha_edge)
    info = {
        "來源": source,
        "樣本數": len(rows),
        "特徵數": len(feature_names),
        "candidate 權重數": len(weights_by_model["candidate"]),
        "edge 權重數": len(weights_by_model["edge"]),
        "holdout 準確率": accs,
        "輸出": args.out,
    }
    print(json.dumps(info, ensure_ascii=False, indent=1))
    if args.report:
        write_report(args.report, info, weights_by_model)
        print(f"報告：{args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
