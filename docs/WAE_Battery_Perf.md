# 電力與效能量測

## 目前數字（SM-A5560 實測）

- 離線語音辨識：WAV 111–187ms；首次模型載入約 4 秒，之後 1–2 秒
- 助理開啟 → 可說話：約 1–2 秒（模型已解壓）
- APK 111.7 MB；首次解壓模型 +24MB（`filesDir/sherpa-zh-14m`）
- 常駐：無前景服務（最省電）；喚醒詞尚未實作

## 量測方法

```powershell
adb shell dumpsys batterystats --reset
# 使用 30 分鐘（含 10 次語音指令）
adb shell dumpsys batterystats | Select-String "com.wae.agent"
```

## 目標

- 待機耗電增加 < 3%/天（目前應接近 0，無常駐服務）
- 單次語音指令耗電 < 0.5%（待量測）
- 喚醒詞若上線：< 3%/天（需實測）
