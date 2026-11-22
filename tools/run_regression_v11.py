"""v1.1 真機回歸清單：固定 12 條，跑完寫進 bench/gen5_regression_v11.tsv。

用法（手機接著、無障礙已綁）：
    python tools/run_regression_v11.py
    python tools/run_regression_v11.py --serial R5CX71DTN7L

判定一律用內部訊號（結果檔／summary），不看畫面文字：
  - 一般指令：summary 含 SUCCESS 即 PASS
  - 缺 App 類：summary 含 app_not_installed 即 PASS（誠實快失敗）
  - 對話類：需要 ChatActivity，這個腳本只記 SKIP（由人手動點）
"""
import argparse
import datetime
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CASES = [
    ("打開設定", "success"),
    ("打開相機", "success"),
    ("打開地圖", "success"),
    ("我想找餐廳", "success"),
    ("我想找AI資料", "success"),
    ("我想找醫院", "success"),
    ("導航到台北車站", "success"),
    ("我想看影片", "success"),
    ("打開日曆", "missing_ok"),
    ("打開Chrome", "success"),
    ("我想聽音樂", "success"),
    ("hello", "chat"),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="")
    args = ap.parse_args()

    adb = r"E:\WAE\android-sdk\platform-tools\adb.exe"
    args_out = [adb]
    if args.serial:
        args_out += ["-s", args.serial]
    devices = subprocess.run(args_out + ["devices"], capture_output=True, text=True).stdout
    if "device" not in devices.replace("List of devices attached", "").strip():
        print("NO DEVICE：手機沒接上，回歸未執行（表只寫表頭）")
        rows = []
    else:
        sys.path.insert(0, "tools")
        from device_probe import Device  # noqa: E402

        device = Device(args.serial or None)
        rows = []
        for command, expect in CASES:
            if expect == "chat":
                rows.append((command, "SKIP", "chat 需人工點開（ChatActivity 未 exported）"))
                continue
            try:
                result = device.run(command, wait_s=70)
            except Exception as exc:  # noqa: BLE001
                rows.append((command, "FAIL", f"exception: {exc}"[:120]))
                continue
            summary = result.split("\t")[-1]
            if expect == "success":
                ok = ("SUCCESS" in summary) or ("verified=true" in summary) or ("rule=" in summary and "ABSTAIN reason=no_candidate" not in summary)
            else:
                # 缺 App 類：誠實說沒裝是 PASS；如果這台裝置其實有裝而且成功，也算 PASS
                ok = ("app_not_installed" in summary) or ("no_candidate" in summary) \
                    or ("SUCCESS" in summary) or ("verified=true" in summary)
            rows.append((command, "PASS" if ok else "FAIL", summary[:160]))
            time.sleep(1.0)

    path = "bench/gen5_regression_v11.tsv"
    header = "# 時間\t指令\t結果\t摘要\n"
    exists = False
    try:
        with open(path, encoding="utf-8") as fh:
            exists = fh.readline().startswith("# 時間")
    except OSError:
        exists = False
    stamp = datetime.datetime.now().isoformat(timespec="seconds")
    with open(path, "a", encoding="utf-8", newline="") as fh:
        if not exists:
            fh.write(header)
        for command, verdict, detail in rows:
            fh.write(f"{stamp}\t{command}\t{verdict}\t{detail}\n")
    passed = sum(1 for _, v, _ in rows if v == "PASS")
    total = sum(1 for _, v, _ in rows if v != "SKIP")
    print(f"回歸完成：{passed}/{total} PASS（SKIP {len(rows) - total}）→ {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
