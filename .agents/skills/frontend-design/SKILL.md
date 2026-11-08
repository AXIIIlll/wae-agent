---
name: frontend-design
description: >-
  Intentional Frontend Design & Micro-Polish Skill (anthropics/skills/frontend-design + jakubkrehel/skills/better-interface).
  Guides HTML prototyping and mobile UI design with token-driven hierarchy, concentric border radii, optical alignment,
  tabular numbers, and strict anti-clutter minimalism (zero extra persistent buttons).
---

# Frontend Design & Interface Polish Skill (`anthropics/skills` + `jakubkrehel/skills`)

Use this skill when prototyping interfaces in HTML or implementing production mobile UI so the result feels calm, mass-market friendly, and meticulously engineered.

## 1. Intentional Aesthetic & Anti-Clutter Architecture (`anthropics/skills/frontend-design`)
- **Zero-Button-Bloat Default State**: Never crowd the viewport with grids of feature buttons, sidebars, or floating toolbars. A consumer chat assistant should present only **2 persistent zones**:
  1. **Conversation Canvas** (with transient Empty-State starter chips that vanish once the first turn begins).
  2. **Unified Bottom Input Bar** (single text field + unified mic/send trigger in the thumb zone).
- **Progressive Disclosure via Inline Cards**: Surface capabilities contextually inside the conversation stream (structured Note/Agenda/Notification cards, inline single-CTA Permission bubble) rather than permanent chrome.
- **Tonal Surface Tokens**:
  - Canvas Background: `#0B0F17` (Dark) / `#F8FAFC` (Light)
  - Elevated Card Surface: `#131B2E` (Dark) / `#FFFFFF` (Light)
  - Subtle Hairline Border: `1px solid rgba(255, 255, 255, 0.07)` (Dark) / `1px solid rgba(15, 23, 42, 0.08)` (Light)
  - Primary Accent: `#3B82F6` (Calm Cobalt)
  - Ephemeral Privacy Badge Ink: `#10B981` (Muted Emerald) or `#94A3B8` (Slate)

## 2. Design Engineering Micro-Polish (`jakubkrehel/skills/better-interface`)
- **Concentric Border Radii**: Outer container radius $R_{\text{outer}}$ must equal inner element radius plus padding ($R_{\text{outer}} = R_{\text{inner}} + P$). For example, a card with `rounded-2xl` (`16px`) and `p-2` (`8px`) should use `rounded-lg` (`8px`) for inner pills/buttons.
- **Tabular Numerals**: Always apply `font-variant-numeric: tabular-nums` (`tabular-nums`) to timestamps, countdowns (e.g., `7天後自動銷毀`), phone numbers, and storage/RAM percentages.
- **Optical Alignment**: Icons paired with text must match stroke weight (`1.75px`–`2px`) and optical center; play/send/arrow icons inside circular buttons should shift `1px` toward their visual direction.
- **Minimum Floor & Touch Ergonomics**: Never use font sizes below `12px` (`text-xs`), and ensure all interactive pills/buttons maintain comfortable tap padding (`>= 44px–48px` height on mobile).
