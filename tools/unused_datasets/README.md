# 未使用資料集（刻意留在 repo、不進 APK）

這些 TSV 目前**沒有任何 App 程式碼或測試引用**（全庫 grep 0 筆），
為了讓 APK 保持精簡（這是使用者明確要求的），把它們移出 `app/src/main/assets/`。
檔案本身留在這裡，未來若要接（例如中文同義詞反義詞、AOSP 設定頁語料、
意图槽位語料）再搬回去，並先確認有實際讀取點。

| 檔案 | 大小 | 原本可能的用途 |
|---|---|---|
| cilin_hownet_antonym_mutual_exclusion_100k.tsv | 1.47 MB | 中文反義詞互斥（語意消歧） |
| aosp_seq2act_ui_settings_lexicon.tsv | 0.51 MB | AOSP 設定頁語料（UI 動作對應） |
| smp_ecdt_intent_slot_corpus.tsv | 0.25 MB | 意圖/槽位語料 |

移除日期：2026-11-14（S29 APK 瘦身）
