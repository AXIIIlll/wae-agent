# -*- coding: utf-8 -*-
"""M3：訓練決策 reranker（特徵式 logistic regression）並匯出權重。

輸入：collect_ui_samples.py 產生的 JSONL（target, candidate, label, flags）
輸出：
  - app/src/main/assets/agent/reranker_linear.json（Kotlin 讀取）
  - E:\\WAE\\reports\\reranker_report.txt
"""
import argparse, json, math, os, io
from collections import Counter

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, roc_auc_score
from sklearn.model_selection import train_test_split

ROOT = r"E:\NewAndroidApp"
OUT_ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "agent", "reranker_linear.json")
OUT_REPORT = r"E:\WAE\reports\reranker_report.txt"

FEATURES = [
    "exact", "contains", "char_overlap", "len_ratio",
    "bigram_jaccard", "clickable", "editable", "is_row", "is_edit",
]

def bigrams(s):
    s = "".join(s.split())
    return {s[i:i + 2] for i in range(max(0, len(s) - 1))}

def featurize(target, cand, clickable, editable, cls):
    t = "".join(target.lower().split())
    c = "".join(cand.lower().split())
    exact = 1.0 if t == c else 0.0
    contains = 1.0 if (t and (t in c or c in t)) else 0.0
    overlap = 0.0
    if t:
        overlap = sum(1 for ch in t if ch in c) / len(t)
    len_ratio = min(len(t), len(c)) / max(1, max(len(t), len(c)))
    bt, bc = bigrams(t), bigrams(c)
    jac = len(bt & bc) / max(1, len(bt | bc))
    return [
        exact, contains, overlap, len_ratio, jac,
        1.0 if clickable else 0.0,
        1.0 if editable else 0.0,
        1.0 if "row" in (cls or "").lower() else 0.0,
        1.0 if ("edit" in (cls or "").lower()) else 0.0,
    ]

def load(path):
    X, y, groups = [], [], []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            X.append(featurize(r["target"], r["candidate"], r.get("clickable", False),
                               r.get("editable", False), r.get("cls", "")))
            y.append(int(r["label"]))
            groups.append(r.get("screen", ""))
    return np.array(X), np.array(y), groups

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=r"E:\WAE\datasets\reranker_ui.jsonl")
    ap.add_argument("--min-pairs", type=int, default=200)
    args = ap.parse_args()

    X, y, groups = load(args.data)
    if len(X) < args.min_pairs:
        raise SystemExit(f"資料太少（{len(X)} pairs），先跑 collect_ui_samples.py")
    Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)
    clf = LogisticRegression(max_iter=1000, class_weight="balanced")
    clf.fit(Xtr, ytr)
    proba = clf.predict_proba(Xte)[:, 1]
    acc = accuracy_score(yte, (proba >= 0.5).astype(int))
    auc = roc_auc_score(yte, proba)

    weights = {name: float(w) for name, w in zip(FEATURES, clf.coef_[0])}
    out = {
        "version": 1,
        "type": "logistic",
        "features": FEATURES,
        "weights": weights,
        "bias": float(clf.intercept_[0]),
        "trainedPairs": int(len(X)),
        "valAccuracy": float(acc),
        "valAuc": float(auc),
    }
    os.makedirs(os.path.dirname(OUT_ASSET), exist_ok=True)
    io.open(OUT_ASSET, "w", encoding="utf-8", newline="\n").write(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    os.makedirs(os.path.dirname(OUT_REPORT), exist_ok=True)
    report = [
        f"pairs={len(X)} positives={int(y.sum())} negatives={len(y)-int(y.sum())}",
        f"val accuracy={acc:.3f} auc={auc:.3f}",
        "weights=" + json.dumps(weights, indent=2),
    ]
    io.open(OUT_REPORT, "w", encoding="utf-8", newline="\n").write("\n".join(report) + "\n")
    print("\n".join(report))
    print("asset ->", OUT_ASSET)

if __name__ == "__main__":
    main()
