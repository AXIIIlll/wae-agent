#!/usr/bin/env python3
"""
G0：Grounding fixture 離線評分（stdlib only）→ docs/Grounding_Report.md。

量的是「可重播的定位品質」，全部由 fixture 內的 score / labelIndex 決定，
不呼叫 App、不需要手機，可放進 CI 或改完融合權重後重跑。

定義（與 Kotlin 端 GroundingReplay 完全一致）：
  Top-1          最高分候選 == labelIndex（只算已標註步）
  Top-3          labelIndex 落在前三名（只算已標註步）
  錯點率         chosenIndex >= 0 且 chosenIndex != labelIndex（只算已標註步）
  abstain        chosenIndex < 0，或最高分 < --abstain-threshold（模擬門檻）
  abstain precision  可判定的 abstain 步中「Top-1 本來會錯」的比例
  延遲          trace 的 elapsedMs（grounding 額外耗時，不是整步耗時）

命名慣例：`seed_` 開頭的 fixture 視為單元測試用的自檢資料，不列入正式指標；
正式資料用 `build_fixtures.py` 產生的檔名（例如 `20260101_EASY_...`）。

用法：
  python tools/grounding/evaluate_grounding.py                 # 寫 docs/Grounding_Report.md
  python tools/grounding/evaluate_grounding.py --stdout        # 只印出、不寫檔
  python tools/grounding/evaluate_grounding.py --fixtures tools/grounding/fixtures
  python tools/grounding/evaluate_grounding.py --fail-on-gate  # 有指標未達標就 exit 2

已知邊界：
  - 標籤多為弱標籤（畫面有變化 / 後置條件），不是人工真值
  - 樣本 < 30 步時只印「樣本不足」，不應拿來宣稱驗收
  - abstain 門檻是模擬值；真正門檻由 G1 校準後再回填
"""
import argparse
import datetime
import glob
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_FIXTURE_DIRS = [
    os.path.join(ROOT, "tools", "grounding", "fixtures"),
    os.path.join(ROOT, "app", "src", "test", "resources", "grounding_fixtures"),
]
DEFAULT_OUT = os.path.join(ROOT, "docs", "Grounding_Report.md")

# 目標值來源：docs/WAE_Grounding2_Plan.md「驗收指標」
TARGET_TOP1 = 0.90
TARGET_TOP3 = 0.97
TARGET_WRONG_CLICK = 0.02
TARGET_ABSTAIN_PRECISION = 0.90
TARGET_AVG_LATENCY_MS = 30.0
MIN_SAMPLE_FOR_CLAIM = 30

NO_DATA = "尚無資料"


def display_path(path):
    """相對專案根的路徑；跨磁碟機（例如暫存測試放 C:）時退回絕對路徑。"""
    try:
        return os.path.relpath(path, ROOT)
    except ValueError:
        return path


def load_fixtures(paths):
    """讀入 fixture JSON；回傳 [{'path','rel','name','source','steps'}]。"""
    fixtures = []
    for entry in paths:
        if os.path.isdir(entry):
            files = sorted(glob.glob(os.path.join(entry, "*.json")))
        elif os.path.isfile(entry):
            files = [entry]
        else:
            print("  ! 找不到 fixture 路徑：%s" % entry)
            continue
        for path in files:
            try:
                with open(path, encoding="utf-8") as handle:
                    payload = json.load(handle)
            except (ValueError, OSError) as error:
                print("  ! %s 讀取失敗：%s" % (path, error))
                continue
            if not isinstance(payload, dict) or not isinstance(payload.get("steps"), list):
                print("  ! %s 不是合法 fixture（缺 steps）" % path)
                continue
            steps = [step for step in payload["steps"] if isinstance(step, dict)]
            if not steps:
                continue
            fixtures.append(
                {
                    "path": path,
                    "rel": display_path(path),
                    "name": str(payload.get("name") or os.path.splitext(os.path.basename(path))[0]),
                    "source": str(payload.get("source") or ""),
                    "steps": steps,
                }
            )
    return fixtures


def ranking(step):
    candidates = step.get("candidates") or []
    scores = []
    for index, candidate in enumerate(candidates):
        try:
            score = float(candidate.get("score", 0.0))
        except (TypeError, ValueError):
            score = 0.0
        if not math.isfinite(score):
            score = 0.0
        scores.append((-score, index))
    return [index for _, index in sorted(scores)]


def safe_int(value, default=-1):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def top_score(step):
    candidates = step.get("candidates") or []
    if not candidates:
        return 0.0
    values = []
    for candidate in candidates:
        try:
            score = float(candidate.get("score", 0.0))
        except (TypeError, ValueError):
            score = 0.0
        values.append(score if math.isfinite(score) else 0.0)
    return max(values)


def rate(numerator, denominator):
    return None if denominator == 0 else float(numerator) / denominator


def evaluate_steps(steps, threshold):
    labeled = 0
    top1_hits = 0
    top3_hits = 0
    wrong_clicks = 0
    abstain_steps = 0
    abstain_evaluable = 0
    abstain_correct = 0
    elapsed_values = []
    zero_elapsed = 0

    for step in steps:
        candidates = step.get("candidates") or []
        label = safe_int(step.get("labelIndex"), -1)
        chosen = safe_int(step.get("chosenIndex"), -1)
        order = ranking(step)
        top1_ok = label >= 0 and bool(order) and order[0] == label
        top3_ok = label >= 0 and label in order[:3]

        if label >= 0:
            labeled += 1
            top1_hits += 1 if top1_ok else 0
            top3_hits += 1 if top3_ok else 0
            if chosen >= 0 and chosen != label:
                wrong_clicks += 1

        abstained = (chosen < 0) or (not candidates) or (top_score(step) < threshold)
        if abstained:
            abstain_steps += 1
            if label >= 0:
                abstain_evaluable += 1
                abstain_correct += 0 if top1_ok else 1

        elapsed = safe_int(step.get("elapsedMs"), 0)
        if elapsed < 0:
            elapsed = 0
        if elapsed == 0:
            zero_elapsed += 1
        elapsed_values.append(elapsed)

    avg_elapsed = (
        sum(elapsed_values) / float(len(elapsed_values)) if elapsed_values else None
    )
    return {
        "steps": len(steps),
        "labeled": labeled,
        "top1_hits": top1_hits,
        "top3_hits": top3_hits,
        "wrong_clicks": wrong_clicks,
        "abstain_steps": abstain_steps,
        "abstain_evaluable": abstain_evaluable,
        "abstain_correct": abstain_correct,
        "avg_elapsed_ms": avg_elapsed,
        "max_elapsed_ms": max(elapsed_values) if elapsed_values else None,
        "zero_elapsed": zero_elapsed,
        "top1_rate": rate(top1_hits, labeled),
        "top3_rate": rate(top3_hits, labeled),
        "wrong_click_rate": rate(wrong_clicks, labeled),
        "abstain_precision": rate(abstain_correct, abstain_evaluable),
    }


def pct(value):
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return NO_DATA
    return "%.1f%%" % (value * 100.0)


def status(value, target, higher_is_better):
    if value is None:
        return NO_DATA
    if higher_is_better:
        return "PASS" if value >= target else "FAIL"
    return "PASS" if value <= target else "FAIL"


def latency_text(value):
    if value is None:
        return NO_DATA
    return "%.1f ms" % value


def render_steps_table(fixtures):
    lines = [
        "| fixture | 步數 | 已標註 | Top-1 | Top-3 | 錯點率 | abstain 步 | 來源 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for fixture in fixtures:
        metrics = evaluate_steps(fixture["steps"], 0.0)
        lines.append(
            "| %s | %d | %d | %s | %s | %s | %d | %s |"
            % (
                fixture["name"],
                metrics["steps"],
                metrics["labeled"],
                pct(metrics["top1_rate"]),
                pct(metrics["top3_rate"]),
                pct(metrics["wrong_click_rate"]),
                metrics["abstain_steps"],
                fixture["source"] or "-",
            )
        )
    return lines


def render_pkg_table(fixtures):
    groups = {}
    for fixture in fixtures:
        for step in fixture["steps"]:
            pkg = str(step.get("pkg") or "?")
            groups.setdefault(pkg, []).append(step)
    if not groups:
        return []
    lines = [
        "| pkg token | 步數 | 已標註 | Top-1 | Top-3 | 錯點率 |",
        "|---|---|---|---|---|---|",
    ]
    for pkg in sorted(groups):
        metrics = evaluate_steps(groups[pkg], 0.0)
        lines.append(
            "| %s | %d | %d | %s | %s | %s |"
            % (
                pkg,
                metrics["steps"],
                metrics["labeled"],
                pct(metrics["top1_rate"]),
                pct(metrics["top3_rate"]),
                pct(metrics["wrong_click_rate"]),
            )
        )
    return lines


def render_report(fixtures, args, generated_at):
    real = [f for f in fixtures if not f["name"].startswith("seed_")]
    seeds = [f for f in fixtures if f["name"].startswith("seed_")]
    if args.include_seed_in_main:
        real = fixtures
        seeds = []

    main_steps = [step for fixture in real for step in fixture["steps"]]
    all_steps = [step for fixture in fixtures for step in fixture["steps"]]
    main = evaluate_steps(main_steps, args.abstain_threshold)
    has_main_labels = main["labeled"] > 0
    has_latency = main["avg_elapsed_ms"] is not None and main["zero_elapsed"] < main["steps"]

    lines = []
    lines.append("# Grounding 量測報告（G0 / Phase 0）")
    lines.append("")
    lines.append("> 產生時間：%s" % generated_at)
    lines.append("> 產生器：`tools/grounding/evaluate_grounding.py`（Kotlin 端定義見 `GroundingReplay.kt`）")
    lines.append(
        "> 全部 fixture：%d 份／%d 步；正式 fixture：%d 份／%d 步（已標註 %d）"
        % (len(fixtures), len(all_steps), len(real), len(main_steps), main["labeled"])
    )
    lines.append(
        "> 驗收門檻來源：`docs/WAE_Grounding2_Plan.md`（Top-1 ≥90%、Top-3 ≥97%、"
        "錯點率 ≤2%、abstain precision ≥90%、每步延遲 <30ms）"
    )
    lines.append("> abstain 模擬門檻：%.3f（真門檻待 G1 校準後回填）" % args.abstain_threshold)
    lines.append("")

    lines.append("## 資料來源")
    lines.append("")
    if fixtures:
        for fixture in fixtures:
            tag = "自檢（不列入正式指標）" if fixture["name"].startswith("seed_") else "正式"
            lines.append(
                "- `%s`（%s，%d 步，來源：%s）"
                % (fixture["rel"], tag, len(fixture["steps"]), fixture["source"] or "-")
            )
    else:
        lines.append("- 尚無資料：`tools/grounding/fixtures/` 與 `app/src/test/resources/grounding_fixtures/` 都沒有 fixture。")
        lines.append("- 取得方式：先跑真機 `python tools/grounding/capture_benchmark.py`，再用 `build_fixtures.py` 轉檔。")
    lines.append("")

    lines.append("## 核心指標（正式 fixture 合併）")
    lines.append("")
    if not has_main_labels:
        lines.append("**尚無資料。**")
        lines.append("")
        lines.append("正式 fixture 的已標註步數為 0，因此 Top-1 / Top-3 / 錯點率 / abstain 都無法定義。")
        lines.append("（`seed_` fixture 的自檢數字見下方，僅用來證明解析與定義一致，不是量測結果。）")
        lines.append("")
    else:
        lines.append("| 指標 | 定義 | 目標 | 實測 | 判定 |")
        lines.append("|---|---|---|---|---|")
        lines.append(
            "| Top-1 | 最高分候選＝答案（已標註步） | ≥90%% | %s（%d/%d） | %s |"
            % (
                pct(main["top1_rate"]),
                main["top1_hits"],
                main["labeled"],
                status(main["top1_rate"], TARGET_TOP1, True),
            )
        )
        lines.append(
            "| Top-3 | 答案落在前三名（已標註步） | ≥97%% | %s（%d/%d） | %s |"
            % (
                pct(main["top3_rate"]),
                main["top3_hits"],
                main["labeled"],
                status(main["top3_rate"], TARGET_TOP3, True),
            )
        )
        lines.append(
            "| 錯點率 | 選中候選≠答案（已標註步） | ≤2%% | %s（%d/%d） | %s |"
            % (
                pct(main["wrong_click_rate"]),
                main["wrong_clicks"],
                main["labeled"],
                status(main["wrong_click_rate"], TARGET_WRONG_CLICK, False),
            )
        )
        if main["abstain_evaluable"] > 0:
            lines.append(
                "| abstain precision | 可判定 abstain 中「Top-1 本來會錯」的比例 | ≥90%% | %s（%d/%d） | %s |"
                % (
                    pct(main["abstain_precision"]),
                    main["abstain_correct"],
                    main["abstain_evaluable"],
                    status(main["abstain_precision"], TARGET_ABSTAIN_PRECISION, True),
                )
            )
        else:
            lines.append("| abstain precision | 可判定 abstain 中「Top-1 本來會錯」的比例 | ≥90% | 尚無資料（沒有可判定 abstain 步） | 尚無資料 |")
        lines.append("")
        if main["labeled"] < MIN_SAMPLE_FOR_CLAIM:
            lines.append(
                "> ⚠ 樣本不足：已標註 %d 步 < %d 步，以上數字只供 smoke check，"
                "**不構成驗收結論**。" % (main["labeled"], MIN_SAMPLE_FOR_CLAIM)
            )
            lines.append("")

    lines.append("## 延遲（每步 grounding 額外耗時）")
    lines.append("")
    if has_latency:
        lines.append("| 指標 | 目標 | 實測 | 判定 |")
        lines.append("|---|---|---|---|")
        lines.append(
            "| 平均 | <30 ms | %s | %s |"
            % (
                latency_text(main["avg_elapsed_ms"]),
                status(main["avg_elapsed_ms"], TARGET_AVG_LATENCY_MS, False),
            )
        )
        lines.append("| 最大 | - | %s | - |" % latency_text(main["max_elapsed_ms"]))
    else:
        lines.append("**尚無資料。**")
        if main["steps"] > 0:
            lines.append("")
            lines.append(
                "既有 fixture 的 `elapsedMs` 全為 0，代表 trace 尚未填入 grounding 耗時；"
                "延遲必須在服務層量測後才有意義。"
            )
    lines.append("")

    if main_steps:
        lines.append("## 每份 fixture 明細")
        lines.append("")
        lines.extend(render_steps_table(real))
        lines.append("")
        pkg_lines = render_pkg_table(real)
        if pkg_lines:
            lines.append("## 每個套件（token 化）明細")
            lines.append("")
            lines.extend(pkg_lines)
            lines.append("")

    if seeds:
        seed_steps = [step for fixture in seeds for step in fixture["steps"]]
        seed_metrics = evaluate_steps(seed_steps, args.abstain_threshold)
        lines.append("## fixture 自檢（seed，非真機指標）")
        lines.append("")
        lines.append(
            "> 這節是手寫種子 fixture 的解析/定義自檢（%d 步），用來證明 Kotlin 與 Python "
            "對 Top-1/Top-3/abstain 的定義一致，**不可引用為真機正確率**。"
            % seed_metrics["steps"]
        )
        lines.append("")
        lines.append("| 指標 | 值 |")
        lines.append("|---|---|")
        lines.append("| Top-1 | %s（%d/%d） |" % (
            pct(seed_metrics["top1_rate"]), seed_metrics["top1_hits"], seed_metrics["labeled"]))
        lines.append("| Top-3 | %s（%d/%d） |" % (
            pct(seed_metrics["top3_rate"]), seed_metrics["top3_hits"], seed_metrics["labeled"]))
        lines.append("| 錯點率 | %s（%d/%d） |" % (
            pct(seed_metrics["wrong_click_rate"]), seed_metrics["wrong_clicks"], seed_metrics["labeled"]))
        lines.append("| abstain precision | %s（%d/%d） |" % (
            pct(seed_metrics["abstain_precision"]), seed_metrics["abstain_correct"],
            seed_metrics["abstain_evaluable"]))
        lines.append("")
        lines.extend(render_steps_table(seeds))
        lines.append("")

    lines.append("## 怎麼量（可重現步驟）")
    lines.append("")
    lines.append("```powershell")
    lines.append("# 1. 真機收集 trace（前置：debug APK + 無障礙服務 + 服務層已接 recorder）")
    lines.append("python tools/grounding/capture_benchmark.py --limit 5")
    lines.append("# 2. 轉成去識別化 fixture（文字 token 化）")
    lines.append("python tools/grounding/build_fixtures.py")
    lines.append("# 3. 離線評分，更新本報告")
    lines.append("python tools/grounding/evaluate_grounding.py")
    lines.append("# 4. Kotlin 端回歸（定義一致性）")
    lines.append(".\\gradlew.bat :app:testDebugUnitTest --tests \"com.wae.agent.core.grounding.*\"")
    lines.append("```")
    lines.append("")

    lines.append("## 已知邊界")
    lines.append("")
    lines.append("- **弱標籤不是真值**：`labelIndex` 來自「畫面有變化／後置條件成立 → 實際選中的候選」，")
    lines.append("  點錯但畫面有變化仍會被記為正樣本；因此本報告適合看**相對退步**，不宜當對外宣稱。")
    lines.append("- **fixture 已去識別化**：文字／viewId／套件名 token 化（T#/V#/P#），")
    lines.append("  只保留長度、幾何、旗標與分數；因此不能用 fixture 重跑文字語意相關邏輯。")
    lines.append("- **abstain 是模擬的**：目前用 `chosenIndex < 0` 或分數門檻推估，真門檻由 G1/I1 校準。")
    lines.append("- **延遲只含 grounding**：`elapsedMs` 不含感知、動作派發與等待；端到端耗時看 benchmark。")
    lines.append("- **跨 App 樣本不均衡**：pkg token 表可看分佈；樣本集中在少數 App 時 Top-1 會偏樂觀。")
    lines.append("- **seed_ fixture 只做自檢**：手寫 3 步，不代表任何真機結果。")
    lines.append("")
    return "\n".join(lines) + "\n"


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    parser = argparse.ArgumentParser(description="Grounding fixture 離線評分（G0）")
    parser.add_argument(
        "--fixtures", action="append", default=[], help="fixture 檔或目錄（可重複；預設兩個標準目錄）"
    )
    parser.add_argument("--out", default=DEFAULT_OUT, help="報告輸出路徑")
    parser.add_argument("--stdout", action="store_true", help="只印出報告，不寫檔")
    parser.add_argument("--abstain-threshold", type=float, default=0.0, help="模擬 abstain 的分數門檻")
    parser.add_argument(
        "--include-seed-in-main", action="store_true", help="把 seed_ 自檢 fixture 也算進正式指標（不建議）"
    )
    parser.add_argument("--fail-on-gate", action="store_true", help="有指標 FAIL 時 exit 2")
    args = parser.parse_args()

    paths = args.fixtures or DEFAULT_FIXTURE_DIRS
    fixtures = load_fixtures(paths)
    generated_at = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    report = render_report(fixtures, args, generated_at)

    if args.stdout:
        print(report)
    else:
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(report)
        print("報告已寫入：%s" % display_path(args.out))

    real = [f for f in fixtures if not f["name"].startswith("seed_")]
    if args.include_seed_in_main:
        real = fixtures
    main = evaluate_steps([step for fixture in real for step in fixture["steps"]], args.abstain_threshold)
    if main["labeled"] == 0:
        print("目前尚無正式標註資料（seed 自檢不算），指標無法計算。")
        return 0
    failures = []
    if main["top1_rate"] is not None and main["top1_rate"] < TARGET_TOP1:
        failures.append("Top-1")
    if main["top3_rate"] is not None and main["top3_rate"] < TARGET_TOP3:
        failures.append("Top-3")
    if main["wrong_click_rate"] is not None and main["wrong_click_rate"] > TARGET_WRONG_CLICK:
        failures.append("錯點率")
    if main["abstain_precision"] is not None and main["abstain_precision"] < TARGET_ABSTAIN_PRECISION:
        failures.append("abstain")
    if main["avg_elapsed_ms"] is not None and main["avg_elapsed_ms"] >= TARGET_AVG_LATENCY_MS:
        failures.append("延遲")
    if failures:
        print("未達標指標：%s" % "、".join(failures))
        return 2 if args.fail_on_gate else 0
    print("全部指標達標（樣本 %d 步）。" % main["labeled"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
