---
name: unslop
description: >-
  Surgical UI Unslop Skill (heydamir/unslop + JCarterJohnson/unslop-ui + Microck/ui-unslop).
  Audits and strips away AI-generated "vibecoded" design tells: meaningless green status dots,
  redundant tech badges ("AES-256", "Offline"), decorative empty-state icons, filler microcopy,
  purple/neon glows, and transition-all bloat.
---

# UI Unslop Skill (`heydamir/unslop`)

Use this skill to perform a surgical multi-pass audit on any HTML prototype or Android UI code, stripping out every algorithmic "AI tell" until only essential, human-grade utility remains.

## 1. The Slop Tells Checklist (MUST PURGE)
1. **Fake Status Dots & Telemetry Pills**:
   - NEVER add a meaningless green dot (`bg-emerald-400 rounded-full`) next to a title just to make it "look like an AI app".
   - NEVER add self-congratulatory technical badges (`本機離線`, `AES-256 加密`, `AI Powered`, `可直撥`) that normal users do not care about.
2. **Decorative Empty-State Clutter**:
   - NEVER put a random floating icon inside a rounded square in the center of an empty state.
   - NEVER add verbose instructional subtitles explaining how buttons work (e.g., *"點選下方範例，發送後會自動收起"* or *"試試看"*). Users already know they can tap a chip.
3. **Glows, Gradients & Border Soup**:
   - NEVER use purple-to-blue gradients, glowing halos, or colored alert borders (`border-amber-500/30`) where a quiet neutral surface works.
4. **Filler Copy & Sycophantic Labels**:
   - Strip words down to the bare minimum. Instead of `"最近的筆記（共 2 則）"`, use `"筆記"` or just show the list directly. Instead of `"私人內容・僅顯示於畫面，不寫入歷史檔案"`, use a quiet `"不留存紀錄"`.
5. **Unbounded `transition-all`**:
   - Replace `transition-all` with specific property transitions (`transition-colors`, `transition-opacity`) or instant state changes.

## 2. The Unslop Pass Workflow
- **Pass 1 — Subtraction (減法審計)**: Question every header, dot, badge, subtitle, and wrapper div. If deleting it does not prevent the user from completing their task, **delete it**.
- **Pass 2 — Alignment & Spacing**: Left-align structural text and lists. Keep spacing strictly on the `4 / 8 / 16 / 24` scale.
- **Pass 3 — Contrast & Typography**: Rely on font weight (`400` vs `600`) and neutral ink hierarchy (`#F4F4F5` primary, `#A1A1AA` secondary, `#71717A` muted) on a calm neutral canvas (`#09090B` / `#18181B`) rather than colored badges.
