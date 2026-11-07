import os
import re

ASSET_DIR = r"e:\NewAndroidApp\app\src\main\assets\open_source_ai_databases"

def update_asr_file():
    path = os.path.join(ASSET_DIR, "stopwords_and_asr_homophones.tsv")
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()

    new_asr = [
        ("變點極", "並點擊"),
        ("变点极", "並點擊"),
        ("變點擊", "並點擊"),
        ("变点击", "並點擊"),
        ("變點及", "並點擊"),
        ("变点及", "並點擊"),
        ("並點極", "並點擊"),
        ("并点极", "並點擊"),
        ("並點及", "並點擊"),
        ("并点及", "並點擊"),
        ("便點擊", "並點擊"),
        ("便点击", "並點擊"),
        ("便點極", "並點擊"),
        ("便点极", "並點擊"),
        ("冰點擊", "並點擊"),
        ("冰点击", "並點擊"),
        ("病點擊", "並點擊"),
        ("病点击", "並點擊"),
        ("定點擊", "並點擊"),
        ("定点击", "並點擊"),
        ("點極", "點擊"),
        ("点极", "點擊"),
        ("點及", "點擊"),
        ("点及", "點擊"),
        ("點吉", "點擊"),
        ("点吉", "點擊"),
        ("點集", "點擊"),
        ("点集", "點擊"),
        ("電擊", "點擊"),
        ("电击", "點擊"),
        ("典籍", "點擊"),
        ("行車錄線", "行車路線"),
        ("行车录线", "行車路線"),
        ("行車陸線", "行車路線"),
        ("行车陆线", "行車路線"),
        ("規畫路線", "規劃路線"),
        ("规画路线", "規劃路線"),
        ("規畫陸線", "規劃路線"),
        ("搜詢", "搜尋"),
        ("收尋", "搜尋"),
        ("收寻", "搜尋"),
    ]
    existing_asr = set()
    insert_idx = 0
    for idx, line in enumerate(lines):
        if line.startswith("ASR\t"):
            parts = line.split("\t")
            if len(parts) >= 3:
                existing_asr.add(parts[1])
            insert_idx = idx + 1

    to_insert = []
    for wrong, right in new_asr:
        if wrong not in existing_asr:
            to_insert.append(f"ASR\t{wrong}\t{right}")
            existing_asr.add(wrong)

    if to_insert:
        lines[insert_idx:insert_idx] = to_insert
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print(f"Inserted {len(to_insert)} ASR rules into stopwords_and_asr_homophones.tsv")

def build_command_lexicon():
    # Map: term_lower -> (display_term, tag_expr, source, confidence, canonical_or_role)
    lexicon = {}

    # Role -> default icon_hint list (P5: Bidirectional Command Lexicon <-> Icon Hints)
    ROLE_TO_ICON_HINTS = {
        "SWIPE_UP_EXPAND_SHEET": "bottom_sheets|drag_handle|bottom_panel_open|expand_less|keyboard_arrow_up|swipe_up|horizontal_rule",
        "SWIPE_DOWN_COLLAPSE_SHEET": "expand_more|keyboard_arrow_down|arrow_drop_down|swipe_down|bottom_panel_close|collapse_all|unfold_less",
        "EXPAND_FULL_CONTENT": "unfold_more|expand_content|open_in_full|fullscreen|expand_all|expansion_panels",
        "SWIPE_HORIZONTAL_CAROUSEL": "chevron_right|chevron_left|keyboard_arrow_right|keyboard_arrow_left|swipe_left|swipe_right|arrow_forward_ios",
        "TOGGLE_SWITCH_CHECKBOX": "toggle_on|toggle_off|switch|check_box|check_box_outline_blank|radio_button_checked",
        "MAP_DIRECTIONS_ROUTE": "directions|alt_route|directions_car|directions_transit|directions_walk|two_wheeler",
        "MAP_START_NAVIGATION": "navigation|near_me|explore",
        "LOCATION_MAP_PIN": "location_on|pin_drop|my_location|gps_fixed",
        "SEARCH_SUBMIT": "search|manage_search|travel_explore|find_in_page",
        "SEARCH_INPUT_BOX": "search|edit|search_bar",
        "SEND_SUBMIT_CONFIRM": "send|check|done|task_alt|check_circle",
        "EDIT_COMPOSE_POST": "edit|mode_edit|edit_note|draw|create",
        "ATTACH_UPLOAD_CAMERA": "mic|photo_camera|attach_file|upload|image",
        "FILTER_SORT_CHIP": "filter_alt|filter_list|sort|tune",
        "CLOSE_MODAL": "close|cancel|highlight_off|clear",
        "DELETE_REMOVE_CLEAR": "delete|delete_forever|backspace|clear|remove_circle",
        "BACK_UP_NAVIGATION": "arrow_back|arrow_back_ios|west|keyboard_return",
        "MORE_OVERFLOW_MENU": "more_vert|more_horiz|menu|MenuButton",
        "SETTINGS_PREFERENCES": "settings|tune|build|manage_accounts",
        "REFRESH_SYNC_RELOAD": "refresh|sync|autorenew|cached",
        "LIKE_FAVORITE_BOOKMARK": "bookmark|bookmark_border|favorite|star|thumb_up",
        "SHARE_FORWARD": "share|ios_share|reply|forward",
        "COMMENT_REPLY": "chat|comment|forum|rate_review",
        "CALL_PHONE_CONTACT": "call|phone|phone_in_talk|contact_phone",
        "OPEN_EXTERNAL_WEBSITE": "public|language|open_in_new|launch",
        "COPY_PASTE_SELECT": "content_copy|content_paste|link",
        "PLAY_PAUSE_MEDIA": "play_arrow|pause|play_circle|volume_up",
        "ADD_CREATE_NEW": "add|add_circle|add_box|library_add",
        "ADD_TO_CART": "shopping_cart|add_shopping_cart|shopping_bag",
        "CHECKOUT_BUY_NOW": "shopping_cart_checkout|credit_card|payments|shopping_bag",
        "DOWNLOAD_SAVE_OFFLINE": "download|file_download|save_alt",
    }

    def put_entry(term, tag_expr, source, conf, meta="", overwrite=False, icon_hint=""):
        t = term.strip()
        if not t or len(t) > 28:
            return
        k = t.lower()
        if k in lexicon and not overwrite:
            return
        resolved_hint = icon_hint if icon_hint else ROLE_TO_ICON_HINTS.get(meta, "")
        lexicon[k] = (t, tag_expr, source, f"{conf:.2f}", meta, resolved_hint)

    # =========================================================================
    # 1. L1 自動打標 (Source = 'A')
    # =========================================================================
    # 1A. App 別名庫 (global_tw_cn_app_metadata.tsv) -> A
    app_path = os.path.join(ASSET_DIR, "global_tw_cn_app_metadata.tsv")
    with open(app_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                continue
            pkg, label, cat, aliases_str = parts[0], parts[1], parts[2], parts[3]
            put_entry(label, "A", "A", 0.99, label, overwrite=True)
            for al in aliases_str.split("|"):
                al = al.strip()
                if al:
                    put_entry(al, "A", "A", 0.98, label)

    # Extra common app aliases
    extra_apps = [
        ("google地圖", "Google 地圖"), ("google 地圖", "Google 地圖"), ("google地图", "Google 地圖"),
        ("谷歌地圖", "Google 地圖"), ("google maps", "Google 地圖"), ("google map", "Google 地圖"),
        ("youtube", "YouTube"), ("yt", "YouTube"), ("spotify", "Spotify"), ("line", "LINE"),
        ("賴", "LINE"), ("chrome", "Chrome"), ("google瀏覽器", "Chrome"), ("瀏覽器", "Chrome"),
        ("gmail", "Gmail"), ("信箱", "Gmail"), ("備忘錄", "Google Keep"), ("筆記本", "Google Keep"),
        ("記事本", "Google Keep"), ("行事曆", "Google 日曆"), ("日曆", "Google 日曆"),
        ("時鐘", "時鐘"), ("鬧鐘", "時鐘"), ("計算機", "計算機"), ("相簿", "Google 相簿"),
        ("蝦皮", "蝦皮購物"), ("蝦皮購物", "蝦皮購物"), ("momo", "momo購物網"),
        ("高德地圖", "高德地圖"), ("百度地圖", "百度地圖"), ("小紅書", "小紅書"),
        ("嗶哩嗶哩", "嗶哩嗶哩"), ("b站", "嗶哩嗶哩"), ("微信", "微信"), ("wechat", "微信")
    ]
    for al, canon in extra_apps:
        put_entry(al, "A", "A", 0.99, canon, overwrite=True)

    # 1B. UI 動作本體 28 類 (cilin_hownet_antonym_mutual_exclusion_100k.tsv UI_ONTOLOGY) -> B
    mutex_path = os.path.join(ASSET_DIR, "cilin_hownet_antonym_mutual_exclusion_100k.tsv")
    with open(mutex_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("UI_ONTOLOGY\t"):
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                continue
            role = parts[2]
            terms = parts[3].split("|")
            for t in terms:
                t = t.strip()
                if not t:
                    continue
                # Map UI ontology terms to B (target button) or multi-sense if also a verb
                put_entry(t, "B", "A", 0.95, role)

    # 1C. Material Icons (google_material_icons_full_semantics.tsv) -> B
    icons_path = os.path.join(ASSET_DIR, "google_material_icons_full_semantics.tsv")
    generic_prefixes = {"介面圖標", "界面图标", "地圖與地點", "地图与地点", "裝置狀態", "设备状态", "社群分享", "商務金融", "商务金融", "智慧家庭", "智能家居", "檔案資料夾", "文件文件夹"}
    with open(icons_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            icon_name, cats, role, tw_str, cn_str = parts[0], parts[1], parts[2], parts[3], parts[4]
            if role != "GENERAL_UI_ICON":
                for term in (tw_str.split("|") + cn_str.split("|")):
                    term = term.strip()
                    if term and term not in generic_prefixes and len(term) >= 2:
                        put_entry(term, "B", "A", 0.92, role)

    # =========================================================================
    # 2. L2 半自動打標 (Source = 'S')：AOSP Android 14 UI & 詞林動詞同義群擴展
    # =========================================================================
    aosp_path = os.path.join(ASSET_DIR, "aosp_seq2act_ui_settings_lexicon.tsv")
    with open(aosp_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 5:
                continue
            res_key = parts[1].strip()
            cat = parts[2].strip()
            tw = parts[3].strip().strip('"')
            cn = parts[4].strip().strip('"')
            for term in (tw, cn):
                if not term or len(term) > 12 or len(term) < 2:
                    continue
                if any(p in term for p in ["？", "?", "！", "!", "…", "...", "：", ":"]):
                    continue
                if cat in ("NETWORK_CONNECTIVITY", "DISPLAY_VISUAL", "SOUND_NOTIFICATION", "SECURITY_PRIVACY_LOCATION", "BATTERY_POWER"):
                    if any(w in term for w in ["藍牙", "蓝牙", "Wi-Fi", "WLAN", "熱點", "热点", "漫遊", "漫游", "旋轉", "旋转", "深色", "暗色", "夜覽", "夜览", "護眼", "护眼", "勿擾", "勿扰", "飛航", "飞行", "省電", "省电", "定位", "位置", "震動", "振动", "靜音", "静音", "自動", "自动"]):
                        put_entry(term, "P>B", "S", 0.88, f"AOSP:{cat}")
                    else:
                        put_entry(term, "B>P", "S", 0.85, f"AOSP:{cat}")
                elif cat == "CORE_ACTION_CONTROL":
                    put_entry(term, "c>B", "S", 0.90, f"AOSP:{cat}")
                else:
                    put_entry(term, "B", "S", 0.82, f"AOSP:{cat}")

    # L2 Cilin Seed Verb Expansion
    cilin_path = os.path.join(ASSET_DIR, "hit_cilin_synonyms_expanded.tsv")
    seed_to_tag = {
        "點擊": ("c", "CILIN_TAP"),
        "點選": ("c", "CILIN_TAP"),
        "按壓": ("c", "CILIN_TAP"),
        "觸摸": ("c", "CILIN_TAP"),
        "搜尋": ("s+Q>c+S>B", "CILIN_SEARCH"),
        "檢索": ("s+Q>c+S", "CILIN_SEARCH"),
        "查詢": ("s+Q>c+S>B", "CILIN_SEARCH"),
        "查找": ("s+Q>c+S>B", "CILIN_SEARCH"),
        "輸入": ("t+Q>I", "CILIN_INPUT"),
        "填寫": ("t+Q", "CILIN_INPUT"),
        "鍵入": ("t+Q", "CILIN_INPUT"),
        "返回": ("b>B", "CILIN_BACK"),
        "後退": ("b>B", "CILIN_BACK"),
        "滑動": ("r", "CILIN_SCROLL"),
        "捲動": ("r", "CILIN_SCROLL"),
        "播放": ("p>c+S>B", "CILIN_PLAY"),
        "暫停": ("p>c+S>B", "CILIN_PLAY"),
        "分享": ("g>c+S>B", "CILIN_SHARE"),
        "傳送": ("g>c+S>B", "CILIN_SHARE"),
        "發送": ("g>c+S>B", "CILIN_SHARE"),
        "轉發": ("g>c+S>B", "CILIN_SHARE"),
        "擷取": ("x", "CILIN_EXTRACT"),
        "提取": ("x", "CILIN_EXTRACT"),
        "複製": ("x>c+S>B", "CILIN_EXTRACT"),
        "啟動": ("o+A>c+S", "CILIN_OPEN"),
        "關閉": ("w+W>c+S>B", "CILIN_TOGGLE"),
        "停用": ("w+W>c+S>B", "CILIN_TOGGLE"),
    }
    with open(cilin_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or not line[7:8] == "=":
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            code = parts[0]
            words = [w.strip() for w in parts[1].split("|") if w.strip()]
            matched_tag = None
            for seed, (tag_expr, role_note) in seed_to_tag.items():
                if seed in words:
                    matched_tag = (tag_expr, role_note)
                    break
            if matched_tag:
                for w in words:
                    if 2 <= len(w) <= 6:
                        put_entry(w, matched_tag[0], "S", 0.86, f"{matched_tag[1]}:{code}")

    # =========================================================================
    # 3. L3 人工精標與多意思條件規則 (Source = 'M', Highest Priority Overwrite)
    # =========================================================================
    l3_manual_entries = [
        # --- 多意思核心動詞 (Multi-Sense Verbs with Context Conditions) ---
        ("開啟", "o+A>w+W>c+S", "M", 0.98, "MULTI_OPEN_TOGGLE_TAP"),
        ("开启", "o+A>w+W>c+S", "M", 0.98, "MULTI_OPEN_TOGGLE_TAP"),
        ("打開", "o+A>w+W>c+S", "M", 0.98, "MULTI_OPEN_TOGGLE_TAP"),
        ("打开", "o+A>w+W>c+S", "M", 0.98, "MULTI_OPEN_TOGGLE_TAP"),
        ("開", "o+A>w+W>c+S", "M", 0.92, "MULTI_OPEN_TOGGLE_TAP"),
        ("關", "w+W>c+S", "M", 0.94, "MULTI_TOGGLE_TAP"),
        ("關閉", "w+W>c+S>B", "M", 0.98, "CLOSE_MODAL"),
        ("关闭", "w+W>c+S>B", "M", 0.98, "CLOSE_MODAL"),
        ("關掉", "w+W>c+S", "M", 0.98, "CLOSE_MODAL"),
        ("关掉", "w+W>c+S", "M", 0.98, "CLOSE_MODAL"),
        ("啟用", "w+W>c+S", "M", 0.97, "TOGGLE_ON"),
        ("启用", "w+W>c+S", "M", 0.97, "TOGGLE_ON"),
        ("停用", "w+W>c+S", "M", 0.97, "TOGGLE_OFF"),
        ("切換", "w+W>o+A>c+S", "M", 0.96, "TOGGLE_SWITCH"),
        ("切换", "w+W>o+A>c+S", "M", 0.96, "TOGGLE_SWITCH"),
        ("切換到", "o+A>c+S", "M", 0.97, "LAUNCH_APP"),
        ("切换到", "o+A>c+S", "M", 0.97, "LAUNCH_APP"),
        ("切到", "o+A>c+S", "M", 0.96, "LAUNCH_APP"),
        ("進入", "o+A>c+S", "M", 0.96, "OPEN_OR_TAP"),
        ("进入", "o+A>c+S", "M", 0.96, "OPEN_OR_TAP"),
        ("前往", "o+A>c+S", "M", 0.95, "OPEN_OR_TAP"),
        ("啟動", "o+A>c+S", "M", 0.98, "LAUNCH_APP"),
        ("启动", "o+A>c+S", "M", 0.98, "LAUNCH_APP"),
        ("查看", "c+S>s+Q", "M", 0.97, "TAP_OR_SEARCH"),
        ("看看", "c+S>s+Q", "M", 0.94, "TAP_OR_SEARCH"),
        ("檢視", "c+S>s+Q", "M", 0.95, "TAP_OR_SEARCH"),
        ("搜尋", "s+Q>c+S>B", "M", 0.99, "SEARCH_SUBMIT"),
        ("搜索", "s+Q>c+S>B", "M", 0.99, "SEARCH_SUBMIT"),
        ("查詢", "s+Q>c+S>B", "M", 0.99, "SEARCH_SUBMIT"),
        ("查询", "s+Q>c+S>B", "M", 0.99, "SEARCH_SUBMIT"),
        ("查找", "s+Q>c+S>B", "M", 0.99, "SEARCH_SUBMIT"),
        ("查一下", "s+Q>c+S", "M", 0.98, "SEARCH_SUBMIT"),
        ("搜一下", "s+Q>c+S", "M", 0.98, "SEARCH_SUBMIT"),
        ("找一下", "s+Q>c+S", "M", 0.98, "SEARCH_SUBMIT"),
        ("去搜", "s+Q", "M", 0.98, "SEARCH_SUBMIT"),
        ("去查", "s+Q", "M", 0.98, "SEARCH_SUBMIT"),
        ("上網查", "s+Q", "M", 0.98, "SEARCH_SUBMIT"),
        ("查", "s+Q>c+S", "M", 0.95, "SEARCH_SUBMIT"),
        ("搜", "s+Q>c+S", "M", 0.95, "SEARCH_SUBMIT"),
        ("找", "s+Q>c+S", "M", 0.94, "SEARCH_SUBMIT"),
        ("search", "s+Q>c+S>B", "M", 0.98, "SEARCH_SUBMIT"),
        ("find", "s+Q>c+S>B", "M", 0.96, "SEARCH_SUBMIT"),

        # --- 純點擊動作 (c) ---
        ("點擊", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("点击", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("點選", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("点选", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("點開", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("点开", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("點進", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("点进", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("點入", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("点入", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("點一下", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("点一下", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("按一下", "c", "M", 1.00, "EXPLICIT_TAP"),
        ("按下", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("輕觸", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("点按", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("點按", "c", "M", 0.99, "EXPLICIT_TAP"),
        ("按個", "c", "M", 0.98, "EXPLICIT_TAP"),
        ("點", "c>p", "M", 0.95, "EXPLICIT_TAP"),
        ("按", "c", "M", 0.96, "EXPLICIT_TAP"),
        ("選擇", "c", "M", 0.96, "EXPLICIT_TAP"),
        ("选择", "c", "M", 0.96, "EXPLICIT_TAP"),
        ("選取", "c", "M", 0.96, "EXPLICIT_TAP"),
        ("挑一個", "c", "M", 0.96, "EXPLICIT_TAP"),
        ("選一個", "c", "M", 0.96, "EXPLICIT_TAP"),
        ("點第一個", "c", "M", 0.99, "SELECT_FIRST"),
        ("點第一首", "c", "M", 0.99, "SELECT_FIRST"),
        ("點擊第一個", "c", "M", 0.99, "SELECT_FIRST"),
        ("點進網站", "c", "M", 0.99, "OPEN_EXTERNAL_WEBSITE"),
        ("點進官網", "c", "M", 0.99, "OPEN_EXTERNAL_WEBSITE"),
        ("進入網站", "c", "M", 0.99, "OPEN_EXTERNAL_WEBSITE"),
        ("進入官網", "c", "M", 0.99, "OPEN_EXTERNAL_WEBSITE"),

        # --- 純文字輸入動作 (t) ---
        ("輸入", "t+Q>I", "M", 1.00, "INPUT_TEXT"),
        ("输入", "t+Q>I", "M", 1.00, "INPUT_TEXT"),
        ("打字", "t+Q", "M", 0.99, "INPUT_TEXT"),
        ("填入", "t+Q", "M", 0.99, "INPUT_TEXT"),
        ("填寫", "t+Q", "M", 0.98, "INPUT_TEXT"),
        ("填写", "t+Q", "M", 0.98, "INPUT_TEXT"),
        ("寫下", "t+Q", "M", 0.98, "INPUT_TEXT"),
        ("寫上", "t+Q", "M", 0.98, "INPUT_TEXT"),
        ("貼上", "t>c+S>B", "M", 0.97, "COPY_PASTE_SELECT"),
        ("粘贴", "t>c+S>B", "M", 0.97, "COPY_PASTE_SELECT"),

        # --- 返回 (b) / 滑動 (r) / 長按 (l) / 雙擊 (d) ---
        ("返回", "b>B", "M", 1.00, "BACK_UP_NAVIGATION"),
        ("上一頁", "b>B", "M", 1.00, "BACK_UP_NAVIGATION"),
        ("上一页", "b>B", "M", 1.00, "BACK_UP_NAVIGATION"),
        ("回上一頁", "b>B", "M", 1.00, "BACK_UP_NAVIGATION"),
        ("回上頁", "b>B", "M", 1.00, "BACK_UP_NAVIGATION"),
        ("後退", "b>B", "M", 0.98, "BACK_UP_NAVIGATION"),
        ("下滑", "r", "M", 1.00, "SCROLL_DOWN"),
        ("往下滑", "r", "M", 1.00, "SCROLL_DOWN"),
        ("向下滑", "r", "M", 1.00, "SCROLL_DOWN"),
        ("向下滑動", "r", "M", 1.00, "SCROLL_DOWN"),
        ("上滑", "r", "M", 1.00, "SCROLL_UP"),
        ("往上滑", "r", "M", 1.00, "SCROLL_UP"),
        ("向上滑", "r", "M", 1.00, "SCROLL_UP"),
        ("向上滑動", "r", "M", 1.00, "SCROLL_UP"),
        ("左滑", "r", "M", 1.00, "SWIPE_LEFT"),
        ("往左滑", "r", "M", 1.00, "SWIPE_LEFT"),
        ("向左滑", "r", "M", 1.00, "SWIPE_LEFT"),
        ("右滑", "r", "M", 1.00, "SWIPE_RIGHT"),
        ("往右滑", "r", "M", 1.00, "SWIPE_RIGHT"),
        ("向右滑", "r", "M", 1.00, "SWIPE_RIGHT"),
        ("長按", "l", "M", 1.00, "LONG_PRESS"),
        ("长按", "l", "M", 1.00, "LONG_PRESS"),
        ("按住", "l", "M", 0.99, "LONG_PRESS"),
        ("雙擊", "d", "M", 1.00, "DOUBLE_TAP"),
        ("双击", "d", "M", 1.00, "DOUBLE_TAP"),
        ("點兩下", "d", "M", 1.00, "DOUBLE_TAP"),
        ("連點兩下", "d", "M", 1.00, "DOUBLE_TAP"),
        ("連按兩下", "d", "M", 1.00, "DOUBLE_TAP"),

        # --- 播放 (p) / 擷取 (x) / 傳送分享 (g) ---
        ("播放", "p>c+S>B", "M", 0.99, "PLAY_PAUSE_MEDIA"),
        ("暫停", "p>c+S>B", "M", 0.99, "PLAY_PAUSE_MEDIA"),
        ("暂停", "p>c+S>B", "M", 0.99, "PLAY_PAUSE_MEDIA"),
        ("聽歌", "p>s+Q", "M", 0.98, "PLAY_PAUSE_MEDIA"),
        ("放歌", "p>s+Q", "M", 0.98, "PLAY_PAUSE_MEDIA"),
        ("點歌", "p>s+Q", "M", 0.98, "PLAY_PAUSE_MEDIA"),
        ("查歌", "p>s+Q", "M", 0.98, "PLAY_PAUSE_MEDIA"),
        ("播放第一首", "p>c", "M", 0.99, "PLAY_FIRST"),
        ("抓出來", "x", "M", 1.00, "EXTRACT_TO_MEMORY"),
        ("抓出来", "x", "M", 1.00, "EXTRACT_TO_MEMORY"),
        ("找出來", "x", "M", 0.98, "EXTRACT_TO_MEMORY"),
        ("提取出來", "x", "M", 1.00, "EXTRACT_TO_MEMORY"),
        ("擷取出來", "x", "M", 1.00, "EXTRACT_TO_MEMORY"),
        ("複製出來", "x", "M", 1.00, "EXTRACT_TO_MEMORY"),
        ("复制出来", "x", "M", 1.00, "EXTRACT_TO_MEMORY"),
        ("抄下來", "x", "M", 0.98, "EXTRACT_TO_MEMORY"),
        ("記下來", "x>t", "M", 0.98, "NOTE_SAVE"),
        ("存到", "g>t", "M", 0.98, "NOTE_SAVE"),
        ("記到", "g>t", "M", 0.98, "NOTE_SAVE"),
        ("寫進", "g>t", "M", 0.98, "NOTE_SAVE"),
        ("傳給", "g", "M", 1.00, "SHARE_FORWARD"),
        ("傳到", "g", "M", 0.99, "SHARE_FORWARD"),
        ("傳送給", "g", "M", 1.00, "SHARE_FORWARD"),
        ("發給", "g", "M", 1.00, "SHARE_FORWARD"),
        ("分享給", "g", "M", 1.00, "SHARE_FORWARD"),
        ("分享", "B>c+S>g", "M", 0.98, "SHARE_FORWARD"),
        ("轉發", "B>c+S>g", "M", 0.98, "SHARE_FORWARD"),

        # --- 連接詞 (1 = 依序接下一步, 2 = 條件/結果) ---
        ("然後再", "1", "M", 1.00, "CONN_SEQ"),
        ("然后再", "1", "M", 1.00, "CONN_SEQ"),
        ("然後", "1", "M", 1.00, "CONN_SEQ"),
        ("然后", "1", "M", 1.00, "CONN_SEQ"),
        ("接著再", "1", "M", 1.00, "CONN_SEQ"),
        ("接着再", "1", "M", 1.00, "CONN_SEQ"),
        ("接著", "1", "M", 1.00, "CONN_SEQ"),
        ("接着", "1", "M", 1.00, "CONN_SEQ"),
        ("再來", "1", "M", 1.00, "CONN_SEQ"),
        ("再来", "1", "M", 1.00, "CONN_SEQ"),
        ("之後再", "1", "M", 1.00, "CONN_SEQ"),
        ("之后再", "1", "M", 1.00, "CONN_SEQ"),
        ("之後", "1>2", "M", 0.99, "CONN_SEQ"),
        ("之后", "1>2", "M", 0.99, "CONN_SEQ"),
        ("最後再", "1", "M", 1.00, "CONN_SEQ"),
        ("最后再", "1", "M", 1.00, "CONN_SEQ"),
        ("最後", "1", "M", 1.00, "CONN_SEQ"),
        ("最后", "1", "M", 1.00, "CONN_SEQ"),
        ("並且", "1", "M", 1.00, "CONN_SEQ"),
        ("并且", "1", "M", 1.00, "CONN_SEQ"),
        ("並", "1", "M", 1.00, "CONN_SEQ"),
        ("并", "1", "M", 1.00, "CONN_SEQ"),
        ("以及", "1", "M", 0.99, "CONN_SEQ"),
        ("順便", "1", "M", 0.98, "CONN_SEQ"),
        ("顺便", "1", "M", 0.98, "CONN_SEQ"),
        ("再", "1", "M", 0.96, "CONN_SEQ"),
        ("查完後", "2>1", "M", 1.00, "CONN_COND"),
        ("查完后", "2>1", "M", 1.00, "CONN_COND"),
        ("搜尋完後", "2>1", "M", 1.00, "CONN_COND"),
        ("搜尋後", "2>1", "M", 1.00, "CONN_COND"),
        ("複製結果後", "2>1", "M", 1.00, "CONN_COND"),
        ("複製後", "2>1", "M", 1.00, "CONN_COND"),

        # --- 關鍵目標名詞：地圖與導航按鈕 (B) vs 輸入框 (I) ---
        ("路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("路线", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("行車路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("行车路线", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("駕車路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("驾车路线", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("開車路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("开车路线", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("騎車路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("步行路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("大眾運輸路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("規劃路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("规划路线", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("路線規劃", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("路线规划", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("查看路線", "c+S>B", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("查看路线", "c+S>B", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("導航路線", "B>c+S", "M", 1.00, "MAP_DIRECTIONS_ROUTE"),
        ("怎麼走", "B>c+S", "M", 0.99, "MAP_DIRECTIONS_ROUTE"),
        ("开始导航", "B>c+S", "M", 1.00, "MAP_START_NAVIGATION"),
        ("開始導航", "B>c+S", "M", 1.00, "MAP_START_NAVIGATION"),
        ("開始", "B>c+S", "M", 0.98, "MAP_START_NAVIGATION"),
        ("出發", "B>c+S", "M", 0.98, "MAP_START_NAVIGATION"),
        ("導航", "B>c+S>A", "M", 0.98, "MAP_START_NAVIGATION"),
        ("儲存", "B>c+S", "M", 0.99, "LIKE_FAVORITE_BOOKMARK"),
        ("儲存地點", "B>c+S", "M", 1.00, "LIKE_FAVORITE_BOOKMARK"),
        ("收藏", "B>c+S", "M", 0.99, "LIKE_FAVORITE_BOOKMARK"),
        ("按讚", "c+S>B", "M", 1.00, "LIKE_FAVORITE_BOOKMARK"),
        ("讚", "B>c+S", "M", 0.99, "LIKE_FAVORITE_BOOKMARK"),
        ("通話", "B>c+S", "M", 0.99, "CALL_PHONE_CONTACT"),
        ("網站", "B>c+S", "M", 0.98, "OPEN_EXTERNAL_WEBSITE"),
        ("官網", "B>c+S", "M", 0.98, "OPEN_EXTERNAL_WEBSITE"),
        ("評論", "B>c+S", "M", 0.98, "COMMENT_REPLY"),
        ("加入購物車", "B>c+S", "M", 1.00, "ADD_TO_CART"),
        ("立即購買", "B>c+S", "M", 1.00, "CHECKOUT_BUY_NOW"),
        ("結帳", "B>c+S", "M", 1.00, "CHECKOUT_BUY_NOW"),
        ("搜尋框", "I", "M", 1.00, "SEARCH_INPUT_BOX"),
        ("搜索框", "I", "M", 1.00, "SEARCH_INPUT_BOX"),
        ("輸入框", "I", "M", 1.00, "SEARCH_INPUT_BOX"),
        ("输入框", "I", "M", 1.00, "SEARCH_INPUT_BOX"),
        ("搜尋欄", "I", "M", 1.00, "SEARCH_INPUT_BOX"),
        ("文字框", "I", "M", 1.00, "SEARCH_INPUT_BOX"),
        ("設定頁", "P", "M", 0.99, "SETTINGS_PAGE"),
        ("首頁", "P>B", "M", 0.99, "HOME_PAGE"),

        # --- P5: 抽屜展開、手勢滑動、全螢幕展開、切換開關與圖標雙向打通詞條 ---
        ("展開詳情", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("展开详情", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("上滑展開", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("上滑展开", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("展開抽屜", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("展开抽屉", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("拉開抽屜", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("拉開面板", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("展開底部面板", "r>c+S>B", "M", 1.00, "SWIPE_UP_EXPAND_SHEET"),
        ("底部抽屜", "B>r>c", "M", 0.99, "SWIPE_UP_EXPAND_SHEET"),
        ("抽屜把手", "B>r>c", "M", 0.99, "SWIPE_UP_EXPAND_SHEET"),
        ("回到頂部", "c+S>r>B", "M", 0.99, "SWIPE_UP_EXPAND_SHEET"),
        ("回到顶部", "c+S>r>B", "M", 0.99, "SWIPE_UP_EXPAND_SHEET"),
        ("收合面板", "c+S>r>B", "M", 1.00, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("收起抽屜", "c+S>r>B", "M", 1.00, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("下滑收合", "r>c+S>B", "M", 1.00, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("摺疊面板", "c+S>r>B", "M", 0.99, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("下拉選單", "B>c+S", "M", 1.00, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("下拉菜单", "B>c+S", "M", 1.00, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("展開下拉", "c+S>B", "M", 0.99, "SWIPE_DOWN_COLLAPSE_SHEET"),
        ("展開全文", "c+S>r>B", "M", 1.00, "EXPAND_FULL_CONTENT"),
        ("展开全文", "c+S>r>B", "M", 1.00, "EXPAND_FULL_CONTENT"),
        ("展開完整內容", "c+S>r>B", "M", 1.00, "EXPAND_FULL_CONTENT"),
        ("展開全部", "c+S>r>B", "M", 0.99, "EXPAND_FULL_CONTENT"),
        ("全螢幕", "B>c+S", "M", 0.99, "EXPAND_FULL_CONTENT"),
        ("全屏", "B>c+S", "M", 0.99, "EXPAND_FULL_CONTENT"),
        ("下一頁", "c+S>r>B", "M", 0.99, "SWIPE_HORIZONTAL_CAROUSEL"),
        ("下一页", "c+S>r>B", "M", 0.99, "SWIPE_HORIZONTAL_CAROUSEL"),
        ("向右翻頁", "r>c+S>B", "M", 0.99, "SWIPE_HORIZONTAL_CAROUSEL"),
        ("向左翻頁", "r>c+S>B", "M", 0.99, "SWIPE_HORIZONTAL_CAROUSEL"),
        ("勾選", "w>c+S>B", "M", 1.00, "TOGGLE_SWITCH_CHECKBOX"),
        ("勾选", "w>c+S>B", "M", 1.00, "TOGGLE_SWITCH_CHECKBOX"),
        ("打勾", "w>c+S>B", "M", 1.00, "TOGGLE_SWITCH_CHECKBOX"),
        ("取消勾選", "w>c+S>B", "M", 1.00, "TOGGLE_SWITCH_CHECKBOX"),
        ("核取方塊", "B>w>c", "M", 0.99, "TOGGLE_SWITCH_CHECKBOX"),
        ("切換開關", "B>w>c", "M", 0.99, "TOGGLE_SWITCH_CHECKBOX"),
        ("清除文字", "c+S>B", "M", 1.00, "DELETE_REMOVE_CLEAR"),
        ("清空輸入", "c+S>B", "M", 1.00, "DELETE_REMOVE_CLEAR"),
        ("關閉彈窗", "b>c+S>B", "M", 1.00, "CLOSE_MODAL"),

        # --- 語音辨識錯字變體 (e = 語音錯字變體, 附正確詞於第5欄) ---
        ("變點極", "e", "M", 1.00, "並點擊"),
        ("变点极", "e", "M", 1.00, "並點擊"),
        ("變點擊", "e", "M", 1.00, "並點擊"),
        ("变点击", "e", "M", 1.00, "並點擊"),
        ("變點及", "e", "M", 1.00, "並點擊"),
        ("变点及", "e", "M", 1.00, "並點擊"),
        ("並點極", "e", "M", 1.00, "並點擊"),
        ("并点极", "e", "M", 1.00, "並點擊"),
        ("並點及", "e", "M", 1.00, "並點擊"),
        ("并点及", "e", "M", 1.00, "並點擊"),
        ("便點擊", "e", "M", 1.00, "並點擊"),
        ("便点击", "e", "M", 1.00, "並點擊"),
        ("便點極", "e", "M", 1.00, "並點擊"),
        ("便点极", "e", "M", 1.00, "並點擊"),
        ("冰點擊", "e", "M", 1.00, "並點擊"),
        ("病點擊", "e", "M", 1.00, "並點擊"),
        ("定點擊", "e", "M", 1.00, "並點擊"),
        ("點極", "e", "M", 1.00, "點擊"),
        ("点极", "e", "M", 1.00, "點擊"),
        ("點及", "e", "M", 1.00, "點擊"),
        ("点及", "e", "M", 1.00, "點擊"),
        ("點吉", "e", "M", 1.00, "點擊"),
        ("點集", "e", "M", 1.00, "點擊"),
        ("電擊", "e", "M", 1.00, "點擊"),
        ("典籍", "e", "M", 1.00, "點擊"),
        ("行車錄線", "e", "M", 1.00, "行車路線"),
        ("行車陸線", "e", "M", 1.00, "行車路線"),
        ("規畫路線", "e", "M", 1.00, "規劃路線"),
        ("規畫陸線", "e", "M", 1.00, "規劃路線"),
        ("搜詢", "e", "M", 1.00, "搜尋"),
        ("收尋", "e", "M", 1.00, "搜尋"),
        ("買茶歌", "e", "M", 1.00, "查歌"),
        ("台灣高帖", "e", "M", 1.00, "台灣高鐵"),
        ("深色模是", "e", "M", 1.00, "深色模式"),
        ("護眼模是", "e", "M", 1.00, "護眼模式"),
        ("勿擾模是", "e", "M", 1.00, "勿擾模式"),
        ("飛航模是", "e", "M", 1.00, "飛航模式"),
        ("省電模是", "e", "M", 1.00, "省電模式"),

        # --- 雜訊/停用單字排除 (z) ---
        ("路", "z", "M", 1.00, "NOISE_SINGLE_CHAR"),
        ("道", "z", "M", 1.00, "NOISE_SINGLE_CHAR"),
        ("線", "z", "M", 1.00, "NOISE_SINGLE_CHAR"),
        ("的", "z", "M", 1.00, "STOPWORD"),
        ("了", "z", "M", 1.00, "STOPWORD"),
        ("嗎", "z", "M", 1.00, "STOPWORD"),
        ("呢", "z", "M", 1.00, "STOPWORD"),
        ("吧", "z", "M", 1.00, "STOPWORD"),
        ("啊", "z", "M", 1.00, "STOPWORD"),
    ]
    for term, tag_expr, src, conf, meta in l3_manual_entries:
        put_entry(term, tag_expr, src, conf, meta, overwrite=True)

    # Sort entries: M first, then A, then S, then by term length descending
    src_order = {"M": 0, "A": 1, "S": 2}
    sorted_items = sorted(
        lexicon.values(),
        key=lambda x: (src_order.get(x[2], 9), -len(x[0]), x[0])
    )

    out_path = os.path.join(ASSET_DIR, "command_lexicon.tsv")
    header = [
        "# Three-Tier Tagged Command Lexicon (L1 Auto + L2 Semi-Auto + L3 Manual Multi-Sense & Conditions + P5 Icon Hints)",
        "# Format: term \\t tag_expr(with > priority and +A/+W/+Q/+S conditions) \\t source(A/S/M) \\t confidence(0..1) \\t canonical_or_role \\t icon_hint",
        "# Action Codes (A): c=點擊, t=輸入文字, s=搜尋, b=返回, r=捲動滑動, w=開關切換, l=長按, d=雙擊, o=開啟App, x=擷取資訊, g=傳送分享, p=播放暫停",
        "# Connector Codes (J): 1=依序接下一步, 2=條件結果",
        "# Target Noun Codes (N): B=按鈕, I=輸入框, A=App名, P=頁面區域",
        "# Special Codes (S): e=語音錯字變體(第5欄為正確詞), z=不使用/雜訊, ?=待觀察",
        "# Conditions: +A=後接App名, +W=後接開關項, +Q=後接搜尋內容, +S=畫面有同名或同角色按鈕"
    ]
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        for h in header:
            f.write(h + "\n")
        for t, tag_expr, src, conf, meta, icon_hint in sorted_items:
            f.write(f"{t}\t{tag_expr}\t{src}\t{conf}\t{meta}\t{icon_hint}\n")

    counts = {"A": 0, "S": 0, "M": 0}
    icon_hint_count = 0
    for _, _, src, _, _, icon_hint in sorted_items:
        counts[src] = counts.get(src, 0) + 1
        if icon_hint:
            icon_hint_count += 1
    print(f"Generated command_lexicon.tsv with {len(sorted_items)} entries: L1(A)={counts['A']}, L2(S)={counts['S']}, L3(M)={counts['M']}, with_icon_hint={icon_hint_count}")

if __name__ == "__main__":
    update_asr_file()
    build_command_lexicon()
