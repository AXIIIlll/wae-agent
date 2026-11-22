"""CMGUI（阿里中文手機 GUI 軌跡）→ WAE role-plan 訓練資料。

為什麼不用截圖：
  CMGUI 的截圖是 35 個 4.9GB 的壓縮檔（約 170GB），下載與 OCR 都不現實。
  但**動作序列本身**（CLICK/TYPE/SWIPE/BACK/STOP）＋ instruction 已經足以學到
  「一個任務長什麼形狀」與「參數是什麼」——缺少的只有「點哪個元素」。

  所以每個 CLICK 映射成**角色**（$role:input / $role:first_result / $role:cart …），
  執行期再由無障礙樹把角色解析成真正的元素。

角色推導原則（v2，順序優先、動詞只給最後一步）：
  1. 第一個 CLICK            → OPEN_APP(app)
  2. TYPE 前那個 CLICK       → $role:input（聚焦輸入框）
  3. TYPE 後第一個 CLICK     → $role:first_result（送出搜尋/選第一筆）
  4. 中間其餘 CLICK          → $role:next（折疊成一個）
  5. 最後一個 CLICK          → instruction 的最後一個動詞角色（購物車/導航/播放…）
  6. SWIPE/BACK/HOME/LONG_PRESS 照原動作映射

用法：
  python tools/cmgui/convert_cmgui.py --input E:/WAE/datasets/cmgui/train.jsonl --out E:/WAE/datasets/cmgui_plans
"""
from __future__ import annotations

import argparse
import json
import os
import re
from collections import Counter

# instruction 動詞 → 角色（長詞優先；只用於「最後一步」）
VERB_ROLES = [
    ("加入购物车", "cart"), ("加入購物車", "cart"),
    ("立即购买", "buy"), ("购买", "buy"), ("下单", "buy"), ("付款", "buy"),
    ("发起导航", "navigate"), ("开始导航", "navigate"), ("导航", "navigate"),
    ("播放", "play"), ("观看", "play"), ("收听", "play"),
    ("发送", "submit"), ("提交", "submit"), ("确认", "confirm"), ("确定", "confirm"),
    ("关注", "follow"), ("点赞", "like"), ("评论", "comment"),
]

SEARCH_HINT = re.compile(r"(搜索|搜一下|查找|查一下|输入|輸入|搜)")

ROLE_VERIFY = {
    "input": "輸入框",
    "first_result": "搜尋結果",
    "next": "詳情",
    "cart": "購物車",
    "buy": "訂單",
    "navigate": "導航",
    "play": "暫停",
    "submit": "已送出",
    "confirm": "完成",
    "follow": "已關注",
    "like": "已按讚",
    "comment": "留言",
}


def final_verb(instruction: str) -> str | None:
    """取 instruction 裡**最後出現**的動詞角色（任務最後一步通常就是它）。"""
    best_pos, best_role = -1, None
    for word, role in VERB_ROLES:
        pos = instruction.rfind(word)
        if pos > best_pos:
            best_pos, best_role = pos, role
    return best_role


def convert_episode(rec: dict) -> dict | None:
    instruction = (rec.get("instruction") or "").strip()
    app = (rec.get("app") or "").strip()
    steps = [s for s in (rec.get("step_data") or []) if s.get("action") != "STOP"]
    if not instruction or not app or len(steps) < 2:
        return None

    # 先標記結構性角色
    click_idx = [i for i, s in enumerate(steps) if s.get("action") == "CLICK"]
    type_idx = [i for i, s in enumerate(steps) if s.get("action") == "TYPE"]
    if not click_idx:
        return None

    plan: list[dict] = [{"op": "OPEN_APP", "target": app, "verify": app}]
    focus_click = None
    if type_idx:
        before = [i for i in click_idx if i < type_idx[0]]
        focus_click = before[-1] if before else None
    first_result_click = None
    if type_idx:
        after = [i for i in click_idx if i > type_idx[0]]
        first_result_click = after[0] if after else None

    verb = final_verb(instruction)
    last_click = click_idx[-1] if click_idx else None

    used_focus = used_first = False
    for i, s in enumerate(steps):
        action = s.get("action")
        if action == "KEY_HOME":
            plan.append({"op": "HOME", "target": "", "verify": "桌面"})
            continue
        if action == "KEY_BACK":
            plan.append({"op": "BACK", "target": "", "verify": "上一頁"})
            continue
        if action == "SWIPE":
            plan.append({"op": "SCROLL_DOWN", "target": "", "verify": "列表"})
            continue
        if action == "LONG_PRESS":
            plan.append({"op": "LONG_PRESS", "target": "$role:next", "verify": ROLE_VERIFY["next"]})
            continue
        if action == "TYPE":
            text = (s.get("type_text") or "").strip()
            role = "input"
            if SEARCH_HINT.search(instruction):
                role = "input"  # 一律用 input；搜尋框由執行期以 affordance 判斷
            plan.append({"op": "TYPE", "target": f"$role:{role}", "text": text, "verify": ROLE_VERIFY[role]})
            continue
        if action != "CLICK":
            continue

        if i == click_idx[0]:
            continue  # 已由 OPEN_APP 代表
        if focus_click is not None and i == focus_click and not used_focus:
            used_focus = True
            plan.append({"op": "TAP", "target": "$role:input", "verify": ROLE_VERIFY["input"]})
            continue
        if first_result_click is not None and i == first_result_click and not used_first:
            used_first = True
            plan.append({"op": "TAP", "target": "$role:first_result", "verify": ROLE_VERIFY["first_result"]})
            continue
        if verb is not None and i == last_click:
            plan.append({"op": "TAP", "target": f"$role:{verb}", "verify": ROLE_VERIFY.get(verb, "完成")})
            continue
        plan.append({"op": "TAP", "target": "$role:next", "verify": ROLE_VERIFY["next"]})

    plan = postprocess(plan)
    if plan is None:
        return None
    return {"instruction": instruction, "app": app, "plan": plan, "source": "cmgui", "episode_id": rec.get("episode_id")}


def postprocess(plan: list[dict]) -> list[dict] | None:
    """折疊連續的 $role:next、限制長度，並要求至少有 TYPE 或一個語意角色。"""
    out: list[dict] = []
    prev_next = False
    for st in plan:
        is_next = st["target"] == "$role:next"
        if is_next and prev_next:
            continue
        out.append(st)
        prev_next = is_next
    if len(out) > 8 or len(out) < 2:
        return None
    has_type = any(s["op"] == "TYPE" for s in out)
    semantic = [s for s in out if s["target"].startswith("$role:") and s["target"][6:] not in ("next", "input")]
    if not has_type and not semantic:
        return None
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=r"E:/WAE/datasets/cmgui/train.jsonl")
    ap.add_argument("--out", default=r"E:/WAE/datasets/cmgui_plans")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    kept, dropped = 0, 0
    roles = Counter()
    lengths = Counter()
    out_path = os.path.join(args.out, "train.jsonl")
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        with open(args.input, "r", encoding="utf-8") as f:
            for line in f:
                if args.limit and kept + dropped >= args.limit:
                    break
                line = line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                conv = convert_episode(rec)
                if conv is None:
                    dropped += 1
                    continue
                out.write(json.dumps(conv, ensure_ascii=False) + "\n")
                kept += 1
                lengths[len(conv["plan"])] += 1
                for st in conv["plan"]:
                    t = st["target"]
                    if t.startswith("$role:"):
                        roles[t[6:]] += 1
    print(f"kept={kept} dropped={dropped} -> {out_path}")
    print("plan lengths:", dict(sorted(lengths.items())))
    print("roles:", roles.most_common(15))


if __name__ == "__main__":
    main()
