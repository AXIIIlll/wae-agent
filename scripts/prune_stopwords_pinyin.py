import os

STOPWORDS_PATH = r"e:\NewAndroidApp\app\src\main\assets\open_source_ai_databases\stopwords_and_asr_homophones.tsv"
SMP_PATH = r"e:\NewAndroidApp\app\src\main\assets\open_source_ai_databases\smp_ecdt_intent_slot_corpus.tsv"
POPUP_PATH = r"e:\NewAndroidApp\app\src\main\assets\open_source_ai_databases\popup_ad_blocker_specialist_db.tsv"


def prune_stopwords_pinyin():
    before_bytes = os.path.getsize(STOPWORDS_PATH)
    with open(STOPWORDS_PATH, "r", encoding="utf-8") as f:
        lines = f.readlines()

    kept_lines = []
    removed_pinyin = 0
    stop_count = 0
    asr_count = 0

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith("type\t"):
            kept_lines.append(line)
            continue
        parts = stripped.split("\t")
        row_type = parts[0].strip().upper()
        if row_type == "PINYIN":
            removed_pinyin += 1
            continue
        elif row_type == "STOP":
            stop_count += 1
        elif row_type == "ASR":
            asr_count += 1
        kept_lines.append(line)

    with open(STOPWORDS_PATH, "w", encoding="utf-8", newline="\n") as f:
        for line in kept_lines:
            f.write(line.rstrip("\r\n") + "\n")

    after_bytes = os.path.getsize(STOPWORDS_PATH)
    print(
        f"[stopwords_and_asr_homophones.tsv] Before: {len(lines)} lines ({before_bytes} B) -> "
        f"After: {len(kept_lines)} lines ({after_bytes} B) | "
        f"STOP={stop_count}, ASR={asr_count}, Removed PINYIN={removed_pinyin}"
    )


def strip_smp_bom():
    with open(SMP_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    has_bom = "\ufeff" in content
    cleaned = content.replace("\ufeff", "")
    with open(SMP_PATH, "w", encoding="utf-8", newline="\n") as f:
        for line in cleaned.splitlines():
            f.write(line + "\n")
    print(f"[smp_ecdt_intent_slot_corpus.tsv] Stripped UTF-8 BOM: {has_bom}")


def update_popup_drag_handle_note():
    with open(POPUP_PATH, "r", encoding="utf-8") as f:
        lines = f.readlines()
    updated = []
    for line in lines:
        if line.startswith("SWIPE_DISMISS_CONTAINER\tDRAG_HANDLE\t"):
            parts = line.rstrip("\r\n").split("\t")
            if len(parts) >= 5 and "僅在彈窗且無白名單時" not in parts[4]:
                parts[4] = parts[4] + "（僅在彈窗且無白名單時才視為可滑掉；否則視為上滑展開把手）"
                line = "\t".join(parts) + "\n"
        updated.append(line)
    with open(POPUP_PATH, "w", encoding="utf-8", newline="\n") as f:
        for line in updated:
            f.write(line.rstrip("\r\n") + "\n")
    print("[popup_ad_blocker_specialist_db.tsv] Updated DRAG_HANDLE rule note.")


if __name__ == "__main__":
    prune_stopwords_pinyin()
    strip_smp_bom()
    update_popup_drag_handle_note()
