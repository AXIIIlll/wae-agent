import io
p = "tools/symbolic_sim/phone_env.py"
s = io.open(p, encoding="utf-8").read()

# 1) 列表改成「視窗式」：內容可以比畫面長，只有可見項才給 slot
s = s.replace("""    def _list_screen(self, sid: str, title: str, rows: List[str], row_h: float = 56.0,
                     family: str = "row", role: str = "nav", subtitle: str = "",
                     back: bool = True) -> Screen:
        scroll = self._scroll(sid)
        content_h = len(rows) * row_h
        max_scroll = max(0, int(content_h - self.body_h))
        nodes = [self._row(i, self.body_top - scroll + i * row_h, row_h, label,
                           family=family, role=role, subtitle=subtitle)
                 for i, label in enumerate(rows[:MAX_SLOTS])]
        return Screen(sid, nodes, scroll, max_scroll, Chrome(app_title=title, back=back))""",
"""    def _list_screen(self, sid: str, title: str, rows: List[str], row_h: float = 56.0,
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
        return Screen(sid, nodes, scroll, max_scroll, Chrome(app_title=title, back=back))""")

# 2) 設定頁擴充到 16 列（超過一屏 → 一定要捲動）
s = s.replace("""        rows = ["網路與網際網路", "已連接的裝置", "應用程式", "通知", "電池",
                "儲存空間", "音效與震動", "顯示", "安全性", "系統", "關於手機"]""",
"""        rows = ["網路與網際網路", "已連接的裝置", "應用程式", "通知", "電池",
                "儲存空間", "音效與震動", "顯示", "安全性", "系統",
                "協助工具", "數位健康與家長監護", "Google", "系統更新",
                "關於手機", "開發人員選項"]""")

# 3) 應用程式頁也擴充（超過一屏）
s = s.replace("""    def _build_apps(self) -> Screen:
        return self._list_screen("apps", "應用程式", self.NATIVE_APPS, row_h=64.0,
                                 family="list_item", role="content")""",
"""    def _build_apps(self) -> Screen:
        apps = self.NATIVE_APPS + ["錄音機", "天氣", "備忘錄", "計算機", "時鐘", "日曆", "電話"]
        return self._list_screen("apps", "應用程式", apps, row_h=64.0,
                                 family="list_item", role="content")""")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("viewport lists + longer settings/apps")
