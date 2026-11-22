import io
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()

# 1) 累積統計（跨 episode）+ reset()
s = s.replace("""        self.visited_screens: set = set()
        self.invalid_actions = 0""",
"""        self.visited_screens: set = set()
        # 跨 episode 的累積統計（gate 判定用）
        self.cum = {
            "toggle_flips": 0, "effective_scrolls": 0, "taps_total": 0,
            "taps_with_change": 0, "valid_actions": 0, "invalid_actions": 0,
        }
        self.invalid_actions = 0""")

s = s.replace("""    # ── 觀測 ────────────────────────────────────────────────────────""",
"""    def reset(self) -> str:
        \"\"\"開始新的「使用 session」：清空世界狀態，但保留累積統計與已探索畫面。\"\"\"
        self.toggles = {
            "airplane": False, "wifi": True, "bluetooth": False, "mobile_data": True,
            "dark_mode": False, "dnd": False, "ringtone": True,
        }
        self.sliders = {"brightness": 2, "media_volume": 3}
        self.positions = {}
        self.calc_display = "0"
        self.email_subject = ""
        self.steps = 0
        self._enter("home")
        return self.observe()

    # ── 觀測 ────────────────────────────────────────────────────────""")

# 2) 統計計數改為累積
s = s.replace("""            self.invalid_actions += 1
            info["kind"] = "invalid\"""",
"""            self.invalid_actions += 1
            self.cum["invalid_actions"] += 1
            info["kind"] = "invalid\"""")
s = s.replace("""            self.valid_actions += 1""",
"""            self.valid_actions += 1
            self.cum["valid_actions"] += 1""")
s = s.replace("""                    self.effective_scrolls += 1
                    info["kind"] = "scroll\"""",
"""                    self.effective_scrolls += 1
                    self.cum["effective_scrolls"] += 1
                    info["kind"] = "scroll\"""")
s = s.replace("""                self.taps_total += 1
                self._tap(slot, info)""",
"""                self.taps_total += 1
                self.cum["taps_total"] += 1
                self._tap(slot, info)""")
# 3) 修正：tap 造成的變化要計入（kind 是 nav/toggle/slider/calc 也算）
s = s.replace("""            info["changed"] = before != self.observe()
            if info["changed"] and info["kind"] == "tap":
                self.taps_with_change += 1""",
"""            info["changed"] = before != self.observe()
            if info["changed"] and info["kind"] in ("nav", "toggle", "slider", "calc", "tap"):
                self.taps_with_change += 1
                self.cum["taps_with_change"] += 1""")
# 4) stats() 回傳累積值
s = s.replace("""    def stats(self) -> dict:
        return {
            "screens_discovered": len(self.visited_screens),
            "screens_total": len(self.TOTAL_SCREENS),
            "toggle_flips": self.toggle_flips,
            "effective_scrolls": self.effective_scrolls,
            "taps_total": self.taps_total,
            "taps_with_change": self.taps_with_change,
            "valid_actions": self.valid_actions,
            "invalid_actions": self.invalid_actions,
        }""",
"""    def stats(self) -> dict:
        return {
            "screens_discovered": len(self.visited_screens),
            "screens_total": len(self.TOTAL_SCREENS),
            "toggle_flips": self.cum["toggle_flips"],
            "effective_scrolls": self.cum["effective_scrolls"],
            "taps_total": self.cum["taps_total"],
            "taps_with_change": self.cum["taps_with_change"],
            "valid_actions": self.cum["valid_actions"],
            "invalid_actions": self.cum["invalid_actions"],
        }""")
# toggle_flips 也累積
s = s.replace("""                self.toggle_flips += 1""",
"""                self.toggle_flips += 1
                self.cum["toggle_flips"] += 1""")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# train.py：每回合 reset
p2 = "tools/symbolic_sim/train.py"
s2 = io.open(p2, encoding="utf-8").read()
s2 = s2.replace("""    for ep in range(1, args.episodes + 1):
        obs = env.observe()""",
"""    for ep in range(1, args.episodes + 1):
        obs = env.reset()""")
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("env reset + counters fixed")
