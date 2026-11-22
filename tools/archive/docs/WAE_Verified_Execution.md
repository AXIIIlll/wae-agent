# 可驗證執行（Verified Execution）— 設計與路線

> 目的：讓「AI 在手機上操作 App」不再是黑盒。每個動作都有前置條件、策略檢查與證據；
> 無法證明安全時，預設拒絕或要求人工確認。

## 目前已有的部分

- `PolicyVerifier`：策略自動機 + 有界模型檢查，輸出 SAFE / NEED_CONFIRM / UNSAFE / UNDECIDABLE + 憑證 hash
- `SimSandbox`：執行前模擬與探測
- `VerifiedExecutor` / `ComputeEval`：契約、證據、反例
- `SafetyGate` / `SafetyAutomaton`：ALLOW / REQUIRE_CONFIRM / BLOCK / ABORT_SESSION
- 語音層：`VoiceRiskPolicy`（BLOCK/CONFIRM/SAFE）+ 確認卡 + 安全模式

## 缺口

1. `WAE_OMEGA_ENABLED` 仍是 false（shadow 只寫 log）
2. 憑證沒有對外呈現（使用者看不到「為什麼允許」）
3. 沒有可重播的驗證紀錄供第三方檢查

## 里程碑

| 階段 | 內容 | 驗收 |
|---|---|---|
| V1 | SAFE 動作附憑證摘要（assistant 顯示「已驗證」） | 100% SAFE 有憑證 |
| V2 | 每次執行寫入可重播紀錄（JSONL，含 hash） | 可用工具重播驗證 |
| V3 | Ω active：非 SAFE 一律走模擬與確認 | 0 個未驗證執行 |
| V4 | 對外白皮書 + demo（飛航模式完成多步任務） | 可重現 |

## 為什麼這是「劃時代」

Siri/Google 助理的決策在雲端、不可驗證；WAE 把「為什麼允許這個動作」變成可檢查的憑證，
且完全在本機。這是第一個把手機自動化從「相信它」變成「檢查它」的嘗試。
