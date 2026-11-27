## 這個 PR 做了什麼

<!-- 一句話說明；如果是修題目，寫「第 N 題：現象 → 根因 → 修法」 -->

## 根因（不是「我改了什麼」，而是「為什麼會壞」）

<!-- 例如：指紋把時鐘秒數算進去，導致畫面永遠「有變化」 → 假成功 -->

## 驗證

- [ ] `powershell -File tools\debt_report.ps1` 五項全 ok
- [ ] `.gradlew.bat :core:test :app:testFullDebugUnitTest` 通過
- [ ] 若動到決策／點擊路徑：**真機驗證過**，並貼上日誌
- [ ] 若新增資料列：TSV 無 BOM、無 emoji、數值進 `ui_tunables.tsv`

## 真機證據（動到行為時必填）

```
adb logcat -s WAE_TASK WAE_PICK WAE_OPTION WAE_MENU
```

<!-- 貼上關鍵幾行，例如：
WAE_PICK: find(option) top3=#30:TAB:最近記錄@2035,450:0.80
WAE_OPTION: bestControl '通話紀錄' → '最近記錄' score=0.80
WAE_TASK: status=DONE steps=2
-->

## 風險與影響範圍

<!-- 會不會影響其他題目？有沒有已知邊界？ -->

- [ ] 我確認這次改動不會降低其他題目的成功率（原因：＿＿＿）
