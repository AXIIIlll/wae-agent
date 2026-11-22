"""WAE 符號模擬器 v3：真實 dp 佈局 + **全部合成資料**（不碰真機內容、不含 AI App）。

真機基準（實測 Samsung SM-A5560）：
  1080×2340 px、450 dpi → density 2.8125 → **384×832 dp（19.5:9）**
  status bar 24dp、app bar 56dp、gesture bar 24dp、列表列 56dp、雙行列 80dp
  switch 52×32dp、底部導覽 80dp、計算機鍵盤 4 欄

內容政策（重要）：
  - **所有文字、聯絡人、訊息、行程、檔案都是合成資料**（張三/測試訊息 1/會議 10:00…）
  - 只建模 **AOSP 原生 App**（設定、計算機、時鐘、日曆、聯絡人、電話、訊息、檔案、相簿、相機）
  - 不建模任何 AI 助理類 App、不複製任何真機內容

動作空間（17）：BACK / SCROLL_DOWN / SCROLL_UP / HOME / SHADE(下拉通知) / TAP slot 0..11
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

MAX_SLOTS = 12
ACTION_BACK = 0
ACTION_SCROLL_DOWN = 1
ACTION_SCROLL_UP = 2
ACTION_HOME = 3
ACTION_SHADE = 4
ACTION_TAP_BASE = 5
NUM_ACTIONS = ACTION_TAP_BASE + MAX_SLOTS


@dataclass
class DeviceMetrics:
    w_px: int = 1080
    h_px: int = 2340
    density_dpi: int = 450
    status_bar_dp: float = 24.0
    app_bar_dp: float = 56.0
    gesture_bar_dp: float = 24.0

    @property
    def scale(self) -> float:
        return self.density_dpi / 160.0

    @property
    def w_dp(self) -> float:
        return round(self.w_px / self.scale, 1)

    @property
    def h_dp(self) -> float:
        return round(self.h_px / self.scale, 1)

    @property
    def aspect(self) -> float:
        return round(self.h_dp / self.w_dp, 3)

    def to_dict(self) -> dict:
        return {
            "w_px": self.w_px, "h_px": self.h_px, "density_dpi": self.density_dpi,
            "scale": round(self.scale, 4), "w_dp": self.w_dp, "h_dp": self.h_dp,
            "aspect": self.aspect, "status_bar_dp": self.status_bar_dp,
            "app_bar_dp": self.app_bar_dp, "gesture_bar_dp": self.gesture_bar_dp,
        }


PRESETS = {
    "samsung_a55": DeviceMetrics(1080, 2340, 450),   # 實機量測基準
    "pixel_7": DeviceMetrics(1080, 2400, 420),
    "compact_16_9": DeviceMetrics(1080, 1920, 420),
}


@dataclass
class Rect:
    x: float
    y: float
    w: float
    h: float

    def to_list(self) -> List[float]:
        return [round(self.x, 1), round(self.y, 1), round(self.w, 1), round(self.h, 1)]


@dataclass
class Node:
    slot: int
    family: str      # row / switch / slider / icon / list_item / button / text / tile
    role: str        # nav / setting / content / input / dead
    label: str
    rect: Rect = field(default_factory=lambda: Rect(0, 0, 0, 0))
    checked: bool = False
    value: int = 0
    subtitle: str = ""
    alive: bool = True


@dataclass
class Chrome:
    status_time: str = "9:41"
    app_title: str = ""
    back: bool = False
    nav_items: List[str] = field(default_factory=list)
    battery: int = 87
    shade: bool = False


@dataclass
class Screen:
    screen_id: str
    nodes: List[Node] = field(default_factory=list)
    scroll_dp: int = 0
    scroll_max: int = 0
    chrome: Chrome = field(default_factory=Chrome)

    def visible_nodes(self) -> List[Node]:
        return sorted(self.nodes, key=lambda n: n.slot)


SWITCH_LABELS = {
    "Wi-Fi": "wifi", "行動網路": "mobile", "飛航模式": "airplane", "無線基地台": "hotspot",
    "深色主題": "dark", "勿擾模式": "dnd", "省電模式": "saver", "藍牙": "bluetooth",
    "自動旋轉": "rotate", "手電筒": "flash",
}
SLIDER_LABELS = {"亮度": "brightness", "媒體音量": "media_volume", "鈴聲音量": "ring_volume"}

# ── 合成資料（全部假資料，與真機無關）────────────────────────────────
FAKE_CONTACTS = ["張三", "李四", "王五", "陳小明", "林美玲", "黃大文", "吳小雨"]
FAKE_MESSAGES = ["測試訊息 1", "測試訊息 2", "會議改到下午三點", "好的，收到"]
FAKE_EVENTS = ["會議 10:00", "午餐 12:00", "專案討論 15:30", "運動 18:00"]
FAKE_FILES = ["文件", "下載", "圖片", "音樂", "影片"]
FAKE_ALARMS = ["07:00 鬧鐘", "08:30 週末鬧鐘", "06:45 起床", "22:30 睡前"]

SETTINGS_POOL = ["網路與網際網路", "已連接的裝置", "應用程式", "通知", "電池",
                 "儲存空間", "音效與震動", "顯示", "安全性", "系統",
                 "協助工具", "數位健康與家長監護", "Google", "系統更新",
                 "關於手機", "開發人員選項", "帳號與備份", "位置資訊",
                 "安全與緊急狀況", "桌布與樣式"]
APP_POOL = ["計算機", "時鐘", "日曆", "聯絡人", "電話", "訊息", "檔案", "相簿",
            "相機", "設定", "錄音機", "天氣", "備忘錄", "瀏覽器", "郵件", "音樂"]


class SymbolicPhoneEnv:
    TOTAL_SCREENS = (
        "home", "shade", "settings_main", "network", "display", "sound", "battery",
        "storage", "apps", "calculator", "clock", "calendar", "contacts",
        "phone", "messages", "messages_thread", "files", "gallery", "camera",
    )
    NATIVE_APPS = ["計算機", "時鐘", "日曆", "聯絡人", "電話", "訊息", "檔案", "相簿", "相機", "設定"]

    def __init__(self, seed: int = 0, max_steps: int = 120,
                 device: DeviceMetrics | str = "samsung_a55", randomize: bool = False):
        self.rng = random.Random(seed)
        self.randomize = randomize
        self.m = PRESETS[device] if isinstance(device, str) else device
        self.max_steps = max_steps
        self.status_h = self.m.status_bar_dp
        self.appbar_h = self.m.app_bar_dp
        self.gesture_h = self.m.gesture_bar_dp
        self.body_top = self.status_h + self.appbar_h
        self.body_h = self.m.h_dp - self.body_top - self.gesture_h
        self._defaults()
        self.cum = {"toggle_flips": 0, "effective_scrolls": 0, "taps_total": 0,
                    "taps_with_change": 0, "valid_actions": 0, "invalid_actions": 0}
        self.visited_screens: set = set()
        self.steps = 0
        self._enter("home")

    def _defaults(self) -> None:
        self.switches: Dict[str, bool] = {
            "wifi": True, "mobile": True, "airplane": False, "hotspot": False,
            "dark": False, "dnd": False, "saver": False, "bluetooth": False,
            "rotate": True, "flash": False,
        }
        self.sliders: Dict[str, int] = {"brightness": 2, "media_volume": 3, "ring_volume": 3}
        self.positions: Dict[str, int] = {}
        self.calc_display = "0"
        self.current_event = ""
        self.current_contact = ""
        self.current_thread = ""
        self.photo_count = 0
        self.contacts = list(FAKE_CONTACTS)
        self.messages = list(FAKE_MESSAGES)
        self.events = list(FAKE_EVENTS)
        self.files = list(FAKE_FILES)
        self.alarms = list(FAKE_ALARMS)
        self.settings_rows_order = list(SETTINGS_POOL)
        self.apps_rows_order = list(APP_POOL)

    # ── 生命週期 ────────────────────────────────────────────────────
    def _randomize_data(self) -> None:
        """每回合重新抽合成資料：讓「新奇度」不會在幾分內就飽和。"""
        r = self.rng
        # 只隨機化「世界初始狀態」，不隨機化文字/清單本身：
        # 這樣狀態空間不會爆炸（表格 Q 才學得起來），但每回合仍有不同的探索目標。
        for k in self.switches:
            if k != "mobile":
                self.switches[k] = r.random() < 0.5
        for k in self.sliders:
            self.sliders[k] = r.randint(0, 4)
        # 少量資料變化（固定大小抽樣，維持狀態空間可控）
        self.events = r.sample(FAKE_EVENTS, k=r.randint(2, 4))
        self.alarms = r.sample(FAKE_ALARMS, k=r.randint(1, 2))

    def reset(self) -> str:
        self._defaults()
        if self.randomize:
            self._randomize_data()
        self.steps = 0
        self._enter("home")
        return self.observe()

    def _enter(self, screen_id: str) -> None:
        self.screen_id = screen_id
        self.visited_screens.add(screen_id)

    def _screen(self) -> Screen:
        return getattr(self, "_build_" + self.screen_id)()

    # ── 觀測 ────────────────────────────────────────────────────────
    def observe(self) -> str:
        s = self._screen()
        parts = [s.screen_id, f"sp={s.scroll_dp}/{s.scroll_max}", f"app={s.chrome.app_title}"]
        for n in s.visible_nodes():
            extra = ""
            if n.family == "switch":
                extra = ":on" if n.checked else ":off"
            elif n.family == "slider":
                extra = f":v{n.value}"
            label = n.label or ""
            parts.append(f"{n.slot}:{n.family}:{n.role}:{label}{extra}")
        if s.screen_id == "calculator":
            parts.append(f"calc={self.calc_display}")
        if s.screen_id == "messages_thread":
            parts.append(f"thread={self.current_thread}")
        if s.screen_id == "gallery":
            parts.append(f"photos={self.photo_count}")
        return "|".join(parts)

    def action_mask(self) -> List[bool]:
        mask = [False] * NUM_ACTIONS
        sid = self.screen_id
        mask[ACTION_HOME] = sid not in ("home",)
        mask[ACTION_SHADE] = True
        mask[ACTION_BACK] = sid not in ("home", "shade")
        s = self._screen()
        mask[ACTION_SCROLL_DOWN] = s.scroll_dp < s.scroll_max
        mask[ACTION_SCROLL_UP] = s.scroll_dp > 0
        for n in s.nodes:
            if n.alive and 0 <= n.slot < MAX_SLOTS:
                mask[ACTION_TAP_BASE + n.slot] = True
        return mask

    def step(self, action: int) -> Tuple[str, float, bool, dict]:
        self.steps += 1
        info: dict = {"changed": False, "kind": "noop"}
        before = self.observe()
        mask = self.action_mask()
        if not (0 <= action < NUM_ACTIONS) or not mask[action]:
            self.cum["invalid_actions"] += 1
            info["kind"] = "invalid"
            reward = -0.10
        else:
            self.cum["valid_actions"] += 1
            if action == ACTION_HOME:
                self._enter("home"); info["kind"] = "home"
            elif action == ACTION_SHADE:
                if self.screen_id == "shade":
                    self._back()
                else:
                    self._enter("shade")
                info["kind"] = "shade"
            elif action == ACTION_BACK:
                self._back(); info["kind"] = "back"
            elif action in (ACTION_SCROLL_DOWN, ACTION_SCROLL_UP):
                s = self._screen()
                delta = 80 if action == ACTION_SCROLL_DOWN else -80
                new_pos = max(0, min(s.scroll_max, s.scroll_dp + delta))
                if new_pos != s.scroll_dp:
                    self.positions[self.screen_id] = new_pos
                    self.cum["effective_scrolls"] += 1
                    if not hasattr(self, "scroll_positions"):
                        self.scroll_positions = set()
                    self.scroll_positions.add((self.screen_id, new_pos))
                    info["kind"] = "scroll"
            else:
                self.cum["taps_total"] += 1
                self._tap(action - ACTION_TAP_BASE, info)
            info["changed"] = before != self.observe()
            if info["changed"] and info["kind"] in ("nav", "toggle", "slider", "calc", "tap", "text"):
                self.cum["taps_with_change"] += 1
            reward = 0.0
        return self.observe(), reward, self.steps >= self.max_steps, info

    # ── 轉移 ────────────────────────────────────────────────────────
    BACK_MAP = {
        "shade": "home", "settings_main": "home", "network": "settings_main",
        "display": "settings_main", "sound": "settings_main", "battery": "settings_main",
        "storage": "settings_main", "apps": "settings_main",
        "calculator": "home", "clock": "home", "calendar": "home", "contacts": "home",
        "phone": "home", "messages": "home", "messages_thread": "messages",
        "files": "home", "gallery": "home", "camera": "home",
    }

    def _back(self) -> None:
        self._enter(self.BACK_MAP.get(self.screen_id, "home"))

    def _tap(self, slot: int, info: dict) -> None:
        node = next((n for n in self._screen().nodes if n.slot == slot and n.alive), None)
        if node is None:
            return
        label = node.label

        # 1) 開關 / 滑桿（任何頁面通用）
        if node.family == "switch":
            key = SWITCH_LABELS.get(label)
            if key:
                self.switches[key] = not self.switches[key]
                self.cum["toggle_flips"] += 1
                info["kind"] = "toggle"
            return
        if node.family == "slider":
            key = SLIDER_LABELS.get(label)
            if key:
                self.sliders[key] = (self.sliders[key] + 1) % 5
                info["kind"] = "slider"
            return

        # 2) 頁面跳轉
        if self.screen_id == "home":
            targets = {"設定": "settings_main", "計算機": "calculator", "時鐘": "clock",
                       "日曆": "calendar", "聯絡人": "contacts", "電話": "phone",
                       "訊息": "messages", "檔案": "files", "相簿": "gallery", "相機": "camera"}
            if label in targets:
                self._enter(targets[label]); info["kind"] = "nav"
            return

        if self.screen_id == "settings_main":
            targets = {"網路與網際網路": "network", "顯示": "display",
                       "音效與震動": "sound", "電池": "battery", "儲存空間": "storage",
                       "應用程式": "apps"}
            if label in targets:
                self._enter(targets[label]); info["kind"] = "nav"
            return

        if self.screen_id == "calculator" and node.family == "button":
            self._calc_tap(label, info)
            return

        if self.screen_id == "calendar" and node.family == "list_item":
            self.current_event = label; info["kind"] = "text"
            return

        if self.screen_id == "contacts" and node.family == "list_item":
            self.current_contact = label; info["kind"] = "text"
            return

        if self.screen_id == "messages" and node.family == "list_item":
            self.current_thread = label; self._enter("messages_thread"); info["kind"] = "nav"
            return

        if self.screen_id == "gallery" and node.family == "icon":
            self.photo_count += 1; info["kind"] = "text"
            return

        if self.screen_id == "camera" and label == "快門":
            self.photo_count += 1; info["kind"] = "text"
            return

        if self.screen_id == "clock" and node.family == "row":
            info["kind"] = "text"
            return

        if self.screen_id == "phone" and node.family == "button":
            info["kind"] = "text"
            return

    def _calc_tap(self, label: str, info: dict) -> None:
        if label.isdigit():
            if self.calc_display in ("0", "結果"):
                self.calc_display = label
            elif len(self.calc_display) < 4:
                self.calc_display += label
            info["kind"] = "calc"
        elif label == "C":
            self.calc_display = "0"; info["kind"] = "calc"
        elif label == "=":
            self.calc_display = "結果"; info["kind"] = "calc"
        elif label in ("+", "−", "×", "÷", "%"):
            info["kind"] = "calc"
        else:
            info["kind"] = "calc"

    # ── 佈局輔助 ────────────────────────────────────────────────────
    def _scroll(self, sid: str) -> int:
        return self.positions.get(sid, 0)

    def _row(self, slot: int, y: float, h: float, label: str, family: str = "row",
             role: str = "nav", checked: bool = False, value: int = 0,
             subtitle: str = "", inset: float = 16.0) -> Node:
        return Node(slot, family, role, label,
                    Rect(inset, y, self.m.w_dp - inset * 2, h),
                    checked=checked, value=value, subtitle=subtitle)

    def _list_screen(self, sid: str, title: str, rows: List[str], row_h: float = 56.0,
                     family: str = "row", role: str = "nav", subtitle: str = "",
                     back: bool = True) -> Screen:
        scroll = self._scroll(sid)
        content_h = len(rows) * row_h
        max_scroll = max(0, int(content_h - self.body_h))
        # 只把「目前可見」的列放進節點（slot 重新編號），其餘等捲動才出現
        nodes: List[Node] = []
        slot = 0
        for row_idx, label in enumerate(rows):
            y = self.body_top + row_idx * row_h - scroll
            if y + row_h <= self.body_top or y >= self.body_top + self.body_h:
                continue
            if slot >= MAX_SLOTS:
                break
            nodes.append(self._row(slot, y, row_h, label,
                                   family=family, role=role, subtitle=subtitle))
            slot += 1
        return Screen(sid, nodes, scroll, max_scroll, Chrome(app_title=title, back=back))

    # ── 畫面建構 ────────────────────────────────────────────────────
    def _build_home(self) -> Screen:
        w = self.m.w_dp
        cols, cell_w, cell_h = 4, (w - 32) / 4, 92.0
        nodes = []
        home_apps = [a for a in self.apps_rows_order if a in self.NATIVE_APPS]
        home_apps = (home_apps + self.NATIVE_APPS)[:MAX_SLOTS - 2]
        for i, label in enumerate(home_apps):
            r, c = divmod(i, cols)
            nodes.append(Node(i, "icon", "nav", label,
                              Rect(16 + c * cell_w + 8, self.body_top + 16 + r * cell_h, 56, 56)))
        nodes.append(Node(10, "text", "input", "搜尋",
                          Rect(16, self.body_top, w - 32, 40)))
        nodes.append(Node(11, "icon", "nav", "相機",
                          Rect(w - 72, self.m.h_dp - self.gesture_h - 96, 56, 56)))
        return Screen("home", nodes, 0, 0, Chrome(app_title="", battery=87))

    def _build_shade(self) -> Screen:
        """下拉快速設定（Material 3 風格）：6 個開關磚 + 亮度滑桿。"""
        w = self.m.w_dp
        nodes = []
        tiles = [("Wi-Fi", "wifi"), ("行動網路", "mobile"), ("藍牙", "bluetooth"),
                 ("飛航模式", "airplane"), ("勿擾模式", "dnd"), ("手電筒", "flash")]
        tile_w, tile_h = (w - 32 - 12) / 2, 72.0
        for i, (label, key) in enumerate(tiles):
            r, c = divmod(i, 2)
            nodes.append(Node(i, "tile", "setting", label,
                              Rect(16 + c * (tile_w + 12), self.body_top + 8 + r * (tile_h + 12),
                                   tile_w, tile_h),
                              checked=self.switches[key]))
        nodes.append(Node(6, "slider", "setting", "亮度",
                          Rect(16, self.body_top + 8 + 3 * (tile_h + 12), w - 32, 64),
                          value=self.sliders["brightness"]))
        return Screen("shade", nodes, 0, 0,
                      Chrome(app_title="快速設定", shade=True, battery=87))

    def _build_settings_main(self) -> Screen:
        return self._list_screen("settings_main", "設定", self.settings_rows_order)

    def _build_network(self) -> Screen:
        y = self.body_top + 8
        nodes = [
            Node(0, "switch", "setting", "Wi-Fi",
                 Rect(16, y, self.m.w_dp - 32, 64), checked=self.switches["wifi"]),
            Node(1, "switch", "setting", "行動網路",
                 Rect(16, y + 64, self.m.w_dp - 32, 64), checked=self.switches["mobile"]),
            Node(2, "switch", "setting", "飛航模式",
                 Rect(16, y + 128, self.m.w_dp - 32, 64), checked=self.switches["airplane"]),
            Node(3, "switch", "setting", "無線基地台",
                 Rect(16, y + 192, self.m.w_dp - 32, 64), checked=self.switches["hotspot"]),
            self._row(4, y + 264, 56.0, "已儲存的網路"),
            self._row(5, y + 320, 56.0, "數據用量"),
        ]
        return Screen("network", nodes, 0, 0, Chrome(app_title="網路與網際網路", back=True))

    def _build_display(self) -> Screen:
        y = self.body_top + 8
        nodes = [
            Node(0, "slider", "setting", "亮度",
                 Rect(16, y, self.m.w_dp - 32, 72), value=self.sliders["brightness"]),
            Node(1, "switch", "setting", "深色主題",
                 Rect(16, y + 72, self.m.w_dp - 32, 64), checked=self.switches["dark"]),
            self._row(2, y + 136, 56.0, "字型大小"),
            self._row(3, y + 192, 56.0, "螢幕逾時"),
            self._row(4, y + 248, 56.0, "自動旋轉"),
        ]
        return Screen("display", nodes, 0, 0, Chrome(app_title="顯示", back=True))

    def _build_sound(self) -> Screen:
        y = self.body_top + 8
        nodes = [
            Node(0, "slider", "setting", "媒體音量",
                 Rect(16, y, self.m.w_dp - 32, 72), value=self.sliders["media_volume"]),
            Node(1, "slider", "setting", "鈴聲音量",
                 Rect(16, y + 72, self.m.w_dp - 32, 72), value=self.sliders["ring_volume"]),
            Node(2, "switch", "setting", "勿擾模式",
                 Rect(16, y + 144, self.m.w_dp - 32, 64), checked=self.switches["dnd"]),
            self._row(3, y + 208, 56.0, "鈴聲"),
        ]
        return Screen("sound", nodes, 0, 0, Chrome(app_title="音效與震動", back=True))

    def _build_battery(self) -> Screen:
        y = self.body_top + 8
        nodes = [
            Node(0, "text", "content", "電量 87%",
                 Rect(16, y, self.m.w_dp - 32, 64)),
            Node(1, "switch", "setting", "省電模式",
                 Rect(16, y + 64, self.m.w_dp - 32, 64), checked=self.switches["saver"]),
            self._row(2, y + 128, 56.0, "電池用量"),
            self._row(3, y + 184, 56.0, "充電"),
        ]
        return Screen("battery", nodes, 0, 0, Chrome(app_title="電池", back=True))

    def _build_storage(self) -> Screen:
        rows = ["應用程式 12.4 GB", "圖片 3.1 GB", "影片 1.8 GB", "音訊 0.9 GB", "其他 2.2 GB"]
        return self._list_screen("storage", "儲存空間", rows, row_h=64.0,
                                 family="list_item", role="content")

    def _build_apps(self) -> Screen:
        return self._list_screen("apps", "應用程式", self.apps_rows_order, row_h=64.0,
                                 family="list_item", role="content")

    def _build_clock(self) -> Screen:
        y = self.body_top + 8
        nodes = [Node(0, "text", "content", "9:41", Rect(16, y, self.m.w_dp - 32, 56))]
        for i, label in enumerate(self.alarms):
            nodes.append(self._row(i + 1, y + 64 + i * 72.0, 72.0, label,
                                   family="row", role="content"))
        nodes.append(self._row(3, y + 64 + 2 * 72.0, 56.0, "新增鬧鐘"))
        return Screen("clock", nodes, 0, 0, Chrome(app_title="時鐘", back=True))

    def _build_calendar(self) -> Screen:
        y = self.body_top + 8
        nodes = [Node(0, "text", "content", "2026 年 11 月",
                      Rect(16, y, self.m.w_dp - 32, 48))]
        for i, label in enumerate(self.events):
            nodes.append(self._row(i + 1, y + 56 + i * 72.0, 72.0, label,
                                   family="list_item", role="content",
                                   subtitle="測試行程"))
        return Screen("calendar", nodes, 0, 0, Chrome(app_title="日曆", back=True))

    def _build_contacts(self) -> Screen:
        y = self.body_top + 8
        nodes = [Node(0, "text", "input", "搜尋聯絡人", Rect(16, y, self.m.w_dp - 32, 40))]
        for i, label in enumerate(self.contacts):
            nodes.append(self._row(i + 1, y + 48 + i * 64.0, 64.0, label,
                                   family="list_item", role="content",
                                   subtitle="0912-345-678"))
        return Screen("contacts", nodes, 0, 0, Chrome(app_title="聯絡人", back=True))

    def _build_phone(self) -> Screen:
        keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]
        display_h = 96.0
        keypad_top = self.body_top + display_h
        key_h = (self.m.h_dp - keypad_top - self.gesture_h) / 4
        nodes = [Node(0, "text", "content", "輸入號碼",
                      Rect(16, self.body_top, self.m.w_dp - 32, display_h))]
        for i, label in enumerate(keys):
            r, c = divmod(i, 3)
            w = (self.m.w_dp - 32) / 3
            nodes.append(Node(i + 1, "button", "content", label,
                              Rect(16 + c * w, keypad_top + r * key_h, w - 8, key_h - 10)))
        return Screen("phone", nodes, 0, 0, Chrome(app_title="電話", back=True))

    def _build_messages(self) -> Screen:
        y = self.body_top + 8
        nodes = [Node(0, "text", "input", "搜尋訊息", Rect(16, y, self.m.w_dp - 32, 40))]
        for i, label in enumerate(self.contacts[:4]):
            nodes.append(self._row(i + 1, y + 48 + i * 80.0, 80.0, label,
                                   family="list_item", role="content",
                                   subtitle=self.messages[i % len(self.messages)]))
        return Screen("messages", nodes, 0, 0, Chrome(app_title="訊息", back=True))

    def _build_messages_thread(self) -> Screen:
        y = self.body_top + 8
        nodes = [Node(0, "text", "content", self.current_thread or "對話",
                      Rect(16, y, self.m.w_dp - 32, 40))]
        for i, msg in enumerate(self.messages):
            nodes.append(Node(i + 1, "text", "content", msg,
                              Rect(16, y + 48 + i * 56.0, self.m.w_dp - 32, 48)))
        nodes.append(Node(5, "text", "input", "輸入訊息",
                          Rect(16, self.m.h_dp - self.gesture_h - 72, self.m.w_dp - 120, 48)))
        return Screen("messages_thread", nodes, 0, 0,
                      Chrome(app_title="對話", back=True))

    def _build_files(self) -> Screen:
        return self._list_screen("files", "檔案", FAKE_FILES, row_h=72.0,
                                 family="row", role="content")

    def _build_gallery(self) -> Screen:
        w = self.m.w_dp
        cell = (w - 48) / 3
        nodes = []
        for i in range(9):
            r, c = divmod(i, 3)
            nodes.append(Node(i, "icon", "content", f"照片{i + 1}",
                              Rect(16 + c * (cell + 8), self.body_top + 16 + r * (cell + 8),
                                   cell, cell)))
        return Screen("gallery", nodes, 0, 0, Chrome(app_title="相簿", back=True))

    def _build_camera(self) -> Screen:
        w, h = self.m.w_dp, self.m.h_dp
        return Screen("camera", [
            Node(0, "text", "content", "觀景窗",
                 Rect(0, self.body_top, w, h - self.body_top - self.gesture_h - 120)),
            Node(1, "button", "content", "快門",
                 Rect(w / 2 - 36, h - self.gesture_h - 110, 72, 72)),
            Node(2, "row", "setting", "模式：相片",
                 Rect(16, h - self.gesture_h - 150, w - 32, 32)),
        ], 0, 0, Chrome(app_title="相機", back=True))

    def _build_calculator(self) -> Screen:
        display_h = 120.0
        keys = [["C", "+/-", "%", "÷"], ["7", "8", "9", "×"], ["4", "5", "6", "−"],
                ["1", "2", "3", "+"], ["0", ".", "=", ""]]
        nodes = [Node(0, "text", "content", self.calc_display,
                      Rect(16, self.body_top, self.m.w_dp - 32, display_h))]
        slot = 1
        keypad_top = self.body_top + display_h
        key_h = (self.m.h_dp - keypad_top - self.gesture_h) / 5
        for r, row in enumerate(keys):
            for c, label in enumerate(row):
                if label == "" or slot >= MAX_SLOTS:
                    continue
                w = (self.m.w_dp - 16) / 4
                nodes.append(Node(slot, "button", "content", label,
                                  Rect(8 + c * w, keypad_top + r * key_h, w - 8, key_h - 8)))
                slot += 1
        return Screen("calculator", nodes, 0, 0, Chrome(app_title="計算機", back=True))

    # ── 統計 ────────────────────────────────────────────────────────
    def stats(self) -> dict:
        return {
            "screens_discovered": len(self.visited_screens),
            "screens_total": len(self.TOTAL_SCREENS),
            "toggle_flips": self.cum["toggle_flips"],
            "effective_scrolls": self.cum["effective_scrolls"],
            "taps_total": self.cum["taps_total"],
            "taps_with_change": self.cum["taps_with_change"],
            "valid_actions": self.cum["valid_actions"],
            "invalid_actions": self.cum["invalid_actions"],
            "scroll_positions": len(getattr(self, "scroll_positions", set())),
        }
