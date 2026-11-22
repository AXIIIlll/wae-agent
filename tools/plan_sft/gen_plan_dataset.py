"""把專案既有的 TSV 資料合成成 planner SFT 資料集（(中文指令, plan JSON)）。

用法：
  python tools/plan_sft/gen_plan_dataset.py --out E:/WAE/datasets/plan_sft

輸出：
  train.jsonl / valid.jsonl   每行 {"goal","selected_app","installed_apps","plan","source"}
  valid_hard.jsonl            手寫難題（不進訓練，只評測）
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from waeplan import ROOT, validate_plan  # noqa: E402

ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "open_source_ai_databases")


def load_tsv(name: str) -> list[list[str]]:
    path = os.path.join(ASSETS, name)
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#"):
                continue
            rows.append(line.split("\t"))
    return rows


# ---------------------------------------------------------------- 資料來源

APPS = []          # (label, category, aliases)
for r in load_tsv("global_tw_cn_app_metadata.tsv"):
    if len(r) < 3:
        continue
    label = r[1].strip()
    cat = r[2].strip()
    aliases = [a.strip() for a in (r[3].split("|") if len(r) > 3 else []) if a.strip()]
    if label:
        APPS.append((label, cat, aliases))

CATS = []          # (name, aliases, purposes)
for r in load_tsv("ui_purpose_category_map.tsv"):
    if len(r) < 4:
        continue
    name = r[1].strip()
    aliases = [a.strip() for a in r[2].split("|") if a.strip()]
    purposes = [p.strip() for p in r[3].split("|") if p.strip()]
    CATS.append((name, aliases, purposes))

TOGGLES = []       # (target, sections)
for r in load_tsv("ui_toggle_section_map.tsv"):
    if len(r) < 2:
        continue
    TOGGLES.append((r[0].strip(), [s.strip() for s in r[1].split("|") if s.strip()]))

TEMPLATES = []     # (id, pattern, steps)
for r in load_tsv("ui_task_templates.tsv"):
    if len(r) < 4 or r[0] != "template":
        continue
    TEMPLATES.append((r[1].strip(), r[2].strip(), r[3].strip()))

APP_LABELS = [a[0] for a in APPS]
BROWSERS = [a[0] for a in APPS if a[1] in ("BROWSER", "GOOGLE_BROWSER")] or ["Chrome"]


# ---------------------------------------------------------------- 計畫樣板

def step(i, do, target=None, verify=None, **kw):
    s = {"id": i, "do": do}
    if target:
        s["target"] = target
    s["verify"] = verify or do[:4]
    s.update(kw)
    return s


def plan(goal_kind, summary, steps):
    return {"version": 1, "goalKind": goal_kind, "summary": summary, "steps": steps}


def plan_launch(app: str):
    return plan("launch", f"開啟{app}", [
        step(1, f"開啟{app}", f"$app:{app}", app),
    ])


def plan_read(cat: str, term: str):
    if cat == term:
        return plan("query", f"到設定查看{term}", [
            step(1, "開啟設定", "$app:設定", "設定"),
            step(2, f"點進{term}", term, term),
            step(3, f"讀取{term}", term, term),
        ])
    return plan("query", f"到設定查看{term}", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, f"點進{cat}", cat, cat),
        step(3, f"點進{term}", term, term),
        step(4, f"讀取{term}", term, term),
    ])


def plan_toggle(section: str, target: str, on: bool):
    verb = "開啟" if on else "關閉"
    return plan("toggle", f"到設定{verb}{target}", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, f"點進{section}", section, section),
        step(3, f"{verb}{target}開關", target, target),
    ])


def plan_search(browser: str, query: str):
    return plan("search", f"用{browser}搜尋{query}", [
        step(1, f"開啟{browser}", f"$app:{browser}", browser),
        step(2, "點搜尋框", "搜尋", "搜尋"),
        step(3, f"輸入{query}並送出", "搜尋", "搜尋結果"),
    ])


def plan_ask(summary: str, question: str, options: list[str]):
    return plan("open_ended", summary, [
        step(1, "詢問必要資訊", None, "使用者已回答", ask=question, options=options),
    ])


# ---------------------------------------------------------------- 改寫

LAUNCH_FORMS = ["打開{}", "開啟{}", "幫我開{}", "我要用{}", "幫我打開{}", "開一下{}", "進{}", "{} 開一下"]
READ_FORMS = ["查一下{}", "看一下{}", "幫我查{}", "我想知道{}", "讀一下{}", "{}是多少", "看{}剩多少", "幫我看{}",
              "幫我看看{}", "現在{}多少", "{} 在哪裡", "我要看{}"]
TOGGLE_ON_FORMS = ["把{}打開", "幫我開{}", "開啟{}", "把{}開起來", "我要開{}"]
TOGGLE_OFF_FORMS = ["把{}關掉", "幫我關{}", "關閉{}", "把{}關起來", "{} 關掉"]

QUERIES = ["台北一零一", "明天的天氣", "高鐵時刻表", "牛肉麵 推薦", "匯率", "停車場", "捷運路線", "附近加油站",
           "台中住宿", "台北到高雄 火車", "iPhone 價格", "颱風 動態", "匯款 手續費", "咖啡廳 營業時間",
           "明天 會下雨嗎", "哪一家 評價最高"]

# ASR 常見同音錯字（模擬語音輸入的雜訊，讓 planner 對錯字有韌性）
NOISE_MAP = [("打開", "大開"), ("車站", "車展"), ("上一頁", "上一夜"), ("設定", "設訂"),
             ("藍牙", "藍芽"), ("螢幕", "屏幕"), ("關掉", "關調"), ("導航", "倒航"),
             ("照片", "照騙"), ("餐廳", "參廳")]


def corrupt(goal: str, rng: random.Random) -> str:
    for a, b in NOISE_MAP:
        if a in goal and rng.random() < 0.8:
            return goal.replace(a, b, 1)
    return goal


def pick_apps(rng: random.Random, need: str | None = None) -> list[str]:
    pool = [a for a in APP_LABELS if a != need]
    n = rng.randint(15, 28)
    picked = rng.sample(pool, min(n, len(pool)))
    if need and need not in picked:
        picked.insert(rng.randrange(len(picked) + 1), need)
    return picked


def emit(rows, goal, selected_app, apps, p, source):
    """只記錄 (goal, plan, source)；installed_apps 在切分後才依 goal 產生變體。"""
    ok, why = validate_plan(p)
    if not ok:
        raise SystemExit(f"invalid plan ({why}) from {source}: {goal}")
    rows.append({
        "goal": goal,
        "selected_app": selected_app,
        "plan": p,
        "source": source,
    })


# ---------------------------------------------------------------- 手寫難題

HARD = [
    ("幫我把剛才拍的那張照片傳給媽媽", plan("open_ended", "從相簿分享照片給聯絡人", [
        step(1, "開啟相簿", "$app:相簿", "相簿"),
        step(2, "選取最近一張照片", "$any_content", "照片"),
        step(3, "按分享", "分享", "分享"),
        step(4, "選擇通訊 App", "LINE", "LINE"),
        step(5, "選擇聯絡人媽媽", "媽媽", "聊天室"),
    ])),
    ("我想看劇", plan("open_ended", "開啟影劇 App 並挑一部劇", [
        step(1, "從已安裝清單挑一個影劇 App 開啟", "短劇", "App 首頁"),
        step(2, "在首頁挑一部劇", "$any_content", "播放器"),
    ])),
    ("幫我訂明天最早的高鐵到台北", plan("open_ended", "查高鐵班次並準備訂票", [
        step(1, "開啟訂票 App", "$app", "首頁"),
        step(2, "選擇高鐵訂票", "高鐵", "訂票"),
        step(3, "出發地輸入目前位置", "出發", "出發"),
        step(4, "目的地輸入台北", "台北", "台北"),
        step(5, "選擇明天最早的班次", "明天", "班次"),
    ])),
    ("幫我訂餐廳", plan_ask("缺少餐廳資訊", "要吃哪一種料理、在哪一區？", ["火鍋", "日式", "小吃"])),
    ("找一首歌來聽", plan("open_ended", "開啟音樂 App 並播放", [
        step(1, "開啟音樂 App", "$app:音樂", "音樂"),
        step(2, "按下播放", "播放", "播放中"),
    ])),
    ("幫我查一下電池剩多少", plan("query", "到設定查看電池", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進電池", "電池", "電池"),
    ])),
    ("用瀏覽器搜尋台北一零一", plan("search", "用瀏覽器搜尋", [
        step(1, "開啟瀏覽器", "$app:瀏覽器", "瀏覽器"),
        step(2, "在搜尋框輸入關鍵字", "搜尋", "搜尋結果"),
    ])),
    ("幫我把藍牙關掉", plan("toggle", "到設定關閉藍牙", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進藍牙", "藍牙", "藍牙"),
        step(3, "關閉藍牙開關", "使用藍牙", "藍牙"),
    ])),
    ("幫我把音量調小一點", plan("settings", "到設定調低音量", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進音效與震動", "音效與震動", "音效"),
        step(3, "調低媒體音量", "媒體音量", "音量"),
    ])),
    ("把螢幕調亮一點", plan("settings", "到設定調高亮度", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進顯示", "顯示", "顯示"),
        step(3, "調高亮度", "亮度", "亮度"),
    ])),
    ("幫我把 Wi-Fi 打開", plan("toggle", "到設定開啟 Wi-Fi", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進連接", "連接", "連接"),
        step(3, "開啟 Wi-Fi 開關", "Wi-Fi", "Wi-Fi"),
    ])),
    ("看一下現在幾點", plan("query", "查看目前時間", [
        step(1, "回到桌面看時間", "$any_content", "時間"),
    ])),
    ("幫我截圖", plan("open_ended", "截取目前畫面", [
        step(1, "按下截圖", "$any_content", "已儲存"),
    ])),
    ("傳訊息給媽媽說我晚點回家", plan("open_ended", "傳訊息給聯絡人", [
        step(1, "開啟通訊 App", "$app:LINE", "LINE"),
        step(2, "搜尋聯絡人媽媽", "搜尋", "媽媽"),
        step(3, "進入聊天室", "媽媽", "聊天室"),
        step(4, "輸入訊息並送出", "輸入訊息", "已送出"),
    ])),
    ("幫我導航到台北車站", plan("open_ended", "地圖導航", [
        step(1, "開啟地圖 App", "$app:地圖", "地圖"),
        step(2, "搜尋台北車站", "搜尋", "台北車站"),
        step(3, "按下路線", "路線", "導航"),
    ])),
    ("把深色模式打開", plan("toggle", "到設定開啟深色模式", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進顯示", "顯示", "顯示"),
        step(3, "開啟深色模式", "深色模式", "深色"),
    ])),
    ("看一下儲存空間還剩多少", plan("query", "查看儲存空間", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進儲存空間", "儲存空間", "儲存空間"),
        step(3, "讀取可用空間", "可用空間", "GB"),
    ])),
    ("幫我開飛航模式", plan("toggle", "到設定開啟飛航模式", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進連接", "連接", "連接"),
        step(3, "開啟飛航模式", "飛航模式", "飛航"),
    ])),
    ("幫我查系統版本", plan("query", "查看系統版本", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進關於", "關於", "關於"),
        step(3, "讀取版本號", "版本號", "版本"),
    ])),
    ("把版本號貼到 LINE 給小明", plan("open_ended", "讀版本號後貼到聊天室", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進關於", "關於", "關於"),
        step(3, "記下版本號", "版本號", "版本"),
        step(4, "返回", "$any_content", "設定"),
        step(5, "開啟 LINE", "$app:LINE", "LINE"),
        step(6, "貼上版本號給小明", "小明", "已送出"),
    ])),
    ("幫我找附近評價最高的咖啡店", plan("open_ended", "地圖搜尋咖啡店", [
        step(1, "開啟地圖 App", "$app:地圖", "地圖"),
        step(2, "搜尋咖啡店", "搜尋", "咖啡"),
        step(3, "依評價排序", "評價", "評論"),
    ])),
    ("我要聽周杰倫的歌", plan("open_ended", "音樂 App 搜尋並播放", [
        step(1, "開啟音樂 App", "$app:音樂", "音樂"),
        step(2, "搜尋周杰倫", "搜尋", "周杰倫"),
        step(3, "播放第一首", "$any_content", "播放中"),
    ])),
    ("幫我把手機靜音", plan("toggle", "開啟靜音", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進音效與震動", "音效與震動", "音效"),
        step(3, "切換為靜音", "靜音", "靜音"),
    ])),
    ("看一下我的應用程式清單", plan("query", "查看已安裝應用程式", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進應用程式", "應用程式", "應用程式"),
    ])),
    ("幫我查明天的天氣", plan("open_ended", "查天氣", [
        step(1, "開啟天氣 App", "$app", "天氣"),
        step(2, "切換到明天", "明天", "天氣"),
    ])),
    ("我手機很卡怎麼辦", plan_ask("需要更多資訊才能處理", "要我先幫你看哪一項？", ["電池用量", "儲存空間", "應用程式"])),
    ("幫我整理桌面", plan_ask("缺少整理方式", "要怎麼整理？", ["分類資料夾", "移除空白頁", "依顏色排序"])),
    ("幫我刪掉所有照片", plan_ask("需要確認刪除範圍", "要刪哪個相簿的照片？", ["最近項目", "所有照片", "截圖"])),
    ("打開上一個 App", plan("open_ended", "回到上一個 App", [
        step(1, "開啟最近使用", "$any_content", "最近使用"),
    ])),
    ("幫我查一下股價", plan("open_ended", "查股價", [
        step(1, "開啟看盤 App", "$app", "首頁"),
        step(2, "搜尋台積電", "搜尋", "台積電"),
        step(3, "讀取股價", "股價", "股價"),
    ])),
    ("叫外送", plan("open_ended", "開啟外送 App 點餐", [
        step(1, "開啟外送 App", "$app", "首頁"),
        step(2, "搜尋想吃的餐點", "搜尋", "餐點"),
        step(3, "加入購物車", "加入", "購物車"),
    ])),
    ("幫我訂飲料", plan("open_ended", "開啟飲料 App 點單", [
        step(1, "開啟飲料 App", "$app", "首頁"),
        step(2, "選擇飲料", "$any_content", "飲料"),
        step(3, "加入購物車", "加入", "購物車"),
    ])),
    ("幫我加行事曆明天下午三點開會", plan("open_ended", "新增行事曆事件", [
        step(1, "開啟行事曆", "$app", "行事曆"),
        step(2, "新增事件", "新增", "新增事件"),
        step(3, "輸入標題開會", "標題", "開會"),
        step(4, "設定時間明天下午三點", "時間", "明天"),
        step(5, "儲存", "儲存", "已儲存"),
    ])),
    ("幫我看今天的新聞", plan("open_ended", "看新聞", [
        step(1, "開啟新聞 App", "$app", "新聞"),
        step(2, "進入頭條", "頭條", "新聞"),
    ])),
    ("幫我掃 QR code", plan("open_ended", "開啟掃碼", [
        step(1, "開啟相機", "$app:相機", "相機"),
        step(2, "選擇掃描 QR code", "掃描", "QR"),
    ])),
    ("幫我把 Wi-Fi 和藍牙都打開", plan("toggle", "開啟 Wi-Fi 與藍牙", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進連接", "連接", "連接"),
        step(3, "開啟 Wi-Fi 開關", "Wi-Fi", "Wi-Fi"),
        step(4, "開啟藍牙開關", "藍牙", "藍牙"),
    ])),
    ("先看電池再看儲存空間", plan("query", "依序查看電池與儲存空間", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進電池", "電池", "電池"),
        step(3, "返回", "$any_content", "設定"),
        step(4, "點進儲存空間", "儲存空間", "儲存空間"),
    ])),
    ("把亮度調高然後打開 Chrome", plan("settings", "調高亮度後開瀏覽器", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進顯示", "顯示", "顯示"),
        step(3, "調高亮度", "亮度", "亮度"),
        step(4, "開啟 Chrome", "$app:Chrome", "Chrome"),
    ])),
    ("幫我看看有沒有更新", plan("query", "檢查系統更新", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進軟體更新", "軟體更新", "更新"),
        step(3, "檢查更新", "檢查", "更新"),
    ])),
    ("幫我關閉通知", plan("toggle", "關閉通知", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進通知", "通知", "通知"),
        step(3, "關閉通知", "通知", "通知"),
    ])),
    ("幫我打開熱點", plan("toggle", "開啟熱點", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進連接", "連接", "連接"),
        step(3, "開啟熱點", "熱點", "熱點"),
    ])),
    ("幫我找最近的 YouBike", plan("open_ended", "地圖找 YouBike", [
        step(1, "開啟地圖 App", "$app:地圖", "地圖"),
        step(2, "搜尋 YouBike", "搜尋", "YouBike"),
    ])),
    ("幫我把字體調大", plan("settings", "調大字体", [
        step(1, "開啟設定", "$app:設定", "設定"),
        step(2, "點進顯示", "顯示", "顯示"),
        step(3, "調整字體大小", "字體大小", "字體"),
    ])),
]


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=r"E:/WAE/datasets/plan_sft")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)

    rows = []

    # 1) 開啟 App（刻意壓低比重，避免模型只會 launch）
    for label, cat, aliases in APPS[:70]:
        names = [label] + [a for a in aliases if len(a) <= 6][:1]
        for name in names:
            for form in LAUNCH_FORMS[:2]:
                goal = form.format(name)
                apps = pick_apps(rng, label)
                sel = label if rng.random() < 0.5 else None
                emit(rows, goal, sel, apps, plan_launch(label), "launch")

    # 2) 設定查詢（讀值）
    for cname, caliases, purposes in CATS:
        for term in purposes[:14]:
            for form in READ_FORMS:
                goal = form.format(term)
                apps = pick_apps(rng, "設定")
                emit(rows, goal, None, apps, plan_read(cname, term), "settings_read")

    # 3) 開關
    for target, sections in TOGGLES:
        for on in (True, False):
            forms = TOGGLE_ON_FORMS if on else TOGGLE_OFF_FORMS
            for form in forms:
                goal = form.format(target)
                apps = pick_apps(rng, "設定")
                section = sections[0] if sections else "設定"
                emit(rows, goal, None, apps, plan_toggle(section, target, on), "toggle")

    # 4) 搜尋
    for browser in BROWSERS[:6]:
        for q in QUERIES:
            for form in ("用{b}搜尋{q}", "幫我用{b}查{q}", "在{b}搜尋{q}"):
                goal = form.format(b=browser, q=q)
                apps = pick_apps(rng, browser)
                emit(rows, goal, browser, apps, plan_search(browser, q), "search")

    # 5) 指定 App 做事（跨 App 的常見形狀）
    for app in ("LINE", "地圖", "Chrome", "音樂", "相簿", "YouTube"):
        if app not in APP_LABELS:
            continue
        for q in QUERIES[:4]:
            apps = pick_apps(rng, app)
            if app == "LINE":
                emit(rows, f"用 LINE 傳訊息給小美說{q}", app, apps,
                     plan("open_ended", "用 LINE 傳訊息", [
                         step(1, "開啟 LINE", f"$app:{app}", "LINE"),
                         step(2, "搜尋聯絡人小美", "搜尋", "小美"),
                         step(3, "進入聊天室輸入訊息", "輸入訊息", "已送出"),
                     ]), "app_action")
            elif app == "地圖":
                emit(rows, f"在地圖上查{q}", app, apps,
                     plan("open_ended", "地圖搜尋地點", [
                         step(1, "開啟地圖", f"$app:{app}", "地圖"),
                         step(2, f"搜尋{q}", "搜尋", q),
                     ]), "app_action")
            elif app in ("Chrome", "YouTube"):
                emit(rows, f"用 {app} 搜尋{q}", app, apps,
                     plan("search", f"用{app}搜尋", [
                         step(1, f"開啟{app}", f"$app:{app}", app),
                         step(2, "點搜尋框", "搜尋", "搜尋"),
                         step(3, f"輸入{q}並送出", "搜尋", "搜尋結果"),
                     ]), "app_action")
            elif app == "相簿":
                emit(rows, "打開相簿看最近的照片", app, apps,
                     plan("open_ended", "開啟相簿看最近照片", [
                         step(1, "開啟相簿", f"$app:{app}", "相簿"),
                         step(2, "進入最近項目", "最近", "照片"),
                     ]), "app_action")
            elif app == "音樂":
                emit(rows, f"用音樂 App 聽{q}", app, apps,
                     plan("open_ended", "音樂搜尋播放", [
                         step(1, "開啟音樂 App", f"$app:{app}", "音樂"),
                         step(2, f"搜尋{q}", "搜尋", q),
                         step(3, "播放第一首", "$any_content", "播放中"),
                     ]), "app_action")

    # 6) 資訊不足 → 反問（教 abstain，不要硬猜）
    ASK_GOALS = [
        ("幫我訂餐廳", "要吃哪一種料理、在哪一區？", ["火鍋", "日式", "小吃"]),
        ("幫我訂飯店", "要住哪個城市、哪幾天？", ["台北", "台中", "高雄"]),
        ("幫我買票", "要買哪一種票？", ["電影票", "車票", "機票"]),
        ("幫我叫車", "要從哪裡到哪裡？", ["目前位置到公司", "公司到家裡"]),
        ("幫我設鬧鐘", "要設幾點？", ["早上七點", "早上八點", "晚上十點"]),
        ("幫我提醒我", "要提醒什麼事、什麼時候？", ["半小時後", "明天早上"]),
        ("幫我傳訊息", "要傳給誰、說什麼？", ["媽媽", "同事", "群組"]),
        ("幫我找地方吃飯", "想在哪一區吃？", ["附近", "公司附近", "捷運站附近"]),
        ("幫我買東西", "要買什麼？", ["日用品", "飲料", "零食"]),
        ("幫我安排明天的行程", "明天幾點開始、要做什麼？", ["早上", "下午", "晚上"]),
    ]
    for goal, q, opts in ASK_GOALS:
        for form in (goal, goal.replace("幫我", "我要"), goal.replace("幫我", "可以幫我")):
            emit(rows, form, None, pick_apps(rng), plan_ask("缺少必要資訊", q, opts), "ask")

    # 7) 手寫難題：1/3 留作評測（絕不進訓練），2/3 進訓練
    hard_all = []
    for goal, p in HARD:
        emit(hard_all, goal, None, [], p, "handwritten")
    rng.shuffle(hard_all)
    n_hard_eval = max(10, len(hard_all) // 3)
    hard_eval = hard_all[:n_hard_eval]
    for r in hard_all[n_hard_eval:]:
        rows.append(r)

    # 8) 去重（同一句只留一份）→ **依 goal 切分**，避免同句同時出現在 train/valid
    uniq, seen = [], set()
    for r in rows:
        if r["goal"] in seen:
            continue
        seen.add(r["goal"])
        uniq.append(r)
    rng.shuffle(uniq)
    n_valid = max(150, int(len(uniq) * 0.08))
    valid_u, train_u = uniq[:n_valid], uniq[n_valid:]

    # 9) 依 goal 產生「不同已安裝 App 清單」的變體（同一句不重複進 valid）
    def need_of(p):
        for s in p["steps"]:
            t = str(s.get("target", ""))
            if t.startswith("$app:") and len(t) > 5:
                return t[5:]
        return None

    def make_row(u, rng_local):
        need = need_of(u["plan"])
        apps = pick_apps(rng_local, need)
        sel = u["selected_app"]
        if sel is None and need and rng_local.random() < 0.4:
            sel = need
        return {
            "goal": u["goal"],
            "selected_app": sel,
            "installed_apps": apps,
            "plan": u["plan"],
            "source": u["source"],
        }

    # 每個來源的變體數不同：query 天生句子多，給少一點變體才不會壓倒其他能力
    VARIANTS_BY_SOURCE = {
        "settings_read": 3,
        "launch": 10,
        "toggle": 10,
        "search": 10,
        "open_ended": 16,
        "ask": 16,
        "handwritten": 12,
        "app_action": 12,
    }

    def n_variants(src: str) -> int:
        return VARIANTS_BY_SOURCE.get(str(src), 5)

    train = [make_row(u, rng) for u in train_u for _ in range(n_variants(u["source"]))]
    valid = [make_row(u, rng) for u in valid_u]
    hard_rows = [make_row(u, rng) for u in hard_eval]

    # 10) ASR 雜訊：對 6% 的訓練列產生錯字版本（同一句、同一計畫）
    noisy = []
    for r in rng.sample(train, max(1, int(len(train) * 0.06))):
        r2 = dict(r)
        g = corrupt(r2["goal"], rng)
        if g != r2["goal"]:
            r2["goal"] = g
            r2["source"] = str(r2.get("source", "")) + "/noise"
            noisy.append(r2)
    train.extend(noisy)
    rng.shuffle(train)

    def dump(path, items):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            for it in items:
                f.write(json.dumps(it, ensure_ascii=False, sort_keys=True) + "\n")

    dump(os.path.join(args.out, "train.jsonl"), train)
    dump(os.path.join(args.out, "valid.jsonl"), valid)
    dump(os.path.join(args.out, "valid_hard.jsonl"), hard_rows)

    from collections import Counter
    kinds = Counter(r["plan"]["goalKind"] for r in train)
    print(f"unique_goals={len(uniq)} train={len(train)} valid={len(valid)} hard={len(hard_rows)}")
    print("train goalKind:", dict(kinds))
    print("out:", args.out)


if __name__ == "__main__":
    main()
