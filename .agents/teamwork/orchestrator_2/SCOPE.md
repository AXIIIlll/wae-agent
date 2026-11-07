# Scope: Android AI Automation System Optimizations & Detail Refinements (R1-R8)

## Architecture
The system enhances the on-device AI accessibility automation engine (`com.example.newandroidapp`) with 8 production-grade optimizations across perception, control, gestures, memory, and safety.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Smart Loading Wait | Detect ProgressBar, loading spinners, skeleton screens, 400ms~800ms adaptive wait before screenshot/decision | M1 | R1 |
| F2 | IME Occlusion Auto-Hide | Spatial IME occlusion detection and automatic dismissal when target buttons or results are occluded | M1 | R2 |
| F3 | Adaptive Scroll Distance | Dynamic scroll stride calculation based on list density and item heights (large-stride vs fine small-stride) | M1 | R4 |
| F4 | Slider Precision Control | Percentage coordinate calculation and tap/drag execution for SeekBar / Slider components | M2 | R5 |
| F5 | Picker Precision Control | Fine-grained directional flicking/nudging for NumberPicker date/time wheels | M2 | R5 |
| F6 | Auto-Clear Input Box | Detect existing text in input fields and clean via programmatic clear and select-all overwrite before typing | M2 | R6 |
| F7 | Rich Gesture Suite Expansion | Add LONG_PRESS, DOUBLE_TAP, SWIPE_LEFT, SWIPE_RIGHT to AiActionType, decision engine, and gesture dispatcher | M2 | R8 |
| F8 | Cross-Screen Graph Memory | GraphTransitionStep and GraphTransitionPath models in EpisodicAppMemory, navigationStack integration, fast traversal | M3 | R3 |
| F9 | Human-in-the-Loop Handover | Sensitive screen detection (CAPTCHA, bank password, biometrics, OTP), pause state machine, floating overlay alert, resume | M3 | R7 |
| F10 | Dual-Track E2E Test Suite | Comprehensive automated tests across Tiers 1-4 for all 8 features | M4 | AC |
| F11 | Physical Device ADB Live Validation | End-to-end functionality verified on physical Samsung Galaxy A55 5G (`R5CX71DTN7L`) | M4 | AC |
| F12 | Zero-Emoji Compliance & Build Sync | 100% Zero-Emoji clean, Gradle testDebugUnitTest pass, assembleDebug pass | M4 | AC |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Perception & Spatial Interaction Refinements | F1 (Smart Loading), F2 (IME Occlusion Auto-Hide), F3 (Adaptive Scroll Distance) | none | DONE |
| M2 | Controls, Input & Gesture Suite Expansion | F4 (Slider Control), F5 (Picker Control), F6 (Auto-Clear Input), F7 (Rich Gestures) | M1 | DONE |
| M3 | Cross-Screen Graph Memory & Safety Handover | F8 (Cross-Screen Memory), F9 (Human Handover & Safety State Machine) | M1, M2 | DONE |
| M4 | E2E Testing, Live ADB Validation & Build Certification | F10 (E2E Tests), F11 (Live ADB Validation), F12 (Clean Build & Zero-Emoji) | M1, M2, M3 | DONE |

## Interface Contracts
See `e:\NewAndroidApp\PROJECT.md § Interface Contracts` for detailed signatures.
