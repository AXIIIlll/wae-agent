"""CMGUI role-plan → WAE planner 的訓練格式（goal + plan JSON）。

輸出與 `tools/plan_sft/train_lora.py` 完全相容：
  {"goal","selected_app","installed_apps","plan","source"}

plan 的 target 用 `$role:<角色>`，執行期由無障礙樹解析成真正元素
（角色詞彙見 docs/WAE_OpenEnded_Intent.md）。

用法：
  python tools/cmgui/to_plan_jsonl.py --input E:/WAE/datasets/cmgui_plans/train.jsonl --out E:/WAE/datasets/cmgui_plans/plan_rows.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
import random

ROLE_DO = {
    "input": "點擊輸入框",
    "first_result": "點選第一個結果",
    "next": "點選項目",
    "cart": "加入購物車",
    "buy": "購買",
    "navigate": "開始導航",
    "play": "播放",
    "submit": "送出",
    "confirm": "確認",
    "follow": "追蹤",
    "like": "按讚",
    "comment": "留言",
}


def step(i: int, do: str, target: str, verify: str) -> dict:
    return {"id": i, "do": do, "target": target, "verify": verify}


def convert(rec: dict, installed_extra: list[str]) -> dict | None:
    instruction = rec.get("instruction", "").strip()
    app = rec.get("app", "").strip()
    raw = rec.get("plan") or []
    if not instruction or not app or len(raw) < 2:
        return None

    steps = []
    idx = 0
    for st in raw:
        op = st.get("op")
        target = st.get("target", "")
        verify = st.get("verify", "完成")
        idx += 1
        if op == "OPEN_APP":
            steps.append(step(idx, f"開啟{app}", f"$app:{app}", app))
        elif op == "TYPE":
            text = st.get("text", "")
            # AgentAction.TYPE 會把 target 當成「要打進去的字」，所以 target 必須是 payload，
            # 不是 $role:input（聚焦由前一步 TAP($role:input) 負責）。
            steps.append(step(idx, f"輸入{text}", text, "輸入框"))
        elif op == "TAP":
            role = target.replace("$role:", "") if target.startswith("$role:") else "next"
            steps.append(step(idx, ROLE_DO.get(role, "點選項目"), f"$role:{role}", verify))
        elif op == "SCROLL_DOWN":
            steps.append(step(idx, "往下滑", "$role:list", "列表"))
        elif op == "BACK":
            steps.append(step(idx, "返回", "", "上一頁"))
        elif op == "HOME":
            steps.append(step(idx, "回到桌面", "", "桌面"))
        elif op == "LONG_PRESS":
            steps.append(step(idx, "長按項目", "$role:next", "選單"))
        else:
            idx -= 1

    if len(steps) < 2:
        return None

    plan = {
        "version": 1,
        "goalKind": "open_ended",
        "summary": instruction[:40],
        "steps": steps,
    }
    # 已安裝清單：一定要有目標 App，其餘用常見 App 補滿（訓練時學會從清單挑）
    apps = [app] + [a for a in installed_extra if a != app]
    random.shuffle(apps)
    return {
        "goal": instruction,
        "selected_app": app,
        "installed_apps": apps[:28],
        "plan": plan,
        "source": "cmgui",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=r"E:/WAE/datasets/cmgui_plans/train.jsonl")
    ap.add_argument("--out", default=r"E:/WAE/datasets/cmgui_plans/plan_rows.jsonl")
    ap.add_argument("--apps", default=r"E:/NewAndroidApp/app/src/main/assets/open_source_ai_databases/global_tw_cn_app_metadata.tsv")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    pool = []
    with open(args.apps, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) > 1 and p[1].strip():
                pool.append(p[1].strip())

    kept = 0
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as out:
        with open(args.input, "r", encoding="utf-8") as f:
            for line in f:
                if args.limit and kept >= args.limit:
                    break
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                extra = rng.sample(pool, min(27, len(pool)))
                row = convert(rec, extra)
                if row is None:
                    continue
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                kept += 1
    print(f"rows={kept} -> {args.out}")


if __name__ == "__main__":
    main()
