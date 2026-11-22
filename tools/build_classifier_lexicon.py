#!/usr/bin/env python3
"""
生成中文量詞/代詞/數詞詞庫：app/src/main/assets/open_source_ai_databases/zh_classifier_lexicon.tsv

用途：讓「類別級目標」判定（例如「隨機點一封信」「任何一個影片」）完全由**資料**驅動，
而不是在程式碼裡硬寫量詞/代詞清單。

資料來源（全部離線、可公開取得）：
  1. CC-CEDICT（CC BY-SA 4.0）：詞條中的 `CL:封[feng1]` 量詞標注
     https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz
  2. jieba 詞典（MIT）：詞性標注 r(代詞) / m(數詞) / q,mq(量詞)
     https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt
  3. OpenCC（Apache-2.0）：繁簡映射，用於補繁體變體（兩/個/筆/張…）
     https://github.com/BYVoid/OpenCC

用法：
  python tools/build_classifier_lexicon.py
（會把兩個來源下載到系統暫存目錄，輸出直接寫入 assets）
"""
import gzip
import json
import os
import re
import sys
import tempfile
import urllib.request

ASSET_DIR = os.path.join("app", "src", "main", "assets", "open_source_ai_databases")
OUT_FILE = os.path.join(ASSET_DIR, "zh_classifier_lexicon.tsv")
OPENCC_FILE = os.path.join(ASSET_DIR, "opencc_zh_tw_cn_lexicon.tsv")

CEDICT_URL = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
JIEBA_URL = "https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt"


def download(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "wae-lexicon-builder"})
    data = urllib.request.urlopen(req, timeout=180).read()
    if url.endswith(".gz"):
        data = gzip.decompress(data)
    return data.decode("utf-8", "replace")


def load_traditional_map() -> dict:
    """OpenCC 詞庫格式: CHAR \t 簡體 \t 繁體"""
    mapping = {}
    if not os.path.exists(OPENCC_FILE):
        return mapping
    with open(OPENCC_FILE, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[0] == "CHAR":
                mapping.setdefault(parts[1], parts[2])
    return mapping


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="wae_lex_")
    cedict_path = os.path.join(tmp, "cedict.txt")
    jieba_path = os.path.join(tmp, "jieba_dict.txt")

    print("downloading CC-CEDICT ...")
    with open(cedict_path, "w", encoding="utf-8") as f:
        f.write(download(CEDICT_URL))
    print("downloading jieba dict ...")
    with open(jieba_path, "w", encoding="utf-8") as f:
        f.write(download(JIEBA_URL))

    s2t = load_traditional_map()

    def with_traditions(words):
        out = set(words)
        for w in words:
            t = "".join(s2t.get(ch, ch) for ch in w)
            if t != w:
                out.add(t)
        return out

    # 1) CC-CEDICT 的 CL: 量詞
    cl_re = re.compile(r"CL:([^/\[]+)(?:\[[^\]]*\])?")
    measures = set()
    with open(cedict_path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "CL:" not in line:
                continue
            for m in cl_re.finditer(line):
                for part in m.group(1).split("|"):
                    part = part.strip()
                    if 1 <= len(part) <= 3:
                        measures.add(part)

    # 2) jieba 的 r / m / q
    pronouns, numerals, jieba_measures = set(), set(), set()
    with open(jieba_path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 3:
                continue
            word, tag = parts[0], parts[2]
            if len(word) > 3:
                continue
            if tag == "r":
                pronouns.add(word)
            elif tag in ("q", "mq"):
                jieba_measures.add(word)
            elif tag == "m" and len(word) <= 2:
                numerals.add(word)

    measures |= jieba_measures
    measures = with_traditions(measures)
    pronouns = with_traditions(pronouns)
    numerals = with_traditions(numerals)

    single = sorted(w for w in measures if len(w) == 1)
    multi = sorted(w for w in measures if len(w) > 1)

    os.makedirs(ASSET_DIR, exist_ok=True)
    with open(OUT_FILE, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 中文量詞/代詞/數詞詞庫（資料驅動，無任何 App 資料）\n")
        f.write("# 來源 1: CC-CEDICT (CC BY-SA 4.0) 的 CL: 量詞標注\n")
        f.write("# 來源 2: jieba (MIT) 的詞性標注 (r/m/q/mq)\n")
        f.write("# 來源 3: OpenCC (Apache-2.0) 繁簡映射，用於補繁體變體\n")
        f.write("# 格式: word \\t type\n")
        for w in single:
            f.write(f"{w}\tMEASURE\n")
        for w in multi:
            f.write(f"{w}\tMEASURE_MULTI\n")
        for w in sorted(pronouns):
            f.write(f"{w}\tPRONOUN\n")
        for w in sorted(numerals):
            f.write(f"{w}\tNUMERAL\n")

    print(json.dumps({
        "out": OUT_FILE,
        "single_measures": len(single),
        "multi_measures": len(multi),
        "pronouns": len(pronouns),
        "numerals": len(numerals),
        "bytes": os.path.getsize(OUT_FILE),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
