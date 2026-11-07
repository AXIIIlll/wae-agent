---
name: mobile-design
description: >-
  Mobile layout optimization skill (Sticklight / mobile-design). Enforces thumb-zone
  ergonomics, >=48dp touch targets, dynamic IME keyboard & WindowInsets avoidance,
  Bottom Sheet hierarchy, and high-performance scrolling lists on Android M3 (8dp grid).
---

# Mobile Design Skill (`Sticklight / mobile-design`)

Use this skill whenever designing, refactoring, or implementing mobile UI layouts on Android.

## Core Engineering Rules

1. **Thumb-Zone Ergonomics (大拇指黃金觸控熱區)**
   - **Primary Actions (Bottom 35%–65% of viewport)**: Place high-frequency interactive controls (primary CTA, voice trigger, command input, floating orb expansion controls) within easy one-handed thumb reach.
   - **Secondary & Telemetry Readouts (Top 0%–35% of viewport)**: Place status badges, passive indicators, and header titles in the upper viewing zone.
   - **Minimum Touch Target Size**: Every interactive element MUST have an effective hit area of at least `48dp × 48dp` (`minimumWidth`, `minimumHeight`, or touch-delegate padding) with at least `8dp` separation between adjacent targets.

2. **Material Design 3 (M3) `8dp` Spatial Grid**
   - All padding, margins, corner radii, and component heights must align to the `8dp` base grid (`8dp`, `16dp`, `24dp`, `32dp`, `48dp`, `56dp`), using `4dp` only for tight inline icon-to-label spacing.

3. **Keyboard (IME) & System Bar Avoidance (`WindowInsets`)**
   - Dynamically inset layouts using `WindowInsets.Type.statusBars()`, `WindowInsets.Type.navigationBars()`, and `WindowInsets.Type.ime()`.
   - Never allow floating panels or input boxes to be covered by the soft keyboard or overlap the top Status Bar / bottom Navigation Bar.

4. **Bottom Sheet & Progressive Disclosure**
   - Keep the primary screen focused on a single primary workflow.
   - Move diagnostic logs, secondary mode switches, and advanced visualizers into collapsible Bottom Sheets or drawers with smooth height/alpha transitions.
