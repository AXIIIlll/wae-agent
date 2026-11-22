"""S0 spike：單步決策到底需不需要小模型？

做法（完全離線、不碰裝置）：
  1. 從成功收據抽樣本：每筆收據有「效果」（例 `NAVIGATE:app:設定`）與「手段」。
  2. 把效果鍵拆成 (動作型別, 目標描述)，當成 StepGoal。
  3. 用兩種方法對「當前畫面」猜目標元素：
     A. 純規則：完全相同 → 包含 → 角色（跟 ClickResolver 一樣）
     B. 字元相似度（difflib）排序
  4. 比對哪一種在「目標字串」上命中。因為收據沒有元素清單，
     這裡用**效果鍵裡的目標**當 ground truth，量的是「從目標字串找回元素」的上限。

用法：
    python tools/step_policy_spike.py --limit 50
輸出：
    bench/step_policy_spike.tsv
"""

import argparse
import io
import re
import subprocess
import sys
from difflib import SequenceMatcher
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
OUT = ROOT / "bench" / "step_policy_spike.tsv"
PKG = "com.wae.agent"


def _console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def read_receipts(serial: str) -> list[str]:
    out = subprocess.run(
        [str(ADB)] + (["-s", serial] if serial else []) +
        ["shell", "run-as", PKG, "cat", "files/mem/receipts.tsv"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    return [l for l in out.splitlines() if l.strip() and not l.startswith("#")]


def goals_from_effect(effect: str) -> tuple[str, str]:
    """`NAVIGATE:app:設定` → ("NAVIGATE", "設定")；`SELECT:query:藍牙` → ("SELECT", "藍牙")。"""
    parts = effect.split(":")
    if len(parts) < 2:
        return (effect, "")
    op = parts[0]
    target = parts[-1]
    return (op, target)


def rule_pick(goal: str, candidates: list[str]) -> str | None:
    if goal in candidates:
        return goal
    contains = [c for c in candidates if goal in c]
    if len(contains) == 1:
        return contains[0]
    return None


def similarity_pick(goal: str, candidates: list[str]) -> str | None:
    if not candidates:
        return None
    scored = sorted(((SequenceMatcher(None, goal, c).ratio(), c) for c in candidates), reverse=True)
    return scored[0][1] if scored and scored[0][0] > 0 else None


def main() -> int:
    _console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="emulator-5554")
    parser.add_argument("--limit", type=int, default=50)
    args = parser.parse_args()

    rows = read_receipts(args.serial)
    samples = []
    for line in rows:
        cells = line.split("\t")
        if len(cells) < 6:
            continue
        effect = cells[4]
        if not effect:
            continue
        op, target = goals_from_effect(effect)
        if not target:
            continue
        samples.append((effect, op, target))
        if len(samples) >= args.limit:
            break

    if not samples:
        print("沒有樣本（收據是空的？）")
        return 1

    # 候選集：同一批收據裡出現過的所有目標（模擬「畫面上可能有哪些元素」）
    candidates = sorted({t for _, _, t in samples})

    rule_hits = sim_hits = 0
    lines = ["# effect\top\ttarget\trule_pick\tsim_pick\trule_ok\tsim_ok"]
    for effect, op, target in samples:
        r = rule_pick(target, candidates)
        s = similarity_pick(target, candidates)
        rule_ok = r == target
        sim_ok = s == target
        rule_hits += rule_ok
        sim_hits += sim_ok
        lines.append(f"{effect}\t{op}\t{target}\t{r or ''}\t{s or ''}\t{int(rule_ok)}\t{int(sim_ok)}")

    total = len(samples)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    io.open(OUT, "w", encoding="utf-8", newline="").write("\n".join(lines) + "\n")
    print(f"樣本 {total}：規則 top-1 {rule_hits}/{total}（{rule_hits*100//total}%）、"
          f"相似度 top-1 {sim_hits}/{total}（{sim_hits*100//total}%）")
    print(f"已寫入 {OUT}")
    print("判讀：若規則已 ≥ 90%，就不需要小模型；差異大才值得做 embedding。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
