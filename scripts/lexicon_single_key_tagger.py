#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
單鍵標註工具 (Command Lexicon Single-Key Tagger)
------------------------------------------------
依照《指令詞典打標籤計劃書》設計的電腦端免打字標註工具：
- 畫面一次顯示一個詞和它的建議代碼，例如：「點開   建議：c (點擊)」
- [Enter]      : 接受建議代碼並存檔
- [單字母鍵]   : 改成該代碼 (c/t/s/b/r/w/l/d/o/x/g/p, 1/2, B/I/A/P, e/z/?)
- [依序按字母] : 組合多意思優先順序 (例如按 o 再按 > 再按 w 再按 > 再按 c -> o>w>c)
- [+ 加字母]   : 為當前意思加上條件 (+A 後接App / +W 後接開關 / +Q 後接搜尋詞 / +S 畫面有同名按鈕)
- [z]          : 標為雜訊 (不使用)
- [?]          : 標為待觀察
- [u]          : 回到上一個詞重新標註 (Undo)
- [Space]      : 跳過此詞
- [q]          : 儲存並離開

使用方式：
  python scripts/lexicon_single_key_tagger.py                # 互動式單鍵標註 L2/L3 候選詞
  python scripts/lexicon_single_key_tagger.py --audit        # 輸出品質檢查與抽樣覆核報告
"""

import os
import sys

LEXICON_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "open_source_ai_databases", "command_lexicon.tsv"
)

TAG_NAMES = {
    "c": "點擊 (TAP)",
    "t": "輸入文字 (INPUT)",
    "s": "搜尋 (SEARCH)",
    "b": "返回 (BACK)",
    "r": "捲動／滑動 (SCROLL)",
    "w": "開關切換 (TOGGLE)",
    "l": "長按 (LONG_PRESS)",
    "d": "雙擊 (DOUBLE_TAP)",
    "o": "開啟 App (OPEN_APP)",
    "x": "擷取資訊 (EXTRACT)",
    "g": "傳送／分享 (SHARE)",
    "p": "播放／暫停 (PLAY)",
    "1": "依序接下一步 (SEQ)",
    "2": "條件／結果 (COND)",
    "B": "按鈕 (BUTTON)",
    "I": "輸入框 (INPUT_BOX)",
    "A": "App 名 (APP)",
    "P": "頁面／區域 (PAGE)",
    "e": "語音錯字變體 (ASR)",
    "z": "不使用／雜訊 (NOISE)",
    "?": "待觀察 (OBSERVE)",
}

CONDITION_NAMES = {
    "+A": "後接App名",
    "+W": "後接開關項",
    "+Q": "後接搜尋詞",
    "+S": "畫面有同名按鈕",
}


def explain_tag_expr(expr: str) -> str:
    parts = expr.split(">")
    explained = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        base = p[0]
        cond = p[1:] if len(p) > 1 else ""
        base_str = TAG_NAMES.get(base, base)
        cond_str = f"[{CONDITION_NAMES.get(cond, cond)}]" if cond else ""
        explained.append(f"{base_str}{cond_str}")
    return " > ".join(explained)


def load_lexicon(path: str):
    headers = []
    rows = []
    if not os.path.exists(path):
        return headers, rows
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            raw = line.rstrip("\r\n")
            if not raw:
                continue
            if raw.startswith("#"):
                headers.append(raw)
                continue
            cols = raw.split("\t")
            while len(cols) < 5:
                cols.append("")
            rows.append(cols[:5])
    return headers, rows


def save_lexicon(path: str, headers, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for h in headers:
            f.write(h + "\n")
        for r in rows:
            f.write("\t".join(r[:5]) + "\n")


def get_single_char():
    try:
        import msvcrt
        ch = msvcrt.getwch()
        return ch
    except ImportError:
        import tty
        import termios
        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            return sys.stdin.read(1)
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)


def run_quality_audit(rows):
    total = len(rows)
    by_source = {"A": 0, "S": 0, "M": 0}
    multi_sense = 0
    conditional = 0
    for term, expr, src, conf, meta in rows:
        by_source[src] = by_source.get(src, 0) + 1
        if ">" in expr:
            multi_sense += 1
        if "+" in expr:
            conditional += 1
    print("=" * 64)
    print("【指令詞典品質檢查報告 (Command Lexicon Quality Audit)】")
    print(f"總詞條數       : {total}")
    print(f"L1 自動 (A)    : {by_source.get('A', 0)}")
    print(f"L2 半自動 (S)  : {by_source.get('S', 0)}")
    print(f"L3 人工精標 (M): {by_source.get('M', 0)}")
    print(f"多意思詞條 (>) : {multi_sense}")
    print(f"帶條件詞條 (+) : {conditional}")
    print("=" * 64)


def interactive_loop():
    headers, rows = load_lexicon(LEXICON_PATH)
    if "--audit" in sys.argv:
        run_quality_audit(rows)
        return

    print("=" * 68)
    print("  指令詞典單鍵標註工具 (免打字・按一個鍵即存)")
    print("  [Enter]=接受建議  [c/t/s/b/r/w/l/d/o/x/g/p/1/2/B/I/A/P]=改代碼")
    print("  [>]=新增次要意思  [+A/+W/+Q/+S]=加條件  [z]=雜訊  [?]=待觀察")
    print("  [u]=回上一個      [Space]=跳過          [q]=儲存並離開")
    print("=" * 68)

    # Filter candidates that are Semi-Auto (S) or Pending (?)
    target_indices = [i for i, r in enumerate(rows) if r[2] == "S" or "?" in r[1]]
    if not target_indices:
        target_indices = list(range(len(rows)))

    pos = 0
    history = []
    modified_count = 0

    while 0 <= pos < len(target_indices):
        idx = target_indices[pos]
        term, suggested_expr, src, conf, meta = rows[idx]
        print(f"\n[{pos + 1}/{len(target_indices)}] 詞條：「{term}」  建議：{suggested_expr}  ({explain_tag_expr(suggested_expr)})  [來源:{src} 信心:{conf}]")
        print("請按鍵 (Enter=接受 / 字母=改標 / u=回上一個 / Space=跳過 / q=存檔離開): ", end="", flush=True)

        building_expr = ""
        while True:
            ch = get_single_char()
            if ch in ("\r", "\n"):
                final_expr = building_expr if building_expr else suggested_expr
                history.append((idx, list(rows[idx])))
                rows[idx][1] = final_expr
                rows[idx][2] = "M"
                rows[idx][3] = "0.95" if final_expr != "?" else "0.50"
                modified_count += 1
                print(f" -> 已儲存: {term}\t{final_expr}\tM")
                pos += 1
                break
            elif ch == " ":
                print(" -> [跳過]")
                pos += 1
                break
            elif ch == "u":
                if history:
                    prev_idx, prev_row = history.pop()
                    rows[prev_idx] = prev_row
                    pos = max(0, pos - 1)
                    print(" -> [已復原上一個詞]")
                else:
                    print(" -> [無上一個紀錄]")
                break
            elif ch == "q" or ch == "\x03":
                save_lexicon(LEXICON_PATH, headers, rows)
                print(f"\n已儲存 {modified_count} 筆標註至 {LEXICON_PATH}")
                return
            elif ch == "+":
                print("+", end="", flush=True)
                cond_ch = get_single_char().upper()
                if f"+{cond_ch}" in CONDITION_NAMES:
                    building_expr = (building_expr or suggested_expr) + f"+{cond_ch}"
                    print(f"{cond_ch} (目前: {building_expr})", end=" ", flush=True)
            elif ch == ">":
                building_expr = (building_expr or suggested_expr) + ">"
                print(">", end="", flush=True)
            elif ch == "1":
                # Could be connector '1' or prefix to stack another code
                if building_expr and not building_expr.endswith(">"):
                    building_expr += ">"
                else:
                    building_expr += "1"
                print(building_expr, end=" ", flush=True)
            elif ch in TAG_NAMES:
                if building_expr and not building_expr.endswith(">"):
                    building_expr += ">" + ch
                else:
                    building_expr += ch
                print(f"{ch} (目前: {building_expr})", end=" ", flush=True)

    save_lexicon(LEXICON_PATH, headers, rows)
    print(f"\n標註完成！已儲存 {modified_count} 筆更新至 {LEXICON_PATH}")


if __name__ == "__main__":
    interactive_loop()
