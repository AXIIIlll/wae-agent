# -*- coding: utf-8 -*-
"""
產生「通用元件能力圖譜」(Widget Capability Ontology)。

設計原則（與專案既有資料政策一致）：
  * 程式碼不寫任何 App 名稱、不寫任何元件清單；所有清單都在這個 TSV 裡。
  * 這份資料只描述「公開 SDK 的類別名」與「該類元件天生具備的操作能力」，
    不涉及任何 App 內部實作、不解析 APK，屬綠區。

欄位：
  family_id      元件家族識別碼（穩定、供程式引用）
  class_patterns 類別名稱片段（|分隔，大小寫不敏感，任一命中即屬此家族）
  capabilities   天生具備的操作能力（|分隔，見 CAPS 說明）
  aria_role      對應的 WAI-ARIA 1.2 角色（跨平台共同語言）
  purposes       可能的用途標籤（|分隔，中文），供「元件→可能作用」與「作用→元件」雙向查詢
  zh_name        中文名稱
  en_name        英文名稱
  source         來源分類

能力代碼（capability）：
  TAP         可單擊
  TOGGLE      二態切換（開/關、勾/不勾）
  SET_VALUE   可設定「絕對數值」（0.0~1.0 或 min~max）
  STEP_VALUE  可按 +/- 或一格一格加減
  SCROLL_V    垂直捲動
  SCROLL_H    水平捲動
  PICK_ONE    從一群選項挑一個（下拉/單選清單）
  PICK_MANY   可多選
  EXPAND      可展開/收合
  OPEN_MENU   會彈出選單
  TEXT_INPUT  可輸入文字
  SUBMIT      可送出（確認/完成）
  NAVIGATE    會切換頁面/目的地
  DISMISS     可關閉
  DRAG        可拖曳
  LONG_HOLD   長按有意義
  MEDIA       播放控制
  PINCH       雙指縮放
  REVEAL      滑出/抽屜
  READ_ONLY   純顯示（不應互動）

來源：
  * AOSP framework widgets（public SDK 類別名，Apache-2.0）
  * AndroidX / Material Components（public SDK 類別名，Apache-2.0）
  * AndroidX Preference（設定頁元件，Apache-2.0）
  * WAI-ARIA 1.2 roles（W3C，角色語彙對照）
  * RICO / CLAY / rico_semantics 的「元件型別」語彙（僅作為類型清單之交叉驗證，不複製其標註內容）
"""
import os

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "open_source_ai_databases",
    "ui_widget_capability_ontology.tsv",
)

CATEGORY_OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "open_source_ai_databases",
    "ui_purpose_category_map.tsv",
)

CATEGORY_HEADER = [
    "# 用途 → 所屬分類 關聯表 (Purpose → Category Map) — 跨 App 通用，不含任何單一 App 資料",
    "# 用途: 「亮度」這種屬性要知道它住在哪個設定分類（顯示），才不會靠語意相似度亂鑽分支",
    "# 來源: AOSP Settings 公開分類與設定項名稱（Apache-2.0）+ 通用中文介面用語",
    "# 格式: category_id \\t category_name \\t category_aliases \\t purpose_terms \\t source",
]

LEXICON_OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "open_source_ai_databases",
    "ui_value_adjust_lexicon.tsv",
)

LEXICON_HEADER = [
    "# 數值調整語彙庫 (Value Adjust Lexicon) — 跨 App 通用語言層資料，程式碼不得內建詞表",
    "# 用途: 「調高/降低/最大/一半/一點/很多」等詞的方向與幅度，全部由資料決定，方便後期擴充管理",
    "# 格式: term \\t role \\t magnitude \\t note",
    "# role: MAX(絕對最大) MIN(絕對最小) MID(絕對中間) UP(調高) DOWN(調低)",
    "#       DELTA_SMALL(小幅度) DELTA_LARGE(大幅度) FILLER(目標名詞前的填充詞，會被剝除)",
    "# magnitude: 僅 DELTA_* 使用（0~1，佔量程比例）",
]

# (role, magnitude, [terms], note)
LEXICON_GROUPS = [
    ("MAX", "", ["最大", "最高", "最強", "最亮", "最大聲", "全滿", "滿格", "全開", "開到最大", "調到最大",
                 "maximize", "maximum", "full", "loudest", "brightest"], "絕對最大"),
    ("MIN", "", ["最小", "最低", "最弱", "最暗", "最小聲", "關到最小",
                 "minimum", "quietest", "dimmest"], "絕對最小"),
    ("MID", "", ["一半", "中間", "中等", "適中", "half", "middle", "medium"], "絕對中間"),
    ("UP", "", ["調高", "調大", "加大", "增大", "加高", "提高", "上升", "上調", "增加", "變大", "轉大",
                "亮一點", "亮一些", "大聲", "快一點", "加快", "調亮",
                "turn up", "increase", "raise", "louder", "brighter", "faster"], "往上調"),
    ("DOWN", "", ["調低", "調小", "減小", "減低", "降低", "下降", "下調", "減少", "變小", "轉小",
                  "暗一點", "暗一些", "小聲", "慢一點", "減慢", "調暗",
                  "turn down", "decrease", "lower", "quieter", "dimmer", "slower"], "往下調"),
    ("DELTA_SMALL", "0.10", ["一點", "一些", "稍微", "小幅", "少許", "微微", "一點點",
                             "a bit", "slightly", "a little"], "小幅度"),
    ("DELTA_LARGE", "0.30", ["很多", "大幅", "明顯", "顯著", "大量", "一堆",
                             "a lot", "much", "significantly"], "大幅度"),
    ("FILLER", "", ["幫我", "請", "把", "將", "我要", "我想", "現在", "然後", "稍微", "一點點", "一點", "一些",
                    "少許", "調高", "調低", "調大", "調小", "調整", "設定", "設為", "設成", "調到", "調成",
                    "增加到", "減少到", "加大", "減小", "提高", "降低", "增加", "減少", "上升", "下降",
                    "上調", "下調", "變大", "變小", "最大", "最小", "最高", "最低", "一半", "中間", "中等",
                    "適中", "更多", "更少"], "目標名詞前的填充詞"),
]

# (category_id, name, aliases, purposes, source)
CATEGORIES = [
    ("display", "顯示",
     "顯示|顯示螢幕|螢幕|畫面|顯示與亮度",
     "亮度|字體大小|顯示大小|螢幕逾時|螢幕休眠|深色模式|深色主題|自動旋轉|螢幕更新率|解析度|色彩|護眼模式|螢幕鎖定時間|觸控靈敏度",
     "aosp-settings"),
    ("sound", "音效與震動",
     "音效與震動|音效|聲音|震動",
     "音量|震動強度|鈴聲|通知音|媒體音量|來電音量|鬧鐘音量|靜音|觸控音效|音質",
     "aosp-settings"),
    ("network", "網路與網際網路",
     "網路與網際網路|網路|網際網路|連線",
     "無線網路|行動網路|飛航模式|熱點|數據用量|漫遊|VPN|代理伺服器|Wi-Fi",
     "aosp-settings"),
    ("connected", "已連接的裝置",
     "已連接的裝置|連接|藍牙",
     "藍牙|配對|USB|NFC|投放|鄰近分享|印表機",
     "aosp-settings"),
    ("battery", "電池與裝置維護",
     "電池|裝置維護|電池與裝置維護",
     "電池|省電模式|耗電|充電|效能模式",
     "aosp-settings"),
    ("storage", "儲存空間",
     "儲存空間|儲存",
     "儲存空間|可用空間|容量|清理|快取",
     "aosp-settings"),
    ("apps", "應用程式",
     "應用程式|應用|程式",
     "預設應用|應用程式權限|解除安裝|應用程式資訊|自動更新",
     "aosp-settings"),
    ("notifications", "通知",
     "通知",
     "通知|橫幅|勿擾模式|通知分類|通知音效",
     "aosp-settings"),
    ("security", "安全性與鎖定螢幕",
     "安全性|安全|鎖定螢幕",
     "螢幕鎖定|指紋|密碼|臉部辨識|圖形鎖|安全性更新|加密",
     "aosp-settings"),
    ("accounts", "帳號與備份",
     "帳號與備份|帳號|備份",
     "帳號|同步|備份|還原|自動同步",
     "aosp-settings"),
    ("system", "一般管理",
     "一般管理|系統|語言與輸入",
     "語言|輸入法|日期與時間|鍵盤|軟體更新|重設|開發人員選項|系統導覽",
     "aosp-settings"),
    ("privacy", "隱私權",
     "隱私權|隱私",
     "定位|位置|權限|廣告|使用記錄",
     "aosp-settings"),
    ("accessibility", "協助工具",
     "協助工具|無障礙",
     "放大手勢|TalkBack|字幕|顏色修正|互動控制",
     "aosp-settings"),
    ("personalize", "桌布與個人化",
     "桌布|個人化|主題",
     "桌布|主題|小工具|主螢幕|圖示|動態效果",
     "aosp-settings"),
]

HEADER = [
    "# 通用元件能力圖譜 (Widget Capability Ontology) — 跨 App 通用，不含任何單一 App 資料",
    "# 來源: AOSP framework widgets + AndroidX + Material Components + AndroidX Preference + WAI-ARIA 1.2 roles",
    "# 格式: family_id \\t class_patterns \\t capabilities \\t aria_role \\t purposes \\t zh_name \\t en_name \\t source \\t prior",
    "# prior: strong=類別知識可信；weak=類別只是通用容器，能力必須由運行期語意訊號決定",
    "# 用途: 辨識到元件 → 查它「可能是什麼、可能能做什麼」；反向：想做某種操作 → 查哪些元件做得到",
]

# 這些家族的類別名不帶任何能力資訊（自訂/Compose/通用容器），能力一律由運行期訊號決定
WEAK_PRIOR = {"generic_view", "compose_view"}

# (family_id, class_patterns, caps, aria, purposes, zh, en, source)
ROWS = [
    # ── 文字與顯示 ──────────────────────────────────────────────
    ("text_view",
     "android.widget.TextView|androidx.appcompat.widget.AppCompatTextView|com.google.android.material.textview.MaterialTextView|androidx.emoji.widget.EmojiTextView|androidx.appcompat.widget.AppCompatCheckedTextView",
     "TAP|LONG_HOLD|READ_ONLY", "text",
     "文字顯示|標籤|說明|標題|提示|連結|日期|價格|計數|狀態",
     "文字", "TextView", "aosp"),
    ("edit_text",
     "android.widget.EditText|androidx.appcompat.widget.AppCompatEditText|com.google.android.material.textfield.TextInputEditText|android.widget.AutoCompleteTextView|androidx.appcompat.widget.AppCompatAutoCompleteTextView|android.widget.MultiAutoCompleteTextView|androidx.emoji.widget.EmojiEditText",
     "TEXT_INPUT|TAP|SUBMIT|PICK_ONE|LONG_HOLD|OPEN_MENU", "textbox",
     "輸入|搜尋|帳號|密碼|訊息|留言|表單欄位|筆記|地址|數量",
     "輸入框", "EditText", "aosp"),
    ("search_box",
     "androidx.appcompat.widget.SearchView|android.widget.SearchView|com.google.android.material.search.SearchView|com.google.android.material.search.SearchBar|androidx.appcompat.widget.SearchView$SearchAutoComplete",
     "TEXT_INPUT|TAP|SUBMIT|OPEN_MENU|PICK_ONE", "searchbox",
     "搜尋|查找|篩選|搜尋建議|查詢",
     "搜尋框", "SearchView", "androidx"),
    ("button",
     "android.widget.Button|androidx.appcompat.widget.AppCompatButton|com.google.android.material.button.MaterialButton|android.widget.ImageButton|androidx.appcompat.widget.AppCompatImageButton|android.widget.ToggleButton|androidx.appcompat.widget.AppCompatToggleButton",
     "TAP|SUBMIT|LONG_HOLD", "button",
     "確認|送出|取消|下一步|登入|註冊|開始|完成|重試|套用|一般按鈕",
     "按鈕", "Button", "aosp"),
    ("button_toggle_group",
     "com.google.android.material.button.MaterialButtonToggleGroup|android.widget.RadioGroup|androidx.appcompat.widget.AppCompatRadioButton$RadioStateList?",
     "PICK_ONE|NAVIGATE|TAP", "radiogroup",
     "分段切換|選項切換|切換檢視|模式選擇",
     "按鈕群組", "ButtonToggleGroup", "material"),
    ("image_view",
     "android.widget.ImageView|androidx.appcompat.widget.AppCompatImageView|com.google.android.material.imageview.ShapeableImageView|com.facebook.drawee.view.SimpleDraweeView|androidx.emoji.widget.EmojiAppCompatImageView|android.widget.ImageButton?",
     "TAP|LONG_HOLD|READ_ONLY", "img",
     "圖片|頭像|縮圖|相片|圖示|封面|預覽圖",
     "圖片", "ImageView", "aosp"),
    ("icon_view",
     "androidx.appcompat.widget.AppCompatImageHelper?",
     "TAP|READ_ONLY", "img",
     "圖示|圖標|按鈕圖標|狀態圖標",
     "圖示", "IconView", "androidx"),
    ("progress_bar",
     "android.widget.ProgressBar|com.google.android.material.progressindicator.LinearProgressIndicator|com.google.android.material.progressindicator.CircularProgressIndicator|com.google.android.material.progressindicator.BaseProgressIndicator",
     "READ_ONLY", "progressbar",
     "載入中|進度顯示|等待|完成度|已用額度|儲存空間",
     "進度條", "ProgressBar", "aosp"),
    ("chronometer",
     "android.widget.Chronometer|android.widget.DigitalClock",
     "READ_ONLY", "timer",
     "計時|時間顯示|倒數",
     "計時器", "Chronometer", "aosp"),

    # ── 開關與選取 ──────────────────────────────────────────────
    ("checkbox",
     "android.widget.CheckBox|androidx.appcompat.widget.AppCompatCheckBox|com.google.android.material.checkbox.MaterialCheckBox",
     "TAP|TOGGLE|PICK_MANY", "checkbox",
     "勾選|多選|同意條款|記住我|訂閱|啟用",
     "核取方塊", "CheckBox", "aosp"),
    ("radio_button",
     "android.widget.RadioButton|androidx.appcompat.widget.AppCompatRadioButton|com.google.android.material.radiobutton.MaterialRadioButton",
     "TAP|TOGGLE|PICK_ONE", "radio",
     "單選|選項|性別|付款方式|語言|偏好設定",
     "選項按鈕", "RadioButton", "aosp"),
    ("switch",
     "android.widget.Switch|androidx.appcompat.widget.SwitchCompat|com.google.android.material.materialswitch.MaterialSwitch|com.google.android.material.switchmaterial.SwitchMaterial|androidx.appcompat.widget.SwitchCompat?",
     "TAP|TOGGLE", "switch",
     "開關|啟用停用|飛航模式|無線網路|藍牙|通知|深色模式|自動播放|震動",
     "開關", "Switch", "androidx"),
    ("chip",
     "com.google.android.material.chip.Chip|com.google.android.material.chip.ChipGroup|androidx.appcompat.widget.AppCompatCheckBox?",
     "TAP|TOGGLE|PICK_ONE|PICK_MANY|DISMISS", "button",
     "篩選|標籤|分類|快速選項|已選條件|搜尋建議",
     "標籤晶片", "Chip", "material"),
    ("spinner",
     "android.widget.Spinner|androidx.appcompat.widget.AppCompatSpinner",
     "TAP|OPEN_MENU|PICK_ONE", "combobox",
     "下拉選單|選擇項目|排序方式|語言|地區|分類",
     "下拉選單", "Spinner", "aosp"),
    ("exposed_dropdown",
     "com.google.android.material.textfield.MaterialAutoCompleteTextView|androidx.appcompat.widget.ListPopupWindow|com.google.android.material.textfield.ExposedDropdownMenu",
     "TAP|OPEN_MENU|PICK_ONE|TEXT_INPUT", "combobox",
     "下拉選單|自動完成|建議清單|選擇項目",
     "下拉輸入框", "ExposedDropdownMenu", "material"),

    # ── 數值調整（本次重點）──────────────────────────────────────
    ("seek_bar",
     "android.widget.SeekBar|androidx.appcompat.widget.AppCompatSeekBar|com.google.android.material.slider.Slider|com.google.android.material.slider.BaseSlider",
     "SET_VALUE|STEP_VALUE|DRAG|SCROLL_H|TAP", "slider",
     "亮度|音量|字體大小|顯示大小|縮放比例|更新頻率|震動強度|數值調整|播放進度|時間軸",
     "滑桿", "SeekBar", "aosp"),
    ("range_slider",
     "com.google.android.material.slider.RangeSlider",
     "SET_VALUE|STEP_VALUE|DRAG|SCROLL_H|TAP", "slider",
     "區間|價格範圍|雙端數值|範圍篩選",
     "區間滑桿", "RangeSlider", "material"),
    ("rating_bar",
     "android.widget.RatingBar|androidx.appcompat.widget.AppCompatRatingBar",
     "SET_VALUE|STEP_VALUE|TAP|DRAG", "slider",
     "評分|星等|滿意度|打分",
     "評分條", "RatingBar", "aosp"),
    ("number_picker",
     "android.widget.NumberPicker|androidx.core.widget.NestedScrollView?",
     "SET_VALUE|STEP_VALUE|TEXT_INPUT|SCROLL_V", "spinbutton",
     "數字選擇|數量|年份|時間|小數|年齡",
     "數字選擇器", "NumberPicker", "aosp"),
    ("date_picker",
     "android.widget.DatePicker|android.widget.CalendarView|androidx.core.widget.NestedScrollView?",
     "PICK_ONE|SET_VALUE|STEP_VALUE|SCROLL_V|OPEN_MENU", "dialog",
     "日期|生日|排程|預約|到期日|行事曆",
     "日期選擇器", "DatePicker", "aosp"),
    ("time_picker",
     "android.widget.TimePicker|android.widget.AnalogClock?",
     "PICK_ONE|SET_VALUE|STEP_VALUE|SCROLL_V", "dialog",
     "時間|鬧鐘|提醒|營業時間|排程",
     "時間選擇器", "TimePicker", "aosp"),

    # ── 清單與容器 ──────────────────────────────────────────────
    ("recycler_view",
     "androidx.recyclerview.widget.RecyclerView|androidx.recyclerview.widget.LinearLayoutManager?",
     "SCROLL_V|SCROLL_H|PICK_ONE|LONG_HOLD|DRAG", "list",
     "列表|清單|訊息串|搜尋結果|卡片串|動態牆|商品列表|信件列表",
     "回收檢視清單", "RecyclerView", "androidx"),
    ("adapter_list",
     "android.widget.ListView|android.widget.GridView|android.widget.AbsListView|android.widget.ExpandableListView|android.widget.Gallery|android.widget.AdapterView",
     "SCROLL_V|SCROLL_H|PICK_ONE|LONG_HOLD|EXPAND", "list",
     "列表|清單|網格|相簿|搜尋結果|選單",
     "清單", "ListView", "aosp"),
    ("scroll_view",
     "android.widget.ScrollView|androidx.core.widget.NestedScrollView|androidx.core.widget.NestedScrollView?",
     "SCROLL_V|SCROLL_H|DRAG", "group",
     "捲動|整頁內容|長文|設定頁",
     "捲動容器", "ScrollView", "aosp"),
    ("horizontal_scroll",
     "android.widget.HorizontalScrollView|androidx.recyclerview.widget.RecyclerView?",
     "SCROLL_H|DRAG", "group",
     "橫向捲動|橫向列表|分類列|輪播",
     "橫向捲動容器", "HorizontalScrollView", "aosp"),
    ("view_pager",
     "androidx.viewpager.widget.ViewPager|androidx.viewpager2.widget.ViewPager2|androidx.viewpager.widget.PagerTabStrip",
     "SCROLL_H|TAP|PICK_ONE|NAVIGATE", "tabpanel",
     "頁面切換|輪播|左右滑動|引導頁|分頁內容",
     "翻頁容器", "ViewPager", "androidx"),
    ("swipe_refresh",
     "androidx.swiperefreshlayout.widget.SwipeRefreshLayout",
     "SCROLL_V|DRAG", "group",
     "下拉重新整理|刷新",
     "下拉刷新", "SwipeRefreshLayout", "androidx"),
    ("card_view",
     "androidx.cardview.widget.CardView|com.google.android.material.card.MaterialCardView",
     "TAP|LONG_HOLD|READ_ONLY", "group",
     "卡片|內容容器|項目|條目",
     "卡片", "CardView", "androidx"),

    # ── 導覽 ──────────────────────────────────────────────────
    ("toolbar",
     "androidx.appcompat.widget.Toolbar|com.google.android.material.appbar.MaterialToolbar|android.widget.Toolbar|android.app.ActionBar",
     "TAP|NAVIGATE|OPEN_MENU|LONG_HOLD", "toolbar",
     "標題列|返回|選單|更多選項|搜尋入口",
     "工具列", "Toolbar", "androidx"),
    ("app_bar",
     "com.google.android.material.appbar.AppBarLayout|com.google.android.material.appbar.CollapsingToolbarLayout",
     "SCROLL_V|EXPAND|NAVIGATE", "banner",
     "頂部區|可收合標題|大標題",
     "應用程式列", "AppBarLayout", "material"),
    ("tabs",
     "com.google.android.material.tabs.TabLayout|android.widget.TabWidget|android.widget.TabHost|androidx.viewpager.widget.PagerTabStrip",
     "TAP|PICK_ONE|NAVIGATE|SCROLL_H", "tablist",
     "分頁|頁籤|分類切換|頂部分類",
     "分頁列", "TabLayout", "material"),
    ("bottom_nav",
     "com.google.android.material.bottomnavigation.BottomNavigationView|com.google.android.material.navigation.NavigationBarView",
     "TAP|PICK_ONE|NAVIGATE", "navigation",
     "底部導覽|主要頁面切換|首頁|探索|個人",
     "底部導覽列", "BottomNavigationView", "material"),
    ("nav_drawer",
     "androidx.drawerlayout.widget.DrawerLayout|com.google.android.material.navigation.NavigationView|android.widget.SlidingDrawer",
     "REVEAL|NAVIGATE|DISMISS|DRAG|TAP", "navigation",
     "側邊選單|抽屜|帳號選單|設定入口",
     "側邊抽屜", "DrawerLayout", "androidx"),
    ("fab",
     "com.google.android.material.floatingactionbutton.FloatingActionButton|com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton",
     "TAP|SUBMIT", "button",
     "新增|撰寫|建立|主要動作|開啟對話",
     "浮動按鈕", "FloatingActionButton", "material"),

    # ── 彈出與提示 ──────────────────────────────────────────────
    ("bottom_sheet",
     "com.google.android.material.bottomsheet.BottomSheetDialog|com.google.android.material.bottomsheet.BottomSheetBehavior|androidx.coordinatorlayout.widget.CoordinatorLayout",
     "EXPAND|DISMISS|SCROLL_V|DRAG|TAP", "dialog",
     "底部面板|更多選項|分享面板|篩選面板",
     "底部彈出面板", "BottomSheet", "material"),
    ("snackbar",
     "com.google.android.material.snackbar.Snackbar|com.google.android.material.snackbar.SnackbarContentLayout",
     "TAP|DISMISS", "alert",
     "提示|復原|錯誤訊息|短暫通知",
     "提示條", "Snackbar", "material"),
    ("toast_like",
     "android.widget.Toast|android.widget.Toast$TN",
     "READ_ONLY", "alert",
     "短暫提示|系統訊息",
     "浮動提示", "Toast", "aosp"),

    # ── 輸入與表單容器 ──────────────────────────────────────────
    ("text_input_layout",
     "com.google.android.material.textfield.TextInputLayout|androidx.appcompat.widget.LinearLayoutCompat?",
     "TEXT_INPUT|TAP", "group",
     "表單欄位容器|輸入框外框|錯誤提示",
     "輸入框容器", "TextInputLayout", "material"),
    ("web_view",
     "android.webkit.WebView|com.tencent.smtt.sdk.WebView|android.webkit.WebViewFragment?",
     "SCROLL_V|SCROLL_H|TEXT_INPUT|TAP|SUBMIT|PICK_ONE|DRAG|LONG_HOLD", "document",
     "網頁|文章內容|網頁表單|說明頁|條款|付款頁",
     "網頁檢視", "WebView", "aosp"),

    # ── 媒體與圖形 ──────────────────────────────────────────────
    ("video_player",
     "android.widget.VideoView|com.google.android.exoplayer2.ui.PlayerView|androidx.media3.ui.PlayerView|tv.danmaku.ijk.media.widget.media.IjkVideoView|com.google.android.exoplayer2.ui.StyledPlayerView",
     "MEDIA|TAP|SET_VALUE|DRAG|SCROLL_H", "application",
     "影片播放|時間軸|全螢幕|進度拖曳|音量",
     "影片播放器", "VideoView", "aosp"),
    ("media_controller",
     "android.widget.MediaController|androidx.media3.ui.PlayerControlView|com.google.android.exoplayer2.ui.PlayerControlView",
     "MEDIA|TAP|SET_VALUE|DRAG", "toolbar",
     "播放暫停|上一首下一首|進度|音量|全螢幕",
     "媒體控制列", "MediaController", "androidx"),
    ("map_view",
     "com.google.android.gms.maps.MapView|com.google.android.gms.maps.SupportMapFragment|com.amap.api.maps.MapView|com.baidu.mapapi.map.MapView|com.tencent.tencentmap.mapsdk.maps.MapView|com.google.android.gms.maps.MapFragment",
     "DRAG|PINCH|TAP|SCROLL_V|SCROLL_H", "application",
     "地圖|定位|導航|路線|地點|縮放",
     "地圖", "MapView", "community-libs"),
    ("photo_view",
     "com.github.chrisbanes.photoview.PhotoView|uk.co.senab.photoview.PhotoView|com.davemorrissey.labs.subscaleview.SSIV|com.github.chrisbanes.photoview.PhotoViewAttacher",
     "PINCH|DRAG|TAP", "img",
     "圖片縮放|看圖|雙指放大|相片檢視",
     "可縮放圖片", "PhotoView", "community-libs"),
    ("chart_view",
     "com.github.mikephil.charting.charts.LineChart|com.github.mikephil.charting.charts.BarChart|com.github.mikephil.charting.charts.PieChart|com.github.mikephil.charting.charts.RadarChart|com.github.mikephil.charting.charts.CandleStickChart|com.github.mikephil.charting.charts.CombinedChart",
     "READ_ONLY|TAP|PINCH|DRAG", "img",
     "圖表|統計|折線|柱狀|圓餅|股價|趨勢",
     "圖表", "Chart", "community-libs"),
    ("lottie_view",
     "com.airbnb.lottie.LottieAnimationView|com.airbnb.lottie.LottieDrawable?",
     "READ_ONLY|TAP", "img",
     "動畫|插圖|載入動畫|空狀態圖",
     "動畫檢視", "LottieAnimationView", "community-libs"),
    ("surface_view",
     "android.view.SurfaceView|android.view.TextureView|android.opengl.GLSurfaceView",
     "DRAG|PINCH|TAP|MEDIA", "application",
     "相機預覽|3D|遊戲畫面|影片輸出",
     "繪圖表面", "SurfaceView", "aosp"),
    ("keyboard_view",
     "android.inputmethodservice.KeyboardView|com.android.internal.inputmethod?",
     "TAP|LONG_HOLD", "keyboard",
     "鍵盤|虛擬鍵盤|輸入法",
     "鍵盤", "KeyboardView", "aosp"),

    # ── 設定頁（Preference 家族）────────────────────────────────
    ("preference_row",
     "androidx.preference.Preference|android.preference.Preference|androidx.preference.PreferenceGroup?",
     "TAP|NAVIGATE", "link",
     "設定項目|進入子頁|選項列|說明列",
     "設定列", "Preference", "androidx-preference"),
    ("preference_category",
     "androidx.preference.PreferenceCategory|androidx.preference.PreferenceScreen|android.preference.PreferenceCategory",
     "READ_ONLY|SCROLL_V", "group",
     "分類標題|設定分組|區段標題",
     "設定分類", "PreferenceCategory", "androidx-preference"),
    ("switch_preference",
     "androidx.preference.SwitchPreference|androidx.preference.SwitchPreferenceCompat|android.preference.SwitchPreference|androidx.preference.CheckBoxPreference|androidx.preference.TwoStatePreference|com.android.settingslib.RestrictedSwitchPreference|com.android.settings.widget.SwitchBar?",
     "TAP|TOGGLE", "switch",
     "設定開關|啟用停用|功能開啟|通知開關|自動更新",
     "設定開關", "SwitchPreference", "androidx-preference"),
    ("seekbar_preference",
     "androidx.preference.SeekBarPreference|androidx.preference.SeekBarPreferenceCompat|com.android.settingslib.widget.SeekBarPreference?",
     "SET_VALUE|STEP_VALUE|DRAG|TAP", "slider",
     "亮度|音量|字體大小|顯示大小|逾時時間|數值設定",
     "設定滑桿", "SeekBarPreference", "androidx-preference"),
    ("list_preference",
     "androidx.preference.ListPreference|androidx.preference.DropDownPreference|android.preference.ListPreference|androidx.preference.MultiSelectListPreference",
     "PICK_ONE|PICK_MANY|OPEN_MENU|TAP", "combobox",
     "選項選擇|語言|地區|排序|主題|畫質|多選設定",
     "設定選項清單", "ListPreference", "androidx-preference"),
    ("edit_preference",
     "androidx.preference.EditTextPreference|android.preference.EditTextPreference",
     "TEXT_INPUT|TAP", "textbox",
     "設定輸入|名稱|位址|號碼|自訂值",
     "設定輸入框", "EditTextPreference", "androidx-preference"),

    # ── 通用/未知元件（最後手段，能力需由運行期訊號決定）──────────
    ("compose_view",
     "androidx.compose.ui.platform.AndroidComposeView|androidx.compose.ui.platform.ComposeView|androidx.compose.ui.window.DialogWrapper|androidx.compose.ui.platform.ViewRootForInspector",
     "TAP|LONG_HOLD|SCROLL_V|TOGGLE|SET_VALUE|TEXT_INPUT|PICK_ONE|EXPAND|NAVIGATE", "group",
     "Compose 語意節點|自訂介面|通用元件（能力由運行期語意判定）",
     "Compose 檢視", "AndroidComposeView", "androidx"),
    ("generic_view",
     "android.view.View|android.view.ViewGroup|android.widget.FrameLayout|android.widget.LinearLayout|android.widget.RelativeLayout|android.widget.ConstraintLayout|androidx.constraintlayout.widget.ConstraintLayout|androidx.constraintlayout.widget.Group|android.widget.TableLayout|android.widget.TableRow|android.widget.GridLayout|android.widget.Space|android.widget.ViewFlipper|android.widget.ViewAnimator",
     "TAP|LONG_HOLD|SCROLL_V|SCROLL_H|READ_ONLY", "group",
     "通用容器|自訂元件|排版區塊|點擊區",
     "通用檢視", "View", "aosp"),
    ("ad_view",
     "com.google.android.gms.ads.AdView|com.google.android.gms.ads.NativeExpressAdView|com.facebook.ads.AdView|com.applovin.adview.AppLovinAdView",
     "DISMISS|READ_ONLY", "img",
     "廣告|橫幅廣告|推廣內容",
     "廣告", "AdView", "community-libs"),
]


def main():
    lines = list(HEADER)
    seen = set()
    for fam, classes, caps, aria, purposes, zh, en, src in ROWS:
        assert fam not in seen, "duplicate family_id: %s" % fam
        seen.add(fam)
        for col, val in (("class_patterns", classes), ("capabilities", caps), ("purposes", purposes)):
            assert "\t" not in val, (fam, col)
            assert val.strip() != "", (fam, col)
        lines.append("\t".join([
            fam, classes, caps, aria, purposes, zh, en, src,
            "weak" if fam in WEAK_PRIOR else "strong",
        ]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    cat_lines = list(CATEGORY_HEADER)
    seen_cat = set()
    for cid, name, aliases, purposes, src in CATEGORIES:
        assert cid not in seen_cat, "duplicate category_id: %s" % cid
        seen_cat.add(cid)
        for col, val in (("aliases", aliases), ("purposes", purposes)):
            assert "\t" not in val, (cid, col)
        cat_lines.append("\t".join([cid, name, aliases, purposes, src]))
    with open(CATEGORY_OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(cat_lines) + "\n")

    lex_lines = list(LEXICON_HEADER)
    for role, magnitude, terms, note in LEXICON_GROUPS:
        for t in terms:
            assert "\t" not in t, t
            lex_lines.append("\t".join([t, role, magnitude, note]))
    with open(LEXICON_OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lex_lines) + "\n")
    print("wrote %s rows=%d" % (OUT, len(ROWS)))
    print("wrote %s rows=%d" % (CATEGORY_OUT, len(CATEGORIES)))
    print("wrote %s rows=%d" % (LEXICON_OUT, len(lex_lines) - len(LEXICON_HEADER)))


if __name__ == "__main__":
    main()
