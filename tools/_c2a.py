import io
p = "tools/symbolic_sim/run_overnight.py"
s = io.open(p, encoding="utf-8").read()

# 1) 加入目標池與 HER-lite
s = s.replace('''def evaluate(agent: CuriosityAgent, episodes: int = 20, seed: int = 999) -> dict:''',
'''# ── C2：目標與 HER-lite ────────────────────────────────────────────
def goal_pool() -> list:
    """(名稱, 判定函式)。判定只看環境狀態，不依賴模型 → 可當 reward oracle。"""
    return [
        ("開啟 Wi-Fi", lambda e: e.switches["wifi"]), ("關閉 Wi-Fi", lambda e: not e.switches["wifi"]),
        ("開啟藍牙", lambda e: e.switches["bluetooth"]), ("關閉藍牙", lambda e: not e.switches["bluetooth"]),
        ("開啟飛航模式", lambda e: e.switches["airplane"]), ("關閉飛航模式", lambda e: not e.switches["airplane"]),
        ("開啟深色主題", lambda e: e.switches["dark"]), ("關閉深色主題", lambda e: not e.switches["dark"]),
        ("開啟勿擾模式", lambda e: e.switches["dnd"]),
        ("亮度調到最高", lambda e: e.sliders["brightness"] == 4),
        ("音量調到最高", lambda e: e.sliders["media_volume"] == 4),
        ("打開計算機", lambda e: e.screen_id == "calculator"),
        ("打開設定", lambda e: e.screen_id == "settings_main"),
        ("回到首頁", lambda e: e.screen_id == "home"),
        ("捲動到最底", lambda e: e._screen().scroll_max > 0 and e._screen().scroll_dp >= e._screen().scroll_max),
    ]


def sample_goal(env, rng):
    pool = [(n, f) for n, f in goal_pool() if not f(env)]
    return rng.choice(pool) if pool else goal_pool()[0]


def goal_key(goal_name: str, obs: str) -> str:
    """目標條件化的 Q 鍵（表格法：把目標接在觀測前面）。"""
    return f"{goal_name}||{obs}"


def evaluate(agent: CuriosityAgent, episodes: int = 20, seed: int = 999) -> dict:''')

# 2) gate 用「訓練累計」的捲動覆蓋
s = s.replace('''def gate_pass(st: dict, train_screens: int = 0) -> bool:
    """基本操作門檻：畫面覆蓋（訓練）+ 捲動覆蓋 + 開關操作 + 動作準確度。"""
    screens = max(train_screens, st.get("screens_discovered", 0))
    return (screens >= 15 and st.get("toggle_flips", 0) >= 30
            and st.get("scroll_positions", 0) >= 5''',
'''def gate_pass(st: dict, train_screens: int = 0, train_scroll: int = 0) -> bool:
    """基本操作門檻：畫面覆蓋（訓練）+ 捲動覆蓋 + 開關操作 + 動作準確度。"""
    screens = max(train_screens, st.get("screens_discovered", 0))
    scrolls = max(train_scroll, st.get("scroll_positions", 0))
    return (screens >= 15 and st.get("toggle_flips", 0) >= 30
            and scrolls >= 5''')
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("goals + gate patched")
