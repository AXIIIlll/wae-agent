"""手段使用統計：每個效果實際走哪條路、備援佔多少。

為什麼要這個：R7 的「不穩」其實是「掉進 UI 備援路徑」。
如果只看成功／失敗，要等它偶爾失敗才會發現；看手段分佈，**退化成備援的第一天就會看到**。

用法：
    python tools/means_stats.py                      # 從 emulator-5554 拉收據
    python tools/means_stats.py -s R5CX71DTN7L       # 指定真機序號
    python tools/means_stats.py --file bench/x.tsv   # 用本機檔案
    python tools/means_stats.py --snapshot           # 另存 bench/means_stats.tsv
"""

import argparse
import io
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(r"E:\WAE\android-sdk\platform-tools\adb.exe")
PKG = "com.wae.agent"
RECEIPTS = "files/mem/receipts.tsv"

# 同一個效果若有「便宜路徑」與「備援路徑」，這裡定義誰是首選
PREFERRED = {
    "intent:wae.action.SEARCH": "首選（搜尋意圖）",
    "intent:android.intent.action.MAIN": "首選（開 App）",
    "system_api:global_action:HOME": "首選（全域動作）",
    "system_api:brightness:up": "首選（系統 API）",
    "a11y:focus": "備援（UI 路徑）",
    "a11y:click": "備援（UI 路徑）",
    "input:ime": "備援（輸入法）",
    "gesture:TAP": "備援（座標）",
}


def _safe_console() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def pull(serial: str) -> str:
    cmd = [str(ADB)] + (["-s", serial] if serial else []) + ["shell", "run-as", PKG, "cat", RECEIPTS]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return out.stdout or ""


def parse_per_task(text: str, since_minutes: int = 0):
    """回傳每一次任務的 [(effect, means, outcome, post)]，用來分辨「補救成功」與「真的放掉了」。"""
    import time

    tasks = []
    cutoff = int(time.time() * 1000) - since_minutes * 60 * 1000 if since_minutes > 0 else 0
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) < 6:
            continue
        if cutoff and len(cells) > 8:
            try:
                if int(cells[8]) < cutoff:
                    continue
            except ValueError:
                pass
        steps = []
        for item in cells[5].split(";"):
            parts = item.split("|")
            if len(parts) < 3:
                continue
            post = parts[3] if len(parts) > 3 else "?"
            steps.append((parts[0], parts[1], parts[2], post))
        if steps:
            tasks.append(steps)
    return tasks


def parse(text: str, since_minutes: int = 0):
    """回傳 [(effect, means, outcome, postHeld)]。"""
    rows = []
    cutoff = 0
    if since_minutes > 0:
        import time
        cutoff = int(time.time() * 1000) - since_minutes * 60 * 1000
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) < 6:
            continue
        if cutoff and len(cells) > 8:
            try:
                if int(cells[8]) < cutoff:
                    continue
            except ValueError:
                pass
        steps = cells[5]
        for item in steps.split(";"):
            parts = item.split("|")
            if len(parts) < 3:
                continue
            effect, means, outcome = parts[0], parts[1], parts[2]
            post = parts[3] if len(parts) > 3 else "?"
            rows.append((effect, means, outcome, post))
    return rows


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("-s", "--serial", default="emulator-5554")
    parser.add_argument("--file", default="")
    parser.add_argument("--snapshot", action="store_true")
    parser.add_argument("--since-minutes", type=int, default=0, help="只看最近 N 分鐘的收據（0=全部）")
    args = parser.parse_args()

    text = io.open(args.file, encoding="utf-8").read() if args.file else pull(args.serial)
    rows = parse(text, args.since_minutes)
    if not rows:
        print("沒有收據資料（先跑幾個指令）")
        return 1

    by_effect = defaultdict(Counter)
    silent = 0
    for effect, means, outcome, post in rows:
        by_effect[effect][means] += 1
        if outcome == "SUCCESS" and post == "0":
            silent += 1

    fallback_total = sum(1 for _e, m, _o, _p in rows if PREFERRED.get(m, "").startswith("備援"))
    print(f"收據步數：{len(rows)}，靜默失敗：{silent}，備援手段：{fallback_total}\n")
    lines = []
    for effect in sorted(by_effect):
        total = sum(by_effect[effect].values())
        means_list = by_effect[effect].most_common()
        primary_share = means_list[0][1] / total
        desc = ", ".join(f"{m}×{c}" for m, c in means_list[:3])
        tag = ""
        for m, _ in means_list:
            label = PREFERRED.get(m, "")
            if label.startswith("備援") and primary_share < 0.5:
                tag = "  ← 主要走備援"
        line = f"{effect:<40} {total:>4}  {desc}{tag}"
        print(line)
        lines.append(f"{effect}\t{total}\t{desc}")

    # 備援佔比：只列「有定義首選路徑」的效果
    print("\n備援佔比（愈高愈脆）：")
    for effect in sorted(by_effect):
        counter = by_effect[effect]
        total = sum(counter.values())
        fb = sum(c for m, c in counter.items() if PREFERRED.get(m, "").startswith("備援"))
        if fb == 0:
            continue
        print(f"  {effect:<40} {fb}/{total} = {fb / total:.0%}")

    # 靜默失敗（回報成功但後置條件不成立）按效果排序
    silent_by_effect = Counter()
    silent_means = defaultdict(Counter)
    for effect, means, outcome, post in rows:
        if outcome == "SUCCESS" and post == "0":
            silent_by_effect[effect] += 1
            silent_means[effect][means] += 1

    if silent_by_effect:
        print("\n靜默失敗（回報成功但沒做到）：")
        for effect, count in silent_by_effect.most_common(10):
            means_desc = ", ".join(f"{m}×{c}" for m, c in silent_means[effect].most_common(3))
            print(f"  {effect:<40} {count:>4}  {means_desc}")

    # 真·靜默失敗：同一次任務裡「回報成功但沒做到」，而且之後沒有補救成功
    tasks = parse_per_task(text, args.since_minutes)
    abandoned = Counter()
    for steps in tasks:
        last_ok = {}
        for effect, means, outcome, post in steps:
            if outcome == "SUCCESS" and post == "1":
                last_ok[effect] = True
        for effect, means, outcome, post in steps:
            if outcome == "SUCCESS" and post == "0" and not last_ok.get(effect, False):
                abandoned[effect] += 1

    if abandoned:
        total_tasks = len(tasks)
        print(f"\n真·靜默失敗（{total_tasks} 次任務中，沒補救成功就放掉的）：")
        for effect, count in abandoned.most_common(10):
            print(f"  {effect:<40} {count:>4}")

    snapshot = ROOT / "bench" / "means_stats.tsv"
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    io.open(snapshot, "w", encoding="utf-8", newline="").write(
        "# effect\ttotal\tmeans\n" + "\n".join(lines) + "\n"
    )
    print(f"\n已寫入 {snapshot}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
