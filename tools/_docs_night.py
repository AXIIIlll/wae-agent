import io
# 1) README 補「整晚學習」章節
p = "tools/symbolic_sim/README.md"
s = io.open(p, encoding="utf-8").read()
s += '''
## 整晚自主學習（C1 → C2）

```powershell
# 前景跑（可看到即時輸出）
C:\\Users\\Wade\\.dsh\\dsh-runtimes\\dsh-primary-runtime\\dependencies\\python\\python.exe `
  tools\\symbolic_sim\\run_overnight.py --hours 8

# 背景跑（關掉視窗也會繼續）
powershell -ExecutionPolicy Bypass -File tools\\symbolic_sim\\start_overnight.ps1
```

- **C1（前 6 分鐘）**：好奇心探索（無任務），熟悉 19 個原生 App 畫面、開關、捲動
- **C2（之後）**：目標條件訓練（15 個目標）+ **HER-lite**（沒達成目標但達成別的目標也算學到）
- 每 2 分鐘更新 `out/overnight/progress.jsonl`、`report.md`
- 每 2 分鐘存檢查點 `out/overnight/checkpoint.json.gz`（可 `--resume` 接續）
- 早上看 `docs/WAE_MORNING_CHECK.md`

### 已知特性（誠實）

- 符號環境的「世界」不大：C1 約 2 分鐘就 19/19 摸熟，之後主要靠 C2 目標與隨機初始狀態維持學習
- 表格 Q 的狀態數會隨目標條件化增加；`--max-q` 會在過大時清掉只出現一次的狀態
- 這個階段驗證的是「學習流程與獎勵設計」，不是真實 App 的複雜度
'''
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# 2) AGENTS.md：更新 E 槽路徑（SDK 已搬移）
p2 = "AGENTS.md"
s2 = io.open(p2, encoding="utf-8").read()
s2 = s2.replace('$env:JAVA_HOME=\'C:\\Users\\Wade\\.jdks\\jbr-21.0.11\'\n.\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon\n\n$adb="C:\\Users\\Wade\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"',
'$env:JAVA_HOME=\'C:\\Users\\Wade\\.jdks\\jbr-21.0.11\'\n$env:GRADLE_USER_HOME=\'E:\\WAE\\gradle-home\'\n.\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon\n\n# SDK 已搬到 E 槽（C 槽保留給系統）\n$adb="E:\\WAE\\android-sdk\\platform-tools\\adb.exe"')
s2 = s2.replace('- **TSV 資料檔一律不可有 BOM**', '- **所有產物一律放 E 槽**（C 槽快滿）：Gradle 用 `E:\\WAE\\gradle-home`、Android SDK 在 `E:\\WAE\\android-sdk`、資料在 `E:\\WAE\\datasets`、Python venv 在 `E:\\WAE\\venvs`。\n- **TSV 資料檔一律不可有 BOM**')
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("README + MORNING + AGENTS updated")
