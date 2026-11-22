import io
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()
s = s.replace('''        r = self.rng
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
            self.sliders[k] = r.randint(0, 4)''',
'''        r = self.rng
        # 只隨機化「世界初始狀態」，不隨機化文字/清單本身：
        # 這樣狀態空間不會爆炸（表格 Q 才學得起來），但每回合仍有不同的探索目標。
        for k in self.switches:
            if k != "mobile":
                self.switches[k] = r.random() < 0.5
        for k in self.sliders:
            self.sliders[k] = r.randint(0, 4)
        # 少量資料變化（固定大小抽樣，維持狀態空間可控）
        self.events = r.sample(FAKE_EVENTS, k=r.randint(2, 4))
        self.alarms = r.sample(FAKE_ALARMS, k=r.randint(1, 2))''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("light randomization")
