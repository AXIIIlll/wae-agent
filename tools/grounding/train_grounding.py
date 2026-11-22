#!/usr/bin/env python3
"""Grounding 2.0 裝置端自動標註飛輪（I5）的離線訓練器 — Phase 3 / G2。

輸入：App debug 版寫出的 `filesDir/grounding_samples.jsonl`（由
`OutcomeLabeler.record` 產生），一行一個樣本：

  {"features":{"flag:isClickable":1.000,"num:textSim":0.830},"label":1,"reason":"screen_changed"}

輸出：
  `ui_grounding_weights.tsv`  App 端 **可以直接載入** 的權重檔，格式與
                              `ui_decision_head.tsv` 相同：
                                meta<TAB>key<TAB>value
                                model<TAB>feature<TAB>weight
  `out/report.md`             樣本數 / 正負比 / holdout 準確率 / 權重表 / 已知邊界

用法：
  # 1) 真機樣本訓練（先 adb run-as 把 JSONL 拉到 E 槽，見 README.md）
  python train_grounding.py --input E:/WAE/datasets/grounding_samples.jsonl

  # 2) 沒有真機樣本時，用合成資料做冒煙測試
  python train_grounding.py --synthesize 400

注意：不引入新依賴（numpy 由 DSH 內建 Python 提供）。全程離線，不上傳任何資料。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys

try:
    import numpy as np
except ImportError:  # pragma: no cover - DSH bundled Python 一定內建 numpy
    print("需要 numpy（DSH bundled Python 已內建）", file=sys.stderr)
    raise SystemExit(2)


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(SCRIPT_DIR, "ui_grounding_weights.tsv")
DEFAULT_REPORT = os.path.join(SCRIPT_DIR, "out", "report.md")

# ---------------------------------------------------------------------------
# 合成樣本（只給 --synthesize 冒煙測試用，不是正式權重）
#
# 特徵命名約定（與 App 端抽取器對齊；訓練器對「沒見過的特徵」自動忽略）：
#   flag:*  0/1 的布林訊號
#   num:*   0~1 的連續分數
# 這裡的「真值權重」模擬一個理想 grounding 打分器，只用來驗證訓練管線能收斂。
# ---------------------------------------------------------------------------
SYNTH_TRUTH = {
    # 候選品質（正向）
    "flag:isClickable": 0.9,
    "flag:textExact": 1.3,
    "flag:isEditable": 0.8,
    "flag:onScreen": 0.6,
    "flag:labelOwned": 0.7,
    "num:textSim": 1.6,
    "num:ocrConf": 0.5,
    # 干擾（負向）
    "num:centerDist": -0.9,
    "num:area": -0.25,
    "num:depth": -0.35,
    "num:redundancy": -0.6,
}
SYNTH_FEATURES = sorted(SYNTH_TRUTH)
# 負向偏移：讓合成資料的正負比接近真機（多數動作成功，但仍有明顯負樣本）
SYNTH_BIAS = -2.1
REASON_POSITIVE = ("screen_changed", "postcondition")
REASON_NEGATIVE = "no_change"


def load_jsonl(path: str) -> tuple[list[dict], list[float], list[str]]:
    """讀 OutcomeLabeler 寫出的 JSONL；壞行直接跳過（飛輪資料不應因一行壞掉全滅）。"""
    rows: list[dict] = []
    labels: list[float] = []
    reasons: list[str] = []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                print(f"跳過第 {lineno} 行（不是合法 JSON）", file=sys.stderr)
                continue
            feats = obj.get("features")
            if not isinstance(feats, dict) or not feats:
                continue
            clean: dict[str, float] = {}
            for k, v in feats.items():
                try:
                    fv = float(v)
                except (TypeError, ValueError):
                    continue
                if fv != fv or fv in (float("inf"), float("-inf")):  # NaN/Inf
                    continue
                clean[str(k)] = fv
            if not clean:
                continue
            rows.append(clean)
            labels.append(1.0 if float(obj.get("label", 0)) >= 0.5 else 0.0)
            reasons.append(str(obj.get("reason", "unknown")))
    return rows, labels, reasons


def synthesize(n: int, seed: int = 7, noise: float = 0.35) -> tuple[list[dict], list[float], list[str]]:
    """產生 n 個合成的 grounding 樣本（僅供無真機資料時的管線冒煙測試）。"""
    rng = random.Random(seed)
    flag_prob = {
        "flag:isClickable": 0.70,
        "flag:textExact": 0.30,
        "flag:isEditable": 0.15,
        "flag:onScreen": 0.95,
        "flag:labelOwned": 0.60,
    }
    num_ranges = {
        "num:textSim": (0.0, 1.0),
        "num:ocrConf": (0.0, 1.0),
        "num:centerDist": (0.0, 1.0),
        "num:area": (0.0, 0.6),
        "num:depth": (0.0, 1.0),
        "num:redundancy": (0.0, 0.5),
    }
    rows: list[dict] = []
    labels: list[float] = []
    reasons: list[str] = []
    for _ in range(n):
        feats: dict[str, float] = {}
        for name in SYNTH_FEATURES:
            if name in flag_prob:
                feats[name] = 1.0 if rng.random() < flag_prob[name] else 0.0
            else:
                lo, hi = num_ranges[name]
                feats[name] = round(lo + rng.random() * (hi - lo), 3)
        logit = SYNTH_BIAS + sum(SYNTH_TRUTH[k] * v for k, v in feats.items())
        logit += rng.gauss(0.0, noise)  # 弱標籤噪聲：畫面變化 ≠ 一定是對的元素
        label = 1.0 if logit > 0.0 else 0.0
        if label > 0.5:
            reason = rng.choice(REASON_POSITIVE)
        else:
            reason = REASON_NEGATIVE
        rows.append(feats)
        labels.append(label)
        reasons.append(reason)
    return rows, labels, reasons


def build_matrix(rows: list[dict]) -> tuple[list[str], np.ndarray]:
    """把 list[features] 轉成固定欄序的特徵矩陣（欄名排序，輸出穩定）。"""
    names = sorted({k for r in rows for k in r})
    index = {name: i for i, name in enumerate(names)}
    X = np.zeros((len(rows), len(names)), dtype=np.float64)
    for i, r in enumerate(rows):
        for k, v in r.items():
            j = index.get(k)
            if j is not None:
                X[i, j] = v
    return names, X


def _sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30.0, 30.0)))


def fit_logistic(
    X: np.ndarray,
    y: np.ndarray,
    l2: float = 0.05,
    lr: float = 0.5,
    steps: int = 3000,
) -> tuple[np.ndarray, float]:
    """Ridge logistic regression（特徵先標準化，全批次梯度下降）。

    App 端要用「原始尺度」的權重（直接 dot 特徵），所以訓練完會把
    標準化空間的 w 轉回原始空間：w_raw = w/std、b_raw = b - Σ(w*mean/std)。
    """
    n, d = X.shape
    mu = X.mean(axis=0)
    sd = X.std(axis=0)
    sd = np.where(sd < 1e-6, 1.0, sd)
    Z = (X - mu) / sd
    w = np.zeros(d)
    b = 0.0
    for _ in range(steps):
        p = _sigmoid(Z @ w + b)
        w -= lr * (Z.T @ (p - y) / n + l2 * w)
        b -= lr * float(np.mean(p - y))
    w_raw = w / sd
    b_raw = b - float(np.dot(w, mu / sd))
    return w_raw, b_raw


def predict_proba(X: np.ndarray, w: np.ndarray, b: float) -> np.ndarray:
    return _sigmoid(X @ w + b)


def evaluate(
    X_tr: np.ndarray, y_tr: np.ndarray, X_te: np.ndarray, y_te: np.ndarray,
    l2: float, lr: float, steps: int,
) -> dict:
    """在 train 上擬合、在 holdout 上量測（回傳可直接寫進報告的數字）。"""
    w, b = fit_logistic(X_tr, y_tr, l2=l2, lr=lr, steps=steps)
    tr_pred = (predict_proba(X_tr, w, b) > 0.5).astype(float)
    te_prob = predict_proba(X_te, w, b)
    te_pred = (te_prob > 0.5).astype(float)
    accuracy = float(np.mean(te_pred == y_te))
    base = float(max(np.mean(y_te), 1.0 - np.mean(y_te)))
    tp = float(np.sum((te_pred == 1.0) & (y_te == 1.0)))
    fp = float(np.sum((te_pred == 1.0) & (y_te == 0.0)))
    fn = float(np.sum((te_pred == 0.0) & (y_te == 1.0)))
    precision = tp / (tp + fp) if (tp + fp) > 0 else float("nan")
    recall = tp / (tp + fn) if (tp + fn) > 0 else float("nan")
    return {
        "holdout_n": int(len(y_te)),
        "holdout_accuracy": round(accuracy, 4),
        "holdout_majority_baseline": round(base, 4),
        "holdout_precision": round(precision, 4),
        "holdout_recall": round(recall, 4),
        "train_accuracy": round(float(np.mean(tr_pred == y_tr)), 4),
        "w": w,
        "b": b,
    }


def write_tsv(
    path: str,
    weights: dict[str, float],
    bias: float,
    alpha: float,
    source: str,
    min_weight: float,
) -> None:
    """寫 App 端權重檔（UTF-8 無 BOM、LF；# 開頭為註解，App reader 應跳過）。"""
    lines = [
        "# ui_grounding_weights.tsv — 由 tools/grounding/train_grounding.py 產生，請勿手改",
        "# 格式：model<TAB>feature<TAB>weight；meta 列設定 alpha/bias；# 開頭為註解",
        f"# 來源：{source}",
        f"meta\talpha\t{alpha:.2f}",
        f"meta\tbias\t{bias:.4f}",
    ]
    items = sorted(weights.items(), key=lambda kv: (-abs(kv[1]), kv[0]))
    kept = 0
    for name, w in items:
        if abs(w) < min_weight:
            continue
        # 用 .4f 會產生 -0.0000，統一收斂成 0.0000
        lines.append(f"grounding\t{name}\t{w + 0.0:.4f}")
        kept += 1
    if kept == 0:
        lines.append("# 警告：所有權重都被 min_weight 濾掉，模型等同只看 bias")
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def write_report(
    path: str,
    info: dict,
    weights: dict[str, float],
    report_data: dict,
) -> None:
    lines = [
        "# Grounding 權重訓練報告",
        "",
        "> 由 `tools/grounding/train_grounding.py` 自動產生，請勿手改。",
        "> 這份報告是**離線訓練**的證據；App 端權重請以同批輸出的 `ui_grounding_weights.tsv` 為準。",
        "",
        "## 資料",
        "",
        f"- 來源：{info['source']}",
        f"- 輸出權重：`{info['out']}`",
        f"- 樣本數：{info['samples']}",
        f"- 特徵數：{info['features']}",
        f"- 正樣本（label=1）：{info['positives']}（{info['positive_rate']:.1%}）",
        f"- 負樣本（label=0）：{info['negatives']}",
        f"- 標註理由分佈：{info['reason_mix']}",
        "",
        "## 模型",
        "",
        "- 模型：ridge logistic regression（特徵標準化 + 全批次梯度下降，純 numpy）",
        f"- 超參數：L2={info['l2']}、lr={info['lr']}、steps={info['steps']}、門檻=0.5（未校準）",
        f"- holdout：{report_data['holdout_n']} 筆（{info['holdout']:.0%}）",
        f"- **holdout 準確率：{report_data['holdout_accuracy']:.4f}**",
        f"- 多數類別基準：{report_data['holdout_majority_baseline']:.4f}",
        f"- holdout precision（正類）：{report_data['holdout_precision']:.4f}",
        f"- holdout recall（正類）：{report_data['holdout_recall']:.4f}",
        f"- 訓練集準確率：{report_data['train_accuracy']:.4f}",
        f"- bias：{report_data['b']:.4f}",
        "",
        "## 權重（依 |weight| 排序）",
        "",
        "| feature | weight |",
        "|---|---|",
    ]
    for name, w in sorted(weights.items(), key=lambda kv: (-abs(kv[1]), kv[0])):
        lines.append(f"| {name} | {w:.4f} |")
    lines += [
        "",
        "## 已知邊界",
        "",
        "- **弱標籤有噪聲**：`screen_changed` 只代表畫面有變化，變化可能來自動畫、廣告、"
        "系統 toast，不保證點到的是「正確」元素；`no_change` 也可能是本來就不需要變化的"
        "成功動作（例如已勾選的開關再點一次）。因此本模型學的是「動作是否帶來可觀測反應」"
        "的代理訊號，不等於 Top-1 定位正確率。",
        "- **資料分佈偏斜**：真機上多數步驟會成功，正樣本通常較多；負樣本不足時 recall 會"
        "被高估。若 `positive_rate` 偏離 50% 太多，請先補負樣本或等 G4 的驗證式迴圈產生"
        "更多明確負例。",
        "- **合成資料不代表真機**：`--synthesize` 只驗證訓練管線可以收斂；用合成資料產生的"
        "權重**不可**直接上線，正式權重必須來自真機 JSONL。",
        "- **未校準**：門檻固定 0.5，沒有 Platt/isotonic 校準，機率不可直接當「信心」使用；"
        "信心校準是 Lead 的 Phase 2 工作。",
        "- **特徵字典不固定**：TSV 只含本次訓練看過的特徵；App 端若抽到 TSV 沒有的特徵，"
        "必須忽略（權重 0），不能假設兩邊欄位一致。",
        "- **不進 APK、不上傳**：樣本與權重只存在 E 槽 / 裝置私有目錄。",
        "",
        "## 怎麼量 / 下一步",
        "",
        "1. 這份報告的 holdout 準確率是「離線代理指標」；真機要看 G3 整合後的"
        "「Top-1 定位正確率 / 錯誤點擊率」A/B（見 `docs/WAE_Grounding2_Plan.md` 驗收指標）。",
        "2. 換權重後先跑一輪 300 步重播基準（G0 的 replay）確認沒退步，再上真機。",
        "3. 真機樣本累積到至少數百筆且覆蓋多個 App 後再重訓；每次重訓保留上一版 TSV 方便回滾。",
        "",
    ]
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description="Grounding 飛輪離線訓練器（ridge logistic）")
    ap.add_argument("--input", help="grounding_samples.jsonl（OutcomeLabeler 寫出的檔）")
    ap.add_argument("--out", default=DEFAULT_OUT, help="輸出權重 TSV")
    ap.add_argument("--report", default=DEFAULT_REPORT, help="輸出報告 md；給空字串可關閉")
    ap.add_argument("--synthesize", type=int, default=0, help="合成樣本數（冒煙測試）")
    ap.add_argument("--alpha", type=float, default=1.00, help="寫進 meta 的融合權重倍率")
    ap.add_argument("--l2", type=float, default=0.05, help="L2 正則化係數")
    ap.add_argument("--lr", type=float, default=0.5, help="梯度下降學習率")
    ap.add_argument("--steps", type=int, default=3000, help="梯度下降步數")
    ap.add_argument("--holdout", type=float, default=0.2, help="holdout 比例（0~0.5）")
    ap.add_argument("--seed", type=int, default=13, help="holdout 切分種子")
    ap.add_argument("--min-weight", type=float, default=1e-4, help="輸出時過濾太小的權重")
    args = ap.parse_args()

    if args.synthesize > 0:
        rows, labels, reasons = synthesize(args.synthesize)
        source = f"synthetic({args.synthesize})"
    elif args.input:
        rows, labels, reasons = load_jsonl(args.input)
        source = args.input
    else:
        print("請給 --input 或 --synthesize", file=sys.stderr)
        return 2

    if not rows:
        print("沒有可用樣本（features 全空或檔案不存在）", file=sys.stderr)
        return 2

    y = np.asarray(labels, dtype=np.float64)
    positives = int(np.sum(y == 1.0))
    negatives = int(len(y) - positives)
    if positives == 0 or negatives == 0:
        print("樣本只有單一類別，無法訓練 logistic（至少要有正負各 1 筆）", file=sys.stderr)
        return 2

    names, X = build_matrix(rows)

    # holdout 評估（資料少時誠實回報 nan，不要假裝有量到）
    rng = np.random.default_rng(args.seed)
    idx = rng.permutation(len(y))
    cut = int(len(y) * (1.0 - max(0.0, min(0.5, args.holdout))))
    if cut >= 20 and len(y) - cut >= 5:
        eval_result = evaluate(
            X[idx[:cut]], y[idx[:cut]], X[idx[cut:]], y[idx[cut:]],
            l2=args.l2, lr=args.lr, steps=args.steps,
        )
    else:
        eval_result = {
            "holdout_n": 0,
            "holdout_accuracy": float("nan"),
            "holdout_majority_baseline": float("nan"),
            "holdout_precision": float("nan"),
            "holdout_recall": float("nan"),
            "train_accuracy": float("nan"),
            "w": np.zeros(len(names)),
            "b": 0.0,
        }
    # 正式權重用全部資料重訓（holdout 只用來量測，不參與輸出）
    w, b = fit_logistic(X, y, l2=args.l2, lr=args.lr, steps=args.steps)
    weights = {name: float(w[i]) for i, name in enumerate(names)}
    # 報告要跟 TSV 對得上：bias / 訓練集準確率改用「最終權重」的數字
    eval_result["b"] = float(b)
    eval_result["train_accuracy"] = round(
        float(np.mean((predict_proba(X, w, b) > 0.5).astype(float) == y)), 4
    )

    reason_counts: dict[str, int] = {}
    for r in reasons:
        reason_counts[r] = reason_counts.get(r, 0) + 1
    reason_mix = "、".join(f"{k}={v}" for k, v in sorted(reason_counts.items(), key=lambda kv: -kv[1]))

    info = {
        "source": source,
        "samples": len(rows),
        "features": len(names),
        "positives": positives,
        "negatives": negatives,
        "positive_rate": positives / len(rows),
        "reason_mix": reason_mix,
        "l2": args.l2,
        "lr": args.lr,
        "steps": args.steps,
        "holdout": args.holdout,
        "out": args.out,
    }
    write_tsv(args.out, weights, b, args.alpha, source, args.min_weight)
    if args.report:
        write_report(args.report, info, weights, eval_result)

    summary = {
        "來源": source,
        "樣本數": len(rows),
        "特徵數": len(names),
        "正/負": f"{positives}/{negatives}",
        "holdout 準確率": eval_result["holdout_accuracy"],
        "holdout 基準": eval_result["holdout_majority_baseline"],
        "輸出": args.out,
        "報告": args.report or "(略過)",
    }
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
