"""Gen-5 能力評測（I3）：把多套案例合成一次可重跑的評測。

用法：
    python tools/gen5_eval.py            # 只跑（需先安裝 App）
    python tools/gen5_eval.py --install  # 先安裝再跑

每一套都是「指令\t預期」的 TSV；結果會累加並寫進 bench/gen5_eval_history.tsv。
"""

import argparse
import datetime
import io
import re
import subprocess
import time
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SMOKE = Path(__file__).resolve().parent / "gen5_smoke.py"
HISTORY = ROOT / "bench" / "gen5_eval_history.tsv"
TIMINGS = ROOT / "bench" / "eval_timings.tsv"

SUITES_TSV = ROOT / "tools" / "eval_suites.tsv"


def load_suites() -> list[tuple[str, Path, set[str], int]]:
    """(name, 案例檔, 標籤, 估計秒數)——資料驅動，不在程式裡寫死套件清單。"""
    out = []
    for raw in SUITES_TSV.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) < 3:
            continue
        name = cells[0].strip()
        path = ROOT / cells[1].strip()
        tags = {t.strip() for t in cells[2].split(",") if t.strip()}
        eta = int(cells[3]) if len(cells) > 3 and cells[3].strip().isdigit() else 0
        out.append((name, path, tags, eta))
    return out

RESULT_RE = re.compile(r"RESULT: (PASS|FAIL) \((\d+)/(\d+)\)")


def load_timings() -> dict[str, float]:
    """上次實測的每套耗時（沒有就回空，讓呼叫端用估計值）。"""
    out: dict[str, float] = {}
    if not TIMINGS.exists():
        return out
    for raw in TIMINGS.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        cells = raw.split("\t")
        if len(cells) < 3:
            continue
        try:
            out[cells[1]] = float(cells[2])
        except ValueError:
            continue
    return out


def record_timing(name: str, seconds: float) -> None:
    TIMINGS.parent.mkdir(parents=True, exist_ok=True)
    new = not TIMINGS.exists()
    with io.open(TIMINGS, "a", encoding="utf-8", newline="") as f:
        if new:
            f.write("# timestamp\tsuite\tseconds\n")
        f.write("%s\t%s\t%.1f\n" % (datetime.datetime.now().isoformat(timespec="seconds"), name, seconds))


def run_suite(name: str, cases: Path, wait: int, skip_install: bool) -> tuple[int, int, float]:
    out_file = ROOT / "bench" / ("last_" + Path(cases).stem + ".tsv")
    cmd = [sys.executable, str(SMOKE), "--wait", str(wait), "--cases", str(cases),
           "--out", str(out_file)]
    if skip_install:
        cmd.append("--skip-install")
    started = time.monotonic()
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (proc.stdout or "") + (proc.stderr or "")
    # 永遠留一份原始輸出：出問題時不必重跑一次才知道原因（Round 97 的教訓）
    log_path = ROOT / "bench" / ("last_" + Path(cases).stem + ".log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    io.open(log_path, "w", encoding="utf-8", newline="").write(out)
    duration = time.monotonic() - started
    match = RESULT_RE.search(out)
    if not match:
        print(f"{name:<10} 無結果（腳本輸出異常，exit={proc.returncode}）")
        for line in out.splitlines()[-5:]:
            print("   | " + line.strip())
        return 0, 0, duration
    passed, total = int(match.group(2)), int(match.group(3))
    status = "PASS" if passed == total else "FAIL"
    print(f"{name:<10} {status} ({passed}/{total})  耗時 {duration:.0f}s")
    record_timing(name, duration)
    if passed != total:
        for line in out.splitlines():
            if "FAIL" in line and "expect=" in line:
                print("   " + line.strip())
    return passed, total, duration


def _safe_console() -> None:
    """Windows 主控台預設 cp950，直接印中文會 UnicodeEncodeError。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("--install", action="store_true", help="先安裝 App")
    parser.add_argument("--wait", type=int, default=14, help="每個指令等幾秒（實測 11 秒會掉條）")
    parser.add_argument("--tag", default="", help="只跑含這個標籤的套件（例：lexicon）")
    parser.add_argument("--suites", default="", help="只跑這些套件（逗號分隔，例：baseline,extra）")
    parser.add_argument("--dry-run", action="store_true", help="只印出會跑哪些套件與估計時間，不真的跑")
    args = parser.parse_args()

    wanted = {x.strip() for x in args.suites.split(",") if x.strip()}
    suites = load_suites()
    if wanted:
        suites = [s for s in suites if s[0] in wanted]
    if args.tag:
        suites = [s for s in suites if args.tag in s[2]]
    if not suites:
        print("沒有符合條件的套件（檢查 --tag / --suites）")
        return 1

    measured = load_timings()
    eta = int(sum(measured.get(s[0], float(s[3])) for s in suites))
    print("將執行：" + ", ".join(s[0] for s in suites) + f"（估計 {eta // 60} 分 {eta % 60} 秒）\n")
    if args.dry_run:
        for name, path, tags, est in suites:
            src = "實測" if name in measured else "估計"
            secs = int(measured.get(name, float(est)))
            print(f"  {name:<10} {secs:>4}s（{src}） 標籤={','.join(sorted(tags))}")
        return 0

    total_pass = 0
    total = 0
    for name, path, _tags, _eta in suites:
        if not path.exists():
            print(f"{name:<10} 缺少檔案：{path}")
            continue
        passed, count, _duration = run_suite(name, path, args.wait, skip_install=not args.install)
        total_pass += passed
        total += count

    print(f"\n總計：{total_pass}/{total}")
    HISTORY.parent.mkdir(parents=True, exist_ok=True)
    new_file = not HISTORY.exists()
    with io.open(HISTORY, "a", encoding="utf-8", newline="") as f:
        if new_file:
            f.write("# timestamp\tpass\ttotal\tnode\n")
        f.write(
            "%s\t%d\t%d\t%s\n"
            % (datetime.datetime.now().isoformat(timespec="seconds"), total_pass, total, "eval")
        )
    return 0 if total_pass == total and total > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
