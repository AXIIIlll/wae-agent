#!/usr/bin/env python3
"""
生成 100 任務評測集：benchmark/tasks.tsv

設計原則：
  1. **難度分層**：EASY 30 / MEDIUM 30 / HARD 25 / EXTREME 15，每個難度都有
  2. **EXTREME 必須是真長鏈**：≥30 步（多 App、多階段、含干擾與返回）
  3. **任務是資料不是程式**：每行一個任務，可人工修改、可版本化
  4. **不綁定特定 App 版本**：用使用者口語（開/搜/點/回），由 Agent 自己解析
  5. 所有任務都可由 `debug_command` 注入，執行器不需支援中文輸入

欄位：
  id          任務編號
  tier        EASY / MEDIUM / HARD / EXTREME
  app_family  任務主要涉及的 App 家族（僅供統計，不影響執行）
  cmd         使用者口語指令（繁中）
  min_steps   預期最少動作步數（用於統計「是否真的走到那麼深」）
  timeout_s   單任務逾時
  notes       驗收要點

用法：
  python tools/gen_benchmark_tasks.py
"""
import json
import os
import sys

OUT = os.path.join("benchmark", "tasks.tsv")

# ── App 家族（僅統計用；任務本身用口語）──────────────────────────
MAIL = "mail"
MAPS = "maps"
VIDEO = "video"
SOCIAL = "social"
SHOP = "shop"
GAME = "game"
SETTINGS = "settings"
BROWSER = "browser"
MUSIC = "music"
FILES = "files"

# ── EASY（30）：1–3 步，搜尋/開關/返回 ──────────────────────────
EASY = [
    (MAPS, "打開地圖", 1, 40, "App 啟動"),
    (MAPS, "在地圖搜尋台北101", 2, 45, "搜尋成功且有結果"),
    (MAIL, "打開gmail", 1, 40, "App 啟動"),
    (MAIL, "在gmail搜尋invoice", 2, 50, "搜尋框輸入並提交"),
    (VIDEO, "打開youtube", 1, 40, "App 啟動"),
    (VIDEO, "在youtube搜尋周杰倫", 2, 50, "搜尋有結果"),
    (SETTINGS, "打開設定", 1, 40, "App 啟動"),
    (SETTINGS, "進入顯示設定", 2, 45, "二級頁"),
    (BROWSER, "打開瀏覽器", 1, 40, "App 啟動"),
    (BROWSER, "在瀏覽器搜尋天氣", 2, 50, "搜尋頁"),
    (MUSIC, "打開音樂", 1, 40, "App 啟動"),
    (MUSIC, "在音樂搜尋晴天", 2, 50, "搜尋有結果"),
    (FILES, "打開檔案管理", 1, 40, "App 啟動"),
    (FILES, "在檔案管理看下載項目", 2, 50, "進入下載目錄"),
    (SOCIAL, "打開社群app", 1, 40, "App 啟動"),
    (SHOP, "打開購物app", 1, 40, "App 啟動"),
    (GAME, "打開遊戲", 1, 40, "App 啟動"),
    (MAPS, "在地圖搜尋加油站", 2, 45, "搜尋有結果"),
    (MAIL, "在gmail看收件匣", 2, 45, "回到收件匣"),
    (VIDEO, "在youtube回到首頁", 2, 45, "首頁"),
    (SETTINGS, "打開wifi設定", 2, 45, "Wifi 頁"),
    (SETTINGS, "打開藍牙設定", 2, 45, "藍牙頁"),
    (BROWSER, "在瀏覽器開新分頁", 2, 50, "新分頁"),
    (MAPS, "在地圖放大畫面", 2, 45, "地圖縮放"),
    (MAPS, "在地圖縮小畫面", 2, 45, "地圖縮放"),
    (MAIL, "在gmail切換到寄件備份", 2, 50, "資料夾切換"),
    (VIDEO, "在youtube打開訂閱頁", 2, 50, "訂閱分頁"),
    (SETTINGS, "在設定搜尋電池", 2, 50, "設定內搜尋"),
    (FILES, "在檔案管理搜尋pdf", 2, 50, "檔案搜尋"),
    (SOCIAL, "在社群app回到首頁", 2, 45, "首頁"),
]

# ── MEDIUM（30）：4–10 步，搜尋→打開→返回→再操作 ────────────────
MEDIUM = [
    (MAIL, "在gmail搜尋invoice並點開第一封", 4, 80, "搜尋+開啟郵件"),
    (MAIL, "在gmail搜尋發票並點開第二封", 4, 80, "索引選擇"),
    (MAIL, "在gmail打開第一封信然後返回", 4, 70, "開關可回溯"),
    (MAPS, "在地圖搜尋餐廳並點開第一家", 4, 80, "搜尋+詳情"),
    (MAPS, "在地圖搜尋咖啡廳然後返回", 4, 70, "返回首頁"),
    (MAPS, "在地圖搜尋捷運站並切換到大眾運輸", 5, 90, "篩選切換"),
    (VIDEO, "在youtube搜尋周杰倫並點開第一個影片", 4, 80, "搜尋+播放頁"),
    (VIDEO, "在youtube搜尋關鍵字然後往下滑看更多", 5, 90, "滾動"),
    (VIDEO, "在youtube打開第一個影片然後返回", 4, 70, "開關可回溯"),
    (SETTINGS, "進入顯示設定把亮度調高一點", 5, 90, "滑桿操作"),
    (SETTINGS, "進入音效設定然後返回", 4, 70, "二級頁返回"),
    (SETTINGS, "在設定搜尋飛航模式並打開它", 5, 90, "開關"),
    (SETTINGS, "在設定搜尋藍牙並關閉它", 5, 90, "開關"),
    (BROWSER, "在瀏覽器搜尋台北車站並點開第一個結果", 4, 90, "搜尋+結果"),
    (BROWSER, "在瀏覽器開啟一個網站然後返回", 4, 70, "返回"),
    (BROWSER, "在瀏覽器搜尋新聞然後往下滑", 5, 90, "滾動"),
    (MUSIC, "在音樂搜尋晴天並播放第一首", 4, 80, "搜尋+播放"),
    (MUSIC, "在音樂搜尋歌手然後返回", 4, 70, "返回"),
    (FILES, "在檔案管理進入下載項目並打開第一個檔案", 5, 90, "檔案開啟"),
    (FILES, "在檔案管理搜尋pdf並返回", 4, 70, "搜尋+返回"),
    (SOCIAL, "在社群app搜尋關鍵字並點開第一篇", 4, 80, "搜尋+貼文"),
    (SOCIAL, "在社群app把第一篇貼文按讚", 5, 90, "寫入動作（有安全閘門）"),
    (SHOP, "在購物app搜尋耳機並點開第一個商品", 4, 90, "搜尋+詳情"),
    (SHOP, "在購物app搜尋商品然後返回", 4, 70, "返回"),
    (GAME, "打開遊戲並同意開始", 4, 80, "自繪畫面"),
    (GAME, "在遊戲中往上滑查看背包", 5, 90, "上滑動作"),
    (MAPS, "在地圖長按一個位置", 4, 80, "長按"),
    (MAIL, "在gmail下拉重新整理收件匣", 4, 70, "下滑手勢"),
    (BROWSER, "在瀏覽器左右滑切換分頁", 4, 80, "左右滑"),
    (SETTINGS, "在設定清除搜尋框再搜尋wifi", 5, 90, "清除+重輸"),
]

# ── HARD（25）：10–25 步，多層導覽/篩選/干擾 ─────────────────────
HARD = [
    (MAPS, "在地圖搜尋餐廳然後篩選評分最高的一家並點開", 10, 150, "搜尋+篩選"),
    (MAPS, "在地圖搜尋加油站然後切換到衛星檢視再返回", 12, 160, "檢視切換"),
    (MAPS, "在地圖規劃到台北車站的路線並選擇步行", 12, 160, "路線規劃"),
    (MAPS, "在地圖搜尋景點往下滑三次再看評論", 14, 180, "多次滾動"),
    (MAIL, "在gmail搜尋invoice然後切換到促銷分類再點開第一封", 12, 160, "分類切換"),
    (MAIL, "在gmail打開一封郵件往下滑讀完然後標記已讀", 13, 170, "長頁+寫入"),
    (MAIL, "在gmail搜尋附件郵件並打開附件列表", 12, 160, "附件"),
    (VIDEO, "在youtube搜尋料理然後篩選長片再點開第一個", 12, 170, "篩選"),
    (VIDEO, "在youtube打開影片往下滑看留言然後返回", 13, 170, "滾動+返回"),
    (VIDEO, "在youtube搜尋音樂並加入播放清單", 13, 170, "寫入動作"),
    (SETTINGS, "進入應用程式清單找到其中一個app再進去看權限", 14, 180, "多層導覽"),
    (SETTINGS, "在設定搜尋電池並查看用量詳情再返回", 12, 160, "多層"),
    (SETTINGS, "關閉飛航模式再開啟藍牙再返回", 12, 160, "多開關"),
    (BROWSER, "在瀏覽器搜尋台北景點然後打開第一個結果往下滑三次再返回", 15, 190, "長鏈"),
    (BROWSER, "在瀏覽器開三個分頁然後關閉第二個", 14, 180, "分頁管理"),
    (MUSIC, "在音樂搜尋專輯然後點開第一張再播放", 12, 160, "多層"),
    (MUSIC, "在音樂建立播放清單並加入第一首歌", 14, 180, "寫入動作"),
    (FILES, "在檔案管理進入下載項目按大小排序再打開第一個", 13, 170, "排序"),
    (FILES, "在檔案管理搜尋圖片然後滑動瀏覽", 13, 170, "滾動"),
    (SOCIAL, "在社群app搜尋關鍵字打開貼文往下滑看留言再按讚", 15, 190, "長鏈"),
    (SOCIAL, "在社群app打開通知清單再返回首頁", 12, 160, "多層"),
    (SHOP, "在購物app搜尋商品點開第一個加入購物車", 14, 190, "寫入動作"),
    (SHOP, "在購物app搜尋商品篩選價格再點開第一個", 13, 180, "篩選"),
    (GAME, "在遊戲中完成一局教學", 20, 240, "自繪+多步"),
    (GAME, "在遊戲中打開設定調整音量再返回遊戲", 14, 180, "多層"),
]

# ── EXTREME（15）：≥30 步，跨 App / 多階段 / 含干擾 ──────────────
EXTREME = [
    (MAPS, "在地圖搜尋餐廳點開第一家往下滑看評論回到列表再點開第二家再返回首頁", 32, 360, "深層導覽"),
    (MAPS, "在地圖規劃到台北101的路線切換三種交通方式再返回搜尋加油站再點開第一家", 34, 380, "跨功能"),
    (MAIL, "在gmail搜尋invoice點開第一封往下滑讀完返回列表再搜尋發票點開第二封再返回", 32, 360, "重複流程"),
    (MAIL, "在gmail搜尋附件郵件打開第一封打開附件列表返回再切換到寄件備份", 33, 370, "多階段"),
    (VIDEO, "在youtube搜尋料理點開第一個影片往下滑看留言按讚返回再搜尋飲料點開第一個", 32, 370, "跨流程"),
    (VIDEO, "在youtube搜尋音樂打開播放清單加入兩首歌再返回首頁再打開訂閱頁", 34, 380, "多寫入"),
    (SETTINGS, "在設定搜尋藍牙關閉再開啟再進入應用程式清單找到一個app看權限再返回", 32, 360, "多層+開關"),
    (SETTINGS, "在設定關閉飛航模式開啟wifi連接第一個網路再返回搜尋電池看用量再返回", 34, 380, "跨設定"),
    (BROWSER, "在瀏覽器搜尋台北景點打開三個結果分頁切換第二個往下滑三次關閉再返回第一個", 34, 380, "多分頁"),
    (BROWSER, "在瀏覽器搜尋新聞打開第一個往下滑五次按讚再分享取消再返回", 33, 380, "含取消/安全閘門"),
    (MUSIC, "在音樂搜尋晴天播放第一首加入播放清單再搜尋另一首歌播放再返回首頁", 32, 370, "多寫入"),
    (SHOP, "在購物app搜尋耳機篩選價格點開第一個加入購物車再返回搜尋第二個商品加入", 34, 390, "多寫入+安全"),
    (FILES, "在檔案管理進入下載項目開啟第一個檔案返回滑到最底再開啟最後一個檔案", 32, 360, "深層列表"),
    (SOCIAL, "在社群app搜尋關鍵字打開第一篇往下滑看留言按讚返回開啟通知再返回首頁", 33, 370, "長鏈"),
    (GAME, "在遊戲完成教學關卡打開設定調整音量返回再進入背包往上滑查看道具", 35, 400, "自繪+多階段"),
]

TIERS = {"EASY": EASY, "MEDIUM": MEDIUM, "HARD": HARD, "EXTREME": EXTREME}


def main() -> int:
    rows = []
    counters = {t: 0 for t in TIERS}
    for tier, tasks in TIERS.items():
        for (family, cmd, min_steps, timeout_s, notes) in tasks:
            counters[tier] += 1
            prefix = {"EASY": "E", "MEDIUM": "M", "HARD": "H", "EXTREME": "X"}[tier]
            rows.append({
                "id": f"{prefix}{counters[tier]:02d}",
                "tier": tier,
                "app_family": family,
                "cmd": cmd,
                "min_steps": min_steps,
                "timeout_s": timeout_s,
                "notes": notes,
            })

    total = len(rows)
    if total != 100:
        print(f"任務數必須為 100，實際 {total}")
        return 1
    extreme_short = [r for r in rows if r["tier"] == "EXTREME" and r["min_steps"] < 30]
    if extreme_short:
        print("EXTREME 任務必須 >=30 步：", [r["id"] for r in extreme_short])
        return 1

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 100 任務評測集（難度分層；EXTREME >=30 步）\n")
        f.write("id\ttier\tapp_family\tcmd\tmin_steps\ttimeout_s\tnotes\n")
        for r in rows:
            f.write("\t".join(str(r[k]) for k in
                              ("id", "tier", "app_family", "cmd", "min_steps", "timeout_s", "notes")) + "\n")

    print(json.dumps({
        "total": total,
        "by_tier": counters,
        "extreme_min_steps": min(r["min_steps"] for r in rows if r["tier"] == "EXTREME"),
        "out": OUT,
        "bytes": os.path.getsize(OUT),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
