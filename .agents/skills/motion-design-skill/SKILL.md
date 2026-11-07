---
name: motion-design-skill
description: >-
  Disney animation principles for mobile micro-interactions (LottieFiles / motion-design-skill).
  Enforces 100ms-300ms tactile press/release feedback, cubic-bezier curves, and spring physics,
  strictly forbidding flat linear animations.
---

# Motion Design Skill (`LottieFiles / motion-design-skill`)

Use this skill to bring Disney's 12 Principles of Animation into Android UI micro-interactions.

## Core Motion Rules

1. **Zero Linear Interpolation (嚴禁死板線性動畫)**
   - Never use `LinearInterpolator` for UI movement, scaling, or fading.
   - Use **Material 3 Emphasized Decelerate**: `PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)` for entering/expanding elements (`220ms–280ms`).
   - Use **Material 3 Emphasized Accelerate**: `PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)` for exiting/collapsing elements (`150ms–200ms`).
   - Use **Standard Fast-Out-Slow-In**: `PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)` for state morphing (`180ms`).
   - Use **Overshoot / Spring Physics**: `OvershootInterpolator(1.35f)` or `SpringAnimation` (`dampingRatio = 0.72f`, `stiffness = 380f`) for tactile release and floating orb expansion.

2. **Tactile Press & Release Micro-Interaction (`100ms–260ms`)**
   - **Anticipation / Squash (`ACTION_DOWN`, `90ms`)**: Scale interactive buttons/cards down to `0.96f` with slight alpha shift (`0.92f`) so the surface feels physically responsive under the finger.
   - **Follow-Through & Overshoot (`ACTION_UP` / `ACTION_CANCEL`, `220ms`)**: Rebound smoothly back to `1.0f` using spring/overshoot interpolation.

3. **State Feedback Choreography (狀態轉場編排)**
   - **Loading / Listening Pulse**: Subtle breathing scale (`1.0f <-> 1.05f`) and border glow transition when voice listening or AI loop is active.
   - **Success / Error Cues**: Crisp `180ms` color-tint crossfade and micro-bounce on completion or safety gate trigger.
