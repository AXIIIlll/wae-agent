"""能力清單一致性檢查：清單上的 ✅ 必須有可指出的證據。

背景：`docs/WAE_Gen5_Capability_Map.md` 曾有 10 列寫著「⬜ 還沒做」，其實早就驗證過。
清單是「我以為」，這個腳本檢查的是「有沒有證據」。兩者不一致時，永遠相信跑出來的那個。

用法：
    python tools/doc_consistency.py          # 檢查
    python tools/doc_consistency.py --list   # 只列出 ✅ 與證據種類

證據種類見 `tools/capability_evidence.tsv` 開頭說明。
"""

import argparse
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAP = ROOT / "docs" / "WAE_Gen5_Capability_Map.md"
EVIDENCE = ROOT / "tools" / "capability_evidence.tsv"

ROW_RE = re.compile(r"\| ([A-I]\d+) \| ([^|]+) \| ([^|]+) \|")
CASE_GLOBS = ["tools/gen5_*.tsv"]
TEST_DIRS = [ROOT / "core" / "src" / "test", ROOT / "app" / "src" / "test"]


def _safe_console() -> None:
    """Windows 主控台預設 cp950；印中文與 emoji 會炸。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def capability_rows() -> list[tuple[str, str, str]]:
    rows = []
    for line in io.open(MAP, encoding="utf-8").read().splitlines():
        m = ROW_RE.match(line.strip())
        if m:
            rows.append((m.group(1), m.group(2).strip(), m.group(3).strip()))
    return rows


def load_evidence() -> dict[str, tuple[str, str]]:
    out: dict[str, tuple[str, str]] = {}
    for raw in io.open(EVIDENCE, encoding="utf-8").read().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) < 3:
            continue
        out[cells[0].strip()] = (cells[1].strip(), cells[2].strip())
    return out


def all_cases() -> str:
    text = []
    for pattern in CASE_GLOBS:
        for path in ROOT.glob(pattern):
            text.append(path.read_text(encoding="utf-8"))
    return "\n".join(text)


def check_code(detail: str) -> bool:
    if "::" not in detail:
        return False
    path_part, keyword = detail.split("::", 1)
    path = ROOT / path_part
    if not path.exists():
        return False
    return keyword in path.read_text(encoding="utf-8", errors="replace")


def check_test(detail: str) -> bool:
    for base in TEST_DIRS:
        if not base.exists():
            continue
        for path in base.rglob("*.kt"):
            if detail in path.name:
                return True
    return False


def main() -> int:
    _safe_console()
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true", help="列出 ✅ 與證據種類")
    args = parser.parse_args()

    rows = capability_rows()
    evidence = load_evidence()
    cases = all_cases()

    done = [(i, n) for i, n, st in rows if st.startswith("✅")]
    problems = []

    for cap_id, name in done:
        entry = evidence.get(cap_id)
        if entry is None:
            problems.append((cap_id, name, "沒有證據列"))
            continue
        kind, detail = entry
        ok = False
        if kind == "case":
            ok = detail in cases
        elif kind == "code":
            ok = check_code(detail)
        elif kind == "test":
            ok = check_test(detail)
        elif kind == "manual":
            ok = len(detail.strip()) >= 8
        else:
            problems.append((cap_id, name, "未知的證據種類：" + kind))
            continue
        if not ok:
            problems.append((cap_id, name, f"證據查不到（{kind}: {detail}）"))

    if args.list:
        print(f"✅ {len(done)} 條：")
        for cap_id, name in done:
            kind = evidence.get(cap_id, ("?", ""))[0]
            print(f"  {cap_id:<4} {name:<28} {kind}")

    print(f"\n清單：{len(rows)} 列，✅ {len(done)} 條，證據檔 {len(evidence)} 條")
    if problems:
        print("\n有 ✅ 但沒有可驗證的證據：")
        for cap_id, name, why in problems:
            print(f"  {cap_id:<4} {name:<28} {why}")
        return 1
    print("\n一致性檢查：PASS（每個 ✅ 都能指出證據）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
