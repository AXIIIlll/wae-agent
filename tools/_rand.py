import io
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()

# 1) 建構子加 randomize（每回合重新抽資料 → 新奇度不會一次就飽和）
s = s.replace("""    def __init__(self, seed: int = 0, max_steps: int = 120,
                 device: DeviceMetrics | str = "samsung_a55"):
        self.rng = random.Random(seed)""",
"""    def __init__(self, seed: int = 0, max_steps: int = 120,
                 device: DeviceMetrics | str = "samsung_a55", randomize: bool = False):
        self.rng = random.Random(seed)
        self.randomize = randomize""")

# 2) 每次 reset 都重新抽（內容/順序/初始狀態），但畫面結構維持真實 dp
s = s.replace("""    def reset(self) -> str:
        self._defaults()
        self.steps = 0""",
"""    def _randomize_data(self) -> None:
        \"\"\"每回合重新抽合成資料：讓「新奇度」不會在幾分內就飽和。\"\"\"
        r = self.rng
        self.contacts = r.sample(FAKE_CONTACTS, k=min(len(FAKE_CONTACTS), r.randint(4, 7)))
        self.messages = r.sample(FAKE_MESSAGES, k=min(len(FAKE_MESSAGES), r.randint(2, 4)))
        self.events = r.sample(FAKE_EVENTS, k=min(len(FAKE_EVENTS), r.randint(2, 4)))
        self.files = r.sample(FAKE_FILES, k=min(len(FAKE_FILES), r.randint(3, 5)))
        self.alarms = r.sample(FAKE_ALARMS, k=min(len(FAKE_ALARMS), r.randint(1, 2)))
        self.settings_rows_order = r.sample(SETTINGS_POOL, k=r.randint(10, 14))
        self.apps_rows_order = r.sample(APP_POOL, k=r.randint(10, 16))
        for k in self.switches:
            if k != "mobile":
                self.switches[k] = r.random() < 0.45
        for k in self.sliders:
            self.sliders[k] = r.randint(0, 4)

    def reset(self) -> str:
        self._defaults()
        if self.randomize:
            self._randomize_data()
        self.steps = 0""")

# 3) 預設值：加資料欄位
s = s.replace("""        self.current_event = ""
        self.current_contact = ""
        self.current_thread = ""
        self.photo_count = 0""",
"""        self.current_event = ""
        self.current_contact = ""
        self.current_thread = ""
        self.photo_count = 0
        self.contacts = list(FAKE_CONTACTS)
        self.messages = list(FAKE_MESSAGES)
        self.events = list(FAKE_EVENTS)
        self.files = list(FAKE_FILES)
        self.alarms = list(FAKE_ALARMS)
        self.settings_rows_order = list(SETTINGS_POOL)
        self.apps_rows_order = list(APP_POOL)""")

# 4) 建構子要先有預設資料（_defaults 已含）
# 5) 定義資料池
s = s.replace("""FAKE_ALARMS = ["07:00 鬧鐘", "08:30 週末鬧鐘"]""",
"""FAKE_ALARMS = ["07:00 鬧鐘", "08:30 週末鬧鐘", "06:45 起床", "22:30 睡前"]

SETTINGS_POOL = ["網路與網際網路", "已連接的裝置", "應用程式", "通知", "電池",
                 "儲存空間", "音效與震動", "顯示", "安全性", "系統",
                 "協助工具", "數位健康與家長監護", "Google", "系統更新",
                 "關於手機", "開發人員選項", "帳號與備份", "位置資訊",
                 "安全與緊急狀況", "桌布與樣式"]
APP_POOL = ["計算機", "時鐘", "日曆", "聯絡人", "電話", "訊息", "檔案", "相簿",
            "相機", "設定", "錄音機", "天氣", "備忘錄", "瀏覽器", "郵件", "音樂"]""")

# 6) 各畫面改用「每回合抽到的資料」
s = s.replace("""    def _build_settings_main(self) -> Screen:
        rows = ["網路與網際網路", "已連接的裝置", "應用程式", "通知", "電池",
                "儲存空間", "音效與震動", "顯示", "安全性", "系統",
                "協助工具", "數位健康與家長監護", "Google", "系統更新",
                "關於手機", "開發人員選項"]
        return self._list_screen("settings_main", "設定", rows)""",
"""    def _build_settings_main(self) -> Screen:
        return self._list_screen("settings_main", "設定", self.settings_rows_order)""")

s = s.replace("""    def _build_apps(self) -> Screen:
        apps = self.NATIVE_APPS + ["錄音機", "天氣", "備忘錄", "計算機", "時鐘", "日曆", "電話"]
        return self._list_screen("apps", "應用程式", apps, row_h=64.0,
                                 family="list_item", role="content")""",
"""    def _build_apps(self) -> Screen:
        return self._list_screen("apps", "應用程式", self.apps_rows_order, row_h=64.0,
                                 family="list_item", role="content")""")

s = s.replace("        for i, label in enumerate(FAKE_ALARMS):", "        for i, label in enumerate(self.alarms):")
s = s.replace("        for i, label in enumerate(FAKE_EVENTS):", "        for i, label in enumerate(self.events):")
s = s.replace("        for i, label in enumerate(FAKE_CONTACTS):", "        for i, label in enumerate(self.contacts):")
s = s.replace("        for i, label in enumerate(FAKE_CONTACTS[:4]):", "        for i, label in enumerate(self.contacts[:4]):")
s = s.replace("        for i, label in enumerate(FAKE_FILES):", "        for i, label in enumerate(self.files):")
s = s.replace("""        for i, msg in enumerate(FAKE_MESSAGES):""", """        for i, msg in enumerate(self.messages):""")
s = s.replace("""                                   subtitle=FAKE_MESSAGES[i % len(FAKE_MESSAGES)]))""",
"""                                   subtitle=self.messages[i % len(self.messages)]))""")

# 7) 首頁 app 圖示也要跟著抽（否則首頁永遠一樣）
s = s.replace("""        for i, label in enumerate(self.NATIVE_APPS[:MAX_SLOTS - 2]):""",
"""        home_apps = [a for a in self.apps_rows_order if a in self.NATIVE_APPS]
        home_apps = (home_apps + self.NATIVE_APPS)[:MAX_SLOTS - 2]
        for i, label in enumerate(home_apps):""")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("domain randomization added")
