---
name: avoid-ai-design
description: >-
  Anti-AI-slop UI refactoring skill (funboy322 / avoid-ai-design). Eliminates generic
  AI-generated layout cliches, excessive neon borders, emoji clutter, and flat symmetry,
  replacing them with bespoke typography hierarchy, tonal surface elevation, and M3 8dp rhythm.
---

# Avoid AI Design Skill (`funboy322 / avoid-ai-design`)

Use this skill to audit and refactor UI code so it looks crafted by a senior human product designer rather than a generic AI template.

## Anti-AI-Slop Checklist & Rules

1. **Eliminate Generic AI Visual Cliches (拒絕廉價 AI 罐頭感)**
   - **No Neon/Rainbow Border Soup**: Never put thick bright cyan/purple/green borders (`2dp+` high-saturation strokes) on every card. Instead, build depth through **Tonal Surface Elevation** (`#090D14` Background ➔ `#111722` Surface Container Low ➔ `#182030` Surface Container High) with subtle `1dp` low-opacity hairline borders (`#1FFFFFFF` or `#26334D`).
   - **No Emoji Spam on Every Label**: Do not prefix every single sentence, button, and sub-label with random emojis. Use clean typographic badges, status dots, or restrained semantic icons.
   - **Break Monotonous Equal-Width Button Grids**: Avoid stacking 4–5 full-width colorful buttons of identical height in a single card. Establish **1 unmistakable Primary Action** (filled high-contrast surface), **Secondary Tonal Actions** (muted container fill), and **Tertiary/Ghost Controls** (subtle text/outline).

2. **Bespoke Typography & Visual Hierarchy (高訂字體階層與留白韻律)**
   - **4-Tier Type Scale**:
     - **Headline / Hero Title**: `20sp–22sp`, Bold (`w700`), tight letter-spacing (`-0.01em`), primary high-contrast ink (`#F8FAFC`).
     - **Section Title / Card Header**: `14sp–15sp`, SemiBold (`w600`), subtle tracking (`0.02em`), accent or crisp white.
     - **Body / Control Copy**: `13sp–14sp`, Regular/Medium, comfortable line height (`1.35x`), secondary ink (`#CBD5E1`).
     - **Monospace Telemetry / Metadata**: `11sp`, `Typeface.MONOSPACE`, muted slate ink (`#94A3B8`) for latency, frame sequence, and coordinates.
   - **Asymmetric & Intentional Whitespace**: Use generous outer padding (`20dp–24dp`) and tight internal grouping (`8dp` within a group, `16dp–24dp` between distinct functional groups).
