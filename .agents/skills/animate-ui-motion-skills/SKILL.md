---
name: animate-ui-motion-skills
description: >-
  Hardware-accelerated mobile transitions and container transforms (Animate UI / animate-ui
  & iart-ai / motion-skills). Covers GPU RenderNode properties, scroll-driven effects, and
  seamless Floating Orb <-> Expanded Capsule shared container transitions.
---

# Animate UI & Motion Skills (`Animate UI / animate-ui` & `iart-ai / motion-skills`)

Use this skill for advanced mobile transitions, hardware-accelerated view animations, and seamless container morphing.

## Core Engineering Rules

1. **GPU Hardware Acceleration Only (硬體加速屬性原則)**
   - Animate `translationX`, `translationY`, `scaleX`, `scaleY`, and `alpha` via `ViewPropertyAnimator` with `.withLayer()` so animations run on the RenderThread at 60/120fps without triggering expensive measure/layout passes on every frame.

2. **Seamless Container Transform (共享容器無縫展開與收合)**
   - Never abruptly toggle `visibility = View.GONE` / `View.VISIBLE` between related states (such as the Floating AI Orb and the Expanded Command Capsule, or the collapsible Advanced Drawer).
   - **Orb ➔ Capsule Expansion**:
     - Scale the capsule from `(scaleX = 0.78f, scaleY = 0.78f, alpha = 0f, translationY = -12dp)` to `(1f, 1f, 1f, 0dp)` over `240ms` using `PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)` with `pivotX` anchored to the orb's edge.
   - **Capsule ➔ Orb Collapse**:
     - Animate the capsule down to `(scaleX = 0.82f, scaleY = 0.82f, alpha = 0f)` over `160ms` before hiding, while popping the orb back in from `scale = 0.6f -> 1.0f` with `OvershootInterpolator(1.4f)`.

3. **Staggered Entrance & Scroll Choreography (階層式入場與捲動聯動)**
   - Stagger primary cards on screen entrance by `35ms–45ms` per card (`translationY = 18dp -> 0dp`, `alpha = 0f -> 1f`, `240ms` M3 Emphasized Decelerate).
   - Animate horizontal chip rows and drawer expansions with smooth translation + opacity reveal.
