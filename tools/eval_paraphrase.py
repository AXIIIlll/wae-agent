"""改寫集評測：量「沒見過的說法」懂不懂。

用法（手機或模擬器接著）：
    python tools/eval_paraphrase.py --serial emulator-5554

每個句子跑兩個探針：
  !judge <句子>  → 規則層判到哪個目的（SPECIFIC 才算命中）
  !kb <句子>     → 檢索層第一名是哪個目的
輸出：bench/paraphrase_eval.tsv（逐句）＋終端摘要（seen / heldout 分開算）
"""
import argparse
import datetime
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="")
    args = ap.parse_args()

    adb = r"E:\WAE\android-sdk\platform-tools\adb.exe"
    serial = args.serial
    sys.path.insert(0, "tools")
    from device_probe import Device  # noqa: E402

    device = Device(serial or None)

    rows = []
    with open("bench/paraphrase_set.tsv", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            c = line.split("\t")
            if len(c) >= 5:
                rows.append(c[:5])

    results = []
    for iid, intent, utterance, split, expect in rows:
        judge = device.run_full("!judge " + utterance, wait_s=45) or ""
        kb = device.run_full("!kb " + utterance, wait_s=45) or ""
        judge_hit = expect in judge and "SPECIFIC" in judge
        kb_first = ""
        # !kb 的第一個 P- 行才是 top-1（之前讀到最後一行＝第 3 名）
        for line in kb.splitlines():
            if line.strip().startswith("P-"):
                kb_first = line.strip().split(" ")[0]
                break
        kb_hit = expect in kb_first
        judge_clean = judge.replace("\t", " ").replace("\n", " ")[:200]
        results.append((iid, intent, utterance, split, expect, judge_hit, kb_hit, judge_clean, kb_first))

    stamp = datetime.datetime.now().isoformat(timespec="seconds")
    path = "bench/paraphrase_eval.tsv"
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write("# 時間\tid\t意圖\t句子\tsplit\t期望\t規則命中\t檢索命中\tjudge\t檢索第一\n")
        for r in results:
            fh.write(stamp + "\t" + "\t".join(str(x) for x in r) + "\n")

    def rate(split: str, idx: int) -> str:
        subset = [r for r in results if r[3] == split]
        if not subset:
            return "n/a"
        hits = sum(1 for r in subset if r[idx])
        return f"{hits}/{len(subset)} ({round(100 * hits / len(subset))}%)"

    print("=== 規則層（!judge）===")
    print("seen    :", rate("seen", 5))
    print("heldout :", rate("heldout", 5))
    print("=== 檢索層（!kb 第一名）===")
    print("seen    :", rate("seen", 6))
    print("heldout :", rate("heldout", 6))
    print("明細：", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
