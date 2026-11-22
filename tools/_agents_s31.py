import io
p = r'AGENTS.md'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''## 6. 文件''', '''## 6. 最近修正（避免復發）

- **不要恢復「App 啟動後自動回根頁」規則**（S31）：曾因 Maps 首頁被姿態模型判成 SEARCH_RESULTS 而按 BACK 退出 App，導致整條指令失敗。詳見 `docs/WAE_S31_RootAndSafetyFix.md`。
- **安全關鍵字比對**：ASCII 關鍵字不跨底線匹配；`SafetyGate` 比對前會移除 `[...]` 機器標籤（view id 等），避免 `edit_compose_post` 誤命中 `post`。

## 7. 文件''')
s = s.replace('''- 評測集與難度定義：`benchmark/README.md`''',
'''- S31 修正記錄（回根頁規則、安全誤攔）：`docs/WAE_S31_RootAndSafetyFix.md`
- 評測集與難度定義：`benchmark/README.md`''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('AGENTS updated')
