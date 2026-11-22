
---

# 第 3 部補篇 C　UI 繪製、姿態模型、模式挖掘、TaskOS 的逐項解剖

> 補篇 A 拆了語言與感知的每一步；補篇 B 拆了驗證、落盤、LLM、語音、記憶體。
> 這一篇拆**畫面是怎麼畫出來的**、**姿態是怎麼分類的**、**模式是怎麼被挖出來的**、
> **模板是怎麼展開成步驟的**。
>
> 這一篇的每個數字都對得回 `GhostView.kt`、`GlassDrawable.kt`、
> `PostureFeatures.kt`、`WaePatternMiningEngine.kt`、`TaskTemplates.kt` 的原始行。

---

## §6.31 幽靈的 Canvas 數學

【實作】`app/src/main/java/com/wae/agent/ui/ghost/GhostView.kt`（235 行）

### 6.31.1 設計座標系：為什麼是 120

【實作】`GhostView.kt:168`

```kotlin
canvas.scale(w / 120f, h / 120f)
```

**問題**：一個 View 會被用在 24dp（列表頭像）到 190dp（主視覺）之間。

**若直接在 View 座標畫**：

$$
\text{半徑} = f(w) \Rightarrow \text{每個尺寸都要重算}
$$

**用固定設計空間 + 一次縮放**：

$$
\text{路徑座標} \in [0, 120]^2, \quad \text{實際座標} = \text{路徑座標} \times \frac{w}{120}
$$

**為什麼是 120**：它要能整除常見的 dp 尺寸，且讓關鍵點落在整數：

| 設計值 | 120dp | 144dp | 190dp |
|---|---|---|---|
| 中心 60 | 60 | 72 | 95 |
| 眼睛 y=57 | 57 | 68.4 | 90.25 |
| 半徑 59 | 59 | 70.8 | 93.4 |

**注意 120 不是 100**：因為要留邊界。幽靈本體在 $[20, 98]$，即寬 78；加上光暈（半徑 59，中心 60）會碰到 $[1, 119]$。若用 100 設計空間，光暈會超出邊界被裁掉——**這正是曾經發生的 bug**（幽靈變成方框）。

$$
\text{bloom 範圍} = [60 - 59,\ 60 + 59] = [1,\ 119] \subset [0, 120]
$$

**所以 59 不是隨便選的**：它是「不超出 120 空間」的最大值。

$$
r_{\text{bloom}} < \min(60, 120 - 60) = 60
$$

取 59 留 1 單位餘裕。

### 6.31.2 身體路徑：三段貝茲與三段尾巴

【實作】`GhostView.kt:66-80`

```kotlin
bodyPath.moveTo(60f, 18f)                                  // 頭頂
bodyPath.cubicTo(37f, 18f, 20f, 33f, 20f, 54f)             // 左上弧
bodyPath.lineTo(20f, 80f)                                   // 左側直線
bodyPath.cubicTo(20f, 88f, 25f, 94f, 32f, 94f)              // 尾巴 1（凹）
bodyPath.cubicTo(38f, 94f, 40f, 88f, 46f, 88f)              // 尾巴 2（凸）
bodyPath.cubicTo(51f, 88f, 53f, 94f, 59f, 94f)              // 尾巴 3（凹）
bodyPath.cubicTo(65f, 94f, 67f, 88f, 72f, 88f)              // 尾巴 4（凸）
bodyPath.cubicTo(78f, 88f, 80f, 94f, 86f, 94f)              // 尾巴 5（凹）
bodyPath.cubicTo(93f, 94f, 98f, 88f, 98f, 80f)              // 右下弧
bodyPath.lineTo(98f, 54f)                                   // 右側直線
bodyPath.cubicTo(98f, 33f, 83f, 18f, 60f, 18f)              // 右上弧
bodyPath.close()
```

**這是一個什麼形狀**：上方是**半圓**（兩個 cubic 拼成），兩側**垂直直線**，底部是**波浪**（5 段 cubic 交替在 $y=94$ 與 $y=88$）。

**貝茲曲線的數學**：

$$
B(t) = (1-t)^3 P_0 + 3(1-t)^2 t P_1 + 3(1-t)t^2 P_2 + t^3 P_3, \quad t \in [0,1]
$$

**為什麼用 cubic 而不是 arc**：

| 方式 | 控制點 | 可調性 |
|---|---|---|
| `arcTo`（圓弧） | 無（只能調半徑） | 低 |
| `cubicTo` | 兩個控制點 | **高** |

幽靈的頭不是正圓，是「上寬下窄的橢圓」——要做出「頭比身體寬一點」的感覺，必須用 cubic 手動調控制點。

**逐段解剖左上弧**：

```
P0 = (60, 18)   頭頂中心
P1 = (37, 18)   往左的水平切線 → 決定「頭頂平坦的程度」
P2 = (20, 33)   往上的垂直切線 → 決定「左側直立的程度」
P3 = (20, 54)   左側起點
```

**控制點的幾何意義**：$P_1$ 與 $P_0$ 同 $y$ → 曲線在 $P_0$ 的切線是**水平**；$P_2$ 與 $P_3$ 同 $x$ → 曲線在 $P_3$ 的切線是**垂直**。

$$
B'(0) \propto P_1 - P_0 = (-23, 0) \quad\text{（水平）}
$$
$$
B'(1) \propto P_3 - P_2 = (0, 21) \quad\text{（垂直）}
$$

**這保證了「頭頂圓滑、側面直立」**——不需要算角度，只要對齊座標。

**尾巴的交替**：

| 段 | $P_0$ | $P_3$ | 形狀 |
|---|---|---|---|
| 1 | (20,88) | (32,94) | 下凹 |
| 2 | (38,94) | (46,88) | 上凸 |
| 3 | (51,88) | (59,94) | 下凹 |
| 4 | (65,94) | (72,88) | 上凸 |
| 5 | (78,88) | (86,94) | 下凹 |

**交替的規律**：

$$
y_{P_3} = \begin{cases} 94 & \text{奇數段}\\ 88 & \text{偶數段}\end{cases}
$$

**振幅**：

$$
\Delta y = 94 - 88 = 6
$$

6/120 = 5% 的高度差——肉眼看得出波浪，但不至於像齒輪。

**為什麼是三凹兩凸而不是五凸**：幽靈的尾巴（像裙擺）是「下垂的波浪」，視覺上「凹下去的地方是布的褶皺」。三個凹、兩個凸讓它看起來像布料而不是荷葉邊。

### 6.31.3 五個 Shader 的座標與色票

【實作】`GhostView.kt:82-132`

**（1）bodyPaint：線性漸層**

```kotlin
LinearGradient(0f, 0f, 42f, 120f, ...)
```

**方向向量**：

$$
\vec{d} = (42, 120) - (0, 0) = (42, 120)
$$

**這是「左上到右下」的斜向**，斜率：

$$
\frac{\Delta y}{\Delta x} = \frac{120}{42} \approx 2.86
$$

**為什麼斜而不垂直**：垂直漸層（$(0,0) \to (0,120)$）會讓身體均勻變暗，看起來像「漸層貼紙」。斜向讓**左上偏亮、右下偏藍**，模擬「光源在左上」。

**三個色停**：

| 位置 | RGBA | 意義 |
|---|---|---|
| 0.00 | (255,255,255, 0.52) | 頂部：近白、52% 不透明 |
| 0.55 | (214,230,255, 0.34) | 中段：淡藍、34% |
| 1.00 | (169,194,245, 0.20) | 底部：更藍、20% |

**不透明度的梯度**：

$$
\alpha : 0.52 \to 0.34 \to 0.20
$$

**這編碼「幽靈越往下越透明」**——底部要「化開」，不是硬邊。

**（2）sheenPaint：徑向漸層（內光）**

```kotlin
RadialGradient(40f, 22f, 58f, ...)
```

$$
\text{中心} = (40, 22), \quad r = 58
$$

**這是「左上角的一團柔光」**。它的半徑 58 大於身體寬度的一半（39），所以光會溢出到身體外——但被 `clipPath(bodyPath)` 裁掉（下節）。

**色停**：

| 位置 | RGBA |
|---|---|
| 0.00 | (255,255,255, 0.88) |
| 0.45 | (236,245,255, 0.30) |
| 1.00 | (236,245,255, 0) |

**0.88 的不透明度**：這是整個幽靈最亮的部分。**「內部有光源」的感覺全靠它。**

**（3）rimPaint：邊線漸層（一邊亮一邊化開）**

```kotlin
LinearGradient(4f, 10f, 104f, 104f, ...)
```

**方向**：從左上角 $(4,10)$ 到右下角 $(104,104)$。

**四個色停**：

| 位置 | 不透明度 | 效果 |
|---|---|---|
| 0.00 | 1.00 | 左上完全亮 |
| 0.34 | 0.80 | |
| 0.62 | 0.22 | |
| 0.88 | **0.00** | 完全消失 |

**這是「輪廓在右下角消失」的實作**：

$$
\alpha_{\text{rim}}(s) = \text{interp}(s), \quad s \in [0, 1]
$$

在 $s > 0.88$ 之後，輪廓完全透明——幽靈的右下角**沒有邊界**。

**為什麼要這樣**：均勻一圈的輪廓看起來像貼紙（有明確邊界）。「一邊亮一邊化開」讓它看起來像**霧氣凝成的形體**，邊界是模糊的。

**（4）bloomPaint：光暈**

```kotlin
RadialGradient(60f, 60f, 59f, ...)
```

**中心正好是身體中心**，半徑 59（前面算過：不超出 120 空間的最大值）。

**色停**：

| 位置 | RGBA |
|---|---|
| 0.00 | (147,197,253, 0.30) |
| 0.50 | (139,92,246, 0.14) |
| 1.00 | (139,92,246, 0) |

**注意顏色變化**：中心是**淡藍**，外圈是**紫**。

$$
\text{藍} \to \text{紫} \to \text{透明}
$$

**這是「冷暖過渡」**——讓光暈不是單色光，而是像霓虹管邊緣的色散。

**（5）mistPaint：底部薄霧**

```kotlin
RadialGradient(60f, 108f, 54f, ...)
```

**中心在 $y=108$**——比身體底部（94）更下面。

**這是刻意的**：霧的中心在身體**外面**，所以畫在身體裡的部分是「霧的上半部」，邊緣自然淡化。

$$
\text{中心 } y = 108, \quad r = 54 \Rightarrow \text{霧範圍} = [54, 162]
$$

而身體底部在 94，所以霧從 $y=54$ 開始（約身體中段）到 $y=94$（底部）。

**效果**：尾巴看起來「浸在霧裡」，不是硬邊。

### 6.31.4 為什麼要 `clipPath`

【實作】`GhostView.kt:181-185`

```kotlin
canvas.save()
canvas.clipPath(bodyPath)
canvas.drawCircle(41f, 24f, 52f, sheenPaint)
canvas.drawCircle(60f, 108f, 54f, mistPaint)
canvas.restore()
```

**理由**：sheen 與 mist 的半徑（52、54）都大於身體，若不裁切，光會畫到身體外面：

$$
\text{sheen 範圍} = [(41-52, 24-52),\ (41+52, 24+52)] = [(-11,-28),\ (93,76)]
$$

超出 $[0,120]$ 且超出身體範圍。

**`clipPath` 的成本**：

$$
T_{\text{clip}} = O(|\text{path segments}|) \times O(\text{canvas area})
$$

在硬體加速下，`clipPath` 是**stencil buffer** 操作，成本與面積成正比。對 190dp 的 View（570 px 平方 = 32 萬像素），成本約 0.1 ms。

**每秒 60 幀 → 6 ms/s**。可接受。

**但有一個陷阱**：`clipPath` 在硬體加速下對「非矩形路徑」支援有限。API 18+ 支援，但某些舊 GPU 會回退到軟體渲染。

**幽靈的路徑**：是 cubic 曲線，對硬體來說是「任意路徑」→ 可能觸發軟體渲染。

**這是已知的效能取捨**：視覺效果優先於幀率（因為它不是遊戲）。

### 6.31.5 動畫相位：為何 4.32 秒

【實作】`GhostView.kt:149-165`

```kotlin
val now = SystemClock.uptimeMillis()
val phase = if (animated) (now % 4320L) / 4320f else 0f
val ang = phase * 2f * PI.toFloat()
```

$$
\text{phase}(t) = \frac{t \bmod 4320}{4320} \in [0, 1)
$$

**4320 ms 的來源**：它是四個動畫週期的**最小公倍數**：

| 動畫 | 週期 |
|---|---|
| 漂浮 | 4320 ms |
| 呼吸 | 4320 ms |
| 眨眼 | 2160 ms |
| 眼神 | 4320 ms |

$$
\text{lcm}(4320,\ 2160) = 4320
$$

**為什麼要共用**：若各自不同週期，整個系統的狀態永遠不會回到原點：

$$
\exists t : \text{state}(t) = \text{state}(0) \iff t = \text{lcm}
$$

若沒有共同週期，動畫會有「突然跳一下」的接縫。

**為什麼是 4320 而不是 4000**：

$$
2160 \times 2 = 4320
$$

眨眼是「每 2.16 秒一次」，所以它有兩個週期並在 4.32 秒時對齊。

**為什麼是 2.16 秒眨一次**：人類眨眼頻率約 15–20 次/分鐘：

$$
\frac{60}{17.5} \approx 3.4\ \text{s}
$$

2.16 秒比人類快，但**這是刻意的**：較快的眨眼讓「有生命感」更明顯（使用者不會盯著幽靈看超過 4 秒）。

### 6.31.6 漂浮與呼吸的數學

```kotlin
val floatY = -sin(ang) * 6f
val breathe = 1f + 0.028f * sin(ang + PI/2)
```

**漂浮**：

$$
y(t) = -6 \sin(2\pi t / T)
$$

**負號的意義**：Canvas 的 $y$ 軸向下，所以 $-6\sin$ 表示「相位前半上升，後半下降」。

**振幅 6/120 = 5%**：

$$
\frac{6}{120} = 0.05
$$

對 190dp 的幽靈：

$$
6 \times \frac{190}{120} \approx 9.5\ \text{dp}
$$

約 3 mm——剛好是「看得出來在飄，但不會覺得在跳」。

**呼吸**：

$$
s(t) = 1 + 0.028 \sin\Big(\frac{2\pi t}{T} + \frac{\pi}{2}\Big)
$$

**為什麼有 $\pi/2$ 相位差**：它讓呼吸與漂浮**不同步**：

$$
\sin(\theta) \perp \sin(\theta + \pi/2) = \cos(\theta)
$$

若同相位，幽靈會「上升時同時脹大」——看起來像被拉長。正交相位讓它「上升時脹、下降時縮」，像漂浮的氣球。

**振幅 0.028**：

$$
1.028 - 1 = 0.028
$$

2.8% 的縮放。對 190dp 是 5.3dp 的變化——幾乎感覺不到，但**大腦能察覺**（這是「生命感」的來源）。

### 6.31.7 眨眼：一個註解與程式碼不一致的地方

```kotlin
// 眨眼：每 2.16 秒一次，閉 0.11 秒
val blinkPhase = (now % 2160L) / 2160f
val eyeScale = if (animated && blinkPhase > 0.945f && blinkPhase < 0.975f) 0.08f else 1f
```

**實際的閉眼時長**：

$$
(0.975 - 0.945) \times 2160 = 0.03 \times 2160 = 64.8\ \text{ms}
$$

**而註解寫 0.11 秒（110 ms）**。

**這是一個文件與程式碼的不一致**（documentation drift）。手冊記錄它，因為：

1. 若未來有人「照註解」調參數，會改錯
2. 64.8 ms 在 60 fps 下只有約 4 幀——**這正是「眨眼」該有的長度**（人類眨眼 100–150 ms，但動畫壓短才不突兀）

**正確的行為是 64.8 ms**，註解過時。

**閉眼時的 `eyeScale = 0.08`**：

$$
\text{ry} = 10 \times 0.08 = 0.8
$$

眼睛高度從 10 變成 0.8——幾乎是一條線。**不是 0**：完全消失會像「眼睛不見了」，留 0.8 讓它像「閉眼」。

### 6.31.8 眼神：分段線性

```kotlin
val lookX = when {
    !animated -> 0f
    phase in 0.55f..0.60f -> -(phase - 0.55f) / 0.05f * 5f
    phase in 0.60f..0.72f -> -5f
    phase in 0.72f..0.78f -> -(0.78f - phase) / 0.06f * 5f
    else -> 0f
}
```

**這是一條分段線性函數**：

$$
\text{lookX}(\phi) =
\begin{cases}
-100(\phi - 0.55) & 0.55 \le \phi \le 0.60 \quad\text{（往左移）}\\
-5 & 0.60 \le \phi \le 0.72 \quad\text{（停住）}\\
-100(0.78 - \phi) & 0.72 \le \phi \le 0.78 \quad\text{（回正）}\\
0 & \text{其他}
\end{cases}
$$

**時長換算**（$\phi \times 4320$ ms）：

| 區間 | 時長 |
|---|---|
| 移動 | 216 ms |
| 停住 | 518 ms |
| 回正 | 259 ms |

**為什麼是「移→停→回」而不是正弦**：

$$
\text{正弦} \Rightarrow \text{永遠在動}
$$

而真實的「瞥一眼」是**快速移動 → 凝視 → 快速回正**（saccade 模型）：

$$
\text{眼動} = \text{saccade} + \text{fixation} + \text{saccade}
$$

分段線性正是這個模型。

**斜率**：

$$
\frac{5}{0.05} = 100\ \text{設計單位/相位}
$$

即 216 ms 內移動 5 個設計單位：

$$
v = \frac{5}{216\ \text{ms}} = 0.023\ \text{單位/ms}
$$

對 190dp 是 $0.023 \times 190/120 = 0.036$ dp/ms ≈ 0.11 mm/ms——很快，但只移 8 mm。

### 6.31.9 光點：四顆的相位與上升

【實作】`GhostView.kt:219-234`

```kotlin
val specs = arrayOf(
    floatArrayOf(0.16f, 0.34f, 0f, 0.9f),      // x, y, phaseOffset, alphaGain
    floatArrayOf(0.86f, 0.40f, 900f, 0.7f),
    floatArrayOf(0.10f, 0.74f, 1800f, 0.6f),
    floatArrayOf(0.90f, 0.80f, 2700f, 0.75f)
)
```

**四顆的相位差**：

$$
\Delta = \frac{900}{4320} = 0.208
$$

即**均勻分布在週期上**（$1/4 = 0.25$，接近）。

**每顆的動畫**：

$$
t = \frac{(now + \text{offset}) \bmod 4320}{4320}
$$
$$
\text{rise} = (1 - t) \times 22
$$

**上升 22 個設計單位**：

$$
\frac{22}{120} = 0.183
$$

對 190dp 是 35dp ≈ 9 mm 的上升。

**透明度**：

$$
\alpha = \sin(t \pi) \times \text{gain}
$$

**這是一個「淡入→淡出」**：

| $t$ | $\sin(t\pi)$ |
|---|---|
| 0 | 0 |
| 0.5 | 1 |
| 1 | 0 |

**半徑**：

$$
r = 1.5 + 1.3(1 - t)
$$

**從 2.8 縮到 1.5**——光點上升時**變小**，模擬「飄散」。

**為什麼要光點**：靜態的幽靈即使有呼吸，也像「圖」。光點提供**第三層次的運動**（身體、眼睛、環境粒子），這是「活物感」的關鍵。

### 6.31.10 為什麼用 `postInvalidateOnAnimation`

```kotlin
if (animated) {
    drawMotes(canvas, now, w / 120f, h / 120f)
    postInvalidateOnAnimation()
}
```

**`postInvalidateOnAnimation` 的語意**：

$$
\text{請求在下一幀重繪} \Rightarrow \text{與 Choreographer 同步}
$$

**與 `postInvalidate()` 的差別**：

| API | 時機 |
|---|---|
| `invalidate()` | 立即排入 |
| `postInvalidate()` | 下一個訊息迴圈週期 |
| `postInvalidateOnAnimation()` | **下一個 vsync** |

**用 vsync 的理由**：避免在一個 vsync 週期內多次重繪（浪費電）。

**相位用 `uptimeMillis` 而非 delta 累加**：

$$
\text{phase} = f(t_{\text{now}}) \quad\text{不是}\quad \text{phase} += \Delta
$$

**這是刻意的**：若用累加，掉幀會導致動畫變慢：

$$
\text{掉 1 幀} \Rightarrow \text{動畫慢 16.7 ms}
$$

用絕對時間，動畫始終與牆上時鐘同步：

$$
\text{掉 10 幀} \Rightarrow \text{動畫「跳」過 167 ms，但總時長不變}
$$

**代價**：掉幀時動畫會「跳」，而不是「變慢」。對緩慢的漂浮動畫，跳躍不可見；對快速動畫（如眨眼）可能錯過。

**眨眼只有 3 幀**（64.8 ms / 16.7 ms ≈ 3.9 幀），若掉一幀可能完全看不到。**這是已知的視覺瑕疵**，但在中階機上不掉幀。

---

## §6.32 玻璃卡片的 shader 幾何

【實作】`app/src/main/java/com/wae/agent/ui/glass/GlassDrawable.kt`（144 行）

### 6.32.1 三層結構

```kotlin
override fun draw(canvas: Canvas) {
    canvas.drawPath(path, fill)        // 1. 底色
    if (highlight) canvas.drawPath(path, sheen)   // 2. 高光
    canvas.drawPath(path, line)        // 3. 邊線
}
```

**順序不可換**：

| 順序 | 若交換 | 後果 |
|---|---|---|
| fill 先 | 高光被蓋 | 看不到光澤 |
| line 最後 | 邊線被蓋 | 邊界消失 |

**繪製順序 = 疊放順序**（後畫的在上）。

### 6.32.2 fill：垂直漸層

```kotlin
LinearGradient(rect.left, rect.top, rect.left, rect.bottom, fillTop, fillBottom, CLAMP)
```

**方向向量**：

$$
\vec{d} = (0,\ h)
$$

**純垂直**（$x$ 不變）。這讓卡片「上亮下暗」，模擬「光從上方來」。

**預設色**：

$$
\text{fillTop} = \alpha\,34, \quad \text{fillBottom} = \alpha\,14
$$

$$
34/255 \approx 13.3\%, \qquad 14/255 \approx 5.5\%
$$

**為什麼只有 13% 白**：玻璃不是白，是**被背景染色的半透明**。太大就變成「白色貼紙」。

### 6.32.3 line：對角漸層

```kotlin
LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
    Color.argb(90,255,255,255), Color.argb(18,255,255,255), CLAMP)
```

**方向向量**：

$$
\vec{d} = (w,\ h)
$$

**這是「左上 → 右下」的對角**，斜率取決於卡片長寬比。

**不透明度**：

$$
90/255 \approx 35\% \to 18/255 \approx 7\%
$$

**效果**：左上邊框明顯、右下邊框幾乎消失。

**為什麼**：均勻的邊框看起來像「有框的盒子」。漸層邊框讓卡片像「一片玻璃的邊緣被光照到」——右上角的光照不到，所以邊緣淡。

### 6.32.4 sheen：對角高光

```kotlin
LinearGradient(rect.left, rect.top,
    rect.left + rect.width()*0.45f, rect.top + rect.height()*0.9f,
    Color.argb(40,255,255,255), Color.argb(0,255,255,255), CLAMP)
```

**終點的計算**：

$$
(x_1, y_1) = (0.45w,\ 0.9h)
$$

（相對卡片左上角）

**為什麼不是到右下角 $(w, h)$**：

$$
\text{到 } (0.45w, 0.9h) \Rightarrow \text{高光集中在左上角}
$$

若到 $(w,h)$，高光會鋪滿整個對角線；只到 45% 寬度讓它**局限在左上角**，像「一片斜射的光斑」。

**不透明度**：$40/255 \approx 16\%$ 到 $0$。

### 6.32.5 【底層】alpha 的疊加數學

當三層都畫在同一條路徑上，最終顏色是：

$$
C_{\text{final}} = C_{\text{line}} A_{\text{line}} + \Big(C_{\text{sheen}} A_{\text{sheen}} + \big(C_{\text{fill}} A_{\text{fill}} + C_{\text{bg}} (1 - A_{\text{fill}})\big)(1 - A_{\text{sheen}})\Big)(1 - A_{\text{line}})
$$

**這是 source-over 的遞推**：

$$
C_{\text{out}} = C_s A_s + C_d (1 - A_s)
$$

**逐層代入**：

1. fill 疊在背景上：$C_1 = W \cdot 0.133 + C_{\text{bg}} \cdot 0.867$
2. sheen 疊上去：$C_2 = W \cdot 0.157 + C_1 \cdot 0.843$
3. line 疊上去：$C_3 = W \cdot 0.35 + C_2 \cdot 0.65$

**數值**（假設背景是全黑）：

$$
C_1 = 255 \times 0.133 = 33.9
$$
$$
C_2 = 255 \times 0.157 + 33.9 \times 0.843 = 40.0 + 28.6 = 68.6
$$
$$
C_3 = 255 \times 0.35 + 68.6 \times 0.65 = 89.3 + 44.6 = 133.9
$$

**所以邊線的亮度約 134/255 ≈ 52%**——一條明顯的亮線。

**這就是「玻璃邊緣」的視覺來源**：不是單一的白色描邊，而是三層疊加後的結果。

### 6.32.6 為什麼用 `Paint.alpha` 而不是改 shader

```kotlin
override fun setAlpha(alpha: Int) {
    fill.alpha = alpha
    line.alpha = alpha
    sheen.alpha = alpha
}
```

**`Paint.alpha` 與 shader 的關係**：

$$
\alpha_{\text{effective}} = \alpha_{\text{shader color}} \times \frac{\alpha_{\text{paint}}}{255}
$$

**這是乘法調變**。所以 `setAlpha(128)` 會讓整個卡片半透明，且**保留內部的漸層比例**。

**若改 shader 的色值**（重建 `LinearGradient`）：

$$
\text{重建成本} = O(1)\ \text{但會配置物件}
$$

每次 `setAlpha` 都重建 → GC 壓力。而 `Paint.alpha` 是純數值設定。

**這是效能取捨**：`setAlpha` 被呼叫的頻率高（按壓回彈動畫每幀都調），所以必須 $O(1)$ 無配置。

---

## §6.33 背景：五個徑向漸層的疊加

【實作】`GlassDrawable.kt:74-144`

### 6.33.1 五層結構

```kotlin
canvas.drawColor("#050409")              // 0. 深底
// 1. 上左：紫  (0.08w, 0.02h) r=0.72w  a=120
// 2. 右上：粉  (w,      0.10h) r=0.60w  a=80
// 3. 右下：青  (w,      h)     r=0.66w  a=66
// 4. 左下：靛  (0,      h)     r=0.62w  a=80
// 5. 中央壓暗  (0.5w, 0.5h)   r=0.62·max(w,h)  a=150→90
```

### 6.33.2 為什麼用「四角 + 中央壓暗」

**四角**：讓玻璃有「彩色背景可以透」。若背景是純黑，玻璃的半透明白就只是灰。

**徑向半徑 vs 螢幕寬度的比例**：

| 角落 | $r / w$ |
|---|---|
| 上左 | 0.72 |
| 右上 | 0.60 |
| 右下 | 0.66 |
| 左下 | 0.62 |

$$
\bar{r} \approx 0.65w
$$

**若半徑 = w**：四個光暈會在中間**疊成白色**（四層 alpha 疊加）：

$$
\alpha_{\text{疊加}} = 1 - \prod(1 - \alpha_i) = 1 - (1-0.47)(1-0.31)(1-0.26)(1-0.31) \approx 0.81
$$

而中央還有 0.59 的黑色壓暗——但**順序是黑壓暗最後**，所以它會蓋掉光暈。

**若半徑太小（0.4w）**：四個角落是暗的，中間是空的——玻璃沒東西可透。

**0.65w 的效果**：光暈在邊緣最亮，向中心衰減，中心留下一點色彩但不刺眼。

### 6.33.3 中央壓暗的數學

```kotlin
RadialGradient(w/2, h/2, 0.62·max(w,h),
    intArrayOf(argb(150,0,0,0), argb(90,0,0,0)),
    floatArrayOf(0.1f, 1f), CLAMP)
```

**中心 alpha 150（59%），邊緣 alpha 90（35%）**。

$$
\alpha(0) = 0.588, \quad \alpha(1) = 0.353
$$

**這是一個「反向」的徑向漸層**：中心**比邊緣更黑**。

**為什麼要中心更黑**：

| 若中心更亮 | 若中心更黑 |
|---|---|
| 中間刺眼，玻璃卡片的文字看不清 | 中間暗，文字對比高 |

**玻璃卡片需要「深色底」才透得出來**——這是 iOS Liquid Glass 的核心：玻璃不是加亮，是**讓背景透過的同時壓低亮度**。

**色停位置 0.1 / 1.0**：

$$
\text{中心 } 10\% \text{ 範圍內幾乎是純 150 alpha}
$$

**這讓中心有一個「暗核」**——不是均勻變暗。

### 6.33.4 【底層】五次全螢幕 `drawRect` 的成本

$$
T = 5 \times W \times H \times c_{\text{pixel}}
$$

對 1080×2340：

$$
5 \times 2.5 \times 10^6 = 1.25 \times 10^7\ \text{像素}
$$

**這是很貴的**：一個全螢幕的漸層填充約 2–4 ms。

**為什麼可以接受**：背景**只在第一次繪製**（Drawable 沒有 `invalidate` 自己）。而且它被設為 `background`，系統會快取。

**若背景要每幀重繪**（例如動畫），5 層會吃掉 10–20 ms/幀 → 掉幀。

**這是為什麼背景是靜態的**：不是設計偷懶，是效能預算。

---

## §6.34 姿態分類：12 維特徵的每一維

【實作】`app/src/main/java/com/wae/agent/core/posture/PostureFeatures.kt`

### 6.34.1 特徵向量的定義

$$
\phi \in \mathbb{R}^{12}
$$

| 索引 | 公式 | 語意 |
|---|---|---|
| 0 | $\text{centerDensity} + \text{scrollableRatio}$ | 列表類 |
| 1 | $2.5 \times \text{editableRatio}$ | 表單類 |
| 2 | $(1 - \text{topAreaRatio})$ 若多視窗 | 對話框（小視窗） |
| 3 | $\text{topAreaRatio}$ 若多視窗 | 全螢幕覆蓋 |
| 4 | $1.5 \times \text{bottomDensity}$ | 底部導覽 |
| 5 | $0.9 \times (\text{bottomDensity} + \text{centerDensity})$ | 導覽 + 列表 |
| 6 | $0.8 \times (\text{topDensity} + \text{centerDensity} + \text{scrollableRatio})$ | 搜尋結果 |
| 7 | $0.7 \times \text{centerDensity}$ | 瀑布流 |
| 8 | Material 類別比 | 元件庫 |
| 9 | Cupertino 類別比 | 元件庫 |
| 10 | Flutter 類別比 | 元件庫 |
| 11 | $0.25$（常數） | 偏置 |

### 6.34.2 為什麼係數是 2.5 / 1.5 / 0.9 / 0.8 / 0.7

**這些係數在放大「訊號的判別力」**：

**索引 1（可編輯比例）乘 2.5**：因為「可編輯元素」在一般頁面裡很少（< 10%）。若不放大，表單頁的特徵值只有 0.1，被其他維度的 0.5+ 淹沒。

$$
\text{editableRatio} = 0.1 \Rightarrow \phi_1 = 0.25
$$

**索引 4（底部密度）乘 1.5**：底部導覽列通常只有 3–5 個項目，佔比 $5/100 = 5\%$。乘 1.5 讓它到 7.5%——仍然小，但**足夠與「沒有底部導覽」區分**。

**索引 0 是相加而不是相乘**：`centerDensity + scrollableRatio` 兩個訊號都指向「列表」，所以相加（聯集）。

### 6.34.3 為什麼索引 11 是常數 0.25

$$
\phi_{11} = 0.25 \quad\text{（常數）}
$$

**這是 bias term**。

**為什麼不放在模型裡當 bias**：

$$
\text{softmax}(W\phi + b)
$$

而 $b$ 可以由 $w_{11} \times 0.25$ 吸收。**把它放進特徵向量**讓「權重矩陣 $W$ 是唯一的參數」，簡化訓練（不用處理 bias 的梯度）。

**代價**：多一個維度（12 vs 11）。可忽略。

### 6.34.4 softmax 分類

【實作】`app/src/main/java/com/wae/agent/core/posture/PostureModel.kt`

$$
P(y = k \mid \phi) = \frac{\exp(w_k^\top \phi)}{\sum_{j=1}^{8} \exp(w_j^\top \phi)}
$$

**注意：8 類**，而特徵是 12 維。

**為什麼是 8 類**：`PagePosture` 的前 8 個值（LIST_FEED、FORM_INPUT、MODAL_DIALOG、BOTTOM_SHEET、DETAIL_PAGE、NAV_HOME、SEARCH_RESULTS、WATERFALL_GRID）。

**輸出**：

$$
\hat{y} = \arg\max_k P(y=k \mid \phi)
$$

### 6.34.5 A/B：啟發式 vs 模型

【實作】`WaePatternMiningEngine.kt:82-95`

```kotlin
val heuristicIdx = run {
    var best = 0
    var bestVal = -1e9f
    for (cls in 0 until 8) {
        if (featureVec[cls] > bestVal) { bestVal = featureVec[cls]; best = cls }
    }
    best
}
```

**這是「取最大特徵」的啟發式**：哪一維最大就判為哪一類。

**它為什麼可能錯**：

$$
\phi_0 = \text{centerDensity} + \text{scrollableRatio}
$$

在「設定頁」（列表）與「搜尋結果」（也有列表）上，$\phi_0$ 都很大。啟發式會把搜尋結果判成一般列表。

**模型能做對的原因**：它學到 $\phi_6$（搜尋結果特徵）的權重。

**保留 A/B 的價值**：可以量測「模型是否真的比啟發式好」。

$$
\text{保留 heuristiIdx} \Rightarrow \text{可離線比較兩者}
$$

**這是工程誠實**：不假設模型一定更好，而是留一條可比較的路。

---

## §6.35 模式挖掘：tier 升級的門檻數學

【實作】`app/src/main/java/com/wae/agent/core/WaePatternMiningEngine.kt`

### 6.35.1 模式鍵

$$
\text{key} = \text{posture} \;\|\; \text{'}|\text{'} \;\|\; \text{intentTag}
$$

**為什麼是這兩個維度**：

| 維度 | 意義 |
|---|---|
| posture | 頁面類型（列表/表單/對話框） |
| intentTag | 要做什麼（`SEARCH`/`TOGGLE`/`NAVIGATE`） |

**這編碼了「在這種頁面上，做這件事，通常要點哪一類元件」**。

### 6.35.2 信心度

```kotlin
val confidence: Float
    get() = if (totalCount <= 0) 0f else successCount.toFloat() / totalCount.toFloat()
```

$$
\text{conf} = \frac{n_{\text{success}}}{n_{\text{total}}}
$$

**這是「原始比率」，不是貝氏後驗。**

**與 §3.11 的路由對比**：

| 機制 | 信心度公式 | 為什麼 |
|---|---|---|
| 路由 | $(s+\alpha)/(n+2\alpha)$ | 需要小樣本時穩定 |
| 模式 | $s/n$ | **未校準，但門檻補償** |

**為什麼這裡可以用原始比率**：因為匹配時要求 $\text{conf} \ge 0.75$ 且 $n$ 有下限（見下），所以「小樣本膨脹」被門檻擋住。

$$
n=1, s=1 \Rightarrow \text{conf}=1.0 \quad\text{但 tier 仍是 PROVISIONAL}
$$

### 6.35.3 三級的升級條件

```kotlin
existing.tier = when {
    verifiedPackages.size >= 3 && confidence >= 0.85f -> GLOBAL_VERIFIED
    successCount >= 2 && confidence >= 0.75f -> APP_LOCAL
    else -> PROVISIONAL
}
```

**升級的聯合條件**：

$$
\text{APP\_LOCAL} \iff n_s \ge 2 \land \frac{n_s}{n} \ge 0.75
$$

$$
\text{GLOBAL\_VERIFIED} \iff |\text{packages}| \ge 3 \land \frac{n_s}{n} \ge 0.85
$$

**逐項解剖**：

| 條件 | 為什麼 |
|---|---|
| $n_s \ge 2$ | 一次成功可能是巧合 |
| $|\text{packages}| \ge 3$ | 跨 3 個 App 都成立 → 這是「通用 UI 模式」，不是單一 App 的特性 |
| $0.75$ vs $0.85$ | 全域化要求更高的信心（因為影響範圍更大） |

**數值驗證**：

若 $n_s = 2$，要 $\text{conf} \ge 0.75$：

$$
\frac{2}{n} \ge 0.75 \Rightarrow n \le 2.67 \Rightarrow n \le 2
$$

**這意味著 $n=2, s=2$（100% 成功）才能升到 APP_LOCAL**。若 $n=3, s=2$（67%），不達標。

$$
n_s=2, n=2 \Rightarrow \text{conf}=1.0 \ge 0.75 \checkmark
$$
$$
n_s=2, n=3 \Rightarrow \text{conf}=0.667 < 0.75 \times
$$

**所以 $n_s \ge 2$ 這個條件在 $n=2$ 時是「兩次都成功」**——比看起來嚴格。

### 6.35.4 匹配時的三道檢查

```kotlin
val scopeAllowed = (pattern.tier == GLOBAL_VERIFIED) ||
    (pattern.tier == APP_LOCAL && pattern.verifiedPackages.contains(packageName))
if (!scopeAllowed || pattern.confidence < 0.75f) return null
```

**三道**：

| 檢查 | 條件 |
|---|---|
| 作用域 | GLOBAL 或（APP_LOCAL 且這個套件在驗證清單） |
| 信心 | $\ge 0.75$ |
| 角色/區帶 | `roleTag` 相符 **或** `gridZone` 相符 |
| 語意守衛 | `hybridSimScore >= minRequiredSim`（預設 0.45） |

**注意第 3 個是「或」**：

$$
\text{roleMatched} \lor \text{zoneMatched}
$$

**為什麼是「或」**：角色（`roleTag`）與區帶（`gridZone`）是兩個獨立線索。在列表頁，「搜尋按鈕」的角色是 `search`，區帶是 `TOP_BAR`。若只認角色，遇到自訂角色標籤就失效；若只認區帶，頂部所有按鈕都會命中。

### 6.35.5 為什麼「零預設模式」

```kotlin
/**
 * 零內建預設模式（Zero Seeded Patterns）：
 * patternStore 初始為空，所有模式必須由真實執行軌跡挖掘產生
 */
```

【實作】`WaePatternMiningEngine.kt:12-14`

**這是一條設計原則**：不預先塞入「我猜的模式」。

$$
\text{patternStore}_{\text{init}} = \emptyset
$$

**理由**：

| 若預塞模式 | 若不預塞 |
|---|---|
| 猜錯的模式會誤導 | 只有真實驗證過的才有 |
| 無法區分「猜的」與「學的」 | 全部都是學的 |
| 冷啟動「看起來很好」 | 冷啟動什麼都沒有 |

**代價**：冷啟動時模式庫是空的，所有指令走完整決策。**這是刻意的**——寧可慢，不要錯。

---

## §6.36 TaskOS 模板 DSL：`parseSteps` 的 token 化

【實作】`app/src/main/java/com/wae/agent/core/taskos/TaskTemplates.kt`

### 6.36.1 資料格式

```
template<TAB>pattern<TAB>steps
```

**三欄**：

| 欄 | 內容 |
|---|---|
| `template` | 模板 id（如 `settings-version`） |
| `pattern` | 正規表示式（比對指令） |
| `steps` | `\|` 分隔的步驟 DSL |

### 6.36.2 步驟 DSL 的語法

```
LAUNCH_APP:設定 | TAP:關於 | COLLECT:LONGEST_NUMBER:version | BACK
```

**每個 token 的結構**：

$$
\text{token} = \text{verb} \;\text{':'}\; \text{arg}_1 \;\text{':'}\; \text{arg}_2 \ldots
$$

**已知的 verb**：

| verb | 意義 | 參數 |
|---|---|---|
| `LAUNCH_APP` | 開 App | 目標 |
| `TAP` | 點擊 | 目標文字 |
| `TYPE` | 輸入 | 內容（可 `$var`） |
| `SUBMIT` | 送出 | — |
| `COLLECT` | 收集 | 策略:變數名 |
| `BACK` | 返回 | — |
| `WAIT` | 等待 | 毫秒 |

**`COLLECT` 的兩種策略**：

```
COLLECT:LONGEST_NUMBER:version    → 取畫面上最長的數字
COLLECT:REGEX:battery:\d+%        → 用正則抓
```

### 6.36.3 變數綁定

```
LAUNCH_APP:$app | TAP:搜尋 | TYPE:$query | SUBMIT
```

**`$var` 的解析**：

$$
\text{arg} = \text{'\$'} \;\|\; \text{name} \Rightarrow \text{查 TaskContext}
$$

**若變數不存在**：

```kotlin
sealed class Start {
    data class NeedVars(val templateId: String, val missing: Set<String>) : Start()
}
```

【實作】`TaskOsEngine.kt:26-29`

**這是一個「反問」機制**：

$$
\text{missing} \neq \emptyset \Rightarrow \text{Start.NeedVars} \Rightarrow \text{UI 反問}
$$

### 6.36.4 為什麼模板「不硬編關鍵字」而用正則

```kotlin
Template(id, Regex(pattern), stepsSpec)
```

**正則 vs 關鍵字清單**：

| 方式 | 表達力 | 維護 |
|---|---|---|
| 關鍵字清單 | 低（只能完全匹配） | 要列舉所有變體 |
| 正則 | 高（可表達「A 後面接 B」） | 一條規則抵十條 |

**範例**：

```
(?i)(?:設定|设置|偏好).*(?:版本)
```

匹配：
- 「設定的版本」
- 「設定裡看版本」
- 「设置-版本」

**而關鍵字清單需要**：`["設定的版本", "設定裡看版本", ...]` — 列不完。

### 6.36.5 為什麼 `pattern` 要 `(?i)`

```kotlin
"settings-version\t(?i)(?:設定|...|version).*(?:版本|...)\t..."
```

**`(?i)` = CASE_INSENSITIVE**：

$$
\text{匹配} \Rightarrow \text{"Version"} = \text{"version"}
$$

**因為**：使用者可能打英文大寫（「Version」），或 App 標籤是英文。

### 6.36.6 為什麼模板的預設是「空表 + fallback」

```kotlin
/**
 * 若無任何模板命中 → NoMatch → 走既有 fallback（decomposeCommandIntoLocalGoals）
 */
```

$$
\text{start}(c) = \text{NoMatch} \Rightarrow \text{fallback}
$$

**這保證了「模板不是必需品」**：

| 若模板是必需品 | 若模板是選配 |
|---|---|
| 沒有模板 → 完全不能做事 | 沒有模板 → 走規則管線 |

**設計原則**：**新增機制不能讓舊機制失效**。

---

## §6.37 SkillCompiler：從會話壓縮成技能

【實作】`app/src/main/java/com/wae/agent/core/taskos/SkillCompiler.kt`

### 6.37.1 它做什麼

$$
\text{session} \xrightarrow{\text{compile}} \text{skill} \xrightarrow{\text{save}} \text{skills.txt}
$$

**session**：一次實際執行的記錄（步驟、結果）
**skill**：可重用的模板

### 6.37.2 上限

```kotlin
private const val MAX_SKILLS = 200
```

$$
|\text{skills}| \le 200
$$

**為什麼是 200**：

| 面向 | 計算 |
|---|---|
| 檔案大小 | 200 × 平均 200 bytes ≈ 40 KB |
| 啟動載入 | 40 KB 解析 ≈ 5 ms |
| 記憶體 | 200 × 200 bytes ≈ 40 KB |

**200 是「夠用且不會變成負擔」的界限**。

**超過怎麼辦**：不寫入（`save` 回 false）。**不是淘汰舊的**——因為「哪個舊的該淘汰」沒有好答案。

### 6.37.3 檔案格式

```kotlin
private const val HEADER = "# WAE_TASK_SKILLS v1"
```

**為什麼第一行是版本標記**：

$$
\text{格式變更} \Rightarrow \text{舊檔不相容}
$$

有 `v1` 就能在未來做「檢查版本，不符則重建」。

### 6.37.4 步驟的序列化

```kotlin
fun toStepsSpec(steps: List<TaskStep>): String
```

**把結構化的步驟轉回 DSL 字串**：

$$
\text{TaskStep} \rightarrow \text{"VERB:arg|VERB:arg"}
$$

**為什麼要來回轉**：

| 存結構化 JSON | 存 DSL 字串 |
|---|---|
| 檔案大 | 小 |
| 解析慢 | 快 |
| 需要 schema | 與模板共用解析器 |

**選 DSL 是因為「與模板共用同一套解析器」**——`parseSteps` 同時服務模板與技能，減少一份程式碼。

### 6.37.5 逃脫與反逃脫

```kotlin
private fun esc(raw: String): String
private fun unesc(raw: String): String
```

**問題**：步驟參數可能含 `:` 或 `|`（例如 `TYPE:台北|101`）。

$$
\text{"台北|101"} \Rightarrow \text{被切成兩個 token}
$$

**解法**：轉義（例如 `\|` → 字面 `|`）。

**這是序列化的經典問題**：分隔符必須可轉義。

### 6.37.6 為什麼技能要「編譯」而不是「記錄」

**若直接記錄 session**：

$$
\text{skill} = \text{原始 session}
$$

**問題**：session 含「這次的具體值」（`$query = "貓"`），下次不能重用。

**編譯做的事**：把具體值抽成變數。

$$
\text{session}(\text{TYPE:"貓"}) \xrightarrow{\text{compile}} \text{skill}(\text{TYPE:}\$\text{query})
$$

**這是「抽象化」**——從一次具體經驗提煉出可重用的模式。

---

## §6.38 alpha 合成的統一數學

**這一節把前面散落的 alpha 計算收攏。**

### 6.38.1 source-over 的定義

$$
C_{\text{out}} = C_s A_s + C_d (1 - A_s)
$$
$$
A_{\text{out}} = A_s + A_d (1 - A_s)
$$

**這是最常用的混合模式**（Porter-Duff 的 `SRC_OVER`）。

### 6.38.2 兩層疊加的實例

**玻璃卡片疊在背景上**：

設背景 $C_d = 20$（深灰）、$A_d = 1$（不透明）
設卡片 $C_s = 255$（白）、$A_s = 0.133$

$$
C_{\text{out}} = 255 \times 0.133 + 20 \times 0.867 = 33.9 + 17.3 = 51.2
$$

**所以玻璃卡片在深灰背景上呈現 $C \approx 51$**——比純灰亮一點，比純白暗很多。

**這就是「玻璃」的數值定義**：

$$
\text{玻璃} = \text{背景} + 13\% \times (\text{白} - \text{背景})
$$

### 6.38.3 `Paint.alpha` 的乘法調變

$$
A_{\text{effective}} = \frac{\alpha_{\text{paint}}}{255} \times A_{\text{shader}}
$$

**所以 `bodyAlpha = 0.5` 的幽靈**：

$$
A_{\text{body}} = 0.5 \times 0.52 = 0.26
$$

即 26% 不透明。**比設定的 52% 更透明**——這是「乘兩次」的結果。

**這是設計意圖**：`bodyAlpha` 是「整體淡出」，不改變內部的相對透明度。

### 6.38.4 Ganesh/Skia 的預乘 alpha

**GPU 通常用預乘 alpha**：

$$
C_{\text{premul}} = C \times A
$$

**所以在 GPU 上**：

$$
C_{\text{out}} = C_s^{\text{premul}} + C_d^{\text{premul}} (1 - A_s)
$$

**這與非預乘的公式等價**，但少一次乘法。

**為什麼要知道這個**：若手動計算預期顏色，要用對公式，否則會覺得「實際顏色比算的亮」。

---

## §6.39 動畫的取樣與掉幀

### 6.39.1 `postInvalidateOnAnimation` 與 Choreographer

$$
\text{Choreographer} \xrightarrow{\text{vsync}} \text{doFrame}(t) \rightarrow \text{onDraw}
$$

**vsync 週期**：

$$
T_{\text{vsync}} = \frac{1000}{f}\ \text{ms}
$$

| 螢幕 | $f$ | $T$ |
|---|---|---|
| 60 Hz | 60 | 16.67 ms |
| 90 Hz | 90 | 11.11 ms |
| 120 Hz | 120 | 8.33 ms |

**這意味著同一個動畫在不同螢幕上的幀數不同**：

$$
n_{\text{frames}} = \frac{T_{\text{anim}}}{T_{\text{vsync}}}
$$

對 4.32 秒的幽靈循環：

| 螢幕 | 幀數 |
|---|---|
| 60 Hz | 259 |
| 90 Hz | 389 |
| 120 Hz | 518 |

**但相位是連續的**（用 `uptimeMillis`），所以**看起來一樣快**——只是更平滑。

### 6.39.2 掉幀的影響

**若一幀超時**：

$$
T_{\text{frame}} > T_{\text{vsync}} \Rightarrow \text{跳過下一個 vsync}
$$

**對眨眼的影響**：

$$
\text{眨眼時長} = 64.8\ \text{ms} = 3.9\ \text{幀（60 Hz）}
$$

掉一幀 → 只剩 2.9 幀 → **可能完全看不到眨眼**。

**這是為什麼眨眼是「隱約可見」而不是「明顯」**：它在設計上就接近臨界。

### 6.39.3 為什麼不用 ValueAnimator

$$
\text{ValueAnimator} \Rightarrow \text{每幀回呼} \Rightarrow \text{配置開銷}
$$

`ValueAnimator` 會在每幀建立 `Float` 物件（裝箱），而 `onDraw` 直接算相位是零配置。

**對一個每秒 60 次呼叫的 `onDraw`**：

$$
60 \times 4\ \text{個動畫} \times 1\ \text{物件} = 240\ \text{物件/秒}
$$

**代價**：GC 壓力。而直接算相位是純運算。

**設計原則**：動畫狀態應該是**時間的純函數**，不是累積的狀態機。

---

## §6.40 補篇 C 的公式與結論

### 6.40.1 新增公式

| 編號 | 內容 | 章節 |
|---|---|---|
| F-43 | 貝茲曲線 $B(t)$ | §6.31.2 |
| F-44 | 設計座標縮放 | §6.31.1 |
| F-45 | 動畫相位 $\text{phase}(t)$ | §6.31.5 |
| F-46 | 眼睛注視分段線性 | §6.31.8 |
| F-47 | 光點上升與透明度 | §6.31.9 |
| F-48 | source-over 合成 | §6.32.5 |
| F-49 | `Paint.alpha` 乘法調變 | §6.38.3 |
| F-50 | softmax 姿態分類 | §6.34.4 |
| F-51 | 模式信心度 $s/n$ | §6.35.2 |
| F-52 | tier 升級條件 | §6.35.3 |
| F-53 | vsync 幀數 | §6.39.1 |

### 6.40.2 三個結論

**結論一：UI 層的每個常數都可以算出來，不是「看起來好」。**

| 常數 | 值 | 為什麼 |
|---|---|---|
| bloom 半徑 | 59 | $< 60$（不超出 120 空間） |
| 漂浮振幅 | 6/120 = 5% | 190dp 時 ≈ 9.5dp |
| 呼吸振幅 | 2.8% | 190dp 時 ≈ 5.3dp |
| 尾巴振幅 | 6/120 = 5% | 可見但不誇張 |
| 動畫週期 | 4320 ms | $\text{lcm}(4320, 2160)$ |
| 玻璃 fill | 13.3% | 51/255（深灰背景上的亮度） |

**結論二：姿態分類的 12 維特徵係數是「放大判別力」的手工先驗，而模型權重是學的。**

$$
\phi_i = c_i \cdot \text{raw}_i, \quad c_i \in \{2.5, 1.5, 0.9, 0.8, 0.7\}
$$

這些 $c_i$ **沒有訓練**，是為了「讓稀有訊號不被淹沒」。而 $W$ 是訓練的。**這是混合設計**，手冊誠實記錄。

**結論三：模式挖掘與路由用了兩套不同的信心度公式，這是刻意的。**

| 機制 | 公式 | 適用 |
|---|---|---|
| 路由 | $(s+\alpha)/(n+2\alpha)$ | 小樣本需要平滑 |
| 模式 | $s/n$ | 有門檻補償，不需要平滑 |

$$
\text{路由} : \text{冷啟動就要可用} \Rightarrow \text{必須平滑}
$$
$$
\text{模式} : \text{沒有足夠證據就不要用} \Rightarrow \text{寧可粗暴}
$$

**同一個系統裡兩種統計哲學，各自解決不同的問題。**

---

**第 3 部補篇 C 結束。**

> 至此，全書涵蓋：
> 導論（0）、問題脈絡（1）、總體架構（2）、17 個子系統（3）、
> 逐項解剖三篇（6.1–6.40）、橫切面（4）、演進與反思（5）、六個附錄。
