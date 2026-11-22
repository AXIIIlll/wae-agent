#!/usr/bin/env python3
"""
訓練姿態模型（3+1 裡的那個 +1）。

輸入：posture_samples.jsonl（由 App 內的 PostureSampleRecorder 產生，每行含 12 維結構特徵 + 姿態標籤）
輸出：posture_model.json（推到手機 filesDir 即可生效，免重新編譯 APK）

用法：
  # 1) 從手機拉樣本
  adb exec-out run-as com.wae.agent cat files/posture_samples.jsonl > samples.jsonl
  # 2) 訓練
  python tools/train_posture_model.py samples.jsonl posture_model.json
  # 3) 推回手機（免重裝）
  adb push posture_model.json /data/local/tmp/
  adb shell run-as com.wae.agent cp /data/local/tmp/posture_model.json /data/data/com.wae.agent/files/

說明：目前樣本標籤來自啟發式/模型自身（弱標籤），
所以訓練結果本質上是「把教師的行為壓進一個可校準、可持續更新的模型」。
若要真正超越啟發式，需要在樣本裡加 `label` 欄位做人工/結果標註（腳本優先採用 label）。
"""
import json
import sys

import numpy as np

POSTURES = [
    "LIST_FEED", "FORM_INPUT", "MODAL_DIALOG", "BOTTOM_SHEET", "DETAIL_PAGE",
    "NAV_HOME", "SEARCH_RESULTS", "WATERFALL_GRID", "EMPTY_OR_UNKNOWN",
]
DIM = 12


def load_rows(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            feats = o.get("features", "")
            if isinstance(feats, str):
                feats = [float(x) for x in feats.split(",") if x.strip()]
            if len(feats) != DIM:
                continue
            label = o.get("label") or o.get("posture") or ""
            if label not in POSTURES:
                continue
            rows.append((np.array(feats, dtype=np.float64), POSTURES.index(label), o.get("heur", "")))
    return rows


def softmax(z):
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def train(X, y, classes, epochs=800, lr=0.35, l2=1e-3):
    n, d = X.shape
    W = np.zeros((d, classes))
    b = np.zeros(classes)
    Y = np.eye(classes)[y]
    for _ in range(epochs):
        P = softmax(X @ W + b)
        G = (P - Y) / n
        W -= lr * (X.T @ G + l2 * W)
        b -= lr * G.sum(axis=0)
    return W, b


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: train_posture_model.py <samples.jsonl> <out.json>")
        return 2
    rows = load_rows(sys.argv[1])
    if len(rows) < 20:
        print(f"樣本太少（{len(rows)}），至少需要 20 筆；先讓 App 跑一段時間收集")
        return 1

    X = np.stack([r[0] for r in rows])
    y = np.array([r[1] for r in rows])
    classes = len(POSTURES)
    W, b = train(X, y, classes)

    P = softmax(X @ W + b)
    acc = float((P.argmax(axis=1) == y).mean())

    # 與啟發式對照（若樣本有 heur 欄位）
    heur_idx = [POSTURES.index(r[2]) for r in rows if r[2] in POSTURES]
    heur_acc = None
    if heur_idx and len(heur_idx) == len(rows):
        heur_acc = float((np.array(heur_idx) == y).mean())

    out = {
        "dim": DIM,
        "classes": classes,
        "classes_order": POSTURES,
        "weights": W.tolist(),
        "bias": b.tolist(),
        "trained_on": len(rows),
        "train_accuracy": round(acc, 4),
        "heuristic_accuracy": None if heur_acc is None else round(heur_acc, 4),
    }
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    support = {POSTURES[c]: int((y == c).sum()) for c in range(classes) if (y == c).sum() > 0}
    print(json.dumps({
        "samples": len(rows),
        "train_accuracy": round(acc, 4),
        "heuristic_accuracy": None if heur_acc is None else round(heur_acc, 4),
        "class_support": support,
        "out": sys.argv[2],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
