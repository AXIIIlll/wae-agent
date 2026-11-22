# WAE 多 Agent 工作流（v1）

> 目標：把「我一個人從頭做到尾」改成「**規劃 → 平行實作 → 獨立驗證 → 彙整打包**」，
> 每個角色只看自己需要的東西，檔案不打架，驗證有證據。

---

## 角色

| 角色 | 誰 | 做什麼 | 不能做什麼 |
|---|---|---|---|
| **Lead（我）** | 主 agent | 拆目標、派工、協調檔案、彙整、最後對你回報 | 不在實作進行中同時改同一批檔 |
| **Planner** | `subagent_fork`（繼承上下文） | 讀現況，產出計畫：步驟、檔案清單、驗收命令、風險 | 不改任何檔案 |
| **Implementer A/B** | `subagent`（平行） | 各自只改**自己那組檔案** | 不改別人檔案、不跑 gradle |
| **Verifier** | `subagent`（只讀） | 跑測試／回歸／檢查 diff，貼命令與輸出 | 不改任何檔案 |
| **Packager** | 我或 `subagent` | 建 APK、寫 VERSION、存檔 | 不改功能碼 |

## 一輪的流程

```
你一句話（目標）
  │
  1. Lead 寫 BRIEFING（目標、限制、檔案範圍、驗收）
  │
  2. Planner（fork）→ plan.md：步驟 + 檔案所有權 + 驗收命令
  │
  3. Lead 派工：Implementer A、B（平行；檔案不相交）
  │      └ 各自回 handoff：改了什麼、怎麼驗、剩什麼
  │
  4. Lead 合併 → 單獨跑一次建置（gradle 不可平行）
  │
  5. Verifier（只讀）→ 證據：測試結果、回歸數字、diff 風險
  │
  6. Lead 對你回報：做了什麼、證據、還沒驗的
  │
  7. Packager：建 release + 快照 + VERSION
```

## 檔案所有權（避免打架）

| 區域 | 誰可以改 | 備註 |
|---|---|---|
| `core/**`（規則、檢索、記憶） | Implementer A | 改 API 要先在 BRIEFING 寫簽名 |
| `app/**/gen5/**`、`app/**/chat/**` | Implementer B | |
| `res/**`、`assets/**` 資料表 | Implementer A | TSV 不可有 BOM |
| `docs/**`、`bench/**`、`tools/**` | Lead 或 Verifier 指定的人 | 文件由我最後統一 |
| APK／`versionN/` | Packager only | |

**鐵則**
1. 一次最多 2 個 Implementer（超過就是整合地獄）
2. **gradle 只有 Lead / Packager 能跑**（平行會鎖 build 目錄）
3. 跨檔介面（例如 core 的函式簽名）由 Planner 先凍結，實作者照簽名
4. 每個 handoff 一定要有「怎麼驗」；沒有驗收條件的任務不派
5. Verifier 只貼證據，不下結論；結論由 Lead 下

## Handoff 格式（實作者回我）

```md
## Handoff: <任務>
- 改了：<檔案清單>
- 怎麼驗：<命令>
- 結果：<貼輸出摘要>
- 沒做／風險：<誠實列>
- 需要別人接手：<有/無>
```

## 驗收格式（Verifier 回我）

```md
## Verification
| 項目 | 命令 | 結果 | 證據 |
|---|---|---|---|
| 單元測試 | gradlew :core:test | PASS | 164 tests, 0 failed |
| 回歸 | tools/run_regression_v11.py | 11/11 | bench/... |
| diff 風險 | 看 git diff | 無 debug 殘留 | - |
```

## 什麼時候不用多 Agent

- 改一個檔案、改一行文案、跑一次建置 → 我直接做（派工成本比做事高）
- 需要來回討論設計 → 先跟你確認，不派工

## 現成範本

- `.agents/teamwork/BRIEFING.md`（派工單）
- `.agents/teamwork/handoff.md`（回報單）
- `.agents/teamwork/*/DISPATCH.md`（分工）
