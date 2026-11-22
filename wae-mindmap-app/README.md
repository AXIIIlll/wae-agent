# WAE 端到端流程心智圖（靜態版）

純 HTML / CSS 打造的一頁式心智圖，呈現 WAE 從一句指令到動作結束的完整流程。

## 執行

```powershell
cd E:\NewAndroidApp\wae-mindmap-app
python -m http.server 5173 --bind 127.0.0.1
```

或直接用瀏覽器開 `index.html`。

## 檔案

- `index.html` — 頁面
- `styles/tokens.css` — 設計變數（顏色／字體／圓角／陰影）
- `styles/site.css` — 版面與工具列、圖例
- `assets/WAE-flow-mindmap.svg` — 心智圖本體（4178 × 4803）

## 和設計稿的差異

- 設計稿的縮放／拖曳／下載 PNG 是 JS 互動；此靜態版以 CSS 等比縮放呈現全貌，工具列保留樣式但無行為（範圍要求純 HTML/CSS）。
